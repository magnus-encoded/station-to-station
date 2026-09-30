import XCTest
@testable import StationToStation

final class GigMenuTests: XCTestCase {
    func testANightHeldOnlyHereIsHereOnlyWhateverIdItHas() {
        XCTAssertEqual(.hereOnly, gigStanding("534b1301", held: ["534b1301"], attendedOnSetlistFm: ["aaaa"]))
    }

    func testANightHeldHereAndOnMyAttendedListIsBoth() {
        XCTAssertEqual(.hereAndSetlistFm, gigStanding("aaaa", held: ["aaaa"], attendedOnSetlistFm: ["aaaa"]))
    }

    func testANightOnlyMyAttendedListHasIsSetlistFmOnly() {
        XCTAssertEqual(.setlistFmOnly, gigStanding("aaaa", held: [], attendedOnSetlistFm: ["aaaa"]))
    }

    func testAGigHeldHereCanAlwaysBeDeleted() {
        XCTAssertEqual([.delete], gigMenu(.hereOnly, hasPage: true))
        XCTAssertEqual([.openOnSetlistFm, .delete], gigMenu(.hereAndSetlistFm, hasPage: true))
    }

    func testAGigOnlySetlistFmHasGoesToSetlistFmInsteadOfDeleting() {
        XCTAssertEqual([.openOnSetlistFm], gigMenu(.setlistFmOnly, hasPage: true))
    }

    func testAGigOnlySetlistFmHasAndNoPageHasNoMenu() {
        XCTAssertEqual([], gigMenu(.setlistFmOnly, hasPage: false))
    }
}
