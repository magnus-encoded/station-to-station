package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.data.friendFromQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The `station-to-station://friend?u=…&name=…&sid=…` card round trip. android.net.Uri
 * can't be built or parsed in a plain JVM unit test (there's no Robolectric here, same
 * reason [GigLinkTest] tests [parseGigLink] rather than the Uri handler it feeds), so
 * this exercises [friendFromQuery] — the value-shaping half of the real
 * `friendFromUri` — with the query values a `Uri` would hand it. #79.
 */
class FriendLinkTest {

    @Test fun roundTrips() {
        val friend = Friend(setlistfm = "dizzi90", name = "Magnus", spotifyId = "dizziness")
        assertEquals(friend, friendFromQuery(friend.setlistfm, friend.name, friend.spotifyId))
    }

    @Test fun survivesADottedUsername() {
        // setlist.fm usernames can contain dots; the card must not mangle them.
        val friend = Friend(setlistfm = "magnus.vikan.90", name = "Magnus V.")
        assertEquals(friend, friendFromQuery(friend.setlistfm, friend.name, friend.spotifyId))
    }

    @Test fun nameAndSpotifyIdDegradeCleanlyWhenAbsent() {
        // No name/sid on the wire (nulls, as a Uri hands back a missing query param) ->
        // name falls back to the username and spotifyId stays null.
        assertEquals(Friend(setlistfm = "alice"), friendFromQuery("alice", null, null))
        // Blank rather than absent must degrade the same way.
        assertEquals(Friend(setlistfm = "alice"), friendFromQuery("alice", "  ", " "))
    }

    @Test fun rejectsAMissingUsername() {
        assertNull(friendFromQuery(null, "Magnus", null))
        assertNull(friendFromQuery("  ", "Magnus", null))
    }

    /**
     * #271: a link cannot make a **Contact**. Holding a key is what makes one, and a
     * **Contact** is not addable remotely — so the parser takes no key at all and the
     * **Card** it hands back has none. The refusal is narrow: the rest of the link
     * still arrives, so this is a door closed rather than a parser that stopped working.
     *
     * The signature is the enforcement — a `k` argument cannot be passed because there
     * is nowhere to pass it. The reflection line is what fails if someone adds one back
     * as a convenience: an added parameter (default or not) leaves the three-argument
     * form compiling and every value assertion below still green.
     */
    @Test fun aLinkNeverCarriesAKey() {
        val card = friendFromQuery("dizzi90", "Magnus", "dizziness")
        assertEquals(Friend(setlistfm = "dizzi90", name = "Magnus", spotifyId = "dizziness"), card)
        assertNull(card?.publicKey)

        val parser = Class.forName("io.github.magnusencoded.stationtostation.data.FriendsKt")
            .declaredMethods.single { it.name == "friendFromQuery" }
        assertEquals(
            "friendFromQuery takes u/name/sid and nothing else — see #271",
            3,
            parser.parameterCount,
        )
    }
}
