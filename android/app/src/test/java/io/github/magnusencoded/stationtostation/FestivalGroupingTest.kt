package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.Festivals
import io.github.magnusencoded.stationtostation.data.StoredFestival
import io.github.magnusencoded.stationtostation.data.billedAs
import io.github.magnusencoded.stationtostation.data.setlistfm.FmArtist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSet
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSets
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSong
import io.github.magnusencoded.stationtostation.data.setlistfm.FmVenue
import io.github.magnusencoded.stationtostation.ui.TimelineNode
import io.github.magnusencoded.stationtostation.ui.groupIntoFestivals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What becomes one **Node**, and what it is called — the one seam, asserted the way the
 * timeline reads it rather than by how the grouping got there.
 *
 * The names here are invented. This repository is public and no real concert history
 * belongs in a fixture.
 */
class FestivalGroupingTest {

    private fun show(
        id: String,
        date: String,
        venue: String,
        artist: String = "Artist $id",
        songs: Int = 1,
        info: String? = null,
    ) = FmSetlist(
        id = id,
        eventDate = date, // dd-MM-yyyy
        artist = FmArtist(name = artist),
        venue = FmVenue(name = venue),
        info = info,
        sets = FmSets(listOf(FmSet(song = List(songs) { FmSong(name = "Song $it") }))),
    )

    /** An identity for [shows], the way the store hands one over. */
    private fun identity(
        vararg shows: String,
        name: String = "Hollowmoor Sound 2026",
        dayMembership: Map<String, List<String>>? = null,
        setTimes: Map<String, String>? = null,
    ) = Festivals(
        byId = mapOf(
            "hm26" to StoredFestival(
                id = "hm26",
                name = name,
                dayMembership = dayMembership,
                setTimes = setTimes,
            ),
        ),
        idByShow = shows.associateWith { "hm26" },
    )

    // --- The false festival ---------------------------------------------------------

    /**
     * #166's own case. Two acts, one room, one night, and nothing that knows what the
     * evening was: a headline show with support, which the app used to draw as a
     * **Festival** named after the venue.
     */
    @Test
    fun `two acts at one venue on one night are one Section, named from its acts`() {
        val nodes = groupIntoFestivals(
            listOf(
                show("1", "24-11-2019", "Hollowmoor Hall", artist = "Marrowfield", songs = 18),
                show("2", "24-11-2019", "Hollowmoor Hall", artist = "Pale Ledger", songs = 6),
            ),
        )
        val section = nodes.single() as TimelineNode.Section
        assertEquals("Marrowfield (Pale Ledger)", section.label)
        assertTrue("Hollowmoor Hall" !in section.label)
    }

    /** The four-day window is gone: a run of nights is a run of nights. */
    @Test
    fun `two nights at one venue with no identity are two Nodes`() {
        val nodes = groupIntoFestivals(
            listOf(
                show("1", "25-06-2026", "Hollowmoor Hall"),
                show("2", "24-06-2026", "Hollowmoor Hall"),
            ),
        )
        assertEquals(2, nodes.size)
        assertTrue(nodes.all { it is TimelineNode.Concert })
    }

    @Test
    fun `a lone show stays a Concert`() {
        val nodes = groupIntoFestivals(listOf(show("1", "10-05-2026", "Hollowmoor Hall")))
        assertTrue(nodes.single() is TimelineNode.Concert)
    }

    @Test
    fun `two acts on one night at different venues stay two Nodes`() {
        val nodes = groupIntoFestivals(
            listOf(
                show("1", "25-06-2026", "Hollowmoor Hall"),
                show("2", "25-06-2026", "Pale Ledger Club"),
            ),
        )
        assertEquals(2, nodes.size)
    }

    /** setlist.fm's `info` is free text, not a name. It must never reach the label. */
    @Test
    fun `free-text info never leaks into the label`() {
        val nodes = groupIntoFestivals(
            listOf(
                show("1", "08-08-2025", "Hollowmoor Field", info = "a long editorial note"),
                show("2", "08-08-2025", "Hollowmoor Field", info = "First show in Norway"),
            ),
        )
        val section = nodes.single() as TimelineNode.Section
        assertTrue("First show in Norway" !in section.label)
        assertTrue("editorial" !in section.label)
    }

    // --- The identity ---------------------------------------------------------------

    @Test
    fun `the same two acts with an identity are one Festival, named from it`() {
        val shows = listOf(
            show("1", "24-11-2019", "Hollowmoor Hall"),
            show("2", "24-11-2019", "Hollowmoor Hall"),
        )
        val nodes = groupIntoFestivals(shows, identity("1", "2"))
        val festival = nodes.single() as TimelineNode.Festival
        assertEquals("Hollowmoor Sound 2026", festival.label)
        assertEquals(2, festival.shows.size)
    }

    /**
     * Membership follows the identity's own day grouping, so a festival's two days are
     * one **Node** — while two nights at that venue *without* an identity are two. The
     * difference is evidence, which is the whole issue.
     */
    @Test
    fun `acts across two days under one identity are one Festival`() {
        val shows = listOf(
            show("1", "25-06-2026", "Hollowmoor Field"),
            show("2", "25-06-2026", "Hollowmoor Field"),
            show("3", "24-06-2026", "Hollowmoor Field"),
        )
        val nodes = groupIntoFestivals(
            shows,
            identity(
                dayMembership = mapOf(
                    "25-06-2026" to listOf("1", "2"),
                    "24-06-2026" to listOf("3"),
                ),
            ),
        )
        val festival = nodes.single() as TimelineNode.Festival
        assertEquals(listOf("1", "2", "3"), festival.shows.map { it.id })
    }

    /** One day of a four-day festival is still that festival. */
    @Test
    fun `a single night carrying an identity is a Festival, not a Concert`() {
        val nodes = groupIntoFestivals(
            listOf(show("1", "25-06-2026", "Hollowmoor Field")),
            identity("1"),
        )
        assertTrue(nodes.single() is TimelineNode.Festival)
    }

    // --- The headliner ladder -------------------------------------------------------
    //
    // Three rungs, each a weaker answer to "who played last" — asserted one at a time,
    // so a fallback firing early is visible rather than absorbed.

    @Test
    fun `the headliner is the latest scheduled set time`() {
        val shows = listOf(
            // Longest set and first in source order, so only the times can decide it.
            show("1", "25-06-2026", "Hollowmoor Field", artist = "Pale Ledger", songs = 20),
            show("2", "25-06-2026", "Hollowmoor Field", artist = "Marrowfield", songs = 4),
        )
        assertEquals(
            "Marrowfield (Pale Ledger)",
            billedAs(shows, setTimes = mapOf("1" to "16:00", "2" to "22:00")),
        )
    }

    /** A set that ran past midnight closed the evening; it did not open it. */
    @Test
    fun `a set after midnight is the last of the night, not the first`() {
        val shows = listOf(
            show("1", "25-06-2026", "Hollowmoor Field", artist = "Pale Ledger", songs = 20),
            show("2", "25-06-2026", "Hollowmoor Field", artist = "Marrowfield", songs = 4),
        )
        assertEquals(
            "Marrowfield (Pale Ledger)",
            billedAs(shows, setTimes = mapOf("1" to "22:00", "2" to "00:30")),
        )
    }

    /** The evening as it went, where the source published the running order. */
    @Test
    fun `a Festival lists its acts in running order`() {
        val shows = listOf(
            show("1", "25-06-2026", "Hollowmoor Field"),
            show("2", "25-06-2026", "Hollowmoor Field"),
        )
        val nodes = groupIntoFestivals(
            shows,
            identity("1", "2", setTimes = mapOf("1" to "22:00", "2" to "16:00")),
        )
        val festival = nodes.single() as TimelineNode.Festival
        assertEquals(listOf("2", "1"), festival.runningOrder().map { it.id })
    }

    @Test
    fun `with no set times the headliner is the longest set`() {
        val nodes = groupIntoFestivals(
            listOf(
                show("1", "25-06-2026", "Hollowmoor Hall", artist = "Pale Ledger", songs = 5),
                show("2", "25-06-2026", "Hollowmoor Hall", artist = "Marrowfield", songs = 17),
            ),
        )
        assertEquals("Marrowfield (Pale Ledger)", (nodes.single() as TimelineNode.Section).label)
    }

    // The two weaker rungs on their own — song count, then source order — are
    // [BilledAsTest]'s, along with how many supports a long evening names.
}
