import XCTest
@testable import StationToStation

/// What lands in the playlist when Spotify's own ranking is wrong for our purpose.
/// Every case here is a recording that Spotify may legitimately rank first and that
/// a person rebuilding a setlist would never have chosen.
///
/// Ported case-for-case from Android's `TrackRankingTest.kt`, per #178: the two
/// implementations disagreeing would mean a different first pick on each phone.
final class TrackRankingTests: XCTestCase {

    private func track(_ name: String, artist: String = "The Warning", album: String = "Error") -> SpotifyTrack {
        SpotifyTrack(
            id: name + artist,
            name: name,
            uri: "spotify:track:" + (name + artist).filter { $0.isLetter || $0.isNumber },
            artists: [SpotifyArtist(name: artist)],
            album: SpotifyAlbum(name: album)
        )
    }

    private func best(_ candidates: [SpotifyTrack], _ song: String, artist: String = "The Warning") -> SpotifyTrack {
        rankCandidates(candidates, song, artist).first!
    }

    func testTheStudioCutBeatsTheLiveOne() {
        let live = track("Choke - Live at Lollapalooza", album: "Live at Lollapalooza 2023")
        let studio = track("Choke")
        XCTAssertEqual(studio.uri, best([live, studio], "Choke").uri)
    }

    func testASongWhoseOwnNameContainsLiveIsNotTreatedAsALiveRecording() {
        // The trap: penalising "live" blindly demotes the only correct answer.
        let studio = track("Live and Let Die", artist: "Wings", album: "Band on the Run")
        let actuallyLive = track("Live and Let Die - Live", artist: "Wings", album: "Wings Over America")
        XCTAssertEqual(studio.uri, best([actuallyLive, studio], "Live and Let Die", artist: "Wings").uri)
    }

    func testKaraokeAndTributeActsLoseToTheBandThemselves() {
        let karaoke = track("Choke", artist: "The Karaoke Channel", album: "Sing Rock Hits")
        let tribute = track("Choke", artist: "Rock Tribute Players", album: "Made Famous By The Warning")
        let real = track("Choke")
        // Spotify returned them first; the band's own recording still wins.
        XCTAssertEqual(real.uri, best([karaoke, tribute, real], "Choke").uri)
    }

    func testAnExactTitleByTheWrongArtistLosesToALooserTitleByTheRightOne() {
        let wrongArtist = track("Money", artist: "Pink Floyd", album: "The Dark Side of the Moon")
        let rightArtist = track("Money (Bonus Track)")
        XCTAssertEqual(rightArtist.uri, best([wrongArtist, rightArtist], "Money").uri)
    }

    func testARemasterIsDemotedButStillBeatsALiveTake() {
        let remaster = track("Evolve - Remastered 2025")
        let live = track("Evolve - Live in Oslo", album: "Live in Oslo")
        XCTAssertEqual(remaster.uri, best([live, remaster], "Evolve").uri)
    }

    func testThePlainCutStillBeatsItsOwnRemaster() {
        let remaster = track("Evolve - Remastered 2025")
        let plain = track("Evolve")
        XCTAssertEqual(plain.uri, best([remaster, plain], "Evolve").uri)
    }

    func testCandidatesWeHaveNoReasonToSeparateKeepSpotifysOrder() {
        // Stability matters: this only overrules Spotify where it has a reason to.
        let first = track("Choke", album: "Error")
        let second = track("Choke", album: "Error Deluxe")
        let ranked = rankCandidates([first, second], "Choke", "The Warning")
        XCTAssertEqual([first.uri, second.uri], ranked.map(\.uri))
    }

    func testAnUnrelatedSongDoesNotOutrankTheOneAskedFor() {
        let unrelated = track("Disciple")
        let wanted = track("Choke")
        XCTAssertEqual(wanted.uri, best([unrelated, wanted], "Choke").uri)
    }

    func testAnEmptyResultSetRanksToNothingRatherThanThrowing() {
        XCTAssertTrue(rankCandidates([], "Choke", "The Warning").isEmpty)
    }
}
