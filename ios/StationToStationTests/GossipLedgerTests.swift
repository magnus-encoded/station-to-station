import CryptoKit
import Foundation
import XCTest
@testable import StationToStation

/// What the gossip channel remembers between one meeting and the next (#417).
///
/// The store is given a temporary file rather than the real one, which is the whole reason
/// `GossipLedger.init` takes a URL: the rules worth asserting — a Gig's signing scope never
/// rotating, a fact never being offered to the same peer twice, nothing surviving past the
/// night it is about, a peer's spend outliving the process — are rules about a file, and on
/// iOS that file has to survive the app being relaunched by the radio mid-night.
///
/// What each fact *is* and when it is refused belongs to `PublicGossipTests`; this file only
/// asserts that the disk keeps and forgets the right things.
final class GossipLedgerTests: XCTestCase {

    private let now = Date(timeIntervalSince1970: 1_788_555_600)
    private var file: URL!

    override func setUp() {
        super.setUp()
        file = FileManager.default.temporaryDirectory
            .appendingPathComponent("gossip-\(UUID().uuidString).json")
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: file)
        super.tearDown()
    }

    private struct Identity {
        let publicKey: String
        let privateKey: P256.Signing.PrivateKey
    }

    private func identity() -> Identity {
        let key = P256.Signing.PrivateKey()
        return Identity(publicKey: key.publicKey.derRepresentation.base64EncodedString(),
                        privateKey: key)
    }

    private lazy var alice = identity()
    private lazy var bob = identity()

    private func ledger() -> GossipLedger { GossipLedger(file: file) }

    func testAuthorScopePersistsWithoutDiscardingExistingLedger() async throws {
        let store = ledger()
        let scope = await store.authorScope(localGigId: "local-gig")
        XCTAssertNotNil(scope)
        XCTAssertNotEqual(scope, "local-gig")
        let again = await store.authorScope(localGigId: "local-gig")
        let other = await store.authorScope(localGigId: "other-gig")
        XCTAssertEqual(scope, again)
        XCTAssertNotEqual(scope, other)
        let reopened = ledger()
        let restored = await reopened.authorScope(localGigId: "local-gig")
        XCTAssertEqual(scope, restored)
        let retained = await reopened.authorScope(localGigId: "local-gig")
        XCTAssertEqual(scope, retained)
        await reopened.forgetAll()
        let afterContactRemoval = await ledger().authorScope(localGigId: "local-gig")
        XCTAssertEqual(scope, afterContactRemoval)
    }

    func testAdoptionAndExplicitMergePreserveSeparateSigningScopesAcrossRestart() async throws {
        let timelineFile = file.appendingPathExtension("timeline")
        defer { try? FileManager.default.removeItem(at: timelineFile) }
        let timeline = TimelineStore(file: timelineFile)
        let first = await timeline.createLocalGig(date: "04-09-2026", artist: "Band", venue: "Room")
        let second = await timeline.createLocalGig(date: "04-09-2026", artist: "Band", venue: "Room")
        let store = ledger()
        let firstScope = await store.authorScope(localGigId: first)
        let secondScope = await store.authorScope(localGigId: second)
        let a = try XCTUnwrap(firstScope)
        let b = try XCTUnwrap(secondScope)
        XCTAssertNotEqual(a, b)
        func fact(_ gig: String, scope: String, identity: Identity, text: String,
                  time: Int64, former: [String] = []) throws -> GossipEnvelope {
            try XCTUnwrap(GossipEnvelope(gigId: gig, formerIds: former, scope: scope,
                author: identity.publicKey, createdAt: time, expiresAt: 100000,
                kind: "log", line: 0, text: text).signed {
                    try? identity.privateKey.signature(for: $0).derRepresentation
                })
        }
        let original = try fact(first, scope: a, identity: alice, text: "Opener", time: 1000)
        let other = try fact(second, scope: b, identity: bob, text: "Another observation", time: 1000)
        _ = await store.receivePublic([original, other], from: "", now: 1000, local: true)
        let adopted = await timeline.adoptSetlistId(gigId: first, setlistId: "fm-1")
        XCTAssertTrue(adopted)
        let replacement = try fact("fm-1", scope: a, identity: alice, text: "Corrected", time: 1001, former: [first])
        _ = await store.receivePublic([replacement], from: "", now: 1001, local: true)
        let merged = await timeline.mergeGigs(first, second)
        XCTAssertNotNil(merged)
        let reopened = ledger()
        let restoredA = await reopened.authorScope(localGigId: first)
        let restoredB = await reopened.authorScope(localGigId: second)
        XCTAssertEqual(restoredA, a)
        XCTAssertEqual(restoredB, b)
        let state = await reopened.publicSnapshot(now: 1001)
        XCTAssertEqual(state.localAuthors.count, 2)
        XCTAssertEqual(state.facts.count, 3)
        XCTAssertEqual(state.project(gigIds: [first]).map(\.text), ["Corrected"])
        XCTAssertEqual(state.project(gigIds: ["fm-1"]).map(\.text), ["Corrected"])
        // This verifies identity retention, not rehoming the merged-away public Log.
    }

    func testAuthorScopeReadsOldLedgerAndFailsClosedWhenItCannotPersist() async throws {
        // A ledger written by the v1 pipeline: its `seen`/`held` keys are gone from
        // `StoredGossip` and are ignored on decode rather than failing the read.
        try Data(#"{"seen":{},"held":[],"budgets":{}}"#.utf8).write(to: file)
        let scope = await ledger().authorScope(localGigId: "local-gig")
        XCTAssertNotNil(scope)
        let missingDirectory = file.appendingPathComponent("missing/gossip.json")
        let unavailable = GossipLedger(file: missingDirectory)
        let failed = await unavailable.authorScope(localGigId: "local-gig")
        XCTAssertNil(failed)
    }

    func testPublicAuthorAndRadioStateSurvivesRestartAndRelayExpiry() async throws {
        let millis: Int64 = 1000
        let first = try XCTUnwrap(GossipEnvelope(gigId: "local-gig", scope: "scope",
            author: alice.publicKey, createdAt: millis, expiresAt: 100000,
            kind: "log", line: 1, text: "Karma Police").signed {
                try? self.alice.privateKey.signature(for: $0).derRepresentation
            })
        let second = try XCTUnwrap(GossipEnvelope(gigId: "local-gig", scope: "scope",
            author: alice.publicKey, createdAt: millis, expiresAt: 100000,
            kind: "log", line: 2, text: "Paranoid Android").signed {
                try? self.alice.privateKey.signature(for: $0).derRepresentation
            })
        let store = ledger()
        async let authored = store.receivePublic([first], from: "", now: millis, local: true)
        async let received = store.receivePublic([second], from: "supplier", now: millis)
        let counts = await (authored, received)
        XCTAssertEqual(counts.0, 1)
        XCTAssertEqual(counts.1, 1)
        await store.deliveredPublic([first.id], to: "recipient")
        await store.receivePublic([second], from: "duplicate", now: millis)
        var detached = await store.publicSnapshot(now: millis)
        detached.facts.removeAll()
        let reopened = ledger()
        var restored = await reopened.publicSnapshot(now: millis)
        XCTAssertEqual(restored.facts.count, 2)
        XCTAssertEqual(restored.localAuthors, Set([first.author]))
        XCTAssertNil(restored.held[second.id])
        XCTAssertTrue(restored.offer(to: "recipient", now: millis).isEmpty)
        await reopened.receivePublic([], from: "", now: 100001)
        let expired = await ledger().publicSnapshot(now: 100001)
        XCTAssertTrue(expired.held.isEmpty)
        XCTAssertTrue(expired.seen.isEmpty)
        XCTAssertEqual(expired.facts.count, 2)
        await reopened.forgetAll()
        let afterContactRemoval = await ledger().publicSnapshot(now: 100001)
        XCTAssertEqual(afterContactRemoval.facts.count, 2)
    }

    // --- Budgets ---

    func testWhatAContactHasSpentSurvivesARelaunch() async {
        guard case .admit(let spent) = gossipAdmit(GossipPeerBudget(), offered: 7, now: now) else {
            return XCTFail("the first handover should be admitted")
        }
        await ledger().spend(spent, for: bob.publicKey, now: now)

        let relaunched = await ledger().budget(for: bob.publicKey, now: now)

        XCTAssertEqual(relaunched.messagesInWindow, 7)
        XCTAssertEqual(gossipAdmit(relaunched, offered: 1, now: now.addingTimeInterval(1)), .cooling)
    }

    func testAContactNobodyHasMetInAnHourIsForgotten() async {
        let store = ledger()
        guard case .admit(let spent) = gossipAdmit(GossipPeerBudget(), offered: 7, now: now) else {
            return XCTFail("the first handover should be admitted")
        }
        await store.spend(spent, for: bob.publicKey, now: now)

        let later = now.addingTimeInterval(gossipPeerWindow + 60)

        let forgotten = await store.budget(for: bob.publicKey, now: later)
        XCTAssertEqual(forgotten, GossipPeerBudget())
    }

    // --- Forgetting ---

    /// A ledger holding nothing but transport memory is deleted outright when the last
    /// **Contact** goes. (A ledger that also holds this device's own facts keeps them — that
    /// half is asserted above, because a Gig I was at is mine and not a Contact's.)
    func testForgettingTakesEverythingWithIt() async {
        let store = ledger()
        guard case .admit(let spent) = gossipAdmit(GossipPeerBudget(), offered: 7, now: now) else {
            return XCTFail("the first handover should be admitted")
        }
        await store.spend(spent, for: bob.publicKey, now: now)

        await store.forgetAll()

        let budget = await store.budget(for: bob.publicKey, now: now)
        XCTAssertEqual(budget, GossipPeerBudget())
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.path))
    }
}
