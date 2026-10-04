package io.github.magnusencoded.stationtostation.features

import io.github.magnusencoded.stationtostation.UiState

/** A [UiState] holder shaped like a controller's `state` / `update` pair. */
class FakeState(initial: UiState = UiState()) {
    var value: UiState = initial
        private set

    val state: () -> UiState = { value }
    val update: ((UiState) -> UiState) -> Unit = { change -> value = change(value) }
}
