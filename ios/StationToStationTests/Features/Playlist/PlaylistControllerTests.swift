import XCTest
@testable import StationToStation

@MainActor
final class PlaylistControllerTests: XCTestCase {

    private func night(id: String = "n1", songs: [FmSong]) -> FmSetlist {
        FmSetlist(id: id, eventDate: "24-11-2019", artist: FmArtist(name: "Band"),
                  venue: FmVenue(name: "Sentrum Scene"), sets: FmSets(set: [FmSet(song: songs)]))
    }

    private func track(_ id: String) -> SpotifyTrack {
        try! JSONDecoder().decode(SpotifyTrack.self, from: Data(#"{"id":"\#(id)","uri":"spotify:track:\#(id)"}"#.utf8))
    }

    func testOpeningANightMatchesEveryNamedSongAndLeavesTapesOut() {
        let model = AppModel()
        let show = night(songs: [
            FmSong(name: "Intro", tape: true),
            FmSong(name: "  "),
            FmSong(name: "Hit"),
            FmSong(name: "Borrowed", cover: FmArtist(name: "Elder")),
        ])

        model.selectSetlist(show)

        XCTAssertEqual(["Intro", "Hit", "Borrowed"], model.state.matches.map(\.song.name))
        XCTAssertEqual([false, true, true], model.state.matches.map(\.included))
        XCTAssertEqual(["Band", "Band", "Elder"], model.state.matches.map(\.searchArtist))
        XCTAssertTrue(model.state.matches.allSatisfy(\.loading))
        XCTAssertTrue(model.state.matching)
        XCTAssertEqual("n1", model.state.selectedSetlist?.id)
    }

    func testOpeningANightResetsThePlaylistBeingMade() {
        let model = AppModel()
        model.state.createdPlaylistUrl = "https://open.spotify.com/playlist/old"
        model.state.coverCandidateIds = ["a"]
        model.state.selectedCoverAssetId = "a"
        model.state.selectedCoverFrameMs = 500
        model.state.coverSearched = true
        model.state.coverUploadError = "no"
        let show = night(songs: [FmSong(name: "Hit")])

        model.selectSetlist(show)

        XCTAssertEqual(TimelineLogic.playlistName(for: show, mine: [], festivals: model.state.festivals),
                       model.state.playlistName)
        XCTAssertNil(model.state.createdPlaylistUrl)
        XCTAssertEqual([], model.state.coverCandidateIds)
        XCTAssertNil(model.state.selectedCoverAssetId)
        XCTAssertEqual(0, model.state.selectedCoverFrameMs)
        XCTAssertFalse(model.state.coverSearched)
        XCTAssertNil(model.state.coverUploadError)
    }

    func testOpeningANightClearsWhatTheLastNightLeftInGigAndGigMedia() {
        let model = AppModel()
        model.state.gigMediaSuggestions = ["x"]
        model.state.selectedAttendance = StoredAttendance(provenance: "attended")

        model.selectSetlist(night(songs: [FmSong(name: "Hit")]))

        XCTAssertEqual([], model.state.gigMediaSuggestions)
        XCTAssertNil(model.state.selectedAttendance)
    }

    func testANightOnMyTimelineOpensAsMine() {
        let model = AppModel()
        let show = night(songs: [FmSong(name: "Hit")])
        model.state.timelineShows = [show]

        model.selectSetlist(show)

        XCTAssertTrue(model.state.selectedIsMine)
    }

    func testSomeoneElsesNightOpensReadOnly() {
        let model = AppModel()
        model.state.selectedIsMine = true

        model.selectSetlist(night(songs: [FmSong(name: "Hit")]))

        XCTAssertFalse(model.state.selectedIsMine)
    }

    func testChoosingACandidateIncludesTheSong() {
        let model = AppModel()
        model.state.matches = [SongMatch(song: FmSong(name: "Hit"), searchArtist: "Band", included: false)]

        model.chooseCandidate(0, track("t1"))

        XCTAssertEqual("t1", model.state.matches[0].selected?.id)
        XCTAssertTrue(model.state.matches[0].included)
    }

    func testTogglingASongFlipsOnlyThatSong() {
        let model = AppModel()
        model.state.matches = [SongMatch(song: FmSong(name: "A"), searchArtist: "Band"),
                               SongMatch(song: FmSong(name: "B"), searchArtist: "Band")]

        model.toggleIncluded(1)
        model.toggleIncluded(7)

        XCTAssertEqual([true, false], model.state.matches.map(\.included))
    }

    func testABlankReSearchChangesNothing() {
        let model = AppModel()
        model.state.matches = [SongMatch(song: FmSong(name: "Hit"), searchArtist: "Band", loading: false)]

        model.researchSong(0, "   ")

        XCTAssertFalse(model.state.matches[0].loading)
    }

    func testAReSearchShowsTheSongLoadingAndClearsItsError() {
        let model = AppModel()
        model.state.matches = [SongMatch(song: FmSong(name: "Hit"), searchArtist: "Band",
                                         loading: false, error: "No results")]

        model.researchSong(0, "hit band")

        XCTAssertTrue(model.state.matches[0].loading)
        XCTAssertNil(model.state.matches[0].error)
    }

    func testAPlaylistWithNoSongsChosenIsRefused() {
        let model = AppModel()
        model.state.matches = [SongMatch(song: FmSong(name: "Hit"), searchArtist: "Band", included: false)]

        model.createPlaylist()

        XCTAssertEqual("No songs selected", model.state.error)
        XCTAssertFalse(model.state.creatingPlaylist)
    }

    func testRemovingAPlaylistKeepsTheNightsOtherPlaylists() {
        let model = AppModel()
        model.state.playlistsBySetlist["n1"] = [StoredPlaylist(url: "u1"), StoredPlaylist(url: "u2")]

        model.removePlaylist("n1", url: "u1")

        XCTAssertEqual(["u2"], model.state.playlistsBySetlist["n1"]?.map(\.url))
    }

    func testPickingAnotherCoverStartsAtItsFirstFrame() {
        let model = AppModel()
        model.state.selectedCoverAssetId = "a"
        model.state.selectedCoverFrameMs = 900

        model.setCover("a")
        XCTAssertEqual(900, model.state.selectedCoverFrameMs)

        model.setCover("b")
        XCTAssertEqual("b", model.state.selectedCoverAssetId)
        XCTAssertEqual(0, model.state.selectedCoverFrameMs)
    }

    func testAPlaylistLinkThatIsNoPlaylistIsRefused() {
        let model = AppModel()

        model.discoverFriendFromPlaylist("https://example.com/nothing")

        XCTAssertEqual("That doesn't look like a Spotify playlist link.", model.state.error)
    }
}
