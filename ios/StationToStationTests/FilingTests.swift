import XCTest
@testable import StationToStation

/// What crosses the app switch into setlist.fm's form, and where the Historian is
/// sent. The same cases Android's `BillTest.kt` runs on the JVM.
final class FilingTests: XCTestCase {

    private func gig(artist: String = "Halden Drift", date: String = "07-08-2026",
                     venue: String = "Nordlys Fields 2026", city: String = "Kalmarhavn") -> FmSetlist {
        localGigSetlist(gigId: "local-1", artist: artist, date: date, venue: venue, city: city)
    }

    // --- The paste -------------------------------------------------------------

    func testThePasteIsBareTitlesOnePerLineInTheOrderTheyWerePlayed() {
        XCTAssertEqual("Second\nFirst\nSecond",
                       setlistPaste(StoredLog(songs: ["Second", "First", "Second"])))
    }

    func testAGapPastesAsSetlistFmsOwnUnknownMarkerNeverAsNothing() {
        // Dropping it would publish a set silently claiming that song was not played.
        XCTAssertEqual("A\n@Unknown[]\nB", setlistPaste(StoredLog(songs: ["A", "  ", "B"])))
    }

    func testAnEmptyLogPastesToNothingRatherThanToAFabricatedSet() {
        XCTAssertEqual("", setlistPaste(StoredLog()))
    }

    // --- What crosses the app switch -------------------------------------------

    func testTheFilingCarriesEveryFieldTheFormAsksForInTheFormsOwnOrder() {
        // Date before Venue is load-bearing: the venue field is disabled until a date
        // is set.
        let fields = filingFields(gig(), StoredLog(songs: ["A", "B"]))
        XCTAssertEqual(["Artist", "Date", "Venue", "City", "Songs"], fields.map(\.label))
        XCTAssertEqual("Halden Drift", fields[0].value)
        XCTAssertEqual("07-08-2026", fields[1].value)
        XCTAssertEqual("Nordlys Fields 2026", fields[2].value)
        XCTAssertEqual("Kalmarhavn", fields[3].value)
        // The songs field hands over the paste, not the summary line beside it.
        XCTAssertEqual("A\nB", fields[4].value)
        XCTAssertEqual("2 songs, in order", fields[4].shown)
    }

    func testTheDateReadsTheWayACalendarDoesBecauseItIsPickedAndNotPasted() {
        // setlist.fm opens a month grid for this one, so no string can land in it. The
        // value shown is the one you go looking for in that grid.
        let date = filingFields(gig(city: ""), StoredLog()).first { $0.label == "Date" }
        XCTAssertEqual("7 August 2026", date?.shown)
        XCTAssertEqual("07-08-2026", date?.value)
    }

    func testAFieldNobodyTypedInIsLeftOutRatherThanOfferedBlank() {
        // A night with no town gets four values. A fifth, empty, would be a value to
        // paste that says nothing — worse than the absence it is hiding.
        let fields = filingFields(gig(city: ""), StoredLog(songs: ["A"]))
        XCTAssertEqual(["Artist", "Date", "Venue", "Songs"], fields.map(\.label))
    }

    func testNothingLoggedStillFilesTheNightItselfMinusTheSongs() {
        XCTAssertEqual(["Artist", "Date", "Venue", "City"],
                       filingFields(gig(), StoredLog()).map(\.label))
    }

    func testTheSongsLineCountsGapsInBecauseThePasteDoes() {
        // "1 song" beside a two-line paste is the small disagreement that makes
        // someone distrust the whole handoff.
        let songs = filingFields(gig(city: ""), StoredLog(songs: ["A", ""]))
            .first { $0.label == "Songs" }
        XCTAssertEqual("2 songs, in order \u{00B7} 1 unnamed", songs?.shown)
        XCTAssertEqual("A\n@Unknown[]", songs?.value)
    }

    // --- Where the Historian is sent -------------------------------------------

    func testANightSetlistFmAlreadyHasGoesToItsOwnPageNeverABuiltEditUrl() {
        let known = FmSetlist(id: "63a80d2f", url: "https://www.setlist.fm/setlist/x-63a80d2f.html")
        XCTAssertEqual("https://www.setlist.fm/setlist/x-63a80d2f.html", setlistEditEntry(known))
    }

    func testANightSetlistFmHasNeverHeardOfGoesToTheAddFlow() {
        XCTAssertEqual(setlistfmAddURL, setlistEditEntry(gig(city: "")))
    }
}
