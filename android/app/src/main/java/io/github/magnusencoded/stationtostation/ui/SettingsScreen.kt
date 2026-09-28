package io.github.magnusencoded.stationtostation.ui

import io.github.magnusencoded.stationtostation.data.laneKey
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.magnusencoded.stationtostation.AppViewModel
import io.github.magnusencoded.stationtostation.BuildConfig
import io.github.magnusencoded.stationtostation.R
import io.github.magnusencoded.stationtostation.UiState
import io.github.magnusencoded.stationtostation.data.setlistfm.SHARED_QUOTA_MESSAGE
import io.github.magnusencoded.stationtostation.data.spotify.SPOTIFY_REDIRECT_URI
import kotlinx.coroutines.launch
import kotlin.math.abs

// Settings is the **Field** (#563): what feeds My timeline, drawn as a picture you move
// around in, rather than a form of keys grouped by vendor. What each service *is* and
// whether it is **Lit** is [serviceGraph]'s; where it goes is [fieldLayout]'s. This file
// only draws those two and moves today's controls into the sheet a tile opens.

private val Ground = Color(0xFF0E0B14)
private val Raised = Color(0xFF17121F)
private val Raised2 = Color(0xFF1D1728)
private val Ink = Color(0xFFEDE9F2)
private val Muted = Color(0xFF8B8299)
private val Faint = Color(0xFF5A5368)
private val LineCol = Color(0xFF2E2740)
private val Amber = Color(0xFFE7B24C)
private val OnAmber = Color(0xFF241A08)
private val TimelineLitFill = Color(0xFF2A2215)

// Dark whatever the system says, like the rest of the night-time screens: the Field is
// amber lines on a dark ground, and a light sheet over it would be a different app.
private val FieldColours = darkColorScheme(
    primary = Amber,
    onPrimary = OnAmber,
    background = Ground,
    onBackground = Ink,
    surface = Raised,
    onSurface = Ink,
    surfaceVariant = Raised2,
    onSurfaceVariant = Muted,
    outline = Faint,
)

@Composable
fun SettingsScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onOpenHandover: () -> Unit = {},
) = MaterialTheme(colorScheme = FieldColours) {
    SettingsField(viewModel, onBack, onOpenHandover)
}

/** What the phone itself has granted. Re-read on every resume: it changes in system settings. */
private data class DeviceAccess(
    val photos: PhotoAccess = PhotoAccess.NONE,
    val calendar: Boolean = false,
    val location: Boolean = false,
)

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun deviceAccess(context: Context): DeviceAccess {
    val all = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        granted(context, Manifest.permission.READ_MEDIA_IMAGES)
    } else {
        granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    // Android 14's "select photos": some access, which still lights the tile.
    val picked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    return DeviceAccess(
        photos = when {
            all -> PhotoAccess.FULL
            picked -> PhotoAccess.PARTIAL
            else -> PhotoAccess.NONE
        },
        calendar = granted(context, Manifest.permission.WRITE_CALENDAR),
        location = granted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(context, Manifest.permission.ACCESS_COARSE_LOCATION),
    )
}

private fun servicesAsKnown(state: UiState, access: DeviceAccess) = ServicesAsKnown(
    setlistFmKeyAvailable = state.setlistFmReady,
    setlistFmOwnKey = state.setlistFmApiKey.isNotBlank(),
    setlistFmSharedQuotaSpent = state.setlistFmSharedQuotaSpent,
    clashfinderUser = state.clashfinderUser,
    clashfinderKey = state.clashfinderPrivateKey.isNotBlank(),
    spotifyConnected = state.spotifyConnected,
    spotifyScope = state.grantedScope,
    knownTimelines = state.friends.size,
    photos = access.photos,
    calendar = access.calendar,
    location = access.location,
    gigActive = state.gossipActiveGig != null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsField(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onOpenHandover: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var access by remember { mutableStateOf(deviceAccess(context)) }
    // Coming back from the system's app settings is a resume, and the whole point of
    // the trip was to change what this reads.
    LifecycleResumeEffect(Unit) {
        access = deviceAccess(context)
        onPauseOrDispose { }
    }
    val graph = remember(state, access) { serviceGraph(servicesAsKnown(state, access)) }
    // By id, not by node: the node is rebuilt every time state moves, and the open
    // sheet should follow it (a Save that lights setlist.fm turns its status amber).
    var openId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    Scaffold(
        containerColor = Ground,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Ground,
                    titleContentColor = Ink,
                    navigationIconContentColor = Ink,
                ),
            )
        },
        // Outside the Field, so a pan never carries it away. Moving to a new phone lives
        // here rather than on the Exchange screen: that screen is for meeting *people*,
        // and this is the same person's second device.
        bottomBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onOpenHandover) { Text("Move to a new phone", color = Ink) }
                Spacer(Modifier.weight(1f))
                Text(
                    "Build ${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_SHA}",
                    color = Faint,
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Field(
            graph = graph,
            onBack = onBack,
            onOpen = { openId = it.id },
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        )
    }

    openId?.let { graph.node(it) }?.let { node ->
        ServiceSheet(
            node = node,
            state = state,
            viewModel = viewModel,
            onDismiss = { openId = null },
            onSpotifyLogin = { clientId ->
                // The browser takes over from here, and the sheet would hide the
                // snackbar if the login cannot even start.
                openId = null
                scope.launch {
                    viewModel.saveSettingsNow(state.setlistFmApiKey, clientId)
                    startSpotifyLogin(context, viewModel)?.let { snackbarHostState.showSnackbar(it) }
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------------
// The Field itself: one canvas for strips and lines, tiles laid over it, all in field
// points under one transform. Drag pans, pinch zooms, double-tap puts it back.

private enum class Drag { UNDECIDED, MOVE, BACK_OUT }

@Composable
private fun Field(
    graph: ServiceGraph,
    onBack: () -> Unit,
    onOpen: (ServiceNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.clipToBounds()) {
        val layout = remember(graph, maxWidth, maxHeight) {
            fieldLayout(graph, maxWidth.value, maxHeight.value)
        }
        // Kept across state changes (a Save must not snap the view back), reset only
        // when the viewport itself changes, as on rotation.
        var view by remember(maxWidth, maxHeight) { mutableStateOf(layout.defaultView) }
        val currentLayout by rememberUpdatedState(layout)
        val currentOnBack by rememberUpdatedState(onBack)

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { view = currentLayout.defaultView })
                }
                .pointerInput(Unit) {
                    fieldGestures(
                        layout = { currentLayout },
                        view = { view },
                        setView = { view = it },
                        onBack = { currentOnBack() },
                    )
                },
        ) {
            Box(
                Modifier
                    .wrapContentSize(Alignment.TopStart, unbounded = true)
                    .size(layout.width.dp, layout.height.dp)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = view.zoom
                        scaleY = view.zoom
                        translationX = view.offsetX.dp.toPx()
                        translationY = view.offsetY.dp.toPx()
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) { drawField(graph, layout) }
                // Compose order is reading order: strip by strip, left to right, then
                // My timeline, then the Alcoves.
                layout.strips.filter { it.role == ServiceRole.INPUT }.forEach { StripTiles(it, graph, layout, onOpen) }
                TimelineBox(graph.timelineLit, layout.timeline)
                layout.strips.filter { it.role == ServiceRole.ALCOVE }.forEach { StripTiles(it, graph, layout, onOpen) }
            }
        }
    }
}

/**
 * Pan, pinch, and back out. A one-finger drag to the right that *starts* with the
 * Field at its left edge is the way back, the same as `swipeRightToBack` everywhere
 * else; any other drag moves the Field. The choice is made once, at touch slop, so a
 * gesture is never both — a pan that happens to reach the edge does not then go back.
 */
private suspend fun PointerInputScope.fieldGestures(
    layout: () -> FieldLayout,
    view: () -> FieldView,
    setView: (FieldView) -> Unit,
    onBack: () -> Unit,
) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        val fromEdge = layout().atLeftEdge(view())
        var drag = Drag.UNDECIDED
        var moved = Offset.Zero
        var backOutX = 0f
        do {
            val event = awaitPointerEvent()
            val fingers = event.changes.count { it.pressed }
            val pan = event.calculatePan()
            // A second finger makes it a pinch, never a way back.
            if (drag == Drag.BACK_OUT && fingers > 1) drag = Drag.MOVE
            when (drag) {
                Drag.UNDECIDED -> {
                    moved += pan
                    if (fingers > 1) {
                        drag = Drag.MOVE
                    } else if (moved.getDistance() > slop) {
                        drag = if (fromEdge && moved.x > abs(moved.y)) Drag.BACK_OUT else Drag.MOVE
                        backOutX = moved.x
                    }
                }
                Drag.BACK_OUT -> backOutX += pan.x
                Drag.MOVE -> {
                    val zoom = event.calculateZoom()
                    val centroid = event.calculateCentroid(useCurrent = true)
                    var next = view()
                    if (zoom != 1f && centroid.isSpecified) {
                        next = layout().zoomAbout(next, centroid.x.toDp().value, centroid.y.toDp().value, zoom)
                    }
                    setView(layout().pan(next, pan.x.toDp().value, pan.y.toDp().value))
                }
            }
            // Once it is a drag, a tile under the finger must not also take it as a tap.
            if (drag != Drag.UNDECIDED) {
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
        if (drag == Drag.BACK_OUT && backOutX.toDp().value >= FieldDp.BackOutThreshold) onBack()
    }
}

private fun DrawScope.drawField(graph: ServiceGraph, layout: FieldLayout) {
    fun at(p: FieldPoint) = Offset(p.x.dp.toPx(), p.y.dp.toPx())
    val corner = CornerRadius(14.dp.toPx())
    layout.strips.forEach { s ->
        val topLeft = Offset(s.rect.left.dp.toPx(), s.rect.top.dp.toPx())
        val size = Size(s.rect.width.dp.toPx(), s.rect.height.dp.toPx())
        drawRoundRect(Raised, topLeft, size, corner)
        drawRoundRect(LineCol, topLeft, size, corner, style = Stroke(1.dp.toPx()))
    }
    // The trunk runs under the timeline box, which is opaque.
    line(Path().apply {
        moveTo(at(layout.join).x, at(layout.join).y)
        lineTo(at(layout.split).x, at(layout.split).y)
    }, graph.timelineLit)
    layout.lanes.forEach { lane ->
        line(lanePath(lane.corners.map(::at), at(lane.join)), graph.node(lane.id)?.lit == true)
    }
    layout.alcoveLines.forEach { a ->
        val from = at(a.from)
        val to = at(a.to)
        val mid = (from.x + to.x) / 2
        line(Path().apply {
            moveTo(from.x, from.y)
            cubicTo(mid, from.y, mid, to.y, to.x, to.y)
        }, graph.alcoveLineLit(a.id))
    }
}

/** A lane's corners with each bend rounded, then the curve on to the join ([curvePoints]'s). */
private fun DrawScope.lanePath(points: List<Offset>, end: Offset): Path {
    val r = FieldDp.CornerRadius.dp.toPx()
    return Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size - 1) {
            val prev = points[i - 1]
            val at = points[i]
            val next = points[i + 1]
            val inDir = (at - prev) / (at - prev).getDistance()
            val outDir = (next - at) / (next - at).getDistance()
            val a = at - inDir * r
            val b = at + outDir * r
            lineTo(a.x, a.y)
            quadraticTo(at.x, at.y, b.x, b.y)
        }
        val last = points.last()
        lineTo(last.x, last.y)
        val midX = (last.x + end.x) / 2
        cubicTo(midX, last.y, midX, end.y, end.x, end.y)
    }
}

/** Lit is a solid amber line; unlit is a thin dashed one, there but not carrying anything. */
private fun DrawScope.line(path: Path, lit: Boolean) {
    drawPath(
        path,
        color = if (lit) Amber else Faint,
        style = Stroke(
            width = if (lit) 2.dp.toPx() else 1.dp.toPx(),
            pathEffect = if (lit) null else PathEffect.dashPathEffect(floatArrayOf(12.dp.toPx(), 10.dp.toPx())),
        ),
    )
}

@Composable
private fun StripTiles(strip: FieldStrip, graph: ServiceGraph, layout: FieldLayout, onOpen: (ServiceNode) -> Unit) {
    Text(
        strip.strip.title.uppercase(),
        color = Faint,
        fontSize = 10.sp,
        letterSpacing = 1.2.sp,
        modifier = Modifier.offset((strip.rect.left + 12).dp, (strip.rect.top + 9).dp),
    )
    strip.ids.forEach { id ->
        val node = graph.node(id) ?: return@forEach
        val tile = layout.tile(id) ?: return@forEach
        ServiceTile(
            node,
            Modifier.offset((tile.center.x - FieldDp.ColumnWidth / 2).dp, tile.rect.top.dp),
        ) { onOpen(node) }
    }
}

@Composable
private fun ServiceTile(node: ServiceNode, modifier: Modifier, onOpen: () -> Unit) {
    val ring = RoundedCornerShape(14.dp)
    Column(
        modifier
            .width(FieldDp.ColumnWidth.dp)
            // One element per tile, saying everything the ring and the label say.
            .clearAndSetSemantics {
                contentDescription = node.spoken
                role = Role.Button
                onClick(label = "Open") {
                    onOpen()
                    true
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(FieldDp.TileSize.dp)
                .border(if (node.lit) 2.dp else 1.dp, if (node.lit) Amber else Faint, ring)
                .padding(3.dp)
                .clip(RoundedCornerShape(11.dp))
                // Pure black inside the ring: the one background Spotify allows its green
                // on, and the most contrast for every other mark.
                .background(Color.Black)
                .clickable(onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) {
            ServiceLogo(node)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            node.name,
            color = if (node.lit) Ink else Muted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Brand marks are never dimmed or tinted, lit or not — both Spotify's and MusicBrainz's
 * guidelines forbid it. An unlit brand says so with its ring, its label and its line.
 */
@Composable
private fun ServiceLogo(node: ServiceNode) {
    val tint = if (node.lit) Ink else Muted
    val icon = Modifier.size(26.dp)
    when (node.id) {
        // App icons that are their own tile: they fill it.
        "setlistfm" -> Image(
            painterResource(R.drawable.settings_setlistfm), null,
            Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
        )
        "clashfinder" -> Image(
            painterResource(R.drawable.settings_clashfinder), null,
            Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
        )
        // Spotify asks for clear space of half the mark's height around it.
        "spotify" -> Image(painterResource(R.drawable.settings_spotify), null, Modifier.size(25.dp))
        "musicbrainz" -> Image(painterResource(R.drawable.settings_musicbrainz), null, Modifier.height(28.dp))
        "photos" -> Icon(Icons.Outlined.PhotoLibrary, null, icon, tint = tint)
        "tickets" -> Icon(Icons.Outlined.ConfirmationNumber, null, icon, tint = tint)
        "location" -> Icon(Icons.Outlined.LocationOn, null, icon, tint = tint)
        "contacts" -> Icon(Icons.Outlined.People, null, icon, tint = tint)
        "gossip" -> Icon(Icons.Outlined.Sensors, null, icon, tint = tint)
        "calendar" -> Icon(Icons.Outlined.CalendarMonth, null, icon, tint = tint)
        else -> Text(node.name.take(1), color = tint)
    }
}

/** No words: the app's own mark, amber-lit when anything feeds it. */
@Composable
private fun TimelineBox(lit: Boolean, rect: FieldRect) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .offset(rect.left.dp, rect.top.dp)
            .size(rect.width.dp, rect.height.dp)
            .background(if (lit) TimelineLitFill else Raised, shape)
            .border(2.dp, if (lit) Amber else Faint, shape)
            .clearAndSetSemantics {
                contentDescription = "My timeline, ${if (lit) "lit" else "not lit"}"
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(R.drawable.ic_launcher_foreground), null,
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        )
    }
}

// ---------------------------------------------------------------------------------
// The sheet a tile opens: what the service gives, how to light it, and the controls
// that used to be the whole of Settings. They call the view model exactly as before.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServiceSheet(
    node: ServiceNode,
    state: UiState,
    viewModel: AppViewModel,
    onDismiss: () -> Unit,
    onSpotifyLogin: (clientId: String) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Raised) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(node.name, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                if (node.experimental) {
                    Spacer(Modifier.width(8.dp))
                    Text("Experimental", color = Muted, fontSize = 11.sp)
                }
            }
            Text(node.status, color = if (node.lit) Amber else Faint, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            node.unlocks.forEach {
                Text((if (node.lit) "✓ " else "· ") + it, color = if (node.lit) Ink else Muted, fontSize = 14.sp)
            }
            if (!node.lit && node.nextStep != null) {
                Spacer(Modifier.height(12.dp))
                Text(node.nextStep, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(16.dp))
            ServiceControls(node, state, viewModel, onSpotifyLogin)
        }
    }
}

@Composable
private fun ServiceControls(
    node: ServiceNode,
    state: UiState,
    viewModel: AppViewModel,
    onSpotifyLogin: (clientId: String) -> Unit,
) {
    val context = LocalContext.current
    val open = { url: String -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    when (node.id) {
        "setlistfm" -> {
            var apiKey by remember(state.setlistFmApiKey) { mutableStateOf(state.setlistFmApiKey) }
            // While the shared key is spent, the sheet leads with that (#457): someone who
            // followed the nudge here came for one thing.
            if (state.setlistFmSharedQuotaSpent) {
                Body(SHARED_QUOTA_MESSAGE)
            } else if (state.bundledSetlistFmKey) {
                Body(
                    "The setlist.fm API has no user login — to load your attended concerts, " +
                        "just enter your setlist.fm username on the My concerts tab.",
                )
            }
            TextButton(onClick = { open("https://www.setlist.fm/settings/api") }) {
                Text("Request one at setlist.fm/settings/api")
            }
            // The field is here whether or not a key is bundled. Hiding it when one was
            // meant "the bundled key cannot be replaced without a rebuild", which is the
            // opposite of what a bring-your-own-source app should offer — and it is the
            // only way out if the bundled key is ever revoked or rate-limited.
            KeyField(
                "setlist.fm API key", apiKey,
                hint = if (state.bundledSetlistFmKey) state.bundledSetlistFmHint else "",
            ) { apiKey = it }
            Spacer(Modifier.height(12.dp))
            AmberButton("Save", Modifier.fillMaxWidth()) {
                viewModel.saveSettings(apiKey, state.spotifyClientId)
            }
        }

        // No bundled fallback here, unlike setlist.fm: one account shared by every install
        // would put all of this app's traffic on a single credential against a host that
        // runs active bot protection.
        "clashfinder" -> {
            var user by remember(state.clashfinderUser) { mutableStateOf(state.clashfinderUser) }
            var key by remember(state.clashfinderPrivateKey) { mutableStateOf(state.clashfinderPrivateKey) }
            if (!node.lit) {
                Body(
                    "clashfinder needs a free account of your own. Register, then copy the " +
                        "private key off your account page. It is not your password.",
                )
            }
            TextButton(onClick = { open("https://clashfinder.com/m/account") }) {
                Text("Register at clashfinder.com")
            }
            KeyField("clashfinder username", user) { user = it }
            Spacer(Modifier.height(8.dp))
            KeyField("clashfinder private key", key) { key = it }
            Spacer(Modifier.height(12.dp))
            AmberButton("Save clashfinder account", Modifier.fillMaxWidth()) {
                viewModel.saveClashfinderCredentials(user, key)
            }
        }

        "spotify" -> {
            var clientId by remember(state.spotifyClientId) { mutableStateOf(state.spotifyClientId) }
            if (state.spotifyConnected) {
                OutlinedButton(
                    onClick = { viewModel.disconnectSpotify() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Log out", color = Ink) }
            } else {
                AmberButton(
                    "Log in with Spotify",
                    Modifier.fillMaxWidth(),
                    enabled = state.bundledSpotifyClientId || clientId.isNotBlank(),
                ) { onSpotifyLogin(clientId) }
            }
            Spacer(Modifier.height(16.dp))
            Body(
                "Spotify allows five signed-in users per app, so logging in may be " +
                    "refused. Use your own Spotify app instead: create one at " +
                    "developer.spotify.com/dashboard with Web API enabled and " +
                    "redirect URI $SPOTIFY_REDIRECT_URI, paste its Client ID " +
                    "below, Save, then log out and back in.",
            )
            // The steps above are the ones people get wrong, and a phone is a bad place
            // to follow them. The site has the same list plus a way to ask for one of
            // the five slots.
            TextButton(onClick = { open("https://magnus-encoded.github.io/station-to-station/") }) {
                Text("Step by step, and how to ask for a slot")
            }
            // Empty means "using the bundled one", and the placeholder says which bundled
            // one. Pre-filling it made a value the user never typed look like one they
            // had — and Save then pinned it as their override for good.
            KeyField(
                "Spotify Client ID", clientId,
                hint = if (state.bundledSpotifyClientId) state.bundledSpotifyHint else "",
            ) { clientId = it }
            Spacer(Modifier.height(12.dp))
            AmberButton("Save", Modifier.fillMaxWidth()) {
                viewModel.saveSettings(state.setlistFmApiKey, clientId)
            }
        }

        "photos", "calendar", "location" -> if (!node.lit) {
            AmberButton("Open app permissions", Modifier.fillMaxWidth()) {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ),
                )
            }
        }

        // Cards are swapped peer to peer and never expire on their own, so without this
        // the only way to drop a lane was to wipe the app.
        "contacts" -> if (state.friends.isEmpty()) {
            Body("Swipe left from your timeline to swap cards with someone, and their line opens beside yours.")
        } else {
            state.friends.forEach { friend ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(friend.name, color = Ink)
                        Text(
                            (if (friend.setlistfm.isBlank()) "" else "@${friend.setlistfm} · ") +
                                "${state.showsByFriend[friend.laneKey]?.size ?: 0} shows",
                            color = Muted,
                            fontSize = 12.sp,
                        )
                    }
                    TextButton(
                        onClick = { viewModel.removeFriend(friend) },
                        modifier = Modifier.semantics { contentDescription = "Remove ${friend.name}" },
                    ) { Text("Remove") }
                }
            }
        }

        // Experimental (#462): no two phones have completed a v2 Pass in the field yet.
        "gossip" -> Body(
            "Check in to share small public observations with nearby phones. Completing " +
                "the set keeps gossip active for 30 minutes, until 06:00 at the latest. " +
                "Delivery is best effort.",
        )

        "musicbrainz" -> Body(
            "MusicBrainz is an open music database. The app asks it for song titles when " +
                "a setlist.fm record is empty, and for artist names as you type. There is " +
                "nothing to set up.",
        )

        "tickets" -> Body("Share a ticket PDF to Station to Station from your mail or files app.")
    }
}

@Composable
private fun Body(text: String) {
    Text(text, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun KeyField(label: String, value: String, hint: String = "", onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { if (hint.isNotEmpty()) Text(hint, color = Faint) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AmberButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = OnAmber),
        modifier = modifier,
    ) { Text(text) }
}
