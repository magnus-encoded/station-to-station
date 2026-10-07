package io.github.magnusencoded.stationtostation.features.tour

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.magnusencoded.stationtostation.AppViewModel

private val Card = Color(0xF21D1728)
private val Ink = Color(0xFFEDE9F2)
private val Muted = Color(0xFF8B8299)
private val Amber = Color(0xFFE7B24C)

/** [content] with the **Tour**'s coach mark over it, while one is running. */
@Composable
fun TourOverlay(viewModel: AppViewModel, content: @Composable () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        content()
        if (state.tour.step != null) {
            CoachMarkCard(
                mark = state.coachMark,
                onAcknowledge = { viewModel.tour.dispatch(TourEvent.Acknowledged) },
                onSkip = { viewModel.tour.dispatch(TourEvent.Skipped) },
            )
        }
    }
}

/**
 * One coach mark, with Skip on every step. Marks whose gesture has no engine yet show their
 * line and Skip only; each step's issue gives its mark the gesture it waits for.
 */
@Composable
private fun BoxScope.CoachMarkCard(mark: CoachMark?, onAcknowledge: () -> Unit, onSkip: () -> Unit) {
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(16.dp)
            .fillMaxWidth()
            .background(Card, RoundedCornerShape(16.dp))
            .padding(20.dp)
            .testTag("coachMark"),
    ) {
        Text(TOUR_FRIEND, color = Amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(mark?.let(::line) ?: "One moment…", color = Ink, fontFamily = FontFamily.Serif, fontSize = 18.sp)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onSkip) { Text("Skip", color = Muted) }
            if (mark == CoachMark.Line) {
                TextButton(onClick = onAcknowledge) { Text("Got it", color = Amber) }
            }
        }
    }
}

// Placeholders: the Virtual friend's name and writing are owned by a human (#607).
private const val TOUR_FRIEND = "Your friend"

private fun line(mark: CoachMark): String = when (mark) {
    CoachMark.Line -> "This is your line. Down is back in time, up is what's coming."
    CoachMark.Curtain -> "Pull down from the top to plan a gig."
    CoachMark.Band -> "Who do you want to see?"
    CoachMark.AddGig -> "Add the gig."
    CoachMark.OpenRoom -> "Tap the gig to open its Room."
    CoachMark.SwipeBack -> "Swipe right to go back."
    CoachMark.Exchange -> "Let's swap contacts."
    CoachMark.PinchOut -> "Pinch out to see my line beside yours."
    CoachMark.Calendar -> "Put it in your calendar."
    CoachMark.Maps -> "Find the way there."
    CoachMark.Ticket -> "Show your ticket at the door."
    CoachMark.CheckIn -> "Check in."
    CoachMark.Log -> "What did they open with?"
    CoachMark.Gap -> "Don't know this one? Leave a Gap."
    CoachMark.Gossip -> "Tell me something back."
    CoachMark.Selfie -> "Take a selfie."
    CoachMark.Spotify -> "Keep the night as a playlist."
}
