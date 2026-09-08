import CryptoKit
import Foundation
import XCTest
@testable import StationToStation

/// The bytes two phones actually exchange (#417). The twin of Android's `GossipWireTest`: the
/// encoders on both sides must produce the same lines, so these assert the literal text rather
/// than only a round trip — a round trip passes happily while both ends are wrong together.
final class GossipWireTests: XCTestCase {

    private let now = Date(timeIntervalSince1970: 1_788_555_600)
    private let token = "cd60b9fef6f960c7b6803f1b45608f0f"

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

    // --- Token offers ---

    func testATokenOfferIsTheHeaderThenOneTokenPerLine() {
        let encoded = encodeGossipTokens([token, "b800c538b1881751fafc9247cb75f58f"])

        XCTAssertEqual(text(encoded),
                       "station-to-station/gossip-tokens/1\n"
                       + "cd60b9fef6f960c7b6803f1b45608f0f\n"
                       + "b800c538b1881751fafc9247cb75f58f")
    }

    func testATokenOfferRoundTrips() {
        XCTAssertEqual(decodeGossipTokens(encodeGossipTokens([token])), [token])
    }

    func testADeviceWithNoContactsStillSendsAWellFormedEmptyOffer() {
        XCTAssertEqual(decodeGossipTokens(encodeGossipTokens([])), [])
    }

    func testBytesThatAreNotATokenOfferDecodeToNil() {
        XCTAssertNil(decodeGossipTokens(Data("hello".utf8)))
        XCTAssertNil(decodeGossipTokens(Data()))
        XCTAssertNil(decodeGossipTokens(Data("station-to-station/gossip-batch/1\n\(token)".utf8)))
    }

    /// Forward compatibility, deliberately: a later build may publish a line this one cannot
    /// read, and the tokens it *can* read are still worth resolving.
    func testAnUnreadableLineIsDroppedRatherThanFailingTheWholeOffer() {
        let data = Data("station-to-station/gossip-tokens/1\nsomething-new\n\(token)".utf8)

        XCTAssertEqual(decodeGossipTokens(data), [token])
    }

    func testAnOfferLargerThanTheWireLimitIsRefused() {
        let huge = Data(String(repeating: "a", count: gossipMaxWireBytes + 1).utf8)

        XCTAssertNil(decodeGossipTokens(huge))
        XCTAssertNil(decodeGossipBatch(huge))
    }

    // --- Batches ---

    func testABatchIsTheHeaderTheSendersTokenThenSixTabSeparatedFieldsPerMessage() {
        let message = minted()

        let encoded = text(encodeGossipBatch(token: token, messages: [message]))
        let lines = encoded.split(separator: "\n", omittingEmptySubsequences: false)

        XCTAssertEqual(String(lines[0]), "station-to-station/gossip-batch/1")
        XCTAssertEqual(String(lines[1]), token)
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

    func testABatchRoundTripsToTheIdenticalMessages() {
        let messages = [minted(gigId: "3ba1f9ca"), minted(gigId: "77bb0142")]

        let decoded = decodeGossipBatch(encodeGossipBatch(token: token, messages: messages)!)

        XCTAssertEqual(decoded, GossipBatch(token: token, messages: messages))
    }

    /// The one property the whole channel rests on: a message that has been over the wire is
    /// byte-for-byte the message that was signed, so its id still recomputes and its signature
    /// still verifies on the far end.
    func testAMessageSurvivesTheWireWithItsIdAndSignatureIntact() {
        let message = minted()

        let decoded = decodeGossipBatch(encodeGossipBatch(token: token, messages: [message])!)
        let arrived = decoded?.messages.first

        XCTAssertEqual(gossipMessageId(arrived!), arrived?.messageId)
        XCTAssertTrue(verifyGossipSignature(arrived!))
    }

    func testAnEmptyBatchIsStillAMeeting() {
        let decoded = decodeGossipBatch(encodeGossipBatch(token: token, messages: [])!)

        XCTAssertEqual(decoded, GossipBatch(token: token, messages: []))
    }

    func testABatchWithoutAWellFormedTokenIsNeitherEncodedNorDecoded() {
        XCTAssertNil(encodeGossipBatch(token: "not-a-token", messages: []))
        XCTAssertNil(decodeGossipBatch(Data("station-to-station/gossip-batch/1\nnot-a-token".utf8)))
    }

    func testBytesThatAreNotABatchDecodeToNil() {
        XCTAssertNil(decodeGossipBatch(Data()))
        XCTAssertNil(decodeGossipBatch(Data("station-to-station/gossip-batch/1".utf8)))
        XCTAssertNil(decodeGossipBatch(encodeGossipTokens([token])))
    }

    /// A peer that can invalidate a whole handover with one bad line is a peer that can stop a
    /// night's check-ins reaching anybody.
    func testAMalformedLineIsDroppedAndTheRestOfTheBatchSurvives() {
        let message = minted()
        let good = text(encodeGossipBatch(token: token, messages: [message]))
        let data = Data((good + "\nnot\ta\tmessage").utf8)

        XCTAssertEqual(decodeGossipBatch(data)?.messages, [message])
    }

    func testABatchOverTheLimitIsTruncatedRatherThanRefused() {
        let messages = (0..<(gossipMaxBatch + 10)).map { _ in minted() }

        let encoded = encodeGossipBatch(token: token, messages: messages)

        XCTAssertEqual(decodeGossipBatch(encoded!)?.messages.count, gossipMaxBatch)
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
        XCTAssertEqual(gossipTokenCharacteristicUUIDString, "7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7722")
        XCTAssertEqual(gossipInboxCharacteristicUUIDString, "7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7723")
        XCTAssertEqual(gossipOutboxCharacteristicUUIDString, "7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7724")
    }

    /// The gossip service is not the Exchange's. Opposite lifetimes — one is a screen a person
    /// is looking at, the other runs in a pocket — and a phone scanning for one must never
    /// connect to the other.
    func testTheGossipServiceIsNotTheExchangeService() {
        XCTAssertNotEqual(gossipServiceUUIDString, exchangeServiceUUIDString)
    }
}
