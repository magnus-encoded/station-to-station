import XCTest
import CryptoKit
@testable import StationToStation

/// The iOS half of #497 — the twin of Android's `GossipUpdateTest` (#496). The two suites
/// assert the same wire format and the same rule, because an **Update** authored on one
/// platform is admitted and projected on the other.
final class GossipUpdateTests: XCTestCase {

    private func fact(_ key: P256.Signing.PrivateKey, gig: String, kind: String, at: Int64,
                      former: [String] = [], text: String = "", scope: String = "night") throws -> GossipEnvelope {
        let draft = GossipEnvelope(gigId: gig, formerIds: former, scope: scope,
            author: key.publicKey.derRepresentation.base64EncodedString(),
            createdAt: at, expiresAt: 100_000, kind: kind, text: text)
        return try XCTUnwrap(draft.signed { try? key.signature(for: $0).derRepresentation })
    }

    /// A night checked into under a local id, witnessed, then catalogued mid-**Gossip**.
    ///
    /// The receiver never sees a **Log** line: the **Update** alone is what carries the
    /// witnessed **Check-in** onto the setlist.fm id, and it survives the restart that a
    /// relaunch makes the normal case on iOS.
    func testSignedUpdateInAPassProjectsTheWitnessAfterRestartWithoutALogLine() throws {
        let alice = P256.Signing.PrivateKey()
        let bob = P256.Signing.PrivateKey()
        let request = try fact(alice, gig: "local-gig", kind: "request", at: 1000)
        let bobClaim = try fact(bob, gig: "local-gig", kind: "request", at: 1100, scope: "bob-night")
        let witness = try XCTUnwrap(witnessRequest(request, with: bobClaim, now: 1200) {
            try? bob.signature(for: $0).derRepresentation
        })
        let update = try fact(alice, gig: "fm-gig", kind: "update", at: 1300, former: ["local-gig"])
        XCTAssertTrue(update.valid())
        // The only in-repo anchor for byte parity with Android: the record round-trips whole.
        XCTAssertEqual(decodePublicEnvelope(update.record()), update)

        let bytes = try XCTUnwrap(encodePublicGossipPass(
            PublicGossipPass(from: "relay", proof: "proof", batch: passBatch([update], request: nil, signer: "relay"))))
        XCTAssertEqual(decodePublicGossipPass(bytes)?.batch, [update])

        var sender = PublicGossipState()
        XCTAssertTrue(sender.receive(request, from: "", now: 1400, local: true))
        XCTAssertTrue(sender.receive(witness, from: bobClaim.author, now: 1401))
        XCTAssertTrue(sender.receive(update, from: "", now: 1402, local: true))

        var receiver = PublicGossipState()
        XCTAssertTrue(receiver.receive(request, from: request.author, now: 1400))
        XCTAssertTrue(receiver.receive(witness, from: bobClaim.author, now: 1401))
        XCTAssertTrue(receiver.receive(update, from: "relay", now: 1402))
        // Repeat adoption is one **Fact**, not two: the replay is refused.
        XCTAssertFalse(receiver.receive(update, from: "relay", now: 1403))

        let restored = try JSONDecoder().decode(PublicGossipState.self, from: JSONEncoder().encode(receiver))
        let evidence = restored.checkInEvidence(gigIds: ["fm-gig"], author: request.author)
        XCTAssertTrue(evidence.asserted)
        XCTAssertTrue(evidence.witnessed)
        XCTAssertEqual(restored.arrivals(gigIds: ["fm-gig"]), [request])
        XCTAssertTrue(restored.project(gigIds: ["fm-gig"]).contains(witness))
        XCTAssertTrue(restored.project(gigIds: ["fm-gig"]).allSatisfy { $0.kind != "log" })
        XCTAssertEqual(sender.witnessedGigIds(), ["local-gig", "fm-gig"])
        XCTAssertEqual(sender.facts.values.filter { $0.kind == "update" }.count, 1)
    }

    /// State written before **Update** existed still decodes, and adopting afterwards links it.
    func testStateFromBeforeUpdatesExistedDecodesAndStillTakesTheLink() throws {
        let alice = P256.Signing.PrivateKey()
        let request = try fact(alice, gig: "local-gig", kind: "request", at: 1000)
        var before = PublicGossipState()
        XCTAssertTrue(before.receive(request, from: "", now: 1100, local: true))
        var json = try JSONSerialization.jsonObject(with: JSONEncoder().encode(before)) as! [String: Any]
        json.removeValue(forKey: "contactNames")
        var restored = try JSONDecoder().decode(PublicGossipState.self,
            from: JSONSerialization.data(withJSONObject: json))
        XCTAssertFalse(restored.checkInEvidence(gigIds: ["fm-gig"], author: request.author).asserted)
        let update = try fact(alice, gig: "fm-gig", kind: "update", at: 1200, former: ["local-gig"])
        XCTAssertTrue(restored.receive(update, from: "", now: 1300, local: true))
        XCTAssertTrue(restored.checkInEvidence(gigIds: ["fm-gig"], author: request.author).asserted)
    }

    /// The rule in one case: an **Update** relabels its own author's **Facts** and no others.
    func testAnotherAuthorsUpdateCannotRelabelAClaimOrItsWitness() throws {
        let alice = P256.Signing.PrivateKey()
        let bob = P256.Signing.PrivateKey()
        let mallory = P256.Signing.PrivateKey()
        let request = try fact(alice, gig: "local-gig", kind: "request", at: 1000)
        let bobClaim = try fact(bob, gig: "local-gig", kind: "request", at: 1100, scope: "bob-night")
        let witness = try XCTUnwrap(witnessRequest(request, with: bobClaim, now: 1200) {
            try? bob.signature(for: $0).derRepresentation
        })
        let forged = try fact(mallory, gig: "fm-gig", kind: "update", at: 1300, former: ["local-gig"])

        var state = PublicGossipState()
        XCTAssertTrue(state.receive(request, from: request.author, now: 1400))
        XCTAssertTrue(state.receive(witness, from: bobClaim.author, now: 1401))
        XCTAssertTrue(state.receive(forged, from: "relay", now: 1402))
        let evidence = state.checkInEvidence(gigIds: ["fm-gig"], author: request.author)
        XCTAssertFalse(evidence.asserted)
        XCTAssertFalse(evidence.witnessed)
        XCTAssertFalse(state.project(gigIds: ["fm-gig"]).contains(request))
        XCTAssertFalse(state.project(gigIds: ["fm-gig"]).contains(witness))
        // The original night is untouched by the forgery.
        XCTAssertTrue(state.checkInEvidence(gigIds: ["local-gig"], author: request.author).witnessed)
    }

    /// An **Update** is not a **Check-in**: no arrivals row, no presence, no **Log** line.
    func testAnUpdateIsNeitherALogLineNorAnArrival() throws {
        let alice = P256.Signing.PrivateKey()
        let update = try fact(alice, gig: "fm-gig", kind: "update", at: 1300, former: ["local-gig"])
        var state = PublicGossipState()
        XCTAssertTrue(state.receive(update, from: "relay", now: 1400))
        XCTAssertTrue(state.arrivals(gigIds: ["fm-gig"]).isEmpty)
        XCTAssertTrue(state.presenceFrom(accepted: [update], gigIds: ["fm-gig"]).isEmpty)
        let base: [String?] = ["Heroes"]
        XCTAssertTrue(weaveGossip(base: base, facts: state.project(gigIds: ["fm-gig"]))
            .allSatisfy { $0.facts.isEmpty })
    }

    /// The malformed shapes the **Storm gate** refuses, so an **Update** can never read as
    /// something a person wrote.
    func testAnUpdateCarryingTextOrALineOrNoFormerIdIsRefused() throws {
        let alice = P256.Signing.PrivateKey()
        XCTAssertFalse(try fact(alice, gig: "fm-gig", kind: "update", at: 1300).valid())
        XCTAssertFalse(try fact(alice, gig: "fm-gig", kind: "update", at: 1300,
                                former: ["local-gig"], text: "Heroes").valid())
        XCTAssertFalse(try fact(alice, gig: "fm-gig", kind: "update", at: 1300,
                                former: ["fm-gig"]).valid())
        var lined = try fact(alice, gig: "fm-gig", kind: "update", at: 1300, former: ["local-gig"])
        lined.line = 0
        XCTAssertFalse(try XCTUnwrap(lined.signed { try? alice.signature(for: $0).derRepresentation }).valid())
    }
}
