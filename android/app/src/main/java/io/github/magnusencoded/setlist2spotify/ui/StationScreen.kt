package io.github.magnusencoded.setlist2spotify.ui

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.magnusencoded.setlist2spotify.AppViewModel
import io.github.magnusencoded.setlist2spotify.BuildConfig
import io.github.magnusencoded.setlist2spotify.CoverCandidate
import io.github.magnusencoded.setlist2spotify.GigLink
import io.github.magnusencoded.setlist2spotify.MediaThumb
import io.github.magnusencoded.setlist2spotify.data.Friend
import io.github.magnusencoded.setlist2spotify.data.photos.PhotoRepository
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmSetlist
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmSong
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Station to Station — the timeline face of the app (working title).
// Flow: splash (log in with Spotify, or skip to setlists-only) → the timeline
// of your setlist.fm shows → a single night's real setlist → convert to a
// Spotify playlist. Import lives behind the "+" node, not the front door.
// ponytail: next enrichment pass is photos-from-the-night onto the concert
// (not just the playlist cover), and bringing the convert/login flow into
// this UI instead of the existing confirm screen.

// --- Nocturnal palette. Amber only ever marks a live/lit moment. ---
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
private val SpotifyGreen = Color(0xFF1DB954)
private val Slate = Color(0xFF6D7E9B) // the future / a connected-source, a cooler light
private val Danger = Color(0xFFE08A8A)

private val Serif = FontFamily.Serif

@Composable
fun SplashScreen(viewModel: AppViewModel, onProceed: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loginError by remember { mutableStateOf<String?>(null) }

    // Passing the splash (either button, or already onboarded on a later launch)
    // advances to the timeline.
    LaunchedEffect(state.onboarded) { if (state.onboarded) onProceed() }

    Box(Modifier.fillMaxSize().background(Ground).padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("◦", color = Amber, fontSize = 20.sp)
            Spacer(Modifier.height(10.dp))
            Text("Station to Station", fontFamily = Serif, fontSize = 30.sp, color = Ink)
            Spacer(Modifier.height(12.dp))
            Text(
                "Your concerts, kept. Connect Spotify to turn any night's setlist into a playlist — or skip and just browse the setlists.",
                color = Muted,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(36.dp))
            Button(
                onClick = {
                    // startActivity fires before we navigate away, so cancelling the
                    // splash's scope can't stop the browser from opening.
                    scope.launch {
                        loginError = startSpotifyLogin(context, viewModel)
                        viewModel.markOnboarded()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Log in with Spotify", fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { viewModel.markOnboarded() }, modifier = Modifier.fillMaxWidth()) {
                Text("Skip — just show me setlists", color = Muted)
            }
            loginError?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = Danger, fontSize = 12.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationTimelineScreen(
    viewModel: AppViewModel,
    onOpenEvent: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenConnect: () -> Unit,
    onOpenNearby: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = Ground,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ground, titleContentColor = Muted),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("◦ ", color = Amber, fontSize = 13.sp)
                        Text("Station to Station", fontFamily = Serif, fontSize = 16.sp, color = Muted)
                    }
                },
                actions = {
                    // Left/right axis is people: the way to others starts here.
                    IconButton(onClick = onOpenConnect) {
                        Icon(Icons.Filled.Person, contentDescription = "Connect with people", tint = Faint)
                    }
                    if (state.setlists.isNotEmpty()) {
                        IconButton(onClick = onOpenImport) {
                            Icon(Icons.Filled.Add, contentDescription = "Add shows", tint = Faint)
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Faint)
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            // Which build is actually on the phone. It is installed over Wi-Fi from CI,
            // and answering that by hashing APKs cost more than it should have.
            Text(
                "${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_SHA} · ${state.nodePlace}",
                color = Faint.copy(alpha = 0.5f),
                fontSize = 9.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 4.dp),
            )
            when {
                state.setlistsLoading && state.setlists.isEmpty() ->
                    CircularProgressIndicator(color = Amber, modifier = Modifier.align(Alignment.Center))

                state.setlists.isEmpty() -> EmptyTimeline(onAdd = onOpenImport)

                else -> {
                    val earliest = state.setlists.mapNotNull { it.year()?.toIntOrNull() }.minOrNull()
                    val listState = rememberLazyListState()
                    // Zooming out doesn't go anywhere: the strip beside my line opens and
                    // the other timelines slide into it, at my scale, on my spine.
                    // A card swap lands you here already zoomed out — you just went
                    // looking for their line, so it should be on screen.
                    val zoomedOut = state.zoomedOut
                    LaunchedEffect(state.justConnected) {
                        if (state.justConnected) {
                            viewModel.setZoomedOut(true)
                            viewModel.consumeJustConnected()
                        }
                    }
                    // An immutable set, swapped out on each toggle: a mutable list here
                    // is the same instance before and after, so remember() below could
                    // never see it change and the rows never rebuilt.
                    val expanded = state.openFestivals
                    val lanes = remember(state.friends) { state.friends.reversed() }
                    // Springy rather than timed: the other lines settle into place like
                    // something physical arriving, instead of a panel sliding.
                    val laneWidth by animateDpAsState(
                        if (zoomedOut) stripWidth(lanes.size) else 0.dp,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessLow,
                        ),
                        label = "lanes",
                    )
                    // Descending toward the past pulls the next page in before you hit
                    // the bottom, so history keeps flowing without a button. Measured
                    // against the rows actually laid out, not the raw show count: a
                    // festival collapses many shows into one row, so 20 shows can be 3
                    // rows that never scroll — and the old check never fired.
                    val nearPast by remember {
                        derivedStateOf {
                            val info = listState.layoutInfo
                            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                            last >= info.totalItemsCount - 3
                        }
                    }
                    LaunchedEffect(nearPast, state.setlistsLoading, state.setlists.size) {
                        if (nearPast && !state.setlistsLoading && state.setlists.size < state.setlistsTotal) {
                            viewModel.loadMoreSetlists()
                        }
                    }
                    // Pulling down at the top of the line opens a gap toward the future:
                    // the space where planning will live. It springs shut on release.
                    val scope = rememberCoroutineScope()
                    val pull = remember { Animatable(0f) }
                    val pullMax = with(LocalDensity.current) { 96.dp.toPx() }
                    val pullNest = remember {
                        object : NestedScrollConnection {
                            override fun onPostScroll(
                                consumed: Offset,
                                available: Offset,
                                source: NestedScrollSource,
                            ): Offset {
                                if (available.y <= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
                                scope.launch {
                                    pull.snapTo((pull.value + available.y * 0.4f).coerceAtMost(pullMax))
                                }
                                return Offset(0f, available.y)
                            }

                            override suspend fun onPreFling(available: Velocity): Velocity {
                                pull.animateTo(0f)
                                return Velocity.Zero
                            }
                        }
                    }

                    Column(Modifier.fillMaxSize()) {
                        Text(
                            buildString {
                                append("${state.setlists.size} shows")
                                if (earliest != null) append(" · since $earliest")
                            },
                            color = Faint,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 20.dp, top = 2.dp, bottom = 14.dp),
                        )
                        // Whose line is whose, only while more than one is showing.
                        // Scrolls sideways: the key is the one thing that grows without
                        // limit as friends are added, and it must not push the line off.
                        if (laneWidth > 0.dp) {
                            Row(
                                Modifier
                                    .horizontalScroll(rememberScrollState())
                                    .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                LaneKey(Amber, "You")
                                lanes.forEachIndexed { i, friend ->
                                    Spacer(Modifier.width(14.dp))
                                    LaneKey(railColor(i), friend.name)
                                }
                            }
                        }
                        PlanningPull(progress = { pull.value / pullMax }, heightPx = { pull.value })
                        LaunchedEffect(state.setlists) { viewModel.resolveFestivalNames() }
                        LaunchedEffect(zoomedOut) { if (zoomedOut) viewModel.loadFriendTimelines() }
                        val rows = remember(
                            state.setlists, state.festivalNames, lanes, state.showsByFriend, zoomedOut, expanded,
                        ) {
                            weaveTimelines(
                                mine = state.setlists,
                                festivalNames = state.festivalNames,
                                friends = if (zoomedOut) lanes else emptyList(),
                                theirs = if (zoomedOut) state.showsByFriend else emptyMap(),
                                expanded = expanded,
                            )
                        }
                        LaunchedEffect(rows, lanes) { logWovenRows(rows, lanes) }

                        // A station-to-station:// link names a gig, and only here can a
                        // gig be turned into a place: one inside a collapsed festival
                        // has no row of its own until the festival opens, so this may
                        // take two passes — open it, let the rows rebuild, then scroll.
                        LaunchedEffect(state.linkedGig, rows) {
                            val gig = state.linkedGig ?: return@LaunchedEffect
                            if (state.linkedGigAs == GigLink.SETLIST) {
                                viewModel.knownGig(gig)?.let {
                                    viewModel.openShow(it)
                                    viewModel.consumeGigLink()
                                    onOpenEvent()
                                }
                                return@LaunchedEffect
                            }
                            // A collapsed festival's own shows are only mine, so a night
                            // of theirs absorbed into it would never be found and never
                            // open the festival holding it.
                            // Last, not first: an open festival lists the gig again as a
                            // row of its own below its header, and that row is the place
                            // the link actually means.
                            val at = rows.indexOfLast { row ->
                                row.shows.any { it.id == gig } ||
                                    row.showsHereByFriends.any { it.id == gig }
                            }
                            if (at < 0) return@LaunchedEffect
                            val row = rows[at]
                            val insideClosedFestival =
                                row.node is TimelineNode.Festival && row.key !in expanded
                            if (insideClosedFestival) {
                                viewModel.openFestival(row.key)
                                return@LaunchedEffect
                            }
                            // +1 for the future prompt, which is item 0 of the list.
                            listState.animateScrollToItem(at + 1)
                            viewModel.consumeGigLink()
                        }

                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .nestedScroll(pullNest)
                                // Swipe the timeline left to start connecting with someone
                                // nearby — the "act on this level" gesture, people axis.
                                .pointerInput(Unit) {
                                    val threshold = 90.dp.toPx()
                                    var dragX = 0f
                                    detectHorizontalDragGestures(
                                        onDragStart = { dragX = 0f },
                                        onDragEnd = { if (dragX <= -threshold) onOpenNearby() },
                                        onHorizontalDrag = { _, delta -> dragX += delta },
                                    )
                                }
                                // Pinch out to open the other timelines beside mine; pinch
                                // back in to close them again. Nothing navigates.
                                .pointerInput(state.friends) {
                                    detectPinch(
                                        onZoomOut = { viewModel.setZoomedOut(true) },
                                        onZoomIn = { viewModel.setZoomedOut(false) },
                                    )
                                },
                        ) {
                            // The future edge: scroll up toward what's ahead.
                            item { FuturePrompt() }
                            itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                                val isFirst = index == 0
                                val rails: @Composable () -> Unit =
                                    { PeopleRails(row, rows.getOrNull(index + 1), lanes, laneWidth, state.nodePlace) }
                                val nodeX = crossingX(row, lanes, laneWidth, state.nodePlace)
                                when (val node = row.node) {
                                    is TimelineNode.Concert -> TimelineItem(
                                        setlist = node.setlist,
                                        highlight = isFirst && row.mine,
                                        mine = row.mine,
                                        laneWidth = laneWidth,
                                        inside = row.depth > 0,
                                        nodeX = nodeX,
                                        shared = row.shared,
                                        rails = rails,
                                        photos = state.photosBySetlist[node.setlist.id].orEmpty(),
                                        loadPhotoPreview = viewModel::photoPreview,
                                        onClick = {
                                            viewModel.openShow(node.setlist)
                                            onOpenEvent()
                                        },
                                    )

                                    // A festival opens where it stands rather than pushing
                                    // you into a screen of its own.
                                    is TimelineNode.Festival -> FestivalItem(
                                        festival = node,
                                        highlight = isFirst,
                                        open = row.key in expanded,
                                        mine = row.mine,
                                        laneWidth = laneWidth,
                                        nodeX = nodeX,
                                        sharedCount = row.sharedCount,
                                        theirCount = row.showsHereByFriends.size,
                                        // Company has a colour of its own — a night two
                                        // friends shared is nobody's lane colour either.
                                        theirColor = if (row.others.size > 1) Crossed
                                        else railColor(nodeHost(row, lanes).coerceAtLeast(0)),
                                        rails = rails,
                                        onClick = {
                                            viewModel.toggleFestival(row.key)
                                        },
                                    )
                                }
                            }
                            // The past edge: a quiet spinner while the next page flows in.
                            if (state.setlistsLoading && state.setlists.isNotEmpty()) {
                                item {
                                    Row(
                                        Modifier.fillMaxWidth().padding(16.dp),
                                        horizontalArrangement = Arrangement.Center,
                                    ) { CircularProgressIndicator(color = Amber, modifier = Modifier.size(22.dp)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The gap that opens when you pull down past the top of your line: the line keeps
 * going up, into the shows you haven't been to yet. ponytail: a signpost, not a
 * destination — releasing springs it shut until planning is somewhere to go.
 */
@Composable
private fun PlanningPull(progress: () -> Float, heightPx: () -> Float) {
    val density = LocalDensity.current
    val h = with(density) { heightPx().toDp() }
    if (h <= 0.dp) return
    Column(
        Modifier.fillMaxWidth().height(h).alpha((progress() * 1.4f).coerceIn(0f, 1f)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.width(2.dp).height(h * 0.4f).background(LineCol))
        Spacer(Modifier.height(6.dp))
        Text("↑  PLANNING", color = Slate, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
        Text("the shows ahead", color = Faint, fontSize = 11.sp)
    }
}

/** Top of the timeline — the future. Bandsintown/planning lives here (not wired yet). */
@Composable
private fun FuturePrompt() {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 18.dp)) {
        Text("↑  THE FUTURE", color = Slate, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(4.dp))
        Text("Connect Bandsintown to see the shows ahead — coming soon.", color = Faint, fontSize = 12.sp)
    }
}

/** The empty spine: one lit node you tap to bring in your shows. */
@Composable
private fun EmptyTimeline(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.width(2.dp).height(64.dp).background(LineCol))
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(AmberSoft)
                .border(1.5.dp, Amber, CircleShape)
                .clickable(onClick = onAdd),
            contentAlignment = Alignment.Center,
        ) { Text("+", color = Amber, fontSize = 28.sp) }
        Box(Modifier.width(2.dp).height(30.dp).background(LineCol))
        Spacer(Modifier.height(16.dp))
        Text("Add your first show", fontFamily = Serif, fontSize = 18.sp, color = Ink)
        Spacer(Modifier.height(4.dp))
        Text("Pull your history from setlist.fm.", color = Muted, fontSize = 13.sp)
    }
}

/** The setlist.fm import, reached from the "+" node. Pops itself once shows land. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(viewModel: AppViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val startCount = remember { viewModel.state.value.setlists.size }
    var username by remember { mutableStateOf(state.mySetlistFmUser) }
    var apiKey by remember { mutableStateOf("") }

    // Leave for the timeline the moment an import actually brings shows in.
    LaunchedEffect(state.setlists.size) {
        if (state.setlists.size != startCount && state.setlists.isNotEmpty()) onDone()
    }

    Scaffold(
        containerColor = Ground,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ground, titleContentColor = Ink),
                title = { Text("Add your shows", fontFamily = Serif, fontSize = 18.sp, color = Ink) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Faint)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(28.dp).fillMaxWidth()) {
            Text(
                "Your concert history already lives on setlist.fm. Enter your username and your line fills itself in.",
                color = Muted,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(22.dp))
            if (!state.setlistFmReady) {
                StationField(apiKey, { apiKey = it }, "setlist.fm API key")
                Spacer(Modifier.height(10.dp))
            }
            StationField(username, { username = it }, "setlist.fm username", imeDone = true)
            state.error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = Danger, fontSize = 12.sp)
            }
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    viewModel.importAttended(username.trim(), if (!state.setlistFmReady) apiKey else null)
                },
                enabled = username.isNotBlank() &&
                    (state.setlistFmReady || apiKey.isNotBlank()) &&
                    !state.setlistsLoading,
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color(0xFF241A06)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.setlistsLoading) {
                    CircularProgressIndicator(color = Color(0xFF241A06), modifier = Modifier.size(18.dp))
                } else {
                    Text("Import from setlist.fm", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StationField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    imeDone: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = if (imeDone) KeyboardOptions(imeAction = ImeAction.Done) else KeyboardOptions.Default,
        keyboardActions = KeyboardActions.Default,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Amber,
            unfocusedBorderColor = LineLit,
            focusedTextColor = Ink,
            unfocusedTextColor = Ink,
            cursorColor = Amber,
            focusedLabelColor = Amber,
            unfocusedLabelColor = Faint,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun TimelineItem(
    setlist: FmSetlist,
    highlight: Boolean,
    onClick: () -> Unit,
    mine: Boolean = true,
    laneWidth: Dp = 0.dp,
    inside: Boolean = false,
    nodeX: Dp = SpineX,
    shared: Boolean = false,
    rails: @Composable () -> Unit = {},
    photos: List<Uri> = emptyList(),
    loadPhotoPreview: suspend (Uri) -> MediaThumb = { MediaThumb(null) },
) {
    val songCount = setlist.performed().size
    val zoomedOut = laneWidth > 0.dp
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(onClick = onClick),
    ) {
        // My own spine, always at the same place. A show only someone else was at
        // leaves it bare: the line runs on, the edge between my nodes just gets longer.
        Box(Modifier.width(SpineWidth + laneWidth).fillMaxHeight()) {
            rails()
            // Zoomed out the lines are the canvas's job — it has friends' lanes to draw.
            if (!zoomedOut) {
                Box(
                    Modifier.padding(start = SpineX).width(2.dp).fillMaxHeight()
                        .background(Amber.copy(alpha = 0.3f)),
                )
            }
            if (mine) {
                val size = if (inside) 10.dp else 14.dp
                Box(
                    Modifier
                        .padding(start = nodeX - size / 2 + 1.dp, top = 6.dp)
                        .size(size)
                        .clip(CircleShape)
                        .background(
                            // See-through: a node is a ring, and the lines stop at
                            // its rim rather than being hidden behind a fill.
                            when {
                                shared -> Color.Transparent
                                highlight -> AmberSoft
                                else -> Color.Transparent
                            },
                        )
                        .border(
                            2.dp,
                            // Amber is what "mine" looks like at every resolution; the
                            // night our lines became one gets a colour of its own.
                            when {
                                shared -> Crossed
                                highlight -> Amber
                                else -> Amber.copy(alpha = 0.6f)
                            },
                            CircleShape,
                        ),
                )
            }
        }
        Column(Modifier.padding(start = if (inside) 14.dp else 0.dp, end = 18.dp, bottom = 22.dp)) {
            Text(
                setlist.readableDate() ?: "Unknown date",
                color = Faint,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.0.sp,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                setlist.artist?.name ?: "Unknown artist",
                fontFamily = Serif,
                fontSize = 17.sp,
                color = if (mine) Ink else Muted,
            )
            Spacer(Modifier.height(2.dp))
            Text(setlist.venueLine(), color = Muted, fontSize = 13.sp)
            // The Reliver's own keepsakes of the night, small enough not to compete
            // with the facts around them — under the artist, over the song count.
            if (photos.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row {
                    photos.take(3).forEach { uri ->
                        PhotoThumb(uri, size = 20.dp, loadPreview = loadPhotoPreview)
                        Spacer(Modifier.width(4.dp))
                    }
                }
            }
            Spacer(Modifier.height(7.dp))
            Text(
                if (songCount > 0) "$songCount songs" else "setlist not logged",
                color = Faint,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun LaneKey(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(12.dp).background(color))
        Spacer(Modifier.width(5.dp))
        Text(label, color = Muted, fontSize = 11.sp)
    }
}

/**
 * One lane per friend, opening out to the right of my spine as you zoom out. Kept
 * close to the spine: the further out they sit, the harder a line has to swerve to
 * come and meet mine, and the swerve is what reads as an interruption.
 */
internal val LaneStep = 20.dp

/**
 * How wide the strip may grow. Past this the lanes tighten instead of pushing the
 * text off the phone, so the view survives more friends than fit at full spacing.
 */
private val MaxStripWidth = 132.dp

/** Lane spacing for [count] friends: full step until the strip is full, then tighter. */
internal fun laneStep(count: Int): Dp =
    if (count <= 0) LaneStep else minOf(LaneStep, MaxStripWidth / count)

/** The strip's width at [count] friends — never more than [MaxStripWidth]. */
internal fun stripWidth(count: Int): Dp = laneStep(count) * count

/** My own line. Not a lane: it is the fixed thing every lane is measured against. */
internal const val Spine = -1

private fun laneX(index: Int, step: Dp) = SpineX + step * (index + 1)

/** Lane positions are continuous, so a node can sit between two of them. -1 is my spine. */
private fun laneXf(offset: Float, step: Dp) = SpineX + step * (offset + 1f)

/**
 * Where a crossing sits among the lines that meet at it. Undecided by argument, so
 * all three are here to be looked at: the lines converge on whichever this picks, and
 * mine converges too unless the answer happens to be my own spine.
 *
 * ponytail: cycled by a key (see MainActivity) rather than a setting, because it is a
 * question being answered, not a preference being offered.
 */
enum class NodePlace {
    /** The innermost line that was there — mine whenever I am one of them. */
    INNERMOST,

    /** The average of everyone who was there; belongs to no one. */
    CENTRED,

    /** The outermost line that was there — the furthest visitor. */
    OUTERMOST,
}

/** Which lines were at a row: [Spine] for me, plus a lane index per friend present. */
internal fun linesAt(row: WovenRow, lanes: List<Friend>): List<Int> = buildList {
    if (row.mine) add(Spine)
    lanes.forEachIndexed { i, f ->
        if (row.others.any { it.setlistfm == f.setlistfm }) add(i)
    }
}

/** Where the row's node sits, as a lane offset — fractional under [NodePlace.CENTRED]. */
internal fun nodeOffset(row: WovenRow, lanes: List<Friend>, place: NodePlace): Float {
    val at = linesAt(row, lanes)
    if (at.isEmpty()) return Spine.toFloat()
    return when (place) {
        NodePlace.INNERMOST -> at.min().toFloat()
        NodePlace.OUTERMOST -> at.max().toFloat()
        NodePlace.CENTRED -> at.sum().toFloat() / at.size
    }
}

/**
 * Where a line is drawn at a row: on the node if it was there, otherwise its own lane.
 * [line] is [Spine] for mine or a lane index for a friend's.
 */
internal fun lineOffset(row: WovenRow?, line: Int, lanes: List<Friend>, place: NodePlace): Float {
    if (row == null) return line.toFloat()
    val there = if (line == Spine) row.mine else {
        lanes.getOrNull(line)?.let { f -> row.others.any { it.setlistfm == f.setlistfm } } == true
    }
    return if (there) nodeOffset(row, lanes, place) else line.toFloat()
}

/**
 * Which line a row's node sits on. Lines that share a node become one line, so a
 * night has exactly one node — mine when I was there (my line never moves to meet
 * anyone), otherwise the innermost lane among the friends who were, which the
 * others come to. Returns [Spine] or a lane index.
 */
internal fun nodeHost(row: WovenRow, lanes: List<Friend>): Int {
    if (row.mine) return Spine
    return lanes.indices.firstOrNull { i ->
        row.others.any { it.setlistfm == lanes[i].setlistfm }
    } ?: Spine
}

/**
 * Which line [friend] is drawn on at [row]: the node's host if they were there,
 * otherwise their own lane. This is the whole merge rule — asking it per friend is
 * what makes A parting on the row B joins two independent answers instead of one
 * shared boolean. Replaces `merged()`, whose Boolean could only ever mean "with me".
 */
internal fun hostLane(row: WovenRow?, friend: Friend, lanes: List<Friend>): Int {
    val own = lanes.indexOfFirst { it.setlistfm == friend.setlistfm }
    if (row == null || row.others.none { it.setlistfm == friend.setlistfm }) return own
    return nodeHost(row, lanes)
}

/** Is this friend's line one line with someone else's here — mine, or another friend's? */
internal fun joinedAt(row: WovenRow?, friend: Friend): Boolean {
    if (row == null || row.others.none { it.setlistfm == friend.setlistfm }) return false
    return row.mine || row.others.size > 1
}

/**
 * Where a row's node sits. My line never moves — a night we shared happens *on* my
 * line, and theirs comes to meet it. Putting the node between the two made both
 * timelines leave their own path to attend it.
 */
internal fun crossingX(
    row: WovenRow,
    lanes: List<Friend>,
    laneWidth: Dp,
    place: NodePlace = NodePlace.INNERMOST,
): Dp {
    val offset = nodeOffset(row, lanes, place)
    if (laneWidth <= 0.dp || offset == Spine.toFloat()) return SpineX
    val step = laneStep(lanes.size)
    // The lanes are still sliding out while the strip opens; keep the node with them.
    val open = (laneWidth / stripWidth(lanes.size)).coerceIn(0f, 1f)
    return SpineX + (laneXf(offset, step) - SpineX) * open
}

/**
 * How much of a row's tail is spent bending toward the next node. A junction belongs
 * to the **edge** between two nodes, not to the sliver above the lower one: with the
 * whole turn crammed into [nodeY], a line that steps out for one gig and comes back
 * drew a rounded rectangle instead of parting and rejoining.
 */
private val EdgeBend = 56.dp

/** One person walking alone, and what each extra one on the same line adds. */
private val LineWidth = 2.dp
private val PerPerson = 1.2.dp

/**
 * The woven spine as facts rather than pixels: `adb logcat -s Woven`.
 *
 * Every rule in this file is visual, and the only way to check one has been to read
 * a screenshot — which is slow and, at least once, wrong: three lines converging was
 * read off an image as a merge that the data said never happened. A row's model and
 * the lane each person is drawn on are both computable here, so they can be asserted
 * on instead of squinted at. Debug builds only.
 */
internal fun logWovenRows(rows: List<WovenRow>, lanes: List<Friend>) {
    if (!BuildConfig.DEBUG) return
    Log.d("Woven", "--- ${rows.size} rows, lanes=${lanes.map { it.setlistfm }} ---")
    rows.forEach { row ->
        val where = lanes.joinToString(" ") { f ->
            val lane = hostLane(row, f, lanes)
            "${f.setlistfm}@${if (lane == Spine) "spine" else "lane$lane"}"
        }
        Log.d(
            "Woven",
            "${row.date} d${row.depth} ${if (row.mine) "mine" else "theirs"} " +
                "node=${if (row.node is TimelineNode.Festival) "festival" else "gig"} " +
                "with=[${row.others.joinToString(",") { it.setlistfm }}] " +
                "together=${row.sharedCount} theirs=${row.showsHereByFriends.size} " +
                "host=${nodeHost(row, lanes)} $where key=${row.key}",
        )
    }
}

@Composable
internal fun PeopleRails(
    row: WovenRow,
    next: WovenRow?,
    friends: List<Friend>,
    laneWidth: Dp,
    place: NodePlace = NodePlace.INNERMOST,
) {
    if (laneWidth <= 0.dp || friends.isEmpty()) return
    Canvas(Modifier.fillMaxSize()) {
        val spineX = SpineX.toPx() + 1.dp.toPx()
        val step = laneStep(friends.size)
        val open = (laneWidth / stripWidth(friends.size)).coerceIn(0f, 1f) * friends.size
        val nodeY = if (row.node is TimelineNode.Festival) 15.dp.toPx() else 13.dp.toPx()
        val h = size.height
        val stroke = Stroke(width = 2.dp.toPx())

        // A node is a ring you see through, so no line may be drawn inside one:
        // every line stops at the rim and picks up again on the far side. This is
        // the outer radius of whichever node this row draws.
        val nodeR = when {
            row.node is TimelineNode.Festival -> 11.dp.toPx()
            row.depth > 0 -> 5.dp.toPx()
            else -> 7.dp.toPx()
        }

        // Every line is drawn the same way, mine included. Mine is line -1 and is
        // only special in that its own lane is the spine — under any placement but
        // INNERMOST it travels to a crossing like anyone else.
        val lines = listOf(Spine) + friends.indices

        fun slideOf(line: Int) = if (line == Spine) 1f else (open - line).coerceIn(0f, 1f)
        fun xOf(offset: Float, line: Int): Float {
            val slide = slideOf(line)
            return spineX + (laneXf(offset, step).toPx() - spineX) * slide
        }

        fun thereAt(r: WovenRow, line: Int) =
            if (line == Spine) r.mine
            else friends.getOrNull(line)?.let { f -> r.others.any { it.setlistfm == f.setlistfm } } == true

        // How many lines lie on this one where it runs. Merged lines are one line by
        // definition, so without this two of them draw the same path twice and look
        // exactly like one — the weight is what says how many.
        fun peopleAt(r: WovenRow?, line: Int): Int {
            if (r == null) return 1
            val here = lineOffset(r, line, friends, place)
            return lines.count { lineOffset(r, it, friends, place) == here && thereAt(r, it) }
                .coerceAtLeast(1)
        }

        // How many travel a bend together: they must share *both* of its ends.
        fun peopleAlong(to: WovenRow?, line: Int): Int {
            if (to == null) return peopleAt(row, line)
            val a = lineOffset(row, line, friends, place)
            val b = lineOffset(to, line, friends, place)
            return lines.count {
                lineOffset(row, it, friends, place) == a && lineOffset(to, it, friends, place) == b
            }.coerceAtLeast(1)
        }

        // Green means more than one line is on this stretch, and weight means how
        // many. Colour follows the geometry rather than the endpoints: where lines
        // lie on top of each other they *are* one line and have to read as one, and
        // where one peels away it is alone again and takes its own colour back.
        fun paint(people: Int, present: Boolean, line: Int): Pair<Color, Stroke> {
            val colour = when {
                people > 1 -> Crossed
                line == Spine -> Amber.copy(alpha = if (present) 0.85f else 0.4f)
                present -> railColor(line)
                else -> LineCol
            }
            return colour to Stroke(width = LineWidth.toPx() + PerPerson.toPx() * (people - 1))
        }

        val nodeAt = nodeOffset(row, friends, place)

        lines.forEach { line ->
            if (slideOf(line) <= 0f) return@forEach

            val x = xOf(lineOffset(row, line, friends, place), line)
            val toX = if (next == null) x else xOf(lineOffset(next, line, friends, place), line)
            val here = thereAt(row, line)

            // Where this line has a node, it stops at the rim and starts again on
            // the far side — nothing is drawn inside a node. A line only meets a
            // node it belongs to, so someone who wasn't here just runs past.
            val gap = if (here) nodeR else 0f

            val (atX, atXStroke) = paint(peopleAt(row, line), here, line)

            if (nodeY - gap > 0f) {
                val approach = Path().apply {
                    moveTo(x, 0f)
                    lineTo(x, nodeY - gap)
                }
                drawPath(approach, atX, style = atXStroke)
            }

            // The last stretch of a row belongs to the edge ahead, and every line gets
            // it — not only the ones that bend. Company peeling away leaves a line
            // alone even when that line never moves, so my spine has to stop being
            // green there too, or it claims a crossing after the others have gone.
            // Never longer than the room below the node, or a short row would draw
            // its straight stretch backwards before turning.
            val bendLen = minOf((h - nodeY - gap) * 0.8f, EdgeBend.toPx()).coerceAtLeast(0f)

            val body = Path().apply {
                moveTo(x, nodeY + gap)
                lineTo(x, h - bendLen)
            }
            drawPath(body, atX, style = atXStroke)

            val (leaving, leavingStroke) = paint(peopleAlong(next, line), here, line)
            val tail = Path().apply {
                moveTo(x, h - bendLen)
                if (toX == x) lineTo(x, h)
                else cubicTo(x, h - bendLen * 0.45f, toX, h - bendLen * 0.55f, toX, h)
            }
            drawPath(tail, leaving, style = leavingStroke)

            // One node per night, drawn once by the innermost line that was there.
            // My own rows and festivals draw their own, so this only fills the gap
            // for a gig of theirs.
            val drawsNode = here && !row.mine && row.node !is TimelineNode.Festival &&
                line == linesAt(row, friends).minOrNull()
            if (drawsNode) {
                val nodeX = xOf(nodeAt, line)
                val joined = linesAt(row, friends).size > 1
                drawCircle(
                    if (joined) Crossed else railColor(line),
                    6.dp.toPx(),
                    Offset(nodeX, nodeY),
                    style = stroke,
                )
            }
        }
    }
}

// --- Event view: a single night, its real setlist as a spine ---

internal sealed interface EventRow {
    data object Encore : EventRow

    /**
     * [number] is null for a tape track. It played in the room, so it stays on the
     * line — but it is not one of the songs the band performed, and numbering it
     * pushed every song after it out by one against the setlist on setlist.fm.
     */
    data class SongItem(val number: Int?, val song: FmSong) : EventRow
}

internal fun FmSetlist.eventRows(): List<EventRow> = buildList {
    var n = 0
    sets?.set.orEmpty().forEach { set ->
        if (set.encore != null) add(EventRow.Encore)
        // A nameless entry is setlist.fm's placeholder for a song nobody could
        // identify; it has nothing to show and must not take a number either.
        set.song.filter { it.name.isNotBlank() }.forEach { song ->
            add(EventRow.SongItem(if (song.tape) null else ++n, song))
        }
    }
}

/** A gig photo or video frame, decoded lazily and cached by its own [uri] key. */
@Composable
private fun PhotoThumb(uri: Uri, size: Dp, loadPreview: suspend (Uri) -> MediaThumb, modifier: Modifier = Modifier) {
    var thumb by remember(uri) { mutableStateOf(MediaThumb(null)) }
    LaunchedEffect(uri) { thumb = loadPreview(uri) }
    Box(modifier.size(size).clip(RoundedCornerShape(6.dp)).background(Raised2)) {
        thumb.bitmap?.let {
            Image(
                it.asImageBitmap(),
                contentDescription = "Your photo from this show",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (thumb.isVideo) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Video",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(size / 3)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.4f)),
            )
        }
    }
}

/**
 * The Reliver's own pictures and clips on a gig, picked straight from the system
 * photo picker — no gallery permission, and no attempt at matching the night by
 * date; that's [GigPhotoSuggestions]' job. Tap opens one in whatever app the phone
 * already uses for the format. Long-press any of them to enter arranging — every
 * photo gets an [x], dragging one reorders the strip, and a tap anywhere that
 * isn't an [x] leaves arranging again.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GigPhotos(
    photos: List<Uri>,
    loadPreview: suspend (Uri) -> MediaThumb,
    onAdd: () -> Unit,
    onOpen: (Uri) -> Unit,
    onRemove: (Uri) -> Unit,
    onReorder: (List<Uri>) -> Unit,
) {
    var arranging by remember { mutableStateOf(false) }
    // A working copy so a drag can preview the new order before it's committed —
    // resynced whenever the real list changes under it (an add, a remove, or the
    // commit at the end of a drag landing back through [photos]).
    val order = remember(photos) { photos.toMutableStateList() }
    val strideX = with(LocalDensity.current) { (GigPhotoSize + ItemGap).toPx() }

    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            // Catches a tap on the blank space past the last photo — arranging
            // has to be dismissible from anywhere, not only from a thumbnail.
            .pointerInput(arranging) {
                if (arranging) detectTapGestures(onTap = { arranging = false })
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        order.forEach { uri ->
            var dragX by remember(uri) { mutableStateOf(0f) }
            Box(
                Modifier
                    .offset { IntOffset(dragX.roundToInt(), 0) }
                    .then(
                        if (arranging) {
                            Modifier
                                .pointerInput(uri, order.size) {
                                    detectDragGestures(
                                        onDrag = { change, amount -> change.consume(); dragX += amount.x },
                                        onDragEnd = {
                                            val moves = (dragX / strideX).roundToInt()
                                            val from = order.indexOf(uri)
                                            val to = (from + moves).coerceIn(0, order.lastIndex)
                                            if (to != from) order.add(to, order.removeAt(from))
                                            dragX = 0f
                                            onReorder(order.toList())
                                        },
                                        onDragCancel = { dragX = 0f },
                                    )
                                }
                                .pointerInput(uri) { detectTapGestures(onTap = { arranging = false }) }
                        } else {
                            Modifier.combinedClickable(
                                onClick = { onOpen(uri) },
                                onLongClick = { arranging = true },
                            )
                        },
                    ),
            ) {
                PhotoThumb(uri, size = GigPhotoSize, loadPreview = loadPreview)
                if (arranging) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(3.dp)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Danger)
                            .clickable { onRemove(uri) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(13.dp)) }
                }
            }
            Spacer(Modifier.width(ItemGap))
        }
        if (!arranging) {
            Box(
                Modifier
                    .size(GigPhotoSize)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Raised2)
                    .border(1.dp, LineLit, RoundedCornerShape(10.dp))
                    .clickable(onClick = onAdd),
                contentAlignment = Alignment.Center,
            ) { Text("+", color = Muted, fontSize = 26.sp) }
        }
    }
}

/** Big enough to actually look like a keepsake, not a chip. */
private val GigPhotoSize = 108.dp
private val ItemGap = 10.dp

/**
 * The same same-night gallery search [CoverPicker] does for a playlist cover,
 * offered as one-tap adds to the gig's keepsakes instead of a single chosen cover.
 */
@Composable
private fun GigPhotoSuggestions(
    candidates: List<CoverCandidate>,
    loading: Boolean,
    searched: Boolean,
    permissionGranted: Boolean,
    already: List<Uri>,
    onRequestPermission: () -> Unit,
    onAdd: (Uri) -> Unit,
) {
    val offered = remember(candidates, already) { candidates.filter { it.uri !in already } }
    when {
        !permissionGranted -> TextButton(onClick = onRequestPermission, contentPadding = PaddingValues(vertical = 2.dp)) {
            Text("Suggest photos from that night", color = Muted, fontSize = 12.sp)
        }
        loading -> Text("Looking through your gallery…", color = Faint, fontSize = 12.sp)
        offered.isEmpty() -> if (searched) {
            Text("No more photos from that night in your gallery.", color = Faint, fontSize = 12.sp)
        }
        else -> Column {
            Text("From that night — tap to add", color = Faint, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                offered.forEach { candidate ->
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Raised2)
                            .clickable { onAdd(candidate.uri) },
                    ) {
                        candidate.preview?.let {
                            Image(
                                it.asImageBitmap(),
                                contentDescription = "Suggested photo from that night",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationEventScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onConvert: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val setlist = state.selectedSetlist
    val context = LocalContext.current
    // What this night already became. Every one of them: each url may be in
    // somebody's hands, so none of them stops being reachable from here.
    val made = setlist?.let { state.playlistsBySetlist[it.id] }.orEmpty()
    val gigPhotos = setlist?.let { state.photosBySetlist[it.id] }.orEmpty()
    // The Reliver picks straight from the system photo (and video) picker — no
    // gallery permission needed for that path, unlike the suggestions below.
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> if (uris.isNotEmpty()) setlist?.let { viewModel.addGigPhotos(it.id, uris) } }
    // Gallery access is only ever asked for after the "suggest" tap, so opening
    // a gig never triggers a permission prompt on its own.
    val gigSuggestPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.loadGigPhotoSuggestions() }
    // Silent when permission isn't there yet — same guard as the prompt above,
    // so a gig already granted access just re-searches without another tap.
    LaunchedEffect(setlist?.id) { viewModel.loadGigPhotoSuggestions() }

    Scaffold(
        containerColor = Ground,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ground, titleContentColor = Faint),
                title = { Text(setlist?.year() ?: "", color = Faint, fontSize = 12.sp, letterSpacing = 1.5.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Faint)
                    }
                },
            )
        },
        bottomBar = {
            if (setlist != null && setlist.performed().isEmpty() && setlist.url != null) {
                // The Historian's crumb: nothing to convert here, but a nudge toward
                // fixing the gap at the source is better than nothing.
                Column(
                    Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "‹ swipe to open this setlist on setlist.fm",
                        color = Amber,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clickable {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(setlist.url)))
                            }
                            .padding(vertical = 6.dp),
                    )
                }
            } else if (setlist != null && setlist.performed().isNotEmpty()) {
                // A quiet, tappable hint rather than a big CTA — the same action the
                // swipe fires, kept visible so it's discoverable and reachable without
                // the gesture.
                val convert = {
                    viewModel.selectSetlist(setlist)
                    onConvert()
                }
                Column(
                    Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Once a night has a playlist, opening it is the primary offer and
                    // making another is the aside — converting twice is the rare case.
                    if (made.isNotEmpty()) {
                        made.forEach { playlist ->
                            Row(
                                Modifier
                                    .clickable {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(playlist.url)),
                                        )
                                    }
                                    .padding(vertical = 6.dp, horizontal = 20.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(7.dp).clip(CircleShape).background(SpotifyGreen))
                                Spacer(Modifier.width(8.dp))
                                // One playlist needs no naming; several have to be told
                                // apart, because the one you sent is a particular one.
                                Text(
                                    if (made.size == 1) "Open the playlist ↗"
                                    else "${playlist.name.ifBlank { "Playlist" }} ↗",
                                    color = SpotifyGreen,
                                    fontSize = 14.sp,
                                )
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "‹ swipe to make another",
                            color = Faint,
                            fontSize = 12.sp,
                            modifier = Modifier.clickable(onClick = convert).padding(vertical = 4.dp),
                        )
                    } else {
                        Text(
                            "‹ swipe to open as a Spotify playlist",
                            color = Amber,
                            fontSize = 13.sp,
                            modifier = Modifier.clickable(onClick = convert).padding(vertical = 6.dp),
                        )
                    }
                }
            }
        },
    ) { padding ->
        if (setlist == null) {
            Box(Modifier.padding(padding).fillMaxSize()) {
                Text("No show selected.", color = Muted, modifier = Modifier.align(Alignment.Center))
            }
            return@Scaffold
        }
        val rows = setlist.eventRows()
        val canConvert = setlist.performed().isNotEmpty()
        LazyColumn(
            Modifier
                .padding(padding)
                .fillMaxSize()
                // Left acts on this level — convert. Right is its mirror: back up one,
                // to the timeline this night was opened from. Registered even when
                // there is nothing to convert, or a show with no logged setlist would
                // be the one screen you can't swipe out of.
                .pointerInput(setlist.id, canConvert) {
                    val threshold = 110.dp.toPx()
                    var dragX = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dragX = 0f },
                        onDragEnd = {
                            when {
                                dragX <= -threshold && canConvert -> {
                                    viewModel.selectSetlist(setlist)
                                    onConvert()
                                }
                                dragX <= -threshold && !canConvert -> setlist.url?.let {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)))
                                }
                                dragX >= threshold -> onBack()
                            }
                        },
                        onHorizontalDrag = { _, delta -> dragX += delta },
                    )
                },
        ) {
            item {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp)) {
                    Text(setlist.artist?.name ?: "Unknown artist", fontFamily = Serif, fontSize = 27.sp, color = Ink)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        listOfNotNull(setlist.venueLine(), setlist.readableDate()).joinToString(" · "),
                        color = Muted,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(11.dp))
                    Row {
                        EventTag("${setlist.performed().size} songs")
                        setlist.tour?.name?.let {
                            Spacer(Modifier.width(6.dp))
                            EventTag(it)
                        }
                        if (made.isNotEmpty()) {
                            Spacer(Modifier.width(6.dp))
                            EventTag(
                                if (made.size == 1) "playlist" else "${made.size} playlists",
                                color = SpotifyGreen,
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        EventTag("self-logged", color = Faint)
                    }
                    Spacer(Modifier.height(12.dp))
                    GigPhotos(
                        photos = gigPhotos,
                        loadPreview = viewModel::photoPreview,
                        onAdd = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                        // No FLAG_GRANT_READ_URI_PERMISSION: a photo-picker uri only grants
                        // *us* read access, and asking to re-grant one we don't own the
                        // authority for (e.g. Google Photos' cloud picker backend) throws a
                        // SecurityException straight out of startActivity and crashes.
                        onOpen = { uri -> context.startActivity(Intent(Intent.ACTION_VIEW, uri)) },
                        onRemove = { uri -> viewModel.removeGigPhoto(setlist.id, uri) },
                        onReorder = { newOrder -> viewModel.reorderGigPhotos(setlist.id, newOrder) },
                    )
                    Spacer(Modifier.height(8.dp))
                    GigPhotoSuggestions(
                        candidates = state.gigPhotoSuggestions,
                        loading = state.gigPhotoSuggestionsLoading,
                        searched = state.gigPhotoSuggestionsSearched,
                        permissionGranted = state.gigPhotoSuggestionsPermissionGranted,
                        already = gigPhotos,
                        onRequestPermission = {
                            gigSuggestPermissionLauncher.launch(PhotoRepository.requiredPermissions())
                        },
                        onAdd = { uri -> viewModel.addGigPhotos(setlist.id, listOf(uri)) },
                    )
                }
            }
            if (rows.isEmpty()) {
                item {
                    Text(
                        "This show has no setlist on setlist.fm yet.",
                        color = Muted,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }
            items(rows) { row ->
                when (row) {
                    is EventRow.Encore -> EncoreLabel()
                    is EventRow.SongItem -> SongRow(row.number, row.song)
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun SongRow(number: Int?, song: FmSong) {
    val cover = song.cover?.name
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(end = 20.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(50.dp).fillMaxHeight()) {
            Box(Modifier.align(Alignment.TopCenter).width(2.dp).fillMaxHeight().background(LineCol))
            // A tape track sits on the line as a bare dot: it happened, it isn't
            // numbered, and it doesn't pretend to be part of the set.
            val size = if (number == null) 8.dp else 18.dp
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = if (number == null) 7.dp else 2.dp)
                    .size(size)
                    .clip(CircleShape)
                    .background(Raised)
                    .border(1.5.dp, LineLit, CircleShape),
                contentAlignment = Alignment.Center,
            ) { if (number != null) Text(number.toString(), color = Faint, fontSize = 10.sp) }
        }
        Column(Modifier.weight(1f).padding(top = 1.dp, bottom = 15.dp)) {
            Text(song.name, color = if (number == null) Muted else Ink, fontSize = 15.sp)
            val note = cover?.let { "$it cover" } ?: "tape".takeIf { song.tape }
            if (note != null) Text(note, color = Faint, fontSize = 11.sp)
        }
    }
}

@Composable
private fun EncoreLabel() {
    Text(
        "— ENCORE —",
        color = Amber,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(start = 50.dp, top = 4.dp, bottom = 14.dp),
    )
}

@Composable
private fun EventTag(text: String, color: Color = Muted) {
    Text(
        text,
        color = color,
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Raised2)
            .border(1.dp, Color(0xFF2A2338), RoundedCornerShape(20.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}
