package io.github.magnusencoded.stationtostation.features

import io.github.magnusencoded.stationtostation.UiState

/** A [UiState] holder shaped like a controller's `state` / `update` pair. */
class FakeState(initial: UiState = UiState()) {
    var value: UiState = initial
        private set

    val state: () -> UiState = { value }
    val update: ((UiState) -> UiState) -> Unit = { change -> value = change(value) }
}
package io.github.magnusencoded.stationtostation.features

import io.github.magnusencoded.stationtostation.UiState

/** The `state`/`update` pair a controller is built from, over a plain variable. */
class FakeState(var current: UiState = UiState()) {
    val state: () -> UiState = { current }
    val update: ((UiState) -> UiState) -> Unit = { current = it(current) }
}
package io.github.magnusencoded.stationtostation.features

import io.github.magnusencoded.stationtostation.UiState

/** The `state`/`update` pair a controller is built on, over a plain variable. */
class FakeState(var value: UiState = UiState()) {
    val state: () -> UiState = { value }
    val update: ((UiState) -> UiState) -> Unit = { change -> value = change(value) }
}
