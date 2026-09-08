import CryptoKit
import Foundation
import XCTest
@testable import StationToStation

/// What the gossip channel remembers between one meeting and the next (#417).
///
/// The store is given a temporary file rather than the real one, which is the whole reason
/// `GossipLedger.init` takes a URL: the rules worth asserting — never offer the same message
/// to the same **Contact** twice, never echo one back to whoever handed it over, never keep
/// anything past the night it is about — are rules about a file, and on iOS that file has to
/// survive the app being relaunched by the radio mid-night.
final class GossipLedgerTests: XCTestCase {

    private let now = Date(timeIntervalSince1970: 1_788_555_600)
    private var tonight: Date { now.addingTimeInterval(8 * 3600) }
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
    private lazy var carol = identity()

    private func signed(by author: Identity, gigId: String = "3ba1f9ca",
                        expiresAt: Date? = nil) -> GossipCheckIn {
        var message = GossipCheckIn(messageId: "", gigId: gigId, checkedInBy: author.publicKey,
                                    checkedInAt: now.addingTimeInterval(-600),
                                    expiresAt: expiresAt ?? tonight, signature: "")
        guard let payload = gossipPayload(message),
              let signature = signChallenge(payload, privateKey: author.privateKey),
              let messageId = gossipMessageId(message)
        else {
            XCTFail("could not sign the fixture")
            return message
        }
        message.messageId = messageId
        message.signature = signature.base64EncodedString()
        return message
    }

    private func ledger() -> GossipLedger { GossipLedger(file: file) }

    // --- My own arrival ---

    func testMyOwnCheckInIsHeldForOnwardRelayButNeverOfferedBackToMe() async {
        let store = ledger()
        let mine = signed(by: alice)

        await store.hold(mine, now: now)

        let toBob = await store.offer(to: bob.publicKey, now: now)
        let toMyself = await store.offer(to: alice.publicKey, now: now)
        XCTAssertEqual(toBob, [mine])
        XCTAssertEqual(toMyself, [])
    }

    /// The store-and-forward decision `gossipStormGate` leaves to the transport, asserted:
    /// a message is offered every time this device meets somebody who has not had it, not
    /// once at the moment it arrived.
    func testAHeldMessageIsStillOfferedAtTheNextMeetingHoursLater() async {
        let store = ledger()
        await store.hold(signed(by: alice), now: now)

        let later = await store.offer(to: bob.publicKey, now: now.addingTimeInterval(6 * 3600))

        XCTAssertEqual(later.count, 1)
    }

    // --- The gate's decision, kept ---

    func testWhatTheGateAcceptedIsHeldAndWhatItSawIsRemembered() async {
        let store = ledger()
        let message = signed(by: alice)
        let plan = gossipStormGate(seen: [:], batch: [message], from: bob.publicKey, now: now,
                                   contacts: [alice.publicKey, bob.publicKey, carol.publicKey])

        await store.record(plan, from: bob.publicKey, now: now)

        let seen = await store.seen(now: now)
        let toCarol = await store.offer(to: carol.publicKey, now: now)
        XCTAssertEqual(seen[message.messageId], message.expiresAt)
        XCTAssertEqual(toCarol, [message])
    }

    /// Never back to the peer that handed it over, and never to its own author. Both survive
    /// being written to disk, which is the point of storing them rather than the gate's
    /// audience list.
    func testAMessageIsNeverOfferedBackToItsCarrierOrItsAuthor() async {
        let store = ledger()
        let message = signed(by: alice)
        let plan = gossipStormGate(seen: [:], batch: [message], from: bob.publicKey, now: now,
                                   contacts: [alice.publicKey, bob.publicKey, carol.publicKey])

        await store.record(plan, from: bob.publicKey, now: now)

        let toBob = await store.offer(to: bob.publicKey, now: now)
        let toAlice = await store.offer(to: alice.publicKey, now: now)
        XCTAssertEqual(toBob, [])
        XCTAssertEqual(toAlice, [])
    }

    func testTheSeenSetSurvivesTheProcessDyingBetweenMeetings() async {
        let message = signed(by: alice)
        let plan = gossipStormGate(seen: [:], batch: [message], from: bob.publicKey, now: now,
                                   contacts: [alice.publicKey, bob.publicKey])
        await ledger().record(plan, from: bob.publicKey, now: now)

        // A whole new actor over the same file: what iOS does every time it relaunches this
        // app into the background.
        let relaunched = await ledger().seen(now: now)

        XCTAssertEqual(relaunched[message.messageId], message.expiresAt)
    }

    /// The storm the gate is named for, arriving through the back door: without a seen set
    /// that outlives the process, the same check-in is accepted and re-relayed all night.
    func testAMessageAlreadySeenIsRejectedAfterARelaunch() async {
        let message = signed(by: alice)
        let contacts: Set<String> = [alice.publicKey, bob.publicKey]
        let first = gossipStormGate(seen: [:], batch: [message], from: bob.publicKey, now: now,
                                    contacts: contacts)
        await ledger().record(first, from: bob.publicKey, now: now)

        let seen = await ledger().seen(now: now)
        let again = gossipStormGate(seen: seen, batch: [message], from: bob.publicKey, now: now,
                                    contacts: contacts)

        XCTAssertEqual(again.accepted, [])
        XCTAssertEqual(again.rejected, [GossipRejected(messageId: message.messageId,
                                                       reason: .alreadySeen)])
    }

    // --- Delivery ---

    func testAMessageIsNotOfferedTwiceToTheSameContact() async {
        let store = ledger()
        let message = signed(by: alice)
        await store.hold(message, now: now)

        await store.delivered([message.messageId], to: bob.publicKey)

        let toBob = await store.offer(to: bob.publicKey, now: now)
        let toCarol = await store.offer(to: carol.publicKey, now: now)
        XCTAssertEqual(toBob, [])
        XCTAssertEqual(toCarol, [message])
    }

    /// A handover that died halfway is a handover to try again next time these two phones are
    /// in the same room — which on iOS is the common case, not the exception.
    func testAHandoverThatWasNeverConfirmedIsOfferedAgain() async {
        let store = ledger()
        let message = signed(by: alice)
        await store.hold(message, now: now)

        _ = await store.offer(to: bob.publicKey, now: now)

        let again = await store.offer(to: bob.publicKey, now: now)
        XCTAssertEqual(again, [message])
    }

    // --- Expiry, and how little is kept ---

    func testNothingIsKeptPastTheNightItIsAbout() async {
        let store = ledger()
        let message = signed(by: alice, expiresAt: now.addingTimeInterval(60))
        await store.hold(message, now: now)

        let afterwards = now.addingTimeInterval(120)
        let held = await store.offer(to: bob.publicKey, now: afterwards)
        let seen = await store.seen(now: afterwards)
        XCTAssertEqual(held, [])
        XCTAssertEqual(seen, [:])
    }

    func testAnOfferIsBoundedByWhatTheFarEndWillAcceptInOneBatch() async {
        let store = ledger()
        for index in 0..<(gossipMaxBatch + 5) {
            await store.hold(signed(by: alice, gigId: "gig\(index)"), now: now)
        }

        let offer = await store.offer(to: bob.publicKey, now: now)

        XCTAssertEqual(offer.count, gossipMaxBatch)
    }

    /// Truncation drops the messages that had least night left, not an arbitrary tail.
    func testAnOfferKeepsTheMessagesWithTheMostNightLeft() async {
        let store = ledger()
        let soon = signed(by: alice, gigId: "soon", expiresAt: now.addingTimeInterval(60))
        let late = signed(by: alice, gigId: "late", expiresAt: tonight)
        await store.hold(soon, now: now)
        await store.hold(late, now: now)

        let offer = await store.offer(to: bob.publicKey, now: now)

        XCTAssertEqual(offer.first, late)
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
        _ = await store.seen(now: later)

        let forgotten = await store.budget(for: bob.publicKey, now: later)
        XCTAssertEqual(forgotten, GossipPeerBudget())
    }

    // --- Forgetting ---

    func testForgettingTakesEverythingWithIt() async {
        let store = ledger()
        await store.hold(signed(by: alice), now: now)

        await store.forgetAll()

        let held = await store.offer(to: bob.publicKey, now: now)
        XCTAssertEqual(held, [])
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.path))
    }
}
