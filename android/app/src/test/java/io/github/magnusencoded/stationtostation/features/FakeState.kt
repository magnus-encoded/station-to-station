package io.github.magnusencoded.stationtostation.features

import io.github.magnusencoded.stationtostation.UiState

/** The `state`/`update` pair a controller is built from, over a plain variable. */
class FakeState(var current: UiState = UiState()) {
    val state: () -> UiState = { current }
    val update: ((UiState) -> UiState) -> Unit = { current = it(current) }
}
