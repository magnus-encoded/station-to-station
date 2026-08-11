package io.github.magnusencoded.stationtostation.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.magnusencoded.stationtostation.AppViewModel
import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist

private val Ground = Color(0xFF0E0B14)
private val Raised = Color(0xFF17121F)
private val Raised2 = Color(0xFF1D1728)
private val LineCol = Color(0xFF2E2740)
private val LineLit = Color(0xFF4A3F63)
private val Ink = Color(0xFFEDE9F2)
private val Muted = Color(0xFF8B8299)
private val Faint = Color(0xFF5A5368)
private val Amber = Color(0xFFE7B24C)
private val AmberSoft = Color(0x29E7B24C)
private val Slate = Color(0xFF6D7E9B) // the other person's line, a cooler light
private val Serif = FontFamily.Serif

/**
 * A pinch detector that only reacts to a genuine two-finger pinch, so single-finger
 * scrolling and horizontal swipes pass straight through to the list underneath.
 * (detectTransformGestures also fires on one-finger pan, which swallows those.)
 * Pinch together → [onZoomOut] (see more, a coarser resolution); spread apart →
 * [onZoomIn] (drop into a finer one).
 */
internal suspend fun PointerInputScope.detectPinch(
    onZoomOut: () -> Unit = {},
    onZoomIn: () -> Unit = {},
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var zoom = 1f
        var fired = false
        do {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } < 2) continue
            val change = event.calculateZoom()
            if (change != 1f) {
                zoom *= change
                event.changes.forEach { it.consume() }
                if (zoom < 0.75f) { fired = true; onZoomOut() }
                else if (zoom > 1.3f) { fired = true; onZoomIn() }
            }
        } while (!fired && event.changes.any { it.pressed })
    }
}

// --- Rail colours, shared with the timeline's zoomed-out lanes ---

/**
 * Each lane gets its own light: mine is amber, the rest cycle through cooler ones.
 * Eight of them, because that is roughly where lanes get too close together to tell
 * apart by position anyway — past it the cycle repeats and colour stops carrying
 * identity, which is what the key above the timeline is for.
 */
private val RailColors = listOf(
    Slate,
    Color(0xFF8A6DA0),
    Color(0xFF5F8E8A),
    Color(0xFFA07E6D),
    Color(0xFF7B8FC4),
    Color(0xFFA8748C),
    Color(0xFF6E9B77),
    Color(0xFF9A8F5F),
)

internal fun railColor(index: Int) = RailColors[index % RailColors.size]
