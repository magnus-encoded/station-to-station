package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.TourCommand
import io.github.magnusencoded.stationtostation.data.TourEvent
import io.github.magnusencoded.stationtostation.data.TourState
import io.github.magnusencoded.stationtostation.data.TourStep
import io.github.magnusencoded.stationtostation.data.runTour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TourScriptTest {
    private val progress = listOf(
        TourEvent.Acknowledged, TourEvent.CurtainPulled, TourEvent.BandPicked,
        TourEvent.GigAdded, TourEvent.RoomOpened, TourEvent.SwipedBack,
        TourEvent.ContactExchanged, TourEvent.PinchedOut, TourEvent.TicketImported,
        TourEvent.CalendarAdded, TourEvent.MapsOpened, TourEvent.TicketShown,
        TourEvent.CheckedIn, TourEvent.LogEntryWritten, TourEvent.GapRecorded,
        TourEvent.GossipSent, TourEvent.SetlistFilled, TourEvent.ReturnedFromPhotos,
        TourEvent.MediaAdded,
    )

    private fun statesAtEveryInteractiveStep(): List<TourState> {
        var state = runTour(TourState(), TourEvent.Started(online = true)).state
        val states = mutableListOf(state)
        for (event in progress) {
            state = runTour(state, event).state
            if (states.last().step != state.step) states += state
        }
        return states
    }

    @Test fun `offline start stays unstarted and emits nothing`() {
        val result = runTour(TourState(), TourEvent.Started(online = false))
        assertEquals(TourState(), result.state)
        assertTrue(result.commands.isEmpty())
    }

    @Test fun `skip at every shared step purges and finishes without a playlist`() {
        assertEquals((1..19).map { "S$it" }, statesAtEveryInteractiveStep().map { it.step!!.name })
        for (state in statesAtEveryInteractiveStep()) {
            val result = runTour(state, TourEvent.Skipped)
            assertTrue(result.state.finished)
            assertEquals(null, result.state.step)
            assertEquals(listOf(TourCommand.PurgeDemoWorld, TourCommand.MarkTourFinished), result.commands)
            assertFalse(result.state.pendingSpotifyRetry)
        }
    }

    @Test fun `resume returns to every saved step and does not repeat one-shot work`() {
        for (state in statesAtEveryInteractiveStep()) {
            val result = runTour(state, TourEvent.Resumed)
            assertEquals(state.step, result.state.step)
            assertTrue(result.commands.none { it == TourCommand.LookUpBand || it == TourCommand.ImportDemoTicket })
            if (state.step !in setOf(TourStep.S9, TourStep.S17)) {
                assertTrue(result.commands.isNotEmpty())
            }
        }
    }

    @Test fun `replay starts S1 in a fresh Demo world`() {
        val finished = TourState(finished = true, demoWorld = 4)
        val result = runTour(finished, TourEvent.ReplayRequested)
        assertEquals(TourStep.S1, result.state.step)
        assertEquals(5, result.state.demoWorld)
        assertEquals(listOf(TourCommand.ShowCoachMark(TourStep.S1)), result.commands)
    }

    @Test fun `out of order event changes nothing`() {
        val atS1 = runTour(TourState(), TourEvent.Started(online = true)).state
        assertEquals(TourTransitionSnapshot(atS1, emptyList()), runTour(atS1, TourEvent.GigAdded).snapshot())
    }

    private data class TourTransitionSnapshot(val state: TourState, val commands: List<TourCommand>)
    private fun io.github.magnusencoded.stationtostation.data.TourTransition.snapshot() =
        TourTransitionSnapshot(state, commands)
}
