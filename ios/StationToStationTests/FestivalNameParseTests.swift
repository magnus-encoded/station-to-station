import XCTest
@testable import StationToStation

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

    /// The identity, not just the label (#166). The slug in the href is setlist.fm's
    /// own key for the festival — the same one across every act and every year's
    /// edition — so it, and never the name, is what a stored identity is derived from.
    func testReadsTheIdentityOutOfTheHrefNotJustTheLabel() {
        let festival = parseFestivalLink("""
        <a class="nested" href="../../../festival/2025/oyafestivalen-2025-73d58625.html"
           title="View Øyafestivalen 2025 details">Øyafestivalen 2025</a>
        """)
        XCTAssertEqual("oyafestivalen-2025-73d58625", festival?.slug)
        XCTAssertEqual("../../../festival/2025/oyafestivalen-2025-73d58625.html", festival?.href)
    }

    func testAPlainClubShowHasNoFestivalLink() {
        XCTAssertNil(parseFestivalName("<html><body>Blå, Oslo, Norway</body></html>"))
        XCTAssertNil(parseFestivalLink("<html><body>Blå, Oslo, Norway</body></html>"))
    }
}

/// The festival page — the only place the range, the day grouping and the set times
/// live. Three facts, read one at a time: a redesign upstream must cost the field it
/// touched and never the night (ADR-0004). Twin of Android's FestivalPageParseTest,
/// trimmed from the same real page.
final class FestivalPageParseTests: XCTestCase {

    private let page = """
    <div class="condensed dateBlock dtstart">
      <span class="value-title" title="2026-06-24"></span>
      <span class="month">Jun</span><span class="day">24</span>
    </div>
    <span>Wed June 24, 2026 - Sat June 27, 2026</span>
    <p class="FestivalSetlistsGroupedVenueDayBySubVenue-eventDate x1">Wednesday, June 24, 2026</p>
    <div class="FestivalSetlistListItem-root">
      <div class="FestivalSetlistListItem-scheduledStart"><p>2:00 pm</p></div>
      <a href="/setlist/gojira/2026/ekebergsletta-oslo-norway-1ba2c3d4.html">Gojira</a>
    </div>
    <div class="FestivalSetlistListItem-root">
      <div class="FestivalSetlistListItem-scheduledStart"><p>10:30 pm</p></div>
      <a href="/setlist/ghost/2026/ekebergsletta-oslo-norway-2ba2c3d4.html">Ghost</a>
    </div>
    <p class="FestivalSetlistsGroupedVenueDayBySubVenue-eventDate x1">Thursday, June 25, 2026</p>
    <div class="FestivalSetlistListItem-root">
      <a href="/setlist/turnstile/2026/ekebergsletta-oslo-norway-3ba2c3d4.html">Turnstile</a>
    </div>
    """

    func testReadsTheRangeTheFestivalRanOver() {
        let f = parseFestivalPage(page)
        XCTAssertEqual("24-06-2026", f.rangeFrom)
        XCTAssertEqual("27-06-2026", f.rangeTo)
    }

    /// Membership is the source's own day grouping, not my attendance and not a window
    /// drawn over dates — which is the whole of #166 in one field.
    func testReadsTheSourcesOwnDayGrouping() {
        let expected: [String: [String]] = [
            "24-06-2026": ["1ba2c3d4", "2ba2c3d4"],
            "25-06-2026": ["3ba2c3d4"],
        ]
        XCTAssertEqual(expected, parseFestivalPage(page).dayMembership)
    }

    /// Twelve-hour on the page, twenty-four here, so the latest set also sorts last.
    func testReadsThePublishedStartTimesForTheActsThatHaveOne() {
        let times = parseFestivalPage(page).setTimes
        XCTAssertEqual("14:00", times?["1ba2c3d4"])
        XCTAssertEqual("22:30", times?["2ba2c3d4"])
        XCTAssertNil(times?["3ba2c3d4"]) // no scheduled start published
    }

    /// One field going missing must not take the others with it.
    func testAPageWithNoTimesStillYieldsItsDays() {
        let f = parseFestivalPage(
            page.replacingOccurrences(
                of: "FestivalSetlistListItem-scheduledStart", with: "somethingElse"
            )
        )
        XCTAssertNil(f.setTimes)
        XCTAssertEqual(2, f.dayMembership?.count)
    }

    func testAPageShapedNothingLikeAFestivalYieldsNothingRatherThanNonsense() {
        let f = parseFestivalPage("<html><body>Not a festival page at all.</body></html>")
        XCTAssertNil(f.rangeFrom)
        XCTAssertNil(f.rangeTo)
        XCTAssertNil(f.dayMembership)
        XCTAssertNil(f.setTimes)
    }
}
