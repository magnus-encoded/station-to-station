package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.TourCommand
import io.github.magnusencoded.stationtostation.data.TourEvent
import io.github.magnusencoded.stationtostation.data.TourState
import io.github.magnusencoded.stationtostation.data.TourStep
import io.github.magnusencoded.stationtostation.data.runTour
import io.github.magnusencoded.stationtostation.data.tourPlaylistDescription
import io.github.magnusencoded.stationtostation.data.tourPlaylistName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TourScriptTest {
    private val progress = listOf(
        TourEvent.Acknowledged, TourEvent.CurtainPulled, TourEvent.BandPicked,
        TourEvent.GigAdded, TourEvent.RoomOpened, TourEvent.SwipedBack,
        TourEvent.ContactExchanged(59.91, 10.75), TourEvent.PinchedOut, TourEvent.TicketImported,
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

    @Test fun `add the gig waits for every real gesture in order`() {
        var result = runTour(TourState(), TourEvent.Started(online = true))
        result = runTour(result.state, TourEvent.Acknowledged)
        assertEquals(TourStep.S2, result.state.step)
        assertEquals(result.state, runTour(result.state, TourEvent.BandPicked).state)

        result = runTour(result.state, TourEvent.CurtainPulled)
        assertEquals(TourStep.S3, result.state.step)
        result = runTour(result.state, TourEvent.BandPicked)
        assertEquals(TourStep.S4, result.state.step)
        result = runTour(result.state, TourEvent.GigAdded)
        assertEquals(TourStep.S5, result.state.step)
        result = runTour(result.state, TourEvent.RoomOpened)
        assertEquals(TourStep.S6, result.state.step)
        result = runTour(result.state, TourEvent.SwipedBack)
        assertEquals(TourStep.S7, result.state.step)
    }

    @Test fun `meeting the friend records the venue then waits for pinch and ticket import`() {
        var state = statesAtEveryInteractiveStep().single { it.step == TourStep.S7 }
        var result = runTour(state, TourEvent.ContactExchanged(59.9139, 10.7522))
        assertEquals(TourStep.S8, result.state.step)
        assertEquals(59.9139, result.state.demoVenueLat)
        assertEquals(10.7522, result.state.demoVenueLon)
        assertEquals(listOf(TourCommand.ShowCoachMark(TourStep.S8)), result.commands)

        state = result.state
        assertEquals(state, runTour(state, TourEvent.TicketImported).state)
        result = runTour(state, TourEvent.PinchedOut)
        assertEquals(TourStep.S9, result.state.step)
        assertEquals(listOf(TourCommand.ImportDemoTicket), result.commands)

        result = runTour(result.state, TourEvent.TicketImported)
        assertEquals(TourStep.S10, result.state.step)
    }

    @Test fun `selfie round trip resumes S18 and delivers friend selfie after a real attach`() {
        val atSelfie = statesAtEveryInteractiveStep().single { it.step == TourStep.S18 }

        assertEquals(atSelfie, runTour(atSelfie, TourEvent.MediaAdded).state)
        val returned = runTour(atSelfie, TourEvent.ReturnedFromPhotos)
        assertEquals(TourStep.S18, returned.state.step)
        assertTrue(returned.state.returnedFromPhotos)

        val attached = runTour(returned.state, TourEvent.MediaAdded)
        assertEquals(TourStep.S19, attached.state.step)
        assertEquals(TourCommand.DeliverFriendSelfie, attached.commands.first())
        assertTrue(attached.commands.contains(TourCommand.ShowCoachMark(TourStep.S19)))
    }

    @Test fun `Spotify export finishes and decline leaves a retry that export clears`() {
        val atSpotify = statesAtEveryInteractiveStep().single { it.step == TourStep.S19 }
        val declined = runTour(atSpotify, TourEvent.SpotifyDeclined)
        assertTrue(declined.state.finished)
        assertTrue(declined.state.pendingSpotifyRetry)
        assertEquals(listOf(TourCommand.PurgeDemoWorld, TourCommand.MarkTourFinished), declined.commands)

        val retried = runTour(declined.state, TourEvent.SpotifyExported)
        assertFalse(retried.state.pendingSpotifyRetry)
        assertTrue(retried.commands.isEmpty())

        val exported = runTour(atSpotify, TourEvent.SpotifyExported)
        assertTrue(exported.state.finished)
        assertFalse(exported.state.pendingSpotifyRetry)
        assertEquals(listOf(TourCommand.PurgeDemoWorld, TourCommand.MarkTourFinished), exported.commands)
    }

    @Test fun `Tour playlist metadata uses explicit placeholder friend copy`() {
        assertEquals("Went to a gig with Virtual friend", tourPlaylistName())
        assertEquals("Tour complete: went to a gig with Virtual friend.", tourPlaylistDescription())
    }

    private data class TourTransitionSnapshot(val state: TourState, val commands: List<TourCommand>)
    private fun io.github.magnusencoded.stationtostation.data.TourTransition.snapshot() =
        TourTransitionSnapshot(state, commands)
}
