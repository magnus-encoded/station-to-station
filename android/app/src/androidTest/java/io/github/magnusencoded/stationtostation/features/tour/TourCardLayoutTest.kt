package io.github.magnusencoded.stationtostation.features.tour

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TourCardLayoutTest {
    @get:Rule val compose = createComposeRule()
    @Test fun nameTabDoesNotCoverInstructionsAtLargeFontSize() {
        val character = TourCharacter.load(InstrumentationRegistry.getInstrumentation().targetContext)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                    CoachMarkCard(character, CoachMark.Calendar, Color.Blue, {}, {}, Modifier.width(360.dp))
                }
            }
        }
        val tab = compose.onNodeWithText(character.name).fetchSemanticsNode().boundsInRoot
        val instruction = compose.onNodeWithText(character.line(CoachMark.Calendar).instruction).fetchSemanticsNode().boundsInRoot
        assertTrue("Name tab $tab overlaps instructions $instruction", tab.bottom <= instruction.top)
    }
}
