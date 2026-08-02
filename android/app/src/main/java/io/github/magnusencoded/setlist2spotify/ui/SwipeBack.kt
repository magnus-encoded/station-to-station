package io.github.magnusencoded.setlist2spotify.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Right goes back, the same as everywhere else on the spine. One rightward drag
 * past [threshold] fires [onBack]. Put it on a screen's outermost layout, not a
 * scrollable child: detectHorizontalDragGestures waits for horizontal-dominant
 * slop, so vertical scrolling underneath it is unaffected.
 *
 * PointerInputScope is itself a Density, so the threshold converts to px inline —
 * no LocalDensity plumbing needed.
 */
fun Modifier.swipeRightToBack(threshold: Dp = 110.dp, onBack: () -> Unit): Modifier =
    this.pointerInput(Unit) {
        var dragX = 0f
        detectHorizontalDragGestures(
            onDragStart = { dragX = 0f },
            onDragEnd = { if (dragX >= threshold.toPx()) onBack() },
            onHorizontalDrag = { _, delta -> dragX += delta },
        )
    }
