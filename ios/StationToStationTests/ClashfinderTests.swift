import XCTest
@testable import StationToStation

/// The clashfinder source (#389, #390), ported case for case from Android's
/// `ClashfinderTest`.
///
/// The JSON here is written out rather than captured. A saved copy of a real
/// document would put a festival's programme in the repo, which is the thing this
/// app deliberately does not do; what is being tested is the *shape*, and that is
/// reproducible without anyone's data.
final class ClashfinderTests: XCTestCase {

    private let cal: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(secondsFromGMT: 0)!
        return c
    }()

    private func day(_ ymd: String) -> Date {
        let p = ymd.split(separator: "-").compactMap { Int($0) }
        var comps = DateComponents()
        comps.year = p[0]; comps.month = p[1]; comps.day = p[2]
        return cal.date(from: comps)!
    }

    private let document = """
        {
          "name": "Øyafestivalen 2026",
          "timezone": "Europe/Oslo",
          "copyright": "Clashfinder data CC BY-NC 3.0",
          "lastEdit": "2026-07-11 13:44:46",
          "locations": [
            {"name": "Amfiet", "events": [
              {"name": "Headline", "start": "2026-08-13 21:30", "end": "2026-08-13 23:15",
               "mbId": "b7ffd2af-418f-4be2-bdd1-22f8b48613da"},
              {"name": "Opener", "start": "2026-08-13 16:00", "end": "2026-08-13 16:45"}
            ]},
            {"name": "Klubben", "events": [
              {"name": "After Midnight", "start": "2026-08-14 00:45", "end": "2026-08-14 01:45"}
            ]}
          ]
        }
        """

    func testADocumentFlattensItsStagesIntoActsDownTheClock() {
        let programme = parseClashfinderEvent(document, id: "oyafestivalen2026")
        XCTAssertEqual("Øyafestivalen 2026", programme.name)
        XCTAssertEqual("Clashfinder data CC BY-NC 3.0", programme.copyright)
        XCTAssertEqual("2026-07-11 13:44:46", programme.lastEdit)
        XCTAssertEqual(["Opener", "Headline", "After Midnight"], programme.acts.map(\.artist))
        XCTAssertEqual("Amfiet", programme.acts[0].stage)
    }

    /// The regression that matters most. Clashfinder has already dated the 00:45
    /// set to Saturday; `ProgrammeAct.startsAt` independently pushes anything
    /// before the night boundary forward a day. Split naively, the shift lands
    /// twice and the act appears a full day late.
    func testAnActAfterMidnightBelongsToTheNightBeforeNotTheNextAfternoon() {
        let late = parseClashfinderEvent(document).acts.first { $0.artist == "After Midnight" }!
        XCTAssertEqual(day("2026-08-14").addingTimeInterval(45 * 60), late.startsAt(calendar: cal))
        XCTAssertEqual("2026-08-13", late.date)
    }

    /// The payload carries `mbId`; only that spelling is decoded, and the sibling
    /// `mbid` key is simply unmapped rather than a source of conflict.
    func testTheMusicBrainzIdParses() {
        let headline = parseClashfinderEvent(document).acts.first { $0.artist == "Headline" }!
        XCTAssertEqual("b7ffd2af-418f-4be2-bdd1-22f8b48613da", headline.mbid)
    }

    /// The id goes into the path of a setlist.fm request, off a document anyone
    /// may edit. Anything that is not a MusicBrainz id is dropped at the edge.
    func testAnIdThatIsNotAMusicBrainzIdIsDroppedRatherThanUsed() {
        let doc = """
            {"name": "F", "locations": [{"name": "Stage", "events": [
              {"name": "Act", "start": "2026-08-13 20:00", "mbId": "../../search/artists?q=x"}
            ]}]}
            """
        XCTAssertEqual("", parseClashfinderEvent(doc).acts.first!.mbid)
    }

    func testAPublishedEndTimeSurvivesOntoTheRecord() {
        let headline = parseClashfinderEvent(document).acts.first { $0.artist == "Headline" }!
        XCTAssertEqual("23:15", headline.end)
        XCTAssertNotNil(headline.endsAt(calendar: cal))
    }

    func testAnActMissingARequiredFieldIsDroppedNeverHalfBuilt() {
        let broken = """
            {"name": "Half a festival", "locations": [
              {"name": "Amfiet", "events": [
                {"name": "No start"},
                {"name": "", "start": "2026-08-13 20:00"},
                {"name": "Fine", "start": "2026-08-13 20:00"}
              ]},
              {"name": "", "events": [{"name": "No stage", "start": "2026-08-13 20:00"}]}
            ]}
            """
        XCTAssertEqual(["Fine"], parseClashfinderEvent(broken).acts.map(\.artist))
    }

    func testADocumentThatHasChangedShapeParsesToNothingNotToNonsense() {
        XCTAssertTrue(parseClashfinderEvent(#"{"error":"not found"}"#).acts.isEmpty)
        XCTAssertTrue(parseClashfinderEvent("<html>we redesigned the api</html>").acts.isEmpty)
    }

    // MARK: - The index and the picker

    private let index = """
        {
          "oyafestivalen2026": {"name":"oyafestivalen2026","desc":"Øyafestivalen 2026",
            "edits":4,"numDays":5,"numActs":139,"numStages":16,"startDate":1786406400,
            "private":false,"coreClashfinder":false},
          "oyastub": {"name":"oyastub","desc":"Oyafestivalen 2026 (stub)",
            "edits":1,"numDays":5,"numActs":1,"numStages":1,"startDate":1786406400,
            "private":false,"coreClashfinder":false},
          "nextyear": {"name":"nextyear","desc":"Some Other Festival 2027",
            "edits":9,"numDays":2,"numActs":80,"numStages":4,"startDate":1817856000,
            "private":false,"coreClashfinder":true},
          "secret": {"name":"secret","desc":"Private Party 2026",
            "edits":2,"numDays":1,"numActs":10,"numStages":1,"startDate":1786406400,
            "private":true,"coreClashfinder":false}
        }
        """

    private var festivals: [ClashfinderFestival] { parseClashfinderIndex(index) }

    func testTheIndexReducesToWhatThePickerUses() {
        let oya = festivals.first { $0.id == "oyafestivalen2026" }!
        XCTAssertEqual("Øyafestivalen 2026", oya.name)
        XCTAssertEqual("2026-08-11", oya.start)
        XCTAssertEqual(5, oya.days)
        XCTAssertEqual(139, oya.acts)
        XCTAssertEqual(16, oya.stages)
    }

    func testAPrivateClashfinderIsNotOfferedBecauseItCannotBeOpened() {
        XCTAssertFalse(festivals.contains { $0.id == "secret" })
    }

    func testCandidatesAreOrderedByNearnessToTheDayPastAsWellAsFuture() {
        // Last week beats next year: the picker exists to find the festival you
        // are about to be at or have just been to, not to filter history out of
        // reach.
        let ranked = rankFestivals(festivals, on: day("2026-08-20"), calendar: cal)
        XCTAssertEqual("oyafestivalen2026", ranked.first?.id)
    }

    func testAFestivalInProgressOnTheDayMatchesOnItsWholeRun() {
        // Day two of five. Matching on the start alone misses exactly the person
        // who is standing in the field with the phone out.
        let oya = festivals.first { $0.id == "oyafestivalen2026" }!
        XCTAssertEqual(0, oya.distanceFrom(day("2026-08-12"), calendar: cal))
        XCTAssertEqual(0, oya.distanceFrom(day("2026-08-15"), calendar: cal))
        XCTAssertEqual(1, oya.distanceFrom(day("2026-08-16"), calendar: cal))
    }

    func testAOneActStubRanksBelowTheFullTimetableAndIsStillListed() {
        let ranked = rankFestivals(festivals, on: day("2026-08-11"), calendar: cal).map(\.id)
        XCTAssertEqual(["oyafestivalen2026", "oyastub"], Array(ranked.prefix(2)))
        XCTAssertTrue(ranked.contains("oyastub"))
    }

    func testTwoGenuinelyCompetingEntriesBothSurvive() {
        // Same festival, two spellings, both real. The app orders them; the
        // person chooses.
        let ranked = rankFestivals(festivals, on: day("2026-08-11"), calendar: cal)
        XCTAssertEqual(2, ranked.filter { $0.start == "2026-08-11" }.count)
    }

    func testAQueryWithoutDiacriticsFindsANameThatHasThem() {
        let found = rankFestivals(festivals, on: day("2026-08-11"), query: "oyafest", calendar: cal)
        XCTAssertEqual(["oyafestivalen2026", "oyastub"], found.map(\.id))
    }

    func testSearchCoversTheWholeCatalogueAndNotTheNearestFew() {
        let found = rankFestivals(festivals, on: day("2026-08-11"), query: "other festival", calendar: cal)
        XCTAssertEqual(["nextyear"], found.map(\.id))
    }

    // MARK: - Credentials

    /// The order is the whole test. A wrong concatenation order is a 401
    /// indistinguishable from a mistyped key, and this is the cheapest place to
    /// catch it — the expected digest below is sha256 of the two strings in
    /// *this* order, so swapping them fails here rather than in the field.
    ///
    /// Made-up credentials, deliberately: the order was checked against
    /// clashfinder's own key generator once, and pinning it does not need
    /// anybody's real account.
    func testThePublicKeyIsSha256OfTheUsernameAndThePrivateKeyInThatOrder() {
        XCTAssertEqual(
            "926593c0e73340310b674c5642a657b7072610f1480fefcbcd0c345d6d9f6059",
            clashfinderPublicKey(user: "stationtostation", privateKey: "not-a-real-private-key"))
        // Trimmed, because a pasted key arrives with whatever the clipboard had
        // on it.
        XCTAssertEqual(
            clashfinderPublicKey(user: "stationtostation", privateKey: "not-a-real-private-key"),
            clashfinderPublicKey(user: "  stationtostation ", privateKey: " not-a-real-private-key\n"))
    }

    // MARK: - Artist resolution

    func testAnExactNameBindsDiacriticsAndPunctuationAside() {
        let hits = [FmArtist(mbid: "1", name: "Sigrid"), FmArtist(mbid: "2", name: "Sigríd")]
        XCTAssertEqual("1", matchArtist("Sigrid", hits)?.mbid)
    }

    func testACollaborationBillingResolvesToItsLeadArtist() {
        XCTAssertEqual("Four Tet", billingLead("Four Tet b2b Skrillex"))
        XCTAssertEqual("Beyoncé", billingLead("Beyoncé feat. Jay-Z"))
        // An ampersand is part of a band's name, not a collaboration marker.
        XCTAssertEqual("Nick Cave & The Bad Seeds", billingLead("Nick Cave & The Bad Seeds"))

        let hits = [FmArtist(mbid: "7", name: "Four Tet")]
        XCTAssertEqual("7", matchArtist("Four Tet b2b Skrillex", hits)?.mbid)
    }

    /// The top hit is the search's most confident answer and it is wrong. Its
    /// score cannot gate anything — setlist.fm returns maximum confidence for
    /// results like this — so nothing but exact equality may bind.
    func testANearMissDoesNotBindHoweverConfidentTheSearchIs() {
        let hits = [
            FmArtist(mbid: "1", name: "The Silent Disco Band"),
            FmArtist(mbid: "2", name: "Silent Disco DJs"),
        ]
        XCTAssertNil(matchArtist("Silent Disco", hits))
        XCTAssertNil(matchArtist("Quiz in the Tent", hits))
    }

    func testAFestivalWithNoStartDateIsNotACandidate() {
        let dateless = ClashfinderFestival(id: "x", name: "Undated")
        XCTAssertEqual(Int.max, dateless.distanceFrom(day("2026-08-11"), calendar: cal))
    }

    /// The one route to a document while the app itself is refused: the address a
    /// browser can still fetch, credentials and all.
    func testTheBrowserFallbackUrlCarriesTheAccountsCredentials() {
        let auth = ClashfinderAuth(user: "magnus", publicKey: "abc123")
        let url = clashfinderUrl("event/oyafestivalen2026.json", auth: auth)
        XCTAssertEqual(
            "https://clashfinder.com/data/event/oyafestivalen2026.json?authUsername=magnus&authPublicKey=abc123",
            url)
    }
}
