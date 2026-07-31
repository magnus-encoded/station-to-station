package io.github.magnusencoded.setlist2spotify

import io.github.magnusencoded.setlist2spotify.data.spotify.SpotifyAlbum
import io.github.magnusencoded.setlist2spotify.data.spotify.SpotifyArtist
import io.github.magnusencoded.setlist2spotify.data.spotify.SpotifyTrack
import io.github.magnusencoded.setlist2spotify.data.spotify.rankCandidates
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What lands in the playlist when Spotify's own ranking is wrong for our purpose.
 * Every case here is a recording that Spotify may legitimately rank first and that
 * a person rebuilding a setlist would never have chosen.
 */
class TrackRankingTest {

    private fun track(name: String, artist: String = "The Warning", album: String = "Error") =
        SpotifyTrack(
            id = name + artist,
            name = name,
            uri = "spotify:track:" + name.filter { it.isLetterOrDigit() } + artist.filter { it.isLetterOrDigit() },
            artists = listOf(SpotifyArtist(artist)),
            album = SpotifyAlbum(album),
        )

    private fun best(candidates: List<SpotifyTrack>, song: String, artist: String = "The Warning") =
        rankCandidates(candidates, song, artist).first()

    @Test
    fun `the studio cut beats the live one`() {
        val live = track("Choke - Live at Lollapalooza", album = "Live at Lollapalooza 2023")
        val studio = track("Choke")
        assertEquals(studio, best(listOf(live, studio), "Choke"))
    }

    @Test
    fun `a song whose own name contains live is not treated as a live recording`() {
        // The trap: penalising "live" blindly demotes the only correct answer.
        val studio = track("Live and Let Die", artist = "Wings", album = "Band on the Run")
        val actuallyLive = track("Live and Let Die - Live", artist = "Wings", album = "Wings Over America")
        assertEquals(
            studio,
            best(listOf(actuallyLive, studio), "Live and Let Die", artist = "Wings"),
        )
    }

    @Test
    fun `karaoke and tribute acts lose to the band themselves`() {
        val karaoke = track("Choke", artist = "The Karaoke Channel", album = "Sing Rock Hits")
        val tribute = track("Choke", artist = "Rock Tribute Players", album = "Made Famous By The Warning")
        val real = track("Choke")
        // Spotify returned them first; the band's own recording still wins.
        assertEquals(real, best(listOf(karaoke, tribute, real), "Choke"))
    }

    @Test
    fun `an exact title by the wrong artist loses to a looser title by the right one`() {
        val wrongArtist = track("Money", artist = "Pink Floyd", album = "The Dark Side of the Moon")
        val rightArtist = track("Money (Bonus Track)")
        assertEquals(rightArtist, best(listOf(wrongArtist, rightArtist), "Money"))
    }

    @Test
    fun `a remaster is demoted but still beats a live take`() {
        val remaster = track("Evolve - Remastered 2025")
        val live = track("Evolve - Live in Oslo", album = "Live in Oslo")
        assertEquals(remaster, best(listOf(live, remaster), "Evolve"))
    }

    @Test
    fun `the plain cut still beats its own remaster`() {
        val remaster = track("Evolve - Remastered 2025")
        val plain = track("Evolve")
        assertEquals(plain, best(listOf(remaster, plain), "Evolve"))
    }

    @Test
    fun `candidates we have no reason to separate keep Spotify's order`() {
        // Stability matters: this only overrules Spotify where it has a reason to.
        val first = track("Choke", album = "Error")
        val second = track("Choke", album = "Error Deluxe")
        assertEquals(
            listOf(first, second),
            rankCandidates(listOf(first, second), "Choke", "The Warning"),
        )
    }

    @Test
    fun `an unrelated song does not outrank the one asked for`() {
        val unrelated = track("Disciple")
        val wanted = track("Choke")
        assertEquals(wanted, best(listOf(unrelated, wanted), "Choke"))
    }

    @Test
    fun `an empty result set ranks to nothing rather than throwing`() {
        assertEquals(emptyList<SpotifyTrack>(), rankCandidates(emptyList(), "Choke", "The Warning"))
    }
}
