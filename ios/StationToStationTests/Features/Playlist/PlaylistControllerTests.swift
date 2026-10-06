import XCTest
@testable import StationToStation

@MainActor
final class PlaylistControllerTests: XCTestCase {

    private final class Calls {
        var gigMedia: [String] = []
        var gigLog: [String] = []
        var friends: [Friend] = []
    }

    /// The controller holds its host `unowned`, and the Spotify and store tasks a test
    /// starts outlive the test, so every host lives as long as the test process.
    private static var hosts: [FakeState] = []

    private func rig() -> (FakeState, PlaylistController, Calls) {
        let host = FakeState()
        Self.hosts.append(host)
        let calls = Calls()
        let playlist = PlaylistController(
            host: host,
            spotify: SpotifyClient(Settings()),
            timelines: TimelineStore(file: FileManager.default.temporaryDirectory
                .appendingPathComponent("playlist-\(UUID().uuidString).json")),
            loadGigMedia: { calls.gigMedia.append($0.id) },
            loadGigLog: { calls.gigLog.append($0.id) },
            addFriend: { calls.friends.append($0) }
        )
        return (host, playlist, calls)
    }

    private func night(id: String = "n1", songs: [FmSong]) -> FmSetlist {
        FmSetlist(id: id, eventDate: "24-11-2019", artist: FmArtist(name: "Band"),
                  venue: FmVenue(name: "Sentrum Scene"), sets: FmSets(set: [FmSet(song: songs)]))
    }

    private func track(_ id: String) -> SpotifyTrack {
        try! JSONDecoder().decode(SpotifyTrack.self, from: Data(#"{"id":"\#(id)","uri":"spotify:track:\#(id)"}"#.utf8))
    }

    func testOpeningANightMatchesEveryNamedSongAndLeavesTapesOut() {
        let (host, playlist, _) = rig()
        let show = night(songs: [
            FmSong(name: "Intro", tape: true),
            FmSong(name: "  "),
            FmSong(name: "Hit"),
            FmSong(name: "Borrowed", cover: FmArtist(name: "Elder")),
        ])

        playlist.selectSetlist(show)

        XCTAssertEqual(["Intro", "Hit", "Borrowed"], host.state.matches.map(\.song.name))
        XCTAssertEqual([false, true, true], host.state.matches.map(\.included))
        XCTAssertEqual(["Band", "Band", "Elder"], host.state.matches.map(\.searchArtist))
        XCTAssertTrue(host.state.matches.allSatisfy(\.loading))
        XCTAssertTrue(host.state.matching)
        XCTAssertEqual("n1", host.state.selectedSetlist?.id)
    }

    func testOpeningANightResetsThePlaylistBeingMade() {
        let (host, playlist, _) = rig()
        host.state.createdPlaylistUrl = "https://open.spotify.com/playlist/old"
        host.state.coverCandidateIds = ["a"]
        host.state.selectedCoverAssetId = "a"
        host.state.selectedCoverFrameMs = 500
        host.state.coverSearched = true
        host.state.coverUploadError = "no"
        let show = night(songs: [FmSong(name: "Hit")])

        playlist.selectSetlist(show)

        XCTAssertEqual(TimelineLogic.playlistName(for: show, mine: [], festivals: host.state.festivals),
                       host.state.playlistName)
        XCTAssertNil(host.state.createdPlaylistUrl)
        XCTAssertEqual([], host.state.coverCandidateIds)
        XCTAssertNil(host.state.selectedCoverAssetId)
        XCTAssertEqual(0, host.state.selectedCoverFrameMs)
        XCTAssertFalse(host.state.coverSearched)
        XCTAssertNil(host.state.coverUploadError)
    }

    func testOpeningANightAsksGigAndGigMediaToLoadIt() {
        let (_, playlist, calls) = rig()
        playlist.selectSetlist(night(songs: [FmSong(name: "Hit")]))

        XCTAssertEqual(["n1"], calls.gigMedia)
        XCTAssertEqual(["n1"], calls.gigLog)
    }

    func testANightOnMyTimelineOpensAsMine() {
        let (host, playlist, _) = rig()
        let show = night(songs: [FmSong(name: "Hit")])
        host.state.timelineShows = [show]

        playlist.selectSetlist(show)

        XCTAssertTrue(host.state.selectedIsMine)
    }

    func testSomeoneElsesNightOpensReadOnly() {
        let (host, playlist, _) = rig()
        host.state.selectedIsMine = true

        playlist.selectSetlist(night(songs: [FmSong(name: "Hit")]))

        XCTAssertFalse(host.state.selectedIsMine)
    }

    func testChoosingACandidateIncludesTheSong() {
        let (host, playlist, _) = rig()
        host.state.matches = [SongMatch(song: FmSong(name: "Hit"), searchArtist: "Band", included: false)]

        playlist.chooseCandidate(0, track("t1"))

        XCTAssertEqual("t1", host.state.matches[0].selected?.id)
        XCTAssertTrue(host.state.matches[0].included)
    }

    func testTogglingASongFlipsOnlyThatSong() {
        let (host, playlist, _) = rig()
        host.state.matches = [SongMatch(song: FmSong(name: "A"), searchArtist: "Band"),
                               SongMatch(song: FmSong(name: "B"), searchArtist: "Band")]

        playlist.toggleIncluded(1)
        playlist.toggleIncluded(7)

        XCTAssertEqual([true, false], host.state.matches.map(\.included))
    }

    func testABlankReSearchChangesNothing() {
        let (host, playlist, _) = rig()
        host.state.matches = [SongMatch(song: FmSong(name: "Hit"), searchArtist: "Band", loading: false)]

        playlist.researchSong(0, "   ")

        XCTAssertFalse(host.state.matches[0].loading)
    }

    func testAReSearchShowsTheSongLoadingAndClearsItsError() {
        let (host, playlist, _) = rig()
        host.state.matches = [SongMatch(song: FmSong(name: "Hit"), searchArtist: "Band",
                                         loading: false, error: "No results")]

        playlist.researchSong(0, "hit band")

        XCTAssertTrue(host.state.matches[0].loading)
        XCTAssertNil(host.state.matches[0].error)
    }

    func testAPlaylistWithNoSongsChosenIsRefused() {
        let (host, playlist, _) = rig()
        host.state.matches = [SongMatch(song: FmSong(name: "Hit"), searchArtist: "Band", included: false)]

        playlist.createPlaylist()

        XCTAssertEqual("No songs selected", host.state.error)
        XCTAssertFalse(host.state.creatingPlaylist)
    }

    func testRemovingAPlaylistKeepsTheNightsOtherPlaylists() {
        let (host, playlist, _) = rig()
        host.state.playlistsBySetlist["n1"] = [StoredPlaylist(url: "u1"), StoredPlaylist(url: "u2")]

        playlist.removePlaylist("n1", url: "u1")

        XCTAssertEqual(["u2"], host.state.playlistsBySetlist["n1"]?.map(\.url))
    }

    func testPickingAnotherCoverStartsAtItsFirstFrame() {
        let (host, playlist, _) = rig()
        host.state.selectedCoverAssetId = "a"
        host.state.selectedCoverFrameMs = 900

        playlist.setCover("a")
        XCTAssertEqual(900, host.state.selectedCoverFrameMs)

        playlist.setCover("b")
        XCTAssertEqual("b", host.state.selectedCoverAssetId)
        XCTAssertEqual(0, host.state.selectedCoverFrameMs)
    }

    func testAPlaylistLinkThatIsNoPlaylistIsRefused() {
        let (host, playlist, _) = rig()

        playlist.discoverFriendFromPlaylist("https://example.com/nothing")

        XCTAssertEqual("That doesn't look like a Spotify playlist link.", host.state.error)
    }
}
