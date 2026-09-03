package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.ui.GigLeaf
import io.github.magnusencoded.stationtostation.ui.gigLeaf
import io.github.magnusencoded.stationtostation.ui.nightWindow
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** Which action leads, on the clock — the boundaries `gigLeaf` itself decides on. */
class GigLeafTest {

    private val night = nightWindow(LocalDate.of(2026, 8, 6))

    @Test
    fun `before the night, the leaf is still planning`() {
        assertEquals(GigLeaf.PLAN, gigLeaf(LocalDate.of(2026, 8, 5).atTime(20, 0), night))
    }

    @Test
    fun `during the night the leaf is capture, and stays capture past midnight`() {
        assertEquals(GigLeaf.CAPTURE, gigLeaf(LocalDate.of(2026, 8, 6).atTime(21, 0), night))
        assertEquals(GigLeaf.CAPTURE, gigLeaf(LocalDate.of(2026, 8, 7).atTime(2, 0), night))
    }

    @Test
    fun `a check-in opens capture even before the window says the night has started`() {
        val early = LocalDate.of(2026, 8, 5).atTime(23, 0)
        assertEquals(GigLeaf.PLAN, gigLeaf(early, night))
        assertEquals(GigLeaf.CAPTURE, gigLeaf(early, night, checkedIn = true))
    }

    @Test
    fun `after the window closes the leaf becomes publish — but only the leaf`() {
        assertEquals(GigLeaf.PUBLISH, gigLeaf(LocalDate.of(2026, 8, 7).atTime(11, 0), night))
        // And a check-in cannot drag it back: being there yesterday does not reopen
        // the night. The Log itself stays editable regardless — that is the screen's
        // rule, not this function's, precisely because it is not conditional.
        assertEquals(
            GigLeaf.PUBLISH,
            gigLeaf(LocalDate.of(2026, 8, 7).atTime(11, 0), night, checkedIn = true),
        )
    }

    @Test
    fun `an undated gig has no clock to follow and keeps the plan-ahead leaf`() {
        assertEquals(GigLeaf.PLAN, gigLeaf(LocalDate.of(2026, 8, 6).atTime(21, 0), null))
    }
}
