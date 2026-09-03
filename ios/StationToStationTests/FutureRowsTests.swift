import XCTest
@testable import StationToStation

/// The future lane draws one node per evening, exactly as the Spine does below today
/// (#134) — the `futureRows` entry point itself, over and above `groupIntoFestivals`
/// (`FestivalGroupingTests`) and `plannedLane` (`SpineNightsTests`) that it composes.
final class FutureRowsTests: XCTestCase {

    private func planned(_ id: String, _ date: String, _ artist: String,
                         venue: String = "Rockefeller") -> FmSetlist {
        FmSetlist(id: id, eventDate: date, artist: FmArtist(name: artist),
                  venue: FmVenue(name: venue), url: "https://setlist.fm/\(id)")
    }

    private func stillPlanned(_ gigs: [FmSetlist]) -> [String: StoredAttendance] {
        Dictionary(uniqueKeysWithValues: gigs.map { ($0.id, StoredAttendance(provenance: "planned")) })
    }

    func testTwoPlannedNightsAtOnePlaceAreOneRowAboveTodayToo() {
        // The same shape below today is the same shape above it — the lane used to draw
        // them as two loose nodes only because it did its own grouping, which was none
        // (#134).
        let tickets = [
            planned("g1", "20-08-2026", "Low Tide", venue: "Sentrum"),
            planned("g2", "20-08-2026", "Nord&Nord", venue: "Sentrum"),
        ]
        let rows = futureRows(tickets: tickets, attendance: stillPlanned(tickets))
        XCTAssertEqual(1, rows.count)
        guard case .ticket(let node) = rows[0], case .section = node else {
            return XCTFail("two planned nights at one venue should be one Section")
        }
        XCTAssertEqual(2, node.shows.count)
    }

    func testTheFutureLaneIsFurthestFutureFirst() {
        let tickets = [planned("g1", "20-08-2026", "Low Tide"),
                       planned("g2", "01-08-2026", "Nord&Nord")]
        let rows = futureRows(tickets: tickets, attendance: stillPlanned(tickets))
        XCTAssertEqual(["planned-g1", "planned-g2"], rows.map(\.id))
    }
}
