import CryptoKit
import Foundation
import XCTest
@testable import StationToStation

/// The rotating per-**Contact** token (#417, #416, ADR-0019 amendment of 2026-09-08). No radio
/// and no keychain: the derivation is a function of two public keys and a clock, which is
/// exactly why it can be pinned to a fixed vector and why Android's `GossipTokenTest` pins
/// itself to the same one.
///
/// The vector below is the contract between the two platforms. If it changes, an iPhone stops
/// recognising a Pixel it has already met, silently — no error, no log, just two phones that
/// never gossip again. It is asserted first for that reason.
///
/// Keys here are stand-in strings, not real SPKI, exactly as in the Kotlin twin: the derivation
/// treats them as opaque bytes, and a fixture that has to generate keypairs to test a string
/// sort is a fixture that hides what it is asserting.
final class GossipTokenTests: XCTestCase {

    /// 2026-09-04T21:00:00Z — the instant every gossip test on both platforms pins to. It is
    /// exactly on a bucket boundary (1_788_555_600 = 1_987_284 × 900), which is deliberate:
    /// the boundary is where a floor/truncate disagreement would show.
    private let now = Date(timeIntervalSince1970: 1_788_555_600)
    private let bucket: Int64 = 1_987_284

    private let mine = "AAAAkey-mine"
    private let theirs = "ZZZZkey-theirs"
    private let stranger = "MMMMkey-stranger"

    // --- The cross-platform vector ---

    /// **The one assertion that would catch the two platforms drifting apart.** Every other
    /// test in this file passes just as happily against a derivation Android cannot reproduce.
    /// `GossipTokenTest.kt` asserts this exact string from the same two keys and the same
    /// bucket. Do not change these values to make a failure go away.
    func testTheTokenMatchesTheFixedCrossPlatformVector() {
        XCTAssertEqual(gossipToken(mine: "AAAAkey-mine", theirs: "ZZZZkey-theirs",
                                   bucket: 1_987_284),
                       "09f8a789e6db4230")
        XCTAssertEqual(gossipTokenBucket(Date(timeIntervalSince1970: 1_788_555_600)), 1_987_284)
    }

    /// Sorted before hashing, so whichever of the pair is speaking computes the same bytes.
    /// Without this a token would only ever be recognised in one direction — the kind of bug
    /// that looks like a flaky radio.
    func testBothEndsOfAPairDeriveTheSameToken() {
        XCTAssertEqual(gossipToken(mine: mine, theirs: theirs, bucket: bucket),
                       gossipToken(mine: theirs, theirs: mine, bucket: bucket))
    }

    func testATokenIsEightBytesOfLowerCaseHex() {
        let token = gossipToken(mine: mine, theirs: theirs, bucket: bucket)

        XCTAssertEqual(token?.count, gossipTokenBytes * 2)
        XCTAssertTrue(isSafeGossipToken(token ?? ""))
    }

    func testADifferentPairIsADifferentToken() {
        XCTAssertNotEqual(gossipToken(mine: mine, theirs: theirs, bucket: bucket),
                          gossipToken(mine: mine, theirs: stranger, bucket: bucket))
    }

    func testTheTokenRotatesWithTheBucket() {
        XCTAssertNotEqual(gossipToken(mine: mine, theirs: theirs, bucket: bucket),
                          gossipToken(mine: mine, theirs: theirs, bucket: bucket + 1))
    }

    /// The keys are joined with a newline, so a key that could carry one is refused rather
    /// than escaped — otherwise two different pairs could encode identically.
    func testAKeyCarryingTheJoiningNewlineIsRefusedRatherThanEscaped() {
        XCTAssertNil(gossipToken(mine: "mine\nsplit", theirs: theirs, bucket: bucket))
        XCTAssertNil(gossipToken(mine: mine, theirs: "theirs\nsplit", bucket: bucket))
        XCTAssertNil(gossipToken(mine: "", theirs: theirs, bucket: bucket))
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

    func testTheTableCoversTheBucketEitherSideOfItsOwn() {
        let table = gossipTokenTable(mine: mine, contacts: [theirs], now: now)

        XCTAssertEqual(table.count, 3)
        for offset in -1...1 {
            let token = gossipToken(mine: theirs, theirs: mine, bucket: bucket + Int64(offset))
            XCTAssertEqual(table[token ?? ""], theirs)
        }
        XCTAssertNil(table[gossipToken(mine: theirs, theirs: mine, bucket: bucket + 2) ?? ""])
    }

    /// Two phones whose clocks differ by ten minutes still recognise each other; the skew
    /// window is what makes that true, and it is the whole reason a publisher offers one
    /// bucket while a reader holds three.
    func testTwoPhonesTenMinutesApartStillRecogniseEachOther() {
        let theirOffer = gossipTokenOffer(mine: theirs, contacts: [mine],
                                          now: now.addingTimeInterval(600))
        let myTable = gossipTokenTable(mine: mine, contacts: [theirs], now: now)

        XCTAssertEqual(gossipResolveOffer(theirOffer, table: myTable), theirs)
    }

    func testAPhoneAnHourOutOfStepIsNotRecognised() {
        let theirOffer = gossipTokenOffer(mine: theirs, contacts: [mine],
                                          now: now.addingTimeInterval(3600))
        let myTable = gossipTokenTable(mine: mine, contacts: [theirs], now: now)

        XCTAssertNil(gossipResolveOffer(theirOffer, table: myTable))
    }

    func testAFollowedLineContributesNothingToTheTable() {
        XCTAssertTrue(gossipTokenTable(mine: mine, contacts: [], now: now).isEmpty)
        XCTAssertTrue(gossipTokenTable(mine: mine, contacts: ["", "  "], now: now).isEmpty)
    }

    // --- The offer ---

    func testTheOfferIsOneTokenPerContactSortedSoItsOrderSaysNothing() {
        let offer = gossipTokenOffer(mine: mine, contacts: [stranger, theirs, theirs], now: now)

        XCTAssertEqual(offer.count, 2)
        XCTAssertEqual(offer, offer.sorted())
        XCTAssertTrue(offer.allSatisfy(isSafeGossipToken))
        XCTAssertEqual(Set(offer), Set([gossipToken(mine: mine, theirs: theirs, bucket: bucket),
                                        gossipToken(mine: mine, theirs: stranger, bucket: bucket)]
                                        .compactMap { $0 }))
    }

    /// The listener never names itself: what a stranger reads is a set of numbers that mean
    /// nothing to them and are different in a quarter of an hour.
    func testAnOfferNamesNobody() {
        let offer = gossipTokenOffer(mine: mine, contacts: [theirs], now: now)

        XCTAssertFalse(offer.contains { $0.contains(mine) || $0.contains(theirs) })
    }

    func testAnOfferResolvesToTheContactThatPublishedIt() {
        let theirTable = gossipTokenTable(mine: theirs, contacts: [mine], now: now)

        XCTAssertEqual(gossipResolveOffer(gossipTokenOffer(mine: mine, contacts: [theirs], now: now),
                                          table: theirTable),
                       mine)
    }

    func testAStrangersOfferResolvesToNobody() {
        let theirTable = gossipTokenTable(mine: theirs, contacts: [mine], now: now)
        let strangerOffer = gossipTokenOffer(mine: "nobody", contacts: [stranger], now: now)

        XCTAssertNil(gossipResolveOffer(strangerOffer, table: theirTable))
    }

    /// A device that presents two of my Contacts' tokens at once is not one of them — it is
    /// something that has assembled a set from elsewhere. Attributing its **Pass** to either
    /// Contact would be putting a name the gate trusts on a stranger's messages.
    func testAnOfferNamingTwoContactsAtOnceResolvesToNeither() {
        let table = gossipTokenTable(mine: mine, contacts: [theirs, stranger], now: now)
        let both = [gossipToken(mine: mine, theirs: theirs, bucket: bucket),
                    gossipToken(mine: mine, theirs: stranger, bucket: bucket)].compactMap { $0 }

        XCTAssertNil(gossipResolveOffer(both, table: table))
    }

    func testAnEmptyOfferResolvesToNobodyRatherThanCrashing() {
        XCTAssertNil(gossipResolveOffer([], table: gossipTokenTable(mine: mine, contacts: [theirs],
                                                                    now: now)))
        XCTAssertNil(gossipResolveOffer(["09f8a789e6db4230"], table: [:]))
    }

    func testADeviceWithNoContactsOffersNothing() {
        XCTAssertEqual(gossipTokenOffer(mine: mine, contacts: [], now: now), [])
        XCTAssertEqual(gossipTokenOffer(mine: mine, contacts: ["", "  "], now: now), [])
    }

    // --- The shape check ---

    func testOnlySixteenLowerCaseHexCharactersAreATokenShape() {
        XCTAssertTrue(isSafeGossipToken("09f8a789e6db4230"))
        XCTAssertFalse(isSafeGossipToken(""))
        XCTAssertFalse(isSafeGossipToken("09f8a789e6db42"))
        XCTAssertFalse(isSafeGossipToken("09f8a789e6db4230ff"))
        XCTAssertFalse(isSafeGossipToken("09F8A789E6DB4230"))
        XCTAssertFalse(isSafeGossipToken("09f8a789e6db423g"))
    }

    /// The domain separator, asserted rather than assumed: the pair key is used for nothing
    /// else today, and this is what keeps that safe if it ever is.
    func testTheTokenIsBoundToItsDomainSeparator() {
        let bare = HMAC<SHA256>.authenticationCode(
            for: Data("\(bucket)".utf8),
            using: SymmetricKey(data: Data("\(mine)\n\(theirs)".utf8))
        ).prefix(gossipTokenBytes).map { String(format: "%02x", $0) }.joined()

        XCTAssertNotEqual(gossipToken(mine: mine, theirs: theirs, bucket: bucket), bare)
    }
}
