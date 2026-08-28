import XCTest
@testable import StationToStation

/// The catalogue an artist's own songs come from (#126), ported from Android's
/// `MusicBrainzTest`. Parsing is kept apart from fetching, so the shape of a reply is
/// asserted without a socket.
///
/// The titles are the real ones for the artist that motivated the feature, because the
/// ranking has to be checked against the actual case. No personal data is involved: a
/// band's song titles are a published catalogue.
final class MusicBrainzTests: XCTestCase {

    private let page = """
    {
      "recording-count": 3,
      "recording-offset": 0,
      "recordings": [
        { "id": "1", "title": "Toothpicks and Gum", "length": 214000 },
        { "id": "2", "title": "High and Apple Sweet" },
        { "id": "3", "title": "Between Stations" }
      ]
    }
    """

    func testAPageOfRecordingsReadsAsTitlesAndATotal() {
        let parsed = parseRecordings(page)

        XCTAssertEqual(3, parsed.count)
        XCTAssertEqual(
            ["Toothpicks and Gum", "High and Apple Sweet", "Between Stations"],
            parsed.recordings.map(\.title)
        )
    }

    /// A reply we cannot read is an empty catalogue, never an exception on a gig screen.
    func testAnUnreadableReplyIsAnEmptyPage() {
        XCTAssertEqual(0, parseRecordings("<html>rate limited</html>").count)
        XCTAssertTrue(parseRecordings("").recordings.isEmpty)
        XCTAssertTrue(parseRecordings("{}").recordings.isEmpty)
    }

    /// MusicBrainz lists every *recording*: a studio take, a live take and a remaster
    /// are three rows and one song. Raw, the panel would offer the same title three
    /// times and bury the rest.
    func testOneEntryPerSongNotPerRecording() {
        let titles = [
            "Toothpicks and Gum",
            "Toothpicks and Gum",
            "toothpicks and gum",
            "Between Stations",
        ]

        XCTAssertEqual(["Toothpicks and Gum", "Between Stations"], dedupe(titles))
    }

    /// Same normalisation as recognition everywhere else, and the first spelling wins.
    func testPunctuationDoesNotMakeASecondEntry() {
        XCTAssertEqual(["Don't Look Back"], dedupe(["Don't Look Back", "Dont Look Back"]))
        XCTAssertTrue(dedupe(["", "  "]).isEmpty)
    }

    /// The whole point of the second source, asserted end to end from the parse: the
    /// pool setlist.fm could offer for this artist is empty, and the catalogue puts the
    /// right answer first.
    func testTheCatalogueAnswersTheCaseTheSetlistPoolCouldNot() {
        let fromSetlistFm: [String] = []
        let catalogue = dedupe(parseRecordings(page).recordings.map(\.title))

        let ranked = rankTitles("All held together by toothpicks and gum", fromSetlistFm + catalogue)

        XCTAssertEqual("Toothpicks and Gum", ranked.first)
        XCTAssertEqual(3, ranked.count)
    }

    /// The twin of Android's `sameSong`, which `dedupe` is built on.
    func testSongKeyThrowsAwayCasePunctuationAndSpacing() {
        XCTAssertTrue(sameSong("P.I.M.P.", "pimp"))
        XCTAssertTrue(sameSong("Don't Look Back", "dontlookback"))
        XCTAssertFalse(sameSong("Between Stations", "Between Nations"))
    }

    /// Artist completion for a planned gig (#350). The four artists called Nirvana are
    /// the reason the disambiguation comes back with the name.
    private let artistPage = """
        {
          "count": 4,
          "artists": [
            { "id": "a", "name": "Nirvana", "score": 74, "disambiguation": "UK band" },
            { "id": "b", "name": "Nirvana", "score": 100, "disambiguation": "US grunge band" },
            { "id": "c", "name": "", "score": 90 },
            { "id": "", "name": "Nirvana 2002", "score": 88 }
          ]
        }
        """

    func testArtistsComeBackBestFirstWithWhatTellsThemApart() {
        let hits = parseArtists(artistPage)

        // The nameless hit and the idless one are both dropped: a row nobody can read
        // is not a suggestion, and one with no id cannot be followed up.
        XCTAssertEqual(2, hits.count)
        XCTAssertEqual("US grunge band", hits.first?.disambiguation)
        XCTAssertEqual("b", hits.first?.mbid)
    }

    func testAReplyThatIsNotWhatWeExpectedIsAnEmptyListNotACrash() {
        XCTAssertTrue(parseArtists("<html>rate limited</html>").isEmpty)
        XCTAssertTrue(parseArtists("").isEmpty)
    }
}
