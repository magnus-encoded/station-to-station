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
internal enum class PlanningDoor { None, Gig, Programme, Import }

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
internal const val ProgrammeDetent = 0.62f
internal const val ImportDetent = 0.88f

/**
 * Finger-to-gap ratio: the gap grows at half the speed of the drag, so the travel is
 * felt as weight rather than as a panel stuck to the fingertip.
 */
internal const val PullDamping = 0.5f

/**
 * How much of an upward drag the open gap takes for itself, before the list sees any.
 *
 * The list can always scroll down into the past, so it consumed every upward delta and
 * left nothing over: the gap stayed open with a door still armed while the timeline moved
 * behind it. While the gap is open the drag is the curtain's — but only as far as the
 * distance that shuts it ([pull] px of gap ÷ the damping), so a drag that closes it early
 * still scrolls with what's left rather than dying in the curtain.
 *
 * [dy] and the result are raw drag pixels, negative upward.
 */
internal fun curtainTakes(dy: Float, pull: Float): Float =
    if (dy >= 0f || pull <= 0f) 0f else dy.coerceAtLeast(-pull / PullDamping)

/** Which door a pull this deep has armed. [progress] is 0f..1f of the curtain's travel. */
internal fun armedDoor(progress: Float): PlanningDoor = when {
    progress >= ImportDetent -> PlanningDoor.Import
    progress >= ProgrammeDetent -> PlanningDoor.Programme
    progress >= GigDetent -> PlanningDoor.Gig
    else -> PlanningDoor.None
}

/**
 * The gap that opens when you pull down past the top of your line: the line keeps going
 * up, into the shows you haven't been to yet — and the three ways in hang in that gap.
 *
 * They reveal from the top down as the gap grows, so the first one you can see is the
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
        // Top, so a door stays where it appeared as the gap keeps growing. Arrangement
        // only ever distributes *spare* space, and for most of the travel the doors are
        // taller than the gap — so Bottom did nothing until full pull and then slid the
        // whole block down. What you have already read stays where you read it.
        verticalArrangement = Arrangement.Top,
    ) {
        Box(Modifier.width(2.dp).height(20.dp).background(LineCol))
        Spacer(Modifier.height(10.dp))
        // Shallowest first, because the gap is clipped from the bottom: the order they
        // appear in has to be the order the pull arms them, or you are choosing a door
        // that is still hidden (#263).
        Door("a gig you're going to", lit = armed == PlanningDoor.Gig)
        Door("a festival programme", lit = armed == PlanningDoor.Programme)
        Door("your setlist.fm history", lit = armed == PlanningDoor.Import)
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
