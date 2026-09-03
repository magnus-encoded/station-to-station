package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.SETLISTFM_ADD_URL
import io.github.magnusencoded.stationtostation.data.StoredLog
import io.github.magnusencoded.stationtostation.data.filingFields
import io.github.magnusencoded.stationtostation.data.isLocal
import io.github.magnusencoded.stationtostation.data.localGigSetlist
import io.github.magnusencoded.stationtostation.data.setlistEditEntry
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** A local night, and what crosses the app switch into setlist.fm's own form. */
class FilingTest {

    // --- The Gig a local night becomes -------------------------------------------

    @Test
    fun `a local gig carries its own id and no setlist-fm page`() {
        val gig = localGigSetlist("local-1", "Velvet Ditch", LocalDate.of(2026, 8, 6), "Nordlys Fields 2026", "Norway")
        assertEquals("local-1", gig.id)
        assertEquals("06-08-2026", gig.eventDate)
        assertEquals("Velvet Ditch", gig.artist?.name)
        assertEquals("Nordlys Fields 2026", gig.venue?.name)
        assertNull(gig.url)
        assertTrue(gig.isLocal())
    }

    @Test
    fun `a local gig never carries songs — those are the Log's, not a setlist's`() {
        val gig = localGigSetlist("local-1", "Halden Drift", LocalDate.of(2026, 8, 7), "Nordlys Fields", "")
        assertTrue(gig.performed().isEmpty())
        assertNull(gig.sets)
    }

    @Test
    fun `a night with no room typed in has no venue, rather than an empty string`() {
        // Null rather than "": two roomless nights must not read as sharing a room.
        val gig = localGigSetlist("g1", "Paper Cranes", LocalDate.of(2026, 8, 7), venue = "", city = "Kalmarhavn")
        assertNull(gig.venue?.name)
        assertEquals("Kalmarhavn", gig.venue?.city?.name)
    }

    @Test
    fun `a night with no room yet reads as the town it was in`() {
        val gig = localGigSetlist("g1", "Paper Cranes", LocalDate.of(2026, 8, 7), venue = "", city = "Kalmarhavn")
        assertEquals("Kalmarhavn", gig.venueLine())
    }

    @Test
    fun `a night with nothing to say about where it was still says so`() {
        val gig = localGigSetlist("g1", "Paper Cranes", LocalDate.of(2026, 8, 7), venue = "", city = "")
        assertEquals("Unknown venue", gig.venueLine())
    }

    @Test
    fun `a venue that is known is still the first thing the line says`() {
        val gig = localGigSetlist("g1", "Paper Cranes", LocalDate.of(2026, 8, 7), "Hollowmoor Park", "Vardhavn")
        assertEquals("Hollowmoor Park, Vardhavn", gig.venueLine())
    }

    // --- What crosses the app switch into setlist.fm's form ---------------------

    @Test
    fun `the filing carries every field the form asks for, in the form's own order`() {
        // Order read off setlist.fm's add form on the Pixel 2026-08-06. Date before
        // Venue is load-bearing: the venue field is disabled until a date is set.
        val gig = localGigSetlist("local-1", "Halden Drift", LocalDate.of(2026, 8, 7), "Nordlys Fields 2026", "Kalmarhavn")
        val fields = filingFields(gig, StoredLog(songs = listOf("A", "B")))
        assertEquals(listOf("Artist", "Date", "Venue", "City", "Songs"), fields.map { it.label })
        assertEquals("Halden Drift", fields[0].value)
        assertEquals("07-08-2026", fields[1].value)
        assertEquals("Nordlys Fields 2026", fields[2].value)
        assertEquals("Kalmarhavn", fields[3].value)
        // The songs field hands over the paste, not the summary line beside it.
        assertEquals("A\nB", fields[4].value)
        assertEquals("2 songs, in order", fields[4].shown)
    }

    @Test
    fun `the date reads the way a calendar does, because it is picked and not pasted`() {
        // setlist.fm opens a month grid for this one, so no string can land in it. The
        // value shown is the one you go looking for in that grid.
        val gig = localGigSetlist("local-1", "Halden Drift", LocalDate.of(2026, 8, 7), "Nordlys Fields", "")
        val date = filingFields(gig, StoredLog()).first { it.label == "Date" }
        assertEquals("7 August 2026", date.shown)
        assertEquals("07-08-2026", date.value)
    }

    @Test
    fun `a field nobody typed in is left out rather than offered blank`() {
        // A fifth, empty, value would be a value to paste that says nothing — worse
        // than the absence it is hiding.
        val gig = localGigSetlist("local-1", "Paper Cranes", LocalDate.of(2026, 8, 7), "Nordlys Fields", "")
        val fields = filingFields(gig, StoredLog(songs = listOf("A")))
        assertEquals(listOf("Artist", "Date", "Venue", "Songs"), fields.map { it.label })
    }

    @Test
    fun `nothing logged still files the night itself, minus the songs`() {
        val gig = localGigSetlist("local-1", "Paper Cranes", LocalDate.of(2026, 8, 7), "Nordlys Fields", "Kalmarhavn")
        val fields = filingFields(gig, StoredLog())
        assertEquals(listOf("Artist", "Date", "Venue", "City"), fields.map { it.label })
    }

    @Test
    fun `the songs line counts gaps in, because the paste does`() {
        // "1 song" beside a two-line paste is the small disagreement that makes
        // someone distrust the whole handoff.
        val gig = localGigSetlist("local-1", "Halden Drift", LocalDate.of(2026, 8, 7), "Nordlys Fields", "")
        val songs = filingFields(gig, StoredLog(songs = listOf("A", ""))).first { it.label == "Songs" }
        assertEquals("2 songs, in order · 1 unnamed", songs.shown)
        assertEquals("A\n@Unknown[]", songs.value)
    }

    // --- Where the Historian is sent -----------------------------------------------

    @Test
    fun `a night setlist-fm already has goes to its own page, never a built edit url`() {
        val known = FmSetlist(id = "63a80d2f", url = "https://www.setlist.fm/setlist/x-63a80d2f.html")
        assertEquals("https://www.setlist.fm/setlist/x-63a80d2f.html", setlistEditEntry(known))
    }

    @Test
    fun `a night setlist-fm has never heard of goes to the add flow`() {
        val stub = localGigSetlist("local-1", "Paper Cranes", LocalDate.of(2026, 8, 7), "Nordlys Fields", "")
        assertEquals(SETLISTFM_ADD_URL, setlistEditEntry(stub))
    }
}
