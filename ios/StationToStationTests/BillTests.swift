import XCTest
@testable import StationToStation

/// The Swift twin of Android's `BillTest` — the **Bill** half of it. The **Log** half
/// already lives in `StoredLogTests`, because iOS split `data/Bill.kt` into the two
/// files those records actually belong in.
///
/// Ported case for case rather than re-derived. The two platforms share one cache file,
/// so a rule asserted on one side and not the other is a corruption with extra steps.
///
/// A **Bill** is what a poster is: names, a place, a range of nights, and no gigs. The
/// nights are the range's, never the clock's — dating an act on the day someone happened
/// to open the app is the fabrication `StoredBill` exists to refuse.
///
/// A poster with the shapes that actually turn up: a suffix that is not a disambiguation,
/// an ampersand, a non-ASCII character, and a leading `?` hedge on the last line only.
private let poster = """
Paper Cranes TRIO
Nord&Nord
Halden Drift
?Åse Lindqvist
"""

/// A fixed zone, so a night is the same night wherever CI is standing.
private let cal: Calendar = {
    var c = Calendar(identifier: .gregorian)
    c.timeZone = TimeZone(identifier: "Europe/Oslo")!
    return c
}()

private func at(_ year: Int, _ month: Int, _ day: Int, _ hour: Int, _ minute: Int = 0) -> Date {
    cal.date(from: DateComponents(year: year, month: month, day: day,
                                  hour: hour, minute: minute))!
}

/// A festival shaped like the one this was built for — three nights, invented names —
/// because the case that broke was a Bill read on the day after it ended.
private let threeNights = StoredBill(
    id: "nordlys",
    name: "Nordlys Fields 2026",
    city: "Kalmarhavn",
    from: "06-08-2026",
    to: "09-08-2026",
    acts: parseLineup("Paper Cranes\nVelvet Ditch")
)

final class BillTests: XCTestCase {

    // MARK: - The lineup, as it is actually pasted in

    func testAPastedLineupBecomesActsInPosterOrder() {
        XCTAssertEqual(["Paper Cranes TRIO", "Nord&Nord", "Halden Drift", "Åse Lindqvist"],
                       parseLineup(poster).map(\.name))
    }

    func testALeadingQuestionMarkIsThePostersOwnHedgeAndOnlyThatActs() {
        XCTAssertEqual([false, false, false, true], parseLineup(poster).map(\.maybe))
    }

    func testNoActArrivesWithANightThatIsTheWholePoint() {
        XCTAssertTrue(parseLineup(poster).allSatisfy { $0.gigId == nil })
    }

    func testBlankLinesBulletsAndRepeatsAreDropped() {
        let acts = parseLineup("- Halden Drift\n\n  \n• Halden Drift\n* Paper Cranes\n")
        XCTAssertEqual(["Halden Drift", "Paper Cranes"], acts.map(\.name))
    }

    // MARK: - The night an act tapped in the field belongs to

    func testAnActTappedDuringTheEveningIsTonight() {
        XCTAssertEqual("06-08-2026", billNight(now: at(2026, 8, 6, 22, 30), calendar: cal))
    }

    func testAnActTappedWalkingOutAtHalfOneIsStillLastNight() {
        XCTAssertEqual("06-08-2026", billNight(now: at(2026, 8, 7, 1, 30), calendar: cal))
    }

    func testSixInTheMorningIsANewDayTheSameEdgeCheckInDraws() {
        XCTAssertEqual("07-08-2026", billNight(now: at(2026, 8, 7, 6, 0), calendar: cal))
    }

    // MARK: - A Bill knows when it is (#135)

    func testABillsNightsAreEveryDayOfItsRangeEndsIncluded() {
        XCTAssertEqual(["06-08-2026", "07-08-2026", "08-08-2026", "09-08-2026"],
                       billNights(threeNights, calendar: cal))
    }

    func testABillWithNoDatesTypedInHasNoNightsAndNoRangeToArgueWith() {
        XCTAssertEqual([], billNights(StoredBill(name: "Nordlys Fields"), calendar: cal))
    }

    func testWhereTheClockStandsRelativeToTheRangeIsTheWholeQuestion() {
        XCTAssertEqual(.before, billWhen(threeNights, now: at(2026, 8, 5, 20, 0), calendar: cal))
        XCTAssertEqual(.during, billWhen(threeNights, now: at(2026, 8, 7, 22, 30), calendar: cal))
        XCTAssertEqual(.after, billWhen(threeNights, now: at(2026, 8, 10, 12, 0), calendar: cal))
    }

    func testWalkingOutOfTheLastNightAtHalfOneIsStillDuringTheFestival() {
        // `nightEndsHour` decides which night it is, and this reads the same boundary:
        // the festival is not over while its last night is still going on.
        XCTAssertEqual(.during, billWhen(threeNights, now: at(2026, 8, 10, 1, 30), calendar: cal))
    }

    func testInsideTheRangeTheClockStillDatesTheGigExactlyAsItAlwaysDid() {
        let now = at(2026, 8, 7, 22, 30)
        XCTAssertEqual(billNight(now: now, calendar: cal),
                       gigNight(threeNights, chosen: nil, now: now, calendar: cal))
        XCTAssertEqual("07-08-2026", gigNight(threeNights, chosen: nil, now: now, calendar: cal))
    }

    func testAfterTheFestivalTheClockCannotDateAnythingItHasToBeAsked() {
        // The field report: Nordlys Fields ran 6–9 August, the phone says the 10th, and
        // tapping an act that never got a night minted a Gig dated 10 August — a night
        // the festival did not have.
        let theDayAfter = at(2026, 8, 10, 12, 0)
        XCTAssertNil(gigNight(threeNights, chosen: nil, now: theDayAfter, calendar: cal))
        XCTAssertEqual("08-08-2026",
                       gigNight(threeNights, chosen: "08-08-2026", now: theDayAfter, calendar: cal))
    }

    func testBeforeTheFestivalOpensNothingHasPlayedSoThereIsNoNightToGive() {
        XCTAssertNil(gigNight(threeNights, chosen: nil, now: at(2026, 8, 5, 23, 0), calendar: cal))
    }

    func testANightTheFestivalDidNotHaveIsRefusedHoweverItWasArrivedAt() {
        // The invariant, stated as the one thing that cannot happen: no clock and no
        // choice can date a Gig outside the Bill it was minted from.
        let clocks = (4...11).map { at(2026, 8, $0, 1, 30) } + (4...11).map { at(2026, 8, $0, 21, 0) }
        let choices: [String?] = [nil]
            + (1...14).map { fmDate(at(2026, 8, $0, 12, 0), calendar: cal) }
            + [fmDate(at(2025, 8, 7, 12, 0), calendar: cal)]
        let nights = billNights(threeNights, calendar: cal)
        for now in clocks {
            for chosen in choices {
                guard let night = gigNight(threeNights, chosen: chosen, now: now, calendar: cal)
                else { continue }
                XCTAssertTrue(nights.contains(night),
                              "minted \(night) outside \(threeNights.from)..\(threeNights.to)")
            }
        }
    }

    func testTheMintedGigItselfCarriesADateInsideTheRange() {
        // The invariant where it actually lands: on the record, in setlist.fm's own date
        // shape, which is what artist + venue + day has to match on later.
        let night = gigNight(threeNights, chosen: "08-08-2026",
                             now: at(2026, 8, 10, 12, 0), calendar: cal)
        let gig = localGigSetlist(gigId: "local-1", artist: "Velvet Ditch", date: night ?? "",
                                  venue: threeNights.name, city: threeNights.city)
        XCTAssertEqual("08-08-2026", gig.eventDate)
        XCTAssertTrue(billNights(threeNights, calendar: cal).contains(gig.eventDate ?? ""))
    }

    func testABillWithNoRangeLeftIsTheClocksAsBeforeNothingToDisagreeWith() {
        // An undated Bill is a real thing to be holding, and it keeps the old behaviour
        // rather than becoming un-markable.
        let undated = StoredBill(id: "u", name: "Nordlys Fields")
        let now = at(2026, 8, 10, 23, 0)
        XCTAssertEqual("10-08-2026", gigNight(undated, chosen: nil, now: now, calendar: cal))
        XCTAssertEqual(.during, billWhen(undated, now: now, calendar: cal))
    }

    func testAOneDayBillHasOneNightAndItIsTheOnlyAnswer() {
        let oneDay = StoredBill(id: "d", name: "Harbour Sessions", from: "06-08-2026")
        XCTAssertEqual(["06-08-2026"], billNights(oneDay, calendar: cal))
        XCTAssertNil(gigNight(oneDay, chosen: nil, now: at(2026, 8, 7, 20, 0), calendar: cal))
        XCTAssertEqual("06-08-2026",
                       gigNight(oneDay, chosen: "06-08-2026", now: at(2026, 8, 7, 20, 0), calendar: cal))
    }

    // MARK: - The Gig an Act becomes

    func testALocalGigCarriesItsOwnIdAndNoSetlistFmPage() {
        let gig = localGigSetlist(gigId: "local-1", artist: "Velvet Ditch", date: "06-08-2026",
                                  venue: "Nordlys Fields 2026", city: "Norway")
        XCTAssertEqual("local-1", gig.id)
        XCTAssertEqual("06-08-2026", gig.eventDate)
        XCTAssertEqual("Velvet Ditch", gig.artist?.name)
        XCTAssertEqual("Nordlys Fields 2026", gig.venue?.name)
        XCTAssertNil(gig.url)
        XCTAssertTrue(gig.isLocal)
    }

    func testALocalGigNeverCarriesSongsThoseAreTheLogsNotASetlists() {
        let gig = localGigSetlist(gigId: "local-1", artist: "Halden Drift", date: "07-08-2026",
                                  venue: "Nordlys Fields", city: "")
        XCTAssertTrue(gig.performed().isEmpty)
        XCTAssertNil(gig.sets)
    }

    func testABlankVenueIsUnknownRatherThanAPlaceTwoGigsShare() {
        // Nil, not "": empty strings compare equal, so two nights that merely both lack
        // a venue would cluster as one place (#128).
        let gig = localGigSetlist(gigId: "a", artist: "Nord&Nord", date: "06-08-2026",
                                  venue: "", city: "")
        XCTAssertNil(gig.venue?.name)
        XCTAssertNil(gig.venue?.city?.name)
    }

    func testTwoActsOnTheSameNightAtOneVenueAreOneEveningsWorthOfGigs() {
        // The payoff of dating acts rather than inventing dates: once they are real
        // nights they group by venue and date exactly like any other pair of shows.
        let a = localGigSetlist(gigId: "a", artist: "Nord&Nord", date: "06-08-2026",
                                venue: "Nordlys Fields", city: "")
        let b = localGigSetlist(gigId: "b", artist: "Halden Drift", date: "06-08-2026",
                                venue: "Nordlys Fields", city: "")
        let nodes = groupIntoFestivals([b, a])
        XCTAssertEqual(1, nodes.count)
        guard case .section = nodes.first else {
            return XCTFail("two acts on one night at one venue should be one Section")
        }
    }

    // MARK: - The record on the wire

    func testABillSurvivesTheRoundTripThroughTheCacheFormat() {
        // Modelled rather than carried raw since #172, which means this side now owns
        // the decode — and a field that silently stops round-tripping is data loss the
        // next Android read discovers, not this one.
        let bill = StoredBill(id: "nordlys", name: "Nordlys Fields 2026", city: "Kalmarhavn",
                              from: "06-08-2026", to: "09-08-2026",
                              acts: [StoredAct(name: "Velvet Ditch", maybe: true,
                                               candidates: ["Low Tide"], gigId: "g1",
                                               matchedArtist: "Velvet Ditch (NO)",
                                               mbid: "mb-1", tried: true, surprise: true)])
        let data = try! JSONEncoder().encode(bill)
        XCTAssertEqual(bill, try! JSONDecoder().decode(StoredBill.self, from: data))
    }

    func testAnOlderRecordMissingEveryNewFieldStillDecodes() {
        // kotlinx falls back to the default on a missing key; so does this, or a cache
        // written before a field existed takes the whole file down on read.
        let bill = try! JSONDecoder().decode(
            StoredBill.self,
            from: Data(#"{"id":"b","acts":[{"name":"Velvet Ditch"}]}"#.utf8))
        XCTAssertEqual("b", bill.id)
        XCTAssertEqual("", bill.name)
        XCTAssertEqual([StoredAct(name: "Velvet Ditch")], bill.acts)
    }

    // MARK: - Where a Bill sits in the future lane

    private func planned(_ id: String, _ date: String, _ artist: String,
                         venue: String = "Rockefeller") -> FmSetlist {
        FmSetlist(id: id, eventDate: date, artist: FmArtist(name: artist),
                  venue: FmVenue(name: venue), url: "https://setlist.fm/\(id)")
    }

    func testTheFutureLaneIsOneListFurthestFutureFirst() {
        let rows = futureRows(bills: [threeNights],
                              tickets: [planned("g1", "20-08-2026", "Low Tide"),
                                        planned("g2", "01-08-2026", "Nord&Nord")])
        XCTAssertEqual(["planned-g1", "bill-nordlys", "planned-g2"], rows.map(\.id))
    }

    func testABillSortsByWhenItStartsNotWhenItEnds() {
        // Its last day is the wrong handle: a three-day festival beginning tonight
        // would sort above a gig two days out, which is this same bug one step smaller.
        let rows = futureRows(bills: [threeNights],
                              tickets: [planned("g1", "08-08-2026", "Low Tide")])
        XCTAssertEqual(["planned-g1", "bill-nordlys"], rows.map(\.id))
    }

    func testARowWithNoDateSortsToTheBottomRatherThanTheTop() {
        // Unknown is not "the furthest away". It still renders: a Bill whose dates were
        // never typed in is a real thing to be holding.
        let undated = StoredBill(id: "u", name: "Harbour Sessions",
                                 acts: parseLineup("Velvet Ditch"))
        let rows = futureRows(bills: [undated, threeNights],
                              tickets: [planned("g1", "01-08-2026", "Low Tide")])
        XCTAssertEqual(["bill-nordlys", "planned-g1", "bill-u"], rows.map(\.id))
    }

    func testTwoPlannedNightsAtOnePlaceAreOneRowAboveTodayToo() {
        // The same shape below today is the same shape above it — the lane used to draw
        // them as two loose nodes only because it did its own grouping, which was none
        // (#134).
        let rows = futureRows(bills: [], tickets: [
            planned("g1", "20-08-2026", "Low Tide", venue: "Sentrum"),
            planned("g2", "20-08-2026", "Nord&Nord", venue: "Sentrum"),
        ])
        XCTAssertEqual(1, rows.count)
        guard case .ticket(let node) = rows[0], case .section = node else {
            return XCTFail("two planned nights at one venue should be one Section")
        }
        XCTAssertEqual(2, node.shows.count)
    }

    // MARK: - The pool an Act's setlist is ticked off from

    private func setlist(_ id: String, _ songs: [String]) -> FmSetlist {
        FmSetlist(id: id, sets: FmSets(set: [FmSet(song: songs.map { FmSong(name: $0) })]))
    }

    func testThePoolIsWhatTheyHaveBeenPlayingMostPlayedFirst() {
        // Frequency across the most recent setlists, not the single latest one: a set
        // has a stable core and a rotating edge, and the core is worth offering first.
        let pool = candidateSongs([
            setlist("a", ["Low Tide", "Harbour"]),
            setlist("b", ["Low Tide", "Kalmar"]),
            setlist("c", ["Low Tide", "Harbour"]),
        ])
        XCTAssertEqual(["Low Tide", "Harbour", "Kalmar"], pool)
    }

    func testEqualCountsKeepTheOrderTheMostRecentSetlistPlayedThem() {
        XCTAssertEqual(["Harbour", "Kalmar"], candidateSongs([setlist("a", ["Harbour", "Kalmar"])]))
    }

    func testOnlyTheMostRecentFewSetlistsAreAsked() {
        // `take` is the window, and a song that has fallen out of the set should fall
        // out of the pool with it.
        let pool = candidateSongs([
            setlist("a", ["Low Tide"]), setlist("b", ["Low Tide"]),
            setlist("c", ["Low Tide"]), setlist("d", ["Low Tide"]),
            setlist("e", ["Retired"]),
        ])
        XCTAssertEqual(["Low Tide"], pool)
    }

    func testAnArtistWithNoHistoryHasAnEmptyPoolWhichIsAnAnswer() {
        XCTAssertEqual([], candidateSongs([]))
    }

    func testAnArtistLabelNamesWhatTellsItFromItsNamesakes() {
        XCTAssertEqual("Silent Majority (US hardcore)",
                       artistLabel(name: "Silent Majority", disambiguation: "US hardcore"))
        XCTAssertEqual("Silent Majority", artistLabel(name: "Silent Majority", disambiguation: nil))
        XCTAssertEqual("Silent Majority", artistLabel(name: "Silent Majority", disambiguation: "  "))
    }

    // MARK: - Telling five bands with one name apart

    func testANamesakeIsFoundByASongYouKnowTheyPlay() {
        XCTAssertTrue(playsSong([setlist("a", ["Harbour", "Low Tide"])], "low tide"))
        XCTAssertFalse(playsSong([setlist("a", ["Harbour"])], "Low Tide"))
    }

    func testTheMatchIgnoresWhatSongKeyAlreadyIgnores() {
        // The same rule corrections use: punctuation and case are not what makes two
        // titles different songs.
        XCTAssertTrue(playsSong([setlist("a", ["Don't Look Back"])], "dont look back"))
    }

    func testNoSetlistsIsNoMatchRatherThanAnError() {
        XCTAssertFalse(playsSong([], "Low Tide"))
    }
}
