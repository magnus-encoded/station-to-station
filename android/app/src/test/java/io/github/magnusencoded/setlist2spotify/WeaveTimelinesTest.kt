package io.github.magnusencoded.setlist2spotify

import io.github.magnusencoded.setlist2spotify.data.Friend
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmArtist
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmSetlist
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmVenue
import io.github.magnusencoded.setlist2spotify.ui.TimelineNode
import io.github.magnusencoded.setlist2spotify.ui.weaveTimelines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The zoomed-out spine: my nodes, other people's, and where the two are the same night. */
class WeaveTimelinesTest {

    private fun show(id: String, date: String, venue: String) = FmSetlist(
        id = id,
        eventDate = date, // dd-MM-yyyy
        artist = FmArtist(name = "Artist $id"),
        venue = FmVenue(name = venue),
    )

    private val trummis = Friend(setlistfm = "Trummispojken", name = "Trummispojken")

    @Test
    fun `with nobody connected the rows are just my own`() {
        val rows = weaveTimelines(
            mine = listOf(show("1", "21-11-2025", "Blå")),
            festivalNames = emptyMap(),
            friends = emptyList(),
            theirs = emptyMap(),
        )
        assertEquals(1, rows.size)
        assertTrue(rows[0].mine)
        assertTrue(rows[0].others.isEmpty())
    }

    @Test
    fun `their festival at my venue folds into my node instead of sitting beside it`() {
        val rows = weaveTimelines(
            mine = listOf(show("a1", "25-06-2026", "Ekebergsletta"), show("a2", "24-06-2026", "Ekebergsletta")),
            festivalNames = emptyMap(),
            friends = listOf(trummis),
            theirs = mapOf(
                "Trummispojken" to listOf(show("b1", "27-06-2026", "Ekebergsletta"), show("b2", "26-06-2026", "Ekebergsletta")),
            ),
        )
        assertEquals(1, rows.size)
        assertTrue(rows[0].node is TimelineNode.Festival)
        assertTrue(rows[0].shared)
        assertEquals(listOf(trummis), rows[0].others)
    }

    @Test
    fun `a night only they were at gets its own row and leaves my spine bare`() {
        val rows = weaveTimelines(
            mine = listOf(show("a1", "21-11-2025", "Blå")),
            festivalNames = emptyMap(),
            friends = listOf(trummis),
            theirs = mapOf("Trummispojken" to listOf(show("b1", "12-06-2025", "3Arena"))),
        )
        assertEquals(2, rows.size)
        // Newest first, and the one that isn't mine carries no node of my own.
        assertTrue(rows[0].mine)
        assertFalse(rows[1].mine)
        assertEquals(listOf(trummis), rows[1].others)
    }

    @Test
    fun `opening a shared festival lists both sides' gigs underneath it`() {
        val mine = listOf(show("a1", "25-06-2026", "Ekebergsletta"), show("a2", "24-06-2026", "Ekebergsletta"))
        val theirs = mapOf("Trummispojken" to listOf(show("b1", "26-06-2026", "Ekebergsletta")))
        val collapsed = weaveTimelines(mine, emptyMap(), listOf(trummis), theirs)
        val rows = weaveTimelines(mine, emptyMap(), listOf(trummis), theirs, expanded = setOf(collapsed[0].key))

        assertEquals(4, rows.size) // the festival, then its three gigs
        assertTrue(rows[0].node is TimelineNode.Festival)
        val inner = rows.drop(1)
        assertTrue(inner.all { it.depth == 1 })
        assertEquals(listOf(false, true, true), inner.map { it.mine }) // 26th theirs, 25th + 24th mine
    }
}
