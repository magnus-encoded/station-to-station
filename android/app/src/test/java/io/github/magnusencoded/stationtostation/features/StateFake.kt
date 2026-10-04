package io.github.magnusencoded.stationtostation.features

import io.github.magnusencoded.stationtostation.UiState

/** The `state`/`update` pair a controller is handed, backed by a plain variable. */
class StateFake(var current: UiState = UiState()) {
    val state: () -> UiState = { current }
    val update: ((UiState) -> UiState) -> Unit = { transform -> current = transform(current) }
}
package io.github.magnusencoded.stationtostation.features

import io.github.magnusencoded.stationtostation.UiState

/** Stands in for the view model's state in controller tests: pass [state] and [update]. */
class StateFake(initial: UiState = UiState()) {
    var current: UiState = initial
        private set

    val state: () -> UiState = { current }
    val update: ((UiState) -> UiState) -> Unit = { edit -> current = edit(current) }
}
