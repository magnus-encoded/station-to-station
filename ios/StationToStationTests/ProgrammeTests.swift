import XCTest
@testable import StationToStation

/// The festival programme (#173), ported case for case from Android's `ProgrammeTest`.
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

    private func act(_ artist: String, _ start: String, _ stage: String, date: String = "2026-08-13") -> ProgrammeAct {
        ProgrammeAct(artist: artist, date: date, start: start, stage: stage)
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

    /// The markup this mimics, written out rather than captured: a saved copy of
    /// the real page would put a festival's programme in the repo, which is the
    /// thing this app deliberately does not do. What is being tested is the
    /// *shape* — nested spans inside the h3, Next.js comment nodes mid-text,
    /// "kl." before the time, a Norwegian date with no year — and that is
    /// reproducible without anyone's data.
    private let pageShape = """
        <div><h3 class="x">Band One<!-- --> <span class="y">(<!-- -->UK<!-- -->)</span></h3>
        <ul class="flex"><li><span><span class="inline-block first-letter:uppercase">torsdag 13. august</span></span></li>
        <li><span><span class="hidden md:inline-block">kl.</span> <!-- -->15:45</span></li>
        <li><span class="h-8">Main Stage</span></li></ul></div>
        <div><h3 class="x">Band &amp; Band&#x27;s Friend&#160;Two &#x1F3B8;</h3>
        <ul class="flex"><li><span><span class="inline-block first-letter:uppercase">fredag 14. august</span></span></li>
        <li><span><span class="hidden md:inline-block">kl.</span> <!-- -->22:00</span></li>
        <li><span class="h-8">Tent</span></li></ul></div>
        """

    func testThePageParsesIntoActsCommentsAndNestedSpansAndAll() {
        let acts = oyaProgramme(pageShape, year: 2026)
        XCTAssertEqual(2, acts.count)
        XCTAssertEqual("Band One (UK)", acts[0].artist)
        XCTAssertEqual("2026-08-13", acts[0].date)
        XCTAssertEqual("15:45", acts[0].start)
        XCTAssertEqual("Main Stage", acts[0].stage)
        XCTAssertEqual([moment("2026-08-13", "00:00"), moment("2026-08-14", "00:00")],
                        programmeDays(acts, calendar: cal))
    }

    func testAnAmpersandInABandNameArrivesAsAnAmpersand() {
        // The live page writes "Nick Cave &amp; The Bad Seeds". Stored raw, that
        // name matches nothing a person or setlist.fm would ever write. The
        // guitar is the code point above U+FFFF: decoded one UTF-16 unit at a
        // time it comes out as garbage, and a band name is exactly where such a
        // character turns up.
        let acts = oyaProgramme(pageShape, year: 2026)
        XCTAssertEqual("Band & Band's Friend Two \u{1F3B8}", acts[1].artist)
    }

    func testABlockMissingAnyFieldIsDroppedNeverHalfBuilt() {
        // The failure that would matter is a partial act quietly joining the
        // list — an act with no stage clashes with nothing and is invisible on
        // the timetable.
        let broken = """
            <div><h3>No time</h3><ul><li><span>torsdag 13. august</span></li>
            <li><span>Main Stage</span></li></ul></div>
            """
        XCTAssertTrue(oyaProgramme(broken, year: 2026).isEmpty)
    }

    func testMarkupThatHasMovedOnParsesToNothingNotToNonsense() {
        XCTAssertTrue(oyaProgramme("<html><body><p>We redesigned the site</p></body></html>", year: 2026).isEmpty)
    }

    func testACachedProgrammeRoundTrips() {
        let acts = oyaProgramme(pageShape, year: 2026)
        XCTAssertEqual(acts, parseProgramme(encodeProgramme(acts)))
    }
}
