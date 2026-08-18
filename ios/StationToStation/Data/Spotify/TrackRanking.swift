import Foundation

/// Puts Spotify's search results for one setlist song into the order a person would
/// have picked them in.
///
/// The conversion took whatever Spotify ranked first. Spotify ranks by popularity and
/// text relevance; it does not know we are rebuilding a night and want the plain studio
/// recording by *this* band. So a search for a song the band played can lead with a
/// karaoke rendition, a tribute act, or a live cut from some other tour — and that is
/// what silently lands in the playlist.
///
/// The Swift twin of Android's `data/spotify/TrackRanking.kt`, ported with its fixtures
/// rather than re-derived from the description, for the same reason `RankTitles.swift`
/// was: two implementations that can disagree eventually will.

/// Markers that mean "not the recording they played" — demoted hard.
private let hardNoise = ["live", "karaoke", "tribute", "made famous by", "in the style of", "instrumental"]

/// Markers that mean "a different cut of the right recording" — demoted, not buried.
private let softNoise = ["remaster", "re-recorded", "rerecorded", "demo", "edit", "mix", "version", "acoustic", "mono"]

private func norm(_ s: String) -> String {
    let lowered = s.lowercased()
    let filtered = String(lowered.map { $0.isLetter || $0.isNumber || $0 == " " ? $0 : " " })
    return filtered
        .replacingOccurrences(of: " +", with: " ", options: .regularExpression)
        .trimmingCharacters(in: .whitespaces)
}

/// The title with any trailing qualifier dropped — `Enter Sandman - Remastered 2021`
/// and `Enter Sandman (Live)` both reduce to `enter sandman`, so the same song in a
/// different dress still reads as the same song.
private func core(_ title: String) -> String {
    let noDash = title.components(separatedBy: " - ").first ?? title
    let noParen = noDash.components(separatedBy: " (").first ?? noDash
    let noBracket = noParen.components(separatedBy: " [").first ?? noParen
    return norm(noBracket)
}

/// Whether this really is the band we mean. Substring either way so "The Warning"
/// matches "Warning", but only for names long enough that the overlap means something —
/// a two-letter artist would otherwise match half of Spotify.
private func artistMatches(_ track: SpotifyTrack, _ artist: String) -> Bool {
    let want = norm(artist)
    if want.isEmpty { return true }
    return track.artists.contains { credited in
        let got = norm(credited.name)
        if got == want { return true }
        if got.count >= 3 && want.contains(got) { return true }
        if want.count >= 3 && got.contains(want) { return true }
        return false
    }
}

/// How well `track` answers "the song `songName` as played by `artist`". Higher is
/// better; the number has no meaning on its own, only against its siblings.
func scoreCandidate(_ track: SpotifyTrack, _ songName: String, _ artist: String) -> Int {
    let wantTitle = norm(songName)
    let wantCore = core(songName)
    let gotCore = core(track.name)
    var score = 0

    // The single strongest signal. A perfect title by the wrong act is the karaoke
    // and tribute case, and it should never win.
    score += artistMatches(track, artist) ? 40 : -60

    let gotTitle = norm(track.name)
    if gotTitle == wantTitle {
        score += 30
    } else if gotCore == wantCore {
        score += 20
    } else if !wantCore.isEmpty && gotCore.contains(wantCore) {
        score += 8
    } else {
        score -= 15
    }

    // A marker only counts against a candidate when the setlist did not ask for it:
    // "Live and Let Die" is the song's name, not a live recording of something else.
    // Album included because "Live at Wembley" says more than the track title does.
    let haystack = norm(track.name + " " + (track.album?.name ?? ""))
    for marker in hardNoise where haystack.contains(marker) && !wantTitle.contains(marker) {
        score -= 25
    }
    for marker in softNoise where haystack.contains(marker) && !wantTitle.contains(marker) {
        score -= 6
    }

    return score
}

/// `candidates` best-first. Sorting is **stable**, so candidates we have no reason to
/// separate keep the order Spotify gave them — this only ever overrules Spotify where
/// it has an actual reason to.
func rankCandidates(_ candidates: [SpotifyTrack], _ songName: String, _ artist: String) -> [SpotifyTrack] {
    candidates.enumerated()
        .sorted { a, b in
            let sa = scoreCandidate(a.element, songName, artist)
            let sb = scoreCandidate(b.element, songName, artist)
            return sa != sb ? sa > sb : a.offset < b.offset
        }
        .map(\.element)
}
