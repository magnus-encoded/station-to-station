import CryptoKit
import Foundation
import XCTest
@testable import StationToStation

/// The rotating per-**Contact** token (#417, ADR-0019 amendment). No radio and no keychain:
/// the derivation is a function of a shared secret and a clock, which is exactly why it can
/// be pinned to a fixed vector and why Android's `GossipTokenTest` can pin itself to the same
/// one.
///
/// The vector below is the contract between the two platforms. If it changes, an iPhone stops
/// recognising a Pixel it has already met, silently — no error, no log, just two phones that
/// never gossip again. It is asserted first for that reason.
final class GossipTokenTests: XCTestCase {

    /// 2026-09-04T21:00:00Z — the instant every gossip test on both platforms pins to. It is
    /// exactly on a bucket boundary (1_788_555_600 = 1_987_284 × 900), which is deliberate:
    /// the boundary is where a floor/truncate disagreement would show.
    private let now = Date(timeIntervalSince1970: 1_788_555_600)
    private let bucket: Int64 = 1_987_284

    /// Bytes 0x00…0x1f. A real secret is the X coordinate of an ECDH agreement and cannot be
    /// written down portably; this one can, and the HMAC does not care where its key came from.
    private let secret = Data((0..<32).map { UInt8($0) })

    // --- The cross-platform vector ---

    func testTheTokenMatchesTheFixedCrossPlatformVector() {
        XCTAssertEqual(gossipToken(secret: secret, bucket: bucket),
                       "cd60b9fef6f960c7b6803f1b45608f0f")
    }

    func testTheNeighbouringBucketsMatchTheirVectorsToo() {
        XCTAssertEqual(gossipToken(secret: secret, bucket: bucket - 1),
                       "b800c538b1881751fafc9247cb75f58f")
        XCTAssertEqual(gossipToken(secret: secret, bucket: bucket + 1),
                       "f22a04b0ebea37982abd299143c7a3df")
    }

    func testATokenIs16BytesOfLowerCaseHex() {
        XCTAssertTrue(isSafeGossipToken(gossipToken(secret: secret, bucket: bucket)))
        XCTAssertEqual(gossipToken(secret: secret, bucket: bucket).count, gossipTokenBytes * 2)
    }

    // --- Buckets ---

    func testAnInstantOnTheBoundaryIsTheBucketItStarts() {
        XCTAssertEqual(gossipTokenBucket(now), bucket)
    }

    func testATokenHoldsStillForTheWholeBucketAndThenMoves() {
        XCTAssertEqual(gossipTokenBucket(now.addingTimeInterval(899)), bucket)
        XCTAssertEqual(gossipTokenBucket(now.addingTimeInterval(900)), bucket + 1)
    }

    /// The reason `gossipTokenBucket` floors by hand. Swift's `/` truncates toward zero, so
    /// a naive version would put a pre-epoch instant one bucket above where Android's
    /// `Math.floorDiv` puts it — and the two would then disagree about every token.
    func testAPreEpochInstantFloorsRatherThanTruncates() {
        XCTAssertEqual(gossipTokenBucket(Date(timeIntervalSince1970: -1)), -1)
        XCTAssertEqual(gossipTokenBucket(Date(timeIntervalSince1970: -900)), -1)
        XCTAssertEqual(gossipTokenBucket(Date(timeIntervalSince1970: -901)), -2)
    }

    func testAnAbsurdClockHasNoBucketAtAll() {
        XCTAssertNil(gossipTokenBucket(Date(timeIntervalSince1970: 1e18)))
        XCTAssertNil(gossipTokenBucket(Date(timeIntervalSince1970: .infinity)))
    }

    // --- What a reader accepts ---

    func testAReaderAcceptsTheBucketEitherSideOfItsOwn() {
        let mine = gossipTokens(secret: secret, now: now)

        XCTAssertEqual(mine.count, 3)
        XCTAssertEqual(mine, [gossipToken(secret: secret, bucket: bucket - 1),
                              gossipToken(secret: secret, bucket: bucket),
                              gossipToken(secret: secret, bucket: bucket + 1)])
    }

    /// Two phones whose clocks differ by ten minutes still recognise each other; the skew
    /// window is what makes that true, and it is the whole reason a writer publishes one
    /// bucket while a reader checks three.
    func testTwoPhonesTenMinutesApartStillRecogniseEachOther() {
        let theirs = gossipTokenOffer(secrets: ["them": secret], now: now.addingTimeInterval(600))

        XCTAssertEqual(gossipResolveOffer(theirs, secrets: ["them": secret], now: now), "them")
    }

    func testAPhoneAnHourOutOfStepIsNotRecognised() {
        let theirs = gossipTokenOffer(secrets: ["them": secret], now: now.addingTimeInterval(3600))

        XCTAssertNil(gossipResolveOffer(theirs, secrets: ["them": secret], now: now))
    }

    // --- The offer ---

    func testTheOfferIsOneTokenPerContactSortedSoItsOrderSaysNothing() {
        let secrets = ["alice": Data(repeating: 1, count: 32),
                       "bob": Data(repeating: 2, count: 32),
                       "carol": Data(repeating: 3, count: 32)]

        let offer = gossipTokenOffer(secrets: secrets, now: now)

        XCTAssertEqual(offer.count, 3)
        XCTAssertEqual(offer, offer.sorted())
        XCTAssertTrue(offer.allSatisfy(isSafeGossipToken))
    }

    func testAnOfferResolvesToTheOneContactItBelongsTo() {
        let alice = Data(repeating: 1, count: 32)
        let bob = Data(repeating: 2, count: 32)
        let theirs = gossipTokenOffer(secrets: ["me": alice], now: now)

        XCTAssertEqual(gossipResolveOffer(theirs, secrets: ["alice": alice, "bob": bob], now: now),
                       "alice")
    }

    func testAStrangersOfferResolvesToNobody() {
        let stranger = gossipTokenOffer(secrets: ["them": Data(repeating: 9, count: 32)], now: now)

        XCTAssertNil(gossipResolveOffer(stranger,
                                        secrets: ["alice": Data(repeating: 1, count: 32)],
                                        now: now))
    }

    /// A device that presents two of my Contacts' tokens at once is not one of them — it is
    /// something that has assembled a set from elsewhere. Attributing its batch to either
    /// Contact would be putting a name the gate trusts on a stranger's messages.
    func testAnOfferNamingTwoContactsAtOnceResolvesToNeither() {
        let alice = Data(repeating: 1, count: 32)
        let bob = Data(repeating: 2, count: 32)
        let both = gossipTokenOffer(secrets: ["a": alice, "b": bob], now: now)

        XCTAssertNil(gossipResolveOffer(both, secrets: ["alice": alice, "bob": bob], now: now))
    }

    func testAnEmptyOfferResolvesToNobodyRatherThanCrashing() {
        XCTAssertNil(gossipResolveOffer([], secrets: ["alice": secret], now: now))
        XCTAssertNil(gossipResolveToken("", secrets: ["alice": secret], now: now))
    }

    func testADeviceWithNoContactsOffersNothing() {
        XCTAssertEqual(gossipTokenOffer(secrets: [:], now: now), [])
    }

    // --- The shape check ---

    func testOnlyThirtyTwoLowerCaseHexCharactersAreATokenShape() {
        XCTAssertFalse(isSafeGossipToken(""))
        XCTAssertFalse(isSafeGossipToken("cd60b9fef6f960c7b6803f1b45608f0"))
        XCTAssertFalse(isSafeGossipToken("cd60b9fef6f960c7b6803f1b45608f0ff"))
        XCTAssertFalse(isSafeGossipToken("CD60B9FEF6F960C7B6803F1B45608F0F"))
        XCTAssertFalse(isSafeGossipToken("cd60b9fef6f960c7b6803f1b45608g0f"))
        XCTAssertTrue(isSafeGossipToken("cd60b9fef6f960c7b6803f1b45608f0f"))
    }

    /// The domain separator, asserted rather than assumed: the same secret is used for
    /// nothing else today, and this is what keeps that true if it ever is.
    func testTheTokenIsBoundToItsDomainSeparator() {
        let bare = HMAC<SHA256>.authenticationCode(for: Data("\(bucket)".utf8),
                                                   using: SymmetricKey(data: secret))
            .prefix(gossipTokenBytes).map { String(format: "%02x", $0) }.joined()

        XCTAssertNotEqual(gossipToken(secret: secret, bucket: bucket), bare)
    }
}
