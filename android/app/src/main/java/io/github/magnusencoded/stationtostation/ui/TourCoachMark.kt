package io.github.magnusencoded.stationtostation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.magnusencoded.stationtostation.data.TourEvent
import io.github.magnusencoded.stationtostation.data.TourStep

/** Coach marks over the real UI. Human-owned friend copy remains a placeholder. */
@Composable
fun TourCoachMark(step: TourStep?, onEvent: (TourEvent) -> Unit) {
    if (step == null || step > TourStep.S6) return
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).padding(24.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().background(Color(0xFF1D1728), RoundedCornerShape(18.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Virtual friend", color = Color(0xFFE7B24C), fontWeight = FontWeight.Bold)
            Text(
                when (step) {
                    TourStep.S1 -> "This is your Line. Up and down is time."
                    TourStep.S2 -> "Pull down the Planning Curtain and choose a gig."
                    TourStep.S3 -> "Pick a band you like. MusicBrainz supplies the artist names."
                    TourStep.S4 -> "Add this gig to your Line."
                    TourStep.S5 -> "Tap the gig to open its Room."
                    TourStep.S6 -> "Swipe right to return to your Line."
                    else -> ""
                },
                color = Color(0xFFEDE9F2),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onEvent(TourEvent.Skipped) }) { Text("Skip") }
                Spacer(Modifier.width(8.dp).weight(1f))
                if (step == TourStep.S1) {
                    Button(
                        onClick = { onEvent(TourEvent.Acknowledged) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE7B24C), contentColor = Color(0xFF241A08)),
                    ) { Text("Got it") }
                }
            }
        }
    }
}
