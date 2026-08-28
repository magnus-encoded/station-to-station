import XCTest
@testable import StationToStation

/// The two lanes, and the night that fell between them.
///
/// A **Gig** leaves `plannedLane` the moment it stops being a plan, so if the Spine is
/// setlist.fm's **Attended** list alone, a night I checked into that setlist.fm has never
/// heard of is on neither list and draws nowhere. That is not hypothetical: it happened
/// on Android to Nick Cave at Øya 2026, holding a fifteen-song **Log** and seven
/// photographs, with both lanes working exactly as written. iOS had the same two lanes
/// and neither of these functions (#341), so it had the same hole.
///
/// Case for case with Android's `SpineNightsTest`.
final class SpineNightsTests: XCTestCase {

    private func show(_ id: String, _ date: String, venue: String = "Tøyenparken") -> FmSetlist {
        FmSetlist(id: id, eventDate: date, artist: FmArtist(name: "Artist \(id)"),
                  venue: FmVenue(name: venue))
    }

    private let checkedIn = StoredAttendance(provenance: "checked_in")
    private let planned = StoredAttendance(provenance: "planned")

    func testACheckedInNightSetlistFmNeverHeardOfIsOnTheSpine() {
        let spine = spineNights(attended: [show("wilco", "13-08-2026")],
                                planned: [show("nickcave", "13-08-2026")],
                                attendance: ["nickcave": checkedIn])
        XCTAssertEqual(["wilco", "nickcave"], spine.map(\.id))
    }

    func testTheSameNightIsNotOnBothLanes() {
        // The exact hand-off: whichever lane takes it, precisely one does.
        let local = [show("nickcave", "13-08-2026")]
        for claim in [checkedIn, planned] {
            let attendance = ["nickcave": claim]
            let onSpine = spineNights(attended: [], planned: local, attendance: attendance).count
            let onFuture = plannedLane(local, attendance).count
            XCTAssertEqual(1, onSpine + onFuture, "one lane draws it, and only one")
        }
    }

    func testAPlanIsLeftToTheFutureLane() {
        let local = [show("nickcave", "13-08-2026")]
        let spine = spineNights(attended: [], planned: local, attendance: ["nickcave": planned])
        XCTAssertEqual([], spine.map(\.id))
    }

    func testAnImportedCopyWinsOverTheLocalOne() {
        // Same night, both lists: one row, and the published record is the one kept.
        let spine = spineNights(attended: [show("nickcave", "13-08-2026")],
                                planned: [show("nickcave", "13-08-2026", venue: "typed by hand")],
                                attendance: ["nickcave": checkedIn])
        XCTAssertEqual(1, spine.count)
        XCTAssertEqual("Tøyenparken", spine.first?.venue?.name)
    }

    func testTheSpineStaysNewestFirst() {
        // The whole timeline reads newest first, and groupIntoFestivals keeps the order
        // it is given — an out-of-order insert would surface as a jumbled Line.
        let spine = spineNights(attended: [show("b", "13-08-2026"), show("a", "01-01-2020")],
                                planned: [show("c", "14-08-2026")],
                                attendance: ["c": checkedIn])
        XCTAssertEqual(["c", "b", "a"], spine.map(\.id))
    }

    func testNothingEvidencedLocallyLeavesTheAttendedListUntouched() {
        let attended = [show("b", "13-08-2026"), show("a", "01-01-2020")]
        XCTAssertEqual(attended.map(\.id),
                       spineNights(attended: attended, planned: [], attendance: [:]).map(\.id))
    }

    func testTheFutureLaneIsFurthestFutureFirstAndPlansOnly() {
        let gigs = [show("soon", "01-08-2026"), show("later", "20-08-2026"),
                    show("gone", "05-08-2026")]
        let lane = plannedLane(gigs, ["soon": planned, "later": planned, "gone": checkedIn])
        XCTAssertEqual(["later", "soon"], lane.map(\.id))
    }
}
