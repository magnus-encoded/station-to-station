package io.github.magnusencoded.stationtostation.features

import io.github.magnusencoded.stationtostation.UiState

/** The `state`/`update` pair a controller is built on, over a plain variable. */
class FakeState(var value: UiState = UiState()) {
    val state: () -> UiState = { value }
    val update: ((UiState) -> UiState) -> Unit = { change -> value = change(value) }
}
