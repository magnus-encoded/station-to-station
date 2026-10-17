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
import io.github.magnusencoded.stationtostation.features.tour.TourScript
import io.github.magnusencoded.stationtostation.features.tour.TourState
import io.github.magnusencoded.stationtostation.features.tour.TourStep
import io.github.magnusencoded.stationtostation.ui.EmptyTimeline
import io.github.magnusencoded.stationtostation.ui.TimelineTopBar
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
        for (label in listOf("Friends", "Festival programme", "Settings")) {
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

    @Test fun secondStepNamesTheEmptyTimelineAddAction() {
        compose.setContent {
            MaterialTheme { CoachMarkCard(CoachMark.Curtain, {}, {}) }
        }
        compose.onNodeWithText("Tap “or add a gig by hand” to plan a gig.").assertExists()
        compose.onNodeWithText("Your virtual tour guide").assertExists()
    }

    @Test fun emptyTimelineOffersTheTourAddAction() {
        var added = false
        var tour = TourState(step = TourStep.S2)
        compose.setContent {
            MaterialTheme {
                EmptyTimeline(onAdd = {}, onAddGig = {
                    added = true
                    tour = TourScript.on(tour, TourEvent.CurtainPulled).state
                })
            }
        }
        compose.onNodeWithText("or add a gig by hand").performClick()
        assertEquals(true, added)
        assertEquals(TourStep.S3, tour.step)
    }
}
