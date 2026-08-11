package io.github.magnusencoded.stationtostation.data.spotify

/**
 * Puts Spotify's search results for one setlist song into the order a person would
 * have picked them in.
 *
 * The conversion took whatever Spotify ranked first. Spotify ranks by popularity and
 * text relevance; it does not know we are rebuilding a night and want the plain studio
 * recording by *this* band. So a search for a song the band played can lead with a
 * karaoke rendition, a tribute act, or a live cut from some other tour — and that is
 * what silently lands in the playlist.
 *
 * Pure and Android-free on purpose: this is the one part of the conversion whose
 * correctness can be argued on the JVM rather than on a phone, which matters when the
 * loop is CI → adb → device.
 */

/** Markers that mean "not the recording they played" — demoted hard. */
private val HARD_NOISE = listOf(
    "live", "karaoke", "tribute", "made famous by", "in the style of", "instrumental",
)

/** Markers that mean "a different cut of the right recording" — demoted, not buried. */
private val SOFT_NOISE = listOf(
    "remaster", "re-recorded", "rerecorded", "demo", "edit", "mix", "version", "acoustic", "mono",
)

private fun norm(s: String): String =
    s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex(" +"), " ").trim()

/**
 * The title with any trailing qualifier dropped — `Enter Sandman - Remastered 2021`
 * and `Enter Sandman (Live)` both reduce to `enter sandman`, so the same song in a
 * different dress still reads as the same song.
 */
private fun core(title: String): String =
    norm(title.substringBefore(" - ").substringBefore(" (").substringBefore(" ["))

/**
 * Whether this really is the band we mean. Substring either way so "The Warning"
 * matches "Warning", but only for names long enough that the overlap means something —
 * a two-letter artist would otherwise match half of Spotify.
 */
private fun artistMatches(track: SpotifyTrack, artist: String): Boolean {
    val want = norm(artist)
    if (want.isBlank()) return true
    return track.artists.any { credited ->
        val got = norm(credited.name)
        when {
            got == want -> true
            got.length >= 3 && want.contains(got) -> true
            want.length >= 3 && got.contains(want) -> true
            else -> false
        }
    }
}

/**
 * How well [track] answers "the song [songName] as played by [artist]". Higher is
 * better; the number has no meaning on its own, only against its siblings.
 */
internal fun scoreCandidate(track: SpotifyTrack, songName: String, artist: String): Int {
    val wantTitle = norm(songName)
    val wantCore = core(songName)
    val gotCore = core(track.name)
    var score = 0

    // The single strongest signal. A perfect title by the wrong act is the karaoke
    // and tribute case, and it should never win.
    score += if (artistMatches(track, artist)) 40 else -60

    score += when {
        norm(track.name) == wantTitle -> 30
        gotCore == wantCore -> 20
        wantCore.isNotEmpty() && gotCore.contains(wantCore) -> 8
        else -> -15
    }

    // A marker only counts against a candidate when the setlist did not ask for it:
    // "Live and Let Die" is the song's name, not a live recording of something else.
    // Album included because "Live at Wembley" says more than the track title does.
    val haystack = norm(track.name + " " + track.album?.name.orEmpty())
    HARD_NOISE.forEach { if (it in haystack && it !in wantTitle) score -= 25 }
    SOFT_NOISE.forEach { if (it in haystack && it !in wantTitle) score -= 6 }

    return score
}

/**
 * [candidates] best-first. Sorting is **stable**, so candidates we have no reason to
 * separate keep the order Spotify gave them — this only ever overrules Spotify where
 * it has an actual reason to.
 */
fun rankCandidates(
    candidates: List<SpotifyTrack>,
    songName: String,
    artist: String,
): List<SpotifyTrack> = candidates.sortedByDescending { scoreCandidate(it, songName, artist) }
