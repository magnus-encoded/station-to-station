package io.github.magnusencoded.stationtostation.features.tour

import io.github.magnusencoded.stationtostation.features.FakeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TourControllerTest {

    private class Store : TourStore {
        var saved: TourState? = null
        var onboarded = false
        var purges = 0
        override suspend fun saveTour(state: TourState) { saved = state }
        override suspend fun setOnboarded() { onboarded = true }
        override suspend fun purgeDemoWorld() { purges++ }
    }

    private val store = Store()
    private val fake = FakeState()
    private var online = true

    private fun controller() = TourController(
        state = fake.state,
        update = fake.update,
        store = store,
        isOnline = { online },
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun a_first_launch_with_a_connection_starts_the_tour_at_the_line() {
        controller().launch()
        assertEquals(TourStep.S1, fake.current.tour.step)
        assertEquals(CoachMark.Line, fake.current.coachMark)
        assertTrue(fake.current.onboarded)
        assertTrue(store.onboarded)
        assertEquals(TourStep.S1, store.saved?.step)
    }

    @Test
    fun a_first_launch_offline_leaves_the_tour_to_be_offered_next_time() {
        online = false
        controller().launch()
        assertNull(fake.current.tour.step)
        assertFalse(fake.current.onboarded)
        assertFalse(store.onboarded)

        online = true
        controller().launch()
        assertEquals(TourStep.S1, fake.current.tour.step)
    }

    @Test
    fun an_install_already_offered_the_tour_gets_nothing_at_launch() {
        fake.update { it.copy(onboarded = true) }
        controller().launch()
        assertNull(fake.current.tour.step)
        assertNull(store.saved)
    }

    @Test
    fun a_launch_after_being_killed_mid_tour_resumes_at_the_saved_step() {
        fake.update { it.copy(onboarded = true, tour = TourState(step = TourStep.S2)) }
        controller().launch()
        assertEquals(TourStep.S2, fake.current.tour.step)
        assertEquals(CoachMark.Curtain, fake.current.coachMark)
    }

    @Test
    fun skip_purges_the_demo_world_and_finishes_the_tour() {
        val c = controller()
        c.launch()
        store.purges = 0
        c.dispatch(TourEvent.Skipped)
        assertEquals(1, store.purges)
        assertTrue(fake.current.tour.finished)
        assertNull(fake.current.tour.step)
        assertNull(fake.current.coachMark)
        assertTrue(store.saved!!.finished)
    }

    @Test
    fun a_lookup_reported_done_is_not_asked_for_again_on_resume() {
        val c = controller()
        c.launch()
        c.dispatch(TourEvent.Acknowledged)
        c.dispatch(TourEvent.CurtainPulled)
        c.completed(OnceOnly.LookUpBand)
        assertEquals(setOf(OnceOnly.LookUpBand), store.saved?.completedEffects)
    }

    @Test
    fun acknowledging_the_line_moves_on_to_the_curtain() {
        val c = controller()
        c.launch()
        c.dispatch(TourEvent.Acknowledged)
        assertEquals(TourStep.S2, fake.current.tour.step)
        assertEquals(CoachMark.Curtain, fake.current.coachMark)
    }

    @Test
    fun replay_from_settings_restarts_a_finished_tour_with_a_fresh_demo_world() {
        fake.update { it.copy(onboarded = true, tour = TourState(finished = true)) }
        controller().replay()
        assertEquals(TourStep.S1, fake.current.tour.step)
        assertFalse(fake.current.tour.finished)
        assertEquals(1, store.purges)
    }

    @Test
    fun resume_from_settings_shows_the_current_step_again() {
        fake.update { it.copy(onboarded = true, tour = TourState(step = TourStep.S4), coachMark = null) }
        controller().resume()
        assertEquals(CoachMark.AddGig, fake.current.coachMark)
    }

    @Test
    fun an_event_the_step_is_not_waiting_for_changes_nothing() {
        val c = controller()
        c.launch()
        val before = fake.current
        store.saved = null
        c.dispatch(TourEvent.GigAdded)
        assertEquals(before, fake.current)
        assertNull(store.saved)
    }
}
