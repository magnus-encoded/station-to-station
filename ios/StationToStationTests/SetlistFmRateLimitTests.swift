import XCTest
@testable import StationToStation

/// The rule that decides whether the shared setlist.fm quota still counts as spent
/// (#457). A pure function of a stored instant and now — no clock, no storage, and the
/// one thing both platforms have to agree on. The mirror of Android's
/// `SetlistFmRateLimitTest`.
final class SetlistFmRateLimitTests: XCTestCase {

    private let noon: TimeInterval = 1_758_000_000

    func testNothingRecordedMeansNotSpent() {
        XCTAssertFalse(sharedQuotaSpent(spentAt: nil, now: noon))
    }

    func testJustRecordedMeansSpent() {
        XCTAssertTrue(sharedQuotaSpent(spentAt: noon, now: noon))
    }

    func testStillSpentAMomentBeforeTheHourIsUp() {
        XCTAssertTrue(sharedQuotaSpent(spentAt: noon, now: noon + sharedQuotaMemorySeconds - 1))
    }

    func testExpiredTheMomentTheHourIsUp() {
        XCTAssertFalse(sharedQuotaSpent(spentAt: noon, now: noon + sharedQuotaMemorySeconds))
    }

    func testExpiredLongAfter() {
        XCTAssertFalse(sharedQuotaSpent(spentAt: noon, now: noon + 5 * sharedQuotaMemorySeconds))
    }

    /// A clock that moved back leaves a timestamp in the future. Believing it would
    /// shut setlist.fm off for as long as the clock stays wrong; disbelieving it costs
    /// one request.
    func testATimestampInTheFutureIsExpiredNotSpentForever() {
        XCTAssertFalse(sharedQuotaSpent(spentAt: noon + sharedQuotaMemorySeconds, now: noon))
        XCTAssertFalse(sharedQuotaSpent(spentAt: noon + 400 * sharedQuotaMemorySeconds, now: noon))
    }
}
