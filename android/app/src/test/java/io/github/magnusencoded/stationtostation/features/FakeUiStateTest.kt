package io.github.magnusencoded.stationtostation.features

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeUiStateTest {
    @Test
    fun `an update is visible to the next read of state`() {
        val fake = FakeUiState()
        assertFalse(fake.state().launched)

        fake.update { it.copy(launched = true) }

        assertTrue(fake.state().launched)
    }

    @Test
    fun `updates apply in order to the state they find`() {
        val fake = FakeUiState()

        fake.update { it.copy(artistQuery = "a") }
        fake.update { it.copy(artistQuery = it.artistQuery + "b") }

        assertEquals("ab", fake.current.artistQuery)
    }
}
