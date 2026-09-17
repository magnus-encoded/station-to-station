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

    /// A check-in signed by a nightly Gig key, sealed to `card` when there is one to recognise.
    private func checkIn(gig: P256.Signing.PrivateKey, card: P256.Signing.PrivateKey?,
                         scope: String, at: Int64 = 1000) throws -> GossipEnvelope {
        let author = gig.publicKey.derRepresentation.base64EncodedString()
        var draft = GossipEnvelope(gigId: "gig", scope: scope, author: author,
                                   createdAt: at, expiresAt: 100000, kind: "request")
        if let card {
            let durable = card.publicKey.derRepresentation.base64EncodedString()
            let signature = try card.signature(for: gossipIdentityBinding(scope: scope, author: author)).derRepresentation
            let sealed = try AES.GCM.seal(signature, using: gossipRecognitionKey(durable: durable, scope: scope))
            draft.attribution = try XCTUnwrap(sealed.combined).base64EncodedString()
        }
        return try XCTUnwrap(draft.signed { try? gig.signature(for: $0).derRepresentation })
    }

    /// Story 38 under v2: the line only ever names somebody this phone can actually name.
    ///
    /// Three check-ins arrive in one **Pass** and all three are believed — they are equally
    /// valid **Facts** and every one of them projects. Only the recognised, unblocked
    /// **Contact** becomes presence, because attribution is the gate: the stranger's phone is
    /// a relay key until an **Exchange** says otherwise, and naming them would be inventing a
    /// person. The block is applied the way a user applies one — to the durable **Card** key,
    /// after the **Fact** was already stored unrecognised — so it is `recognition` catching up
    /// that has to drop them, not `receive` having refused them earlier.
    func testOnlyARecognisedUnblockedContactsCheckInBecomesPresence() throws {
        let contactCard = P256.Signing.PrivateKey()
        let blockedCard = P256.Signing.PrivateKey()
        let contactDurable = contactCard.publicKey.derRepresentation.base64EncodedString()
        let blockedDurable = blockedCard.publicKey.derRepresentation.base64EncodedString()
        let contact = try checkIn(gig: P256.Signing.PrivateKey(), card: contactCard, scope: "contact-scope")
        let stranger = try checkIn(gig: P256.Signing.PrivateKey(), card: nil, scope: "stranger-scope")
        let blocked = try checkIn(gig: P256.Signing.PrivateKey(), card: blockedCard, scope: "blocked-scope")

        var state = PublicGossipState()
        let accepted = [contact, stranger, blocked].filter { state.receive($0, from: $0.author, now: 1500) }
        XCTAssertEqual(accepted, [contact, stranger, blocked])
        state.blocked.insert(blockedDurable)
        state.recognizeContacts([contactDurable, blockedDurable])

        // The stranger is carried and shown like anyone else; they are simply not named.
        XCTAssertEqual(Set(state.arrivals(gigIds: ["gig"])), [contact, stranger])
        XCTAssertEqual(state.presenceFrom(accepted: accepted, gigIds: ["gig"]), [contactDurable])
        // Another night's Pass, same room: nobody here is at the Gig this phone is at.
        XCTAssertEqual(state.presenceFrom(accepted: accepted, gigIds: ["other-gig"]), [])

        // What the surface reads, on an injected clock rather than the app's.
        let stamped = Dictionary(uniqueKeysWithValues:
            state.presenceFrom(accepted: accepted, gigIds: ["gig"]).map { ($0, Date(timeIntervalSince1970: 0)) })
        XCTAssertEqual(gossipNearby(stamped, now: Date(timeIntervalSince1970: 240)), [contactDurable])
        XCTAssertTrue(gossipNearby(stamped, now: Date(timeIntervalSince1970: 360)).isEmpty)
        XCTAssertEqual(alsoHereSentence(["AmandaSvea"]), "AmandaSvea is also here")
        XCTAssertNil(alsoHereSentence([]))
        // If this inverted, a pair mid-Pass would blink out of each other's rooms while
        // actively talking.
        XCTAssertGreaterThan(gossipNearbyWindow, gossipPeerCooldown)
    }

    /// A **Contact** one hop away still counts, and this device's own claim never does.
    ///
    /// The witness is what carries the claim the last hop, so the phone that did the
    /// recognising need not have been the one in radio range — presence follows attribution,
    /// not proximity. The local claim in the same batch is the control: a phone is never
    /// "also here" to itself.
    func testWitnessCarriesAContactsCheckInIntoPresenceAndNeverThisDevicesOwn() throws {
        let card = P256.Signing.PrivateKey()
        let durable = card.publicKey.derRepresentation.base64EncodedString()
        let theirs = try checkIn(gig: P256.Signing.PrivateKey(), card: card, scope: "their-scope")
        let mineKey = P256.Signing.PrivateKey()
        let mine = try checkIn(gig: mineKey, card: nil, scope: "my-scope")
        let witness = try XCTUnwrap(GossipEnvelope(gigId: "gig", scope: "my-scope", author: mine.author,
            createdAt: 1600, expiresAt: 100000, kind: "witness", text: theirs.record())
            .signed { try? mineKey.signature(for: $0).derRepresentation })

        var state = PublicGossipState()
        XCTAssertTrue(state.receive(mine, from: "", now: 1500, local: true))
        XCTAssertTrue(state.receive(witness, from: "blind-relay", now: 1601))
        state.recognizeContacts([durable])
        XCTAssertNil(state.facts[theirs.id])
        XCTAssertEqual(state.presenceFrom(accepted: [witness, mine], gigIds: ["gig"]), [durable])

        // Blocking the Contact retires the claim the witness carries, without retiring the
        // witness's own author, who is this device.
        state.blocked.insert(durable)
        XCTAssertEqual(state.presenceFrom(accepted: [witness, mine], gigIds: ["gig"]), [])

        // Nothing here re-checks the claim a witness carries, and nothing needs to: `valid()`
        // validates a witness's inner claim recursively, so `receive` never admits a
        // validly-signed witness wrapped around a forged one.
        var forged = theirs
        forged.createdAt = 1700
        let attack = try XCTUnwrap(GossipEnvelope(gigId: "gig", scope: "my-scope", author: mine.author,
            createdAt: 1700, expiresAt: 100000, kind: "witness", text: forged.record())
            .signed { try? mineKey.signature(for: $0).derRepresentation })
        XCTAssertFalse(attack.valid())
        XCTAssertFalse(state.receive(attack, from: "blind-relay", now: 1701))
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
    private func fact(_ text: String = "Karma Police", at: Int64 = 1000, kind: String = "log",
                      line: Int = 0) -> GossipEnvelope {
        GossipEnvelope(gigId: "gig", scope: "scope", author: key.publicKey.derRepresentation.base64EncodedString(),
                       createdAt: at, expiresAt: 100000, kind: kind, line: kind == "log" ? line : -1, text: text)
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
            // Credit follows the handle the transport proved, never the one the text names:
            // a receipt from elsewhere must not be able to nominate a third party.
            if kind == "receipt" {
                XCTAssertEqual(Set(receiver.useful.keys), [envelope.author])
                XCTAssertNil(receiver.useful["useful-neighbour"])
            }

            var author = PublicGossipState()
            XCTAssertTrue(author.receive(envelope, from: "", now: 2000, local: true))
            XCTAssertFalse(author.receive(envelope, from: "blind-relay", now: 2001))
            // A receipt is addressed to the one neighbour it names; anything else is gossiped.
            let recipient = kind == "receipt" ? "useful-neighbour" : "recipient"
            XCTAssertEqual(author.offer(to: recipient, now: 2002), [envelope])
            if kind == "receipt" { XCTAssertTrue(author.offer(to: "somebody-else", now: 2002).isEmpty) }
        }
    }

    /// Stories 35 and 36: who a receipt is for, and when there is not one. Story 37 is not in
    /// here — see the comment below and ADR-0022 §3.
    func testReceiptNamesTheDeliveringNeighbourAndOnlyForAPromptlyRecognisedFact() {
        let relay = P256.Signing.PrivateKey()
        let me = relay.publicKey.derRepresentation.base64EncodedString()
        let sign: (Data) -> Data? = { try? relay.signature(for: $0).derRepresentation }
        let delivered = fact()
        // Not recognised as a Contact's at receive time, so nothing is owed. This is story 36
        // and *not* story 37: it asserts what the function does with `recognised: false`, which
        // is the argument's contract. Story 37 — that late attribution cannot reach here at all —
        // is a fact about the call graph (one production caller per platform, the receive path)
        // and no call of this function can witness it. ADR-0022 §3 says so plainly.
        XCTAssertNil(receiptFor(delivered, from: "neighbour", recognised: false, author: me, now: 2000, sign: sign))
        // A blind relay that proved no handle cannot be credited.
        XCTAssertNil(receiptFor(delivered, from: "", recognised: true, author: me, now: 2000, sign: sign))
        let receipt = receiptFor(delivered, from: "neighbour", recognised: true, author: me, now: 2000, sign: sign)
        XCTAssertNotNil(receipt)
        XCTAssertEqual(receipt?.kind, "receipt")
        XCTAssertEqual(receipt?.text, "neighbour")
        XCTAssertEqual(receipt?.author, me)
        XCTAssertEqual(receipt?.line, -1)
        XCTAssertEqual(receipt?.valid(), true)
        // Only the neighbour that delivered this Fact directly, so a receipt never begets one.
        XCTAssertNil(receiptFor(receipt!, from: "neighbour", recognised: true, author: me, now: 2000, sign: sign))
    }

    /// Stories 36, 38 and 40: where a receipt may go, and what it may not do on arrival.
    func testReceiptReachesOnlyItsNeighbourOnARelaySignedPassAndRetiresNothing() {
        let relay = P256.Signing.PrivateKey()
        let me = relay.publicKey.derRepresentation.base64EncodedString()
        let sign: (Data) -> Data? = { try? relay.signature(for: $0).derRepresentation }
        let carried = fact()
        let receipt = receiptFor(carried, from: "neighbour", recognised: true, author: me, now: 2000, sign: sign)!
        var state = PublicGossipState()
        XCTAssertTrue(state.receive(carried, from: "neighbour", now: 2000))
        XCTAssertTrue(state.receive(receipt, from: "", now: 2000, local: true))
        // Never a durable assertion, and the Fact it is about is untouched — still held and
        // still offered to everyone it has not reached.
        XCTAssertTrue(state.facts.values.allSatisfy { $0.kind != "receipt" })
        XCTAssertEqual(state.offer(to: "somebody-else", now: 2001), [carried])
        // Credit for the neighbour that delivered it, on this device, from this device's own note.
        XCTAssertEqual(state.useful["neighbour"], 2000 + publicReceiptMs)
        XCTAssertEqual(state.offer(to: "neighbour", now: 2001), [receipt])
        // The Storm gate is exactly where receiving the Fact left it: a second copy still
        // retires it, and authoring a receipt about it changed nothing.
        XCTAssertFalse(state.receive(carried, from: "third-relay", now: 2001))
        XCTAssertTrue(state.offer(to: "somebody-else", now: 2002).isEmpty)
        // It rides only a Pass signed as the key it was authored under.
        XCTAssertTrue(passBatch([receipt], request: nil, signer: "some-other-key").isEmpty)
        XCTAssertEqual(passBatch([receipt], request: nil, signer: me), [receipt])
        // And at the far end it credits its sender, never the third party its text names.
        var neighbour = PublicGossipState()
        XCTAssertTrue(neighbour.receive(receipt, from: me, now: 2002))
        XCTAssertNil(neighbour.useful["neighbour"])
        XCTAssertEqual(Set(neighbour.useful.keys), [me])
        XCTAssertTrue(neighbour.facts.isEmpty)
        XCTAssertTrue(neighbour.held.isEmpty)
    }

    /// The address namespace. A meeting only ever proves a relay key — the challenge carries
    /// nothing else — so a receipt naming the Gig key that happened to sign a Pass names
    /// something no peer will equal, and is never delivered and never read.
    func testReceiptAddressesTheRelayKeyAMeetingProvesAndNeverTheGigKeyThatSignedThePass() {
        let neighbourRelay = "neighbour-relay-key"
        let neighbourGig = "neighbour-gig-key"
        XCTAssertNotEqual(neighbourRelay, neighbourGig)
        func envelope(_ author: String, _ kind: String) -> GossipEnvelope {
            GossipEnvelope(gigId: "gig", scope: "scope", author: author, createdAt: 1000,
                           expiresAt: 100000, kind: kind, line: kind == "log" ? 0 : -1)
        }
        let log = envelope(neighbourGig, "log")

        // A Pass signed as the relay is addressable; passBatch admits no request onto one.
        XCTAssertEqual(passRelay(PublicGossipPass(from: neighbourRelay, proof: "proof", batch: [log])),
                       neighbourRelay)
        // A Pass the receiver would admit a request from was signed as a Gig, and that key is
        // not one this device can ever meet. No receipt is owed rather than an undeliverable one.
        XCTAssertNil(passRelay(PublicGossipPass(from: neighbourGig, proof: "proof",
                                                batch: [log, envelope(neighbourGig, "request")])))
        // Another device's request riding a relay-signed Pass does not make it unaddressable.
        XCTAssertEqual(passRelay(PublicGossipPass(from: neighbourRelay, proof: "proof",
                                                  batch: [envelope(neighbourGig, "request")])),
                       neighbourRelay)

        // And this is why it matters: the Gig key is unreachable at both ends of the design.
        let relay = P256.Signing.PrivateKey()
        let me = relay.publicKey.derRepresentation.base64EncodedString()
        let sign: (Data) -> Data? = { try? relay.signature(for: $0).derRepresentation }
        let carried = fact()
        let misaddressed = receiptFor(carried, from: neighbourGig, recognised: true, author: me,
                                      now: 2000, sign: sign)!
        var state = PublicGossipState()
        XCTAssertTrue(state.receive(misaddressed, from: "", now: 2000, local: true))
        // The one egress takes the key the challenge proved, so it never matches, and the
        // credit the ranker reads under that same key was never written.
        XCTAssertTrue(state.offer(to: neighbourRelay, now: 2001).isEmpty)
        XCTAssertNil(state.useful[neighbourRelay])
        // Addressed as the meeting will prove it, both halves work.
        let addressed = receiptFor(carried, from: neighbourRelay, recognised: true, author: me,
                                   now: 2000, sign: sign)!
        var correct = PublicGossipState()
        XCTAssertTrue(correct.receive(addressed, from: "", now: 2000, local: true))
        XCTAssertEqual(correct.offer(to: neighbourRelay, now: 2001), [addressed])
        XCTAssertEqual(correct.useful[neighbourRelay], 2000 + publicReceiptMs)
        XCTAssertTrue(correct.offer(to: neighbourGig, now: 2001).isEmpty)
    }

    /// A whole batch from one neighbour owes one receipt, and it survives to be offered.
    func testAMultiFactBatchFromOneNeighbourStillLeavesOneReceiptHeldAndOfferable() {
        let relay = P256.Signing.PrivateKey()
        let me = relay.publicKey.derRepresentation.base64EncodedString()
        let sign: (Data) -> Data? = { try? relay.signature(for: $0).derRepresentation }
        // Two lines of one log: same author, same gig, same scope. The ordinary case.
        let batch = [fact("Karma Police", line: 0), fact("No Surprises", line: 1)]
        XCTAssertNotEqual(batch[0].id, batch[1].id)
        var state = PublicGossipState()
        for envelope in batch { XCTAssertTrue(state.receive(envelope, from: "neighbour", now: 2000)) }
        let receipts = receiptsFor(batch, from: "neighbour", recognised: { _ in true },
                                   author: me, now: 2000, sign: sign)
        // Both Facts name the same record, so there was only ever one thing to say.
        XCTAssertEqual(receipts.count, 1)
        for receipt in receipts {
            XCTAssertTrue(state.receive(receipt, from: "", now: 2000, local: true))
        }
        // The receipt is actually held, actually offered, and actually credited the neighbour.
        XCTAssertEqual(state.held.values.filter { $0.envelope.kind == "receipt" }.map { $0.envelope }, receipts)
        XCTAssertEqual(state.offer(to: "neighbour", now: 2001), receipts)
        XCTAssertEqual(state.useful["neighbour"], 2000 + publicReceiptMs)
        // The rails: no receipt in facts, both Facts still held and still offered onward.
        XCTAssertTrue(state.facts.values.allSatisfy { $0.kind != "receipt" })
        XCTAssertEqual(state.facts.count, batch.count)
        XCTAssertEqual(Set(state.offer(to: "somebody-else", now: 2001).map { $0.id }), Set(batch.map { $0.id }))
        // An unrecognised Fact in the batch owes nothing, and does not mask a recognised one.
        XCTAssertTrue(receiptsFor(batch, from: "neighbour", recognised: { _ in false },
                                  author: me, now: 2000, sign: sign).isEmpty)
        XCTAssertEqual(receiptsFor(batch, from: "neighbour", recognised: { $0.line == 1 },
                                   author: me, now: 2000, sign: sign).count, 1)
        // A receipt admitted from the same neighbour carries the record's own gigId, formerIds
        // and scope, so it collides with the Facts on the de-duplication key. It must be gone
        // before the key is taken, or it wins the slot and then owes nothing.
        let theirs = receiptFor(batch[0], from: "somebody", recognised: true, author: me,
                                now: 1999, sign: sign)!
        XCTAssertEqual(theirs.gigId, batch[0].gigId)
        XCTAssertEqual(theirs.scope, batch[0].scope)
        XCTAssertEqual(theirs.formerIds, batch[0].formerIds)
        XCTAssertEqual(receiptsFor([theirs] + batch, from: "neighbour", recognised: { _ in true },
                                   author: me, now: 2000, sign: sign).count, 1)
    }

    /// Story 41: the decay is its own clock, not a slice of the carry window.
    func testUsefulnessDecaysOnItsOwnClockWhileTheEnvelopeIsStillAlive() {
        XCTAssertNotEqual(publicCarryMs, publicReceiptMs)
        let me = key.publicKey.derRepresentation.base64EncodedString()
        let long = GossipEnvelope(gigId: "gig", scope: "scope", author: me, createdAt: 1000,
                                  expiresAt: 9_000_000, kind: "receipt", text: "somebody")
            .signed { try? self.key.signature(for: $0).derRepresentation }!
        var state = PublicGossipState()
        XCTAssertTrue(state.receive(long, from: me, now: 2000))
        XCTAssertEqual(state.useful[me], 2000 + publicReceiptMs)
        state.prune(now: 2000 + publicReceiptMs - 1)
        XCTAssertEqual(Set(state.useful.keys), [me])
        state.prune(now: 2000 + publicReceiptMs)
        XCTAssertTrue(state.useful.isEmpty)
    }

    /// The twin of Android's tally test. `creditHits` stays reachable here even though nothing
    /// on this platform ranks, so the two summaries cannot drift apart in shape.
    func testTheTallySeparatesAWindowThatFoundCreditFromOneThatOnlyRanked() {
        let tally = GossipTally.shared
        tally.reset()
        defer { tally.reset() }
        // An empty window is not a window. Counting it would put the ranker's denominator up
        // every scan and make a zero numerator look like a busy night that found nothing.
        tally.ranked(candidates: 0, hits: 0)
        XCTAssertTrue(tally.summary().contains("pick windows=0"))

        tally.ranked(candidates: 3, hits: 0)
        tally.ranked(candidates: 2, hits: 1)
        tally.authored()
        // A Pass nothing was owed on is counted once, and it is not a receipt that failed:
        // authored and declined are separate numbers because they are separate stories.
        tally.declined()
        tally.offered(2)
        tally.delivered(1)
        XCTAssertEqual(tally.summary(),
            "receipts authored=1 declined=1 offered=2 delivered=1 · pick windows=2 credit hits=1")
    }

    /// Stories 38 and 39: preference, ties under a seed, and nobody excluded.
    func testUsefulNeighboursComeFirstWithSeededTiesAndNobodyExcluded() {
        let seen = ["plain-a", "useful-a", "plain-b", "useful-b", "plain-c"]
        var rng = SeededGenerator(seed: 7)
        let order = gossipPreferredPeers(seen, credited: { $0.hasPrefix("useful") }, using: &rng)
        XCTAssertEqual(Set(order.prefix(2)), ["useful-a", "useful-b"])
        XCTAssertEqual(Set(order), Set(seen))
        XCTAssertEqual(order.count, seen.count)

        let plain = plainPeers()
        var one = SeededGenerator(seed: 1)
        var oneAgain = SeededGenerator(seed: 1)
        var two = SeededGenerator(seed: 2)
        let first = gossipPreferredPeers(plain, credited: { _ in false }, using: &one)
        XCTAssertEqual(first, gossipPreferredPeers(plain, credited: { _ in false }, using: &oneAgain))
        XCTAssertNotEqual(first, gossipPreferredPeers(plain, credited: { _ in false }, using: &two))
        XCTAssertEqual(Set(first), Set(plain))

        var empty = SeededGenerator(seed: 0)
        XCTAssertTrue(gossipPreferredPeers([], credited: { _ in true }, using: &empty).isEmpty)
    }

    private func plainPeers() -> [String] { (0..<8).map { "peer-\($0)" } }

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
        XCTAssertEqual(passBatch([log, theirs], request: nil, signer: "relay-key"), [log])
        // Mine to prove: sign as its author. Facts still ride along under that key.
        XCTAssertEqual(passAuthor([log, mine, theirs], localAuthors: ["me"]), mine)
        XCTAssertEqual(passBatch([log, mine, theirs], request: mine, signer: "me"), [log, mine])
    }

    func testAttributionCatchesUpOnAnEarlierFactAndOutlivesRemovingTheContact() throws {
        let card = P256.Signing.PrivateKey()
        let durable = card.publicKey.derRepresentation.base64EncodedString()
        var draft = fact("Idioteque")
        let proof = try card.signature(for: gossipIdentityBinding(scope: draft.scope, author: draft.author)).derRepresentation
        let sealed = try AES.GCM.seal(proof, using: gossipRecognitionKey(durable: durable, scope: draft.scope))
        draft.attribution = try XCTUnwrap(sealed.combined).base64EncodedString()
        let line = try XCTUnwrap(draft.signed { try? self.key.signature(for: $0).derRepresentation })
        var state = PublicGossipState()

        // Received before the Exchange: carried and shown, but nobody this device knows.
        XCTAssertTrue(state.receive(line, from: "blind-relay", now: 2000))
        XCTAssertEqual(state.project(gigIds: ["gig"]), [line])
        XCTAssertNil(state.attributedName(line.author, live: [durable: "Ada"]))

        // The Exchange happens afterwards. Attribution catches up on what already arrived.
        state.recognizeContacts([durable], names: [durable: "Ada"])
        XCTAssertEqual(state.attributedName(line.author, live: [durable: "Ada"]), "Ada")

        // Removing the Contact deletes the Friend record, so the live map no longer has the
        // name. Recognition is not revocable, and neither is what it resolves to.
        var restored = try JSONDecoder().decode(PublicGossipState.self, from: JSONEncoder().encode(state))
        restored.recognizeContacts([], names: [:])
        XCTAssertEqual(restored.attributedName(line.author), "Ada")
        XCTAssertEqual(restored.project(gigIds: ["gig"]), [line])

        // A stranger stays a stranger rather than borrowing the name beside them.
        XCTAssertNil(restored.attributedName("someone-else", live: [durable: "Ada"]))
    }

    func testStateFromBeforeTheNamesSnapshotDecodesWithoutLosingTheNight() throws {
        let envelope = fact()
        var before = PublicGossipState()
        XCTAssertTrue(before.receive(envelope, from: "relay", now: 2000))
        before.blocked.insert("someone")
        var json = try JSONSerialization.jsonObject(with: JSONEncoder().encode(before)) as! [String: Any]
        json.removeValue(forKey: "contactNames")
        let restored = try JSONDecoder().decode(PublicGossipState.self,
            from: JSONSerialization.data(withJSONObject: json))
        XCTAssertEqual(restored.project(gigIds: ["gig"]), [envelope])
        XCTAssertEqual(restored.blocked, ["someone"])
        XCTAssertTrue(restored.contactNames.isEmpty)
    }

    func testCarryingIsNotAuthoringSoARelayedFactIsNeverThisDevicesOwn() throws {
        var state = PublicGossipState()
        let theirs = fact("Bodysnatchers")
        let theirClaim = fact(kind: "request")
        XCTAssertTrue(state.receive(theirs, from: "blind-relay", now: 2000))
        XCTAssertTrue(state.receive(theirClaim, from: theirClaim.author, now: 2001))

        // Nothing about carrying makes this device the author: not the record, not the author
        // key it hands on, and not the evidence its own screens read.
        XCTAssertTrue(state.localAuthors.isEmpty)
        XCTAssertEqual(state.witnessedGigIds(), [])
        // Their Log line travels on under their key, never re-signed under this device's.
        // Their request does not travel at all — one hop is the author's own.
        XCTAssertEqual(state.offer(to: "next-peer", now: 2002), [theirs])
        XCTAssertEqual(state.arrivals(gigIds: ["gig"]), [theirClaim])
        XCTAssertNil(state.attributedName(theirs.author))

        // Authoring here is the only way in, and it claims this device's own key alone.
        let myKey = P256.Signing.PrivateKey()
        let mine = try XCTUnwrap(GossipEnvelope(gigId: "gig", scope: "my-scope",
            author: myKey.publicKey.derRepresentation.base64EncodedString(),
            createdAt: 1100, expiresAt: 100000, kind: "request", line: -1)
            .signed { try? myKey.signature(for: $0).derRepresentation })
        XCTAssertTrue(state.receive(mine, from: "", now: 2003, local: true))
        XCTAssertEqual(state.localAuthors, [mine.author])
        XCTAssertEqual(state.arrivals(gigIds: ["gig"]), [theirClaim])
    }

    /// Story 8: a witness means shared presence, so this phone signs one only when it checked
    /// into the same **Gig** itself. Both ways of not having done so are here, because they
    /// fail for different reasons and only one of them is obvious: never having checked in at
    /// all, and having checked into a *different* night while this request arrives.
    func testNoWitnessIsSignedWithoutThisPhonesOwnClaimAtTheSameGig() throws {
        let strangerKey = P256.Signing.PrivateKey()
        func request(_ signingKey: P256.Signing.PrivateKey, gigId: String, scope: String) -> GossipEnvelope {
            GossipEnvelope(gigId: gigId, scope: scope,
                author: signingKey.publicKey.derRepresentation.base64EncodedString(),
                createdAt: 1000, expiresAt: 100000, kind: "request")
                .signed { try? signingKey.signature(for: $0).derRepresentation }!
        }
        let sign: (GossipEnvelope) -> (Data) -> Data? = { _ in
            { try? self.key.signature(for: $0).derRepresentation }
        }
        let theirs = request(strangerKey, gigId: "gig", scope: "their-scope")

        // Carrying for a night I am not at. A request admitted from its author is still only
        // their claim; nothing about receiving it lets this device speak for it.
        var bystander = PublicGossipState()
        XCTAssertTrue(bystander.receive(theirs, from: theirs.author, now: 1500))
        XCTAssertNil(bystander.localClaim(for: theirs))
        XCTAssertNil(witnessFor(bystander, request: theirs, now: 1600, sign: sign))

        // Checked in — at the wrong Gig. `sameGig` is the discriminator, and a claim sharing
        // neither a current nor a former id is somebody else's night.
        var elsewhere = PublicGossipState()
        XCTAssertTrue(elsewhere.receive(request(key, gigId: "other-gig", scope: "my-scope"),
                                        from: "", now: 1500, local: true))
        XCTAssertTrue(elsewhere.receive(theirs, from: theirs.author, now: 1501))
        XCTAssertNil(elsewhere.localClaim(for: theirs))
        XCTAssertNil(witnessFor(elsewhere, request: theirs, now: 1600, sign: sign))

        // Checked in, same night: now there is something to sign with, and the witness
        // embeds the whole signed claim rather than a reference to it.
        var here = PublicGossipState()
        let mine = request(key, gigId: "gig", scope: "my-scope")
        XCTAssertTrue(here.receive(mine, from: "", now: 1500, local: true))
        XCTAssertTrue(here.receive(theirs, from: theirs.author, now: 1501))
        XCTAssertEqual(here.localClaim(for: theirs), mine)
        let witness = try XCTUnwrap(witnessFor(here, request: theirs, now: 1600, sign: sign))
        XCTAssertEqual(witness.kind, "witness")
        XCTAssertEqual(witness.author, mine.author)
        XCTAssertEqual(decodePublicEnvelope(witness.text), theirs)
        XCTAssertTrue(here.receive(witness, from: "", now: 1601, local: true))
        let evidence = here.checkInEvidence(gigIds: ["gig"], author: theirs.author)
        XCTAssertTrue(evidence.asserted)
        XCTAssertTrue(evidence.witnessed)
    }

    /// Story 2: nobody was near enough to witness, and the night is captured anyway.
    ///
    /// The point is that the absence gates nothing — the claim is held and offered, the
    /// **Log** lines behind it publish, project and travel, and the only thing missing is the
    /// second half of `checkInEvidence`. A witness strengthens a claim; it never authorises one.
    func testAClaimNobodyWitnessedStillCapturesProjectsAndTravels() throws {
        var state = PublicGossipState()
        let request = fact("", kind: "request")
        XCTAssertTrue(state.receive(request, from: "", now: 1500, local: true))
        var evidence = state.checkInEvidence(gigIds: ["gig"], author: request.author)
        XCTAssertTrue(evidence.asserted)
        XCTAssertFalse(evidence.witnessed)
        XCTAssertEqual(state.witnessedGigIds(), [])

        // Capture proceeds. Two Log lines written with no witness in the room are admitted,
        // projected and offered exactly as they would be with one.
        let first = fact(at: 1600)
        let second = fact("Evolve", at: 1700, line: 1)
        XCTAssertTrue(state.receive(first, from: "", now: 1601, local: true))
        XCTAssertTrue(state.receive(second, from: "", now: 1701, local: true))
        XCTAssertEqual(state.project(gigIds: ["gig"]), [request, first, second])
        XCTAssertEqual(Set(state.offer(to: "first-peer-of-the-night", now: 1800,
                                       participationEnds: [:]).map(\.id)),
                       [request.id, first.id, second.id])

        // A witness arriving late changes only the second answer. Nothing captured before it
        // is revisited, and nothing was waiting on it.
        let witnessKey = P256.Signing.PrivateKey()
        let theirs = GossipEnvelope(gigId: "gig", scope: "their-scope",
            author: witnessKey.publicKey.derRepresentation.base64EncodedString(),
            createdAt: 1000, expiresAt: 100000, kind: "request")
            .signed { try? witnessKey.signature(for: $0).derRepresentation }!
        let witness = try XCTUnwrap(witnessRequest(request, with: theirs, now: 1900) {
            try? witnessKey.signature(for: $0).derRepresentation
        })
        XCTAssertTrue(state.receive(witness, from: witness.author, now: 1901))
        evidence = state.checkInEvidence(gigIds: ["gig"], author: request.author)
        XCTAssertTrue(evidence.asserted)
        XCTAssertTrue(evidence.witnessed)
        XCTAssertEqual(state.witnessedGigIds(), ["gig"])
        XCTAssertEqual(state.project(gigIds: ["gig"]).filter { $0.author == request.author },
                       [request, first, second])
    }
}

/// A reproducible `RandomNumberGenerator`, so "ties break randomly" is assertable.
/// SplitMix64 — small, seedable, and good enough for a shuffle in a test.
struct SeededGenerator: RandomNumberGenerator {
    private var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func next() -> UInt64 {
        state &+= 0x9E3779B97F4A7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
        z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
        return z ^ (z >> 31)
    }
}
