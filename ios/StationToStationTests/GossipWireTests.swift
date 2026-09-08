import CryptoKit
import Foundation
import XCTest
@testable import StationToStation

/// The bytes two phones actually exchange (#417, #416). The twin of Android's
/// `GossipWireTest`: the encoders on both sides must produce the same lines, so these assert
/// the literal grammar — the header, the field order, the separators — rather than only a
/// round trip. A round trip passes just as happily against a format the other platform cannot
/// read.
final class GossipWireTests: XCTestCase {

    private let now = Date(timeIntervalSince1970: 1_788_555_600)
    private let nonce = Data((0..<32).map { UInt8($0) })

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
    private let proof = "c2lnbmF0dXJl"

    private func text(_ data: Data?) -> String {
        guard let data else { return "<nil>" }
        return String(decoding: data, as: UTF8.self)
    }

    private func minted(gigId: String = "3ba1f9ca", gigDate: String? = "04-09-2026") -> GossipCheckIn {
        guard let message = gossipCheckInMessage(gigId: gigId, gigDate: gigDate,
                                                 publicKey: alice.publicKey, now: now,
                                                 sign: { signChallenge($0, privateKey: alice.privateKey) })
        else {
            XCTFail("could not mint the fixture")
            return GossipCheckIn(messageId: "", gigId: gigId, checkedInBy: "",
                                 checkedInAt: now, expiresAt: now, signature: "")
        }
        return message
    }

    // --- The challenge ---

    func testAChallengeSurvivesTheRoundTrip() {
        let challenge = GossipChallenge(nonce: nonce, tokens: ["00112233445566aa"])

        XCTAssertEqual(decodeGossipChallenge(encodeGossipChallenge(challenge)), challenge)
    }

    /// The grammar itself, because these bytes are the contract with Android: the header, the
    /// base64 nonce, then one hex token per line.
    func testTheChallengeGrammarIsTheHeaderTheNonceThenOneTokenPerLine() {
        let challenge = GossipChallenge(nonce: nonce,
                                        tokens: ["00112233445566aa", "aabbccddeeff0011"])

        let lines = text(encodeGossipChallenge(challenge))
            .split(separator: "\n", omittingEmptySubsequences: false)

        XCTAssertEqual(String(lines[0]), "station-to-station/gossip-challenge/1")
        XCTAssertEqual(String(lines[1]), nonce.base64EncodedString())
        XCTAssertEqual(lines.dropFirst(2).map(String.init),
                       ["00112233445566aa", "aabbccddeeff0011"])
    }

    /// The listener answers with tokens and never with its own key — the whole reason the
    /// challenge is shaped this way. A stable identifier readable by any radio that connects
    /// is what ADR-0019 refuses.
    func testAChallengeNamesNobody() {
        let encoded = text(encodeGossipChallenge(
            GossipChallenge(nonce: nonce, tokens: ["00112233445566aa"])))

        XCTAssertFalse(encoded.contains(alice.publicKey))
    }

    /// Forward compatibility, deliberately: a later build may publish a line this one cannot
    /// read, and the tokens it *can* read are still worth resolving.
    func testAMalformedTokenLineIsDroppedRatherThanFailingTheWholeChallenge() {
        let data = Data(["station-to-station/gossip-challenge/1",
                         nonce.base64EncodedString(),
                         "00112233445566aa",
                         "not-a-token",
                         "AABBCCDDEEFF0011"].joined(separator: "\n").utf8)

        XCTAssertEqual(decodeGossipChallenge(data)?.tokens, ["00112233445566aa"])
    }

    func testAChallengeWithTheWrongHeaderOrAShortNonceIsRefused() {
        XCTAssertNil(decodeGossipChallenge(Data("wrong\n\(nonce.base64EncodedString())".utf8)))
        XCTAssertNil(decodeGossipChallenge(Data(
            "station-to-station/gossip-challenge/1\n\(Data(count: 31).base64EncodedString())".utf8)))
        XCTAssertNil(decodeGossipChallenge(Data("station-to-station/gossip-challenge/1".utf8)))
        XCTAssertNil(decodeGossipChallenge(Data()))
    }

    func testTheNonceIsThirtyTwoBytesOnBothPlatforms() {
        XCTAssertEqual(gossipNonceBytes, 32)
    }

    // --- The possession proof ---

    /// The identity key answers the LAN reconcile challenge and signs gossip payloads too.
    /// Three uses, three prefixes, so a signature made for one can never be presented as an
    /// answer to another.
    func testThePossessionProofIsDomainSeparatedFromEveryOtherUseOfTheIdentityKey() {
        let payload = text(gossipAuthPayload(nonce))

        XCTAssertTrue(payload.hasPrefix("station-to-station/gossip-auth/1\n"))
        XCTAssertEqual(payload,
                       "station-to-station/gossip-auth/1\n\(nonce.base64EncodedString())")
        XCTAssertNotEqual(gossipAuthPayload(nonce), gossipAuthPayload(Data(count: 32)))
    }

    /// End to end, with real key material: the signature a pusher makes over the nonce is the
    /// signature the listener verifies. `verifyChallenge` is the Exchange's, reused rather
    /// than restated.
    func testAProofOverTheIssuedNonceVerifiesAndOverAnotherDoesNot() {
        let signature = signChallenge(gossipAuthPayload(nonce), privateKey: alice.privateKey)!

        XCTAssertTrue(verifyChallenge(gossipAuthPayload(nonce), signature: signature,
                                      publicKeyBase64: alice.publicKey))
        XCTAssertFalse(verifyChallenge(gossipAuthPayload(Data(count: 32)), signature: signature,
                                       publicKeyBase64: alice.publicKey))
        XCTAssertFalse(verifyChallenge(gossipAuthPayload(nonce), signature: signature,
                                       publicKeyBase64: identity().publicKey))
    }

    // --- The Pass ---

    func testAPassSurvivesTheRoundTrip() {
        let pass = GossipPass(from: alice.publicKey, proof: proof,
                              batch: [minted(gigId: "3ba1f9ca"), minted(gigId: "77bb0142")])

        XCTAssertEqual(decodeGossipPass(encodeGossipPass(pass)!), pass)
    }

    func testTheGrammarIsTheHeaderTheClaimLineThenOneRecordPerLine() {
        let message = minted()

        let lines = text(encodeGossipPass(GossipPass(from: alice.publicKey, proof: proof,
                                                     batch: [message])))
            .split(separator: "\n", omittingEmptySubsequences: false)

        XCTAssertEqual(String(lines[0]), "station-to-station/gossip-pass/1")
        XCTAssertEqual(String(lines[1]), "\(alice.publicKey)\t\(proof)")
        XCTAssertEqual(lines.count, 3)
        let fields = lines[2].split(separator: "\t", omittingEmptySubsequences: false)
        XCTAssertEqual(fields.count, 6)
        XCTAssertEqual(String(fields[0]), message.messageId)
        XCTAssertEqual(String(fields[1]), message.gigId)
        XCTAssertEqual(String(fields[2]), message.checkedInBy)
        XCTAssertEqual(String(fields[3]), String(Int64(message.checkedInAt.timeIntervalSince1970)))
        XCTAssertEqual(String(fields[4]), String(Int64(message.expiresAt.timeIntervalSince1970)))
        XCTAssertEqual(String(fields[5]), message.signature)
    }

    /// The one property the whole channel rests on: a message that has been over the wire is
    /// byte-for-byte the message that was signed, so its id still recomputes and its signature
    /// still verifies on the far end.
    func testAMessageSurvivesTheWireWithItsIdAndSignatureIntact() {
        let message = minted()
        let pass = GossipPass(from: alice.publicKey, proof: proof, batch: [message])

        let arrived = decodeGossipPass(encodeGossipPass(pass)!)?.batch.first

        XCTAssertEqual(gossipMessageId(arrived!), arrived?.messageId)
        XCTAssertTrue(verifyGossipSignature(arrived!))
    }

    func testTimesCrossAsEpochSecondsSoTheTwoPlatformsCannotDisagreeOnAFormat() {
        let message = minted()
        let pass = GossipPass(from: alice.publicKey, proof: proof, batch: [message])

        XCTAssertEqual(decodeGossipPass(encodeGossipPass(pass)!)?.batch.first?.checkedInAt, now)
    }

    func testTheWrongHeaderIsNotAPass() {
        XCTAssertNil(decodeGossipPass(
            Data("station-to-station/gossip-pass/2\n\(alice.publicKey)\t\(proof)".utf8)))
    }

    func testAPassWithNoClaimLineIsRefused() {
        XCTAssertNil(decodeGossipPass(Data("station-to-station/gossip-pass/1".utf8)))
        XCTAssertNil(decodeGossipPass(
            Data("station-to-station/gossip-pass/1\n\(alice.publicKey)".utf8)))
        XCTAssertNil(decodeGossipPass(Data("station-to-station/gossip-pass/1\n\t\(proof)".utf8)))
        XCTAssertNil(decodeGossipPass(Data()))
    }

    /// The whole reason a bad record is skipped rather than fatal: otherwise any device in the
    /// chain can stop a message it dislikes by corrupting the one next to it.
    func testAnUnreadableRecordIsSkippedAndTheRestOfTheBatchSurvives() {
        let messages = [minted(gigId: "3ba1f9ca"), minted(gigId: "77bb0142")]
        let good = text(encodeGossipPass(GossipPass(from: alice.publicKey, proof: proof,
                                                    batch: messages)))
        let lines = good.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        let withRubbish = (lines.prefix(3) + ["not\ta\trecord"] + lines.suffix(1))
            .joined(separator: "\n")

        XCTAssertEqual(decodeGossipPass(Data(withRubbish.utf8))?.batch, messages)
    }

    /// A `Date` is a `Double`, so a peer's `1e30` is a constructible value and `Int64(_:)` on
    /// it would abort the process. Every date here arrived over a radio.
    func testAPeersDecimalCannotBecomeATrap() {
        let record = ["abc123", "3ba1f9ca", alice.publicKey,
                      "\(gossipMaxEpochSecond + 1)", "1788580800", "c2lnbmVk"]
            .joined(separator: "\t")
        let absurd = ["station-to-station/gossip-pass/1",
                      "\(alice.publicKey)\t\(proof)",
                      record].joined(separator: "\n")

        XCTAssertEqual(decodeGossipPass(Data(absurd.utf8))?.batch, [])
    }

    func testAFieldCarryingASeparatorIsDroppedRatherThanEncodedAmbiguously() {
        let split = GossipCheckIn(messageId: "abc123", gigId: "gig\tid",
                                  checkedInBy: alice.publicKey, checkedInAt: now,
                                  expiresAt: now.addingTimeInterval(3600), signature: "c2lnbmVk")

        XCTAssertEqual(decodeGossipPass(encodeGossipPass(
            GossipPass(from: alice.publicKey, proof: proof, batch: [split]))!)?.batch, [])
        XCTAssertNil(encodeGossipPass(GossipPass(from: "bo\tb", proof: proof, batch: [])))
        XCTAssertNil(encodeGossipPass(GossipPass(from: alice.publicKey, proof: "pro\nof",
                                                 batch: [])))
        XCTAssertNil(encodeGossipPass(GossipPass(from: "", proof: proof, batch: [])))
    }

    func testAnOversizedWriteIsRefusedWholeBeforeAnythingHasBeenDecided() {
        let huge = Data(String(repeating: "x", count: gossipMaxWireBytes + 1).utf8)

        XCTAssertNil(decodeGossipPass(huge))
        XCTAssertNil(decodeGossipChallenge(huge))
        XCTAssertNil(encodeGossipPass(GossipPass(from: alice.publicKey, proof: proof,
                                                 batch: (0..<2000).map { _ in minted() })))
    }

    /// A **Pass** is written in pieces and ended by an empty one, because that is the only
    /// framing a CoreBluetooth peripheral and an Android GATT server read the same way.
    /// Reassembly is concatenation, so the pieces have to put the bytes back exactly.
    func testAPassChunkedForTheWireReassemblesToItself() {
        let pass = GossipPass(from: alice.publicKey, proof: proof,
                              batch: (0..<8).map { _ in minted() })
        let payload = encodeGossipPass(pass)!

        let chunks = payload.gossipChunks(by: 20)

        XCTAssertGreaterThan(chunks.count, 1)
        XCTAssertTrue(chunks.allSatisfy { $0.count <= 20 })
        XCTAssertEqual(decodeGossipPass(chunks.reduce(Data(), +)), pass)
    }

    // --- Minting my own arrival ---

    func testAMintedCheckInExpiresAtTheEndOfTheGigsOwnNight() {
        let message = minted(gigDate: "04-09-2026")

        XCTAssertEqual(message.expiresAt, gossipExpiry(gigDate: "04-09-2026"))
        XCTAssertEqual(message.checkedInAt, now)
        XCTAssertEqual(message.checkedInBy, alice.publicKey)
    }

    func testAMintedCheckInForANightWithNoDateFallsBackToTheMaximumLifetime() {
        let message = minted(gigDate: nil)

        XCTAssertEqual(message.expiresAt, now.addingTimeInterval(gossipMaxLifetime))
    }

    func testAMintedCheckInIsAcceptedByTheStormGateOnTheFarEnd() {
        let message = minted()
        let bob = identity()

        let plan = gossipStormGate(seen: [:], batch: [message], from: alice.publicKey, now: now,
                                   contacts: [alice.publicKey, bob.publicKey])

        XCTAssertEqual(plan.accepted, [message])
        XCTAssertEqual(plan.rejected, [])
    }

    func testAGigIdTheWireCannotCarryIsNeverMinted() {
        XCTAssertNil(gossipCheckInMessage(gigId: "has\ttab", gigDate: nil,
                                          publicKey: alice.publicKey, now: now,
                                          sign: { signChallenge($0, privateKey: alice.privateKey) }))
    }

    func testAPhoneWithNoIdentityKeyMintsNothingRatherThanAnUnsignedClaim() {
        XCTAssertNil(gossipCheckInMessage(gigId: "3ba1f9ca", gigDate: nil,
                                          publicKey: alice.publicKey, now: now,
                                          sign: { _ in nil }))
    }

    // --- The service the two platforms have to agree on ---

    func testTheServiceAndCharacteristicUUIDsAreTheOnesAndroidLooksFor() {
        XCTAssertEqual(gossipServiceUUIDString, "7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7721")
        XCTAssertEqual(gossipChallengeCharacteristicUUIDString,
                       "7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7722")
        XCTAssertEqual(gossipPassCharacteristicUUIDString, "7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7723")
    }

    /// The gossip service is not the Exchange's. Opposite lifetimes — one is a screen a person
    /// is looking at, the other runs in a pocket — and a phone scanning for one must never
    /// connect to the other.
    func testTheGossipServiceIsNotTheExchangeService() {
        XCTAssertNotEqual(gossipServiceUUIDString, exchangeServiceUUIDString)
    }
}
