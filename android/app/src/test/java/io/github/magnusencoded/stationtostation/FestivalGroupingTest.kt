package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.setlistfm.FmArtist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmVenue
import io.github.magnusencoded.stationtostation.ui.TimelineNode
import io.github.magnusencoded.stationtostation.ui.groupIntoFestivals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FestivalGroupingTest {

    private fun show(id: String, date: String, venue: String, info: String? = null) = FmSetlist(
        id = id,
        eventDate = date, // dd-MM-yyyy
        artist = FmArtist(name = "Artist $id"),
        venue = FmVenue(name = venue),
        info = info,
    )

    @Test
    fun freeTextInfoNeverLeaksIntoTheLabel() {
        // setlist.fm `info` is arbitrary notes, not the festival name, so it must
        // never leak into the label.
        val nodes = groupIntoFestivals(
            listOf(
                show("1", "08-08-2025", "Tøyenparken", info = "a long editorial note"),
                show("2", "07-08-2025", "Tøyenparken", info = "First show in Norway"),
            ),
        )
        val festival = nodes.single() as TimelineNode.Festival
        assertTrue("First show in Norway" !in festival.label)
        assertTrue("editorial" !in festival.label)
    }

    /**
     * #166. The venue used to be the label whenever the festival name had not
     * resolved — so a room appeared on the **Line** as though it were an event, and
     * the **Node** claimed festivalhood on the strength of a venue string and a date
     * window. Nothing knows this was a festival, so nothing says it was.
     */
    @Test
    fun anUnidentifiedClusterIsNeverNamedAfterItsVenue() {
        val nodes = groupIntoFestivals(
            listOf(
                show("1", "08-08-2025", "Tøyenparken"),
                show("2", "07-08-2025", "Tøyenparken"),
            ),
        )
        val festival = nodes.single() as TimelineNode.Festival
        assertEquals(null, festival.name)
        assertTrue(!festival.identified)
        assertTrue("Tøyenparken" !in festival.label)
    }

    @Test
    fun anIdentityFromTheSourceIsTheLabel() {
        val nodes = groupIntoFestivals(
            listOf(
                show("1", "08-08-2025", "Tøyenparken"),
                show("2", "07-08-2025", "Tøyenparken"),
            ),
            names = mapOf("1" to "Øyafestivalen 2025"),
        )
        val festival = nodes.single() as TimelineNode.Festival
        assertTrue(festival.identified)
        assertEquals("Øyafestivalen 2025", festival.label)
    }

    @Test
    fun sameVenueAdjacentDatesBecomeOneFestival() {
        val nodes = groupIntoFestivals(
            listOf(
                show("1", "25-06-2026", "Ekebergsletta"),
                show("2", "25-06-2026", "Ekebergsletta"),
                show("3", "24-06-2026", "Ekebergsletta"),
            ),
        )
        assertEquals(1, nodes.size)
        val festival = nodes[0] as TimelineNode.Festival
        assertEquals(3, festival.shows.size)
    }

    @Test
    fun aLoneShowStaysAConcert() {
        val nodes = groupIntoFestivals(listOf(show("1", "10-05-2026", "Sentrum Scene")))
        assertEquals(1, nodes.size)
        assertTrue(nodes[0] is TimelineNode.Concert)
    }

    @Test
    fun sameVenueMonthsApartDoesNotGroup() {
        val nodes = groupIntoFestivals(
            listOf(
                show("1", "25-06-2026", "Rockefeller"),
                show("2", "10-01-2026", "Rockefeller"),
            ),
        )
        assertEquals(2, nodes.size)
        assertTrue(nodes.all { it is TimelineNode.Concert })
    }

    @Test
    fun differentVenuesOnCloseDatesStaySeparate() {
        val nodes = groupIntoFestivals(
            listOf(
                show("1", "25-06-2026", "Ekebergsletta"),
                show("2", "24-06-2026", "Sentrum Scene"),
            ),
        )
        assertEquals(2, nodes.size)
        assertTrue(nodes.all { it is TimelineNode.Concert })
    }
}
