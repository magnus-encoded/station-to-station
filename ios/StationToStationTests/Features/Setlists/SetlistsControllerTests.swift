import XCTest
@testable import StationToStation

@MainActor
final class SetlistsControllerTests: XCTestCase {

    private var savedUser: String?

    override func setUp() {
        super.setUp()
        savedUser = Settings().mySetlistFmUser
    }

    override func tearDown() {
        Settings().saveMySetlistFmUser(savedUser ?? "")
        super.tearDown()
    }

    private let fmNight = FmSetlist(id: "fm1", eventDate: "01-06-2024",
                                    artist: FmArtist(mbid: "mb1", name: "Someone"),
                                    url: "https://www.setlist.fm/fm1")


    private func controller(
        _ host: FakeState,
        status: Int = 200,
        body: String = #"{"total":0,"setlist":[]}"#
    ) -> SetlistsController {
        let client = SetlistFmClient(
            keySource: { SetlistFmKey(key: "test-key", shared: false) },
            sharedQuotaSpentAt: { nil },
            recordSharedQuotaSpent: { _ in },
            now: { 1_000_000 },
            transport: { _, _ in
                SetlistFmResponse(status: status,
                                  body: (200...299).contains(status) ? Data(body.utf8) : Data())
            },
            sleep: { _ in }
        )
        let file = FileManager.default.temporaryDirectory
            .appendingPathComponent("setlists-\(UUID().uuidString).json")
        let store = TimelineStore(file: file)
        let settings = Settings()
        return SetlistsController(
            host: host,
            setlistFm: client,
            musicBrainz: MusicBrainzClient(),
            timelines: store,
            settings: settings,
            settingsController: SettingsController(host: host, settings: settings, spotify: SpotifyClient(settings)),
            gig: GigController(host: host, timelines: store, setlistFm: client, location: DeviceLocation(),
                               gossip: GossipController(host: host, timelines: store))
        )
    }

    private func settle(_ done: @escaping () -> Bool) async {
        for _ in 0..<200 where !done() {
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
    }

    func testABlankArtistQuerySearchesNothing() {
        let host = FakeState()
        let setlists = controller(host)
        setlists.setArtistQuery("   ")
        setlists.searchArtists()
        XCTAssertFalse(host.state.searchLoading)
    }

    func testAFailedArtistSearchIsReported() async {
        let host = FakeState()
        let setlists = controller(host, status: 500)
        setlists.setArtistQuery("Someone")
        setlists.searchArtists()
        XCTAssertTrue(host.state.searchLoading)
        await settle { !host.failures.isEmpty }
        XCTAssertEqual(1, host.failures.count)
    }

    func testOpeningAnArtistListsTheirNights() async {
        let host = FakeState()
        let setlists = controller(host, body: #"{"total":3,"setlist":[{"id":"a"},{"id":"b"}]}"#)
        setlists.openArtist(FmArtist(mbid: "mb1", name: "Someone"))
        XCTAssertEqual("Someone", host.state.setlistsTitle)
        XCTAssertEqual(.artist, host.state.source)
        XCTAssertTrue(host.state.setlistsLoading)
        await settle { !host.state.setlistsLoading }
        XCTAssertEqual(["a", "b"], host.state.setlists.map(\.id))
        XCTAssertEqual(3, host.state.setlistsTotal)
    }

    func testABlankUserQueryOpensNoList() {
        let host = FakeState()
        host.state.setlistsTitle = "before"
        let setlists = controller(host)
        setlists.setUserQuery("  ")
        setlists.openUserAttended()
        XCTAssertEqual("before", host.state.setlistsTitle)
        XCTAssertFalse(host.state.setlistsLoading)
    }

    func testMyConcertsAdoptsTheUsernameWhenNoneIsSet() async {
        let host = FakeState()
        let setlists = controller(host, body: #"{"total":1,"setlist":[{"id":"a"}]}"#)
        setlists.setUserQuery(" me ")
        setlists.openUserAttended()
        XCTAssertEqual("me", host.state.mySetlistFmUser)
        XCTAssertEqual("Attended by me", host.state.setlistsTitle)
        XCTAssertEqual(.user, host.state.source)
        await settle { !host.state.setlistsLoading }
        XCTAssertEqual(["a"], host.state.setlists.map(\.id))
    }

    func testMyConcertsNeverReplacesAChosenUsername() {
        var state = UiState()
        state.mySetlistFmUser = "chosen"
        let host = FakeState(state)
        let setlists = controller(host)
        setlists.setUserQuery("someone")
        setlists.openUserAttended()
        XCTAssertEqual("chosen", host.state.mySetlistFmUser)
    }

    func testTheNextPageIsAppended() async {
        let host = FakeState()
        host.state.source = .artist
        host.state.setlists = [fmNight]
        host.state.setlistsTotal = 2
        let setlists = controller(host, body: #"{"total":2,"setlist":[{"id":"fm2"}]}"#)
        setlists.loadMoreSetlists()
        await settle { !host.state.setlistsLoading }
        XCTAssertEqual(["fm1", "fm2"], host.state.setlists.map(\.id))
        XCTAssertEqual(2, host.state.setlistsPage)
    }

    func testNoMoreIsLoadedWhileAPageIsLoading() {
        let host = FakeState()
        host.state.setlists = [fmNight]
        host.state.setlistsTotal = 5
        host.state.setlistsLoading = true
        controller(host).loadMoreSetlists()
        XCTAssertEqual(1, host.state.setlistsPage)
    }

    func testNoMoreIsLoadedOnceTheWholeListIsHere() {
        let host = FakeState()
        host.state.setlists = [fmNight]
        host.state.setlistsTotal = 1
        controller(host).loadMoreSetlists()
        XCTAssertFalse(host.state.setlistsLoading)
        XCTAssertEqual(1, host.state.setlistsPage)
    }

    func testAPullWithNoGigOpenAsksNothing() async {
        let host = FakeState()
        let setlists = controller(host)
        await setlists.pullCurtain(.catalogue)
        await setlists.pullCurtain(.checkEvent)
        XCTAssertNil(host.state.catalogueFetching)
        XCTAssertNil(host.state.selectedSetlist)
    }

    func testACatalogueAlreadyHeldIsNotAskedForAgain() async {
        let host = FakeState()
        host.state.selectedSetlist = fmNight
        host.state.catalogueByArtist["mb1"] = ["Song"]
        await controller(host).pullCurtain(.catalogue)
        XCTAssertEqual(["Song"], host.state.catalogueByArtist["mb1"])
        XCTAssertNil(host.state.catalogueFetching)
    }

    func testAnArtistWithoutAnMbidHasNoCatalogueToAskFor() async {
        let host = FakeState()
        host.state.selectedSetlist = FmSetlist(id: "fm2", artist: FmArtist(name: "Nobody"),
                                               url: "https://www.setlist.fm/fm2")
        await controller(host).pullCurtain(.catalogue)
        XCTAssertTrue(host.state.catalogueByArtist.isEmpty)
        XCTAssertNil(host.state.catalogueFetching)
    }

    func testPullingASetlistFmNightTakesWhatSetlistFmSaysNow() async {
        let host = FakeState()
        host.state.selectedSetlist = fmNight
        host.state.timelineShows = [fmNight]
        let setlists = controller(host, body: #"{"id":"fm1","eventDate":"01-06-2024","info":"posted","url":"https://www.setlist.fm/fm1"}"#)
        await setlists.pullCurtain(.fetchSetlist)
        XCTAssertEqual("posted", host.state.selectedSetlist?.info)
        XCTAssertEqual("posted", host.state.timelineShows.first?.info)
    }

    func testAGigWithNoPendingMatchHasNoChipHits() async {
        let host = FakeState()
        host.state.attendanceByGig["local1"] = StoredAttendance()
        let hits = await controller(host).setlistFmChipHits(gigId: "local1")
        XCTAssertTrue(hits.isEmpty)
    }
}
