import XCTest
@testable import SetlistToSpotify

/// Which Line each person is drawn on at a given row. The merge rule lives here:
/// Lines that share a Node become one, and my Spine is only special in that it
/// never moves to meet anyone. Ported from the Android LaneGeometryTest.
final class LaneGeometryTests: XCTestCase {

    private let ozzy = Friend(setlistfm: "Ozzy", name: "Ozzy")
    private let lemmy = Friend(setlistfm: "Lemmy", name: "Lemmy")

    /// Lane 0 is nearest my Spine and belongs to the most recently added friend.
    private var lanes: [Friend] { [ozzy, lemmy] }

    private func row(mine: Bool, _ present: Friend...) -> WovenRow {
        WovenRow(
            node: .concert(FmSetlist(id: "n", artist: FmArtist(name: "A"))),
            mine: mine,
            others: present
        )
    }

    func testAFriendWhoWasntThereStaysInTheirOwnLane() {
        XCTAssertEqual(0, hostLane(row(mine: true), ozzy, lanes))
        XCTAssertEqual(1, hostLane(row(mine: true), lemmy, lanes))
    }

    func testANightIWasAtPullsTheirLineOntoMySpine() {
        let night = row(mine: true, ozzy)
        XCTAssertEqual(Spine, hostLane(night, ozzy, lanes))
        XCTAssertEqual(1, hostLane(night, lemmy, lanes)) // not there, own lane
    }

    func testTwoFriendsAtANightIMissedMergeOntoTheLaneNearestMySpine() {
        let night = row(mine: false, ozzy, lemmy)
        XCTAssertEqual(0, nodeHost(night, lanes))
        XCTAssertEqual(0, hostLane(night, ozzy, lanes))
        XCTAssertEqual(0, hostLane(night, lemmy, lanes)) // came to meet the inner lane
    }

    func testOneFriendAloneAtANightIMissedKeepsTheirOwnLane() {
        let night = row(mine: false, lemmy)
        XCTAssertEqual(1, nodeHost(night, lanes))
        XCTAssertEqual(1, hostLane(night, lemmy, lanes))
        XCTAssertFalse(joinedAt(night, lemmy)) // alone is not company
    }

    func testCompanyIsGreenWhoeverItIsWith() {
        XCTAssertTrue(joinedAt(row(mine: true, ozzy), ozzy))
        XCTAssertTrue(joinedAt(row(mine: false, ozzy, lemmy), lemmy))
        XCTAssertFalse(joinedAt(row(mine: true), ozzy))
    }

    func testOnePartingOnTheRowTheOtherJoinsIsTwoIndependentAnswers() {
        // Above: I was out with Lemmy. Here: with Ozzy instead.
        let above = row(mine: true, lemmy)
        let here = row(mine: true, ozzy)

        // Ozzy comes in from their lane to my spine.
        XCTAssertEqual(0, hostLane(above, ozzy, lanes))
        XCTAssertEqual(Spine, hostLane(here, ozzy, lanes))
        // Lemmy leaves my spine for theirs, on the same row. Neither
        // answer depends on the other, which is what a shared Boolean got wrong.
        XCTAssertEqual(Spine, hostLane(above, lemmy, lanes))
        XCTAssertEqual(1, hostLane(here, lemmy, lanes))
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
