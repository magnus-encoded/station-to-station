import XCTest
@testable import StationToStation

@MainActor
final class NavigationControllerTests: XCTestCase {

    private struct Offline: Error {}

    private var fake: FakeState!
    private var fetched: [String: FmSetlist] = [:]
    private var refreshed: [Friend] = []
    private var mediaLoaded: [String] = []
    private var identify: ([FmSetlist], Festivals) async -> Festivals = { _, known in known }

    override func setUp() {
        super.setUp()
        fake = FakeState()
        fetched = [:]
        refreshed = []
        mediaLoaded = []
        identify = { _, known in known }
    }

    private func controller() -> NavigationController {
        let store = TimelineStore(file: FileManager.default.temporaryDirectory
            .appendingPathComponent("navigation-\(UUID().uuidString).json"))
        return NavigationController(
            host: fake,
            timelines: store,
            fetchSetlist: { [unowned self] id in
                guard let show = fetched[id] else { throw Offline() }
                return show
            },
            identifyFestivals: { [unowned self] mine, known in await identify(mine, known) },
            refreshLine: { [unowned self] in refreshed.append($0) },
            loadGigMedia: { [unowned self] in mediaLoaded.append($0.id) }
        )
    }

    private func show(_ id: String, _ date: String = "25-06-2025") -> FmSetlist {
        FmSetlist(id: id, eventDate: date, artist: FmArtist(name: "Artist \(id)"), venue: FmVenue(name: "Rockefeller"))
    }

    private func settle() async {
        for _ in 0..<20 { await Task.yield() }
    }

    func testAGigOnMyLineOpensAtOnce() {
        fake.state.timelineShows = [show("3bd6b4f0")]
        var opened = 0

        controller().openGig("3bd6b4f0") { opened += 1 }

        XCTAssertEqual(opened, 1)
        XCTAssertEqual(fake.state.selectedSetlist?.id, "3bd6b4f0")
        XCTAssertEqual(mediaLoaded, ["3bd6b4f0"])
    }

    func testAPlannedGigOpensAtOnce() {
        fake.state.plannedGigs = [show("4bd6b4f1", "25-06-2099")]
        var opened = 0

        controller().openGig("4bd6b4f1") { opened += 1 }

        XCTAssertEqual(opened, 1)
        XCTAssertEqual(fake.state.selectedSetlist?.id, "4bd6b4f1")
    }

    func testAnIdThatIsNoSetlistIsRefusedOnScreen() {
        var opened = 0

        controller().openGig("not a gig") { opened += 1 }

        XCTAssertEqual(opened, 0)
        XCTAssertNil(fake.state.selectedSetlist)
        XCTAssertEqual(fake.state.error, "That doesn't look like a setlist.fm gig link.")
        XCTAssertNil(fake.state.errorKind)
    }

    func testAGigNotOnMyLineIsFetchedThenOpenedWithoutBeingKept() async {
        fetched["63de4613"] = show("63de4613")
        var opened = 0

        controller().openGig("63de4613") { opened += 1 }
        await settle()

        XCTAssertEqual(opened, 1)
        XCTAssertEqual(fake.state.selectedSetlist?.id, "63de4613")
        XCTAssertEqual(mediaLoaded, ["63de4613"])
        XCTAssertTrue(fake.state.timelineShows.isEmpty)
        XCTAssertTrue(fake.state.plannedGigs.isEmpty)
    }

    func testAFailedFetchOpensNothingAndSaysNothing() async {
        var opened = 0

        controller().openGig("63de4613") { opened += 1 }
        await settle()

        XCTAssertEqual(opened, 0)
        XCTAssertNil(fake.state.selectedSetlist)
        XCTAssertNil(fake.state.error)
        XCTAssertTrue(fake.failures.isEmpty)
    }

    func testAWovenPlaceLinkZoomsOutAndAsksTheTimelineToFindTheGig() {
        fake.state.friends = [Friend(setlistfm: "alice")]
        var opened = 0

        controller().openPlace("3bd6b4f0", as: .woven) { opened += 1 }

        XCTAssertEqual(opened, 0)
        XCTAssertTrue(fake.state.zoomedOut)
        XCTAssertEqual(fake.state.linkedGig, "3bd6b4f0")
        XCTAssertEqual(fake.state.linkedGigAs, .woven)
    }

    func testASetlistPlaceLinkOpensTheGig() {
        fake.state.timelineShows = [show("3bd6b4f0")]
        var opened = 0

        controller().openPlace("3bd6b4f0", as: .setlist) { opened += 1 }

        XCTAssertEqual(opened, 1)
        XCTAssertNil(fake.state.linkedGig)
    }

    func testHidingALineStampsItAndShowingItClearsIt() {
        let navigation = controller()

        navigation.toggleLineHidden("alice")
        XCTAssertNotNil(fake.state.hiddenAt["alice"])

        navigation.toggleLineHidden("alice")
        XCTAssertNil(fake.state.hiddenAt["alice"])
    }

    func testShowingAContactsLineAgainAsksForItsLatest() {
        let alice = Friend(setlistfm: "alice")
        fake.state.friends = [alice]
        let navigation = controller()

        navigation.toggleLineHidden(alice.laneKey)
        XCTAssertTrue(refreshed.isEmpty)

        navigation.toggleLineHidden(alice.laneKey)
        XCTAssertEqual(refreshed, [alice])
    }

    func testTheContactLightAlwaysComesOnFaithful() {
        let navigation = controller()
        navigation.setShowWithheld(true)

        navigation.toggleContactLight()

        XCTAssertTrue(fake.state.contactLight)
        XCTAssertFalse(fake.state.showWithheld)
    }

    func testAnAddGigLinkTurnsTheIsoDateRoundForTheForm() {
        controller().openAddGig(artist: "Kvelertak", venue: nil, date: "2025-06-25")

        XCTAssertEqual(fake.state.addGigLink, AddGigLink(artist: "Kvelertak", venue: "", date: "25-06-2025"))
    }

    func testFestivalsFoundBehindAreKnownWhenTheNightsAheadAreAsked() async {
        let oya = Festivals(
            byId: ["oya": StoredFestival(id: "oya", name: "\u{00D8}ya")],
            idByShow: ["a1": "oya"]
        )
        fake.state.timelineShows = [show("a1")]
        var askedAheadKnowing: Festivals?
        identify = { mine, known in
            if mine.map(\.id) == ["a1"] { return oya }
            askedAheadKnowing = known
            return known
        }

        controller().resolveFestivals()
        await settle()

        XCTAssertEqual(askedAheadKnowing, oya)
        XCTAssertEqual(fake.state.festivals, oya)
    }
}
