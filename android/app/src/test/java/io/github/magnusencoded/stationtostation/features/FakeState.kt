package io.github.magnusencoded.stationtostation.features

import io.github.magnusencoded.stationtostation.UiState

/** A [UiState] holder for controller tests: `state` and `update` as a controller receives them. */
class FakeState(initial: UiState = UiState()) {
    var current: UiState = initial
        private set

    val state: () -> UiState = { current }
    val update: ((UiState) -> UiState) -> Unit = { transform -> current = transform(current) }
}
