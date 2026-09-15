import XCTest
import CryptoKit
@testable import StationToStation

final class PublicGossipTests: XCTestCase {
    func testExchangeRecognizesEmbeddedClaimWithoutUndoingBlock() throws {
        let card = P256.Signing.PrivateKey()
        let durable = card.publicKey.derRepresentation.base64EncodedString()
        let author = key.publicKey.derRepresentation.base64EncodedString()
        let binding = Data("station-to-station/gossip-identity/2\nclaim-scope\n\(author)".utf8)
        let signature = try card.signature(for: binding).derRepresentation
        let mask = SymmetricKey(data: SHA256.hash(data: Data("station-to-station/gossip-mask/2\n\(durable)\nclaim-scope".utf8)))
        let sealed = try AES.GCM.seal(signature, using: mask)
        var draft = GossipEnvelope(gigId: "gig", scope: "claim-scope", author: author,
            createdAt: 1000, expiresAt: 100000, kind: "request")
        draft.attribution = try XCTUnwrap(sealed.combined).base64EncodedString()
        let claim = try XCTUnwrap(draft.signed { try? key.signature(for: $0).derRepresentation })
        let witness = try XCTUnwrap(GossipEnvelope(gigId: "gig", scope: "witness-scope", author: durable,
            createdAt: 1500, expiresAt: 100000, kind: "witness", text: claim.record())
            .signed { try? card.signature(for: $0).derRepresentation })
        var state = PublicGossipState()
        XCTAssertTrue(state.receive(witness, from: "relay", now: 1600))
        state.blocked.insert(author)
        state.recognizeContacts([durable])
        XCTAssertEqual(state.recognition[author], durable)
        XCTAssertTrue(state.arrivals(gigIds: ["gig"]).isEmpty)
        XCTAssertEqual(state.offer(to: "next", now: 1700), [witness])
        state.recognizeContacts([])
        XCTAssertEqual(state.recognition[author], durable)
    }

    func testAlignmentPreservesReprisesAndEveryAuthorsOrder() {
        let a = ["A", "B", "A"].enumerated().map { index, text -> GossipEnvelope in
            var item = fact(text); item.line = index; return item
        }
        let aligned = weaveGossip(base: ["A", "B", "A"], facts: Array(a.reversed()))
        XCTAssertEqual(aligned.map(\.text), ["A", "B", "A"])
        XCTAssertEqual(aligned.map(\.base), [0, 1, 2])
        XCTAssertTrue(aligned.allSatisfy { $0.facts.count == 1 })
        let disagreeing = a.prefix(2).enumerated().map { index, fact -> GossipEnvelope in
            var item = fact; item.author = "other"; item.line = 1 - index; return item
        }
        let combined = weaveGossip(base: [], facts: a + disagreeing)
        for author in [a[0].author, "other"] {
            XCTAssertEqual((a + disagreeing).filter { $0.author == author }.sorted { $0.line < $1.line }.map(\.text),
                combined.flatMap(\.facts).filter { $0.author == author }.map(\.text))
        }
        XCTAssertEqual(combined.flatMap(\.facts).count, 5)
    }

    func testDurableBlockAppliesAfterRecognitionAcrossRestartWithoutStoppingRelay() throws {
        let envelope = fact()
        var state = PublicGossipState()
        XCTAssertTrue(state.receive(envelope, from: "relay", now: 2000))
        state.blocked.insert("contact-key")
        XCTAssertEqual(state.project(gigIds: ["gig"]), [envelope])
        state.recognition[envelope.author] = "contact-key"
        var restored = try JSONDecoder().decode(PublicGossipState.self, from: JSONEncoder().encode(state))
        XCTAssertTrue(restored.project(gigIds: ["gig"]).isEmpty)
        XCTAssertEqual(restored.offer(to: "another-relay", now: 2001), [envelope])
        XCTAssertNotNil(restored.facts[envelope.id])
    }

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
            let envelope = fact(kind == "receipt" ? "useful-neighbour" : "", kind: kind)
            var receiver = PublicGossipState()
            XCTAssertFalse(receiver.receive(envelope, from: "blind-relay", now: 2000))
            XCTAssertTrue(receiver.seen.isEmpty)
            XCTAssertTrue(receiver.useful.isEmpty)
            XCTAssertTrue(receiver.receive(envelope, from: envelope.author, now: 2001))
            if kind == "request" { XCTAssertEqual(Array(receiver.facts.values), [envelope]) }
            else { XCTAssertTrue(receiver.facts.isEmpty) }
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

    func testDirectRequestPersistsAndWitnessEvidenceStaysSeparate() throws {
        let witnessKey = P256.Signing.PrivateKey()
        func request(_ signingKey: P256.Signing.PrivateKey, scope: String) -> GossipEnvelope {
            GossipEnvelope(gigId: "gig", scope: scope,
                author: signingKey.publicKey.derRepresentation.base64EncodedString(),
                createdAt: 1000, expiresAt: 100000, kind: "request")
                .signed { try? signingKey.signature(for: $0).derRepresentation }!
        }
        let claim = request(key, scope: "claim-scope")
        let local = request(witnessKey, scope: "witness-scope")
        var state = PublicGossipState()
        XCTAssertTrue(state.receive(local, from: "", now: 1500, local: true))
        XCTAssertFalse(state.receive(claim, from: "blind-relay", now: 1600))
        XCTAssertTrue(state.receive(claim, from: claim.author, now: 1601))
        XCTAssertEqual(state.checkInEvidence(gigIds: ["gig"], author: claim.author).witnessed, false)
        let witness = try XCTUnwrap(witnessRequest(claim, with: local, now: 1700) {
            try? witnessKey.signature(for: $0).derRepresentation
        })
        XCTAssertTrue(state.receive(witness, from: witness.author, now: 1701))
        let evidence = state.checkInEvidence(gigIds: ["gig"], author: claim.author)
        XCTAssertTrue(evidence.asserted)
        XCTAssertTrue(evidence.witnessed)
        XCTAssertNotNil(state.facts[claim.id])
        XCTAssertNotNil(state.facts[witness.id])
        XCTAssertNil(state.held[claim.id])
        XCTAssertNotNil(state.held[witness.id])
        // This device witnessed someone else. Its own night is not witnessed by that.
        XCTAssertEqual(state.witnessedGigIds(), [])
        var remote = PublicGossipState()
        XCTAssertTrue(remote.receive(witness, from: "blind-relay", now: 1800))
        XCTAssertEqual(remote.arrivals(gigIds: ["gig"]), [claim])
        XCTAssertTrue(remote.arrivals(gigIds: ["unrelated-gig"]).isEmpty)
        XCTAssertNil(remote.facts[claim.id])
        XCTAssertTrue(remote.receive(claim, from: claim.author, now: 1801))
        XCTAssertEqual(remote.arrivals(gigIds: ["gig"]), [claim])
        remote.prune(now: 100001)
        XCTAssertEqual(remote.arrivals(gigIds: ["gig"]), [claim])
        remote.blocked.insert(claim.author)
        XCTAssertTrue(remote.arrivals(gigIds: ["gig"]).isEmpty)
        XCTAssertNotNil(remote.facts[witness.id])
    }

    func testOnlyMyOwnWitnessedClaimProjects() throws {
        let strangerKey = P256.Signing.PrivateKey()
        func request(_ signingKey: P256.Signing.PrivateKey, scope: String,
                     formerIds: [String] = []) -> GossipEnvelope {
            GossipEnvelope(gigId: "gig", formerIds: formerIds, scope: scope,
                author: signingKey.publicKey.derRepresentation.base64EncodedString(),
                createdAt: 1000, expiresAt: 100000, kind: "request")
                .signed { try? signingKey.signature(for: $0).derRepresentation }!
        }
        let mine = request(key, scope: "mine", formerIds: ["local-gig"])
        var state = PublicGossipState()
        XCTAssertTrue(state.receive(mine, from: "", now: 1500, local: true))
        // Claimed, but nobody has signed for it yet.
        XCTAssertEqual(state.witnessedGigIds(), [])
        let witness = try XCTUnwrap(witnessRequest(mine, with: request(strangerKey, scope: "theirs"),
                                                   now: 1700) {
            try? strangerKey.signature(for: $0).derRepresentation
        })
        XCTAssertTrue(state.receive(witness, from: witness.author, now: 1701))
        // Both the id it carries now and the one it was known by before, so a setlist.fm
        // id arriving after the night still matches the row.
        XCTAssertEqual(state.witnessedGigIds(), ["gig", "local-gig"])
    }

    func testAPassIsSignedForTheRequestItCarriesAndCarriesNoOtherAuthorsRequest() {
        var log = fact(); log.kind = "log"
        var mine = fact(); mine.kind = "request"; mine.line = -1; mine.author = "me"
        var theirs = fact(); theirs.kind = "request"; theirs.line = -1; theirs.author = "them"
        // Nothing of mine to prove: sign as the relay, and drop a request I cannot prove
        // rather than spend the Pass on bytes the receiver is bound to reject.
        XCTAssertNil(passAuthor([log, theirs], localAuthors: ["me"]))
        XCTAssertEqual(passBatch([log, theirs], request: nil), [log])
        // Mine to prove: sign as its author. Facts still ride along under that key.
        XCTAssertEqual(passAuthor([log, mine, theirs], localAuthors: ["me"]), mine)
        XCTAssertEqual(passBatch([log, mine, theirs], request: mine), [log, mine])
    }
}
