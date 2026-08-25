package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.Festivals
import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.data.StoredFestival
import io.github.magnusencoded.stationtostation.data.setlistfm.FmArtist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmVenue
import io.github.magnusencoded.stationtostation.ui.TimelineNode
import io.github.magnusencoded.stationtostation.ui.weaveTimelines
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

    /**
     * A **Festival** identity carried by [shows] — mine and theirs alike, since that
     * is what makes their nights and my nights the same festival rather than two
     * things at one address (#166).
     */
    private fun festival(vararg shows: String) = Festivals(
        byId = mapOf("hm26" to StoredFestival(id = "hm26", name = "Hollowmoor Sound 2026")),
        idByShow = shows.associateWith { "hm26" },
    )

    @Test
    fun `with nobody connected the rows are just my own`() {
        val rows = weaveTimelines(
            mine = listOf(show("1", "21-11-2025", "Blå")),
            festivals = Festivals(),
            friends = emptyList(),
            theirs = emptyMap(),
        )
        assertEquals(1, rows.size)
        assertTrue(rows[0].mine)
        assertTrue(rows[0].others.isEmpty())
    }

    /**
     * Their days at a festival land on my node rather than beside it — because the
     * identity says both are that festival. Without one they would be four separate
     * nights at one address, which is the true, smaller thing (#166).
     */
    @Test
    fun `their days at my festival fold into my node instead of sitting beside it`() {
        val rows = weaveTimelines(
            mine = listOf(show("a1", "25-06-2026", "Ekebergsletta"), show("a2", "24-06-2026", "Ekebergsletta")),
            festivals = festival("a1", "a2", "b1", "b2"),
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

    /** And with no identity, they do not fold: nothing knows those are one thing. */
    @Test
    fun `their run at my venue with no identity stays beside my nights`() {
        val rows = weaveTimelines(
            mine = listOf(show("a1", "25-06-2026", "Ekebergsletta"), show("a2", "24-06-2026", "Ekebergsletta")),
            festivals = Festivals(),
            friends = listOf(lemmy),
            theirs = mapOf(
                "Lemmy" to listOf(show("b1", "27-06-2026", "Ekebergsletta"), show("b2", "26-06-2026", "Ekebergsletta")),
            ),
        )
        assertEquals(4, rows.size)
        assertEquals(2, rows.count { it.mine })
        assertTrue(rows.none { it.shared })
    }

    @Test
    fun `a night only they were at gets its own row and leaves my spine bare`() {
        val rows = weaveTimelines(
            mine = listOf(show("a1", "21-11-2025", "Blå")),
            festivals = Festivals(),
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
        val theirFestival = festival("b1", "b2")
        val collapsed = weaveTimelines(mine, theirFestival, listOf(lemmy), theirs)
        val fest = collapsed.first { it.node is TimelineNode.Festival }
        val rows = weaveTimelines(mine, theirFestival, listOf(lemmy), theirs, expanded = setOf(fest.key))

        val inner = rows.filter { it.depth == 1 }
        assertEquals(2, inner.size)
        assertTrue(inner.none { it.mine })   // I was at neither
        assertTrue(inner.none { it.shared }) // so neither can be a night we shared
    }

    @Test
    fun `opening a shared festival lists both sides' gigs underneath it`() {
        val mine = listOf(show("a1", "25-06-2026", "Ekebergsletta"), show("a2", "24-06-2026", "Ekebergsletta"))
        val theirs = mapOf("Lemmy" to listOf(show("b1", "26-06-2026", "Ekebergsletta")))
        val ours = festival("a1", "a2", "b1")
        val collapsed = weaveTimelines(mine, ours, listOf(lemmy), theirs)
        val rows = weaveTimelines(mine, ours, listOf(lemmy), theirs, expanded = setOf(collapsed[0].key))

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
            festivals = festival("w1", "a2", "b2"),
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
            festivals = Festivals(),
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
            festivals = Festivals(),
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
            festivals = Festivals(),
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
            festivals = festival("b1", "b2"),
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
            festivals = Festivals(),
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
            festivals = Festivals(),
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

    /**
     * **Theirs** means a **Gig** on their timeline *and not on mine*. Where their list is
     * a subset of mine there is nothing that is theirs, however many nights we shared.
     *
     * Found on the Pixel with a **Contact** carrying my own username: the festival read
     * "4 together · 4 yours · 4 theirs". The **Crossings** were real — the arithmetic
     * beside them counted every shared night twice, once as ours and once as theirs.
     */
    @Test
    fun `nothing is theirs when their nights are a subset of mine`() {
        val mine = listOf(
            show("1", "08-08-2026", "Verandaen"),
            show("2", "08-08-2026", "Verandaen"),
            show("3", "08-08-2026", "Verandaen"),
        )
        val me = Friend(setlistfm = "dizzi90", name = "my other phone")

        val row = weaveTimelines(
            mine = mine,
            festivals = Festivals(),
            friends = listOf(me),
            theirs = mapOf("dizzi90" to mine),
        ).first()

        assertTrue(row.mine)
        assertEquals(3, row.sharedCount)
        assertEquals(0, row.theirsCount)
    }

    /** And a night only they were at is still theirs, on the same node. */
    @Test
    fun `a night only they were at is theirs`() {
        val together = show("1", "08-08-2026", "Verandaen")
        val onlyTheirs = show("9", "08-08-2026", "Verandaen")
        val lem = Friend(setlistfm = "Lemmy", name = "Lemmy")

        val row = weaveTimelines(
            mine = listOf(together, show("2", "08-08-2026", "Verandaen")),
            festivals = Festivals(),
            friends = listOf(lem),
            theirs = mapOf("Lemmy" to listOf(together, onlyTheirs)),
        ).first()

        assertEquals(1, row.sharedCount)
        assertEquals(1, row.theirsCount)
    }
}
