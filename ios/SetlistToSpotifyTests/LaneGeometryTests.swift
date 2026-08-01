import XCTest
@testable import SetlistToSpotify

/// Which Line each person is drawn on at a given row. The merge rule lives here:
/// Lines that share a Node become one, and my Spine is only special in that it
/// never moves to meet anyone. Ported from the Android LaneGeometryTest.
final class LaneGeometryTests: XCTestCase {

    private let egil = Friend(setlistfm: "Egil", name: "Egil")
    private let trummis = Friend(setlistfm: "Trummispojken", name: "Trummispojken")

    /// Lane 0 is nearest my Spine and belongs to the most recently added friend.
    private var lanes: [Friend] { [egil, trummis] }

    private func row(mine: Bool, _ present: Friend...) -> WovenRow {
        WovenRow(
            node: .concert(FmSetlist(id: "n", artist: FmArtist(name: "A"))),
            mine: mine,
            others: present
        )
    }

    func testAFriendWhoWasntThereStaysInTheirOwnLane() {
        XCTAssertEqual(0, hostLane(row(mine: true), egil, lanes))
        XCTAssertEqual(1, hostLane(row(mine: true), trummis, lanes))
    }

    func testANightIWasAtPullsTheirLineOntoMySpine() {
        let night = row(mine: true, egil)
        XCTAssertEqual(Spine, hostLane(night, egil, lanes))
        XCTAssertEqual(1, hostLane(night, trummis, lanes)) // not there, own lane
    }

    func testTwoFriendsAtANightIMissedMergeOntoTheLaneNearestMySpine() {
        let night = row(mine: false, egil, trummis)
        XCTAssertEqual(0, nodeHost(night, lanes))
        XCTAssertEqual(0, hostLane(night, egil, lanes))
        XCTAssertEqual(0, hostLane(night, trummis, lanes)) // came to meet the inner lane
    }

    func testOneFriendAloneAtANightIMissedKeepsTheirOwnLane() {
        let night = row(mine: false, trummis)
        XCTAssertEqual(1, nodeHost(night, lanes))
        XCTAssertEqual(1, hostLane(night, trummis, lanes))
        XCTAssertFalse(joinedAt(night, trummis)) // alone is not company
    }

    func testCompanyIsGreenWhoeverItIsWith() {
        XCTAssertTrue(joinedAt(row(mine: true, egil), egil))
        XCTAssertTrue(joinedAt(row(mine: false, egil, trummis), trummis))
        XCTAssertFalse(joinedAt(row(mine: true), egil))
    }

    func testOnePartingOnTheRowTheOtherJoinsIsTwoIndependentAnswers() {
        // Above: I was out with Trummispojken. Here: with Egil instead.
        let above = row(mine: true, trummis)
        let here = row(mine: true, egil)

        // Egil comes in from their lane to my spine.
        XCTAssertEqual(0, hostLane(above, egil, lanes))
        XCTAssertEqual(Spine, hostLane(here, egil, lanes))
        // Trummispojken leaves my spine for theirs, on the same row. Neither
        // answer depends on the other, which is what a shared Boolean got wrong.
        XCTAssertEqual(Spine, hostLane(above, trummis, lanes))
        XCTAssertEqual(1, hostLane(here, trummis, lanes))
    }

    func testTheStripStopsWideningOnceThereAreEnoughFriends() {
        // Few friends: full spacing, strip grows with each one.
        XCTAssertLessThan(stripWidth(2), stripWidth(4))
        XCTAssertEqual(laneStep(2), laneStep(4), accuracy: 0.01)
        // Many: lanes tighten instead of pushing the timeline off the phone.
        XCTAssertEqual(stripWidth(8), stripWidth(20), accuracy: 0.01)
        XCTAssertLessThan(laneStep(20), laneStep(8))
    }

    /// Nobody is drawn in a lane they don't have. Kotlin's indexOfFirst returns
    /// -1 (the Spine) for a stranger; lane 0 belongs to a real friend.
    func testAFriendWithNoLaneIsNotGivenLaneZero() {
        let stranger = Friend(setlistfm: "nobody")
        XCTAssertEqual(Spine, hostLane(row(mine: true), stranger, lanes))
    }
}
