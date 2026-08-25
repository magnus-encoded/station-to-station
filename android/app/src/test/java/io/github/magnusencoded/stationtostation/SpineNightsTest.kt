package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.StoredAttendance
import io.github.magnusencoded.stationtostation.data.setlistfm.FmArtist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmVenue
import io.github.magnusencoded.stationtostation.data.plannedLane
import io.github.magnusencoded.stationtostation.data.spineNights
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two lanes, and the night that fell between them.
 *
 * A **Gig** leaves [plannedLane] the moment it stops being a plan, so if the Spine is
 * setlist.fm's **Attended** list alone, a night I checked into that setlist.fm has
 * never heard of is on neither list and draws nowhere. That is not hypothetical: it
 * happened to Nick Cave at Øya 2026, holding a fifteen-song **Log** and seven
 * photographs, on a device with both lanes working exactly as written.
 */
class SpineNightsTest {

    private fun show(id: String, date: String, venue: String = "Tøyenparken") = FmSetlist(
        id = id,
        eventDate = date, // dd-MM-yyyy
        artist = FmArtist(name = "Artist $id"),
        venue = FmVenue(name = venue),
    )

    private fun claim(provenance: String) = StoredAttendance(provenance = provenance)

    private val checkedIn = claim(StoredAttendance.Provenance.CHECKED_IN)
    private val planned = claim(StoredAttendance.Provenance.PLANNED)

    @Test
    fun aCheckedInNightSetlistFmNeverHeardOfIsOnTheSpine() {
        val attended = listOf(show("wilco", "13-08-2026"))
        val local = listOf(show("nickcave", "13-08-2026"))

        val spine = spineNights(attended, local, mapOf("nickcave" to checkedIn))

        assertEquals(listOf("wilco", "nickcave"), spine.map { it.id })
    }

    @Test
    fun theSameNightIsNotOnBothLanes() {
        // The exact hand-off: whichever lane takes it, precisely one does.
        val local = listOf(show("nickcave", "13-08-2026"))

        for (claim in listOf(checkedIn, planned)) {
            val attendance = mapOf("nickcave" to claim)
            val onSpine = spineNights(emptyList(), local, attendance).size
            val onFuture = plannedLane(local, attendance).size
            assertEquals("one lane draws it, and only one", 1, onSpine + onFuture)
        }
    }

    @Test
    fun aPlanIsLeftToTheFutureLane() {
        val local = listOf(show("nickcave", "13-08-2026"))

        assertEquals(emptyList<String>(), spineNights(emptyList(), local, mapOf("nickcave" to planned)).map { it.id })
    }

    @Test
    fun animportedCopyWinsOverTheLocalOne() {
        // Same night, both lists: one row, and the published record is the one kept.
        val attended = listOf(show("nickcave", "13-08-2026", venue = "Tøyenparken"))
        val local = listOf(show("nickcave", "13-08-2026", venue = "typed by hand"))

        val spine = spineNights(attended, local, mapOf("nickcave" to checkedIn))

        assertEquals(1, spine.size)
        assertEquals("Tøyenparken", spine.single().venue?.name)
    }

    @Test
    fun theSpineStaysNewestFirst() {
        // The whole timeline reads newest first, and groupIntoFestivals keeps the
        // order it is given — an out-of-order insert would surface as a jumbled Line.
        val attended = listOf(show("b", "13-08-2026"), show("a", "01-01-2020"))
        val local = listOf(show("c", "14-08-2026"))

        val spine = spineNights(attended, local, mapOf("c" to checkedIn))

        assertEquals(listOf("c", "b", "a"), spine.map { it.id })
    }

    @Test
    fun nothingEvidencedLocallyLeavesTheAttendedListUntouched() {
        val attended = listOf(show("b", "13-08-2026"), show("a", "01-01-2020"))

        assertEquals(attended, spineNights(attended, emptyList(), emptyMap()))
    }
}
