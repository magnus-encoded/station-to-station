import XCTest
@testable import SetlistToSpotify

/// The staleness predicate a Followed line's fetch is judged against (#77): does a
/// friend's cached Lane already reach back as far as my own oldest Gig. Ported
/// from Android's `AppViewModel.reachesBack`.
final class FriendLaneStalenessTests: XCTestCase {

    private func show(_ id: String, _ date: String) -> FmSetlist {
        FmSetlist(id: id, eventDate: date, artist: FmArtist(name: "Artist \(id)"))
    }

    func testEmptyLaneIsNotStaleWhenIHaveNoGigsAtAll() {
        // Nothing to reach back to, so an empty lane can't fall short of it —
        // it's the "have.isNullOrEmpty()" branch on the caller's side, not this
        // predicate's job to flag a lane as missing.
        XCTAssertTrue(reachesBack([], oldestOfMine: nil))
    }

    func testLaneOlderThanMyOldestGigReachesBack() {
        let mine = show("m", "01-01-2019").localDate()
        let theirs = [show("a", "05-01-2019"), show("b", "10-06-2026")]
        XCTAssertTrue(reachesBack(theirs, oldestOfMine: mine))
    }

    func testLaneThatStopsShortOfMyOldestGigIsStale() {
        let mine = show("m", "01-01-2019").localDate()
        // Their oldest fetched show is newer than my oldest gig — the page cap
        // truncated before a shared night in 2019 could ever be reached.
        let theirs = [show("a", "10-06-2026"), show("b", "01-01-2022")]
        XCTAssertFalse(reachesBack(theirs, oldestOfMine: mine))
    }

    func testLaneWithNoDatedShowsIsStaleWhenIHaveAnOldestGig() {
        let mine = show("m", "01-01-2019").localDate()
        XCTAssertFalse(reachesBack([FmSetlist(id: "x", artist: FmArtist(name: "A"))], oldestOfMine: mine))
    }

    func testExactlyMyOldestDateReachesBack() {
        let mine = show("m", "01-01-2019").localDate()
        XCTAssertTrue(reachesBack([show("a", "01-01-2019")], oldestOfMine: mine))
    }
}
