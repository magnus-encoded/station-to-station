import XCTest
@testable import StationToStation

/// Twin of Android's `OffsetFormatTest`.
final class OffsetFormatTests: XCTestCase {

    func testUnderAnHourReadsAsMinutesAndSeconds() {
        XCTAssertEqual(formatOffset(0), "0:00")
        XCTAssertEqual(formatOffset(214_000), "3:34")
        XCTAssertEqual(formatOffset(3_599_000), "59:59")
    }

    func testAFullGigPastTheHourGrowsAnHoursField() {
        XCTAssertEqual(formatOffset(3_600_000), "1:00:00")
        XCTAssertEqual(formatOffset(4_325_000), "1:12:05")
    }

    func testSubSecondRemaindersRoundDownRatherThanTippingTheMinute() {
        XCTAssertEqual(formatOffset(59_999), "0:59")
    }
}
