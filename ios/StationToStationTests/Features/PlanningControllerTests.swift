import XCTest
@testable import StationToStation

@MainActor
final class PlanningControllerTests: XCTestCase {

    private var store: TimelineStore!
    private var fake: FakeState!
    private var gossip: GossipController!
    private var gig: GigController!
    private var model: PlanningController!

    private var state: UiState {
        get { fake.state }
        set { fake.state = newValue }
    }

    override func setUp() async throws {
        store = TimelineStore(file: URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("planning-\(UUID().uuidString).json"))
        fake = FakeState()
        let setlistFm = SetlistFmClient(keySource: { nil })
        gossip = GossipController(host: fake, timelines: store)
        gig = GigController(host: fake, timelines: store, setlistFm: setlistFm,
                            location: DeviceLocation(), gossip: gossip)
        model = PlanningController(
            host: fake,
            timelines: store,
            setlistFm: setlistFm,
            musicBrainz: MusicBrainzClient(),
            gig: gig,
            gossip: gossip,
            loadTimeline: {},
            markSelectedOwnership: { _, _ in }
        )
    }

    private func eventually(_ condition: () -> Bool) async {
        let deadline = Date().addingTimeInterval(5)
        while !condition() && Date() < deadline {
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
    }

    private func show(_ id: String, _ date: String, _ artist: String = "A Band") -> FmSetlist {
        FmSetlist(id: id, eventDate: date, artist: FmArtist(name: artist))
    }

    func testANightBeforeTodayIsAddedAsAttended() async {
        model.addGig(artist: "A Band", venue: "Blå", date: "01-02-2020", today: "2026-10-05")

        await eventually { state.plannedGigs.count == 1 }
        let id = try! XCTUnwrap(state.plannedGigs.first?.id)
        XCTAssertEqual("attended", state.attendanceByGig[id]?.provenance)
        let stored = await store.load().attendance()[id]
        XCTAssertEqual("attended", stored?.provenance)
    }

    func testANightFromTodayOnIsAddedAsPlanned() async {
        model.addGig(artist: "A Band", venue: "Blå", date: "13-08-2030", today: "2026-10-05")

        await eventually { state.plannedGigs.count == 1 }
        let id = try! XCTUnwrap(state.plannedGigs.first?.id)
        XCTAssertEqual("planned", state.attendanceByGig[id]?.provenance)
        let stored = await store.load().attendance()[id]
        XCTAssertEqual("planned", stored?.provenance)
    }

    func testANightWithNoArtistIsRefusedWithWordsAndNothingIsAdded() async {
        model.addGig(artist: "  ", venue: "Blå", date: "13-08-2030", today: "2026-10-05")

        XCTAssertEqual("A night needs who played and a date as dd-MM-yyyy.", state.error)
        try? await Task.sleep(nanoseconds: 100_000_000)
        XCTAssertTrue(state.plannedGigs.isEmpty)
    }

    func testSomethingThatIsNotAGigLinkIsRefusedWithWords() {
        model.addPlannedGig("not a link")

        XCTAssertEqual("That doesn't look like a setlist.fm gig link.", state.error)
        XCTAssertFalse(state.planningLoading)
    }

    func testAGigAlreadyPlannedIsNotFetchedAgain() {
        state.plannedGigs = [show("53705b8d", "13-08-2030")]

        model.addPlannedGig("53705b8d")

        XCTAssertFalse(state.planningLoading)
        XCTAssertNil(state.error)
    }

    func testPlannedGigsAreReadFromDiskFurthestFutureFirst() async {
        await store.savePlanned(show("near", "13-08-2026"))
        await store.savePlanned(show("far", "13-08-2030"))

        model.loadPlannedGigs()

        await eventually { state.plannedGigs.count == 2 }
        XCTAssertEqual(["far", "near"], state.plannedGigs.map(\.id))
        XCTAssertEqual("planned", state.attendanceByGig["near"]?.provenance)
    }

    func testACommittedProgrammeMintsEachPickedActAsAPlannedGigOfTheFestival() async {
        let acts = [
            ProgrammeAct(artist: "A Band", date: "2030-08-07", start: "18:00", stage: "Amfiet"),
            ProgrammeAct(artist: "B Band", date: "2030-08-08", start: "19:00", stage: "Vika"),
            ProgrammeAct(artist: "Not Picked", date: "2030-08-08", start: "20:00", stage: "Vika"),
        ]
        let programme = StoredProgramme(id: "oya30", name: "Øya", acts: acts)
        let picked = Set(acts.prefix(2).map { actKey($0) })

        model.commitProgramme(programme, diff: ProgrammeDiff(add: Array(acts.prefix(2))),
                              picked: picked, now: parseISODateUTC("2030-01-01")!)

        await eventually { state.plannedGigs.count == 2 }
        XCTAssertEqual(["Amfiet", "Vika"], state.plannedGigs.map { $0.venue?.name ?? "" }.sorted())
        let festivalId = programmeFestivalId(programme)
        for gig in state.plannedGigs {
            XCTAssertEqual("planned", state.attendanceByGig[gig.id]?.provenance)
            XCTAssertEqual(festivalId, state.festivals.idByShow[gig.id])
        }
        XCTAssertEqual("Øya", state.festivals.byId[festivalId]?.name)
    }

    func testAnActAlreadyOnTheLineIsAdoptedIntoTheFestivalNotMintedAgain() async {
        let held = show("53705b8d", "07-08-2030")
        state.timelineShows = [held]
        let acts = [
            ProgrammeAct(artist: "A Band", date: "2030-08-07", start: "18:00", stage: "Amfiet"),
            ProgrammeAct(artist: "B Band", date: "2030-08-08", start: "19:00", stage: "Vika"),
        ]
        let programme = StoredProgramme(id: "oya30", name: "Øya", acts: acts)

        model.commitProgramme(programme, diff: ProgrammeDiff(add: acts),
                              picked: Set(acts.map { actKey($0) }), now: parseISODateUTC("2030-01-01")!)

        await eventually { state.plannedGigs.count == 1 }
        XCTAssertEqual("Vika", state.plannedGigs.first?.venue?.name)
        XCTAssertEqual(programmeFestivalId(programme), state.festivals.idByShow[held.id])
    }

    func testAnEmptyProgrammeDiffWritesNothing() async {
        let act = ProgrammeAct(artist: "A Band", date: "2030-08-07", stage: "Amfiet")
        model.commitProgramme(StoredProgramme(id: "oya30", acts: [act]), diff: ProgrammeDiff(),
                              picked: [actKey(act)])

        try? await Task.sleep(nanoseconds: 200_000_000)
        XCTAssertTrue(state.plannedGigs.isEmpty)
        let planned = await store.load().planned()
        XCTAssertTrue(planned.isEmpty)
    }

    func testJoiningAContactsComingGigPlansItAndClaimsNothing() async {
        let gig = show("53705b8d", "13-08-2030")

        model.joinGig(gig)

        await eventually { state.plannedGigs.contains { $0.id == gig.id } }
        XCTAssertEqual("planned", state.attendanceByGig[gig.id]?.provenance)
        XCTAssertEqual("planned", state.selectedAttendance?.provenance)
    }

    func testJoiningAContactsPastGigClaimsIHaveBeenThere() async {
        let gig = show("53705b8d", "13-08-2020")

        model.joinGig(gig)

        await eventually { state.selectedAttendance != nil }
        XCTAssertEqual("attended", state.attendanceByGig[gig.id]?.provenance)
        let stored = await store.load().attendance()[gig.id]
        XCTAssertEqual("attended", stored?.provenance)
    }

    func testACalendarEventIsRememberedForItsGig() async {
        await store.savePlanned(show("g1", "13-08-2030"))

        model.markCalendarAdded("g1", eventId: "EVENT-1")

        XCTAssertEqual("EVENT-1", state.calendarEventByGig["g1"])
        var stored: [String: String] = [:]
        let deadline = Date().addingTimeInterval(5)
        while stored["g1"] == nil && Date() < deadline {
            stored = await store.load().calendarEvents()
        }
        XCTAssertEqual("EVENT-1", stored["g1"])
    }
}
