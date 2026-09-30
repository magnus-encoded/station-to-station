import XCTest
@testable import StationToStation

final class DeepLinkTests: XCTestCase {

    private let gigs: [(id: String, date: String)] = [("a", "2026-08-01"), ("b", "2026-08-11")]

    func testADateOnAGigLandsOnThatGig() {
        XCTAssertEqual("b", nearestGig(gigs, to: "2026-08-11"))
    }

    func testADateBetweenTwoGigsLandsOnTheCloser() {
        XCTAssertEqual("a", nearestGig(gigs, to: "2026-08-05"))
        XCTAssertEqual("b", nearestGig(gigs, to: "2026-08-08"))
    }

    func testATieGoesToTheEarlierGig() {
        XCTAssertEqual("a", nearestGig(gigs, to: "2026-08-06"))
        XCTAssertEqual("a", nearestGig(gigs.reversed(), to: "2026-08-06"))
    }

    func testADateOutsideEveryGigLandsOnTheNearestEnd() {
        XCTAssertEqual("a", nearestGig(gigs, to: "2020-01-01"))
        XCTAssertEqual("b", nearestGig(gigs, to: "2030-01-01"))
    }

    func testNoGigsIsNowhere() {
        XCTAssertNil(nearestGig([], to: "2026-08-06"))
    }

    func testANightBeforeTodayIsOneIWasAt() {
        XCTAssertEqual(.wasAt, nightKind(date: "2026-09-28", today: "2026-09-29"))
    }

    func testTodayIsAGigIAmGoingTo() {
        XCTAssertEqual(.goingTo, nightKind(date: "2026-09-29", today: "2026-09-29"))
    }

    func testALaterNightOrNoDateIsAGigIAmGoingTo() {
        XCTAssertEqual(.goingTo, nightKind(date: "2026-09-30", today: "2026-09-29"))
        XCTAssertEqual(.goingTo, nightKind(date: nil, today: "2026-09-29"))
    }

    func testAGigOnMyLineOpensWithoutBeingAdded() {
        XCTAssertEqual(.open, planOpenGig("334c742d", onMyLine: true))
    }

    func testAnUnknownSetlistFmIdIsFetchedThenOpened() {
        XCTAssertEqual(.fetchThenOpen, planOpenGig("334c742d", onMyLine: false))
    }

    func testAnUnknownIdWithNothingToFetchIsRefused() {
        XCTAssertEqual(.refuse, planOpenGig("not an id!", onMyLine: false))
    }

    private func log(_ songs: String...) -> StoredLog {
        songs.reduce(StoredLog()) { $0.adding($1) }
    }

    func testBareItemsAppendInOrder() {
        XCTAssertEqual(["A", "B", "C", "D"], log("A", "B").writing(appends: ["C", "D"], replacements: [:]).songs)
    }

    func testANumberedItemReplacesThatSong() {
        XCTAssertEqual(["A", "X", "C"], log("A", "B", "C").writing(appends: [], replacements: [2: "X"]).songs)
    }

    func testANumberJustPastTheEndAppends() {
        XCTAssertEqual(["A", "B", "X"], log("A", "B").writing(appends: [], replacements: [3: "X"]).songs)
    }

    func testANumberFurtherOutIsIgnored() {
        XCTAssertEqual(["A", "B"], log("A", "B").writing(appends: [], replacements: [4: "X"]).songs)
    }

    func testConsecutiveNumbersPastTheEndBuildTheLogPositionByPosition() {
        XCTAssertEqual(["A", "B", "C"], log("A").writing(appends: [], replacements: [2: "B", 3: "C"]).songs)
    }
}
