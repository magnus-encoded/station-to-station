import XCTest
@testable import StationToStation

/// **Departures**: the board, and the change committing it would make. The Swift
/// twin of Android's `ProgrammeTest`'s Departures section (#391, #390).
final class DeparturesTests: XCTestCase {

    private let cal: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(secondsFromGMT: 0)!
        return c
    }()

    private func act(_ artist: String, _ start: String, _ stage: String,
                     date: String = "2026-08-13", end: String = "") -> ProgrammeAct {
        ProgrammeAct(artist: artist, date: date, start: start, stage: stage, end: end)
    }

    private func programme(_ acts: ProgrammeAct...) -> StoredProgramme {
        StoredProgramme(id: "tor2027", name: "Tons of Rock 2027", acts: acts)
    }

    private func day(_ ymd: String) -> Date {
        let p = ymd.split(separator: "-").compactMap { Int($0) }
        var comps = DateComponents()
        comps.year = p[0]; comps.month = p[1]; comps.day = p[2]
        return cal.date(from: comps)!
    }

    private func moment(_ ymd: String, _ hm: String) -> Date {
        let d = ymd.split(separator: "-").compactMap { Int($0) }
        let t = hm.split(separator: ":").compactMap { Int($0) }
        var comps = DateComponents()
        comps.year = d[0]; comps.month = d[1]; comps.day = d[2]
        comps.hour = t[0]; comps.minute = t[1]
        return cal.date(from: comps)!
    }

    /// Who is on each position, so a test says what a person would see.
    private func rungsOn(_ board: Departures, _ ymd: String) -> [[String]] {
        (board.positions[day(ymd)] ?? []).map { p in p.options.map { $0.artist } }
    }

    func testADayWithNoOverlapsIsARunOfSingleActsAndNoRungs() {
        let board = departuresOf(
            programme(act("First", "18:00", "Amfiet"), act("Second", "20:00", "Amfiet")),
            picked: [], applied: [], calendar: cal)
        XCTAssertEqual([["First"], ["Second"]], rungsOn(board, "2026-08-13"))
    }

    func testTwoActsOverlappingAcrossStagesAreOneRungOfTwo() {
        let board = departuresOf(
            programme(act("Want", "20:00", "Amfiet"), act("Also want", "20:30", "Sirkus")),
            picked: [], applied: [], calendar: cal)
        XCTAssertEqual([["Want", "Also want"]], rungsOn(board, "2026-08-13"))
    }

    func testAChainOfThreeIsOneDecisionEvenWhereTheEndsDoNotTouch() {
        // A overlaps B and B overlaps C, but A is over before C starts. Taking A and
        // C is still one walk, so it is one rung.
        let board = departuresOf(
            programme(
                act("A", "20:00", "Amfiet", end: "20:45"),
                act("B", "20:30", "Sirkus", end: "21:15"),
                act("C", "21:00", "Klubben", end: "21:45")
            ),
            picked: [], applied: [], calendar: cal)
        XCTAssertEqual([["A", "B", "C"]], rungsOn(board, "2026-08-13"))
    }

    func testTwoActsOnOneStageAreARunningOrderNeverARung() {
        let board = departuresOf(
            programme(act("First", "20:00", "Amfiet"), act("Second", "20:30", "Amfiet")),
            picked: [], applied: [], calendar: cal)
        XCTAssertEqual([["First"], ["Second"]], rungsOn(board, "2026-08-13"))
    }

    func testAnAfterMidnightActClosesItsOwnNightRatherThanOpeningTheNext() {
        let board = departuresOf(
            programme(
                act("Afternoon", "16:00", "Amfiet", date: "2026-08-13"),
                act("Late", "01:00", "Klubben", date: "2026-08-13")
            ),
            picked: [], applied: [], calendar: cal)
        XCTAssertEqual([["Afternoon"], ["Late"]], rungsOn(board, "2026-08-13"))
    }

    func testTurningOffAnActThatIsOnTheLineIsARemoval() {
        let want = act("Want", "20:00", "Amfiet")
        let diff = departuresOf(programme(want), picked: [], applied: [actKey(want)], calendar: cal).diff
        XCTAssertTrue(diff.add.isEmpty)
        XCTAssertEqual([actKey(want)], diff.remove)
    }

    func testASelectionThatMatchesTheLineChangesNothingSoThereIsNoButton() {
        let want = act("Want", "20:00", "Amfiet")
        let diff = departuresOf(programme(want), picked: [actKey(want)], applied: [actKey(want)], calendar: cal).diff
        XCTAssertTrue(diff.isEmpty)
        XCTAssertNil(commitLabel(diff, festival: "Tons of Rock 2027", firstCommit: false))
    }

    func testASelectionSpanningTwoDaysNamesBothWhicheverTabIsInView() {
        // The tab is a view, not a scope: nothing in the diff knows one exists.
        let thu = act("Thursday act", "20:00", "Amfiet", date: "2026-08-13")
        let fri = act("Friday act", "20:00", "Amfiet", date: "2026-08-14")
        let diff = departuresOf(programme(thu, fri), picked: [actKey(thu), actKey(fri)], applied: [], calendar: cal).diff
        XCTAssertEqual([day("2026-08-13"), day("2026-08-14")], diff.days)
    }

    func testAFirstCommitOnOneDayIsNamedInTheLanguageOfTheFestival() {
        let thu = act("Thursday act", "20:00", "Amfiet", date: "2026-08-13")
        let diff = departuresOf(programme(thu), picked: [actKey(thu)], applied: [], calendar: cal).diff
        XCTAssertEqual("Add Thursday at Tons of Rock 2027", commitLabel(diff, festival: "Tons of Rock 2027", firstCommit: true, calendar: cal))
    }

    func testAddingToAProgrammeAlreadyCommittedSaysHowMuchItAdds() {
        let a = act("A", "20:00", "Amfiet")
        let b = act("B", "20:00", "Sirkus")
        let on = act("Already on", "16:00", "Amfiet")
        let diff = departuresOf(
            programme(a, b, on),
            picked: [actKey(a), actKey(b), actKey(on)],
            applied: [actKey(on)], calendar: cal).diff
        XCTAssertEqual("Add 2 more gigs", commitLabel(diff, festival: "Tons of Rock 2027", firstCommit: false))
    }

    func testACommitThatOnlyTakesThingsAwayAnnouncesItself() {
        let on = act("Already on", "16:00", "Amfiet")
        let diff = departuresOf(programme(on), picked: [], applied: [actKey(on)], calendar: cal).diff
        XCTAssertEqual("Remove 1 gig", commitLabel(diff, festival: "Tons of Rock 2027", firstCommit: false))
    }

    func testAChangeOfMindThatBothAddsAndRemovesIsOneUpdate() {
        let thu = act("Thursday act", "20:00", "Amfiet", date: "2026-08-13")
        let fri = act("Friday act", "20:00", "Amfiet", date: "2026-08-14")
        let diff = departuresOf(programme(thu, fri), picked: [actKey(fri)], applied: [actKey(thu)], calendar: cal).diff
        XCTAssertEqual("Update Thursday and Friday", commitLabel(diff, festival: "Tons of Rock 2027", firstCommit: false, calendar: cal))
    }

    func testACountryTagOnOneSourceIsTheSameArtistAsNoTagOnTheOther() {
        XCTAssertEqual(nameKey("Wilco"), nameKey("Wilco (US)"))
    }

    func testAnAmpersandAndTheWordAndAreTheSameArtist() {
        XCTAssertEqual(nameKey("Nick Cave & the Bad Seeds"), nameKey("Nick Cave and the Bad Seeds (AU)"))
    }

    func testATypographicApostropheIsTheSameArtistAsAPlainOne() {
        XCTAssertEqual(nameKey("Melody's Echo Chamber"), nameKey("Melody\u{2019}s Echo Chamber"))
    }

    func testTwoDifferentArtistsDoNotFoldIntoOne() {
        XCTAssertNotEqual(nameKey("Wilco"), nameKey("Wilko Johnson"))
    }

    func testAMusicBrainzIdDecidesItBeforeTheNameIsEvenRead() {
        let id = "b7ffd2af-418f-4be2-bdd1-22f8b48613da"
        var listed = act("Nick Cave and the Bad Seeds (AU)", "20:00", "Amfiet")
        listed.mbid = id
        XCTAssertEqual(listed, matchAct([listed], name: "something else entirely", mbid: id))
    }

    func testASetThatHasFinishedIsSomethingThatHappenedNotSomethingPlanned() {
        let over = act("Played already", "13:00", "Amfiet", date: "2026-08-15")
        let played = playedActs([over], now: moment("2026-08-15", "18:00"), calendar: cal)
        XCTAssertTrue(played.contains(actKey(over)))
    }

    func testASetStillToComeIsNotSomethingThatHappened() {
        let later = act("On tonight", "22:00", "Amfiet", date: "2026-08-15")
        let played = playedActs([later], now: moment("2026-08-15", "18:00"), calendar: cal)
        XCTAssertFalse(played.contains(actKey(later)))
    }
}
