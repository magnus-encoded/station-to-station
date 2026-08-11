import XCTest
@testable import StationToStation

/// #99's portable half: which photos belong to a night, and what a keepsake's
/// capture time is when the file carries none.
///
/// Every number here is also asserted in Android's `PhotoWindowTest`, against the
/// same fixed values rather than against this file — so neither platform can drift
/// by agreeing with itself. The picking, the permission prompt and the grid are
/// not asserted: they need a device.
///
/// UTC throughout, because the assertions are epoch millis and a runner's zone is
/// not a fact about the domain.
final class PhotoWindowTests: XCTestCase {

    private var utc: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "UTC")!
        return c
    }()

    /// 2026-08-04 00:00:00 UTC — the gig's date, as setlist.fm dates it.
    private let dayStart: Int64 = 1_785_801_600_000
    /// 2026-08-05 06:00:00 UTC — the small hours after it.
    private let windowEnd: Int64 = 1_785_909_600_000

    private func window() throws -> ClosedRange<Int64> {
        try XCTUnwrap(photoWindow(gigDate: "04-08-2026", calendar: utc))
    }

    // MARK: - The window

    func testTheWindowIsTheGigsDayPlusTheSmallHoursAfterIt() throws {
        let w = try window()
        XCTAssertEqual(dayStart, w.lowerBound)
        XCTAssertEqual(windowEnd, w.upperBound)
    }

    func testAShowAtElevenAtNightIsPhotographedOnTheFollowingCalendarDay() throws {
        // The whole reason the window is not "that calendar day": setlist.fm dates
        // a show by when it started, and a 23:00 set is shot after midnight.
        let halfPastMidnight = dayStart + 86_400_000 + 1_800_000
        XCTAssertTrue(isInPhotoWindow(try window(), taken: halfPastMidnight, added: nil))
    }

    func testAPhotoJustOutsideTheWindowIsExcluded() throws {
        let w = try window()
        XCTAssertFalse(isInPhotoWindow(w, taken: dayStart - 1, added: nil))
        XCTAssertFalse(isInPhotoWindow(w, taken: windowEnd + 1, added: nil))
        // Both ends are inclusive.
        XCTAssertTrue(isInPhotoWindow(w, taken: dayStart, added: nil))
        XCTAssertTrue(isInPhotoWindow(w, taken: windowEnd, added: nil))
    }

    func testANightWithNoDateHasNoWindowAtAll() {
        // Different from an empty one: nothing can be said about that night.
        XCTAssertNil(photoWindow(gigDate: "", calendar: utc))
        XCTAssertNil(photoWindow(gigDate: "2026-08-04", calendar: utc))
    }

    // MARK: - The absent-timestamp fallback

    func testAPhotoCarryingNoTimestampFallsBackToWhenTheLibrarySawIt() throws {
        let duringTheNight = dayStart + 86_400_000 + 1_800_000
        XCTAssertEqual(duringTheNight, capturedAtMs(taken: nil, added: duringTheNight))
        XCTAssertTrue(isInPhotoWindow(try window(), taken: nil, added: duringTheNight))
    }

    func testTheCamerasOwnStampWinsWhenItHasOne() {
        XCTAssertEqual(1000, capturedAtMs(taken: 1000, added: 2000))
    }

    func testAZeroStampCountsAsAbsentRatherThanAsNineteenSeventy() {
        XCTAssertEqual(2000, capturedAtMs(taken: 0, added: 2000))
        XCTAssertNil(capturedAtMs(taken: 0, added: 0))
    }

    func testAPhotoThatAnswersNeitherIsNotFromThatNight() throws {
        // A wrong timestamp on a keepsake is worse than an honest gap.
        XCTAssertNil(capturedAtMs(taken: nil, added: nil))
        XCTAssertFalse(isInPhotoWindow(try window(), taken: nil, added: nil))
    }
}
