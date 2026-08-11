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

    private val lemmy = Friend(setlistfm = "Lemmy", name = "Lemmy")
    private val ozzy = Friend(setlistfm = "Ozzy", name = "Ozzy")

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
            friends = listOf(lemmy),
            theirs = mapOf(
                "Lemmy" to listOf(show("b1", "27-06-2026", "Ekebergsletta"), show("b2", "26-06-2026", "Ekebergsletta")),
            ),
        )
        assertEquals(1, rows.size)
        assertTrue(rows[0].node is TimelineNode.Festival)
        assertTrue(rows[0].shared)
        assertEquals(listOf(lemmy), rows[0].others)
    }

    @Test
    fun `a night only they were at gets its own row and leaves my spine bare`() {
        val rows = weaveTimelines(
            mine = listOf(show("a1", "21-11-2025", "Blå")),
            festivalNames = emptyMap(),
            friends = listOf(lemmy),
            theirs = mapOf("Lemmy" to listOf(show("b1", "12-06-2025", "3Arena"))),
        )
        assertEquals(2, rows.size)
        // Newest first, and the one that isn't mine carries no node of my own.
        assertTrue(rows[0].mine)
        assertFalse(rows[1].mine)
        assertEquals(listOf(lemmy), rows[1].others)
    }

    @Test
    fun `opening a festival I never attended keeps every gig theirs`() {
        val theirs = mapOf(
            "Lemmy" to listOf(
                show("b1", "16-05-2026", "Stora Scenen"),
                show("b2", "15-05-2026", "Stora Scenen"),
            ),
        )
        val mine = listOf(show("a1", "21-11-2025", "Blå"))
        val collapsed = weaveTimelines(mine, emptyMap(), listOf(lemmy), theirs)
        val festival = collapsed.first { it.node is TimelineNode.Festival }
        val rows = weaveTimelines(mine, emptyMap(), listOf(lemmy), theirs, expanded = setOf(festival.key))

        val inner = rows.filter { it.depth == 1 }
        assertEquals(2, inner.size)
        assertTrue(inner.none { it.mine })   // I was at neither
        assertTrue(inner.none { it.shared }) // so neither can be a night we shared
    }

    @Test
    fun `opening a shared festival lists both sides' gigs underneath it`() {
        val mine = listOf(show("a1", "25-06-2026", "Ekebergsletta"), show("a2", "24-06-2026", "Ekebergsletta"))
        val theirs = mapOf("Lemmy" to listOf(show("b1", "26-06-2026", "Ekebergsletta")))
        val collapsed = weaveTimelines(mine, emptyMap(), listOf(lemmy), theirs)
        val rows = weaveTimelines(mine, emptyMap(), listOf(lemmy), theirs, expanded = setOf(collapsed[0].key))

        assertEquals(4, rows.size) // the festival, then its three gigs
        assertTrue(rows[0].node is TimelineNode.Festival)
        val inner = rows.drop(1)
        assertTrue(inner.all { it.depth == 1 })
        assertEquals(listOf(false, true, true), inner.map { it.mine }) // 26th theirs, 25th + 24th mine
    }

    // --- Three lines. Everything above holds with one friend and hides the rest. ---

    @Test
    fun `a night all three of us were at is one node carrying both of them`() {
        val tons = show("w1", "25-06-2026", "Ekebergsletta")
        val rows = weaveTimelines(
            mine = listOf(tons, show("a2", "24-06-2026", "Ekebergsletta")),
            festivalNames = emptyMap(),
            friends = listOf(ozzy, lemmy),
            theirs = mapOf(
                "Lemmy" to listOf(tons, show("b2", "26-06-2026", "Ekebergsletta")),
                "Ozzy" to listOf(tons),
            ),
        )
        assertEquals(1, rows.size)
        assertEquals(setOf(ozzy, lemmy), rows[0].others.toSet())
        assertTrue(rows[0].shared)
    }

    @Test
    fun `a gig two friends both went to is counted once, not once each`() {
        val tons = show("w1", "25-06-2026", "Ekebergsletta")
        val rows = weaveTimelines(
            mine = listOf(tons, show("a2", "24-06-2026", "Ekebergsletta")),
            festivalNames = emptyMap(),
            friends = listOf(ozzy, lemmy),
            theirs = mapOf(
                "Lemmy" to listOf(tons),
                "Ozzy" to listOf(tons),
            ),
        )
        // Both were at the same one gig: one show here, and it is the one we shared.
        assertEquals(1, rows[0].showsHereByFriends.size)
        assertEquals(1, rows[0].sharedCount)
    }

    @Test
    fun `a night I missed that two friends shared is one row, not one each`() {
        val theirs = show("b1", "12-06-2025", "3Arena")
        val rows = weaveTimelines(
            mine = listOf(show("a1", "21-11-2025", "Blå")),
            festivalNames = emptyMap(),
            friends = listOf(ozzy, lemmy),
            theirs = mapOf("Lemmy" to listOf(theirs), "Ozzy" to listOf(theirs)),
        )
        assertEquals(2, rows.size) // my night, and the one they shared without me
        val without = rows.first { !it.mine }
        assertEquals(setOf(ozzy, lemmy), without.others.toSet())
    }

    @Test
    fun `a night with one of them says so - the other is not on that node`() {
        val withOzzy = show("a1", "21-11-2025", "Blå")
        val rows = weaveTimelines(
            mine = listOf(withOzzy),
            festivalNames = emptyMap(),
            friends = listOf(ozzy, lemmy),
            theirs = mapOf(
                "Ozzy" to listOf(withOzzy),
                "Lemmy" to listOf(show("b9", "01-01-2020", "Somewhere else")),
            ),
        )
        val mine = rows.first { it.mine }
        assertEquals(listOf(ozzy), mine.others)
        assertEquals(1, mine.sharedCount)
    }

    @Test
    fun `a festival only they went to is never together`() {
        val rows = weaveTimelines(
            mine = listOf(show("a1", "21-11-2025", "Blå")),
            festivalNames = emptyMap(),
            friends = listOf(ozzy, lemmy),
            theirs = mapOf(
                "Ozzy" to listOf(
                    show("b1", "16-05-2026", "Stora Scenen"),
                    show("b2", "15-05-2026", "Stora Scenen"),
                ),
            ),
        )
        // Their node's own shows are theirs, so intersecting them with "what friends
        // attended" used to match every one and light the node green.
        assertEquals(0, rows.first { !it.mine }.sharedCount)
    }

    @Test
    fun `the same single gig on both lists is one node, not a row each`() {
        val night = show("x1", "21-11-2025", "Blå")
        val rows = weaveTimelines(
            mine = listOf(night),
            festivalNames = emptyMap(),
            friends = listOf(lemmy),
            theirs = mapOf("Lemmy" to listOf(night)),
        )
        // A lone concert used to fail to absorb, so a shared night drew two rows.
        assertEquals(1, rows.size)
        assertTrue(rows[0].shared)
        assertEquals(1, rows[0].sharedCount)
    }

    /**
     * My other device, added by **Exchange** with my own **Card**. Every night of mine
     * is a **Crossing** and the whole **Line** runs **Joined** — which is what makes this
     * the sharpest correctness check there is: any node that is *not* green is a real
     * difference between the two devices, not a rendering accident.
     *
     * The lane used to arrive empty, because the stored spine subtracted my own username
     * from the friends map before the weave ever saw it.
     */
    @Test
    fun `a contact carrying my own username is joined at every night of mine`() {
        val mine = listOf(
            show("1", "21-11-2025", "Blå"),
            show("2", "05-08-2026", "Hollowmoor Park"),
        )
        val me = Friend(setlistfm = "dizzi90", name = "my other phone")

        val rows = weaveTimelines(
            mine = mine,
            festivalNames = emptyMap(),
            friends = listOf(me),
            theirs = mapOf("dizzi90" to mine),
        )

        assertEquals(2, rows.size)
        rows.forEach {
            assertTrue("every night is mine", it.mine)
            assertEquals(listOf("dizzi90"), it.others.map { o -> o.setlistfm })
        }
        // And nothing of theirs sits beside mine as a second node.
        assertEquals(mine.size, rows.count { it.mine })
    }
}
