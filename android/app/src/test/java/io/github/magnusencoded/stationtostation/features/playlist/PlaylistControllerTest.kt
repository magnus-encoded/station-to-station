package io.github.magnusencoded.stationtostation.features.playlist

import io.github.magnusencoded.stationtostation.SongMatch
import io.github.magnusencoded.stationtostation.UiState
import io.github.magnusencoded.stationtostation.data.StoredPlaylist
import io.github.magnusencoded.stationtostation.data.TimelineStore
import io.github.magnusencoded.stationtostation.data.photos.PhotoRepository
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSong
import io.github.magnusencoded.stationtostation.data.spotify.SpotifyArtist
import io.github.magnusencoded.stationtostation.data.spotify.SpotifyClient
import io.github.magnusencoded.stationtostation.data.spotify.SpotifyTrack
import io.github.magnusencoded.stationtostation.features.StateFake
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlaylistControllerTest {

    private val fake = StateFake()

    /**
     * The methods under test touch only state, never Spotify or the gallery; those two
     * need an Android context to build, so the controller is handed uninitialised ones.
     */
    private fun <T> unbuilt(type: Class<T>): T {
        val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null) as sun.misc.Unsafe
        @Suppress("UNCHECKED_CAST")
        return unsafe.allocateInstance(type) as T
    }

    private val controller = PlaylistController(
        state = fake.state,
        update = fake.update,
        spotify = unbuilt(SpotifyClient::class.java),
        photos = unbuilt(PhotoRepository::class.java),
        timelines = TimelineStore(File.createTempFile("timelines", ".json").also { it.delete() }),
        scope = CoroutineScope(Dispatchers.Unconfined),
        fail = { error -> fake.current = fake.current.copy(error = error.message) },
        addFriend = {},
    )

    private fun match(name: String, included: Boolean = true) =
        SongMatch(song = FmSong(name = name), searchArtist = "Haken", included = included)

    private fun track(id: String) =
        SpotifyTrack(id = id, name = id, uri = "spotify:track:$id", artists = listOf(SpotifyArtist("Haken")))

    @Test
    fun toggling_a_song_flips_only_that_song() {
        fake.current = UiState(matches = listOf(match("a"), match("b")))
        controller.toggleIncluded(1)
        assertEquals(listOf(true, false), fake.current.matches.map { it.included })
    }

    @Test
    fun an_index_outside_the_matches_changes_nothing() {
        fake.current = UiState(matches = listOf(match("a")))
        controller.toggleIncluded(5)
        assertEquals(listOf(true), fake.current.matches.map { it.included })
    }

    @Test
    fun choosing_a_candidate_selects_it_and_includes_the_song() {
        fake.current = UiState(matches = listOf(match("a", included = false)))
        controller.chooseCandidate(0, track("t1"))
        assertEquals("t1", fake.current.matches[0].selected?.id)
        assertTrue(fake.current.matches[0].included)
    }

    @Test
    fun the_playlist_name_and_visibility_are_set_as_given() {
        controller.setPlaylistName("Haken 2019")
        controller.setPlaylistPublic(true)
        assertEquals("Haken 2019", fake.current.playlistName)
        assertTrue(fake.current.playlistPublic)
    }

    @Test
    fun scrubbing_to_a_frame_records_it() {
        controller.setCoverFrame(1500L)
        assertEquals(1500L, fake.current.selectedCoverFrameMs)
    }

    @Test
    fun a_cover_unchanged_keeps_the_scrubbed_frame() {
        fake.current = UiState(selectedCoverFrameMs = 900L)
        controller.setCover(null)
        assertEquals(900L, fake.current.selectedCoverFrameMs)
    }

    @Test
    fun removing_a_playlist_link_drops_only_that_link_from_the_night() {
        val keep = StoredPlaylist(url = "https://open.spotify.com/playlist/keep")
        val drop = StoredPlaylist(url = "https://open.spotify.com/playlist/drop")
        fake.current = UiState(playlistsBySetlist = mapOf("night" to listOf(keep, drop)))
        controller.removePlaylist("night", drop.url)
        assertEquals(listOf(keep), fake.current.playlistsBySetlist["night"])
    }

    @Test
    fun creating_a_playlist_with_no_song_chosen_says_so_and_does_not_start() {
        fake.current = UiState(matches = listOf(match("a")))
        controller.createPlaylist()
        assertEquals("No songs selected", fake.current.error)
        assertFalse(fake.current.creatingPlaylist)
        assertNull(fake.current.errorKind)
    }
}
