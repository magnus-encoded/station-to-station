import XCTest

final class TourFixturesTests: XCTestCase {
    func testSharedCasesLoadWithEverySpecRowAndEverySkipStep() throws {
        let cases = try TourFixtures.load()
        let ids = Set(cases.map(\.id))
        XCTAssertEqual(ids.count, cases.count)
        XCTAssertEqual(Set(cases.map(\.row)), Set([
            "Happy path", "Offline start", "Skip at Sn", "Resume", "Replay",
            "Spotify declined", "Spotify retry", "Fill: setlist.fm hit",
            "Fill: fallback", "Out-of-order event"
        ]))
        for n in 1...20 { XCTAssertTrue(ids.contains("skip-s\(n)")) }
        for n in 1...19 {
            XCTAssertTrue(ids.contains("resume-s\(n)"))
            XCTAssertTrue(ids.contains("out-of-order-s\(n)"))
        }
        for fixture in cases { XCTAssertFalse(fixture.checks.isEmpty, fixture.id) }
    }
}
