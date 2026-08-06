package io.github.magnusencoded.setlist2spotify.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.PlatformTextStyle
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
import io.github.magnusencoded.setlist2spotify.NOT_STAMPED
import io.github.magnusencoded.setlist2spotify.data.DeviceLocation
import io.github.magnusencoded.setlist2spotify.data.Friend
import io.github.magnusencoded.setlist2spotify.data.FutureRow
import io.github.magnusencoded.setlist2spotify.data.StoredAttendance
import io.github.magnusencoded.setlist2spotify.data.StoredLog
import io.github.magnusencoded.setlist2spotify.data.isLocal
import io.github.magnusencoded.setlist2spotify.data.setlistEditEntry
import io.github.magnusencoded.setlist2spotify.data.futureRows
import io.github.magnusencoded.setlist2spotify.data.postFiling
import io.github.magnusencoded.setlist2spotify.data.setlistPaste
import io.github.magnusencoded.setlist2spotify.data.StoredMedia
import io.github.magnusencoded.setlist2spotify.data.gigInviteUri
import io.github.magnusencoded.setlist2spotify.data.photos.PhotoRepository
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmSetlist
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmSong
import kotlinx.coroutines.launch
import java.time.LocalDateTime
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
    // Reachable from both the future edge and the empty spine: a collector with no
    // history at all still has a ticket for something.
    var adding by remember { mutableStateOf(false) }
    var addingBill by remember { mutableStateOf(false) }

    // Check-in (#33): opening the timeline takes one fix and compares it against
    // what's already known. Foreground, one-shot, nothing scheduled.
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Refusing is not a dead end and not an error: the offer just never appears,
        // and the gig's own screen still has a check-in you can press by hand.
        viewModel.offerCheckIn()
    }
    LaunchedEffect(state.plannedGigs) {
        // The permission is only ever asked for on a night there is something to
        // check into — never merely for opening the app.
        if (!viewModel.checkInDue()) return@LaunchedEffect
        if (viewModel.hasLocationPermission()) viewModel.offerCheckIn()
        else locationPermission.launch(DeviceLocation.requiredPermissions())
    }
    state.checkInOffer?.let { gig ->
        CheckInDialog(
            gig = gig,
            onCheckIn = { viewModel.checkIn(gig.id) },
            onDismiss = { viewModel.dismissCheckInOffer() },
        )
    }

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
                "${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_SHA}",
                color = Faint.copy(alpha = 0.5f),
                fontSize = 9.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 4.dp),
            )
            if (adding) {
                AddPlannedGigDialog(
                    onAdd = { link -> viewModel.addPlannedGig(link); adding = false },
                    onDismiss = { adding = false },
                )
            }
            if (addingBill) {
                AddBillDialog(
                    onAdd = { name, city, from, to, lineup ->
                        viewModel.addBill(name, city, from, to, lineup)
                        addingBill = false
                    },
                    onDismiss = { addingBill = false },
                )
            }
            when {
                state.setlistsLoading && state.setlists.isEmpty() ->
                    CircularProgressIndicator(color = Amber, modifier = Modifier.align(Alignment.Center))

                // One gig I'm going to and nothing else is a timeline, not an empty
                // spine — it is exactly the collector's cold start.
                state.setlists.isEmpty() && state.plannedGigs.isEmpty() && state.bills.isEmpty() ->
                    EmptyTimeline(onAdd = onOpenImport, onPlan = { adding = true })

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
                    var planningOpen by remember { mutableStateOf(false) }
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
                                // Pulled far enough, the curtain latches instead of
                                // springing shut. It was a signpost pointing at a place
                                // that did not exist; planning exists now, so the
                                // gesture that reaches for it should arrive somewhere.
                                if (pull.value >= pullMax * 0.6f) planningOpen = true
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
                        // Everything above today, in one date-ordered list — furthest
                        // out first, the same descending order the attended rows use.
                        // Hoisted out of the LazyColumn because the deep-link scroll
                        // below counts it too, and the two must not drift.
                        val future = remember(state.bills, state.plannedGigs) {
                            val billGigs =
                                state.bills.flatMap { b -> b.acts.mapNotNull { it.gigId } }.toSet()
                            futureRows(state.bills, state.plannedGigs.filterNot { it.id in billGigs })
                        }

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
                            // The rows don't start at item 0: the future prompt is, and
                            // every Bill and every gig I'm going to sits between it and
                            // them. Counted off the same list the LazyColumn emits, so
                            // the two cannot drift — the earlier `plannedGigs.size` was
                            // already wrong once a Bill was on the wall.
                            listState.animateScrollToItem(at + 1 + future.size)
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
                            // The top of the line. Three rows used to sit here, one of
                            // which — "↑ THE FUTURE" — explained a direction the layout
                            // already states by being above today. Gone.
                            //
                            // The two ways in show when the curtain has been pulled
                            // open, or when there is nothing above today to point at
                            // them: a line with a Bill and a ticket on it needs no
                            // caption, an empty one has nothing to learn from.
                            item {
                                FuturePrompt(
                                    open = planningOpen || future.isEmpty(),
                                    loading = state.planningLoading,
                                    onAdd = { adding = true; planningOpen = false },
                                    onAddBill = { addingBill = true; planningOpen = false },
                                )
                            }
                            // Everything above today, in one date-ordered list —
                            // furthest out first, the same descending order the attended
                            // rows below use. Bills and tickets interleave because they
                            // sit on the same Line: drawing Bills as a block above the
                            // tickets put a festival starting tonight above a gig a week
                            // out, and "up is always later" is not a rule a new kind of
                            // node is exempt from.
                            //
                            // A Gig an Act became is drawn inside its Bill, never here:
                            // the Bill is its Festival node, and one night must not be
                            // two nodes on one line. Planned gigs are still not grouped
                            // into festivals — a festival is a shape read off nights
                            // that happened, and a Bill is the announced-lineup case.
                            items(
                                future,
                                key = { row ->
                                    when (row) {
                                        is FutureRow.OnBill -> "bill-${row.bill.id}"
                                        is FutureRow.Ticket -> "planned-${row.gig.id}"
                                    }
                                },
                            ) { row ->
                                when (row) {
                                    is FutureRow.OnBill -> BillItem(
                                        bill = row.bill,
                                        open = row.bill.id in expanded,
                                        fetching = state.billFetching == row.bill.id,
                                        onToggle = { viewModel.toggleFestival(row.bill.id) },
                                        onPlayed = { i -> viewModel.markActPlayed(row.bill.id, i) },
                                        onUnmark = { i -> viewModel.unmarkAct(row.bill.id, i) },
                                        onOpenGig = { gigId ->
                                            state.plannedGigs.firstOrNull { it.id == gigId }?.let {
                                                viewModel.openShow(it)
                                                onOpenEvent()
                                            }
                                        },
                                        onSurprise = { name -> viewModel.addSurpriseAct(row.bill.id, name) },
                                        onFetchCandidates = { viewModel.fetchCandidates(row.bill.id) },
                                        onRemove = { viewModel.removeBill(row.bill.id) },
                                    )

                                    is FutureRow.Ticket -> TimelineItem(
                                        setlist = row.gig,
                                        highlight = false,
                                        planned = true,
                                        laneWidth = laneWidth,
                                        onClick = {
                                            viewModel.openShow(row.gig)
                                            onOpenEvent()
                                        },
                                    )
                                }
                            }
                            itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                                val isFirst = index == 0
                                val rails: @Composable () -> Unit =
                                    { PeopleRails(row, rows.getOrNull(index + 1), lanes, laneWidth) }
                                val nodeX = crossingX(row, lanes, laneWidth)
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
                                        photos = state.mediaBySetlist[node.setlist.id]
                                            .orEmpty().map { Uri.parse(it.ref) },
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
 * going up, into the shows you haven't been to yet.
 *
 * It is no longer only a signpost. Pulled far enough it latches the two ways in
 * open — a gig you're going to, or a festival lineup — which is what let the two
 * permanent add-rows come off the top of the timeline. A short pull still springs
 * shut, so the gesture stays cheap to abandon.
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
        Text("keep pulling to add something ahead", color = Faint, fontSize = 11.sp)
    }
}

/**
 * Top of the timeline — the future. The line runs on above today, and the gigs I hold
 * a ticket for hang off it, so this is a way in rather than the dead end it was.
 */
@Composable
private fun FuturePrompt(open: Boolean, loading: Boolean, onAdd: () -> Unit, onAddBill: () -> Unit) {
    if (loading) {
        Text(
            "Looking it up on setlist.fm…",
            color = Faint,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 14.dp),
        )
        return
    }
    // Nothing at all when the curtain is shut and the line already runs on above
    // today. "↑ THE FUTURE" captioned a direction that being above today already
    // states, and two permanent add-rows made three lines at the top of a timeline
    // for a thing you do twice a year.
    if (!open) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 18.dp),
    ) {
        Text(
            "+  a gig you're going to",
            color = Slate,
            fontSize = 13.sp,
            modifier = Modifier.clickable(onClick = onAdd).padding(vertical = 8.dp),
        )
        // The other door: a festival whose lineup is known and whose nights are not,
        // which the setlist.fm link above cannot express because there is no link.
        Text(
            "+  a festival lineup",
            color = Slate,
            fontSize = 13.sp,
            modifier = Modifier.clickable(onClick = onAddBill).padding(vertical = 8.dp),
        )
    }
}

/**
 * Where a gig you're going to comes from: the setlist.fm page's link.
 *
 * Not a search box and not an artist/venue/date form. setlist.fm's API carries the
 * gig — real id, real venue, empty set list — but its search index stops about a day
 * out, so nothing weeks away can be found by artist, venue or date (#29). The id in
 * the url of the page you were just on is the only handle there is, and typing the
 * details in by hand would invent a second record for a gig setlist.fm already has.
 */
@Composable
private fun AddPlannedGigDialog(onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Raised)
                .padding(20.dp),
        ) {
            Text("A gig you're going to", fontFamily = Serif, fontSize = 19.sp, color = Ink)
            Spacer(Modifier.height(6.dp))
            Text(
                "Paste the setlist.fm link for the show. It can't be searched for this " +
                    "far ahead — the link is the way in.",
                color = Muted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(14.dp))
            StationField(
                value = text,
                onValueChange = { text = it },
                label = "setlist.fm link",
                imeDone = true,
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = Faint) }
                TextButton(
                    onClick = { onAdd(text) },
                    enabled = text.isNotBlank(),
                ) { Text("Add", color = if (text.isBlank()) Faint else Amber) }
            }
        }
    }
}

/**
 * "Are you here?" — the one thing a check-in asks. Shown only when a fix already
 * put the phone at the venue on the night, so it states what it thinks and offers
 * the two honest answers.
 */
@Composable
private fun CheckInDialog(gig: FmSetlist, onCheckIn: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Raised)
                .padding(20.dp),
        ) {
            Text("Are you here?", fontFamily = Serif, fontSize = 19.sp, color = Ink)
            Spacer(Modifier.height(6.dp))
            Text(
                "${gig.artist?.name ?: "This show"} at ${gig.venue?.name ?: "the venue"}, tonight.",
                color = Muted,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Checking in records that you were at it — on this phone, nowhere else.",
                color = Faint,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Not now", color = Faint) }
                TextButton(onClick = onCheckIn) { Text("Check in", color = Amber) }
            }
        }
    }
}

/**
 * The one question a delete has to ask: this night holds the only copy of
 * [photos] photographs, and they go with it.
 *
 * Shown only when that count is above zero. A picture that also lives in the
 * gallery is a pointer, and stopping someone to confirm a pointer teaches them
 * to tap through the dialog that mattered.
 */
@Composable
private fun DeleteNightDialog(photos: Int, onDelete: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Raised)
                .padding(20.dp),
        ) {
            Text("Delete this night?", fontFamily = Serif, fontSize = 19.sp, color = Ink)
            Spacer(Modifier.height(6.dp))
            Text(
                if (photos == 1) "Its photograph is only stored here. Deleting the night deletes it."
                else "Its $photos photographs are only stored here. Deleting the night deletes them.",
                color = Muted,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text("There is no undo.", color = Faint, fontSize = 11.sp)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Keep it", color = Faint) }
                TextButton(onClick = onDelete) { Text("Delete", color = Danger) }
            }
        }
    }
}

/** The empty spine: one lit node you tap to bring in your shows. */
@Composable
private fun EmptyTimeline(onAdd: () -> Unit, onPlan: () -> Unit) {
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
        Spacer(Modifier.height(18.dp))
        // A line can start above today as easily as below it: someone with no history
        // yet still has a ticket for something.
        Text(
            "↑  or add a gig you're going to",
            color = Slate,
            fontSize = 13.sp,
            modifier = Modifier.clickable(onClick = onPlan).padding(8.dp),
        )
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
        Column(Modifier.padding(padding).padding(28.dp).fillMaxWidth().swipeRightToBack(onBack = onBack)) {
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
internal fun StationField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    imeDone: Boolean = false,
    /** A pasted lineup is many lines; every other field here is one. */
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
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
    /**
     * A night I hold a ticket for, not one I was at. Amber means mine-and-happened,
     * so a planned node is drawn in the future's colour instead — at every
     * resolution, since "did I go to this" must never depend on the zoom.
     */
    planned: Boolean = false,
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
            // A planned node is the exception: nobody is woven into a night that hasn't
            // happened, so there is no canvas above it and the spine would break.
            if (!zoomedOut || planned) {
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
                        // Opaque interior so the spine stops at the rim instead of
                        // running through the node. A ring over a transparent centre
                        // let the line show straight through the circle.
                        .background(Ground)
                        .border(
                            2.dp,
                            // Amber is what "mine" looks like at every resolution; the
                            // night our lines became one gets a colour of its own; and
                            // a night that hasn't happened has not earned either.
                            when {
                                planned -> Slate
                                shared -> Crossed
                                highlight -> Amber
                                else -> Amber.copy(alpha = 0.6f)
                            },
                            CircleShape,
                        ),
                ) {
                    // The most-recent node keeps its soft amber glow — over the opaque
                    // fill now, so it tints the interior without the line behind it.
                    if (highlight && !shared) {
                        Box(Modifier.matchParentSize().background(AmberSoft))
                    }
                }
            }
        }
        Column(Modifier.padding(start = if (inside) 14.dp else 0.dp, end = 18.dp, bottom = 22.dp)) {
            Text(
                setlist.readableDateShort() ?: "Unknown date",
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
            // The Reliver's own keepsakes of the night — under the artist, over the
            // song count. Big enough to actually read as a photo; the facts still win
            // by being text, and the full-size gallery on the gig screen is bigger still.
            if (photos.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row {
                    photos.take(3).forEach { uri ->
                        PhotoThumb(uri, size = 44.dp, loadPreview = loadPhotoPreview)
                        Spacer(Modifier.width(6.dp))
                    }
                }
            }
            Spacer(Modifier.height(7.dp))
            Text(
                when {
                    planned -> plannedStatus(setlist.localDate())
                    songCount > 0 -> "$songCount songs"
                    else -> "setlist not logged"
                },
                color = if (planned) Slate else Faint,
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

/**
 * A line index in points. [Spine] is -1, so lane 0 sits one step out from my spine.
 *
 * Which line is a whole number — the only honest float in this area is *where in
 * points*, which is this function's result and the strip's openness in [crossingX].
 */
internal fun laneXf(offset: Int, step: Dp) = SpineX + step * (offset + 1)

/**
 * Which lines were at a row: [Spine] for me, plus a lane index per friend present.
 *
 * The single which-line primitive. Everything else in this section is a question
 * asked of this list — the node's host is its minimum, presence is membership, and
 * company is its size — so the merge rule is written once and cannot drift out of
 * step with the canvas that draws it (#69).
 */
internal fun linesAt(row: WovenRow, lanes: List<Friend>): List<Int> = buildList {
    if (row.mine) add(Spine)
    lanes.forEachIndexed { i, f ->
        if (row.others.any { it.setlistfm == f.setlistfm }) add(i)
    }
}

/**
 * Which line a row's node sits on. Lines that share a node become one line, so a
 * night has exactly one node — mine when I was there (my line never moves to meet
 * anyone), otherwise the innermost lane among the friends who were, which the
 * others come to. Returns [Spine] or a lane index.
 *
 * The innermost line *is* the minimum: [Spine] is -1 and so sorts below every lane
 * index, and `row.mine` is what puts it in the set. That equivalence used to be
 * something to verify by reading two implementations against each other.
 */
internal fun nodeHost(row: WovenRow, lanes: List<Friend>): Int =
    linesAt(row, lanes).minOrNull() ?: Spine

/**
 * Where a line is drawn at a row: on the node if it was there, otherwise its own lane.
 * [line] is [Spine] for mine or a lane index for a friend's. The line-index-keyed twin
 * of [hostLane], and the one the canvas asks.
 */
internal fun lineOffset(row: WovenRow?, line: Int, lanes: List<Friend>): Int {
    if (row == null) return line
    return if (linesAt(row, lanes).contains(line)) nodeHost(row, lanes) else line
}

/**
 * Which line [friend] is drawn on at [row]: the node's host if they were there,
 * otherwise their own lane. This is the whole merge rule — asking it per friend is
 * what makes A parting on the row B joins two independent answers instead of one
 * shared boolean. Replaces `merged()`, whose Boolean could only ever mean "with me".
 *
 * Resolves the friend to a lane index and hands the same rule to [lineOffset]: one
 * rule, two key types, one implementation. `indexOfFirst` returns -1 for someone with
 * no lane, which is [Spine] — deliberately not lane 0, which belongs to a real friend.
 */
internal fun hostLane(row: WovenRow?, friend: Friend, lanes: List<Friend>): Int =
    lineOffset(row, lanes.indexOfFirst { it.setlistfm == friend.setlistfm }, lanes)

/**
 * Where a row's node sits. My line never moves — a night we shared happens *on* my
 * line, and theirs comes to meet it. Putting the node between the two made both
 * timelines leave their own path to attend it.
 */
internal fun crossingX(
    row: WovenRow,
    lanes: List<Friend>,
    laneWidth: Dp,
): Dp {
    val offset = nodeHost(row, lanes)
    if (laneWidth <= 0.dp || offset == Spine) return SpineX
    val step = laneStep(lanes.size)
    // The lanes are still sliding out while the strip opens; keep the node with them.
    val open = (laneWidth / stripWidth(lanes.size)).coerceIn(0f, 1f)
    return SpineX + (laneXf(offset, step) - SpineX) * open
}

/**
 * The height the dump computes its geometry at. A real row's height is only known once
 * it has been laid out, and it varies with the text in it — but the only number that
 * depends on it is the tail bend, and at any height a row with a line of text on it
 * actually reaches, the bend is already clamped to [EdgeBend]. So this stands in for
 * "a row of ordinary height" rather than pretending to measure one.
 */
private val DumpRowHeight = 96.dp

/**
 * The woven spine as facts rather than pixels: `adb logcat -s Woven`.
 *
 * Every rule in this file is visual, and the only way to check one has been to read
 * a screenshot — which is slow and, at least once, wrong: three lines converging was
 * read off an image as a merge that the data said never happened. A row's model, the
 * lane each person is drawn on, *and the geometry actually stroked* are all computable
 * here, so they can be asserted on instead of squinted at. Debug builds only.
 *
 * The geometry printed is the same [rowGeometry] value the canvas draws from, at a
 * fully open strip — so a picture that looks wrong converts into a failing test by
 * copying numbers out of this log.
 */
internal fun logWovenRows(rows: List<WovenRow>, lanes: List<Friend>) {
    if (!BuildConfig.DEBUG) return
    val laneWidth = stripWidth(lanes.size)
    Log.d(
        "Woven",
        "--- ${rows.size} rows, lanes=${lanes.map { it.setlistfm }}, " +
            "geometry in dp at laneWidth=${laneWidth.value} rowHeight=${DumpRowHeight.value} ---",
    )
    rows.forEachIndexed { i, row ->
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
        rowGeometry(row, rows.getOrNull(i + 1), lanes, laneWidth, DumpRowHeight).forEach { d ->
            Log.d(
                "Woven",
                "    ${lineLabel(d.line, lanes)} x=${d.x.value}→${d.toX.value} " +
                    "node=(${d.nodeY.value},r${d.nodeR.value}) bend=${d.bendLen.value} " +
                    "${if (d.present) "here" else "past"} " +
                    "body=${d.people}p/${d.width.value}dp/${d.colour} " +
                    "ahead=${d.peopleAhead}p/${d.widthAhead.value}dp/${d.colourAhead}",
            )
        }
    }
}

/** A role resolved against the palette. The only thing the canvas gets to decide. */
private fun LineColour.paint(): Color = when (this) {
    LineColour.Meeting -> Crossed
    is LineColour.Mine -> Amber.copy(alpha = if (present) 0.85f else 0.4f)
    is LineColour.Rail -> railColor(lane)
    LineColour.Absent -> LineCol
}

/**
 * Strokes what [rowGeometry] says. Every number arrives already computed in points;
 * the only thing this does with geometry is convert it to pixels. A rule that lived
 * here could not be asserted, so none does — changing how a **Line** looks must not be
 * able to move where it goes (#116).
 */
@Composable
internal fun PeopleRails(
    row: WovenRow,
    next: WovenRow?,
    friends: List<Friend>,
    laneWidth: Dp,
) {
    if (laneWidth <= 0.dp || friends.isEmpty()) return
    Canvas(Modifier.fillMaxSize()) {
        val h = size.height
        val drawn = rowGeometry(row, next, friends, laneWidth, h.toDp())
        val ring = Stroke(width = 2.dp.toPx())
        val nodeAt = nodeHost(row, friends)
        val joined = linesAt(row, friends).size > 1

        drawn.forEach { d ->
            val x = d.x.toPx()
            val toX = d.toX.toPx()
            val nodeY = d.nodeY.toPx()
            val gap = d.nodeR.toPx()
            val bendLen = d.bendLen.toPx()
            val body = d.colour.paint()
            val bodyStroke = Stroke(width = d.width.toPx())

            if (nodeY - gap > 0f) {
                val approach = Path().apply {
                    moveTo(x, 0f)
                    lineTo(x, nodeY - gap)
                }
                drawPath(approach, body, style = bodyStroke)
            }

            val trunk = Path().apply {
                moveTo(x, nodeY + gap)
                lineTo(x, h - bendLen)
            }
            drawPath(trunk, body, style = bodyStroke)

            val tail = Path().apply {
                moveTo(x, h - bendLen)
                if (toX == x) lineTo(x, h)
                else cubicTo(x, h - bendLen * 0.45f, toX, h - bendLen * 0.55f, toX, h)
            }
            drawPath(tail, d.colourAhead.paint(), style = Stroke(width = d.widthAhead.toPx()))

            // One node per night, drawn once by the innermost line that was there.
            // My own rows and festivals draw their own, so this only fills the gap
            // for a gig of theirs.
            val drawsNode = d.present && !row.mine && row.node !is TimelineNode.Festival &&
                d.line == nodeAt
            if (drawsNode) {
                drawCircle(
                    if (joined) Crossed else railColor(d.line),
                    6.dp.toPx(),
                    Offset(x, nodeY),
                    style = ring,
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

/** A share-sheet intent carrying a gig-invite deep link a contact's app can open. */
private fun gigInviteChooser(setlist: FmSetlist): Intent {
    val label = listOfNotNull(setlist.artist?.name, setlist.venue?.name, setlist.readableDate())
        .joinToString(" · ")
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "Come to this with me — $label\n${gigInviteUri(setlist.id)}")
    }
    return Intent.createChooser(send, "Invite a friend")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun StationEventScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onConvert: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val setlist = state.selectedSetlist
    val context = LocalContext.current
    // A night I'm going to, not one I was at. Everything this screen says about a
    // setlist has to change: there is no setlist to be missing yet.
    val planned = setlist != null && state.plannedGigs.any { it.id == setlist.id }
    // Read off state rather than asked of the view model, so checking in redraws
    // this screen instead of leaving the button sitting there.
    val checkedIn = setlist != null &&
        state.attendanceByGig[setlist.id]?.provenance == StoredAttendance.Provenance.CHECKED_IN
    // What this night already became. Every one of them: each url may be in
    // somebody's hands, so none of them stops being reachable from here.
    val made = setlist?.let { state.playlistsBySetlist[it.id] }.orEmpty()
    val gigMedia = setlist?.let { state.mediaBySetlist[it.id] }.orEmpty()
    val gigPhotos = gigMedia.map { Uri.parse(it.ref) }
    // The Reliver picks straight from the system photo (and video) picker — no
    // gallery permission needed for that path, unlike the suggestions below.
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> if (uris.isNotEmpty()) setlist?.let { viewModel.addPickedGigPhotos(it.id, uris) } }
    // Gallery access is only ever asked for after the "suggest" tap, so opening
    // a gig never triggers a permission prompt on its own.
    val gigSuggestPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.loadGigPhotoSuggestions() }
    // Silent when permission isn't there yet — same guard as the prompt above,
    // so a gig already granted access just re-searches without another tap.
    LaunchedEffect(setlist?.id) { viewModel.loadGigPhotoSuggestions() }
    // The disambiguation's answer, either way. It runs from this screen and until
    // now landed nowhere: "found them, songs are from X" was written into state and
    // no screen but Friends renders a notice, so the one gesture whose whole point
    // is to tell you *which* band you got told you nothing — and a dead end, which
    // deliberately leaves the old pool alone, was indistinguishable from success.
    // Toast because that is already how this screen answers publish and calendar.
    LaunchedEffect(state.notice) {
        state.notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.consumeNotice()
        }
    }
    LaunchedEffect(state.error) {
        state.error?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.consumeError()
        }
    }
    var viewerUri by remember { mutableStateOf<Uri?>(null) }
    // Where the viewer should open — set when a stamped song on the spine is tapped,
    // so the recording lands on that song instead of at the top of the night.
    var viewerStartMs by remember { mutableStateOf(NOT_STAMPED) }
    // The night's full recording: the first video among the keepsakes. Photos and
    // one-song clips sit alongside it and are not treated as the recording.
    // Kind comes off the record now (#97), not from asking the ContentResolver —
    // a reference that has died still knows what it was.
    val recordingMedia = gigMedia.firstOrNull { it.kind == StoredMedia.Kind.VIDEO }
    val recording = recordingMedia?.let { Uri.parse(it.ref) }

    // The planned-gig leaf, staged like the Spotify convert (#55): the swipe adds the
    // gig to the calendar, then — once the event exists and its link is showing —
    // graduates to inviting a friend, which repeats forever. The event's URI is both
    // the "already added" flag and the thing the link opens.
    val scope = rememberCoroutineScope()
    val calendarEventUri = setlist?.let { state.calendarEventByGig[it.id] }
    val added = calendarEventUri != null
    // Only in the plan-ahead window does the swipe do the calendar/invite dance. PAST
    // keeps the setlist.fm crumb, DAY_OF is the check-in — both left to the fall-through
    // below, exactly as they behaved before, so the swipe never contradicts the hint.
    // --- The Historian's half: my own Log of this night, and where it goes ---------
    //
    // A Log makes sense the moment I am known to have been there — a check-in, or a
    // night this app minted itself, which only ever happens by someone standing in
    // front of the stage tapping an Act. It stays available *forever* after that:
    // remembering a song three days later must cost nothing, so nothing below removes
    // the editor. The clock only decides which action leads.
    val log = setlist?.let { state.logsByGig[it.id] } ?: StoredLog()
    // The Act this night was minted from, when it came off a Bill: it carries the
    // candidate pool and — the part that matters — which artist that pool came from.
    val act = setlist?.let { viewModel.actFor(it.id) }
    val localGig = setlist != null && setlist.isLocal()
    val canLog = setlist != null && (checkedIn || localGig)
    val leaf = gigLeaf(
        now = LocalDateTime.now(),
        window = setlist?.localDate()?.let { nightWindow(it) },
        checkedIn = checkedIn,
    )
    // **Publish**: explicit, labelled, and never a side effect of anything else. The
    // clipboard is the entire channel — setlist.fm's form takes no prefill parameters
    // and its Text Field editor takes a whole ordered set in one paste — so the copy
    // and the door open together, announced, on a tap that says it will.
    //
    // The songs are one of five things the form wants, and the other four were crossing
    // the app switch in the Historian's memory because this screen is gone the moment
    // the browser is up. `postFiling` parks all five in the notification shade, which is
    // the one surface still in reach of Chrome. Songs stay on the clipboard as well —
    // the shade is an upgrade to the handoff, never a gate on it, so a denied
    // notification permission leaves this behaving exactly as it always did.
    val publish: () -> Unit = publish@{
        val s = setlist ?: return@publish
        val clip = context.getSystemService(ClipboardManager::class.java)
        clip?.setPrimaryClip(ClipData.newPlainText("setlist", setlistPaste(log)))
        postFiling(context, s, log)
        Toast.makeText(
            context,
            if (log.songs.isEmpty()) "Nothing logged yet — the gig itself is still worth adding."
            else "${log.songs.size} songs copied. The rest is in your notifications.",
            Toast.LENGTH_LONG,
        ).show()
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(setlistEditEntry(s))))
    }
    // Asked for on the way to publishing, never on launch, and the answer does not
    // gate anything: whichever way it goes, `publish` runs straight after.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { publish() }
    val onPublish: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            publish()
        } else {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    var adopting by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    val plannedTimeState = if (planned) setlist?.localDate()?.let { gigTimeState(LocalDateTime.now(), it) } else null
    val planAhead = planned &&
        plannedTimeState != GigTimeState.PAST && plannedTimeState != GigTimeState.DAY_OF
    // The insert is a couple of binder calls, so it runs off the main thread; success
    // persists the returned URI, and every failure (no writable calendar, provider
    // refusal) degrades to a toast with no link and no stage advance.
    val addToCalendar: () -> Unit = add@{
        val s = setlist ?: return@add
        scope.launch {
            val uri = withContext(Dispatchers.IO) { insertCalendarEvent(context.contentResolver, s) }
            if (uri != null) viewModel.markCalendarAdded(s.id, uri.toString())
            else Toast.makeText(context, "Couldn't add this to your calendar.", Toast.LENGTH_SHORT).show()
        }
    }
    val calendarPermission = arrayOf(Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR)
    // A denied permission is the graceful-degrade path: a toast, and the swipe stays on
    // "add to calendar" because no event was made.
    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) addToCalendar()
        else Toast.makeText(context, "Calendar access is needed to add this show.", Toast.LENGTH_SHORT).show()
    }
    val onAddToCalendar: () -> Unit = {
        if (calendarPermission.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            addToCalendar()
        } else {
            calendarPermissionLauncher.launch(calendarPermission)
        }
    }
    // The invite is unchanged from the button it replaces: the gig-invite deep link out
    // through the OS share sheet. Repeatable — an invite is per-person.
    val onInvite: () -> Unit = { setlist?.let { context.startActivity(gigInviteChooser(it)) } }

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
            if (canLog && setlist != null) {
                // A night I was at that this app is the record of. Capture is the leaf,
                // always — the chip in the header is the permanent door to setlist.fm,
                // so nothing here has to become a handoff when the night ends. The clock
                // only changes the wording: prompting while you are there, quiet
                // correction afterwards.
                Column(
                    Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        when (leaf) {
                            GigLeaf.CAPTURE -> "noting the set — add what they play above"
                            else -> "your log · add anything you remember above"
                        },
                        color = Faint,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                    Text(
                        "‹ copy the set and open setlist.fm",
                        color = Amber,
                        fontSize = 13.sp,
                        modifier = Modifier.clickable(onClick = onPublish).padding(vertical = 6.dp),
                    )
                    if (localGig) {
                        Text(
                            "it's on setlist.fm now — paste the link",
                            color = Slate,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clickable { adopting = true }
                                .padding(vertical = 6.dp),
                        )
                        // Reachable from the night itself, on purpose: the undo on a
                        // Bill's act needs the Bill to still exist, and a night whose
                        // poster has been taken down was left with no way out at all.
                        Text(
                            "delete this night",
                            color = Danger,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clickable {
                                    if (viewModel.photosLostByDeleting(setlist.id) > 0) deleting = true
                                    else { viewModel.deleteLocalGig(setlist.id); onBack() }
                                }
                                .padding(vertical = 6.dp),
                        )
                    }
                }
            } else if (planned && setlist != null) {
                // What a planned gig lets you do follows the clock (#55): plan it while
                // it's still ahead, check in on the night, nudge setlist.fm once it's
                // over. An unparseable date can't be placed on that line, so it falls
                // to the plan-ahead actions rather than losing them.
                val timeState = setlist.localDate()?.let { gigTimeState(LocalDateTime.now(), it) }
                Column(
                    Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // The manual check-in, and the only one there is when location was
                    // refused or the venue couldn't be geocoded. Same night window as
                    // the ambient offer; no location involved at all.
                    if (canCheckInManually(setlist, LocalDateTime.now())) {
                        if (checkedIn) {
                            Text("✓ checked in", color = Amber, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
                        } else {
                            Text(
                                "I'm here — check in",
                                color = Amber,
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .clickable { viewModel.checkIn(setlist.id) }
                                    .padding(vertical = 6.dp),
                            )
                        }
                    }
                    when (timeState) {
                        // Over: adding a setlist is a past action, so the setlist.fm
                        // crumb belongs here and only here.
                        GigTimeState.PAST -> setlist.url?.let { url ->
                            Text(
                                "‹ swipe to open this show on setlist.fm",
                                color = Slate,
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                    .padding(vertical = 6.dp),
                            )
                        }
                        // The night itself: maps and check-in (#33), handled above. No
                        // crumb, no plan-ahead buttons.
                        GigTimeState.DAY_OF -> {}
                        // Still ahead (or an undated gig): the swipe is the action, in two
                        // stages. The hint names what the next swipe does — the same
                        // grammar as the Spotify convert, where the made-playlist link
                        // persists and the hint moves on to "make another".
                        else -> {
                            if (calendarEventUri != null) {
                                // The created event, as a persisted tappable link — the
                                // mirror of a made-playlist row. Opens the event with
                                // ACTION_VIEW on the URI the insert handed back.
                                Row(
                                    Modifier
                                        .clickable {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(calendarEventUri)))
                                        }
                                        .padding(vertical = 6.dp, horizontal = 20.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(Modifier.size(7.dp).clip(CircleShape).background(Slate))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Open the calendar event ↗", color = Slate, fontSize = 14.sp)
                                }
                                Spacer(Modifier.height(2.dp))
                                // Graduated: the swipe now invites, and keeps inviting.
                                Text(
                                    "‹ swipe to invite a friend",
                                    color = Slate,
                                    fontSize = 13.sp,
                                    modifier = Modifier.clickable(onClick = onInvite).padding(vertical = 6.dp),
                                )
                            } else {
                                Text(
                                    "‹ swipe to add to calendar",
                                    color = Slate,
                                    fontSize = 13.sp,
                                    modifier = Modifier.clickable(onClick = onAddToCalendar).padding(vertical = 6.dp),
                                )
                            }
                        }
                    }
                    Text(
                        "I'm not going",
                        color = Danger,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clickable { viewModel.removePlannedGig(setlist.id); onBack() }
                            .padding(vertical = 6.dp),
                    )
                }
            } else if (setlist != null && setlist.performed().isEmpty() && setlist.url != null) {
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
                                    // Long-press drops the link — for when the playlist
                                    // itself was deleted on Spotify and this pointer is
                                    // just dead weight left behind.
                                    .combinedClickable(
                                        onClick = {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(playlist.url)),
                                            )
                                        },
                                        onLongClick = { viewModel.removePlaylist(setlist.id, playlist.url) },
                                    )
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
        if (adopting) {
            AdoptSetlistDialog(
                onAdopt = { link -> viewModel.adoptSetlistLink(setlist.id, link); adopting = false },
                onDismiss = { adopting = false },
            )
        }
        if (deleting) {
            DeleteNightDialog(
                photos = viewModel.photosLostByDeleting(setlist.id),
                onDelete = { deleting = false; viewModel.deleteLocalGig(setlist.id); onBack() },
                onDismiss = { deleting = false },
            )
        }
        val rows = setlist.eventRows()
        val canConvert = setlist.performed().isNotEmpty()
        val offsets = viewModel.songOffsets(recordingMedia?.id, setlist.songs().size)
        // Offsets are indexed over every song, tape included; row.number skips tape,
        // so it can't be used to look one up. -1 for the rows that aren't songs.
        val songIndexByRow = remember(rows) {
            buildList {
                var i = 0
                rows.forEach { add(if (it is EventRow.SongItem) i++ else -1) }
            }
        }
        // Pull down to re-fetch: you log the night here, go type the songs in on
        // setlist.fm, and come back to a screen that still says there's no setlist.
        PullToRefreshBox(
            isRefreshing = state.setlistsLoading,
            onRefresh = viewModel::refreshSelectedSetlist,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    // Swipe-left is THE action gesture; swipe-right is always back, the
                    // way out of any pushed screen. What left does depends on the gig:
                    // a plan-ahead gig adds it to the calendar, then invites once added
                    // (#55); a past night converts to a playlist, or opens on setlist.fm
                    // when there's nothing to convert. PAST/DAY_OF planned gigs fall
                    // through to that same open-on-setlist.fm, matching their crumb.
                    // Registered even with nothing to convert, or a show with no logged
                    // setlist would be the one screen you can't swipe out of.
                    .pointerInput(setlist.id, canConvert, planAhead, added, canLog, leaf) {
                        val threshold = 110.dp.toPx()
                        var dragX = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { dragX = 0f },
                            onDragEnd = {
                                when {
                                    dragX >= threshold -> onBack()
                                    dragX > -threshold -> {}
                                    // A night I logged: the swipe is the labelled
                                    // publish, matching the "‹ copy the set and open
                                    // setlist.fm" hint under it — this file's rule is
                                    // that the swipe never contradicts the hint. Not
                                    // gated on the clock: a gesture that silently does
                                    // nothing for half the night is a dead gesture, and
                                    // this one publishes nothing by itself anyway — it
                                    // fills the clipboard and opens their form.
                                    canLog -> onPublish()
                                    planAhead && !added -> onAddToCalendar()
                                    planAhead && added -> onInvite()
                                    canConvert -> {
                                        viewModel.selectSetlist(setlist)
                                        onConvert()
                                    }
                                    else -> setlist.url?.let {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)))
                                    }
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
                            if (planned) {
                                EventTag(plannedStatus(setlist.localDate()), color = Slate)
                            } else {
                                EventTag("${setlist.performed().size} songs")
                            }
                            setlist.tour?.name?.let {
                                Spacer(Modifier.width(6.dp))
                                EventTag(it)
                            }
                            // The rule this row now follows: a chip that names an
                            // **external record** opens it; a chip stating a local fact
                            // (song count, tour, "checked in") does not. That is what
                            // makes the setlist.fm chip below learnable rather than a
                            // special case — and it was already true of this one, which
                            // has always named a Spotify URL and done nothing with it.
                            if (made.isNotEmpty()) {
                                Spacer(Modifier.width(6.dp))
                                EventTag(
                                    if (made.size == 1) "playlist ↗" else "${made.size} playlists ↗",
                                    color = SpotifyGreen,
                                    onClick = {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(made.first().url)),
                                        )
                                    },
                                )
                            }
                            // How the app came to believe I was here. A check-in is
                            // stronger evidence than setlist.fm's retroactive flag; the
                            // redundant "planned" chip is gone — "you're going"/countdown
                            // above already says all a planned-and-not-checked-in night can.
                            // A badge marks the exceptional. "Checked in" is earned;
                            // the tag that used to sit beside it labelled the *default*
                            // — nearly every attended gig — and so said nothing. Gone.
                            if (checkedIn) {
                                Spacer(Modifier.width(6.dp))
                                EventTag("checked in", color = Amber)
                            }
                            // The setlist.fm id, rendered. Not a button bolted on beside
                            // the data — it *is* `StoredGig.setlistId`, and its absence
                            // is #34's stub condition showing itself. That id is the
                            // correspondence key between people, so this chip is the
                            // joint where my record meets everyone else's.
                            Spacer(Modifier.width(6.dp))
                            if (setlist.url != null) {
                                EventTag(
                                    // The glyph is the tell. Nothing in this row has
                                    // ever answered a tap, so a chip that does cannot
                                    // rely on anyone trying it.
                                    "${setlist.id} ↗",
                                    color = Slate,
                                    // The canonical setlist page, never a constructed
                                    // edit url: this one is always valid, needs no login,
                                    // and editing is one click away on their own site.
                                    onClick = {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(setlist.url)),
                                        )
                                    },
                                )
                            } else {
                                // **Local**: a true property of the record — it exists on
                                // this phone only, and cannot be a **Crossing** until it
                                // has an id. Not "self-reported", which describes how
                                // nearly every claim here was made and so marks nothing.
                                //
                                // Deliberately inert. `/edit` shows a signed-out user a
                                // sign-in wall, and #34 is explicit that a dead-end link
                                // is worse than no crumb — so the absence is stated and
                                // the labelled action below is the door.
                                EventTag("local", color = Faint)
                            }
                        }
                        // Nothing can be pinned to a night nobody has been to yet — the
                        // slot comes back once the gig is checked into or no longer planned.
                        if (showsMediaBlock(planned, checkedIn)) {
                            Spacer(Modifier.height(12.dp))
                            GigPhotos(
                                photos = gigPhotos,
                                loadPreview = viewModel::photoPreview,
                                onAdd = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                                // Opens in the in-app viewer below rather than handing the uri to
                                // whatever app the phone picks: an external app can fail to read
                                // it (permission scoped to us, or the phone's own quirks) and
                                // leave the user staring at a viewer with nothing in it.
                                onOpen = { uri -> viewerUri = uri },
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
                }
                // My own Log, and it is never taken away. A partial capture you can no
                // longer correct from inside the app is the exact trap this feature is
                // built to avoid, so this renders on a night's page forever after.
                if (canLog) {
                    item {
                        Spacer(Modifier.height(6.dp))
                        LogEditor(
                            candidates = act?.candidates.orEmpty(),
                            poolArtist = act?.matchedArtist.orEmpty(),
                            log = log,
                            // Only once I have written something down. An untouched log
                            // beside an imported setlist is not a divergence, it is a
                            // log I have not started — and "setlist.fm has 18, yours has
                            // 0" the instant you check in is noise, not information.
                            published = setlist.performed().size
                                .takeIf { setlist.url != null && log.songs.isNotEmpty() },
                            onChange = { viewModel.editLog(setlist.id, it) },
                            onClosed = { viewModel.setLogClosed(setlist.id, it) },
                            onDisambiguate = { viewModel.disambiguateAct(setlist.id, it) },
                            searching = state.billFetching != null,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }
                if (rows.isEmpty() && !canLog) {
                    item {
                        Text(
                            // A night that hasn't happened has no setlist missing from
                            // it — nothing has been played yet, and saying "not logged"
                            // would blame setlist.fm for a gap that isn't one.
                            if (planned) "This show hasn't happened yet."
                            else "This show has no setlist on setlist.fm yet.",
                            color = Muted,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }
                itemsIndexed(rows) { rowIndex, row ->
                    when (row) {
                        is EventRow.Encore -> EncoreLabel()
                        is EventRow.SongItem -> {
                            val at = offsets.getOrElse(songIndexByRow[rowIndex]) { NOT_STAMPED }
                            SongRow(
                                number = row.number,
                                song = row.song,
                                offsetMs = at,
                                // Only a stamped song knows where it is in the recording;
                                // the rest are inert until someone marks them.
                                onClick = if (at > NOT_STAMPED && recording != null) {
                                    { viewerStartMs = at; viewerUri = recording }
                                } else null,
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    viewerUri?.let { uri ->
        // Only the night's own recording carries the setlist — a short clip of one
        // song is still just a keepsake, and a song list under it would be noise.
        val songs = if (setlist != null && uri == recording) setlist.songs() else emptyList()
        MediaViewerDialog(
            uri = uri,
            isVideo = viewModel.isVideo(uri),
            loadPhoto = viewModel::fullPhoto,
            onDismiss = { viewerUri = null; viewerStartMs = NOT_STAMPED },
            songs = songs,
            // The stamps belong to *this* recording, not to the night — a night with
            // two videos has two answers, and before #97 the second had nowhere to go.
            offsets = viewModel.songOffsets(recordingMedia?.id, songs.size),
            startAtMs = viewerStartMs,
            onStamp = { index, atMs ->
                recordingMedia?.let { viewModel.stampSong(it.id, index, atMs, songs.size) }
            },
        )
    }
}

/**
 * A tap on a keepsake opens it here rather than in an external app — a photo enlarged,
 * a video played back — since a picker/FileProvider uri handed to whatever app the phone
 * chooses can fail to actually load it there.
 *
 * When the keepsake is a whole night's recording, the setlist rides along underneath it:
 * play, and tap a song as it starts to record where it sits in the video. Nothing is
 * inferred — one tap stamps one song — because the recording and the setlist do not
 * always hold the same songs.
 */
@Composable
private fun MediaViewerDialog(
    uri: Uri,
    isVideo: Boolean,
    loadPhoto: suspend (Uri) -> Bitmap?,
    onDismiss: () -> Unit,
    songs: List<FmSong> = emptyList(),
    offsets: List<Long> = emptyList(),
    startAtMs: Long = NOT_STAMPED,
    onStamp: (Int, Long) -> Unit = { _, _ -> },
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (isVideo && songs.isNotEmpty()) {
                var player by remember(uri) { mutableStateOf<VideoView?>(null) }
                Column(Modifier.fillMaxSize()) {
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().weight(0.45f),
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                                setVideoURI(uri)
                                setOnPreparedListener {
                                    if (startAtMs > NOT_STAMPED) seekTo(startAtMs.toInt())
                                    it.start()
                                }
                                player = this
                            }
                        },
                    )
                    Text(
                        "Tap a song as it starts. Long-press to clear.",
                        color = Faint,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 6.dp),
                    )
                    LazyColumn(Modifier.weight(0.55f)) {
                        itemsIndexed(songs) { index, song ->
                            StampRow(
                                number = index + 1,
                                song = song,
                                offsetMs = offsets.getOrElse(index) { NOT_STAMPED },
                                // A stamped song is a place to jump to; an unstamped one
                                // is a place to mark. Same row, told apart by whether it
                                // already knows where it lives.
                                onTap = {
                                    val at = offsets.getOrElse(index) { NOT_STAMPED }
                                    if (at > NOT_STAMPED) player?.seekTo(at.toInt())
                                    else player?.let { onStamp(index, it.currentPosition.toLong()) }
                                },
                                onLongPress = { onStamp(index, NOT_STAMPED) },
                            )
                        }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }
            } else if (isVideo) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                            setVideoURI(uri)
                            setOnPreparedListener { it.start() }
                        }
                    },
                )
            } else {
                var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(uri) { bitmap = loadPhoto(uri) }
                bitmap?.let {
                    Image(
                        it.asImageBitmap(),
                        contentDescription = "Your photo from this show",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().clickable(onClick = onDismiss),
                    )
                } ?: CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}

/** mm:ss, or h:mm:ss once a recording runs past the hour — a full gig usually does. */
internal fun formatOffset(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    return if (h > 0) "%d:%02d:%02d".format(h, (total % 3600) / 60, total % 60)
    else "%d:%02d".format(total / 60, total % 60)
}

/** One song inside the recording viewer: tap to stamp or to jump, long-press to clear. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StampRow(
    number: Int,
    song: FmSong,
    offsetMs: Long,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val stamped = offsetMs > NOT_STAMPED
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onTap, onLongClick = { if (stamped) onLongPress() })
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$number", color = Faint, fontSize = 11.sp, modifier = Modifier.width(24.dp))
        Text(
            song.name,
            color = if (stamped) Ink else Muted,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (stamped) formatOffset(offsetMs) else "–",
            color = if (stamped) Amber else Faint,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun SongRow(
    number: Int?,
    song: FmSong,
    offsetMs: Long = NOT_STAMPED,
    onClick: (() -> Unit)? = null,
) {
    val cover = song.cover?.name
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(end = 20.dp),
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
            ) {
                if (number != null) Text(
                    number.toString(),
                    color = Faint,
                    fontSize = 10.sp,
                    // Default font padding pads above the ascent, so a centred digit
                    // sits high in a circle this small. Drop it and pin the line height
                    // to the glyph so Center means the digit's centre, not the box's.
                    lineHeight = 10.sp,
                    textAlign = TextAlign.Center,
                    style = LocalTextStyle.current.copy(
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                    ),
                )
            }
        }
        Column(Modifier.weight(1f).padding(top = 1.dp, bottom = 15.dp)) {
            Text(song.name, color = if (number == null) Muted else Ink, fontSize = 15.sp)
            val note = cover?.let { "$it cover" } ?: "tape".takeIf { song.tape }
            if (note != null) Text(note, color = Faint, fontSize = 11.sp)
        }
        // Where this song sits in the night's recording, once someone has marked it.
        if (offsetMs > NOT_STAMPED) {
            Text(
                formatOffset(offsetMs),
                color = Amber,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
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
private fun EventTag(text: String, color: Color = Muted, onClick: (() -> Unit)? = null) {
    Text(
        text,
        color = color,
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Raised2)
            .border(1.dp, Color(0xFF2A2338), RoundedCornerShape(20.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}
