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
        val demo = mutableListOf<String>()
        override suspend fun markDemo(gigId: String) { demo += gigId }
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
        fake.update { it.copy(onboarded = true, tour = TourState(finished = true)) }
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

    @Test
    fun an_upgrader_is_offered_the_tour_once_and_dismissing_hides_it_for_good() {
        fake.update { it.copy(onboarded = true) }
        val c = controller()
        c.launch()
        assertTrue(fake.current.tourUpgradePrompt)
        assertNull(fake.current.tour.step)
        c.dismissUpgradePrompt()
        assertFalse(fake.current.tourUpgradePrompt)
        assertNull(fake.current.tour.step)

        val relaunch = FakeState()
        relaunch.update { it.copy(onboarded = true, tour = store.saved!!) }
        TourController(relaunch.state, relaunch.update, store, { true }, CoroutineScope(Dispatchers.Unconfined)).launch()
        assertFalse(relaunch.current.tourUpgradePrompt)
        assertNull(relaunch.current.tour.step)
    }

    @Test
    fun accepting_the_upgrade_prompt_starts_the_tour_and_never_offers_it_again() {
        fake.update { it.copy(onboarded = true) }
        val c = controller()
        c.launch()
        c.acceptUpgradePrompt()
        assertFalse(fake.current.tourUpgradePrompt)
        assertEquals(TourStep.S1, fake.current.tour.step)
        assertEquals(CoachMark.Line, fake.current.coachMark)
        c.dispatch(TourEvent.Skipped)
        assertTrue(store.saved!!.upgradePromptDismissed)

        val relaunch = FakeState()
        relaunch.update { it.copy(onboarded = true, tour = store.saved!!) }
        TourController(relaunch.state, relaunch.update, store, { true }, CoroutineScope(Dispatchers.Unconfined)).launch()
        assertFalse(relaunch.current.tourUpgradePrompt)
    }

    @Test
    fun a_new_install_never_sees_the_upgrade_prompt() {
        for (connected in listOf(false, true, true)) {
            online = connected
            val relaunch = FakeState()
            relaunch.update { it.copy(onboarded = store.onboarded, tour = store.saved ?: TourState()) }
            val c = TourController(relaunch.state, relaunch.update, store, { online }, CoroutineScope(Dispatchers.Unconfined))
            c.launch()
            assertFalse(relaunch.current.tourUpgradePrompt)
            c.dispatch(TourEvent.Skipped)
        }
    }

    @Test
    fun the_add_gig_steps_advance_only_on_their_gestures() {
        val c = controller()
        c.launch()
        c.dispatch(TourEvent.Acknowledged)
        c.dispatch(TourEvent.RoomOpened)
        assertEquals(TourStep.S2, fake.current.tour.step)
        c.dispatch(TourEvent.CurtainPulled)
        assertEquals(CoachMark.Band, fake.current.coachMark)
        c.dispatch(TourEvent.BandPicked)
        c.gigAdded("gig-1")
        assertEquals(CoachMark.OpenRoom, fake.current.coachMark)
        c.dispatch(TourEvent.RoomOpened)
        assertEquals(CoachMark.SwipeBack, fake.current.coachMark)
        c.dispatch(TourEvent.SwipedBack)
        assertEquals(TourStep.S7, fake.current.tour.step)
    }

    @Test
    fun entering_the_band_step_completes_the_lookup_so_a_resume_skips_it() {
        fake.update { it.copy(onboarded = true, tour = TourState(step = TourStep.S2)) }
        controller().dispatch(TourEvent.CurtainPulled)
        assertTrue(OnceOnly.LookUpBand in fake.current.tour.completedEffects)
        assertTrue(OnceOnly.LookUpBand in store.saved!!.completedEffects)
        val (_, commands) = TourScript.on(fake.current.tour, TourEvent.Resumed)
        assertFalse(TourCommand.LookUpBand in commands)
    }

    @Test
    fun a_gig_added_at_the_add_step_is_tagged_demo() {
        fake.update { it.copy(onboarded = true, tour = TourState(step = TourStep.S4)) }
        controller().gigAdded("gig-1")
        assertEquals(listOf("gig-1"), store.demo)
        assertEquals(TourStep.S5, fake.current.tour.step)
    }

    @Test
    fun a_gig_added_outside_the_add_step_stays_the_persons_own() {
        fake.update { it.copy(onboarded = true, tour = TourState(step = TourStep.S5)) }
        controller().gigAdded("gig-1")
        assertTrue(store.demo.isEmpty())
        assertNull(store.saved)
    }
}
