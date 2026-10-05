import XCTest
@testable import StationToStation

@MainActor
final class GigControllerTests: XCTestCase {

    private var model: AppModel!
    private var store: TimelineStore!

    override func setUp() async throws {
        model = AppModel()
        store = TimelineStore()
    }

    // Ids are fresh per test: the store under test is shared with whatever else has run.
    private func gig(date: String = "13-08-2026") -> FmSetlist {
        FmSetlist(id: "gig-\(UUID().uuidString.lowercased())", eventDate: date, artist: FmArtist(name: "A Band"))
    }

    private func planned(_ gig: FmSetlist) async {
        model.state.attendanceByGig[gig.id] = await store.savePlanned(gig)
        model.state.plannedGigs.append(gig)
    }

    private func eventually(_ what: String, _ condition: () async -> Bool) async {
        for _ in 0..<300 {
            if await condition() { return }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTFail("never: \(what)")
    }

    func testDeletingAGigClearsItsStateAtOnceAndTheStoreAfter() async {
        let night = gig()
        await planned(night)
        model.state.selectedSetlist = night
        model.state.gigLog = StoredLog(songs: ["Opener"])
        model.state.calendarEventByGig[night.id] = "event"

        model.deleteGig(night.id)

        XCTAssertFalse(model.state.plannedGigs.contains { $0.id == night.id })
        XCTAssertNil(model.state.attendanceByGig[night.id])
        XCTAssertNil(model.state.calendarEventByGig[night.id])
        XCTAssertNil(model.state.selectedSetlist)
        XCTAssertEqual(model.state.gigLog, StoredLog())
        await eventually("the store forgets it") {
            !(await store.load().planned().contains { $0.id == night.id })
        }
    }

    func testForgettingAPlanDropsItFromStateAndStore() async {
        let night = gig()
        await planned(night)

        model.removePlannedGig(night.id)

        XCTAssertFalse(model.state.plannedGigs.contains { $0.id == night.id })
        XCTAssertNil(model.state.attendanceByGig[night.id])
        await eventually("the store forgets it") {
            !(await store.load().planned().contains { $0.id == night.id })
        }
    }

    func testCheckingInClearsTheOfferAndLandsTheClaimOnTheOpenGig() async {
        let night = gig()
        await planned(night)
        model.state.checkInOffer = night
        model.state.selectedSetlist = night

        model.checkIn(night.id)

        XCTAssertNil(model.state.checkInOffer)
        await eventually("the claim is checked in") {
            model.state.attendanceByGig[night.id]?.provenance == "checked_in"
        }
        XCTAssertEqual(model.state.selectedAttendance?.provenance, "checked_in")
        XCTAssertNotNil(model.state.selectedAttendance?.checkedInAt)
        let stored = await store.load().attendance()[night.id]
        XCTAssertEqual(stored?.provenance, "checked_in")
    }

    func testCheckInIsDueOnTheNightOfAPlannedGig() async {
        let night = gig(date: "13-08-2026")
        await planned(night)
        var evening = DateComponents()
        evening.year = 2026; evening.month = 8; evening.day = 13; evening.hour = 21
        let now = Calendar.current.date(from: evening)!

        let due = await model.checkInDue(now: now)

        XCTAssertTrue(due)
    }

    func testAPastedLinkThatIsNotASetlistIsRefused() {
        model.adoptSetlistLink(gigId: "whatever", linkOrId: "not a link")

        XCTAssertEqual(model.state.error, "That doesn't look like a setlist.fm link.")
    }

    func testANightThatAlreadyHasASetlistFmIdDoesNotTakeAnother() async {
        let local = await store.createLocalGig(date: "13-08-2026", artist: "A Band", venue: "")
        await store.adoptSetlistId(gigId: local, setlistId: "1a2b3c4d")

        model.adoptSetlistLink(gigId: local, linkOrId: "5e6f7a8b")

        await eventually("the refusal is shown") {
            model.state.error == "That night already has a setlist.fm id."
        }
    }

    func testTheLogIsWrittenToStateAndStoreForTheOpenGig() async {
        let night = gig()
        model.state.selectedSetlist = night

        model.addToLog("Opener")
        model.addToLog("Closer")
        model.correctLogEntry(1, title: "Encore")
        model.setLogClosed(true)

        XCTAssertEqual(model.state.gigLog.songs, ["Opener", "Encore"])
        XCTAssertTrue(model.state.gigLog.closed)
        await eventually("the store holds the Log") {
            await store.log(setlistId: night.id) == model.state.gigLog
        }
    }

    func testWithNoGigOpenTheLogIsNotTouched() {
        model.addToLog("Opener")

        XCTAssertEqual(model.state.gigLog, StoredLog())
    }

    func testANoteIsWrittenThenEmptiedAway() {
        let night = gig()
        model.state.selectedSetlist = night

        model.setGigNote(.shared, text: "  Loud  ")
        let note = model.state.mediaBySetlist[night.id]?.first
        XCTAssertEqual(note?.text, "Loud")

        model.setGigVerdict(note!.id, verdict: "up")
        XCTAssertEqual(model.state.mediaBySetlist[night.id]?.first?.verdict, "up")

        model.setGigNote(.shared, text: " ")
        XCTAssertEqual(model.state.mediaBySetlist[night.id], [])
    }
}
