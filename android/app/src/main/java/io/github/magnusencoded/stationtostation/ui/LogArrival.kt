package io.github.magnusencoded.stationtostation.ui

import android.animation.ValueAnimator
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout

/** Each song gets connector, text, then node; the whole batch takes at most three seconds. */
internal data class LogArrivalFrame(val space: Float = 1f, val text: Float = 1f, val node: Boolean = true)
internal data class LogArrival(val index: Int, val count: Int, val started: Long) {
    val duration: Long get() = minOf(360L, 3000L / count.coerceAtLeast(1))
    fun frame(elapsed: Long, reduceMotion: Boolean = false): LogArrivalFrame {
        if (reduceMotion) return LogArrivalFrame()
        val progress = ((elapsed - index * duration).toFloat() / duration).coerceIn(0f, 1f)
        return LogArrivalFrame((progress / .25f).coerceAtMost(1f),
            ((progress - .25f) / .75f).coerceIn(0f, 1f), progress >= 1f)
    }
}
internal fun logArrivalOrder(before: List<String>, after: List<String>, beforeTitles: List<String> = emptyList(), afterTitles: List<String> = emptyList()): List<String> =
    after.filterIndexed { index, key ->
        key !in before && (afterTitles.getOrNull(index)?.takeIf { it.isNotBlank() }?.let { title ->
            afterTitles.take(index + 1).count { it.equals(title, ignoreCase = true) } >
                beforeTitles.count { it.equals(title, ignoreCase = true) }
        } ?: true)
    }

@Composable
internal fun rememberLogArrivals(gig: String, keys: List<String>, titles: List<String>): Map<String, LogArrival> {
    var previous by remember(gig) { mutableStateOf(keys) }
    var previousTitles by remember(gig) { mutableStateOf(titles) }
    var arrivals by remember(gig) { mutableStateOf(emptyMap<String, LogArrival>()) }
    if (previous != keys || previousTitles != titles) {
        val added = logArrivalOrder(previous, keys, previousTitles, titles)
        val now = SystemClock.uptimeMillis()
        arrivals = arrivals.filterKeys { it in keys } + added.mapIndexed { index, key -> key to LogArrival(index, added.size, now) }
        previous = keys
        previousTitles = titles
    }
    return arrivals
}

@Composable
internal fun ArrivingLogRow(arrival: LogArrival?, content: @Composable (LogArrivalFrame) -> Unit) {
    val reduced = !ValueAnimator.areAnimatorsEnabled()
    val frame by produceState(arrival?.frame(SystemClock.uptimeMillis() - arrival.started, reduced) ?: LogArrivalFrame(), arrival, reduced) {
        if (arrival != null && !reduced) {
            do {
                value = arrival.frame(SystemClock.uptimeMillis() - arrival.started)
                if (!value.node) withFrameNanos { }
            } while (!value.node)
        } else value = LogArrivalFrame()
    }
    content(frame)
}
internal fun Modifier.arrivalSpace(frame: LogArrivalFrame): Modifier = clipToBounds().layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, (placeable.height * frame.space).toInt()) { placeable.place(0, 0) }
}
