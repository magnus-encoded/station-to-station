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
}
