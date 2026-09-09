import XCTest
import CryptoKit
@testable import StationToStation

final class PublicGossipTests: XCTestCase {
    private let key = P256.Signing.PrivateKey()
    private func fact(_ text: String = "Karma Police", at: Int64 = 1000) -> GossipEnvelope {
        GossipEnvelope(gigId: "gig", scope: "scope", author: key.publicKey.derRepresentation.base64EncodedString(),
                       createdAt: at, expiresAt: 100000, kind: "log", line: 0, text: text)
            .signed { try? self.key.signature(for: $0).derRepresentation }!
    }
    func testStrangerCarriesAndSecondCopyClosesStormGate() {
        var state = PublicGossipState()
        let envelope = fact()
        XCTAssertTrue(state.receive(envelope, from: "stranger", now: 2000))
        XCTAssertEqual(state.offer(to: "another", now: 2001), [envelope])
        XCTAssertFalse(state.receive(envelope, from: "third", now: 2002))
        XCTAssertTrue(state.offer(to: "another", now: 2003).isEmpty)
        XCTAssertEqual(Array(state.facts.values), [envelope])
    }
    func testBlockedAuthorIsCarriedAndHandoffIsNotRepeated() {
        var state = PublicGossipState()
        let envelope = fact()
        state.blocked.insert(envelope.author)
        _ = state.receive(envelope, from: "Carol", now: 2000)
        XCTAssertTrue(state.facts.isEmpty)
        XCTAssertEqual(state.offer(to: "Bob", now: 2001), [envelope])
        state.delivered(to: "Bob", ids: [envelope.id])
        XCTAssertTrue(state.offer(to: "Bob", now: 2002).isEmpty)
    }
    func testReplacementsConvergeAndKeepHistoryPastExpiry() {
        var state = PublicGossipState()
        let old = fact("Karma police")
        let corrected = fact("Karma Police", at: 1100)
        _ = state.receive(corrected, from: "Carol", now: 2000)
        _ = state.receive(old, from: "Dave", now: 2001)
        XCTAssertEqual(state.project(gigIds: ["gig"]), [corrected])
        XCTAssertEqual(state.facts.count, 2)
        XCTAssertTrue(state.offer(to: "Erin", now: 100001).isEmpty)
        XCTAssertEqual(state.project(gigIds: ["gig"]), [corrected])
    }
    func testAlteredPayloadCannotCloseAnExistingStormGate() {
        var state = PublicGossipState()
        let envelope = fact()
        _ = state.receive(envelope, from: "Carol", now: 2000)
        var forged = envelope
        forged.text = "forged"
        XCTAssertFalse(state.receive(forged, from: "Dave", now: 2001))
        XCTAssertEqual(state.offer(to: "Bob", now: 2002), [envelope])
    }
}
