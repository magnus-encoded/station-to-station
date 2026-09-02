package io.github.magnusencoded.stationtostation.ui

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
 * The rule is that every pushed screen has this, and that it does exactly what the
 * back arrow and the system gesture do — one [onBack], never three behaviours. The
 * one thing it must not swallow is a horizontal carousel: a LazyRow consumes the
 * drag before this ever sees it, including at its ends, so scrolling cards stays
 * scrolling cards and never walks off the screen. That is why this goes outside.
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
