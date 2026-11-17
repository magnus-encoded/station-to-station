package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.MatchLevel
import io.github.magnusencoded.stationtostation.data.SetlistFmCandidate
import io.github.magnusencoded.stationtostation.data.StoredSetlistFmHit
import io.github.magnusencoded.stationtostation.data.StoredSetlistFmLookup
import io.github.magnusencoded.stationtostation.data.setlistfm.FmArtist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmCity
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmVenue
import io.github.magnusencoded.stationtostation.data.setlistfm.TicketSetlistFm
import io.github.magnusencoded.stationtostation.data.setlistfm.TicketSetlistFmAnswer
import io.github.magnusencoded.stationtostation.data.setlistfm.asStoredHit
import io.github.magnusencoded.stationtostation.data.setlistfm.chipHits
import io.github.magnusencoded.stationtostation.data.setlistfm.line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #531's review prompt, decided without the dialog: which candidates it shows, and what
 * Save with one ticked — or with "None of these" — writes. Everything here is synthetic.
 */
class TicketSetlistFmTest {

    private fun hit(id: String, venue: String = "The Hall", city: String = "Oslo") = FmSetlist(
        id = id,
        eventDate = "14-03-2031",
        artist = FmArtist(mbid = "mbid-1", name = "The Examples"),
        venue = FmVenue(name = venue, city = FmCity(name = city)),
        url = "https://www.setlist.fm/setlist/$id.html",
    )

    private fun candidate(id: String, venue: MatchLevel? = MatchLevel.Weak) =
        SetlistFmCandidate(hit(id), MatchLevel.Strong, MatchLevel.Strong, venue)

    private val offered = TicketSetlistFm(
        candidates = listOf(candidate("a1"), candidate("b2")),
        preselectedId = null,
        artist = "The Examples",
        date = "14-03-2031",
        lookedUpAt = 1_000L,
    )

    @Test
    fun `candidates show for the search that found them`() {
        assertTrue(offered.offeredFor("The Examples", "14-03-2031"))
        assertTrue(offered.offeredFor(" The Examples ", "14-03-2031"))
    }

    @Test
    fun `editing the artist or the date hides the candidates`() {
        assertFalse(offered.offeredFor("The Example", "14-03-2031"))
        assertFalse(offered.offeredFor("The Examples", "15-03-2031"))
        assertFalse(offered.offeredFor("The Examples", "not a date"))
    }

    @Test
    fun `a lookup that found nothing offers nothing`() {
        assertFalse(offered.copy(candidates = emptyList()).offeredFor("The Examples", "14-03-2031"))
    }

    @Test
    fun `choosing a candidate takes it and rejects nothing`() {
        val answer = offered.answer("The Examples", "14-03-2031", "b2")
        assertEquals("b2", answer.chosen?.id)
        assertEquals(emptyList<String>(), answer.rejectedIds)
        assertEquals(1_000L, answer.lookedUpAt)
    }

    @Test
    fun `none of these rejects every candidate offered and stamps`() {
        val answer = offered.answer("The Examples", "14-03-2031", null)
        assertNull(answer.chosen)
        assertEquals(listOf("a1", "b2"), answer.rejectedIds)
        val stored = answer.applyTo(StoredSetlistFmLookup(rejectedIds = listOf("z9")))
        assertEquals(listOf("z9", "a1", "b2"), stored.rejectedIds)
        assertEquals(1_000L, stored.lastLookupAt)
    }

    @Test
    fun `an id not among the candidates reads as none of these`() {
        assertEquals(listOf("a1", "b2"), offered.answer("The Examples", "14-03-2031", "elsewhere").rejectedIds)
    }

    @Test
    fun `an edited search records no rejection and no stamp`() {
        val answer = offered.answer("Other Band", "14-03-2031", null)
        assertEquals(TicketSetlistFmAnswer.UNASKED, answer)
        assertFalse(answer.recordsAnything)
        val before = StoredSetlistFmLookup(lastLookupAt = 5L)
        assertEquals(before, answer.applyTo(before))
    }

    @Test
    fun `a lookup that found nothing still stamps the landed gig`() {
        val answer = offered.copy(candidates = emptyList()).answer("The Examples", "14-03-2031", null)
        assertTrue(answer.recordsAnything)
        assertEquals(emptyList<String>(), answer.rejectedIds)
        assertEquals(1_000L, answer.applyTo(StoredSetlistFmLookup()).lastLookupAt)
    }

    @Test
    fun `stamping never moves a later lookup back`() {
        val answer = offered.answer("The Examples", "14-03-2031", null)
        assertEquals(9_000L, answer.applyTo(StoredSetlistFmLookup(lastLookupAt = 9_000L)).lastLookupAt)
    }

    @Test
    fun `the chip draws only the hits still pending`() {
        val a = StoredSetlistFmHit.of(candidate("a1"))
        val b = StoredSetlistFmHit.of(candidate("b2"))
        val lookup = StoredSetlistFmLookup(pendingHitIds = listOf("b2"), pendingHits = listOf(a, b))
        assertEquals(listOf(b), lookup.chipHits())
        assertEquals(emptyList<StoredSetlistFmHit>(), StoredSetlistFmLookup(pendingHitIds = listOf("x")).chipHits())
    }

    @Test
    fun `a row with no question reads artist, venue and date`() {
        val a = StoredSetlistFmHit.of(candidate("a1"))
        assertEquals("The Examples — The Hall, Oslo — 14-03-2031", a.line())
        assertEquals("The Examples — 14-03-2031", a.copy(venue = "", city = " ").line())
    }

    @Test
    fun `a fetched hit stores with no venue level`() {
        val stored = hit("c3").asStoredHit()
        assertEquals("c3", stored.id)
        assertEquals("The Hall", stored.venue)
        assertNull(stored.venueLevel)
    }
}
