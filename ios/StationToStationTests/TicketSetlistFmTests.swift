import XCTest
@testable import StationToStation

/// #531's review prompt, decided without the sheet: which candidates it shows, and what
/// Add with one ticked — or with "None of these" — writes. Case for case with Android's
/// `TicketSetlistFmTest`. Everything here is synthetic.
final class TicketSetlistFmTests: XCTestCase {

    private func hit(_ id: String, venue: String = "The Hall", city: String = "Oslo") -> FmSetlist {
        FmSetlist(
            id: id,
            eventDate: "14-03-2031",
            artist: FmArtist(mbid: "mbid-1", name: "The Examples"),
            venue: FmVenue(name: venue, city: FmCity(name: city)),
            url: "https://www.setlist.fm/setlist/\(id).html"
        )
    }

    private func candidate(_ id: String, venue: MatchLevel? = .weak) -> SetlistFmCandidate {
        SetlistFmCandidate(setlist: hit(id), artist: .strong, date: .strong, venue: venue)
    }

    private var offered: TicketSetlistFm {
        TicketSetlistFm(
            candidates: [candidate("a1"), candidate("b2")],
            preselectedId: nil,
            artist: "The Examples",
            date: "14-03-2031",
            lookedUpAt: 1_000
        )
    }

    private var foundNothing: TicketSetlistFm {
        var none = offered
        none.candidates = []
        return none
    }

    func testCandidatesShowForTheSearchThatFoundThem() {
        XCTAssertTrue(offered.offeredFor(artist: "The Examples", date: "14-03-2031"))
        XCTAssertTrue(offered.offeredFor(artist: " The Examples ", date: "14-03-2031"))
    }

    func testEditingTheArtistOrTheDateHidesTheCandidates() {
        XCTAssertFalse(offered.offeredFor(artist: "The Example", date: "14-03-2031"))
        XCTAssertFalse(offered.offeredFor(artist: "The Examples", date: "15-03-2031"))
        XCTAssertFalse(offered.offeredFor(artist: "The Examples", date: "not a date"))
    }

    func testALookupThatFoundNothingOffersNothing() {
        XCTAssertFalse(foundNothing.offeredFor(artist: "The Examples", date: "14-03-2031"))
    }

    func testChoosingACandidateTakesItAndRejectsNothing() {
        let answer = offered.answer(artist: "The Examples", date: "14-03-2031", chosenId: "b2")
        XCTAssertEqual(answer.chosen?.id, "b2")
        XCTAssertEqual(answer.rejectedIds, [])
        XCTAssertEqual(answer.lookedUpAt, 1_000)
    }

    func testNoneOfTheseRejectsEveryCandidateOfferedAndStamps() {
        let answer = offered.answer(artist: "The Examples", date: "14-03-2031", chosenId: nil)
        XCTAssertNil(answer.chosen)
        XCTAssertEqual(answer.rejectedIds, ["a1", "b2"])
        let stored = answer.applyTo(StoredSetlistFmLookup(rejectedIds: ["z9"]))
        XCTAssertEqual(stored.rejectedIds, ["z9", "a1", "b2"])
        XCTAssertEqual(stored.lastLookupAt, 1_000)
    }

    func testAnIdNotAmongTheCandidatesReadsAsNoneOfThese() {
        XCTAssertEqual(
            offered.answer(artist: "The Examples", date: "14-03-2031", chosenId: "elsewhere").rejectedIds,
            ["a1", "b2"])
    }

    func testAnEditedSearchRecordsNoRejectionAndNoStamp() {
        let answer = offered.answer(artist: "Other Band", date: "14-03-2031", chosenId: nil)
        XCTAssertEqual(answer, TicketSetlistFmAnswer.unasked)
        XCTAssertFalse(answer.recordsAnything)
        let before = StoredSetlistFmLookup(lastLookupAt: 5)
        XCTAssertEqual(answer.applyTo(before), before)
    }

    func testALookupThatFoundNothingStillStampsTheLandedGig() {
        let answer = foundNothing.answer(artist: "The Examples", date: "14-03-2031", chosenId: nil)
        XCTAssertTrue(answer.recordsAnything)
        XCTAssertEqual(answer.rejectedIds, [])
        XCTAssertEqual(answer.applyTo(StoredSetlistFmLookup()).lastLookupAt, 1_000)
    }

    func testStampingNeverMovesALaterLookupBack() {
        let answer = offered.answer(artist: "The Examples", date: "14-03-2031", chosenId: nil)
        XCTAssertEqual(answer.applyTo(StoredSetlistFmLookup(lastLookupAt: 9_000)).lastLookupAt, 9_000)
    }

    func testTheChipDrawsOnlyTheHitsStillPending() {
        let a = StoredSetlistFmHit(candidate("a1"))
        let b = StoredSetlistFmHit(candidate("b2"))
        let lookup = StoredSetlistFmLookup(pendingHitIds: ["b2"], pendingHits: [a, b])
        XCTAssertEqual(lookup.chipHits(), [b])
        XCTAssertEqual(StoredSetlistFmLookup(pendingHitIds: ["x"]).chipHits(), [])
    }

    func testARowWithNoQuestionReadsArtistVenueAndDate() {
        let a = StoredSetlistFmHit(candidate("a1"))
        XCTAssertEqual(a.line(), "The Examples — The Hall, Oslo — 14-03-2031")
        var bare = a
        bare.venue = ""
        bare.city = " "
        XCTAssertEqual(bare.line(), "The Examples — 14-03-2031")
    }

    func testAFetchedHitStoresWithNoVenueLevel() {
        let stored = hit("c3").asStoredHit()
        XCTAssertEqual(stored.id, "c3")
        XCTAssertEqual(stored.venue, "The Hall")
        XCTAssertNil(stored.venueLevel)
    }
}
