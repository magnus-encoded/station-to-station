import CryptoKit
import Foundation
import XCTest
@testable import StationToStation

final class GossipLifecycleTests: XCTestCase {
    func testEndedGigStopsSendingWhileAnotherKeepsRadioOn() async throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let store = TimelineStore(file: file)
        let night = "04-09-2026"
        let end = try XCTUnwrap(gossipExpiry(gigDate: night))
        let done = Int64(end.timeIntervalSince1970 * 1000) - 10_800_000
        let cutoff = done + 1_800_000
        let ended = await store.createLocalGig(date: night, artist: "First", venue: "Room")
        let active = await store.createLocalGig(date: night, artist: "Second", venue: "Room")
        await store.saveAttendance(setlistId: ended, attendance: StoredAttendance(provenance: "checked_in", checkedInAt: done - 1000))
        await store.saveAttendance(setlistId: active, attendance: StoredAttendance(provenance: "checked_in", checkedInAt: done + 1000))
        await store.saveLog(setlistId: ended, log: StoredLog().completing(true, now: done))
        let cache = await store.load()
        let ends = gossipParticipationEnds(cache: cache)
        XCTAssertEqual(ends[ended], cutoff)
        XCTAssertEqual(ends[active], Int64(end.timeIntervalSince1970 * 1000))
        let key = P256.Signing.PrivateKey()
        func fact(_ gig: String) throws -> GossipEnvelope {
            try XCTUnwrap(GossipEnvelope(gigId: gig, scope: "scope", author: key.publicKey.derRepresentation.base64EncodedString(),
                createdAt: cutoff - 1000, expiresAt: Int64(end.timeIntervalSince1970 * 1000),
                kind: "log", line: 0, text: "Encore").signed { try? key.signature(for: $0).derRepresentation })
        }
        let first = try fact(ended)
        let second = try fact(active)
        var state = PublicGossipState()
        XCTAssertTrue(state.receive(first, from: "supplier", now: cutoff - 1000))
        XCTAssertTrue(state.receive(second, from: "supplier", now: cutoff - 1000))
        XCTAssertEqual(state.offer(to: "peer", now: cutoff - 1, participationEnds: ends).count, 2)
        XCTAssertEqual(state.offer(to: "peer", now: cutoff, participationEnds: ends).map(\.id), [second.id])
        XCTAssertEqual(state.held.count, 2)
        XCTAssertEqual(state.facts.count, 2)
        await store.saveLog(setlistId: ended, log: StoredLog().completing(false))
        let reopened = await store.load()
        XCTAssertEqual(state.offer(to: "peer", now: cutoff, participationEnds: gossipParticipationEnds(cache: reopened)).count, 2)
        // Restart reads exactly the same deadline policy from disk.
        let restored = await TimelineStore(file: file).load()
        XCTAssertEqual(gossipParticipationEnds(cache: restored), gossipParticipationEnds(cache: reopened))
        XCTAssertTrue(gossipParticipationEnds(cache: restored, stoppedAt: cutoff).values.allSatisfy { $0 == 0 })
    }

    func testNoAttendanceHasNoDeadlineAndEncoreDoesNotExtendCompletion() {
        var cache = TimelineCache()
        cache.gigs["gig"] = StoredGig(id: "gig", date: "04-09-2026", setlistId: "external")
        XCTAssertEqual(gossipParticipationEnds(cache: cache), ["gig": 0, "external": 0])
        let done: Int64 = 1000
        let completed = StoredLog(songs: ["Opener"]).completing(true, now: done)
        let encore = completed.adding("Encore")
        XCTAssertTrue(encore.closed)
        XCTAssertEqual(encore.completedAt, done)
        XCTAssertEqual(gossipLogChanges(before: completed, after: encore).values.sorted(), ["Encore"])
    }

    /// Story 26 and story 14, which the local-encore case above cannot reach: the encore that
    /// matters is somebody *else's*, arriving off the radio while this phone is in grace. It
    /// has to show beside the line this phone wrote, and it must not buy its sender another
    /// thirty minutes of this phone's battery.
    func testReceivedEncoreDuringGraceShowsInlineAndDoesNotExtendTheDeadline() async throws {
        let night = Night()
        let store = TimelineStore(file: night.timelineFile)
        let gigId = await store.createLocalGig(date: night.date, artist: "First", venue: "Room")
        await store.saveAttendance(setlistId: gigId,
            attendance: StoredAttendance(provenance: "checked_in", checkedInAt: night.done - 3_600_000))
        let mine = StoredLog().adding("Choke").completing(true, now: night.done)
        await store.saveLog(setlistId: gigId, log: mine)
        let checkedInCache = await store.load()
        let deadline = try XCTUnwrap(gossipActiveUntil(cache: checkedInCache))
        XCTAssertEqual(deadline, Date(timeIntervalSince1970: Double(night.done + 1_800_000) / 1000))

        // Through the ledger the radio writes to, because that is the only path by which a
        // received Envelope could reach this device's own attendance or Log at all.
        let duringGrace = night.done + 600_000
        let ledger = GossipLedger(file: night.gossipFile)
        let theirs = try night.foreignFact(gigId: gigId, line: 1, text: "Evolve", at: duringGrace)
        let admitted = await ledger.receivePublicFacts([theirs], from: "supplier", now: duringGrace)
        XCTAssertEqual(admitted.count, 1)

        // Nothing about this device's own night moved: same Log, same completion, same deadline.
        let after = await store.load()
        XCTAssertEqual(after.gigLogs[gigId], mine)
        XCTAssertEqual(gossipActiveUntil(cache: after), deadline)
        XCTAssertNotNil(gossipParticipationUntilInForce(cache: after, at: deadline.addingTimeInterval(-1)))
        XCTAssertNil(gossipParticipationUntilInForce(cache: after, at: deadline))

        // And it is visible where the set is read, beside the line this phone wrote itself.
        let state = await ledger.publicSnapshot(now: duringGrace)
        let rows = weaveGossip(base: mine.songs.map { Optional($0) },
            facts: state.project(gigIds: [gigId]))
        XCTAssertEqual(rows.compactMap(\.text).sorted(), ["Choke", "Evolve"])
        XCTAssertEqual(rows.flatMap(\.facts).map(\.text), ["Evolve"])
    }

    /// Story 30 and story 32, and story 31's iOS shape: the in-app stop in Settings runs
    /// `AppModel.stopGossip`, whose whole effect on the policy is the stop *moment* reaching
    /// `gossipActiveUntil` — the seam asserted here, with no CoreBluetooth in sight.
    ///
    /// The last two lines are the decision Android made first (#477) and iOS matches: a stop
    /// the next **Log** edit undid would not be an off switch, so reopening after a stop does
    /// not resume. It falls out of `checkedInAt > stoppedAt`, which is already the rule.
    func testStopEndsParticipationKeepsFactsAndReopeningAfterwardsDoesNotResume() async throws {
        let night = Night()
        let store = TimelineStore(file: night.timelineFile)
        let checkedIn = night.done - 3_600_000
        let gigId = await store.createLocalGig(date: night.date, artist: "First", venue: "Room")
        await store.saveAttendance(setlistId: gigId,
            attendance: StoredAttendance(provenance: "checked_in", checkedInAt: checkedIn))
        let checkedInCache = await store.load()
        XCTAssertEqual(gossipActiveUntil(cache: checkedInCache), night.end)

        let ledger = GossipLedger(file: night.gossipFile)
        let fact = try night.foreignFact(gigId: gigId, line: 0, text: "Qué Más Quieres", at: checkedIn + 60_000)
        let admitted = await ledger.receivePublicFacts([fact], from: "supplier", now: checkedIn + 60_000)
        XCTAssertEqual(admitted.count, 1)

        let stoppedAt = checkedIn + 120_000
        XCTAssertNil(gossipActiveUntil(cache: checkedInCache, stoppedAt: stoppedAt))

        // Stopping the radio never deletes what the night already brought in.
        let state = await ledger.publicSnapshot(now: stoppedAt)
        XCTAssertEqual(state.project(gigIds: [gigId]).map(\.text), ["Qué Más Quieres"])

        await store.saveLog(setlistId: gigId, log: StoredLog().adding("Choke").completing(true, now: night.done))
        await store.saveLog(setlistId: gigId, log: StoredLog().adding("Choke").completing(false))
        let reopened = await store.load()
        XCTAssertNil(gossipActiveUntil(cache: reopened, stoppedAt: stoppedAt))
        // Only because of the stop: without it, that same reopened Log runs to the night's end.
        XCTAssertEqual(gossipActiveUntil(cache: reopened), night.end)
    }

    /// Story 33, and the reason it holds: the grace deadline is never stored. It is derived on
    /// every read from the check-in, the **Log**'s completion and the night's end, so a restart
    /// recomputes the same instant rather than restoring it, and a crash cannot lose a timer
    /// that does not exist. `GossipTransport`'s `gossip.participationUntil` default is a cache
    /// of this value for the launch path, written from it and overwritten by it.
    func testGraceDeadlineAndHeldFactsSurviveARestart() async throws {
        let night = Night()
        let expected = Date(timeIntervalSince1970: Double(night.done + 1_800_000) / 1000)
        let gigId: String
        do {
            let store = TimelineStore(file: night.timelineFile)
            gigId = await store.createLocalGig(date: night.date, artist: "First", venue: "Room")
            await store.saveAttendance(setlistId: gigId,
                attendance: StoredAttendance(provenance: "checked_in", checkedInAt: night.done - 3_600_000))
            await store.saveLog(setlistId: gigId, log: StoredLog().adding("Choke").completing(true, now: night.done))
            let ledger = GossipLedger(file: night.gossipFile)
            let at = night.done + 60_000
            let fact = try night.foreignFact(gigId: gigId, line: 1, text: "Evolve", at: at)
            let admitted = await ledger.receivePublicFacts([fact], from: "supplier", now: at)
            XCTAssertEqual(admitted.count, 1)
            let first = await store.load()
            XCTAssertEqual(gossipActiveUntil(cache: first), expected)
        }

        // Nothing of the first run is alive: both stores are reopened off disk.
        let cache = await TimelineStore(file: night.timelineFile).load()
        XCTAssertEqual(gossipActiveUntil(cache: cache), expected)
        XCTAssertNotNil(gossipParticipationUntilInForce(cache: cache, at: expected.addingTimeInterval(-1)))
        XCTAssertNil(gossipParticipationUntilInForce(cache: cache, at: expected))

        let state = await GossipLedger(file: night.gossipFile).publicSnapshot(now: night.done + 120_000)
        XCTAssertEqual(state.project(gigIds: [gigId]).map(\.text), ["Evolve"])
        XCTAssertEqual(state.held.count, 1)
    }

    /// The deadline if it has not already passed at `at` — how the radio reads it, and the
    /// only way to assert the 30-minute and 06:00 edges without a clock to move.
    private func gossipParticipationUntilInForce(cache: TimelineCache, at: Date, stoppedAt: Int64 = 0) -> Date? {
        gossipActiveUntil(cache: cache, stoppedAt: stoppedAt).flatMap { at < $0 ? $0 : nil }
    }

    /// One night's worth of scaffolding: a temporary timeline and ledger that clean themselves
    /// up, the night's 06:00 end, and a **Fact** somebody else signed about a Gig on it.
    private final class Night {
        let date = "04-09-2026"
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let key = P256.Signing.PrivateKey()
        let end: Date
        /// Three hours before the night ends, so the 30-minute grace lands well inside it.
        let done: Int64

        var timelineFile: URL { directory.appendingPathComponent("timeline.json") }
        var gossipFile: URL { directory.appendingPathComponent("gossip.json") }

        init() {
            try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            end = gossipExpiry(gigDate: date)!
            done = Int64(end.timeIntervalSince1970 * 1000) - 10_800_000
        }

        deinit { try? FileManager.default.removeItem(at: directory) }

        func foreignFact(gigId: String, line: Int, text: String, at: Int64) throws -> GossipEnvelope {
            try XCTUnwrap(GossipEnvelope(gigId: gigId, scope: "theirs",
                author: key.publicKey.derRepresentation.base64EncodedString(),
                createdAt: at, expiresAt: Int64(end.timeIntervalSince1970 * 1000),
                kind: "log", line: line, text: text)
                .signed { try? key.signature(for: $0).derRepresentation })
        }
    }
}
