package io.github.magnusencoded.stationtostation.features.tour

import io.github.magnusencoded.stationtostation.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What the Tour saves and deletes. */
interface TourStore {
    suspend fun saveTour(state: TourState)
    suspend fun setOnboarded()
    suspend fun purgeDemoWorld()
}

/**
 * Runs [TourScript] against the app: feeds it events, keeps its state in [UiState] and in
 * storage, and carries out the commands this platform has an engine for so far.
 */
class TourController(
    private val state: () -> UiState,
    private val update: ((UiState) -> UiState) -> Unit,
    private val store: TourStore,
    private val isOnline: () -> Boolean,
    private val scope: CoroutineScope,
) {

    /** At launch, once the saved Tour is in state: resume an unfinished one, or offer it. */
    fun launch() {
        val current = state()
        when {
            current.tour.running -> dispatch(TourEvent.Resumed)
            !current.onboarded -> dispatch(TourEvent.Started(isOnline()))
            // Only an install onboarded before the Tour existed has `onboarded` and no Tour state.
            current.tour == TourState() -> update { it.copy(tourUpgradePrompt = true) }
        }
    }

    /** Either answer closes the upgrade prompt for good. */
    fun acceptUpgradePrompt() {
        closeUpgradePrompt()
        replay()
    }

    fun dismissUpgradePrompt() = closeUpgradePrompt()

    private fun closeUpgradePrompt() {
        val after = state().tour.copy(upgradePromptDismissed = true)
        update { it.copy(tour = after, tourUpgradePrompt = false) }
        scope.launch { store.saveTour(after) }
    }

    /** Settings' "Resume tour", there only while a Tour is unfinished. */
    fun resume() = dispatch(TourEvent.Resumed)

    /** Settings' "Replay tour": from S1 with a fresh **Demo world**, finished or not. */
    fun replay() = dispatch(TourEvent.ReplayRequested)

    /** For the engines of S3 and S9: their effect is done and isn't asked for again. */
    fun completed(effect: OnceOnly) {
        val after = TourScript.completed(state().tour, effect)
        update { it.copy(tour = after) }
        scope.launch { store.saveTour(after) }
    }

    fun dispatch(event: TourEvent) {
        val before = state().tour
        val (after, commands) = TourScript.on(before, event)
        if (after == before && commands.isEmpty()) return
        // Offered means started: an offline first launch leaves it to be offered again.
        val offered = !before.running && after.running
        val mark = commands.filterIsInstance<TourCommand.ShowCoachMark>().lastOrNull()?.mark
        update {
            it.copy(
                tour = after,
                coachMark = mark ?: if (after.step == before.step) it.coachMark else null,
                onboarded = it.onboarded || offered,
            )
        }
        scope.launch {
            store.saveTour(after)
            if (offered) store.setOnboarded()
            // A new Demo world starts clean, whatever a killed earlier Tour left behind.
            if (after.demoWorld != before.demoWorld) store.purgeDemoWorld()
            for (command in commands) {
                when (command) {
                    TourCommand.PurgeDemoWorld -> store.purgeDemoWorld()
                    // The rest belong to the steps that need them (#591 onwards).
                    else -> Unit
                }
            }
        }
    }
}
