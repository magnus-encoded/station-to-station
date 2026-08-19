package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.WovenSong
import io.github.magnusencoded.stationtostation.data.weaveSetlist
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The alignment behind the interwoven set (#268). What it decides is which lines are
 * the *same* line, which is the whole of what stops a night being printed twice.
 */
class SetlistWeaveTest {

    private fun sides(rows: List<WovenSong>) = rows.map {
        when {
            it.both -> "="
            it.published != null -> "p"
            else -> "l"
        }.plus(it.published ?: it.logged)
    }

    @Test
    fun `two records of the same set are one line each`() {
        val rows = weaveSetlist(listOf("Tupelo", "Joy"), listOf("Tupelo", "Joy"))
        assertEquals(listOf("=0", "=1"), sides(rows))
    }

    @Test
    fun `a song I missed keeps every later song aligned`() {
        // The reason this is a diff and not an index walk: one dropped entry used to
        // put every song after it out by one, and the whole tail read as disagreement.
        val rows = weaveSetlist(
            listOf("Tupelo", "Joy", "Carnage", "Henry Lee"),
            listOf("Tupelo", "Carnage", "Henry Lee"),
        )
        assertEquals(listOf("=0", "p1", "=2", "=3"), sides(rows))
    }

    @Test
    fun `a song only I have sits between the published ones`() {
        val rows = weaveSetlist(listOf("Tupelo", "Joy"), listOf("Tupelo", "Wild God", "Joy"))
        assertEquals(listOf("=0", "l1", "=1"), sides(rows))
    }

    @Test
    fun `matching is loose the way recognition is everywhere else`() {
        // sameSong's terms: typed in the dark, without the apostrophe.
        val rows = weaveSetlist(listOf("Don't Look Back"), listOf("dont look back"))
        assertEquals(listOf("=0"), sides(rows))
    }

    @Test
    fun `a Gap matches nothing, however well it would fit`() {
        // "One I couldn't name" is a statement that no title was captured. Pairing it
        // with a published title would invent the claim it exists to avoid making.
        val rows = weaveSetlist(listOf("Tupelo"), listOf(""))
        assertEquals(listOf("p0", "l0"), sides(rows))
    }

    @Test
    fun `a row that is not a song is carried through and never matches`() {
        // Encore markers arrive as nulls, and have to keep their place in the set.
        val rows = weaveSetlist(listOf("Tupelo", null, "Joy"), listOf("Tupelo", "Joy"))
        assertEquals(listOf("=0", "p1", "=2"), sides(rows))
    }

    @Test
    fun `one side empty is the other side, in order`() {
        assertEquals(listOf("p0", "p1"), sides(weaveSetlist(listOf("A", "B"), emptyList())))
        assertEquals(listOf("l0", "l1"), sides(weaveSetlist(emptyList(), listOf("A", "B"))))
        assertEquals(emptyList<String>(), sides(weaveSetlist(emptyList(), emptyList())))
    }

    @Test
    fun `a song played twice stays two lines`() {
        // Position is the only thing telling two performances of one song apart, and
        // an LCS that collapsed them would lose the second.
        val rows = weaveSetlist(listOf("Tupelo", "Joy", "Tupelo"), listOf("Tupelo", "Tupelo"))
        assertEquals(listOf("=0", "p1", "=2"), sides(rows))
    }
}
