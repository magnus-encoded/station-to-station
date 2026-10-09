package io.github.magnusencoded.stationtostation.features.tour

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.testTag
import io.github.magnusencoded.stationtostation.data.StoredLog
import io.github.magnusencoded.stationtostation.ui.GigMediaBands
import io.github.magnusencoded.stationtostation.ui.LogEditor
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
    @Test fun actionCardOffersSkipButNoAcknowledgement() {
        val character = TourCharacter.load(InstrumentationRegistry.getInstrumentation().targetContext)
        compose.setContent {
            MaterialTheme { CoachMarkCard(character, CoachMark.Calendar, Color.Blue, {}, {}, screen = "room") }
        }
        compose.onNodeWithText("Skip").assertExists()
        compose.onNodeWithText("OK").assertDoesNotExist()
        compose.onNodeWithText("Got it").assertDoesNotExist()
    }

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

    @Test fun roomCardClearsMediaAndLogWithoutKeyboard() = assertRoomCardClearsContent(700)

    @Test fun roomCardClearsMediaAndLogInReducedViewport() = assertRoomCardClearsContent(420)

    private fun assertRoomCardClearsContent(height: Int) {
        val character = TourCharacter.load(InstrumentationRegistry.getInstrumentation().targetContext)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp).height(height.dp)) {
                    TourDock(inRoom = true, hasCard = true, content = {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            Box(Modifier.testTag("mediaBands")) {
                                GigMediaBands(media = emptyList(), loadPreview = { error("No media") },
                                    arranging = false, contactLight = false, editable = true,
                                    senderName = { null }, onArrange = {}, onAdd = {}, onOpen = {},
                                    onRemove = {}, onMove = { _, _, _ -> })
                            }
                            LogEditor(emptyList(), "", StoredLog(), null, {}, {})
                        }
                    }) { modifier ->
                        CoachMarkCard(character, CoachMark.Log, Color.Blue, {}, {}, modifier)
                    }
                }
            }
        }
        val card = compose.onNodeWithTag("coachMark").fetchSemanticsNode().boundsInRoot
        val media = compose.onNodeWithTag("mediaBands").fetchSemanticsNode().boundsInRoot
        assertTrue("Card $card overlaps media $media", card.bottom <= media.top)
        val field = compose.onNode(hasSetTextAction()).performScrollTo().assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Card $card overlaps Log field $field", card.bottom <= field.top)
    }

    @Test fun lineCardStaysBelowContent() {
        val character = TourCharacter.load(InstrumentationRegistry.getInstrumentation().targetContext)
        compose.setContent {
            MaterialTheme {
                TourDock(inRoom = false, hasCard = true, content = {
                    Box(Modifier.fillMaxSize().testTag("line"))
                }) { modifier ->
                    CoachMarkCard(character, CoachMark.Calendar, Color.Blue, {}, {}, modifier)
                }
            }
        }
        val card = compose.onNodeWithTag("coachMark").fetchSemanticsNode().boundsInRoot
        val line = compose.onNodeWithTag("line").fetchSemanticsNode().boundsInRoot
        assertTrue("Card $card overlaps Line $line", line.bottom <= card.top)
    }
}
