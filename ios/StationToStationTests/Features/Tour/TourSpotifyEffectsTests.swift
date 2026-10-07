import Foundation
import XCTest
@testable import StationToStation

final class TourSpotifyStub: PlaylistSpotify, SpotifyLogin {
    enum Failure: Error, Equatable { case login, search, create, add }
    var failure: Failure?
    var scopes: Bool? = true
    var logins = 0
    var searches: [String] = []
    var creations: [(name: String, description: String, isPublic: Bool)] = []
    var additions: [(id: String, uris: [String])] = []
    var covers: [String] = []
    var searchGate: (() async -> Void)?

    @MainActor func login() async throws {
        logins += 1
        if failure == .login { throw Failure.login }
    }
    func hasPlaylistScopes() -> Bool? { scopes }
    func hasImageUploadScope() -> Bool { true }
    func searchTracks(_ query: String, limit: Int) async throws -> [SpotifyTrack] {
        searches.append(query)
        if let searchGate { await searchGate() }
        if failure == .search { throw Failure.search }
        return [try JSONDecoder().decode(SpotifyTrack.self, from: Data(
            "{\"id\":\"track\(searches.count)\",\"uri\":\"spotify:track:track\(searches.count)\"}".utf8))]
    }
    func createPlaylist(name: String, description: String, isPublic: Bool) async throws -> PlaylistResponse {
        if failure == .create { throw Failure.create }
        creations.append((name, description, isPublic))
        return try JSONDecoder().decode(PlaylistResponse.self, from: Data("{\"id\":\"playlist\(creations.count)\"}".utf8))
    }
    func addTracks(_ playlistId: String, uris: [String]) async throws -> AddTracksResult {
        if failure == .add { throw Failure.add }
        additions.append((playlistId, uris))
        return AddTracksResult(added: uris.count, refused: [])
    }
    func uploadCover(_ playlistId: String, jpeg: Data) async throws { covers.append(playlistId) }
    func getPlaylist(_ playlistId: String) async throws -> SimplePlaylist { throw Failure.search }
    func currentUser() async throws -> SpotifyUser { throw Failure.search }
}

@MainActor
final class TourSpotifyEffectsTests: XCTestCase {
    private struct Online: TourConnectivity {
        func isOnline() -> Bool { true }
    }
    private var suite = ""
    private var defaults: UserDefaults!
    private var file: URL!
    private var timelines: TimelineStore!
    private var host: FakeState!
    private var settings: Settings!
    private var spotify: TourSpotifyStub!
    private var playlist: PlaylistController!
    private var character: [String: String] = [:]
    private var deleted: [String] = []
    private let demo = localGigSetlist(gigId: "demo", artist: "Chosen band", date: "01-01-2030", venue: "Room", city: "")

    override func setUp() async throws {
        suite = "TourSpotifyEffectsTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)!
        file = FileManager.default.temporaryDirectory.appendingPathComponent("\(suite).json")
        timelines = TimelineStore(file: file)
        host = FakeState()
        settings = Settings(store: defaults)
        spotify = TourSpotifyStub()
        playlist = PlaylistController(host: host, spotify: spotify, timelines: timelines,
                                      loadGigMedia: { _ in }, loadGigLog: { _ in }, addFriend: { _ in })
        character = ["S19": "Coach line from data", "playlist.title": "Title from data",
                     "playlist.description": "Achievement from data"]
        deleted = []
        host.state.spotifyConnected = true
        host.state.plannedGigs = [demo]
    }

    override func tearDown() async throws {
        defaults.removePersistentDomain(forName: suite)
        try? FileManager.default.removeItem(at: file)
    }

    private func effects() -> TourSpotifyEffects {
        TourSpotifyEffects(host: host, store: defaults, settings: settings, playlist: playlist, login: spotify,
                            characterLine: { [unowned self] in self.character[$0] ?? "" })
    }

    private func logEffects() -> TourLogEffects {
        TourLogEffects(host: host, store: defaults, setlistFm: SetlistFmClient(keySource: { nil }),
                        musicBrainz: MusicBrainzClient(), friendKey: { nil }, characterLine: { _ in "" })
    }

    private func makeTour() -> (TourController, TourSpotifyEffects, TourAddGigEffects, TourLogEffects) {
        settings.saveTourState(TourState(currentStep: .s19))
        let ending = effects()
        let addGig = TourAddGigEffects(store: defaults) { [unowned self] in self.deleted.append($0) }
        addGig.record(demo.id)
        let log = logEffects()
        log.writeLog(demo.id, log: StoredLog(songs: ["First", "", "Second", "FIRST"]), reply: false, now: 1)
        let tour = TourController(host: host, settings: settings, connectivity: Online(),
                                  demoWorld: DemoWorldRegistry(parts: [log, addGig]), addGig: addGig, log: log,
                                  spotify: ending)
        tour.start()
        return (tour, ending, addGig, log)
    }

    private func eventually(_ condition: () async -> Bool) async {
        for _ in 0..<200 {
            if await condition() { return }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTFail("The Spotify effect did not settle")
    }

    func testS19ExportsTheDemoLogThroughPlaylistWithCharacterTextPublicAndNoCover() async throws {
        host.state.selectedCoverAssetId = "a real photo"
        host.state.mySetlistFmUser = "real-user"
        let (tour, ending, _, _) = makeTour()
        XCTAssertEqual(host.state.tourCoachMark, .spotify)
        XCTAssertEqual(tour.spotifyCoachLine, character["S19"])
        XCTAssertTrue(spotify.creations.isEmpty)
        await tour.exportSpotify().value
        XCTAssertEqual(spotify.logins, 0)
        XCTAssertEqual(spotify.searches, ["track:\"First\" artist:\"Chosen band\"", "track:\"Second\" artist:\"Chosen band\""])
        XCTAssertEqual(spotify.creations.count, 1)
        XCTAssertEqual(spotify.creations.first?.name, character["playlist.title"])
        XCTAssertEqual(spotify.creations.first?.description, character["playlist.description"])
        XCTAssertEqual(spotify.creations.first?.isPublic, true)
        XCTAssertEqual(spotify.additions.first?.uris, ["spotify:track:track1", "spotify:track:track2"])
        XCTAssertTrue(spotify.covers.isEmpty)
        XCTAssertEqual(host.state.selectedCoverAssetId, "a real photo")
        XCTAssertEqual(host.state.createdPlaylistName, character["playlist.title"])
        XCTAssertEqual(host.state.createdTrackCount, 2)
        XCTAssertFalse(host.state.creatingPlaylist)
        XCTAssertEqual(tour.state.currentStep, .s20)
        XCTAssertTrue(tour.state.finished)
        XCTAssertNil(host.state.tourCoachMark)
        XCTAssertEqual(deleted, [demo.id])
        XCTAssertEqual(ending.playlists, [StoredPlaylist(url: "https://open.spotify.com/playlist/playlist1",
                                                       name: "Title from data", trackCount: 2)])
        XCTAssertEqual(effects().playlists, ending.playlists)
        let cache = await timelines.load()
        XCTAssertTrue(cache.gigs.isEmpty)
        tour.skip()
        XCTAssertEqual(tour.state.currentStep, .s20)
        XCTAssertEqual(deleted, [demo.id])
    }

    func testDecliningWithoutSpotifyEndsAndSettingsRetryWorksAfterRelaunchAndPurge() async {
        host.state.spotifyConnected = false
        let (tour, ending, addGig, _) = makeTour()
        tour.declineSpotify()
        XCTAssertEqual(tour.state.currentStep, .s20)
        XCTAssertTrue(tour.state.finished)
        XCTAssertTrue(tour.spotifyRetryPending)
        XCTAssertTrue(settings.tourState.spotifyRetryPending)
        XCTAssertTrue(ending.playlists.isEmpty)
        XCTAssertTrue(spotify.creations.isEmpty)
        XCTAssertEqual(spotify.logins, 0)
        XCTAssertTrue(addGig.demoGigIds.isEmpty)
        host.state.plannedGigs = []
        let restored = effects()
        let relaunched = TourController(host: host, settings: settings, connectivity: Online(),
                                        demoWorld: EmptyDemoWorld(), spotify: restored)
        relaunched.start()
        await relaunched.exportSpotify().value
        XCTAssertEqual(spotify.logins, 1)
        XCTAssertTrue(host.state.spotifyConnected)
        XCTAssertEqual(spotify.creations.count, 1)
        XCTAssertEqual(spotify.additions.first?.uris.count, 2)
        XCTAssertFalse(relaunched.spotifyRetryPending)
        XCTAssertFalse(settings.tourState.spotifyRetryPending)
        XCTAssertEqual(restored.playlists.count, 1)
        await relaunched.exportSpotify().value
        XCTAssertEqual(spotify.creations.count, 1)
    }

    func testLoginRefusalEndsAtS20AndLeavesTheSameSongsRetryable() async {
        host.state.spotifyConnected = false
        spotify.failure = .login
        let (tour, _, _, _) = makeTour()
        await tour.exportSpotify().value
        XCTAssertEqual(tour.state.currentStep, .s20)
        XCTAssertTrue(tour.spotifyRetryPending)
        XCTAssertFalse(host.state.spotifyConnected)
        XCTAssertTrue(spotify.searches.isEmpty)
        XCTAssertEqual(host.failures.count, 1)
        spotify.failure = nil
        await tour.exportSpotify().value
        XCTAssertFalse(tour.spotifyRetryPending)
        XCTAssertEqual(spotify.additions.first?.uris.count, 2)
    }

    func testSearchCreationAndTrackFailuresEndCleanlyAndRetryOnlyClearsOnSuccess() async {
        for failure in [TourSpotifyStub.Failure.search, .create, .add] {
            let (tour, ending, _, _) = makeTour()
            let before = ending.playlists.count
            spotify.failure = failure
            await tour.exportSpotify().value
            XCTAssertEqual(tour.state.currentStep, .s20)
            XCTAssertTrue(tour.spotifyRetryPending)
            XCTAssertFalse(host.state.creatingPlaylist)
            XCTAssertEqual(ending.playlists.count, before)
            await tour.exportSpotify().value
            XCTAssertTrue(settings.tourState.spotifyRetryPending)
            spotify.failure = nil
            await tour.exportSpotify().value
            XCTAssertFalse(settings.tourState.spotifyRetryPending)
            XCTAssertEqual(ending.playlists.count, before + 1)
            XCTAssertTrue(spotify.covers.isEmpty)
        }
    }

    func testUnknownOrMissingPlaylistScopesLeaveAWorkingRetry() async {
        let grants: [Bool?] = [nil, false]
        for scopes in grants {
            let (tour, _, _, _) = makeTour()
            spotify.scopes = scopes
            await tour.exportSpotify().value
            XCTAssertTrue(tour.state.finished)
            XCTAssertTrue(tour.spotifyRetryPending)
            spotify.scopes = true
            await tour.exportSpotify().value
            XCTAssertFalse(tour.spotifyRetryPending)
        }
    }

    func testMissingCharacterTextDeclinesWithoutInventingAPlaylist() async {
        character = [:]
        let (tour, _, _, _) = makeTour()
        await tour.exportSpotify().value
        XCTAssertTrue(tour.state.finished)
        XCTAssertTrue(tour.spotifyRetryPending)
        XCTAssertTrue(spotify.creations.isEmpty)
        XCTAssertTrue(spotify.searches.isEmpty)
        character = ["playlist.title": "A different character title", "playlist.description": "A different achievement"]
        await tour.exportSpotify().value
        XCTAssertEqual(spotify.creations.first?.name, character["playlist.title"])
        XCTAssertEqual(spotify.creations.first?.description, character["playlist.description"])
        XCTAssertFalse(tour.spotifyRetryPending)
    }

    func testReplayHasNewDemoIdsAndCreatesANewPlaylistWithoutReusingItsReceipt() async {
        let (tour, ending, addGig, log) = makeTour()
        await tour.exportSpotify().value
        let first = ending.playlists
        tour.replay()
        XCTAssertEqual(tour.state.currentStep, .s1)
        XCTAssertTrue(addGig.demoGigIds.isEmpty)
        XCTAssertFalse(tour.spotifyRetryPending)
        let next = localGigSetlist(gigId: "replay-demo", artist: "Other band", date: "02-01-2030", venue: "Room", city: "")
        host.state.plannedGigs = [next]
        tour.send(.acknowledged)
        tour.send(.curtainPulled)
        tour.send(.bandPicked)
        tour.gigAdded(next.id)
        log.writeLog(next.id, log: StoredLog(songs: ["Another song"]), reply: false, now: 2)
        let events: [TourEvent] = [.roomOpened, .swipedBack, .contactExchanged(location: TourLocation(latitude: 0, longitude: 0)),
                                .pinchedOut, .ticketImported, .calendarAdded, .mapsOpened, .ticketShown, .checkedIn,
                                .logEntryWritten, .gapRecorded, .gossipSent, .setlistFilled, .returnedFromPhotos,
                                .mediaAdded(visibility: .private)]
        for event in events { tour.send(event) }
        XCTAssertEqual(tour.state.currentStep, .s19)
        XCTAssertEqual(addGig.demoGigIds, [next.id])
        await tour.exportSpotify().value
        XCTAssertEqual(spotify.creations.count, 2)
        XCTAssertEqual(spotify.additions.map { $0.id }, ["playlist1", "playlist2"])
        XCTAssertEqual(ending.playlists.count, 2)
        XCTAssertEqual(ending.playlists.first, first.first)
        XCTAssertEqual(spotify.searches.last, "track:\"Another song\" artist:\"Other band\"")
        XCTAssertTrue(tour.state.finished)
        XCTAssertTrue(addGig.demoGigIds.isEmpty)
    }

    func testRepeatedTapsExportOnceAndAnExportSearchingAtSkipCannotFinishOrCreateAPlaylist() async {
        let (tour, ending, _, _) = makeTour()
        var release: CheckedContinuation<Void, Never>?
        spotify.searchGate = { await withCheckedContinuation { release = $0 } }
        let task = tour.exportSpotify()
        await eventually { release != nil }
        await tour.exportSpotify().value
        XCTAssertEqual(spotify.searches.count, 1)
        tour.skip()
        release?.resume()
        await task.value
        XCTAssertTrue(tour.state.finished)
        XCTAssertNil(tour.state.currentStep)
        XCTAssertFalse(tour.spotifyRetryPending)
        XCTAssertTrue(spotify.creations.isEmpty)
        XCTAssertTrue(ending.playlists.isEmpty)
        XCTAssertFalse(host.state.creatingPlaylist)
    }

    func testTheOrdinaryPickerStillAppendsItsPlaylistReceiptToTheGig() async {
        host.state.selectedSetlist = demo
        host.state.playlistName = "My playlist"
        host.state.playlistPublic = false
        host.state.matches = [SongMatch(song: FmSong(name: "First"), searchArtist: "Chosen band", loading: false,
                                       selected: try! JSONDecoder().decode(SpotifyTrack.self, from: Data(
                                        #"{"id":"one","uri":"spotify:track:one"}"#.utf8)))]
        let previous = StoredPlaylist(url: "https://open.spotify.com/playlist/previous")
        host.state.playlistsBySetlist[demo.id] = [previous]
        await timelines.save(playlists: [demo.id: previous])
        playlist.createPlaylist()
        await eventually { await self.timelines.load().playlists()[self.demo.id]?.count == 2 }
        XCTAssertEqual(spotify.creations.first?.isPublic, false)
        XCTAssertEqual(spotify.creations.first?.name, "My playlist")
        XCTAssertTrue(spotify.creations.first?.description.contains("Created from setlist.fm") == true)
        let cache = await timelines.load()
        XCTAssertEqual(host.state.playlistsBySetlist[demo.id], cache.playlists()[demo.id])
        XCTAssertEqual(cache.playlists()[demo.id]?.first, previous)
    }
}
