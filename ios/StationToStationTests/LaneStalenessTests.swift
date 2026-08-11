import XCTest
@testable import StationToStation

/// The rule behind #77's refetch: a Lane is stale when missing, empty, or
/// truncated short of my own oldest Gig. Asserts external behaviour only —
/// call order and page counts belong to #70's fake plumbing, not here.
final class LaneStalenessTests: XCTestCase {

    private func show(_ id: String, _ date: String) -> FmSetlist {
        FmSetlist(id: id, eventDate: date, artist: FmArtist(name: "The Warning"))
    }

    func testMissingLaneIsStale() {
        XCTAssertTrue(laneIsStale(nil, oldestOfMine: Date()))
    }

    func testEmptyLaneIsStale() {
        XCTAssertTrue(laneIsStale([], oldestOfMine: Date()))
    }

    func testALaneReachingBackToMyOldestGigIsNotStale() {
        let mine = show("mine", "25-06-2019").localDate()
        let theirs = [show("a", "01-01-2026"), show("b", "20-06-2019")]
        XCTAssertFalse(laneIsStale(theirs, oldestOfMine: mine))
    }

    func testALaneFallingShortOfMyOldestGigIsStale() {
        let mine = show("mine", "25-06-2019").localDate()
        let theirs = [show("a", "01-01-2026"), show("b", "10-01-2020")]
        XCTAssertTrue(laneIsStale(theirs, oldestOfMine: mine))
    }

    func testWithNoGigsOfMyOwnNothingIsStale() {
        let theirs = [show("a", "01-01-2026")]
        XCTAssertFalse(laneIsStale(theirs, oldestOfMine: nil))
    }
}
