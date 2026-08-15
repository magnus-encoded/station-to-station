package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.friendFromQuery
import io.github.magnusencoded.stationtostation.data.isPlausibleSetlistFmUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The least trusted string in the app, checked at the door.
 *
 * A username arrives from a link any page can open or from any radio in range, and it
 * ends up in a setlist.fm path carrying our API key. #187 fixed the sink; this is the
 * source. Every name here is invented — this repository is public.
 */
class SetlistFmUserTest {

    @Test
    fun `an ordinary username is accepted`() {
        for (ok in listOf("ozzy", "Lemmy", "dizzi90", "a.b-c_d", "9")) {
            assertTrue(ok, isPlausibleSetlistFmUser(ok))
        }
    }

    @Test
    fun `a name in another script is still a name`() {
        // The rule excludes URL syntax, not people.
        for (ok in listOf("Ø", "Ægir", "Пётр", "さくら")) {
            assertTrue(ok, isPlausibleSetlistFmUser(ok))
        }
    }

    @Test
    fun `the percent that carried the CRLF is refused`() {
        // The #187 payload, at the door rather than at the socket. Doubly encoded is
        // how it survives a URL parser, so that is the form to reject.
        assertFalse(isPlausibleSetlistFmUser("alice%0d%0aX-Evil:%20yes"))
        assertFalse(isPlausibleSetlistFmUser("alice%0d%0a"))
    }

    @Test
    fun `nothing that means something to a URL gets through`() {
        for (bad in listOf("a/b", "a?b", "a#b", "a&b", "a=b", "a:b", "a@b", "a b", "a\tb", "a\nb", "..%2f")) {
            assertFalse(bad, isPlausibleSetlistFmUser(bad))
        }
    }

    @Test
    fun `empty and absurd lengths are refused`() {
        assertFalse(isPlausibleSetlistFmUser(""))
        assertTrue(isPlausibleSetlistFmUser("a".repeat(64)))
        assertFalse(isPlausibleSetlistFmUser("a".repeat(65)))
    }

    @Test
    fun `a link carrying a hostile username yields no contact at all`() {
        // Not a sanitised contact — none. A card we cannot read is not a meeting.
        assertNull(friendFromQuery("alice%0d%0aX-Evil:%20yes", "Alice", null))
        assertNull(friendFromQuery("../../admin", "Alice", null))
    }

    @Test
    fun `an ordinary link still builds the contact it always did`() {
        val f = friendFromQuery(" ozzy ", " Ozzy ", " sid ")
        assertEquals("ozzy", f?.setlistfm)
        assertEquals("Ozzy", f?.name)
        assertEquals("sid", f?.spotifyId)
    }
}
