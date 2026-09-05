import CryptoKit
import XCTest
@testable import StationToStation

/// The gossip storm-gate (#410). No radio, no service, no clock and no keychain — the whole
/// decision is a function of its arguments, which is what lets the part where a mistake is
/// *silent* be asserted without a phone. The twin of Android's `GossipStormGateTest`, case
/// for case, because the two implementations must agree about what they throw away.
///
/// Signatures here are real ECDSA P-256 over the real canonical payload: the one thing a
/// stub verifier could not catch is a payload that does not actually bind what it claims to
/// bind, and that is the failure this feature cannot afford.
///
/// Names and ids are invented — this repository is public and real timeline data never
/// enters a fixture.
final class GossipStormGateTests: XCTestCase {

    private struct Identity {
        let publicKey: String
        let privateKey: P256.Signing.PrivateKey
    }

    private func identity() -> Identity {
        let key = P256.Signing.PrivateKey()
        return Identity(publicKey: key.publicKey.derRepresentation.base64EncodedString(),
                        privateKey: key)
    }

    /// 2026-09-04T21:00:00Z — the same instant Android's twin pins its fixtures to.
    private let now = Date(timeIntervalSince1970: 1_788_555_600)
    private var tonight: Date { now.addingTimeInterval(8 * 3600) }

    /// Alice checked in, Bob is the **Contact** who handed me the batch, and Carol is
    /// somebody else I have met — the smallest set with anyone left to relay to, since a
    /// message is never sent back to the peer it came from or on to its own author.
    private lazy var alice = identity()
    private lazy var bob = identity()
    private lazy var carol = identity()
    private lazy var contacts: Set<String> = [alice.publicKey, bob.publicKey, carol.publicKey]

    private func signed(by author: Identity,
                        gigId: String = "3ba1f9ca",
                        checkedInAt: Date? = nil,
                        expiresAt: Date? = nil) -> GossipCheckIn {
        var message = GossipCheckIn(
            messageId: "",
            gigId: gigId,
            checkedInBy: author.publicKey,
            checkedInAt: checkedInAt ?? now.addingTimeInterval(-600),
            expiresAt: expiresAt ?? tonight,
            signature: ""
        )
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

    func testASignedMessageFromAContactIsAcceptedAndRelayedOn() {
        let message = signed(by: alice)

        let plan = gossipStormGate(seen: [:], batch: [message], from: bob.publicKey,
                                   now: now, contacts: contacts)

        XCTAssertEqual([message], plan.accepted)
        XCTAssertEqual([message], plan.relay.map(\.message))
        XCTAssertEqual([carol.publicKey], plan.relay.first?.to)
        XCTAssertEqual([message.messageId: tonight], plan.seen)
        XCTAssertTrue(plan.rejected.isEmpty)
    }

    func testAMessageSeenOnceIsNotRelayedAgain() {
        let message = signed(by: alice)

        let first = gossipStormGate(seen: [:], batch: [message], from: bob.publicKey,
                                    now: now, contacts: contacts)
        let second = gossipStormGate(seen: first.seen, batch: [message], from: bob.publicKey,
                                     now: now, contacts: contacts)

        XCTAssertEqual([message], first.relay.map(\.message))
        XCTAssertTrue(second.accepted.isEmpty)
        XCTAssertTrue(second.relay.isEmpty)
        XCTAssertEqual([.alreadySeen], second.rejected.map(\.reason))
        // The seen set is unchanged by a re-offer: nothing new to remember.
        XCTAssertEqual(first.seen, second.seen)
    }

    func testTheSameMessageTwiceInsideOneBatchIsAcceptedOnce() {
        let message = signed(by: alice)

        let plan = gossipStormGate(seen: [:], batch: [message, message], from: bob.publicKey,
                                   now: now, contacts: contacts)

        XCTAssertEqual([message], plan.accepted)
        XCTAssertEqual(1, plan.relay.count)
        XCTAssertEqual([.alreadySeen], plan.rejected.map(\.reason))
    }

    func testAnExpiredMessageIsDroppedHoweverNewItIs() {
        let stale = signed(by: alice, expiresAt: now.addingTimeInterval(-1))

        let plan = gossipStormGate(seen: [:], batch: [stale], from: bob.publicKey,
                                   now: now, contacts: contacts)

        XCTAssertTrue(plan.accepted.isEmpty)
        XCTAssertTrue(plan.relay.isEmpty)
        XCTAssertEqual([.expired], plan.rejected.map(\.reason))
        // And it never enters the seen set: an expired message needs no memory.
        XCTAssertTrue(plan.seen.isEmpty)
    }

    /// The edge itself: expiry is exclusive, matching the night window's own 06:00 line.
    func testAMessageExpiringExactlyNowIsExpired() {
        let plan = gossipStormGate(seen: [:], batch: [signed(by: alice, expiresAt: now)],
                                   from: bob.publicKey, now: now, contacts: contacts)

        XCTAssertEqual([.expired], plan.rejected.map(\.reason))
    }

    func testAMessageWhoseSignatureDoesNotVerifyIsRejectedOutright() {
        var forged = signed(by: alice)
        forged.signature = Data([1, 2, 3]).base64EncodedString()

        let plan = gossipStormGate(seen: [:], batch: [forged], from: bob.publicKey,
                                   now: now, contacts: contacts)

        XCTAssertTrue(plan.accepted.isEmpty)
        XCTAssertTrue(plan.relay.isEmpty)
        XCTAssertEqual([.badSignature], plan.rejected.map(\.reason))
        // Nothing forged may occupy a message id: otherwise a forgery poisons the seen set
        // and the genuine message with that id is dropped as a duplicate for ever.
        XCTAssertTrue(plan.seen.isEmpty)
    }

    func testAnUnsignedMessageIsRejected() {
        var unsigned = signed(by: alice)
        unsigned.signature = ""

        let plan = gossipStormGate(seen: [:], batch: [unsigned], from: bob.publicKey,
                                   now: now, contacts: contacts)

        XCTAssertEqual([.badSignature], plan.rejected.map(\.reason))
    }

    /// A real key, a real signature over the real payload — and nobody I have met.
    func testAMessageFromARealKeyThatIsNotAContactIsRejected() {
        let stranger = identity()
        let message = signed(by: stranger)

        let plan = gossipStormGate(seen: [:], batch: [message], from: bob.publicKey,
                                   now: now, contacts: contacts)

        XCTAssertTrue(plan.accepted.isEmpty)
        XCTAssertTrue(plan.relay.isEmpty)
        XCTAssertEqual([.authorNotAContact], plan.rejected.map(\.reason))
    }

    /// The other half of the same rule: bytes only ever arrive along a Contact edge.
    func testABatchHandedOverBySomeoneWhoIsNotAContactYieldsAnEmptyPlan() {
        let stranger = identity()

        let plan = gossipStormGate(seen: [:], batch: [signed(by: alice)],
                                   from: stranger.publicKey, now: now, contacts: contacts)

        XCTAssertTrue(plan.accepted.isEmpty)
        XCTAssertTrue(plan.relay.isEmpty)
        XCTAssertTrue(plan.seen.isEmpty)
        XCTAssertEqual([.senderNotAContact], plan.rejected.map(\.reason))
    }

    func testAMessageIdThatIsNotTheHashOfItsPayloadIsRejected() {
        var relabelled = signed(by: alice)
        // Same signed payload, a new id: unchecked, this is the whole storm — one message
        // re-labelled for ever, never a duplicate, relayed by everyone.
        relabelled.messageId = String(repeating: "0", count: 64)

        let plan = gossipStormGate(seen: [:], batch: [relabelled], from: bob.publicKey,
                                   now: now, contacts: contacts)

        XCTAssertEqual([.forgedId], plan.rejected.map(\.reason))
    }

    func testAMessageIdIsTheHashOfThePayloadAndNothingElse() {
        let message = signed(by: alice)

        // Neither the id nor the signature is part of what the id is taken over, so a
        // message can be identified and signed without a circular definition.
        XCTAssertEqual(message.messageId, gossipMessageId(message))
        var resigned = message
        resigned.signature = "different"
        XCTAssertEqual(message.messageId, gossipMessageId(resigned))

        // Every field that is signed changes it.
        var otherGig = message
        otherGig.gigId = "other"
        XCTAssertNotEqual(message.messageId, gossipMessageId(otherGig))
        var later = message
        later.checkedInAt = message.checkedInAt.addingTimeInterval(1)
        XCTAssertNotEqual(message.messageId, gossipMessageId(later))
        var longer = message
        longer.expiresAt = message.expiresAt.addingTimeInterval(1)
        XCTAssertNotEqual(message.messageId, gossipMessageId(longer))
        var elseWho = message
        elseWho.checkedInBy = bob.publicKey
        XCTAssertNotEqual(message.messageId, gossipMessageId(elseWho))
    }

    /// The one fact the two platforms cannot renegotiate later: the same envelope has to
    /// hash to the same id on both, or a relayed message is a new message on every hop.
    func testTheCanonicalPayloadIsExactlyTheAgreedBytes() {
        let message = GossipCheckIn(
            messageId: "",
            gigId: "3ba1f9ca",
            checkedInBy: "AAAA",
            checkedInAt: Date(timeIntervalSince1970: 1_788_555_600),
            expiresAt: Date(timeIntervalSince1970: 1_788_584_400),
            signature: ""
        )

        XCTAssertEqual(
            "station-to-station/gossip-checkin/1\n3ba1f9ca\nAAAA\n1788555600\n1788584400",
            gossipPayload(message).flatMap { String(data: $0, encoding: .utf8) }
        )
        // Sub-second precision is not part of the identity, on either platform.
        var wobbled = message
        wobbled.checkedInAt = message.checkedInAt.addingTimeInterval(0.4)
        XCTAssertEqual(gossipMessageId(message), gossipMessageId(wobbled))
    }

    /// The sub-second part of either time is outside the payload, so a relay can change it
    /// without breaking the signature or the id. It must therefore decide nothing.
    func testASubSecondWobbleARelayCouldAddDecidesNothing() {
        let message = signed(by: alice)
        var wobbled = message
        wobbled.checkedInAt = message.checkedInAt.addingTimeInterval(0.4)
        wobbled.expiresAt = message.expiresAt.addingTimeInterval(0.9)

        let plan = gossipStormGate(seen: [:], batch: [wobbled], from: bob.publicKey,
                                   now: now, contacts: contacts)

        XCTAssertEqual([wobbled], plan.accepted)
        // The expiry held is the signed one, to the second — not the one the wobble asked for.
        XCTAssertEqual([message.messageId: tonight], plan.seen)
    }

    func testACheckInDatedInTheFutureIsRejected() {
        let ahead = signed(by: alice, checkedInAt: now.addingTimeInterval(gossipClockSkew + 1))

        let plan = gossipStormGate(seen: [:], batch: [ahead], from: bob.publicKey,
                                   now: now, contacts: contacts)

        XCTAssertEqual([.futureDated], plan.rejected.map(\.reason))
    }

    func testAClockAFewMinutesOutIsTolerated() {
        let ahead = signed(by: alice, checkedInAt: now.addingTimeInterval(gossipClockSkew - 1))

        let plan = gossipStormGate(seen: [:], batch: [ahead], from: bob.publicKey,
                                   now: now, contacts: contacts)

        XCTAssertEqual([ahead], plan.accepted)
    }

    func testAClaimedExpiryBeyondTheGigsOwnNightEndIsClampedToIt() {
        let nightEnd = now.addingTimeInterval(2 * 3600)
        let greedy = signed(by: alice, expiresAt: now.addingTimeInterval(365 * 24 * 3600))

        let plan = gossipStormGate(seen: [:], batch: [greedy], from: bob.publicKey,
                                   now: now, contacts: contacts,
                                   nightEndFor: { (gigId: String) -> Date? in
                                       gigId == greedy.gigId ? nightEnd : nil
                                   })

        XCTAssertEqual([greedy], plan.accepted)
        XCTAssertEqual([greedy.messageId: nightEnd], plan.seen)
    }

    func testANightThatHasAlreadyEndedExpiresTheMessageWhateverItClaims() {
        let greedy = signed(by: alice, expiresAt: now.addingTimeInterval(365 * 24 * 3600))

        let plan = gossipStormGate(seen: [:], batch: [greedy], from: bob.publicKey,
                                   now: now, contacts: contacts,
                                   nightEndFor: { (_: String) -> Date? in
                                       self.now.addingTimeInterval(-1)
                                   })

        XCTAssertEqual([.expired], plan.rejected.map(\.reason))
    }

    /// A gig I have never heard of still cannot buy an unbounded lifetime.
    func testAnUnknownGigCapsTheLifetimeAtOneNightFromTheCheckIn() {
        let checkedInAt = now.addingTimeInterval(-600)
        let greedy = signed(by: alice, checkedInAt: checkedInAt,
                            expiresAt: now.addingTimeInterval(365 * 24 * 3600))

        let plan = gossipStormGate(seen: [:], batch: [greedy], from: bob.publicKey,
                                   now: now, contacts: contacts)

        XCTAssertEqual([greedy.messageId: checkedInAt.addingTimeInterval(gossipMaxLifetime)],
                       plan.seen)
    }

    func testAnEarlierClaimedExpiryIsHonouredRatherThanWidened() {
        let early = now.addingTimeInterval(30 * 60)
        let message = signed(by: alice, expiresAt: early)

        let plan = gossipStormGate(seen: [:], batch: [message], from: bob.publicKey,
                                   now: now, contacts: contacts,
                                   nightEndFor: { (_: String) -> Date? in
                                       self.now.addingTimeInterval(9 * 3600)
                                   })

        XCTAssertEqual([message.messageId: early], plan.seen)
    }

    func testTheSeenSetIsPrunedAsItsEntriesExpire() {
        let message = signed(by: alice)
        let seen = [
            "old": now.addingTimeInterval(-1),
            "ending-now": now,
            "still-live": now.addingTimeInterval(1),
        ]

        let plan = gossipStormGate(seen: seen, batch: [message], from: bob.publicKey,
                                   now: now, contacts: contacts)

        XCTAssertEqual(["still-live": now.addingTimeInterval(1), message.messageId: tonight],
                       plan.seen)
    }

    func testRelayGoesToEveryOtherContactAndNeverBackToTheSenderOrTheAuthor() {
        let dave = identity()
        let message = signed(by: alice)

        let plan = gossipStormGate(seen: [:], batch: [message], from: bob.publicKey, now: now,
                                   contacts: contacts.union([dave.publicKey]))

        XCTAssertEqual(1, plan.relay.count)
        XCTAssertEqual([carol.publicKey, dave.publicKey].sorted(), plan.relay.first?.to)
    }

    /// Nobody left to tell is not an error, and it is not a relay either.
    func testAMessageWithNobodyToRelayItToIsAcceptedAndNotRelayed() {
        let message = signed(by: alice)

        let plan = gossipStormGate(seen: [:], batch: [message], from: bob.publicKey, now: now,
                                   contacts: [alice.publicKey, bob.publicKey])

        XCTAssertEqual([message], plan.accepted)
        XCTAssertTrue(plan.relay.isEmpty)
    }

    /// The load-bearing distinction: a **Followed line** holds no key, so it can neither
    /// author a message that is accepted nor appear in a relay audience. Only a **Contact**
    /// — a person whose key arrived in person at an **Exchange** — can.
    func testAFollowedLineIsNotInTheRelayAudienceAndCannotAuthorAMessage() {
        let friends = [
            Friend(setlistfm: "alice", publicKey: alice.publicKey),
            Friend(setlistfm: "bob", publicKey: bob.publicKey),
            Friend(setlistfm: "carol", publicKey: carol.publicKey),
            // Followed lines: pulled from setlist.fm, never met, no key. Including a blank
            // one, which is the shape an older cache can hold.
            Friend(setlistfm: "dave"),
            Friend(setlistfm: "erin", publicKey: " "),
        ]
        let keys = contactKeysOf(friends)

        XCTAssertEqual(Set([alice.publicKey, bob.publicKey, carol.publicKey]), keys)

        let plan = gossipStormGate(seen: [:], batch: [signed(by: alice)], from: bob.publicKey,
                                   now: now, contacts: keys)

        XCTAssertEqual([carol.publicKey], plan.relay.first?.to)
    }

    func testTheSameInputTwiceGivesTheSamePlan() {
        let messages = [signed(by: alice), signed(by: bob, gigId: "another-gig")]
        let seen = ["older": now.addingTimeInterval(60)]

        let first = gossipStormGate(seen: seen, batch: messages, from: bob.publicKey,
                                    now: now, contacts: contacts)
        let second = gossipStormGate(seen: seen, batch: messages, from: bob.publicKey,
                                     now: now, contacts: contacts)

        XCTAssertEqual(first, second)
        XCTAssertEqual(2, first.accepted.count)
    }

    func testAGigIdThatCouldEscapeADirectoryIsRefused() {
        let nasty = signed(by: alice, gigId: "../../etc/passwd")

        let plan = gossipStormGate(seen: [:], batch: [nasty], from: bob.publicKey,
                                   now: now, contacts: contacts)

        XCTAssertEqual([.malformed], plan.rejected.map(\.reason))
    }

    /// A gig id is ASCII or it is nothing. The two platforms read "alphanumeric" differently
    /// once a character leaves ASCII, and a message that propagates through iPhones and dies
    /// at every Android hop is the asymmetry the port exists to prevent.
    func testAGigIdOutsideASCIIIsRefusedOnBothPlatforms() {
        XCTAssertTrue(isSafeGossipId("3ba1f9ca"))
        XCTAssertTrue(isSafeGossipId("a-b_C9"))
        XCTAssertTrue(isSafeGossipId(String(repeating: "f", count: 64)))

        XCTAssertFalse(isSafeGossipId(""))
        XCTAssertFalse(isSafeGossipId(String(repeating: "f", count: 65)))
        // A letter Kotlin and Swift agree is a letter, and one they do not.
        XCTAssertFalse(isSafeGossipId("cafe\u{0301}"))
        XCTAssertFalse(isSafeGossipId("gig\u{1D7CE}"))
        XCTAssertFalse(isSafeGossipId("gig.1"))
        XCTAssertFalse(isSafeGossipId("gig id"))
    }

    /// Nothing is lost by hitting the limit: it is not remembered, so it can come again.
    func testOneHandoverIsReadForAtMostABatchsWorth() {
        let batch = (1...(gossipMaxBatch + 2)).map {
            signed(by: alice, checkedInAt: now.addingTimeInterval(-60 * Double($0)))
        }

        let plan = gossipStormGate(seen: [:], batch: batch, from: bob.publicKey,
                                   now: now, contacts: contacts)

        XCTAssertEqual(gossipMaxBatch, plan.accepted.count)
        XCTAssertEqual([.batchLimit, .batchLimit], plan.rejected.map(\.reason))
        XCTAssertEqual(gossipMaxBatch, plan.seen.count)

        // The overflow is judged on its merits the next time it is offered.
        let again = gossipStormGate(seen: plan.seen, batch: Array(batch.suffix(2)),
                                    from: bob.publicKey, now: now, contacts: contacts)
        XCTAssertEqual(Array(batch.suffix(2)), again.accepted)
    }

    /// A time no envelope could honestly carry. This is where a `Date` — a `Double` — would
    /// trap the process on a peer's bytes if the conversion were not total, and both twins
    /// refuse the same envelopes so that one cannot relay what the other cannot read.
    func testATimeOutsideTheAgreedRangeIsNotEncodable() {
        let message = signed(by: alice)
        var huge = message
        huge.expiresAt = Date(timeIntervalSince1970: 1e30)
        var notANumber = message
        notANumber.checkedInAt = Date(timeIntervalSince1970: .nan)

        XCTAssertNil(gossipPayload(huge))
        XCTAssertNil(gossipPayload(notANumber))
        XCTAssertNil(gossipEpochSeconds(Date(timeIntervalSince1970: .infinity)))

        let plan = gossipStormGate(seen: [:], batch: [huge, notANumber], from: bob.publicKey,
                                   now: now, contacts: contacts)
        XCTAssertEqual([.forgedId, .forgedId], plan.rejected.map(\.reason))
    }

    /// A peer that hands over fifty copies of one real message should cost fifty lookups,
    /// not fifty signature verifications.
    func testARepeatOfAMessageAlreadySeenIsNotVerifiedAgain() {
        let message = signed(by: alice)
        var verifications = 0

        let plan = gossipStormGate(seen: [:], batch: Array(repeating: message, count: 50),
                                   from: bob.publicKey, now: now, contacts: contacts,
                                   verify: { candidate in
                                       verifications += 1
                                       return verifyGossipSignature(candidate)
                                   })

        XCTAssertEqual([message], plan.accepted)
        XCTAssertEqual(1, verifications)
    }

    /// The expiry a checking-in device stamps is the night window this app already draws —
    /// `nightWindow`, off `nightEndsHour` — and not a second rule that could drift from it.
    func testTheExpiryACheckInCarriesIsTheGigsOwnNightEnd() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/Oslo")!

        XCTAssertEqual(nightWindow(gigDate: "04-09-2026", calendar: calendar)?.upperBound,
                       gossipExpiry(gigDate: "04-09-2026", calendar: calendar))
        // 2026-09-05T06:00 in Oslo, which is 04:00Z — the instant Android's twin asserts.
        XCTAssertEqual(Date(timeIntervalSince1970: 1_788_580_800),
                       gossipExpiry(gigDate: "04-09-2026", calendar: calendar))
        XCTAssertNil(gossipExpiry(gigDate: "not a date", calendar: calendar))
    }

    func testOneNightIsTheLongestAMessageCanLive() {
        XCTAssertEqual(30 * 3600, gossipMaxLifetime)
    }
}
