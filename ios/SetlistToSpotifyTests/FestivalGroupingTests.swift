import XCTest
@testable import SetlistToSpotify

/// Ported from the Android FestivalGroupingTest, case for case: the two platforms
/// must cluster the same shows into the same festivals.
final class FestivalGroupingTests: XCTestCase {

    private func show(_ id: String, _ date: String, _ venue: String, info: String? = nil) -> FmSetlist {
        FmSetlist(
            id: id,
            eventDate: date, // dd-MM-yyyy
            artist: FmArtist(name: "Artist \(id)"),
            venue: FmVenue(name: venue),
            info: info
        )
    }

    func testFestivalIsNamedByVenueNotFreeTextInfo() {
        // setlist.fm `info` is arbitrary notes, not the festival name, so it must
        // never leak into the label.
        let nodes = groupIntoFestivals([
            show("1", "08-08-2025", "Tøyenparken", info: "a long editorial note"),
            show("2", "07-08-2025", "Tøyenparken", info: "First show in Norway"),
        ])
        guard case .festival(let name, _)? = nodes.first, nodes.count == 1 else {
            return XCTFail("expected one festival, got \(nodes.count)")
        }
        XCTAssertEqual("Tøyenparken", name)
    }

    func testSameVenueAdjacentDatesBecomeOneFestival() {
        let nodes = groupIntoFestivals([
            show("1", "25-06-2026", "Ekebergsletta"),
            show("2", "25-06-2026", "Ekebergsletta"),
            show("3", "24-06-2026", "Ekebergsletta"),
        ])
        XCTAssertEqual(1, nodes.count)
        guard case .festival(let name, let shows)? = nodes.first else {
            return XCTFail("expected a festival")
        }
        XCTAssertEqual(3, shows.count)
        XCTAssertEqual("Ekebergsletta", name)
    }

    func testALoneShowStaysAConcert() {
        let nodes = groupIntoFestivals([show("1", "10-05-2026", "Sentrum Scene")])
        XCTAssertEqual(1, nodes.count)
        XCTAssertFalse(nodes[0].isFestival)
    }

    func testSameVenueMonthsApartDoesNotGroup() {
        let nodes = groupIntoFestivals([
            show("1", "25-06-2026", "Rockefeller"),
            show("2", "10-01-2026", "Rockefeller"),
        ])
        XCTAssertEqual(2, nodes.count)
        XCTAssertTrue(nodes.allSatisfy { !$0.isFestival })
    }

    func testDifferentVenuesOnCloseDatesStaySeparate() {
        let nodes = groupIntoFestivals([
            show("1", "25-06-2026", "Ekebergsletta"),
            show("2", "24-06-2026", "Sentrum Scene"),
        ])
        XCTAssertEqual(2, nodes.count)
        XCTAssertTrue(nodes.allSatisfy { !$0.isFestival })
    }

    /// The window is four days, and it is measured between *adjacent* shows —
    /// pinned because it is the one number in the clustering rule.
    func testTheWindowIsFourDaysBetweenAdjacentShows() {
        let inside = groupIntoFestivals([
            show("1", "29-06-2026", "Ekebergsletta"),
            show("2", "25-06-2026", "Ekebergsletta"),
        ])
        XCTAssertEqual(1, inside.count)
        let outside = groupIntoFestivals([
            show("1", "30-06-2026", "Ekebergsletta"),
            show("2", "25-06-2026", "Ekebergsletta"),
        ])
        XCTAssertEqual(2, outside.count)
    }

    /// A gig with no date can't be clustered — and must not take the run with it.
    func testAnUndatedShowStaysAConcert() {
        let nodes = groupIntoFestivals([
            FmSetlist(id: "x", venue: FmVenue(name: "Ekebergsletta")),
            show("2", "25-06-2026", "Ekebergsletta"),
        ])
        XCTAssertEqual(2, nodes.count)
        XCTAssertTrue(nodes.allSatisfy { !$0.isFestival })
    }
}
