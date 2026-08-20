package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.data.FriendArrival
import io.github.magnusencoded.stationtostation.data.friendArrival
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a handed-over card is allowed to do to a **Contact** list (#188).
 *
 * A card arrives from a link any page can open, or a write by any radio in range. The
 * write it used to perform was a replace, so knowing a real contact's username was
 * enough to rewrite the name I see against their **Line**. Every name here is invented.
 */
class FriendArrivalTest {

    private val known = listOf(
        Friend(setlistfm = "ozzy", name = "Ozzy", spotifyId = "s-ozzy"),
        Friend(setlistfm = "lemmy", name = "Lemmy"),
    )

    @Test
    fun `a username nobody holds is simply new`() {
        val card = Friend(setlistfm = "dio", name = "Dio")

        assertEquals(FriendArrival.New(card), friendArrival(card, known))
    }

    @Test
    fun `the first contact of all is new`() {
        val card = Friend(setlistfm = "dio", name = "Dio")

        assertEquals(FriendArrival.New(card), friendArrival(card, emptyList()))
    }

    /**
     * Meeting the same person twice is the ordinary case for people who go to gigs
     * together. A prompt that routinely means nothing is a prompt nobody reads.
     */
    @Test
    fun `the same card again is neither a write nor a question`() {
        val same = Friend(setlistfm = "ozzy", name = "Ozzy", spotifyId = "s-ozzy")

        assertEquals(FriendArrival.Unchanged, friendArrival(same, known))
    }

    @Test
    fun `a different display name for someone I hold asks first`() {
        val card = Friend(setlistfm = "ozzy", name = "Ozzy (verified)", spotifyId = "s-ozzy")

        assertEquals(
            FriendArrival.Conflict(known[0], card),
            friendArrival(card, known),
        )
    }

    @Test
    fun `a different spotify id for someone I hold asks first`() {
        val card = Friend(setlistfm = "ozzy", name = "Ozzy", spotifyId = "s-someone-else")

        assertEquals(FriendArrival.Conflict(known[0], card), friendArrival(card, known))
    }

    /**
     * A card carrying no Spotify id is not a claim that they have none — an add by
     * username alone would otherwise ask to erase an id it never mentioned.
     */
    @Test
    fun `a card that is silent about spotify is not proposing to clear it`() {
        val card = Friend(setlistfm = "ozzy", name = "Ozzy")

        assertEquals(FriendArrival.Unchanged, friendArrival(card, known))
    }

    @Test
    fun `learning a spotify id we did not have still asks`() {
        // It is new information about someone I hold, which is the case that asks.
        val card = Friend(setlistfm = "lemmy", name = "Lemmy", spotifyId = "s-lemmy")

        assertEquals(FriendArrival.Conflict(known[1], card), friendArrival(card, known))
    }

    /**
     * The username is setlist.fm's identity and it is not case sensitive, so matching
     * has to agree with the list's own de-duplication — otherwise "Ozzy" would arrive
     * as new and sit beside "ozzy" as a second contact.
     */
    @Test
    fun `the username matches regardless of case`() {
        val card = Friend(setlistfm = "OZZY", name = "Ozzy (verified)")

        assertEquals(FriendArrival.Conflict(known[0], card), friendArrival(card, known))
    }

    @Test
    fun `a case-different card saying the same thing is still unchanged`() {
        val card = Friend(setlistfm = "OZZY", name = "Ozzy", spotifyId = "s-ozzy")

        assertEquals(FriendArrival.Unchanged, friendArrival(card, known))
    }

    // --- The first key: a **Followed line** becoming a **Contact** (#188) ---
    //
    // The ordinary path, not a rare one: someone added from a link or a scanned code,
    // met in person, their card arriving over the radio with a key on it. Asking here
    // would ask whether they are on a different phone than a phone never seen.

    @Test
    fun `a first key for someone I follow is a promotion and not a question`() {
        val card = Friend(setlistfm = "ozzy", name = "Ozzy", spotifyId = "s-ozzy", publicKey = "k-ozzy")

        assertEquals(FriendArrival.Promotion(card), friendArrival(card, known))
    }

    /** The card handed over in person outranks the name a link guessed. */
    @Test
    fun `a promotion carries the card's display name without asking`() {
        val card = Friend(setlistfm = "ozzy", name = "Ozzy Osbourne", spotifyId = "s-real", publicKey = "k-ozzy")

        assertEquals(FriendArrival.Promotion(card), friendArrival(card, known))
    }

    @Test
    fun `a different key for a contact I hold asks first`() {
        val keyed = listOf(Friend(setlistfm = "ozzy", name = "Ozzy", publicKey = "k-ozzy"))
        val card = Friend(setlistfm = "ozzy", name = "Ozzy", publicKey = "k-someone-else")

        assertEquals(FriendArrival.Conflict(keyed[0], card), friendArrival(card, keyed))
    }

    /**
     * The key is collected in one moment and there is no second chance to collect it —
     * only a second **Exchange**. A link, a typed username or a playlist collaborator
     * must never propose unmaking a **Contact**.
     */
    @Test
    fun `a card with no key does not propose clearing a key I hold`() {
        val keyed = listOf(Friend(setlistfm = "ozzy", name = "Ozzy", publicKey = "k-ozzy"))
        val card = Friend(setlistfm = "ozzy", name = "Ozzy")

        assertEquals(FriendArrival.Unchanged, friendArrival(card, keyed))
    }

    @Test
    fun `the same key and nothing else new is unchanged`() {
        val keyed = listOf(Friend(setlistfm = "ozzy", name = "Ozzy", spotifyId = "s-ozzy", publicKey = "k-ozzy"))
        val card = Friend(setlistfm = "ozzy", name = "Ozzy", spotifyId = "s-ozzy", publicKey = "k-ozzy")

        assertEquals(FriendArrival.Unchanged, friendArrival(card, keyed))
    }

    /** Meeting one person I follow says nothing about the others. */
    @Test
    fun `a promotion touches only the person it names`() {
        val card = Friend(setlistfm = "ozzy", name = "Ozzy", spotifyId = "s-ozzy", publicKey = "k-ozzy")

        assertEquals(FriendArrival.Promotion(card), friendArrival(card, known))
        assertEquals(
            FriendArrival.Promotion(Friend(setlistfm = "lemmy", name = "Lemmy", publicKey = "k-lemmy")),
            friendArrival(Friend(setlistfm = "lemmy", name = "Lemmy", publicKey = "k-lemmy"), known),
        )
    }
}
