import XCTest
@testable import StationToStation

/// The future edge's clock (#175), ported from Android's `GigTimeStateTest`.
///
/// A fixed UTC calendar throughout, so the 06:00 boundary is the same instant wherever
/// this runs — the Android suite gets that for free from `LocalDateTime`, and here it
/// has to be said. Every venue and night below is either invented or a published
/// festival date.
final class GigTimeStateTests: XCTestCase {

    func testGossipParticipationEndsWithoutRenewingAfterCompletion() {
        let end = Date(timeIntervalSince1970: 10000)
        let done: Int64 = 6_000_000
        XCTAssertNil(gossipParticipationUntil(checkedInAt: nil, closed: false, completedAt: nil, nightEnd: end))
        XCTAssertEqual(gossipParticipationUntil(checkedInAt: 1, closed: false, completedAt: nil, nightEnd: end), end)
        XCTAssertNil(gossipParticipationUntil(checkedInAt: 1, closed: true, completedAt: nil, nightEnd: end))
        XCTAssertNil(gossipParticipationUntil(checkedInAt: 1, closed: false, completedAt: nil, nightEnd: end, stoppedAt: 1))
        let cutoff = gossipParticipationUntil(checkedInAt: 1, closed: true, completedAt: done, nightEnd: end)!
        XCTAssertTrue(Date(timeIntervalSince1970: 7799) < cutoff)
        XCTAssertFalse(Date(timeIntervalSince1970: 7800) < cutoff)
        XCTAssertEqual(gossipParticipationUntil(checkedInAt: 1, closed: false, completedAt: done, nightEnd: end), cutoff)
        XCTAssertEqual(gossipParticipationUntil(checkedInAt: 1, closed: true, completedAt: 9_999_000, nightEnd: end), end)
        let finished = StoredLog().completing(true, now: done)
        XCTAssertEqual(finished.completing(true, now: done + 500).completedAt, done)
        let reopened = finished.completing(false, now: done + 1_900_000)
        XCTAssertEqual(gossipParticipationUntil(checkedInAt: 1, closed: reopened.closed,
            completedAt: reopened.completedAt, nightEnd: end), end)
        let reopenedCutoff = gossipParticipationUntil(checkedInAt: 1, closed: reopened.closed,
            completedAt: reopened.completedAt, nightEnd: end)!
        XCTAssertTrue(end.addingTimeInterval(-1) < reopenedCutoff)
        XCTAssertFalse(end < reopenedCutoff)
        XCTAssertEqual(reopened.completing(true, now: done + 2_000_000).completedAt, done + 2_000_000)
        let edited = StoredLog(songs: ["one"], closed: true, completedAt: done).adding("encore").correctingAt(0, title: "two")
        XCTAssertEqual(edited.completedAt, done)
        XCTAssertTrue(edited.closed)
        let restored = try! JSONDecoder().decode(StoredLog.self, from: JSONEncoder().encode(edited))
        XCTAssertEqual(restored.completedAt, done)
    }

    private let cal: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(secondsFromGMT: 0)!
        return c
    }()

    /// `dd-MM-yyyy`, the shape a **Gig** carries.
    private func day(_ s: String) -> String { s }

    private func at(_ ymd: String, _ hour: Int, _ minute: Int = 0) -> Date {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "dd-MM-yyyy"
        f.timeZone = cal.timeZone
        return cal.date(bySettingHour: hour, minute: minute, second: 0, of: f.date(from: ymd)!)!
    }

    /// Midday, so "the gig date" and "the night-of window" stay separate cases.
    private var now: Date { at("31-07-2026", 12) }

    // MARK: - The states

    func testFarInTheFutureReadsAsThePlainFutureNode() {
        XCTAssertEqual(.future, gigTimeState(now: now, gigDate: day("08-08-2026"), calendar: cal))
        XCTAssertEqual(.future, gigTimeState(now: now, gigDate: day("31-07-2027"), calendar: cal))
    }

    func testTheFutureApproachingBoundarySitsAtExactlyAWeekOut() {
        XCTAssertEqual(.future, gigTimeState(now: now, gigDate: day("08-08-2026"), calendar: cal))
        XCTAssertEqual(.approaching, gigTimeState(now: now, gigDate: day("07-08-2026"), calendar: cal))
    }

    func testTheApproachingDayOfBoundarySitsAtToday() {
        XCTAssertEqual(.approaching, gigTimeState(now: now, gigDate: day("01-08-2026"), calendar: cal))
        XCTAssertEqual(.dayOf, gigTimeState(now: now, gigDate: day("31-07-2026"), calendar: cal))
    }

    /// A show that ran past midnight is still tonight to everyone who was at it, so the
    /// day-of/past line falls at 06:00 the next morning, not at midnight.
    func testTheNightOfWindowHoldsThroughSixAmThenTheGigIsPast() {
        let gig = day("31-07-2026")

        XCTAssertEqual(.dayOf, gigTimeState(now: at("01-08-2026", 5, 59), gigDate: gig, calendar: cal))
        XCTAssertEqual(.past, gigTimeState(now: at("01-08-2026", 6, 0), gigDate: gig, calendar: cal))
    }

    func testAGigWhoseNightHasBeenAndGoneIsPast() {
        XCTAssertEqual(.past, gigTimeState(now: now, gigDate: day("30-07-2026"), calendar: cal))
        XCTAssertEqual(.past, gigTimeState(now: now, gigDate: day("31-07-2025"), calendar: cal))
    }

    func testAnUnparseableDateHasNoState() {
        XCTAssertNil(gigTimeState(now: now, gigDate: "2026-08-04", calendar: cal))
        XCTAssertNil(gigTimeState(now: now, gigDate: "", calendar: cal))
    }

    // MARK: - The countdown

    func testCountdownHumanisesCoarserAsTheGigRecedes() {
        XCTAssertEqual("tomorrow", formatCountdown(daysUntil: 1))
        XCTAssertEqual("in 13 days", formatCountdown(daysUntil: 13))
        XCTAssertEqual("in 2 weeks", formatCountdown(daysUntil: 14))
        XCTAssertEqual("in 4 weeks", formatCountdown(daysUntil: 30))
        XCTAssertEqual("in 1 month", formatCountdown(daysUntil: 31))
        XCTAssertEqual("in 2 months", formatCountdown(daysUntil: 60))
        // "in 377 days" is absurd; a year out reads in months.
        XCTAssertEqual("in 12 months", formatCountdown(daysUntil: 377))
    }

    /// Android throws here. On a render path a precondition failure would take the
    /// whole timeline down over a word, so this returns nil and the caller falls back.
    func testCountdownRefusesDayOfAndPast() {
        XCTAssertNil(formatCountdown(daysUntil: 0))
        XCTAssertNil(formatCountdown(daysUntil: -1))
    }

    // MARK: - The words

    func testAPlannedNodeSaysHowFarOffTheNightIs() {
        XCTAssertEqual("in 2 months", plannedStatus(gigDate: "30-09-2026", now: now, calendar: cal))
        XCTAssertEqual("in 6 days", plannedStatus(gigDate: "06-08-2026", now: now, calendar: cal))
        XCTAssertEqual("tomorrow", plannedStatus(gigDate: "01-08-2026", now: now, calendar: cal))
        XCTAssertEqual("tonight", plannedStatus(gigDate: "31-07-2026", now: now, calendar: cal))
    }

    func testAPlannedGigWhoseNightHasPassedNeverClaimsToBeTonight() {
        XCTAssertEqual("no setlist yet", plannedStatus(gigDate: "30-07-2026", now: now, calendar: cal))
        XCTAssertEqual("no setlist yet", plannedStatus(gigDate: "31-07-2008", now: now, calendar: cal))
    }

    func testNoSetlistYetIsAFactAboutTheRecordNotAboutTheDate() {
        XCTAssertEqual("no setlist yet", setlistStatus(songCount: 0))
        XCTAssertEqual("1 songs", setlistStatus(songCount: 1))
        XCTAssertEqual("15 songs", setlistStatus(songCount: 15))
    }

    func testAPastNightWithSongsStopsClaimingItHasNone() {
        XCTAssertEqual("15 songs",
                       plannedStatus(gigDate: "30-07-2026", now: now, songCount: 15, calendar: cal))
    }

    func testAGigWithAnUnparseableDateStillSaysSomethingTrue() {
        XCTAssertEqual("you're going", plannedStatus(gigDate: nil, now: now, calendar: cal))
        XCTAssertEqual("you're going", plannedStatus(gigDate: "not a date", now: now, calendar: cal))
    }

    // MARK: - Plan or record (#127)

    func testAPlanIsAPlanOnlyWhileTheClaimSaysPlanned() {
        XCTAssertTrue(isPlanned("planned"))
        XCTAssertFalse(isPlanned("attended"))
        XCTAssertFalse(isPlanned("checked_in"))
        // An imported night nobody ever claimed anything about is not a plan either.
        XCTAssertFalse(isPlanned(nil))
    }

    /// Three days after the festival's last night — every gig below is behind us.
    private var afterRingnes: Date { at("10-08-2026", 12) }

    /// Past, checked into, and the record holds all fifteen titles — the exact
    /// contradiction seen on the device, a chip above its own setlist.
    func testCheckedInWithFifteenSongsStopsSayingNoSetlistYet() {
        let planned = isPlanned("checked_in")

        XCTAssertFalse(planned)
        XCTAssertEqual("15 songs", gigStatus(planned: planned, gigDate: "07-08-2026",
                                             songCount: 15, now: afterRingnes, calendar: cal))
    }

    /// Same night, same venue, and zero songs stored.
    func testCheckedInWithAnEmptyRecordKeepsSayingNoSetlistYet() {
        let planned = isPlanned("checked_in")

        XCTAssertEqual("no setlist yet", gigStatus(planned: planned, gigDate: "07-08-2026",
                                                   songCount: 0, now: afterRingnes, calendar: cal))
    }

    func testAGenuinePlanAheadStillCountsDownSongsOrNot() {
        XCTAssertEqual("tomorrow", gigStatus(planned: true, gigDate: "01-08-2026",
                                             songCount: 0, now: now, calendar: cal))
        XCTAssertEqual("tonight", gigStatus(planned: true, gigDate: "31-07-2026",
                                            songCount: 0, now: now, calendar: cal))
        XCTAssertEqual("you're going", gigStatus(planned: true, gigDate: nil,
                                                 songCount: 0, now: now, calendar: cal))
    }

    // MARK: - The keepsake block and the map

    func testTheMediaBlockIsAbsentOnANightThatHasNotHappenedYet() {
        XCTAssertFalse(showsMediaBlock(planned: true, checkedIn: false))
        XCTAssertTrue(showsMediaBlock(planned: false, checkedIn: false))
    }

    func testCheckingInEarnsTheMediaBlockBackEvenWhileStillPlanned() {
        XCTAssertTrue(showsMediaBlock(planned: true, checkedIn: true))
    }

    func testMapsQueryJoinsVenueAndCity() {
        XCTAssertEqual("Rockefeller, Oslo", venueMapsQuery(venueName: "Rockefeller", city: "Oslo"))
    }

    func testMapsQueryToleratesAMissingHalf() {
        XCTAssertEqual("Rockefeller", venueMapsQuery(venueName: "Rockefeller", city: nil))
        XCTAssertEqual("Oslo", venueMapsQuery(venueName: nil, city: "Oslo"))
        XCTAssertEqual("Rockefeller", venueMapsQuery(venueName: "Rockefeller", city: "  "))
    }

    func testMapsQueryIsNilWhenThereIsNothingWorthSearchingFor() {
        XCTAssertNil(venueMapsQuery(venueName: nil, city: nil))
        XCTAssertNil(venueMapsQuery(venueName: "  ", city: ""))
    }
}
