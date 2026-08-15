import XCTest
@testable import StationToStation

/// The pure half of check-in (#174): time and distance, no phone and no venue.
///
/// Coordinates below are city centres and round numbers, not anyone's location.
final class CheckInTests: XCTestCase {

    private let cal: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(secondsFromGMT: 0)!
        return c
    }()

    private func at(_ ymd: String, _ hour: Int) -> Date {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "dd-MM-yyyy"
        f.timeZone = cal.timeZone
        return cal.date(bySettingHour: hour, minute: 0, second: 0, of: f.date(from: ymd)!)!
    }

    private func gig(_ id: String, date: String = "13-08-2026",
                     lat: Double? = 59.91, lon: Double? = 10.75) -> FmSetlist {
        let coords = (lat == nil && lon == nil) ? nil : FmCoords(lat: lat, long: lon)
        return FmSetlist(id: id, eventDate: date, artist: FmArtist(name: "A Band"),
                         venue: FmVenue(name: "A Venue", city: FmCity(name: "A City", coords: coords)))
    }

    private let inTheCity = (lat: 59.92, lon: 10.76)

    // MARK: - Distance

    /// A known pair: roughly 111 km per degree of latitude at any longitude.
    func testHaversineIsRightToWithinAPercent() {
        let d = metersBetween(0, 0, 1, 0)

        XCTAssertEqual(111_195, d, accuracy: 1_500)
    }

    func testTheSamePointIsNoDistanceAtAll() {
        XCTAssertEqual(0, metersBetween(59.91, 10.75, 59.91, 10.75), accuracy: 0.001)
    }

    // MARK: - The coarse gate

    func testAGigTonightInThisCityIsACandidate() {
        let found = checkInCandidate(gigs: [gig("g1")], now: at("13-08-2026", 21),
                                     where: inTheCity, calendar: cal)

        XCTAssertEqual("g1", found?.id)
    }

    /// The next city over must not get through — that is the whole job of the gate.
    func testAGigInAnotherCityIsNotACandidate() {
        // Bergen-ish, several hundred km away.
        let far = gig("g1", lat: 60.39, lon: 5.32)

        XCTAssertNil(checkInCandidate(gigs: [far], now: at("13-08-2026", 21),
                                      where: inTheCity, calendar: cal))
    }

    func testAGigOnAnotherNightIsNotACandidate() {
        XCTAssertNil(checkInCandidate(gigs: [gig("g1", date: "20-08-2026")],
                                      now: at("13-08-2026", 21), where: inTheCity, calendar: cal))
    }

    /// No coordinates means no prompt — skipped rather than guessed at.
    func testAGigWithNoCityCoordinatesIsSkipped() {
        XCTAssertNil(checkInCandidate(gigs: [gig("g1", lat: nil, lon: nil)],
                                      now: at("13-08-2026", 21), where: inTheCity, calendar: cal))
    }

    /// Half a point is no point: setlist.fm omitting one of the two is not a position.
    func testAHalfKnownPointIsNoPoint() {
        XCTAssertNil(gig("g1", lat: 59.91, lon: nil).cityCoords())
        XCTAssertNil(gig("g1", lat: nil, lon: 10.75).cityCoords())
    }

    func testWithNoFixThereIsNoCandidate() {
        XCTAssertNil(checkInCandidate(gigs: [gig("g1")], now: at("13-08-2026", 21),
                                      where: nil, calendar: cal))
    }

    /// The window is the night, so the small hours still offer the gig — the same
    /// 06:00 edge the time state draws its day-of line at.
    func testTheNightAfterMidnightStillOffersTheGig() {
        let found = checkInCandidate(gigs: [gig("g1")], now: at("14-08-2026", 1),
                                     where: inTheCity, calendar: cal)

        XCTAssertEqual("g1", found?.id)
    }

    // MARK: - The fine gate

    func testAtTheVenueIsWithinItsRadius() {
        // ~200 m north of the venue point.
        XCTAssertTrue(atVenue(where: (59.9118, 10.75), venue: (59.91, 10.75)))
    }

    func testAcrossTownIsNotAtTheVenue() {
        XCTAssertFalse(atVenue(where: (59.95, 10.85), venue: (59.91, 10.75)))
    }

    // MARK: - By hand

    /// The fallback for a refused permission or a venue no geocoder can find, which is
    /// why neither of those is a dead end.
    func testAGigTonightCanBeCheckedIntoByHandWithNoLocationAtAll() {
        XCTAssertTrue(canCheckInManually(gig: gig("g1", lat: nil, lon: nil),
                                         now: at("13-08-2026", 21), calendar: cal))
    }

    func testAGigOnAnotherNightCannotBeCheckedIntoByHand() {
        XCTAssertFalse(canCheckInManually(gig: gig("g1"), now: at("20-08-2026", 21), calendar: cal))
    }
}
