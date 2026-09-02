import XCTest
@testable import StationToStation

/// The festival programme (#173, #389), ported case for case from Android's
/// `ProgrammeTest`.
///
/// A fixed UTC calendar throughout, so the boundary math is the same instant
/// wherever this runs — the Android suite gets that for free from `LocalDateTime`,
/// and here it has to be said.
final class ProgrammeTests: XCTestCase {

    private let cal: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(secondsFromGMT: 0)!
        return c
    }()

    private func act(_ artist: String, _ start: String, _ stage: String, date: String = "2026-08-13", end: String = "") -> ProgrammeAct {
        ProgrammeAct(artist: artist, date: date, start: start, stage: stage, end: end)
    }

    private func moment(_ ymd: String, _ hm: String) -> Date {
        var comps = DateComponents()
        let d = ymd.split(separator: "-").compactMap { Int($0) }
        let t = hm.split(separator: ":").compactMap { Int($0) }
        comps.year = d[0]; comps.month = d[1]; comps.day = d[2]
        comps.hour = t[0]; comps.minute = t[1]
        return cal.date(from: comps)!
    }

    func testAnActEndsWhenTheNextOneOnItsStageStarts() {
        let a = act("First", "18:00", "Amfiet")
        let b = act("Second", "18:40", "Amfiet")
        let ends = endTimes([a, b], calendar: cal)
        XCTAssertEqual(moment("2026-08-13", "18:40"), ends[a])
    }

    func testALongGapToTheNextActDoesNotStretchTheSet() {
        // The stage went quiet for three hours. Without the cap this act would be
        // "playing" all evening and clash with everything.
        let a = act("Afternoon", "16:00", "Klubben")
        let b = act("Evening", "21:00", "Klubben")
        let ends = endTimes([a, b], calendar: cal)
        XCTAssertEqual(moment("2026-08-13", "17:00"), ends[a])
    }

    func testTheLastActOfTheNightFallsBackToTheDefaultLength() {
        let a = act("Headliner", "22:30", "Sirkus")
        XCTAssertEqual(moment("2026-08-13", "23:30"), endTimes([a], calendar: cal)[a])
    }

    func testAPublishedEndIsPreferredOverAnythingInferred() {
        let a = act("First", "18:00", "Amfiet", end: "18:20")
        let b = act("Second", "18:40", "Amfiet")
        XCTAssertEqual(moment("2026-08-13", "18:20"), endTimes([a, b], calendar: cal)[a])
    }

    /// The truncation bug, asserted. A 105-minute headline set clipped to the
    /// default hour makes a real conflict disappear — the clash function reports
    /// free time exactly where the choice is, which is the one thing this feature
    /// exists to prevent.
    func testAPublishedEndLongerThanTheDefaultHourIsHonouredAndTheClashIsSeen() {
        let headline = act("Headline", "22:00", "Amfiet", end: "23:45")
        let other = act("Also want", "23:15", "Sirkus")
        XCTAssertEqual(moment("2026-08-13", "23:45"), endTimes([headline, other], calendar: cal)[headline])
        XCTAssertEqual([other], clashesWith(headline, [headline, other], calendar: cal))
    }

    func testAnEndThatRunsPastMidnightClosesOnTheRightNight() {
        let late = act("Closer", "23:30", "Klubben", end: "01:00")
        XCTAssertEqual(moment("2026-08-14", "01:00"), endTimes([late], calendar: cal)[late])
    }

    /// The 02:00–06:00 stage that runs until it is light. The start is pushed past
    /// the night boundary and the end sits on the far side of it, so read on its
    /// own the end lands a day early — and a four-hour set silently became a
    /// guessed hour, with three of its four hours reported as free time.
    func testASetThatStartsAfterMidnightAndEndsAtDawnKeepsItsRealLength() {
        let allNighter = act("Sunrise", "02:00", "Klubben", end: "06:00")
        XCTAssertEqual(moment("2026-08-14", "02:00"), allNighter.startsAt(calendar: cal))
        XCTAssertEqual(moment("2026-08-14", "06:00"), endTimes([allNighter], calendar: cal)[allNighter])
    }

    func testAnEndThatCannotBeAfterItsStartIsIgnoredAndTheInferenceStands() {
        // A malformed act degrades to a guessed hour rather than dropping out of
        // clash detection entirely.
        let a = act("Malformed", "20:00", "Amfiet", end: "19:00")
        XCTAssertEqual(moment("2026-08-13", "21:00"), endTimes([a], calendar: cal)[a])
    }

    func testOverlappingActsOnDifferentStagesClash() {
        let mine = act("Want", "20:00", "Amfiet")
        let other = act("Also want", "20:30", "Sirkus")
        XCTAssertEqual([other], clashesWith(mine, [mine, other], calendar: cal))
    }

    func testActsOnTheSameStageNeverClash() {
        // Consecutive sets on one stage are a running order, not a choice.
        let a = act("First", "20:00", "Amfiet")
        let b = act("Second", "20:30", "Amfiet")
        XCTAssertTrue(clashesWith(a, [a, b], calendar: cal).isEmpty)
    }

    func testBackToBackAcrossStagesIsADashNotAClash() {
        let a = act("Ends at 21", "20:00", "Amfiet")
        let filler = act("Next on Amfiet", "21:00", "Amfiet")
        let b = act("Starts at 21", "21:00", "Sirkus")
        XCTAssertFalse(clashesWith(a, [a, filler, b], calendar: cal).contains(b))
    }

    func testAnAfterMidnightActBelongsToTheNightNotTheNextAfternoon() {
        let late = act("Late", "01:00", "Klubben", date: "2026-08-13")
        XCTAssertEqual(moment("2026-08-14", "01:00"), late.startsAt(calendar: cal))
        // And so it cannot clash with something playing that same afternoon.
        let afternoon = act("Afternoon", "16:00", "Sirkus", date: "2026-08-13")
        XCTAssertTrue(clashesWith(late, [late, afternoon], calendar: cal).isEmpty)
    }

    func testPlayingAtFindsWhatIsOnAndExcludesWhatHasEnded() {
        let on = act("On now", "20:00", "Amfiet")
        let over = act("Finished", "18:00", "Sirkus")
        let soon = act("Later", "22:00", "Klubben")
        let at = moment("2026-08-13", "20:30")
        XCTAssertEqual([on], playingAt(at, [on, over, soon], calendar: cal))
    }

    func testTheDaysAndTheRunningOrderOfATimetable() {
        let thursday = act("First", "15:45", "Amfiet")
        let friday = act("Second", "22:00", "Tent", date: "2026-08-14")
        let acts = [friday, thursday]
        XCTAssertEqual(
            [moment("2026-08-13", "00:00"), moment("2026-08-14", "00:00")],
            programmeDays(acts, calendar: cal))
        XCTAssertEqual([thursday], actsOn(moment("2026-08-13", "00:00"), acts, calendar: cal))
    }

    func testACachedProgrammeRoundTripsAttributionAndAll() {
        let programme = StoredProgramme(
            id: "oyafestivalen2026",
            name: "Øyafestivalen 2026",
            copyright: "Clashfinder data CC BY-NC 3.0",
            lastEdit: "2026-07-11 13:44:46",
            acts: [act("Headline", "21:30", "Amfiet", end: "23:15")]
        )
        XCTAssertEqual(programme, parseProgramme(encodeProgramme(programme)))
    }

    func testAnUnreadableCacheIsNoProgrammeNotACrash() {
        XCTAssertEqual(StoredProgramme(), parseProgramme("not json at all"))
    }
}
