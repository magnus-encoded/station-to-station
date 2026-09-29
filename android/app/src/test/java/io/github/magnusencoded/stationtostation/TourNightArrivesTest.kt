package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.TourState
import io.github.magnusencoded.stationtostation.data.TourStep
import io.github.magnusencoded.stationtostation.data.location
import io.github.magnusencoded.stationtostation.data.now
import io.github.magnusencoded.stationtostation.ui.GigTimeState
import io.github.magnusencoded.stationtostation.ui.canCheckInManually
import io.github.magnusencoded.stationtostation.ui.gigTimeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class TourNightArrivesTest {
    private val night = LocalDate.of(2026, 10, 1)
    private val realNow = LocalDateTime.of(2030, 1, 2, 12, 0)

    @Test fun `ordinary time rules see approaching and doors through the demo clock`() {
        val approaching = TourState(step = TourStep.S10, demoNow = night.minusDays(1).atTime(12, 0).toString())
        val doors = TourState(step = TourStep.S12, demoNow = night.atTime(19, 0).toString())
        val gig = io.github.magnusencoded.stationtostation.data.localGigSetlist(
            "demo", "Demo band", night, "Demo venue", "",
        )

        assertEquals(GigTimeState.APPROACHING, gigTimeState(approaching.now(realNow), night))
        assertEquals(GigTimeState.DAY_OF, gigTimeState(doors.now(realNow), night))
        assertFalse(canCheckInManually(gig, approaching.now(realNow)))
        assertTrue(canCheckInManually(gig, doors.now(realNow)))
    }

    @Test fun `demo location and time stop overriding the device after Tour ends`() {
        val demoNow = night.atTime(19, 0)
        val device = 60.3913 to 5.3221
        val active = TourState(
            step = TourStep.S13,
            demoNow = demoNow.toString(),
            demoVenueLat = 59.9139,
            demoVenueLon = 10.7522,
        )
        val finished = active.copy(step = null, finished = true)

        assertEquals(demoNow, active.now(realNow))
        assertEquals(59.9139 to 10.7522, active.location(device))
        assertEquals(realNow, finished.now(realNow))
        assertEquals(device, finished.location(device))
    }
}
