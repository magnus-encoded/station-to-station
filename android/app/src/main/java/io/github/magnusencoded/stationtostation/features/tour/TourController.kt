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
            current.tour.step != null -> dispatch(TourEvent.Resumed)
            !current.onboarded -> dispatch(TourEvent.Started(isOnline()))
        }
    }

    /** Settings' "Resume tour", there only while a Tour is unfinished. */
    fun resume() = dispatch(TourEvent.Resumed)

    /** Settings' "Replay tour": from S1 with a fresh **Demo world**, finished or not. */
    fun replay() = dispatch(TourEvent.ReplayRequested)

    fun dispatch(event: TourEvent) {
        val before = state().tour
        val (after, commands) = TourScript.on(before, event)
        if (after == before && commands.isEmpty()) return
        // Offered means started: an offline first launch leaves it to be offered again.
        val offered = before.step == null && after.step != null
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
