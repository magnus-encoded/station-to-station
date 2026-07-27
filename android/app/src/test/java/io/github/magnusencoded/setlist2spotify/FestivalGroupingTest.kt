package io.github.magnusencoded.setlist2spotify

import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmArtist
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmSetlist
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmVenue
import io.github.magnusencoded.setlist2spotify.ui.TimelineNode
import io.github.magnusencoded.setlist2spotify.ui.groupIntoFestivals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FestivalGroupingTest {

    private fun show(id: String, date: String, venue: String) = FmSetlist(
        id = id,
        eventDate = date, // dd-MM-yyyy
        artist = FmArtist(name = "Artist $id"),
        venue = FmVenue(name = venue),
    )

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
        assertEquals("Ekebergsletta", festival.venue)
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
