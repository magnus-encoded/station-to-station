package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.setlistfm.FmCity
import io.github.magnusencoded.stationtostation.data.setlistfm.FmCoords
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmVenue
import io.github.magnusencoded.stationtostation.ui.CITY_GATE_M
import io.github.magnusencoded.stationtostation.ui.VENUE_RADIUS_M
import io.github.magnusencoded.stationtostation.ui.atVenue
import io.github.magnusencoded.stationtostation.ui.canCheckInManually
import io.github.magnusencoded.stationtostation.ui.checkInCandidate
import io.github.magnusencoded.stationtostation.ui.gigTimeState
import io.github.magnusencoded.stationtostation.ui.GigTimeState
import io.github.magnusencoded.stationtostation.ui.metersBetween
import io.github.magnusencoded.stationtostation.ui.withinCheckInWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** Tøyenparken, Oslo — the real gig this was written against. */
private val TOYEN = 59.9219 to 10.7787
private val OSLO_CITY = 59.9127 to 10.7461

private fun gig(
    id: String,
    date: String,
    coords: Pair<Double, Double>? = OSLO_CITY,
) = FmSetlist(
    id = id,
    eventDate = date,
    venue = FmVenue(
        name = "Tøyenparken",
        city = FmCity(name = "Oslo", coords = coords?.let { FmCoords(it.first, it.second) }),
    ),
)

class CheckInWindowTest {

    private val night = LocalDate.of(2026, 8, 13)

    @Test fun `doors open at midnight on the day`() {
        assertTrue(withinCheckInWindow(night.atTime(0, 0), night))
        assertTrue(withinCheckInWindow(night.atTime(21, 30), night))
    }

    @Test fun `a set that runs past midnight is still that night`() {
        assertTrue(withinCheckInWindow(night.plusDays(1).atTime(1, 30), night))
    }

    @Test fun `the window shuts in the morning`() {
        assertFalse(withinCheckInWindow(night.plusDays(1).atTime(6, 0), night))
        assertFalse(withinCheckInWindow(night.plusDays(1).atTime(9, 0), night))
    }

    @Test fun `the night before is too early`() {
        assertFalse(withinCheckInWindow(night.minusDays(1).atTime(23, 59), night))
    }

    @Test fun `a gig with no date can never be checked into`() {
        assertFalse(withinCheckInWindow(night.atTime(21, 0), null))
    }

    /**
     * gigTimeState draws its DAY_OF/PAST line from this same window (#55), so a gig
     * from 2008 reads PAST and offers no check-in. The two can't drift apart.
     */
    @Test fun `a long past gig is past, and cannot be checked into`() {
        val old = LocalDate.of(2008, 6, 14)
        val now = LocalDateTime.of(2026, 8, 13, 21, 0)
        assertEquals(GigTimeState.PAST, gigTimeState(now, old))
        assertFalse(withinCheckInWindow(now, old))
        assertFalse(canCheckInManually(gig("old", "14-06-2008"), now))
    }
}

class CheckInDistanceTest {

    @Test fun `haversine is symmetric and about right`() {
        val d = metersBetween(TOYEN.first, TOYEN.second, OSLO_CITY.first, OSLO_CITY.second)
        // Tøyenparken to Oslo city centre is a couple of kilometres.
        assertTrue("$d", d in 2_000.0..3_500.0)
        assertEquals(
            d,
            metersBetween(OSLO_CITY.first, OSLO_CITY.second, TOYEN.first, TOYEN.second),
            0.001,
        )
        assertEquals(0.0, metersBetween(TOYEN.first, TOYEN.second, TOYEN.first, TOYEN.second), 0.001)
    }

    @Test fun `the far side of the festival field is still at the venue`() {
        // ~400 m north: a big field, one geocoded point at its edge.
        val farSide = (TOYEN.first + 0.0036) to TOYEN.second
        assertTrue(metersBetween(TOYEN.first, TOYEN.second, farSide.first, farSide.second) < VENUE_RADIUS_M)
        assertTrue(atVenue(farSide, TOYEN))
    }

    @Test fun `the city centre is not the venue`() {
        assertFalse(atVenue(OSLO_CITY, TOYEN))
    }
}

class CheckInCandidateTest {

    private val duringTheGig = LocalDateTime.of(2026, 8, 13, 21, 0)
    private val tonight = listOf(gig("tonight", "13-08-2026"))

    @Test fun `in the city on the night is a candidate`() {
        assertEquals("tonight", checkInCandidate(tonight, duringTheGig, TOYEN)?.id)
    }

    @Test fun `no fix is no prompt`() {
        assertNull(checkInCandidate(tonight, duringTheGig, null))
    }

    @Test fun `a gig with no coordinates at all is skipped, not guessed at`() {
        val unlocatable = listOf(gig("nowhere", "13-08-2026", coords = null))
        assertNull(checkInCandidate(unlocatable, duringTheGig, TOYEN))
    }

    @Test fun `right city, wrong night`() {
        assertNull(checkInCandidate(tonight, duringTheGig.minusDays(3), TOYEN))
    }

    @Test fun `right night, wrong city`() {
        // Bergen — well past the coarse gate from Oslo.
        val bergen = 60.3913 to 5.3221
        assertTrue(metersBetween(bergen.first, bergen.second, OSLO_CITY.first, OSLO_CITY.second) > CITY_GATE_M)
        assertNull(checkInCandidate(tonight, duringTheGig, bergen))
    }

    @Test fun `an old gig at the same venue never wins`() {
        val gigs = listOf(gig("2008", "14-06-2008"), gig("tonight", "13-08-2026"))
        assertEquals("tonight", checkInCandidate(gigs, duringTheGig, TOYEN)?.id)
    }
}
