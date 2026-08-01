package io.github.magnusencoded.setlist2spotify

import io.github.magnusencoded.setlist2spotify.ui.GigTimeState
import io.github.magnusencoded.setlist2spotify.ui.formatCountdown
import io.github.magnusencoded.setlist2spotify.ui.gigTimeState
import io.github.magnusencoded.setlist2spotify.ui.plannedStatus
import io.github.magnusencoded.setlist2spotify.ui.venueMapsQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

class GigTimeStateTest {

    private val today = LocalDate.of(2026, 7, 31)

    @Test
    fun `far in the future reads as the plain future node`() {
        assertEquals(GigTimeState.FUTURE, gigTimeState(today, today.plusDays(8)))
        assertEquals(GigTimeState.FUTURE, gigTimeState(today, today.plusYears(1)))
    }

    @Test
    fun `the future-approaching boundary sits at exactly a week out`() {
        assertEquals(GigTimeState.FUTURE, gigTimeState(today, today.plusDays(8)))
        assertEquals(GigTimeState.APPROACHING, gigTimeState(today, today.plusDays(7)))
    }

    @Test
    fun `within a week counts down`() {
        assertEquals(GigTimeState.APPROACHING, gigTimeState(today, today.plusDays(7)))
        assertEquals(GigTimeState.APPROACHING, gigTimeState(today, today.plusDays(1)))
    }

    @Test
    fun `the approaching-day-of boundary sits at today`() {
        assertEquals(GigTimeState.APPROACHING, gigTimeState(today, today.plusDays(1)))
        assertEquals(GigTimeState.DAY_OF, gigTimeState(today, today))
    }

    @Test
    fun `a gig today is day-of`() {
        assertEquals(GigTimeState.DAY_OF, gigTimeState(today, today))
    }

    @Test
    fun `a gig that has passed but has no setlist yet stays day-of`() {
        assertEquals(GigTimeState.DAY_OF, gigTimeState(today, today.minusDays(1)))
        assertEquals(GigTimeState.DAY_OF, gigTimeState(today, today.minusYears(1)))
    }

    @Test
    fun `countdown reads as a day count, singular for one day`() {
        assertEquals("1 day", formatCountdown(1L))
        assertEquals("2 days", formatCountdown(2L))
        assertEquals("7 days", formatCountdown(7L))
    }

    @Test
    fun `countdown refuses day-of and past, that's a different state's job`() {
        assertThrows(IllegalArgumentException::class.java) { formatCountdown(0L) }
        assertThrows(IllegalArgumentException::class.java) { formatCountdown(-1L) }
    }

    @Test
    fun `a planned node says how far off the night is`() {
        assertEquals("you're going", plannedStatus(today.plusMonths(2), today))
        assertEquals("in 6 days", plannedStatus(today.plusDays(6), today))
        assertEquals("in 1 day", plannedStatus(today.plusDays(1), today))
        assertEquals("tonight", plannedStatus(today, today))
    }

    @Test
    fun `a planned gig whose night has passed never claims to be tonight`() {
        // gigTimeState has no PAST — it answers DAY_OF for today and every day after,
        // so this is the guard that keeps a 2008 gig from announcing itself.
        assertEquals("no setlist yet", plannedStatus(today.minusDays(1), today))
        assertEquals("no setlist yet", plannedStatus(today.minusYears(18), today))
    }

    @Test
    fun `a gig with an unparseable date still says something true`() {
        assertEquals("you're going", plannedStatus(null, today))
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
