package io.github.magnusencoded.stationtostation.features.tour

import android.annotation.SuppressLint
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
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
    val context = LocalContext.current
    val friend = remember { TourCharacter.load(context) }
    Box(Modifier.fillMaxSize()) {
        content()
        val step = state.tour.step
        if (step != null && state.tour.running) {
            CoachMarkCard(
                friend = friend,
                step = step,
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
private fun BoxScope.CoachMarkCard(
    friend: TourCharacter,
    step: TourStep,
    onAcknowledge: () -> Unit,
    onSkip: () -> Unit,
) {
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painterResource(drawable(friend.avatar)),
                contentDescription = null,
                modifier = Modifier.size(36.dp).clip(CircleShape),
            )
            Spacer(Modifier.width(10.dp))
            Text(friend.name, color = Amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        Text(friend.line(step), color = Ink, fontFamily = FontFamily.Serif, fontSize = 18.sp)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (step != TourStep.S20) TextButton(onClick = onSkip) { Text("Skip", color = Muted) }
            if (step.mark == CoachMark.Line) {
                TextButton(onClick = onAcknowledge) { Text("Got it", color = Amber) }
            }
        }
    }
}

/** The character file names its drawables, so a new character is data plus two drawables. */
@SuppressLint("DiscouragedApi")
@Composable
private fun drawable(name: String): Int {
    val context = LocalContext.current
    return context.resources.getIdentifier(name, "drawable", context.packageName)
}
