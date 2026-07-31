package io.github.magnusencoded.setlist2spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The address grammar for a night: the last segment is always the gig, and the first
 * says whose line you are looking at — which is the same thing as the resolution.
 */
class GigLinkTest {

    private fun parse(vararg segments: String) = parseGigLink(segments.toList())

    @Test
    fun `a gig on its own is the setlist`() {
        assertEquals("334c742d" to GigLink.SETLIST, parse("334c742d"))
    }

    @Test
    fun `a name in front of it is a single line at that night`() {
        assertEquals("334c742d" to GigLink.SINGLE_LINE, parse("dizzi90", "334c742d"))
    }

    @Test
    fun `Friends in front of it is the woven view`() {
        assertEquals("334c742d" to GigLink.WOVEN, parse("Friends", "334c742d"))
        // Android may hand back the authority in any case; the grammar must not care.
        assertEquals("334c742d" to GigLink.WOVEN, parse("friends", "334c742d"))
    }

    @Test
    fun `a trailing slash does not become the gig`() {
        assertEquals("334c742d" to GigLink.WOVEN, parse("Friends", "334c742d", ""))
    }

    @Test
    fun `nothing to open is not a link`() {
        assertNull(parse())
        assertNull(parse(""))
    }
}
