import XCTest
import CryptoKit
@testable import StationToStation

final class PublicGossipTests: XCTestCase {
    func testSharedSignedPassVerifiesAndRoundTripsExactly() throws {
        let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("fixtures/gossip/signed-pass")
        let bytes = try Data(contentsOf: dir.appendingPathComponent("pass.txt"))
        let pass = try XCTUnwrap(decodePublicGossipPass(bytes))
        XCTAssertEqual(pass.batch.count, 3)
        XCTAssertTrue(pass.batch.allSatisfy { $0.valid() })
        XCTAssertNotEqual(pass.from, pass.batch.first?.author)
        XCTAssertTrue(verifyChallenge(try Data(contentsOf: dir.appendingPathComponent("proof-payload.txt")),
                                     signature: try XCTUnwrap(Data(base64Encoded: pass.proof)),
                                     publicKeyBase64: pass.from))
        XCTAssertEqual(encodePublicGossipPass(pass), bytes)
        XCTAssertEqual(try String(contentsOf: dir.appendingPathComponent("envelope.txt"), encoding: .utf8),
                       pass.batch.first?.record())
        XCTAssertEqual(pass.batch.first?.text, "Björk — Jóga 🎵")
        XCTAssertEqual(pass.batch[1].text, "")
        XCTAssertEqual(pass.batch[2].line, -1)
        var altered = pass.batch[0]
        altered.text = "tampered"
        XCTAssertFalse(altered.valid())
    }

    private let key = P256.Signing.PrivateKey()
    func testPublicChallengeProvesTemporaryKeyAndRejectsTampering() throws {
        let nonce = Data((0..<32).map(UInt8.init))
        let author = key.publicKey.derRepresentation.base64EncodedString()
        let bytes = try XCTUnwrap(encodePublicGossipChallenge(nonce: nonce, from: author) {
            try? self.key.signature(for: $0).derRepresentation
        })
        let decoded = try XCTUnwrap(decodePublicGossipChallenge(bytes))
        XCTAssertEqual(decoded.from, author)
        XCTAssertEqual(decoded.nonce, nonce)
        let text = String(decoding: bytes, as: UTF8.self)
        XCTAssertNil(decodePublicGossipChallenge(Data(text.replacingOccurrences(
            of: nonce.base64EncodedString(), with: Data(repeating: 0, count: 32).base64EncodedString()).utf8)))
        XCTAssertNil(decodePublicGossipChallenge(Data(text.replacingOccurrences(of: "challenge/2", with: "challenge/1").utf8)))
        let proof = try XCTUnwrap(Data(base64Encoded: text.components(separatedBy: "\n").last!))
        XCTAssertFalse(verifyChallenge(publicGossipAuthPayload(nonce), signature: proof, publicKeyBase64: author))
    }
    func testOversizedPassKeepsTheBatchPrefixThatFitsItsActualHeader() throws {
        let envelope = fact(String(repeating: "x", count: 512))
        var pass = PublicGossipPass(from: String(repeating: "k", count: 256),
                                    proof: String(repeating: "s", count: 256),
                                    batch: Array(repeating: envelope, count: 64))
        let bytes = try XCTUnwrap(encodePublicGossipPass(pass))
        let decoded = try XCTUnwrap(decodePublicGossipPass(bytes))
        XCTAssertLessThanOrEqual(bytes.count, gossipMaxWireBytes)
        XCTAssertTrue((1...63).contains(decoded.batch.count))
        XCTAssertEqual(decoded.batch, Array(pass.batch.prefix(decoded.batch.count)))
        XCTAssertGreaterThan(bytes.count + envelope.record().utf8.count + 1, gossipMaxWireBytes)
        pass.from += "k"
        XCTAssertNil(encodePublicGossipPass(pass))
    }
    private func fact(_ text: String = "Karma Police", at: Int64 = 1000, kind: String = "log") -> GossipEnvelope {
        GossipEnvelope(gigId: "gig", scope: "scope", author: key.publicKey.derRepresentation.base64EncodedString(),
                       createdAt: at, expiresAt: 100000, kind: kind, line: kind == "log" ? 0 : -1, text: text)
            .signed { try? self.key.signature(for: $0).derRepresentation }!
    }
    func testOneHopControlsRequireTheirAuthorWithoutPoisoningTheStormGate() {
        for kind in ["request", "receipt"] {
            let envelope = fact("useful-neighbour", kind: kind)
            var receiver = PublicGossipState()
            XCTAssertFalse(receiver.receive(envelope, from: "blind-relay", now: 2000))
            XCTAssertTrue(receiver.seen.isEmpty)
            XCTAssertTrue(receiver.useful.isEmpty)
            XCTAssertTrue(receiver.receive(envelope, from: envelope.author, now: 2001))
            XCTAssertTrue(receiver.facts.isEmpty)
            XCTAssertTrue(receiver.held.isEmpty)
            if kind == "receipt" { XCTAssertNotNil(receiver.useful["useful-neighbour"]) }

            var author = PublicGossipState()
            XCTAssertTrue(author.receive(envelope, from: "", now: 2000, local: true))
            XCTAssertFalse(author.receive(envelope, from: "blind-relay", now: 2001))
            XCTAssertEqual(author.offer(to: "recipient", now: 2002), [envelope])
        }
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
