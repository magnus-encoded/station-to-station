package io.github.magnusencoded.stationtostation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * What a pull of the planning curtain is reaching for.
 *
 * The curtain used to reveal a caption, and two add-rows then appeared on the timeline
 * underneath it — so the gesture's payload was an explanation and the actions arrived as
 * a consequence of reading it. The pull is continuous and expressive; what it controlled
 * was a boolean. Now the doors are *inside* the curtain and the depth of the pull picks
 * one, which is the same argument that deleted "↑ THE FUTURE" carried to its end: the
 * rows are what the curtain is for.
 */
internal enum class PlanningDoor { None, Gig, Bill, Import }

// Per file, as everywhere else in this package.
private val LineCol = Color(0xFF2E2740)
private val Faint = Color(0xFF5A5368)
private val Slate = Color(0xFF6D7E9B) // the future, a cooler light

/**
 * The three commitment points, as a fraction of the curtain's full travel.
 *
 * They are detents rather than one threshold: outcomes separated by a bare distance
 * are a coin flip in the hand. Each has a label that lights when you reach it and a tick
 * of haptic feedback as you cross, so the choice is felt on the way rather than found
 * out on release. The dead band below [GigDetent] is what keeps a short pull cheap to
 * abandon — the gesture has to be able to mean nothing.
 */
internal const val GigDetent = 0.35f
internal const val BillDetent = 0.62f
internal const val ImportDetent = 0.88f

/** Which door a pull this deep has armed. [progress] is 0f..1f of the curtain's travel. */
internal fun armedDoor(progress: Float): PlanningDoor = when {
    progress >= ImportDetent -> PlanningDoor.Import
    progress >= BillDetent -> PlanningDoor.Bill
    progress >= GigDetent -> PlanningDoor.Gig
    else -> PlanningDoor.None
}

/**
 * The gap that opens when you pull down past the top of your line: the line keeps going
 * up, into the shows you haven't been to yet — and the three ways in hang in that gap.
 *
 * They reveal from the bottom up as the gap grows, so the first one you can see is the
 * first one you can reach. The armed one is lit; the others are not. Releasing takes the
 * lit one and nothing else, and releasing with none lit closes the gap.
 */
@Composable
internal fun PlanningPull(progress: () -> Float, heightPx: () -> Float) {
    val h = with(LocalDensity.current) { heightPx().toDp() }
    if (h <= 0.dp) return
    val p = progress()
    val armed = armedDoor(p)
    Column(
        Modifier
            .fillMaxWidth()
            .height(h)
            .clipToBounds()
            .alpha((p * 1.8f).coerceIn(0f, 1f)),
        horizontalAlignment = Alignment.CenterHorizontally,
        // Bottom, so growing the gap reveals upward from the line rather than sliding
        // the whole block. What you have already read stays where you read it.
        verticalArrangement = Arrangement.Bottom,
    ) {
        Box(Modifier.width(2.dp).height(20.dp).background(LineCol))
        Spacer(Modifier.height(10.dp))
        // Import, being the deepest and rarest pull, reveals first — the two doors
        // reached sooner sit below it, closest to the line.
        Door("your setlist.fm history", lit = armed == PlanningDoor.Import)
        Door("a festival lineup", lit = armed == PlanningDoor.Bill)
        Door("a gig you're going to", lit = armed == PlanningDoor.Gig)
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun Door(label: String, lit: Boolean) {
    Text(
        "+  $label",
        color = if (lit) Slate else Faint,
        fontSize = 13.sp,
        fontWeight = if (lit) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}
