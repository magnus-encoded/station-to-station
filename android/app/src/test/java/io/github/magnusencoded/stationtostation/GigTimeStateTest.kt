package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.StoredAttendance
import io.github.magnusencoded.stationtostation.ui.GigTimeState
import io.github.magnusencoded.stationtostation.ui.formatCountdown
import io.github.magnusencoded.stationtostation.ui.gigStatus
import io.github.magnusencoded.stationtostation.ui.gigTimeState
import io.github.magnusencoded.stationtostation.ui.isPlanned
import io.github.magnusencoded.stationtostation.ui.plannedStatus
import io.github.magnusencoded.stationtostation.ui.setlistStatus
import io.github.magnusencoded.stationtostation.ui.showsMediaBlock
import io.github.magnusencoded.stationtostation.ui.venueMapsQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

class GigTimeStateTest {

    private val today = LocalDate.of(2026, 7, 31)
    // Midday, so "the gig date" and "the night-of window" are separate cases.
    private val now = today.atTime(12, 0)

    @Test
    fun `far in the future reads as the plain future node`() {
        assertEquals(GigTimeState.FUTURE, gigTimeState(now, today.plusDays(8)))
        assertEquals(GigTimeState.FUTURE, gigTimeState(now, today.plusYears(1)))
    }

    @Test
    fun `the future-approaching boundary sits at exactly a week out`() {
        assertEquals(GigTimeState.FUTURE, gigTimeState(now, today.plusDays(8)))
        assertEquals(GigTimeState.APPROACHING, gigTimeState(now, today.plusDays(7)))
    }

    @Test
    fun `within a week counts down`() {
        assertEquals(GigTimeState.APPROACHING, gigTimeState(now, today.plusDays(7)))
        assertEquals(GigTimeState.APPROACHING, gigTimeState(now, today.plusDays(1)))
    }

    @Test
    fun `the approaching-day-of boundary sits at today`() {
        assertEquals(GigTimeState.APPROACHING, gigTimeState(now, today.plusDays(1)))
        assertEquals(GigTimeState.DAY_OF, gigTimeState(now, today))
    }

    @Test
    fun `a gig today is day-of`() {
        assertEquals(GigTimeState.DAY_OF, gigTimeState(now, today))
    }

    @Test
    fun `the night-of window holds through 6am, then the gig is past`() {
        // A show that ran past midnight is still tonight to everyone who was at it,
        // so the DAY_OF/PAST line falls at 06:00 the next morning — not midnight.
        assertEquals(GigTimeState.DAY_OF, gigTimeState(today.plusDays(1).atTime(5, 59), today))
        assertEquals(GigTimeState.PAST, gigTimeState(today.plusDays(1).atTime(6, 0), today))
    }

    @Test
    fun `a gig whose night has been and gone is past`() {
        assertEquals(GigTimeState.PAST, gigTimeState(now, today.minusDays(1)))
        assertEquals(GigTimeState.PAST, gigTimeState(now, today.minusYears(1)))
    }

    @Test
    fun `countdown humanises, coarser as the gig recedes`() {
        assertEquals("tomorrow", formatCountdown(1L))
        assertEquals("in 13 days", formatCountdown(13L))
        assertEquals("in 2 weeks", formatCountdown(14L))
        assertEquals("in 4 weeks", formatCountdown(30L))
        assertEquals("in 1 month", formatCountdown(31L))
        assertEquals("in 2 months", formatCountdown(60L))
        // "in 377 days" is absurd; a year out reads in months.
        assertEquals("in 12 months", formatCountdown(377L))
    }

    @Test
    fun `countdown refuses day-of and past, that's a different state's job`() {
        assertThrows(IllegalArgumentException::class.java) { formatCountdown(0L) }
        assertThrows(IllegalArgumentException::class.java) { formatCountdown(-1L) }
    }

    @Test
    fun `a planned node says how far off the night is`() {
        assertEquals("in 2 months", plannedStatus(today.plusMonths(2), now))
        assertEquals("in 6 days", plannedStatus(today.plusDays(6), now))
        assertEquals("tomorrow", plannedStatus(today.plusDays(1), now))
        assertEquals("tonight", plannedStatus(today, now))
    }

    @Test
    fun `a planned gig whose night has passed never claims to be tonight`() {
        assertEquals("no setlist yet", plannedStatus(today.minusDays(1), now))
        assertEquals("no setlist yet", plannedStatus(today.minusYears(18), now))
    }

    // --- The night has passed: the record answers, not the calendar (#127) -------

    /** Three days after Ringnes Festival's last night — every gig below is behind us. */
    private val afterRingnes = LocalDate.of(2026, 8, 10).atTime(12, 0)

    @Test
    fun `no setlist yet is a fact about the record, not about the date`() {
        assertEquals("no setlist yet", setlistStatus(0))
        assertEquals("1 songs", setlistStatus(1))
        assertEquals("15 songs", setlistStatus(15))
    }

    @Test
    fun `a past night with songs stops claiming it has none`() {
        assertEquals("15 songs", plannedStatus(today.minusDays(1), now, songCount = 15))
    }

    @Test
    fun `a plan is a plan only while the claim says planned`() {
        assertEquals(true, isPlanned(StoredAttendance.Provenance.PLANNED))
        assertEquals(false, isPlanned(StoredAttendance.Provenance.ATTENDED))
        assertEquals(false, isPlanned(StoredAttendance.Provenance.CHECKED_IN))
        // An imported night nobody ever claimed anything about isn't a plan either.
        assertEquals(false, isPlanned(null))
    }

    @Test
    fun `Valkyrien Allstars, checked in, fifteen songs, stops saying no setlist yet`() {
        // 7 Aug 2026: past, checked into, and the record holds all fifteen titles —
        // the exact contradiction seen on the device, chip above its own setlist.
        val night = LocalDate.of(2026, 8, 7)
        val planned = isPlanned(StoredAttendance.Provenance.CHECKED_IN)
        assertEquals(false, planned)
        assertEquals("15 songs", gigStatus(planned, night, songCount = 15, now = afterRingnes))
    }

    @Test
    fun `Oyvind Holm, checked in with an empty record, keeps saying no setlist yet`() {
        // Same night, same venue, setlist.fm id 53705b8d — and zero songs stored.
        val night = LocalDate.of(2026, 8, 7)
        val planned = isPlanned(StoredAttendance.Provenance.CHECKED_IN)
        assertEquals("no setlist yet", gigStatus(planned, night, songCount = 0, now = afterRingnes))
    }

    @Test
    fun `a genuine plan ahead still counts down, songs or not`() {
        assertEquals("tomorrow", gigStatus(planned = true, date = today.plusDays(1), songCount = 0, now = now))
        assertEquals("tonight", gigStatus(planned = true, date = today, songCount = 0, now = now))
        assertEquals("you're going", gigStatus(planned = true, date = null, songCount = 0, now = now))
    }

    @Test
    fun `a gig with an unparseable date still says something true`() {
        assertEquals("you're going", plannedStatus(null, now))
    }

    @Test
    fun `the media block is absent on a night that hasn't happened yet`() {
        assertEquals(false, showsMediaBlock(planned = true, checkedIn = false))
        assertEquals(true, showsMediaBlock(planned = false, checkedIn = false))
    }

    @Test
    fun `checking in earns the media block back even while still planned`() {
        assertEquals(true, showsMediaBlock(planned = true, checkedIn = true))
    }

    @Test
    fun `maps query joins venue and city`() {
        assertEquals("Rockefeller, Oslo", venueMapsQuery("Rockefeller", "Oslo"))
    }

    @Test
    fun `maps query tolerates a missing half`() {
        assertEquals("Rockefeller", venueMapsQuery("Rockefeller", null))
        assertEquals("Oslo", venueMapsQuery(null, "Oslo"))
        assertEquals("Rockefeller", venueMapsQuery("Rockefeller", "  "))
    }

    @Test
    fun `maps query is null when there's nothing worth searching for`() {
        assertNull(venueMapsQuery(null, null))
        assertNull(venueMapsQuery("  ", ""))
    }
}
