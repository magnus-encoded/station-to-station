package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.preamble
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sentence over a **Note**, composed from the record (#50).
 *
 * Every name, venue and band here is invented — this repository is public and no real
 * concert history belongs in a fixture.
 */
class PreambleTest {

    @Test
    fun `every clause known reads as one sentence`() {
        assertEquals(
            "I was here with Egil and Trummispojken at Verandaen, seeing this set.",
            preamble(listOf("Egil", "Trummispojken"), "Verandaen", songCount = 14),
        )
    }

    @Test
    fun `no venue drops the place and nothing else`() {
        assertEquals(
            "I was here with Egil, seeing this set.",
            preamble(listOf("Egil"), venue = null, songCount = 14),
        )
    }

    @Test
    fun `nobody else drops the company and nothing else`() {
        assertEquals(
            "I was here at Verandaen, seeing this set.",
            preamble(emptyList(), "Verandaen", songCount = 14),
        )
    }

    @Test
    fun `no setlist drops the set and nothing else`() {
        assertEquals(
            "I was here with Egil at Verandaen.",
            preamble(listOf("Egil"), "Verandaen", songCount = 0),
        )
    }

    /**
     * The 1992 import: attended, undated beyond the year, no venue and no record. It
     * gets no sentence rather than a sentence with holes in it — and it is exactly
     * the night someone most wants to write about from memory.
     */
    @Test
    fun `nothing known renders nothing at all`() {
        assertEquals("", preamble(emptyList(), null, 0))
        assertEquals("", preamble(listOf("  "), "   ", 0))
    }

    @Test
    fun `one name reads without a conjunction and three read as a list`() {
        assertEquals("I was here with Ida.", preamble(listOf("Ida")))
        assertEquals(
            "I was here with Ida, Egil and Trummispojken.",
            preamble(listOf("Ida", "Egil", "Trummispojken")),
        )
    }

    /**
     * **Reconcile** has no time bound, so the cast changes years later. The sentence
     * is a function of the record precisely so that it improves instead of going
     * stale — this is the property that makes storing it wrong.
     */
    @Test
    fun `a contact discovered later simply appears in the sentence`() {
        val then = preamble(listOf("Ida"), "Verandaen")
        val now = preamble(listOf("Ida", "Egil"), "Verandaen")
        assertEquals("I was here with Ida at Verandaen.", then)
        assertEquals("I was here with Ida and Egil at Verandaen.", now)
    }
}
