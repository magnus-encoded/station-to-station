import XCTest
@testable import StationToStation

@MainActor
final class GigControllerTests: XCTestCase {

    private var fake: FakeState!
    private var store: TimelineStore!
    private var gig: GigController!
    private var gossip: GossipController!

    override func setUp() async throws {
        fake = FakeState()
        store = TimelineStore(file: URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("gig-\(UUID().uuidString).json"))
        gossip = GossipController(host: fake, timelines: store)
        gig = GigController(
            host: fake,
            timelines: store,
            setlistFm: SetlistFmClient(keySource: { nil }),
            location: DeviceLocation(),
            sortedPlanned: { $0 },
            gossip: gossip
        )
    }

    private func aNight(date: String = "13-08-2026") -> FmSetlist {
        FmSetlist(id: "gig-\(UUID().uuidString.lowercased())", eventDate: date, artist: FmArtist(name: "A Band"))
    }

    private func planned(_ gig: FmSetlist) async {
        fake.state.attendanceByGig[gig.id] = await store.savePlanned(gig)
        fake.state.plannedGigs.append(gig)
    }

    private func eventually(_ what: String, _ condition: () async -> Bool) async {
        for _ in 0..<300 {
            if await condition() { return }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTFail("never: \(what)")
    }

    func testDeletingAGigClearsItsStateAtOnceAndTheStoreAfter() async {
        let night = aNight()
        await planned(night)
        fake.state.selectedSetlist = night
        fake.state.gigLog = StoredLog(songs: ["Opener"])
        fake.state.calendarEventByGig[night.id] = "event"

        gig.deleteGig(night.id)

        XCTAssertFalse(fake.state.plannedGigs.contains { $0.id == night.id })
        XCTAssertNil(fake.state.attendanceByGig[night.id])
        XCTAssertNil(fake.state.calendarEventByGig[night.id])
        XCTAssertNil(fake.state.selectedSetlist)
        XCTAssertEqual(fake.state.gigLog, StoredLog())
        await eventually("the store forgets it") {
            !(await store.load().planned().contains { $0.id == night.id })
        }
    }

    func testForgettingAPlanDropsItFromStateAndStore() async {
        let night = aNight()
        await planned(night)

        gig.removePlannedGig(night.id)

        XCTAssertFalse(fake.state.plannedGigs.contains { $0.id == night.id })
        XCTAssertNil(fake.state.attendanceByGig[night.id])
        await eventually("the store forgets it") {
            !(await store.load().planned().contains { $0.id == night.id })
        }
    }

    func testCheckingInClearsTheOfferAndLandsTheClaimOnTheOpenGig() async {
        let night = aNight()
        await planned(night)
        fake.state.checkInOffer = night
        fake.state.selectedSetlist = night

        gig.checkIn(night.id)

        XCTAssertNil(fake.state.checkInOffer)
        await eventually("the claim is checked in") {
            fake.state.attendanceByGig[night.id]?.provenance == "checked_in"
        }
        XCTAssertEqual(fake.state.selectedAttendance?.provenance, "checked_in")
        XCTAssertNotNil(fake.state.selectedAttendance?.checkedInAt)
        let stored = await store.load().attendance()[night.id]
        XCTAssertEqual(stored?.provenance, "checked_in")
    }

    func testCheckInIsDueOnTheNightOfAPlannedGig() async {
        let night = aNight(date: "13-08-2026")
        await planned(night)
        var evening = DateComponents()
        evening.year = 2026; evening.month = 8; evening.day = 13; evening.hour = 21
        let now = Calendar.current.date(from: evening)!

        let due = await gig.checkInDue(now: now)

        XCTAssertTrue(due)
    }

    func testAPastedLinkThatIsNotASetlistIsRefused() {
        gig.adoptSetlistLink(gigId: "whatever", linkOrId: "not a link")

        XCTAssertEqual(fake.state.error, "That doesn't look like a setlist.fm link.")
    }

    func testANightThatAlreadyHasASetlistFmIdDoesNotTakeAnother() async {
        let local = await store.createLocalGig(date: "13-08-2026", artist: "A Band", venue: "")
        await store.adoptSetlistId(gigId: local, setlistId: "1a2b3c4d")

        gig.adoptSetlistLink(gigId: local, linkOrId: "5e6f7a8b")

        await eventually("the refusal is shown") {
            fake.state.error == "That night already has a setlist.fm id."
        }
    }

    func testTheLogIsWrittenToStateAndStoreForTheOpenGig() async {
        let night = aNight()
        fake.state.selectedSetlist = night

        gig.addToLog("Opener")
        gig.addToLog("Closer")
        gig.correctLogEntry(1, title: "Encore")
        gig.setLogClosed(true)

        XCTAssertEqual(fake.state.gigLog.songs, ["Opener", "Encore"])
        XCTAssertTrue(fake.state.gigLog.closed)
        await eventually("the store holds the Log") {
            await store.log(setlistId: night.id) == fake.state.gigLog
        }
    }

    func testWithNoGigOpenTheLogIsNotTouched() {
        gig.addToLog("Opener")

        XCTAssertEqual(fake.state.gigLog, StoredLog())
    }

    func testANoteIsWrittenThenEmptiedAway() {
        let night = aNight()
        fake.state.selectedSetlist = night

        gig.setGigNote(.shared, text: "  Loud  ")
        let note = fake.state.mediaBySetlist[night.id]?.first
        XCTAssertEqual(note?.text, "Loud")

        gig.setGigVerdict(note!.id, verdict: "up")
        XCTAssertEqual(fake.state.mediaBySetlist[night.id]?.first?.verdict, "up")

        gig.setGigNote(.shared, text: " ")
        XCTAssertEqual(fake.state.mediaBySetlist[night.id], [])
    }
}
