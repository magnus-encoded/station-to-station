package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.Festivals
import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.data.StoredFestival
import io.github.magnusencoded.stationtostation.data.TimelineCache
import io.github.magnusencoded.stationtostation.data.dismissingMaybe
import io.github.magnusencoded.stationtostation.data.joiningNight
import io.github.magnusencoded.stationtostation.data.setlistfm.FmArtist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmCity
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmVenue
import io.github.magnusencoded.stationtostation.data.undismissingMaybe
import io.github.magnusencoded.stationtostation.data.unjoiningNight
import io.github.magnusencoded.stationtostation.ui.MaybeNight
import io.github.magnusencoded.stationtostation.ui.TimelineNode
import io.github.magnusencoded.stationtostation.ui.WovenRow
import io.github.magnusencoded.stationtostation.ui.compareMaybe
import io.github.magnusencoded.stationtostation.ui.maybeAnswered
import io.github.magnusencoded.stationtostation.ui.maybeFieldSpoken
import io.github.magnusencoded.stationtostation.ui.maybeMergeLabel
import io.github.magnusencoded.stationtostation.ui.maybeNights
import io.github.magnusencoded.stationtostation.ui.maybePill
import io.github.magnusencoded.stationtostation.ui.sameNightLine
import io.github.magnusencoded.stationtostation.ui.weaveTimelines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The zoomed-out spine: my nodes, other people's, and where the two are the same night. */
class WeaveTimelinesTest {

    // A real setlist.fm show by default — `url` is what `isLocal()` reads, and most
    // shows in this file stand in for genuine setlist.fm records on both sides. The
    // #433 tests below build a local (no-account) show explicitly with `url = null`.
    private fun show(id: String, date: String, venue: String) = FmSetlist(
        id = id,
        eventDate = date, // dd-MM-yyyy
        artist = FmArtist(name = "Artist $id"),
        venue = FmVenue(name = venue),
        url = "https://www.setlist.fm/setlist/$id.html",
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

    /**
     * A night we were **both** at, listed inside an open **Festival**, is a
     * **Crossing** — at the **Resolution** the Festival is open, not only at the one
     * it is closed.
     *
     * The regression: `showsHereByFriends` was left off the member rows, and
     * `sharedCount` is an intersection with it, so it was structurally zero at depth
     * 1. The Festival counted "1 together" and the night it counted drew amber.
     */
    @Test
    fun `a shared night inside an open festival is a crossing`() {
        val mine = listOf(show("a1", "25-06-2026", "Ekebergsletta"), show("a2", "24-06-2026", "Ekebergsletta"))
        // a1 is on both lists; a2 is mine alone.
        val theirs = mapOf("Lemmy" to listOf(show("a1", "25-06-2026", "Ekebergsletta")))
        val ours = festival("a1", "a2")
        val collapsed = weaveTimelines(mine, ours, listOf(lemmy), theirs)
        assertEquals(1, collapsed[0].sharedCount) // the closed Festival already knew

        val rows = weaveTimelines(mine, ours, listOf(lemmy), theirs, expanded = setOf(collapsed[0].key))
        val inner = rows.filter { it.depth == 1 }

        assertEquals(1, inner.first { it.shows.firstOrNull()?.id == "a1" }.sharedCount)
        // And the night nobody else was at is still mine alone — the fix must not hand
        // a Crossing to every member row of a shared Festival.
        assertEquals(0, inner.first { it.shows.firstOrNull()?.id == "a2" }.sharedCount)
    }

    /**
     * The **Absorb** case, which the fix must leave exactly as it was: their cluster
     * sits in my node without our having shared a night, so no member row is a
     * Crossing however many **Line**s run through the row.
     */
    @Test
    fun `an absorbed festival has company but no crossing inside`() {
        val mine = listOf(show("a1", "25-06-2026", "Ekebergsletta"), show("a2", "24-06-2026", "Ekebergsletta"))
        val theirs = mapOf(
            "Lemmy" to listOf(
                show("b1", "27-06-2026", "Ekebergsletta"),
                show("b2", "26-06-2026", "Ekebergsletta"),
            ),
        )
        val ours = festival("a1", "a2", "b1", "b2")
        val collapsed = weaveTimelines(mine, ours, listOf(lemmy), theirs)
        assertTrue(collapsed[0].others.isNotEmpty())
        assertEquals(0, collapsed[0].sharedCount)

        val rows = weaveTimelines(mine, ours, listOf(lemmy), theirs, expanded = setOf(collapsed[0].key))
        assertTrue(rows.filter { it.depth == 1 }.all { it.sharedCount == 0 })
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
     * A local **Gig** — no setlist.fm account behind it, so no real setlist.fm id, and
     * a venue string typed by hand or guessed from a ticket rather than pulled from
     * setlist.fm's own listing (#433). A friend's night at the same room on the same
     * date used to draw its own row entirely — "theirs" and nothing else — because the
     * only fold path besides a shared id, `sameEvening`, required the venue names to
     * match character for character, and "Parkteatret" never will against
     * "Parkteatret Scene, Oslo, Norway".
     */
    @Test
    fun `a local gig with no setlist fm id still folds into a friend's night at the same room`() {
        val mine = show("local-1", "29-01-2027", "Parkteatret").copy(url = null)
        val theirs = show("sfm-9", "29-01-2027", "Parkteatret Scene, Oslo, Norway")
        val rows = weaveTimelines(
            mine = listOf(mine),
            festivals = Festivals(),
            friends = listOf(lemmy),
            theirs = mapOf("Lemmy" to listOf(theirs)),
        )

        assertEquals(1, rows.size)
        assertTrue(rows[0].shared)
        assertEquals(1, rows[0].sharedCount)
        assertEquals(0, rows[0].theirsCount)
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

    // --- The maybe-shared marker (#405). Twinned in WeaveTimelinesTests.swift. ---

    /** A Night typed by hand: no setlist.fm id behind it. */
    private fun local(id: String, date: String, venue: String) = show(id, date, venue).copy(url = null)

    @Test
    fun `the same date and the same id is joined, not a maybe`() {
        val night = local("x1", "21-11-2025", "Blå")
        val rows = weaveTimelines(listOf(night), Festivals(), listOf(lemmy), mapOf("Lemmy" to listOf(night)))
        assertEquals(1, rows.size)
        assertEquals(1, rows[0].sharedCount)
        assertTrue(rows[0].maybe.isEmpty())
    }

    @Test
    fun `the same date under different ids is a maybe, and never a crossing`() {
        val rows = weaveTimelines(
            mine = listOf(local("m1", "21-11-2025", "Blå")),
            festivals = Festivals(),
            friends = listOf(lemmy),
            theirs = mapOf("Lemmy" to listOf(local("n1", "21-11-2025", "Blå"))),
        )
        // Two rows: the same room on the same date does not fold two hand-logged Nights,
        // because folding them would be the app answering its own question.
        assertEquals(2, rows.size)
        val mine = rows.single { it.mine }
        assertEquals(listOf(lemmy), mine.maybe)
        assertTrue(mine.others.isEmpty())
        assertEquals(0, mine.sharedCount)
        assertTrue(rows.single { !it.mine }.maybe.isEmpty())
    }

    @Test
    fun `different dates are neither`() {
        val rows = weaveTimelines(
            mine = listOf(local("m1", "21-11-2025", "Blå")),
            festivals = Festivals(),
            friends = listOf(lemmy),
            theirs = mapOf("Lemmy" to listOf(local("n1", "22-11-2025", "Blå"))),
        )
        assertTrue(rows.all { it.maybe.isEmpty() && it.sharedCount == 0 })
    }

    @Test
    fun `a maybe I said is not the same does not come back`() {
        val rows = weaveTimelines(
            mine = listOf(local("m1", "21-11-2025", "Blå")),
            festivals = Festivals(),
            friends = listOf(lemmy),
            theirs = mapOf("Lemmy" to listOf(local("n1", "21-11-2025", "Blå"))),
            apart = mapOf("n1" to setOf("m1")),
        )
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.maybe.isEmpty() })
    }

    @Test
    fun `a maybe I joined is drawn joined`() {
        val rows = weaveTimelines(
            mine = listOf(local("m1", "21-11-2025", "Blå")),
            festivals = Festivals(),
            friends = listOf(lemmy),
            theirs = mapOf("Lemmy" to listOf(local("n1", "21-11-2025", "Somewhere else"))),
            joins = mapOf("n1" to "m1"),
        )
        // Folded onto my node even across two room names, because I said so.
        assertEquals(1, rows.size)
        assertEquals(listOf(lemmy), rows[0].others)
        assertEquals(1, rows[0].sharedCount)
        assertEquals(0, rows[0].theirsCount)
        assertTrue(rows[0].maybe.isEmpty())
    }

    /** Story 24: a Festival day against a single Act is a difference of granularity. */
    @Test
    fun `their Festival day against my single Act is not asserted to be the same record`() {
        val rows = weaveTimelines(
            mine = listOf(show("g1", "25-06-2026", "Ekebergsletta")),
            festivals = festival("f1"),
            friends = listOf(lemmy),
            theirs = mapOf("Lemmy" to listOf(local("f1", "25-06-2026", "Ekebergsletta"))),
        )
        val mine = rows.single { it.mine }
        assertEquals(0, mine.sharedCount)
        assertTrue(mine.others.isEmpty())
        assertEquals(listOf(lemmy), mine.maybe)
    }

    /** Two catalogued records are a fact, not a question: two rooms, two Nights. */
    @Test
    fun `two setlist fm ids on one date are never a maybe`() {
        val rows = weaveTimelines(
            mine = listOf(show("a1", "21-11-2025", "Blå")),
            festivals = Festivals(),
            friends = listOf(lemmy),
            theirs = mapOf("Lemmy" to listOf(show("b1", "21-11-2025", "Rockefeller"))),
        )
        assertTrue(rows.all { it.maybe.isEmpty() })
    }

    /** A Night of mine we already cross is no question, whatever else they logged that date. */
    @Test
    fun `a night already crossed asks nothing more`() {
        val night = local("x1", "21-11-2025", "Blå")
        val rows = weaveTimelines(
            mine = listOf(night),
            festivals = Festivals(),
            friends = listOf(lemmy),
            theirs = mapOf("Lemmy" to listOf(night, local("n2", "21-11-2025", "Rockefeller"))),
        )
        assertTrue(rows.all { it.maybe.isEmpty() })
    }

    @Test
    fun `maybeNights names the pair it is asking about`() {
        val m1 = local("m1", "21-11-2025", "Blå")
        val n1 = local("n1", "21-11-2025", "Blå")
        val asked = maybeNights(listOf(m1), listOf(lemmy, ozzy), mapOf("Lemmy" to listOf(n1)))
        assertEquals(listOf(MaybeNight(lemmy, m1, n1)), asked)
    }

    // --- Answering a maybe from the Spine (#580). Twinned in WeaveTimelinesTests.swift. ---

    private val tom = Friend(setlistfm = "Tom", name = "Tom")

    private fun ids(rows: List<WovenRow>) = rows.map { it.shows.first().id }

    /** Ozzy's catalogued Night that date is no question, and no longer stands between the pair. */
    @Test
    fun `a maybe pair becomes neighbours past another night that date`() {
        val m1 = show("m1", "21-11-2025", "Blå")
        val n1 = local("n1", "21-11-2025", "Brenneriveien 9")
        val rows = weaveTimelines(
            mine = listOf(m1),
            festivals = Festivals(),
            friends = listOf(ozzy, lemmy),
            theirs = mapOf(
                "Ozzy" to listOf(show("b1", "21-11-2025", "Rockefeller")),
                "Lemmy" to listOf(n1),
            ),
        )
        assertEquals(listOf("m1", "n1", "b1"), ids(rows))
        assertEquals(listOf(MaybeNight(lemmy, m1, n1)), rows[1].maybeAbove)
        assertTrue(rows[0].maybeAbove.isEmpty() && rows[2].maybeAbove.isEmpty())
        // Asked by the merge row, so not said again in words on my row.
        assertEquals(listOf(lemmy), rows[0].maybe)
        assertTrue(rows[0].maybeInWords.isEmpty())
    }

    /** mine → merge(Lemmy) → Lemmy's → merge(Tom) → Tom's, in Lane order. */
    @Test
    fun `several maybes stack below my night in lane order`() {
        val m1 = show("m1", "21-11-2025", "Blå")
        val n1 = local("n1", "21-11-2025", "Brenneriveien 9")
        val t1 = local("t1", "21-11-2025", "Rockefeller")
        val rows = weaveTimelines(
            mine = listOf(show("m0", "22-11-2025", "Sentrum"), m1, show("m2", "20-11-2025", "Parkteatret")),
            festivals = Festivals(),
            friends = listOf(tom, ozzy, lemmy),
            theirs = mapOf(
                "Tom" to listOf(t1),
                "Ozzy" to listOf(show("b1", "21-11-2025", "Victoria")),
                "Lemmy" to listOf(n1),
            ),
        )
        assertEquals(listOf("m0", "m1", "t1", "n1", "b1", "m2"), ids(rows))
        assertEquals(listOf(MaybeNight(tom, m1, t1)), rows[2].maybeAbove)
        assertEquals(listOf(MaybeNight(lemmy, m1, n1)), rows[3].maybeAbove)
        assertEquals(listOf(tom, lemmy), rows[1].maybe)
        assertTrue(rows[1].maybeInWords.isEmpty())
    }

    /** One Night of theirs, two of mine that date: one merge row, and the other asks in words. */
    @Test
    fun `a night of theirs sits below the first night of mine that asks`() {
        val m1 = local("m1", "21-11-2025", "Blå")
        val m2 = local("m2", "21-11-2025", "Rockefeller")
        val n1 = local("n1", "21-11-2025", "Brenneriveien 9")
        val rows = weaveTimelines(listOf(m1, m2), Festivals(), listOf(lemmy), mapOf("Lemmy" to listOf(n1)))
        assertEquals(listOf("m1", "n1", "m2"), ids(rows))
        assertEquals(listOf(MaybeNight(lemmy, m1, n1)), rows[1].maybeAbove)
        assertTrue(rows[0].maybeInWords.isEmpty())
        assertEquals(listOf(lemmy), rows[2].maybeInWords)
    }

    @Test
    fun `same night draws my node joined with them, and undo asks again`() {
        val m1 = show("m1", "21-11-2025", "Blå")
        val n1 = local("n1", "21-11-2025", "Brenneriveien 9")
        val cache = TimelineCache()
        fun weave(c: TimelineCache) = weaveTimelines(
            listOf(m1), Festivals(), listOf(lemmy), mapOf("Lemmy" to listOf(n1)),
            joins = c.nightJoins, apart = c.nightDismissals.mapValues { it.value.toSet() },
        )
        val before = weave(cache)

        val joined = weave(cache.joiningNight("n1", "m1"))
        assertEquals(1, joined.size)
        assertEquals(listOf(lemmy), joined[0].joinedWith)
        assertTrue(joined.all { it.maybeAbove.isEmpty() && it.maybe.isEmpty() })

        val apart = weave(cache.dismissingMaybe("n1", "m1"))
        assertTrue(apart.all { it.maybeAbove.isEmpty() && it.maybe.isEmpty() })

        assertEquals(before, weave(cache.joiningNight("n1", "m1").unjoiningNight("n1", "m1")))
        assertEquals(before, weave(cache.dismissingMaybe("n1", "m1").undismissingMaybe("n1", "m1")))
        assertEquals(listOf(MaybeNight(lemmy, m1, n1)), before[1].maybeAbove)
    }

    @Test
    fun `a multi day festival is not dragged across dates to place a maybe`() {
        val mine = show("m1", "21-11-2025", "Blå")
        val rows = weaveTimelines(
            listOf(mine), festival("f1", "f2"), listOf(lemmy),
            mapOf("Lemmy" to listOf(local("f1", "21-11-2025", "Festival"),
                local("f2", "23-11-2025", "Festival"))),
        )
        assertEquals(listOf("2025-11-23", "2025-11-21"), rows.map { it.date.toString() })
        assertTrue(rows.all { it.maybeAbove.isEmpty() })
        assertEquals(listOf(lemmy), rows.single { it.mine }.maybeInWords)
    }

    // --- The comparison (#580) ---

    @Test
    fun `the comparison lights the fields that differ and never the source`() {
        val mine = show("m1", "21-11-2025", "Blå").copy(
            artist = FmArtist(name = "Isak Benjamin"),
            venue = FmVenue(name = "Blå", city = FmCity(name = "Oslo")),
        )
        val theirs = local("n1", "21-11-2025", "Brenneriveien 9").copy(
            artist = FmArtist(name = "isak benjamin "),
            venue = FmVenue(name = "Brenneriveien 9", city = FmCity(name = "Oslo")),
        )
        val maybe = MaybeNight(Friend(setlistfm = "", name = "Mia"), mine, theirs)
        val fields = compareMaybe(maybe)
        assertEquals(listOf("Artist", "Date", "Venue", "City", "From"), fields.map { it.label })
        assertEquals(listOf("Venue"), fields.filter { it.differs }.map { it.label })
        assertEquals("21 Nov 2025", fields[1].yours)
        assertEquals(listOf("setlist.fm", "typed by hand"), fields.last().let { listOf(it.yours, it.theirs) })
        assertEquals("Venue: yours Blå, Mia's Brenneriveien 9, differs", maybeFieldSpoken(maybe, fields[2]))
        assertEquals("City: yours Oslo, Mia's Oslo", maybeFieldSpoken(maybe, fields[3]))
        assertEquals("Same night as Mia's?", maybePill(maybe))
        assertEquals("Maybe the same night: yours above, Mia's below. Compare them.", maybeMergeLabel(maybe))
        assertEquals("Joined with Mia's night", maybeAnswered(maybe, same = true))
        assertEquals("Kept apart from Mia's night", maybeAnswered(maybe, same = false))
    }

    /** The setlist.fm record is the one kept; the line under the table says which, per case. */
    @Test
    fun `the line under the comparison names what same night keeps`() {
        val mia = Friend(setlistfm = "", name = "Mia")
        val fm = show("m1", "21-11-2025", "Blå")
        val hand = local("h1", "21-11-2025", "Blå")
        assertEquals(
            "Same night keeps your setlist.fm entry. Mia's joins it.",
            sameNightLine(MaybeNight(mia, fm, hand.copy(id = "n1"))),
        )
        assertEquals(
            "Same night keeps your entry. Mia's setlist.fm entry joins it.",
            sameNightLine(MaybeNight(mia, hand, fm.copy(id = "n1"))),
        )
        assertEquals(
            "Same night keeps your entry. Mia's joins it.",
            sameNightLine(MaybeNight(mia, hand, hand.copy(id = "n1"))),
        )
    }
}
