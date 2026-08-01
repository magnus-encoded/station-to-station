import XCTest
@testable import SetlistToSpotify

/// The Festival name is scraped from the setlist page — setlist.fm's REST API has
/// no festival field — so pin the shape we rely on. Ported from Android's
/// FestivalNameParseTest.
final class FestivalNameParseTests: XCTestCase {

    func testReadsTheFestivalNameFromThePlayedAtLink() {
        let html = """
        <div class="festivalBg"><h2 class="festivalHeadline">Hey, this setlist was played at a festival:</h2>
        <a class="nested" href="../../../festival/2025/oyafestivalen-2025-73d58625.html"
           title="View Øyafestivalen 2025 details">Øyafestivalen 2025</a></div>
        """
        XCTAssertEqual("Øyafestivalen 2025", parseFestivalName(html))
    }

    func testAPlainClubShowHasNoFestivalLink() {
        XCTAssertNil(parseFestivalName("<html><body>Blå, Oslo, Norway</body></html>"))
    }
}
