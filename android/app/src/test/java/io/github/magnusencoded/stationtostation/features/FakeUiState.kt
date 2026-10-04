package io.github.magnusencoded.stationtostation.features

import io.github.magnusencoded.stationtostation.UiState

/** Stands in for the `state`/`update` pair a controller is constructed with. */
class FakeUiState(initial: UiState = UiState()) {
    var current: UiState = initial
        private set

    val state: () -> UiState = { current }
    val update: ((UiState) -> UiState) -> Unit = { current = it(current) }
}
