package io.github.magnusencoded.stationtostation.features.settings

import io.github.magnusencoded.stationtostation.UiState
import io.github.magnusencoded.stationtostation.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The shape every feature controller takes: shared [UiState] reached through [state] and
 * [update], persistence through its `data/` dependencies, async work on [scope].
 */
class SettingsController(
    @Suppress("unused") private val state: () -> UiState,
    private val update: ((UiState) -> UiState) -> Unit,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    /** Records that the splash was passed, so it never shows again. */
    fun markOnboarded() {
        update { it.copy(onboarded = true) }
        scope.launch { settings.setOnboarded() }
    }
}
