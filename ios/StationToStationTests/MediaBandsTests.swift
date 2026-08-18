import XCTest
@testable import StationToStation

/// The same assertions Android's `MediaBandsTest.kt` runs on the JVM (#162,
/// ported for iOS by #171).
///
/// Every fixture is invented — this repository is public and no real night,
/// person or photograph belongs in a test.
final class MediaBandsTests: XCTestCase {

    private func mine(_ id: String, personal: Bool = false, at: Int64? = nil) -> StoredMedia {
        StoredMedia(id: id, ref: "content://invented/\(id)", capturedAt: at, personal: personal)
    }

    private func theirs(_ id: String, from: String, at: Int64? = nil) -> StoredMedia {
        StoredMedia(id: id, ref: "content://invented/\(id)", capturedAt: at, from: from)
    }

    private func ids(_ media: [StoredMedia]) -> [String] { media.map(\.id) }

    // MARK: - bands and order

    func testMineSplitsByThePersonalBitAndTheirsIsAlwaysShared() {
        let bands = bandsOf([mine("a"), mine("b", personal: true), theirs("t", from: "ida")])
        XCTAssertEqual(["a"], ids(bands.shared))
        XCTAssertEqual(["b"], ids(bands.vault))
        XCTAssertEqual(["t"], ids(bands.received))
    }

    func testReceivedMediaNeverLandsInTheVaultHoweverItsBitIsSet() {
        // A sender's own bit is their business and says nothing about my bands.
        var given = theirs("t", from: "ida")
        given.personal = true
        let bands = bandsOf([mine("a"), given])
        XCTAssertTrue(bands.vault.isEmpty)
        XCTAssertEqual(["t"], ids(bands.received))
    }

    func testMyOwnOrderIsKeptExactlyAsStored() {
        let bands = bandsOf([mine("c", at: 300), mine("a", at: 100), mine("b", at: 200)])
        XCTAssertEqual(["c", "a", "b"], ids(bands.shared))
    }

    func testReceivedMediaIsOrderedByCaptureTime() {
        let bands = bandsOf([theirs("late", from: "ida", at: 900), theirs("early", from: "tor", at: 100)])
        XCTAssertEqual(["early", "late"], ids(bands.received))
    }

    func testReceivedMediaWithoutACaptureTimeKeepsItsArrivalOrderLast() {
        let bands = bandsOf([theirs("unknown", from: "ida"), theirs("stamped", from: "tor", at: 100)])
        XCTAssertEqual(["stamped", "unknown"], ids(bands.received))
    }

    func testANewArrivalNeverMovesOneOfMyPhotographs() {
        // The property the ordering exists for: Reconcile has no time bound, so a
        // Contact made years later drops media into an old night.
        let before = [mine("a"), mine("b")]
        let after = before + [theirs("t", from: "ida", at: 1)]
        XCTAssertEqual(ids(bandsOf(before).shared), ids(bandsOf(after).shared))
    }

    // MARK: - contributors

    func testAnEmptyNightHasNoContributors() {
        XCTAssertEqual(0, bandsOf([]).contributors)
        XCTAssertFalse(bandsOf([]).crossed)
    }

    func testOnlyMineIsOneContributor() {
        let bands = bandsOf([mine("a"), mine("b")])
        XCTAssertEqual(1, bands.contributors)
        XCTAssertFalse(bands.crossed)
    }

    func testOneSenderAndNothingOfMineIsOneContributor() {
        let bands = bandsOf([theirs("t", from: "ida"), theirs("u", from: "ida"), mine("v", personal: true)])
        XCTAssertEqual(1, bands.contributors)
        XCTAssertFalse(bands.crossed)
    }

    func testMinePlusOneSenderIsACrossing() {
        XCTAssertTrue(bandsOf([mine("a"), theirs("t", from: "ida")]).crossed)
    }

    func testTwoSendersAndNothingOfMineIsACrossing() {
        XCTAssertTrue(bandsOf([theirs("t", from: "ida"), theirs("u", from: "tor")]).crossed)
    }

    // MARK: - the release hint, from both gestures

    func testAddingToSharedWhereOneSenderWaitsPromisesACrossing() {
        let night = [theirs("t", from: "ida")]
        XCTAssertEqual(.gained, hintForAdding(night, to: .shared))
    }

    func testAddingToTheVaultPromisesNothing() {
        let night = [theirs("t", from: "ida")]
        XCTAssertEqual(.none, hintForAdding(night, to: .vault))
    }

    func testAddingToABandAlreadyCrossedPromisesNothing() {
        let night = [mine("a"), theirs("t", from: "ida")]
        XCTAssertEqual(.none, hintForAdding(night, to: .shared))
    }

    func testAddingToABandTwoOtherPeopleAreAlreadyInPromisesNothing() {
        // Two senders is already a Crossing, so there is no line left to cross —
        // the count going 2 to 3 is not news.
        let night = [theirs("t", from: "ida"), theirs("u", from: "tor")]
        XCTAssertEqual(.none, hintForAdding(night, to: .shared))
    }

    func testPullingMineOutOfABandTwoOthersAreInWarnsNothing() {
        let night = [mine("a"), theirs("t", from: "ida"), theirs("u", from: "tor")]
        XCTAssertEqual(.none, hintForMoving(night, id: "a", to: .vault))
    }

    func testAddingWhereNobodyElseIsPromisesNothing() {
        XCTAssertEqual(.none, hintForAdding([mine("a", personal: true)], to: .shared))
    }

    func testDraggingUpOutOfTheVaultEarnsTheIdenticalPromise() {
        // Same act, differently sourced — and the same answer, from one derivation.
        let night = [mine("v", personal: true), theirs("t", from: "ida")]
        XCTAssertEqual(.gained, hintForMoving(night, id: "v", to: .shared))
        XCTAssertEqual(hintForAdding([theirs("t", from: "ida")], to: .shared), hintForMoving(night, id: "v", to: .shared))
    }

    func testDraggingMyLastSharedPhotographDownWarnsTheCrossingIsLost() {
        let night = [mine("a"), theirs("t", from: "ida")]
        XCTAssertEqual(.lost, hintForMoving(night, id: "a", to: .vault))
    }

    func testDraggingOneDownWhileAnotherOfMineStaysWarnsNothing() {
        let night = [mine("a"), mine("b"), theirs("t", from: "ida")]
        XCTAssertEqual(.none, hintForMoving(night, id: "a", to: .vault))
    }

    func testDraggingDownWhereNobodyElseIsWarnsNothing() {
        XCTAssertEqual(.none, hintForMoving([mine("a")], id: "a", to: .vault))
    }

    func testReorderingInsideABandNeverPromisesOrWarns() {
        let night = [mine("a"), mine("b"), theirs("t", from: "ida")]
        XCTAssertEqual(.none, hintForMoving(night, id: "a", to: .shared))
    }

    func testAReceivedPhotographOffersNoHintBecauseItCannotMove() {
        let night = [mine("a", personal: true), theirs("t", from: "ida")]
        XCTAssertEqual(.none, hintForMoving(night, id: "t", to: .vault))
    }

    // MARK: - moving

    func testMovingBetweenBandsFlipsThePersonalBit() {
        let night = [mine("a")]
        let after = moveMedia(night, id: "a", to: .vault, index: 0)
        XCTAssertTrue(after.first!.personal)
        XCTAssertFalse(moveMedia(after, id: "a", to: .shared, index: 0).first!.personal)
    }

    func testReorderingWithinABandLeavesEveryBitUntouched() {
        let night = [mine("a"), mine("b"), mine("c"), mine("v", personal: true)]
        let after = moveMedia(night, id: "c", to: .shared, index: 0)
        XCTAssertEqual(["c", "a", "b"], ids(bandsOf(after).shared))
        let before = Dictionary(uniqueKeysWithValues: night.map { ($0.id, $0.personal) })
        let afterMap = Dictionary(uniqueKeysWithValues: after.map { ($0.id, $0.personal) })
        XCTAssertEqual(before, afterMap)
    }

    func testAReceivedPhotographRefusesToMove() {
        let night = [mine("a"), theirs("t", from: "ida")]
        XCTAssertEqual(night, moveMedia(night, id: "t", to: .vault, index: 0))
    }

    func testAnUnknownIdLeavesTheNightAlone() {
        let night = [mine("a")]
        XCTAssertEqual(night, moveMedia(night, id: "nope", to: .vault, index: 0))
    }

    func testMyOwnMediaAlwaysEndsUpLeftOfAnyoneElses() {
        let night = [theirs("t", from: "ida"), mine("v", personal: true)]
        let after = moveMedia(night, id: "v", to: .shared, index: 99)
        XCTAssertEqual(["v", "t"], after.map(\.id))
    }

    func testAnOutOfRangeIndexIsClampedRatherThanTrapping() {
        let night = [mine("a"), mine("b")]
        XCTAssertEqual(["b", "a"], ids(bandsOf(moveMedia(night, id: "b", to: .shared, index: -5)).shared))
    }
}
