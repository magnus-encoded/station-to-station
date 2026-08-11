package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.data.decodeFriends
import io.github.magnusencoded.stationtostation.data.encodeFriends
import io.github.magnusencoded.stationtostation.data.sfmStamp
import io.github.magnusencoded.stationtostation.data.sfmUserFromDescription
import io.github.magnusencoded.stationtostation.data.spotifyPlaylistId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FriendsLogicTest {

    @Test fun stampRoundTrips() {
        val desc = "Setlist at Oslo on 2024-06-01. Created from setlist.fm: https://x ${sfmStamp("magnus90")}"
        assertEquals("magnus90", sfmUserFromDescription(desc))
    }

    @Test fun fromSetlistFmTextDoesNotFalseMatch() {
        // The human-readable "from setlist.fm" must not be mistaken for the stamp.
        assertNull(sfmUserFromDescription("Created from setlist.fm: https://www.setlist.fm/x"))
        assertNull(sfmUserFromDescription("no stamp here"))
        assertNull(sfmUserFromDescription(null))
    }

    @Test fun playlistIdFromLinkAndUri() {
        assertEquals("37i9dQZF1DX", spotifyPlaylistId("https://open.spotify.com/playlist/37i9dQZF1DX?si=abc"))
        assertEquals("37i9dQZF1DX", spotifyPlaylistId("spotify:playlist:37i9dQZF1DX"))
        assertEquals("AbC0123", spotifyPlaylistId("  https://open.spotify.com/playlist/AbC0123  "))
        assertNull(spotifyPlaylistId("not a link"))
    }

    @Test fun friendsEncodeDecodeRoundTrip() {
        val friends = listOf(
            Friend(setlistfm = "magnus90", name = "Magnus", spotifyId = "dizziness"),
            Friend(setlistfm = "alice"),
        )
        assertEquals(friends, decodeFriends(encodeFriends(friends)))
        assertEquals(emptyList<Friend>(), decodeFriends(null))
        assertEquals(emptyList<Friend>(), decodeFriends("garbage-not-json"))
    }
}
