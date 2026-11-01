package io.github.magnusencoded.stationtostation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.magnusencoded.stationtostation.features.tour.CoachMark
import io.github.magnusencoded.stationtostation.features.tour.CoachMarkCard
import io.github.magnusencoded.stationtostation.features.tour.TourEvent
import io.github.magnusencoded.stationtostation.ui.EmptyTimeline
import io.github.magnusencoded.stationtostation.ui.TimelineTopBar
import io.github.magnusencoded.stationtostation.ui.addGigByHand
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TourAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun topBarLabelsBelongToClickableNodesEvenWithoutMerging() {
        val clicked = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                TimelineTopBar(
                    onOpenConnect = { clicked += "people" },
                    onOpenProgramme = { clicked += "programme" },
                    onOpenSettings = { clicked += "settings" },
                )
            }
        }
        for (label in listOf("Connect with people", "Festival programme", "Settings")) {
            compose.onNodeWithContentDescription(label, useUnmergedTree = true)
                .assertHasClickAction().performClick()
        }
        assertEquals(listOf("people", "programme", "settings"), clicked)
    }

    @Test fun coachCardLabelsBelongToClickableNodesEvenWithoutMerging() {
        var skipped = false
        var acknowledged = false
        compose.setContent {
            MaterialTheme {
                CoachMarkCard(CoachMark.Line, { acknowledged = true }, { skipped = true })
            }
        }
        for (label in listOf("Skip", "Got it")) {
            compose.onNodeWithContentDescription(label, useUnmergedTree = true)
                .assertHasClickAction().performClick()
        }
        assertEquals(true, skipped)
        assertEquals(true, acknowledged)
    }

    @Test fun secondStepNamesTheByHandDoorOnlyOnAnEmptyLine() {
        compose.setContent {
            MaterialTheme { CoachMarkCard(CoachMark.Curtain, {}, {}, emptyLine = true) }
        }
        compose.onNodeWithText("Tap “or add a gig by hand” to plan a gig.").assertExists()
    }

    @Test fun secondStepOnALineWithGigsPointsAtThePullDown() {
        compose.setContent {
            MaterialTheme { CoachMarkCard(CoachMark.Curtain, {}, {}, emptyLine = false) }
        }
        compose.onNodeWithText("Pull down from the top to plan a gig.").assertExists()
    }

    @Test fun gestureStepKeepsSkipOnTheClickableNode() {
        var skipped = false
        compose.setContent {
            MaterialTheme {
                CoachMarkCard(CoachMark.Curtain, {}, { skipped = true })
            }
        }
        compose.onNodeWithContentDescription("Skip", useUnmergedTree = true)
            .assertHasClickAction().performClick()
        compose.onNodeWithContentDescription("Got it", useUnmergedTree = true)
            .assertDoesNotExist()
        assertEquals(true, skipped)
    }

    @Test fun theByHandDoorOnAnEmptyTimelineOpensTheDialogAndPullsTheCurtain() {
        val log = mutableListOf<Any>()
        compose.setContent {
            MaterialTheme {
                EmptyTimeline(
                    onAdd = {},
                    onAddGig = { addGigByHand({ log += "open" }, { log += it }) },
                )
            }
        }
        compose.onNodeWithText("or add a gig by hand").performClick()
        assertEquals(listOf<Any>("open", TourEvent.CurtainPulled), log)
    }
}
