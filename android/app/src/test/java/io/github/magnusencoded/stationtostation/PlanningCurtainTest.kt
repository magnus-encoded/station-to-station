package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.ui.BillDetent
import io.github.magnusencoded.stationtostation.ui.GigDetent
import io.github.magnusencoded.stationtostation.ui.ImportDetent
import io.github.magnusencoded.stationtostation.ui.PlanningDoor
import io.github.magnusencoded.stationtostation.ui.armedDoor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The curtain's detents, which are the whole of what the gesture decides. How it *feels*
 * needs a hand and a phone; which door a given depth means does not, and that is the part
 * that can be wrong silently.
 */
class PlanningCurtainTest {

    @Test
    fun `a short pull means nothing, so it stays cheap to abandon`() {
        assertEquals(PlanningDoor.None, armedDoor(0f))
        assertEquals(PlanningDoor.None, armedDoor(GigDetent - 0.01f))
    }

    @Test
    fun `the detents are inclusive — reaching one arms it`() {
        assertEquals(PlanningDoor.Gig, armedDoor(GigDetent))
        assertEquals(PlanningDoor.Bill, armedDoor(BillDetent))
        assertEquals(PlanningDoor.Import, armedDoor(ImportDetent))
    }

    @Test
    fun `pulling further reaches the further door, and never skips one`() {
        assertEquals(PlanningDoor.Gig, armedDoor(BillDetent - 0.01f))
        assertEquals(PlanningDoor.Bill, armedDoor(ImportDetent - 0.01f))
        assertEquals(PlanningDoor.Import, armedDoor(1f))
    }

    @Test
    fun `the dead band is wide enough to be a band and not a hair`() {
        // A detent you can cross by accident is a threshold with extra steps. Each of
        // the four states needs a real share of the travel, or the pull is a coin flip.
        assertEquals(true, GigDetent >= 0.25f)
        assertEquals(true, BillDetent - GigDetent >= 0.2f)
        assertEquals(true, ImportDetent - BillDetent >= 0.2f)
        assertEquals(true, 1f - ImportDetent >= 0.05f)
    }

    @Test
    fun `overshoot past the end still means the last door`() {
        // onPostScroll clamps to pullMax, but the ratio is computed rather than clamped
        // again at the call site — so this is the guard on that arithmetic.
        assertEquals(PlanningDoor.Import, armedDoor(1.4f))
    }

    @Test
    fun `pulling back past a detent de-arms it`() {
        // onPostScroll now folds upward drag into the same running total instead of
        // ignoring it, so retreat has to read back to a lesser door, not stick.
        assertEquals(PlanningDoor.Bill, armedDoor(ImportDetent - 0.01f))
        assertEquals(PlanningDoor.Gig, armedDoor(BillDetent - 0.01f))
        assertEquals(PlanningDoor.None, armedDoor(GigDetent - 0.01f))
    }
}
