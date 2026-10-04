package io.github.magnusencoded.stationtostation.features

import io.github.magnusencoded.stationtostation.UiState

/** The `state`/`update` pair a controller is built from, over a plain variable. */
class FakeState(var current: UiState = UiState()) {
    var value: UiState
        get() = current
        set(v) { current = v }

    val state: () -> UiState = { current }
    val update: ((UiState) -> UiState) -> Unit = { change -> current = change(current) }
}
