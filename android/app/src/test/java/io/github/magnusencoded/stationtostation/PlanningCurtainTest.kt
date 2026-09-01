package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.ui.ProgrammeDetent
import io.github.magnusencoded.stationtostation.ui.GigDetent
import io.github.magnusencoded.stationtostation.ui.ImportDetent
import io.github.magnusencoded.stationtostation.ui.PlanningDoor
import io.github.magnusencoded.stationtostation.ui.armedDoor
import io.github.magnusencoded.stationtostation.ui.curtainTakes
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
        assertEquals(PlanningDoor.Programme, armedDoor(ProgrammeDetent))
        assertEquals(PlanningDoor.Import, armedDoor(ImportDetent))
    }

    @Test
    fun `pulling further reaches the further door, and never skips one`() {
        assertEquals(PlanningDoor.Gig, armedDoor(ProgrammeDetent - 0.01f))
        assertEquals(PlanningDoor.Programme, armedDoor(ImportDetent - 0.01f))
        assertEquals(PlanningDoor.Import, armedDoor(1f))
    }

    @Test
    fun `the dead band is wide enough to be a band and not a hair`() {
        // A detent you can cross by accident is a threshold with extra steps. Each of
        // the four states needs a real share of the travel, or the pull is a coin flip.
        assertEquals(true, GigDetent >= 0.25f)
        assertEquals(true, ProgrammeDetent - GigDetent >= 0.2f)
        assertEquals(true, ImportDetent - ProgrammeDetent >= 0.2f)
        assertEquals(true, 1f - ImportDetent >= 0.05f)
    }

    @Test
    fun `overshoot past the end still means the last door`() {
        // onPostScroll clamps to pullMax, but the ratio is computed rather than clamped
        // again at the call site — so this is the guard on that arithmetic.
        assertEquals(PlanningDoor.Import, armedDoor(1.4f))
    }

    @Test
    fun `an open gap takes the upward drag, so it can close at all`() {
        // The bug: the list consumed every upward delta itself, so the gap stayed open
        // with a door armed and the timeline scrolled behind it.
        assertEquals(-40f, curtainTakes(dy = -40f, pull = 100f), 0.01f)
    }

    @Test
    fun `it takes only as much as shuts it — the rest is the list's scroll`() {
        // 20px of gap at half speed is 40px of drag. A longer drag closes the curtain
        // and keeps going, rather than dying in it.
        assertEquals(-40f, curtainTakes(dy = -300f, pull = 20f), 0.01f)
    }

    @Test
    fun `a shut curtain and a downward drag are none of its business`() {
        assertEquals(0f, curtainTakes(dy = -50f, pull = 0f), 0.01f)
        assertEquals(0f, curtainTakes(dy = 50f, pull = 100f), 0.01f)
    }

    @Test
    fun `pulling back past a detent de-arms it`() {
        // onPostScroll now folds upward drag into the same running total instead of
        // ignoring it, so retreat has to read back to a lesser door, not stick.
        assertEquals(PlanningDoor.Programme, armedDoor(ImportDetent - 0.01f))
        assertEquals(PlanningDoor.Gig, armedDoor(ProgrammeDetent - 0.01f))
        assertEquals(PlanningDoor.None, armedDoor(GigDetent - 0.01f))
    }
}
