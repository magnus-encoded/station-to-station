package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.data.decodeFriends
import io.github.magnusencoded.stationtostation.data.encodeFriends
import io.github.magnusencoded.stationtostation.data.keyFingerprint
import io.github.magnusencoded.stationtostation.data.laneKey
import io.github.magnusencoded.stationtostation.data.nameOf
import io.github.magnusencoded.stationtostation.data.withFriend
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

    private val dio = Friend(setlistfm = "", name = "Dio", publicKey = "k-dio")

    /** User story 34: everything held before this is found where it was left. */
    @Test fun aContactWithAUsernameIsStillFiledUnderIt() {
        assertEquals("magnus90", Friend(setlistfm = "magnus90", publicKey = "k").laneKey)
        assertEquals("alice", Friend(setlistfm = "alice").laneKey)
    }

    @Test fun anAccountlessContactIsFiledUnderItsKeyAndStoresAndReadsBack() {
        assertEquals("key:" + keyFingerprint("k-dio"), dio.laneKey)
        assertEquals(listOf(dio), decodeFriends(encodeFriends(listOf(dio))))
    }

    @Test fun twoContactsWithoutAnAccountAreTwoLanes() {
        val other = dio.copy(publicKey = "k-other")
        val both = withFriend(withFriend(emptyList(), dio), other)
        assertEquals(2, both.size)
        assertEquals(2, both.map { it.laneKey }.toSet().size)
    }

    @Test fun writingAThinnerCardKeepsTheKeyAndTheUsernameHeld() {
        val held = listOf(Friend(setlistfm = "ozzy", name = "Ozzy", publicKey = "k-ozzy"))
        // A link: no key.
        assertEquals("k-ozzy", withFriend(held, Friend(setlistfm = "ozzy", name = "Oz")).single().publicKey)
        // The radio, from a phone that stopped saying its username: same key, no username.
        val written = withFriend(held, Friend(setlistfm = "", name = "Ozzy O", publicKey = "k-ozzy")).single()
        assertEquals("ozzy", written.setlistfm)
        assertEquals("Ozzy O", written.name)
    }

    /** The most recently added person's Lane is Lane 1, so a write moves them there. */
    @Test fun aWrittenContactGoesLast() {
        val held = listOf(dio, Friend(setlistfm = "alice"))
        assertEquals(listOf("alice", "Dio"), withFriend(held, dio.copy(name = "Dio")).map { it.name })
    }

    @Test fun mediaIsAttributedByKeyAndABlankSenderIsNobody() {
        val friends = listOf(dio, Friend(setlistfm = "alice", name = "Alice"))
        assertEquals("Dio", friends.nameOf("k-dio"))
        assertEquals("Alice", friends.nameOf("alice"))
        assertNull(friends.nameOf(""))
    }
}
