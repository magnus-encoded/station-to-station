package io.github.magnusencoded.stationtostation.ui

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
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.magnusencoded.stationtostation.AppViewModel
import io.github.magnusencoded.stationtostation.BuildConfig
import io.github.magnusencoded.stationtostation.CoverCandidate
import io.github.magnusencoded.stationtostation.GigLink
import io.github.magnusencoded.stationtostation.MediaThumb
import io.github.magnusencoded.stationtostation.NOT_STAMPED
import io.github.magnusencoded.stationtostation.data.DeviceLocation
import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.data.FriendArrival
import io.github.magnusencoded.stationtostation.data.FutureRow
import io.github.magnusencoded.stationtostation.data.StoredAttendance
import io.github.magnusencoded.stationtostation.data.StoredLog
import io.github.magnusencoded.stationtostation.data.isLocal
import io.github.magnusencoded.stationtostation.data.rankTitles
import io.github.magnusencoded.stationtostation.data.weaveSetlist
import io.github.magnusencoded.stationtostation.data.setlistEditEntry
import io.github.magnusencoded.stationtostation.data.futureRows
import io.github.magnusencoded.stationtostation.data.spineNights
import io.github.magnusencoded.stationtostation.data.postFiling
import io.github.magnusencoded.stationtostation.data.setlistPaste
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.mutableStateMapOf
import kotlin.math.abs
import io.github.magnusencoded.stationtostation.data.StoredMedia
import io.github.magnusencoded.stationtostation.data.Band
import io.github.magnusencoded.stationtostation.data.ReleaseHint
import io.github.magnusencoded.stationtostation.data.bandsOf
import io.github.magnusencoded.stationtostation.data.hintForAdding
import io.github.magnusencoded.stationtostation.data.hintForMoving
import io.github.magnusencoded.stationtostation.data.preamble
import io.github.magnusencoded.stationtostation.data.isMyNight
import io.github.magnusencoded.stationtostation.data.visibleToContacts
import io.github.magnusencoded.stationtostation.data.withheldFromContacts
import io.github.magnusencoded.stationtostation.data.gigInviteUri
import io.github.magnusencoded.stationtostation.data.photos.PhotoRepository
import io.github.magnusencoded.stationtostation.data.musicbrainz.MbArtist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSong
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import kotlin.math.roundToInt

// Station to Station — the timeline face of the app.
// Flow: splash (log in with Spotify, or skip to setlists-only) → the timeline
// of your setlist.fm shows → a single night's real setlist → convert to a
// Spotify playlist. Import lives behind the "+" node, not the front door.
// ponytail: the convert/login flow still lives in ConfirmScreen rather than
// here. Fold it in only if the hop between the two ever reads as a seam.

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

/** Amber with the light off: my own **Line** as a **Contact** sees it (#145). */
private val Unlit = Color(0xFF7C7788)
private val UnlitField = Color(0xFF1E1B26)
private val SpotifyGreen = Color(0xFF1DB954)
private val Slate = Color(0xFF6D7E9B) // the future / a connected-source, a cooler light
private val Danger = Color(0xFFE08A8A)

/** The wash behind an armed band, in the accent that band is answering with (#268). */
private val SlateSoft = Color(0x296D7E9B)
private val CrossedSoft = Color(0x296FBF9C)

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
    onOpenProgramme: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Reachable from both the future edge and the empty spine: a collector with no
    // history at all still has a ticket for something.
    var adding by remember { mutableStateOf(false) }
    var addingBill by remember { mutableStateOf(false) }
    var addingByHand by remember { mutableStateOf(false) }

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
    state.friendConflict?.let { conflict ->
        FriendOverwriteDialog(
            conflict = conflict,
            onConfirm = { viewModel.confirmFriendOverwrite() },
            onDismiss = { viewModel.dismissFriendOverwrite() },
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
                    IconButton(onClick = onOpenProgramme) {
                        Icon(
                            Icons.Filled.Schedule,
                            contentDescription = "Festival programme",
                            tint = Faint,
                        )
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
                    suggestions = state.artistSuggestions,
                    onArtistTyped = { viewModel.suggestArtists(it) },
                    onArtistPicked = { viewModel.clearArtistSuggestions() },
                    onAdd = { artist, venue, date ->
                        viewModel.addPlannedGigByHand(artist, venue, date)
                        adding = false
                    },
                    onAddByLink = { link -> viewModel.addPlannedGig(link); adding = false },
                    onDismiss = { viewModel.clearArtistSuggestions(); adding = false },
                )
            }
            if (addingByHand) {
                AddLocalGigDialog(
                    onAdd = { artist, venue, date ->
                        viewModel.addLocalGig(artist, venue, date)
                        addingByHand = false
                    },
                    onDismiss = { addingByHand = false },
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
                    EmptyTimeline(
                        onAdd = onOpenImport,
                        onPlan = { adding = true },
                        onAddByHand = { addingByHand = true },
                    )

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
                    // The legend keeps the whole list — it has to offer a hidden person
                    // back — and everything that draws reads the filtered one. #266.
                    val allLanes = remember(state.friends) { state.friends.reversed() }
                    val lanes = remember(allLanes, state.hiddenLines) {
                        visibleLanes(allLanes, state.hiddenLines)
                    }
                    val colours = remember(allLanes, state.hiddenLines) {
                        laneColours(allLanes, state.hiddenLines)
                    }
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
                    // Pulling down at the top of the line opens a gap toward the future,
                    // and the two ways in hang in that gap. How far you pull is what
                    // picks one: the curtain used to latch open and grow two rows on the
                    // timeline underneath it, which spent a continuous gesture on a
                    // boolean and made the actions a consequence of reading a caption.
                    val scope = rememberCoroutineScope()
                    val pull = remember { Animatable(0f) }
                    // 200dp of gap: enough travel to separate three detents by more than
                    // a twitch, and enough drag that none is reached by an ordinary flick
                    // at the top of the list.
                    val pullMax = with(LocalDensity.current) { 200.dp.toPx() }
                    val haptics = LocalHapticFeedback.current
                    val pullNest = remember {
                        object : NestedScrollConnection {
                            /** Last detent crossed, so each one ticks once. */
                            var lastArmed = PlanningDoor.None

                            /** Move the gap by a raw drag delta, ticking on each detent. */
                            fun drag(dy: Float) {
                                scope.launch {
                                    pull.snapTo((pull.value + dy * PullDamping).coerceIn(0f, pullMax))
                                    // A detent you cannot feel is a threshold, and two
                                    // outcomes separated by a bare distance are a coin
                                    // flip in the hand.
                                    val now = armedDoor(pull.value / pullMax)
                                    if (now != lastArmed) {
                                        lastArmed = now
                                        if (now != PlanningDoor.None) {
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    }
                                }
                            }

                            override fun onPreScroll(
                                available: Offset,
                                source: NestedScrollSource,
                            ): Offset {
                                // Closing has to happen *before* the list sees the drag,
                                // or the list eats it and the gap never comes back up.
                                // See curtainTakes for why.
                                if (source != NestedScrollSource.UserInput) return Offset.Zero
                                val take = curtainTakes(available.y, pull.value)
                                if (take == 0f) return Offset.Zero
                                drag(take)
                                return Offset(0f, take)
                            }

                            override fun onPostScroll(
                                consumed: Offset,
                                available: Offset,
                                source: NestedScrollSource,
                            ): Offset {
                                // Opening: only the leftover downward scroll at the list's
                                // own top edge reaches here, so this never steals an
                                // ordinary scroll. Upward is handled in onPreScroll above.
                                if (available.y <= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
                                drag(available.y)
                                return Offset(0f, available.y)
                            }

                            override suspend fun onPreFling(available: Velocity): Velocity {
                                // Release takes the lit door. Releasing with none lit
                                // closes the gap, so a short pull stays cheap to abandon.
                                when (armedDoor(pull.value / pullMax)) {
                                    PlanningDoor.Gig -> adding = true
                                    PlanningDoor.Programme -> onOpenProgramme()
                                    PlanningDoor.Import -> onOpenImport()
                                    PlanningDoor.None -> {}
                                }
                                lastArmed = PlanningDoor.None
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
                        //
                        // Also the filter: tapping a name hides that line and tapping it
                        // again brings it back, so the control sits where the names
                        // already are rather than on a screen of its own. Shown while
                        // zoomed out even with everyone hidden — a name you cannot see
                        // is a name you cannot restore (#266).
                        if (zoomedOut || laneWidth > 0.dp) {
                            Row(
                                Modifier
                                    .horizontalScroll(rememberScrollState())
                                    .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                LaneKey(Amber, "You")
                                allLanes.forEachIndexed { i, friend ->
                                    Spacer(Modifier.width(14.dp))
                                    LaneKey(
                                        color = railColor(i),
                                        label = friend.name,
                                        hidden = friend.setlistfm in state.hiddenLines,
                                        onToggle = { viewModel.toggleLineHidden(friend.setlistfm) },
                                    )
                                }
                            }
                        }
                        PlanningPull(progress = { pull.value / pullMax }, heightPx = { pull.value })
                        // Planned nights become Sections too, so adding one can create an
                        // evening nothing has been asked about yet (#134).
                        LaunchedEffect(state.setlists, state.plannedGigs) {
                            viewModel.resolveFestivals()
                        }
                        LaunchedEffect(zoomedOut) { if (zoomedOut) viewModel.loadFriendTimelines() }
                        val rows = remember(
                            state.setlists, state.plannedGigs, state.attendanceByGig,
                            state.festivals, lanes, state.showsByFriend, zoomedOut, expanded,
                        ) {
                            weaveTimelines(
                                // Through `spineNights`, not `setlists` alone: a local gig
                                // that stops being a plan — checked into, or committed off
                                // a programme whose set has already finished — leaves the
                                // future lane at once, and the spine only picked it up on
                                // the next cold start. It landed nowhere in between.
                                // Deduped on id there, so a night on both lists is one.
                                mine = spineNights(
                                    state.setlists, state.plannedGigs, state.attendanceByGig,
                                ),
                                festivals = state.festivals,
                                friends = if (zoomedOut) lanes else emptyList(),
                                theirs = if (zoomedOut) state.showsByFriend else emptyMap(),
                                expanded = expanded,
                            )
                        }
                        LaunchedEffect(rows, lanes) { logWovenRows(rows, lanes, colours) }
                        // Everything above today, in one date-ordered list — furthest
                        // out first, the same descending order the attended rows use.
                        // Hoisted out of the LazyColumn because the deep-link scroll
                        // below counts it too, and the two must not drift.
                        val future = remember(
                            state.bills, state.plannedGigs, state.attendanceByGig, state.festivals,
                        ) {
                            val billGigs =
                                state.bills.flatMap { b -> b.acts.mapNotNull { it.gigId } }.toSet()
                            futureRows(
                                bills = state.bills,
                                tickets = state.plannedGigs.filterNot { it.id in billGigs },
                                attendance = state.attendanceByGig,
                                festivals = state.festivals,
                            )
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
                                row.node is TimelineNode.Several && row.key !in expanded
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
                                        onDragEnd = {
                                            // Left is Exchange; right is the light switch,
                                            // which is free here because there is nothing
                                            // further out than my own Line (#145). A light
                                            // is not a place, so the same flick returns.
                                            if (dragX <= -threshold) onOpenNearby()
                                            else if (dragX >= threshold) viewModel.toggleContactLight()
                                        },
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
                                }
                                // The same three moves, for anyone not making them with
                                // their fingers. A flick and a pinch are the whole of how
                                // this screen changes **Resolution**, and TalkBack sends
                                // both to the reader instead — so without this the light
                                // and the other lines are not merely awkward to reach,
                                // they do not exist. The gestures above stay exactly as
                                // they are; this is the same call from another door.
                                //
                                // Labels are verbs and say which way the toggle goes,
                                // because the actions menu reads them out of context with
                                // nothing on screen to disambiguate them.
                                .semantics {
                                    customActions = listOf(
                                        CustomAccessibilityAction("Connect with someone nearby") {
                                            onOpenNearby(); true
                                        },
                                        CustomAccessibilityAction(
                                            if (state.contactLight) "Turn the contact light off"
                                            else "Turn the contact light on, to see your line as a contact sees it"
                                        ) { viewModel.toggleContactLight(); true },
                                        CustomAccessibilityAction(
                                            if (zoomedOut) "Close the other timelines"
                                            else "Open the other timelines beside yours"
                                        ) { viewModel.setZoomedOut(!zoomedOut); true },
                                        // The two doors moved into the curtain, and a
                                        // pull depth is not a thing TalkBack can express
                                        // — so without these the only way into planning
                                        // would be a gesture the reader intercepts.
                                        CustomAccessibilityAction("Add a gig you're going to") {
                                            adding = true; true
                                        },
                                        CustomAccessibilityAction("Add a festival lineup") {
                                            addingBill = true; true
                                        },
                                        CustomAccessibilityAction("Import your setlist.fm history") {
                                            onOpenImport(); true
                                        },
                                    )
                                },
                        ) {
                            // The top of the line. Nothing sits here now but the lookup
                            // notice: "↑ THE FUTURE" captioned a direction the layout
                            // already states, and the add-rows that outlived it were the
                            // curtain's doors printed a second time — the doors were
                            // meant to *replace* them, not join them.
                            item { FuturePrompt(loading = state.planningLoading) }
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
                            // two nodes on one line. Planned gigs that share a venue and
                            // a night are a Festival like any other, grouped by the same
                            // function the attended rows use (#134); a Bill stays its own
                            // node, since an announced lineup is a different thing.
                            items(
                                future,
                                key = { row ->
                                    when (row) {
                                        is FutureRow.OnBill -> "bill-${row.bill.id}"
                                        is FutureRow.Ticket -> when (val n = row.node) {
                                            is TimelineNode.Concert -> "planned-${n.setlist.id}"
                                            // Prefixed for the same reason the concert
                                            // above it is: both lanes are items of one
                                            // LazyColumn, and a Festival with a night
                                            // still planned and a night already attended
                                            // is a node in each. The bare identity key
                                            // was used twice and the list threw.
                                            is TimelineNode.Several -> "planned-${n.key}"
                                        }
                                    }
                                },
                            ) { row ->
                                when (row) {
                                    is FutureRow.OnBill -> BillItem(
                                        bill = row.bill,
                                        open = row.bill.id in expanded,
                                        fetching = state.billFetching == row.bill.id,
                                        onToggle = { viewModel.toggleFestival(row.bill.id) },
                                        onPlayed = { i, night ->
                                            viewModel.markActPlayed(row.bill.id, i, night)
                                        },
                                        onUnmark = { i -> viewModel.unmarkAct(row.bill.id, i) },
                                        onOpenGig = { gigId ->
                                            state.plannedGigs.firstOrNull { it.id == gigId }?.let {
                                                viewModel.openShow(it)
                                                onOpenEvent()
                                            }
                                        },
                                        onRename = { i, name -> viewModel.renameAct(row.bill.id, i, name) },
                                        onSurprise = { name, night ->
                                            viewModel.addSurpriseAct(row.bill.id, name, night)
                                        },
                                        onFetchCandidates = { viewModel.fetchCandidates(row.bill.id) },
                                        onRemove = { viewModel.removeBill(row.bill.id) },
                                    )

                                    is FutureRow.Ticket -> when (val node = row.node) {
                                        is TimelineNode.Concert -> TimelineItem(
                                            setlist = node.setlist,
                                            highlight = false,
                                            planned = true,
                                            laneWidth = laneWidth,
                                            onClick = {
                                                viewModel.openShow(node.setlist)
                                                onOpenEvent()
                                            },
                                        )

                                        // Opens in place, like every other node holding
                                        // several nights. It has to open: collapsing two
                                        // planned nights into one node with no way back
                                        // in would take away the only handle each had.
                                        is TimelineNode.Several -> {
                                            val key = node.key
                                            Column {
                                                FestivalItem(
                                                    festival = node,
                                                    highlight = false,
                                                    open = key in expanded,
                                                    laneWidth = laneWidth,
                                                    onClick = { viewModel.toggleFestival(key) },
                                                )
                                                if (key in expanded) {
                                                    node.shows.forEach { gig ->
                                                        TimelineItem(
                                                            setlist = gig,
                                                            highlight = false,
                                                            planned = true,
                                                            inside = true,
                                                            laneWidth = laneWidth,
                                                            onClick = {
                                                                viewModel.openShow(gig)
                                                                onOpenEvent()
                                                            },
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                                val isFirst = index == 0
                                val rails: @Composable () -> Unit =
                                    { PeopleRails(row, rows.getOrNull(index + 1), lanes, laneWidth, colours) }
                                val nodeX = crossingX(row, lanes, laneWidth)
                                when (val node = row.node) {
                                    is TimelineNode.Concert -> {
                                        // Visuals only. A Note has no bytes and an empty
                                        // `ref` (#170), and one drew a blank tile on the row.
                                        val nightMedia = state.mediaBySetlist[node.setlist.id]
                                            .orEmpty().filterNot { it.kind == StoredMedia.Kind.NOTE }
                                        TimelineItem(
                                            setlist = node.setlist,
                                            highlight = isFirst && row.mine,
                                            mine = row.mine,
                                            laneWidth = laneWidth,
                                            inside = row.depth > 0,
                                            nodeX = nodeX,
                                            shared = row.shared && !state.contactLight,
                                            unlit = state.contactLight,
                                            rails = rails,
                                            // Unfiltered on purpose. Filtering here removed a
                                            // night's whole photo strip, so every row changed
                                            // height and the line moved under you — the one
                                            // thing a light switch must never do.
                                            photos = nightMedia.map { Uri.parse(it.ref) },
                                            // Which is why the answer rides alongside instead:
                                            // the same thumbnails in the same places, lit one
                                            // by one. The Room still holds the detail and the
                                            // sharing decision; the timeline now at least says
                                            // truthfully which nights are worth opening.
                                            litPhotos = visibleToContacts(nightMedia)
                                                .map { Uri.parse(it.ref) }.toSet(),
                                            loadPhotoPreview = viewModel::photoPreview,
                                            onClick = {
                                                viewModel.openShow(node.setlist)
                                                onOpenEvent()
                                            },
                                        )
                                    }

                                    // A festival opens where it stands rather than pushing
                                    // you into a screen of its own.
                                    is TimelineNode.Several -> FestivalItem(
                                        festival = node,
                                        highlight = isFirst,
                                        open = row.key in expanded,
                                        mine = row.mine,
                                        laneWidth = laneWidth,
                                        nodeX = nodeX,
                                        sharedCount = row.sharedCount,
                                        theirCount = row.theirsCount,
                                        // Company has a colour of its own — a night two
                                        // friends shared is nobody's lane colour either.
                                        // …and the lane colour is the host's *stable* one,
                                        // so hiding someone never repaints this (#266).
                                        theirColor = if (row.others.size > 1) Crossed
                                        else railColor(colours.getOrElse(nodeHost(row, lanes)) { 0 }),
                                        unlit = state.contactLight,
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
            // The light is on, and it says so across the whole width. Not a badge: a mode
            // you can forget you are in would make withheld photographs read as data loss.
            //
            // Floated over the timeline rather than placed above it, because **flipping
            // the switch must not move the line**. Lighting a corridor does not shorten
            // it: everything here changes colour and opacity and nothing changes size or
            // position, so the night you were looking at is still under your thumb.
            if (state.contactLight) {
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(UnlitField)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text(
                        "AS YOUR CONTACTS SEE IT",
                        color = Ink,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.5.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "One view for everyone you have met in person — there are no per-contact " +
                            "settings. Walk into a night to see what they see of it. Swipe right " +
                            "again to come back.",
                        color = Muted,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

/**
 * Top of the timeline — the future. Only ever a notice now: the ways in are the doors
 * inside the curtain, and printing them here too made the pull decorative.
 */
@Composable
private fun FuturePrompt(loading: Boolean) {
    if (!loading) return
    Text(
        "Looking it up on setlist.fm…",
        color = Faint,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 14.dp),
    )
}

/**
 * A gig you're going to: who is playing, where, and when.
 *
 * **This used to be a paste box for a setlist.fm link**, defended on two grounds. The
 * first still holds: setlist.fm's search index stops about a day out, so a show weeks
 * away cannot be *found* by artist, venue or date (#29). The second — that typing the
 * details in "would invent a second record for a gig setlist.fm already has" — has not
 * been true since the **Bill** shipped. `markActPlayed` mints local **Gig**s for nights
 * setlist.fm has never heard of and `adoptSetlistId` moves one onto the vendor id when
 * setlist.fm catches up, with every association intact. The collision that argument
 * described is one the codebase learned to resolve two features ago.
 *
 * **The link path stays, demoted.** It is strictly better when you have the link: it
 * brings the real id, the real venue and the real date, and needs no adoption later.
 *
 * **The artist completes; the venue does not.** MusicBrainz has a `place` entity and
 * its coverage of small rooms is thin, so a completion box that fails most of the time
 * would teach people to ignore the one above it. A plain field that never guesses is
 * the honest version of a venue.
 */
@Composable
private fun AddPlannedGigDialog(
    suggestions: List<MbArtist>,
    onArtistTyped: (String) -> Unit,
    onArtistPicked: () -> Unit,
    onAdd: (artist: String, venue: String, date: String) -> Unit,
    onAddByLink: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var artist by remember { mutableStateOf("") }
    var venue by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var pasting by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Raised)
                .padding(20.dp),
        ) {
            Text("A gig you're going to", fontFamily = Serif, fontSize = 19.sp, color = Ink)
            Spacer(Modifier.height(6.dp))
            if (pasting) {
                Text(
                    "Paste the setlist.fm link for the show. It brings the real venue " +
                        "and date with it.",
                    color = Muted,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(14.dp))
                StationField(link, { link = it }, "setlist.fm link", imeDone = true)
            } else {
                Text(
                    "It can't be searched for this far ahead, so this night lives on " +
                        "this phone until setlist.fm catches up with it.",
                    color = Muted,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(14.dp))
                StationField(artist, { artist = it; onArtistTyped(it) }, "who's playing")
                // Suggestions sit directly under the field they belong to and nowhere
                // else. Capped at four rows: this is a prompt above a keyboard, and a
                // list that scrolls is a search result page pretending to be a hint.
                suggestions.take(4).forEach { hit ->
                    Text(
                        buildString {
                            append(hit.name)
                            if (hit.disambiguation.isNotBlank()) append("  · ${hit.disambiguation}")
                        },
                        color = Slate,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { artist = hit.name; onArtistPicked() }
                            .padding(vertical = 6.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                StationField(venue, { venue = it }, "venue (optional)")
                Spacer(Modifier.height(8.dp))
                StationField(date, { date = it }, "date (dd-MM-yyyy)", imeDone = true)
            }

            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { pasting = !pasting }) {
                Text(
                    if (pasting) "or type it in" else "or paste a setlist.fm link",
                    color = Faint,
                    fontSize = 12.sp,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = Faint) }
                val ready =
                    if (pasting) link.isNotBlank() else artist.isNotBlank() && date.isNotBlank()
                TextButton(
                    onClick = {
                        if (pasting) onAddByLink(link) else onAdd(artist, venue, date)
                    },
                    enabled = ready,
                ) { Text("Add", color = if (ready) Amber else Faint) }
            }
        }
    }
}

/**
 * A night you were at, typed in — the door onto the zero-account floor (#225).
 *
 * The mirror image of [AddPlannedGigDialog], and the difference between them is the
 * whole reason both exist. A gig you are *going to* cannot be typed in, because
 * setlist.fm's search stops about a day out and a hand-typed future night would
 * invent a second record for one setlist.fm already holds. A night that has already
 * happened, entered by someone with no setlist.fm account, has no upstream record to
 * collide with — there is nothing to import, which is exactly why this is here.
 *
 * The venue is optional and blank is honest. What the app cannot do is guess it.
 */
@Composable
private fun AddLocalGigDialog(
    onAdd: (artist: String, venue: String, date: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var artist by remember { mutableStateOf("") }
    var venue by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Raised)
                .padding(20.dp),
        ) {
            Text("A night you were at", fontFamily = Serif, fontSize = 19.sp, color = Ink)
            Spacer(Modifier.height(6.dp))
            Text(
                "No account needed. This night lives on this phone, and what was " +
                    "played goes in its log afterwards.",
                color = Muted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(14.dp))
            StationField(artist, { artist = it }, "who played")
            Spacer(Modifier.height(8.dp))
            StationField(venue, { venue = it }, "venue (optional)")
            Spacer(Modifier.height(8.dp))
            StationField(date, { date = it }, "date (dd-MM-yyyy)", imeDone = true)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = Faint) }
                TextButton(
                    onClick = { onAdd(artist, venue, date) },
                    enabled = artist.isNotBlank() && date.isNotBlank(),
                ) {
                    Text(
                        "Add it",
                        color = if (artist.isBlank() || date.isBlank()) Faint else Amber,
                    )
                }
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
 * The one question a handed-over card has to ask: it names someone I already hold, and
 * says something different about them (#188).
 *
 * Shown only for a change. A card for a stranger is written without asking, and the
 * same card twice asks nothing — a prompt that routinely means nothing is a prompt
 * nobody reads, and this one has to be read.
 *
 * It names **both** values rather than only the new one, because the question is not
 * "is this name plausible" but "did the person in front of you mean to change what you
 * already had". A card can be handed over by a radio nobody tapped.
 */
@Composable
private fun FriendOverwriteDialog(
    conflict: FriendArrival.Conflict,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Raised)
                .padding(20.dp),
        ) {
            Text("Change this contact?", fontFamily = Serif, fontSize = 19.sp, color = Ink)
            Spacer(Modifier.height(6.dp))
            // A changed key is a changed phone, and that is how the question is asked:
            // someone who bought a handset recognises it immediately, and someone who did
            // not has just been shown an attack. Cryptography is not a thing to ask a
            // person about. A first key never lands here — that is a promotion (#188).
            val keyChanged = conflict.existing.publicKey != null &&
                conflict.incoming.publicKey != null &&
                conflict.existing.publicKey != conflict.incoming.publicKey
            Text(
                if (keyChanged) {
                    "${conflict.existing.name} (@${conflict.existing.setlistfm}) seems to " +
                        "be on a different phone than last time you saw them. Confirm you " +
                        "still want to share."
                } else {
                    "A card for @${conflict.existing.setlistfm} says something different " +
                        "from what you have."
                },
                color = Muted,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text("Now: ${conflict.existing.name}", color = Ink, fontSize = 13.sp)
            Text("Card: ${conflict.incoming.name}", color = Amber, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                "Their timeline does not change either way — only the name you see " +
                    "against it.",
                color = Faint,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Keep mine", color = Faint) }
                TextButton(onClick = onConfirm) { Text("Use the card", color = Amber) }
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

/**
 * The empty spine: one lit node you tap to bring in your shows.
 *
 * Three doors, and the third is not decoration. The lit node imports from
 * setlist.fm and the planned-gig row needs a setlist.fm link, so until #225 every
 * way onto a fresh timeline ran through an account the app insists is optional.
 * The manual row is what makes that claim true at the front door as well as in the
 * data model.
 */
@Composable
private fun EmptyTimeline(onAdd: () -> Unit, onPlan: () -> Unit, onAddByHand: () -> Unit) {
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
                .clickable(onClick = onAdd)
                .semantics { contentDescription = "Add your first show" },
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
        // Both rows above end at setlist.fm. This one does not, and it is the only
        // affordance on this screen that a user without an account can act on.
        Text(
            "or type in a night you were at",
            color = Slate,
            fontSize = 13.sp,
            modifier = Modifier.clickable(onClick = onAddByHand).padding(8.dp),
        )
    }
}

/** The setlist.fm import, reached from the "+" node. Pops itself once shows land. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(viewModel: AppViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val startCount = remember { viewModel.state.value.setlists.size }
    var username by remember { mutableStateOf(state.mySetlistFmUser) }
    var apiKey by remember { mutableStateOf("") }
    var byHand by remember { mutableStateOf(false) }

    if (byHand) {
        AddLocalGigDialog(
            onAdd = { artist, venue, date ->
                viewModel.addLocalGig(artist, venue, date)
                byHand = false
                onDone()
            },
            onDismiss = { byHand = false },
        )
    }

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
                // The field alone asks for something a stranger has no way to find.
                Text(
                    "Get a free key at setlist.fm/settings/api ›",
                    color = Amber,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickable {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.setlist.fm/settings/api"))
                            )
                        }
                        .padding(vertical = 6.dp),
                )
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
            Spacer(Modifier.height(20.dp))
            // The door that does not run through an account, kept on the screen whose
            // whole job is "add your shows" — the empty spine offers it too, but the
            // empty spine is gone the moment there is one night on the line (#225).
            Text(
                "or type a night in by hand",
                color = Slate,
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable { byHand = true }
                    .padding(vertical = 8.dp),
            )
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
    /**
     * Under the contact light (#145): the amber comes off, and with it the meeting
     * green. Absence of colour asserts nothing new — the palette is committed, Slate
     * already means an **Act** not yet seen and green already means a night shared —
     * so desaturating is the honest signal that this is not the view of my own **Line**.
     */
    unlit: Boolean = false,
    rails: @Composable () -> Unit = {},
    photos: List<Uri> = emptyList(),
    /**
     * Which of [photos] a **Contact** actually sees, so the strip can say which under
     * the light rather than dimming all of them alike. Empty off the light, where the
     * question is not being asked and every thumbnail is drawn at full strength.
     *
     * Resolved by [visibleToContacts] at the call site and never re-derived here:
     * ContactView.kt is explicit that a second implementation of this rule will
     * eventually disagree with the first, and that it would disagree in the direction
     * of showing someone less than they are being sent.
     */
    litPhotos: Set<Uri> = emptySet(),
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
                        .background(if (unlit) Unlit.copy(alpha = 0.35f) else Amber.copy(alpha = 0.3f)),
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
                                // A generic contact view has no "we", so a night marked
                                // as shared would claim a relationship this view does
                                // not have — per-contact meaning smuggled back in.
                                unlit -> Unlit
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
                    if (highlight && !shared && !unlit) {
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
                // Opacity, not absence: the same three thumbnails in the same three
                // places, so nothing above or below them moves.
                //
                // Per thumbnail, not per strip. Dimming the whole row was uniform, and
                // uniform is the failure ContactView.kt names about absence — it cannot
                // tell a night I shared nothing from a night I shared everything. A night
                // with an empty vault came up as dark as a withheld one, which does not
                // merely under-inform, it misreports. Count and slots are unchanged, so
                // the reflow this dimming exists to avoid still cannot happen.
                Row {
                    photos.take(3).forEach { uri ->
                        PhotoThumb(
                            uri,
                            size = 44.dp,
                            loadPreview = loadPhotoPreview,
                            modifier = Modifier.alpha(if (unlit && uri !in litPhotos) 0.35f else 1f),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                }
            }
            Spacer(Modifier.height(7.dp))
            Text(
                when {
                    planned -> plannedStatus(setlist.localDate(), songCount = songCount)
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
private fun LaneKey(
    color: Color,
    label: String,
    hidden: Boolean = false,
    onToggle: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .then(
                // A real toggle rather than a tap handler, so a switch or keyboard user
                // gets the control and TalkBack says which way it is before they use it.
                if (onToggle == null) Modifier
                else Modifier
                    .toggleable(value = !hidden, role = Role.Switch) { onToggle() }
                    .semantics { stateDescription = if (hidden) "hidden" else "shown" },
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Hidden is said twice over: the swatch goes out and the name is struck
        // through, so the state survives a colour the reader cannot discriminate.
        Box(Modifier.width(3.dp).height(12.dp).background(if (hidden) Faint else color))
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            color = if (hidden) Faint else Muted,
            fontSize = 11.sp,
            textDecoration = if (hidden) TextDecoration.LineThrough else null,
        )
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
 * The **Lanes** actually drawn: everyone in lane order, minus the people tapped out of
 * the legend. [hidden] holds setlist.fm usernames, the same key the friends list itself
 * de-duplicates on.
 *
 * **The one place hiding is applied** (#266). Every consumer of the lane list — the
 * weave that builds the rows, [rowGeometry], [nodeHost], [crossingX] and the dump — is
 * handed this list, so none of them learns that filtering exists and none of them can
 * disagree about who is on screen. A hidden person is not in a row's other-attendees,
 * so they place no **Line**, count into no **Crossing**, and drop out of a **Festival**'s
 * **Together** and **Theirs** by construction rather than by a second subtraction.
 *
 * A reading aid and nothing else: it is not stored, nothing is sent, and it says
 * nothing about the relationship — a hidden **Contact**'s **Gig resolution**, media and
 * **Reconcile** are untouched, because none of them reads a lane list.
 */
internal fun visibleLanes(lanes: List<Friend>, hidden: Set<String>): List<Friend> =
    if (hidden.isEmpty()) lanes else laneColours(lanes, hidden).map(lanes::get)

/**
 * The colour index each visible **Lane** keeps: its position in the *unfiltered* list.
 *
 * The one thing the seam above does not give for free. **Lane colour** is taken from an
 * index, and the drawn index re-packs when someone is hidden — so without this, hiding
 * one person repaints everyone outside them and a colour you have learned to read stops
 * meaning a person. Kept here rather than in the canvas so "hiding does not recolour
 * anyone" is assertable with no canvas and no device.
 */
internal fun laneColours(lanes: List<Friend>, hidden: Set<String>): List<Int> =
    lanes.indices.filterNot { lanes[it].setlistfm in hidden }

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
 * What a **Node** is, in the log's own vocabulary. The three are a real distinction —
 * a **Section** claims one evening in one room, a **Festival** claims an identity — and
 * a dump that flattened them would hide exactly the bug #166 fixed.
 */
private fun nodeKind(node: TimelineNode): String = when (node) {
    is TimelineNode.Concert -> "gig"
    is TimelineNode.Section -> "section"
    is TimelineNode.Festival -> "festival"
}

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
internal fun logWovenRows(
    rows: List<WovenRow>,
    lanes: List<Friend>,
    colours: List<Int> = emptyList(),
) {
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
                "node=${nodeKind(row.node)} " +
                "with=[${row.others.joinToString(",") { it.setlistfm }}] " +
                "together=${row.sharedCount} theirs=${row.theirsCount} " +
                "here=${row.showsHereByFriends.size} " +
                "host=${nodeHost(row, lanes)} $where key=${row.key}",
        )
        rowGeometry(row, rows.getOrNull(i + 1), lanes, laneWidth, DumpRowHeight, colours).forEach { d ->
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
    is LineColour.Rail -> railColor(colourIndex)
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
    colours: List<Int> = emptyList(),
) {
    if (laneWidth <= 0.dp || friends.isEmpty()) return
    Canvas(Modifier.fillMaxSize()) {
        val h = size.height
        val drawn = rowGeometry(row, next, friends, laneWidth, h.toDp(), colours)
        val ring = Stroke(width = 2.dp.toPx())
        val nodeAt = nodeHost(row, friends)

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
            val drawsNode = d.present && !row.mine && row.node !is TimelineNode.Several &&
                d.line == nodeAt
            if (drawsNode) {
                // The role this line already carries, not a second colour decision:
                // company here *is* people > 1, and the lane's colour is its stable
                // one, which a drawn index stops being once anyone is hidden (#266).
                drawCircle(
                    d.colour.paint(),
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
 * A night's **Media**, in its two bands (#162).
 *
 * **Position is the bit.** The upper band is what a **Contact** can see, the lower is
 * what only I can, and which band a photograph sits in *is* its **Personal** bit.
 * **Amber** edges mine in *both* bands, because Amber means mine and never
 * held-back; the cooler light edges **Received media**, which sits to the right of my
 * own and cannot be dragged at all — its disposition is not mine to set.
 *
 * **The handle teaches itself.** At rest it is a two-way arrow, which says only that
 * it moves. Drag it and the band you are over answers with the whole sentence, so you
 * learn both halves of the model before spending anything — the drag is reversible
 * right up to the release. Down is the vault, deliberately: it is the easier reach,
 * and the direction an unfamiliar thumb drifts must be the one that shares nothing.
 *
 * Long-press a photograph to arrange. [arranging] is owned by the **Room** rather
 * than by this composable, which is what lets a tap anywhere that is not an [x] leave
 * it again.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GigMediaBands(
    media: List<StoredMedia>,
    loadPreview: suspend (Uri) -> MediaThumb,
    arranging: Boolean,
    contactLight: Boolean,
    /**
     * Whether this night is mine to change (#327). Distinct from [contactLight], which
     * is a *preview* of someone else's view of my own night — this is someone else's
     * night. Both suppress editing and they are not the same question, so the room may
     * be read-only for either reason.
     */
    editable: Boolean,
    senderName: (String) -> String?,
    onArrange: () -> Unit,
    onAdd: (Band) -> Unit,
    onOpen: (Uri) -> Unit,
    onRemove: (StoredMedia) -> Unit,
    onMove: (String, Band, Int) -> Unit,
) {
    // Two splits of the same night, and the difference between them is the whole of
    // #50's wiring. [all] is every item and answers *who is in the commons* — a
    // **Note** in the shared band makes me a contributor exactly as a photograph
    // does. [bands] is the visual run only and answers *what the strip draws*: the
    // strip's index maths is tile-strided, and a full-width prose row is not a tile.
    //
    // MediaBands itself stays kind-blind, which is the claim this feature rests on.
    val all = bandsOf(media)
    val bands = bandsOf(media.filterNot { it.kind == StoredMedia.Kind.NOTE })
    val density = LocalDensity.current
    val strideX = with(density) { (GigPhotoSize + ItemGap).toPx() }
    val padStart = with(density) { 20.dp.toPx() }

    val sharedScroll = rememberScrollState()
    val vaultScroll = rememberScrollState()
    // Each strip's rectangle in root coordinates, so a drop lands in the band the
    // finger is actually over. Guessing it from the sign of the vertical travel put
    // the shared band 44dp from a vault photograph, which is inside the vault's own
    // row — the one direction that must be hard to hit by accident was the cheapest.
    val strips = remember { mutableStateMapOf<Band, Rect>() }

    var over by remember { mutableStateOf<Band?>(null) }
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragFrom by remember { mutableStateOf(Band.SHARED) }
    var dragTo by remember { mutableStateOf<Band?>(null) }
    var dragIndex by remember { mutableStateOf(0) }

    fun listOf(band: Band) = if (band == Band.SHARED) bands.shared else bands.vault
    fun scrollOf(band: Band) = if (band == Band.SHARED) sharedScroll else vaultScroll

    fun bandUnder(p: Offset): Band {
        strips.forEach { (band, r) -> if (p.y >= r.top && p.y <= r.bottom) return band }
        val shared = strips[Band.SHARED] ?: return dragFrom
        val vault = strips[Band.VAULT] ?: return dragFrom
        return if (abs(p.y - shared.center.y) <= abs(p.y - vault.center.y)) Band.SHARED else Band.VAULT
    }

    /**
     * Where in [band] the finger is, counted over that band *without* the item being
     * carried — which is the list [moveMedia] inserts into, so the slot that opens is
     * the position the photograph actually takes.
     */
    fun indexUnder(band: Band, p: Offset): Int {
        val r = strips[band] ?: return 0
        val x = p.x - r.left + scrollOf(band).value - padStart
        val room = listOf(band).size - if (band == dragFrom) 1 else 0
        return ((x + strideX / 2f) / strideX).toInt().coerceIn(0, room.coerceAtLeast(0))
    }

    // What letting go would do to the shared band, asked the same way by both
    // gestures — see [releaseHint]. Nothing here special-cases the direction.
    val hint = when {
        dragId != null && dragTo != null -> hintForMoving(media, dragId!!, dragTo!!)
        over != null -> hintForAdding(media, over!!)
        else -> ReleaseHint.NONE
    }
    val promised = if (dragId != null) dragTo else over

    val startDrag = { band: Band, p: Offset ->
        val r = strips[band]
        val at = if (r == null) -1 else ((p.x - r.left + scrollOf(band).value - padStart) / strideX).toInt()
        val item = listOf(band).getOrNull(at)
        if (item != null) {
            dragId = item.id
            dragFrom = band
            dragTo = band
            dragIndex = at
        }
    }
    val moveDrag = { p: Offset ->
        if (dragId != null) {
            val band = bandUnder(p)
            dragTo = band
            dragIndex = indexUnder(band, p)
        }
    }
    val endDrag = {
        dragId?.let { onMove(it, dragTo ?: dragFrom, dragIndex) }
        dragId = null
        dragTo = null
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MediaBand(
                band = Band.SHARED,
                label = "Shared",
                mine = bands.shared,
                received = bands.received,
                // What the band *would* hold, not what changes: the outline is a
                // statement about the collection, so a band already crossed keeps
                // saying so while you hover over it (#268). Off the whole night, not
                // the strip — a **Contact** who sent only a **Note** is still someone
                // I shared the night with.
                crossed = when {
                    promised != Band.SHARED -> all.crossed
                    hint == ReleaseHint.GAINED -> true
                    hint == ReleaseHint.LOST -> false
                    else -> all.crossed
                },
                say = when {
                    promised != Band.SHARED -> null
                    hint == ReleaseHint.GAINED -> {
                        val who = all.received.mapNotNull { it.from }.distinct()
                            .mapNotNull(senderName)
                        // Named where the name is known. There is no join from a
                        // sender's key to a Contact's name yet, so this degrades
                        // rather than inventing one.
                        val subject = when (who.size) {
                            0 -> "someone else is"
                            1 -> who.single() + " is"
                            else -> who.joinToString(" and ") + " are"
                        }
                        "$subject already here — let go and it becomes a night you shared"
                    }
                    hint == ReleaseHint.LOST -> "let go and this stops being a night you shared"
                    else -> null
                },
                offering = over == Band.SHARED,
                offerText = "Share a picture or video",
                arranging = arranging,
                draggingId = dragId,
                slotAt = if (dragTo == Band.SHARED) dragIndex else null,
                scroll = sharedScroll,
                loadPreview = loadPreview,
                onBounds = { strips[Band.SHARED] = it },
                onOpen = onOpen,
                onRemove = onRemove,
                onArrange = onArrange,
                onDragStart = { startDrag(Band.SHARED, it) },
                onDragAt = moveDrag,
                onDrop = endDrag,
            )
            // Under the contact light the room holds what a Contact can see, and they
            // cannot see the vault at all — so it is absent rather than drawn empty,
            // which would have it claim "nothing held back" over a full vault.
            if (!contactLight) {
                MediaBand(
                    band = Band.VAULT,
                    label = "In the vault",
                    mine = bands.vault,
                    received = emptyList(),
                    crossed = false,
                    say = null,
                    offering = over == Band.VAULT,
                    offerText = "Add a picture or video just for you",
                    arranging = arranging,
                    draggingId = dragId,
                    slotAt = if (dragTo == Band.VAULT) dragIndex else null,
                    scroll = vaultScroll,
                    loadPreview = loadPreview,
                    onBounds = { strips[Band.VAULT] = it },
                    onOpen = onOpen,
                    onRemove = onRemove,
                    onArrange = onArrange,
                    onDragStart = { startDrag(Band.VAULT, it) },
                    onDragAt = moveDrag,
                    onDrop = endDrag,
                )
            }
        }
        if (editable) {
            Spacer(Modifier.width(10.dp))
            AttachHandle(
                // The travel is the distance to the bands themselves, so the handle
                // stops where the thing it is pointing at is rather than at a number
                // (#268). Measured off the same rects the drop test uses.
                travel = { at ->
                    val up = strips[Band.SHARED]?.let { it.center.y - at } ?: -160f
                    val down = strips[Band.VAULT]?.let { it.center.y - at } ?: 160f
                    up.coerceAtMost(0f)..down.coerceAtLeast(0f)
                },
                onOver = { over = it },
                onRelease = { band -> over = null; band?.let(onAdd) },
            )
            Spacer(Modifier.width(4.dp))
        }
    }
}

/**
 * The two-way arrow, and the only control that adds.
 *
 * It carries no state during the drag on purpose: a thumb is on top of it for the
 * whole gesture, so anything it said would be said where nobody can read it. The
 * bands answer instead. A tap does nothing, which reads as the wrong gesture rather
 * than as a broken app — a plus that ignored a tap would read as the second.
 *
 * Half a tile wide and a full tile tall: it is a rail the thumb runs along, not a
 * button, and at tile-square it read as a missing photograph (#268). [travel] answers
 * how far it may run given where it is resting — the band centres, so it arrives at
 * the thing it is pointing at instead of stopping at an arbitrary 160px.
 */
@Composable
private fun AttachHandle(
    travel: (restingCentreY: Float) -> ClosedFloatingPointRange<Float>,
    onOver: (Band?) -> Unit,
    onRelease: (Band?) -> Unit,
) {
    val commit = with(LocalDensity.current) { 14.dp.toPx() }
    var offsetY by remember { mutableStateOf(0f) }
    var chosen by remember { mutableStateOf<Band?>(null) }
    // Read off the *outer* box, which never moves — measuring the offset one would
    // fold the drag back into its own limits.
    var restingY by remember { mutableStateOf(0f) }

    Box(
        Modifier
            .onGloballyPositioned { restingY = it.boundsInRoot().center.y }
            .offset { IntOffset(0, offsetY.roundToInt()) }
            .width(GigPhotoSize / 2)
            .height(GigPhotoSize)
            .clip(RoundedCornerShape(10.dp))
            .background(Raised2)
            // Never Amber, and never anything else either: the doc above is the rule
            // and this line was the exception to it, left over from #162 — before
            // #268 settled that amber is the vault's and an upward drag must not
            // reach for it. A handle that lit amber on the way *up* said the one
            // thing the colour is not allowed to say, under a thumb, where nobody
            // could read it anyway. The bands answer.
            .border(1.dp, LineLit, RoundedCornerShape(10.dp))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, amount ->
                        change.consume()
                        offsetY = (offsetY + amount.y).coerceIn(travel(restingY))
                        chosen = when {
                            offsetY < -commit -> Band.SHARED
                            offsetY > commit -> Band.VAULT
                            else -> null
                        }
                        onOver(chosen)
                    },
                    onDragEnd = {
                        onRelease(chosen)
                        offsetY = 0f
                        chosen = null
                    },
                    onDragCancel = {
                        onRelease(null)
                        offsetY = 0f
                        chosen = null
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "↕",
            color = Muted,
            fontSize = 26.sp,
        )
    }
}

/**
 * What a band outlines itself in, media and prose alike (#268).
 *
 * Three colours for three facts, and no colour carries two: **Amber** is the vault
 * and means *only I can see this*, **Slate** is a shared band holding only mine, and
 * **Crossed** is a shared band more than one of us is in. The upward gesture can
 * therefore never light amber, which is the whole point — the direction that spends
 * something must not be drawn in the colour of the direction that spends nothing.
 */
private fun bandAccent(band: Band, crossed: Boolean): Color = when {
    band == Band.VAULT -> Amber
    crossed -> Crossed
    else -> Slate
}

/** The same three, at the alpha the offer overlay washes the strip with. */
private fun bandWash(band: Band, crossed: Boolean): Color = when {
    band == Band.VAULT -> AmberSoft
    crossed -> CrossedSoft
    else -> SlateSoft
}

/**
 * One band: my own media, then **Received media**, then whatever the gesture in
 * progress is promising.
 *
 * [say] and [offerText] are drawn *over* the strip and never displace it — a state
 * change here is colour, never geometry, which is the rule the contact light
 * established. The landing slot is a real slot in the row, so the photographs open a
 * gap where the one you are carrying will go.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaBand(
    band: Band,
    label: String,
    mine: List<StoredMedia>,
    received: List<StoredMedia>,
    crossed: Boolean,
    say: String?,
    offering: Boolean,
    offerText: String,
    arranging: Boolean,
    draggingId: String?,
    slotAt: Int?,
    scroll: ScrollState,
    loadPreview: suspend (Uri) -> MediaThumb,
    onBounds: (Rect) -> Unit,
    onOpen: (Uri) -> Unit,
    onRemove: (StoredMedia) -> Unit,
    onArrange: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDragAt: (Offset) -> Unit,
    onDrop: () -> Unit,
) {
    // The band's own colour, and the only thing the offer overlay recolours with.
    // **Amber is the vault's**, in both states: it means private here and nothing
    // else, so an upward drag must never reach for it (#268). The shared band answers
    // Slate while it would hold only mine, and **Crossed** once letting go means more
    // than one of us is in it.
    val accent = bandAccent(band, crossed)
    val wash = bandWash(band, crossed)
    val tilePx = with(LocalDensity.current) { (GigPhotoSize + ItemGap).toPx() }
    // The gesture lives on the strip, never on a tile. A tile leaves the composition
    // the moment it is picked up — that is how the gap opens — and a pointerInput on
    // a detached node has its coroutine cancelled, so onDragEnd would never arrive
    // and the drop would silently never commit.
    var here by remember { mutableStateOf<LayoutCoordinates?>(null) }

    // The strip follows the landing slot rather than the finger. ponytail: this is
    // the whole of "I cannot drag to a position I cannot see" — a free-running edge
    // scroll is more code and the same outcome.
    LaunchedEffect(slotAt) {
        val at = slotAt ?: return@LaunchedEffect
        val left = (at * tilePx).toInt()
        val right = left + tilePx.toInt()
        when {
            left < scroll.value -> scroll.animateScrollTo(left)
            right > scroll.value + scroll.viewportSize ->
                scroll.animateScrollTo((right - scroll.viewportSize).coerceAtLeast(0))
        }
    }

    Column {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 5.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                label,
                color = if (say != null) {
                    if (crossed) Crossed else Muted
                } else {
                    Faint
                },
                fontSize = 10.sp,
            )
            if (say != null) {
                Spacer(Modifier.width(8.dp))
                Text(say, color = if (crossed) Crossed else Muted, fontSize = 10.sp)
            }
        }
        // One frame, and the gesture changes *it* rather than adding a second. Two
        // outlines around one band is what this looked like when the armed state drew
        // its own: they do not even share a rect, because the strip's own border sits
        // inside the scroll container and travels with the content (#268).
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .border(
                    if (offering) 2.dp else 1.dp,
                    accent,
                    RoundedCornerShape(6.dp),
                ),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned {
                        here = it
                        onBounds(it.boundsInRoot())
                    }
                    .horizontalScroll(scroll)
                    .pointerInput(arranging, band, mine.size) {
                        if (!arranging) return@pointerInput
                        detectDragGestures(
                            onDragStart = { at -> here?.let { onDragStart(it.localToRoot(at)) } },
                            onDrag = { change, _ ->
                                change.consume()
                                here?.let { onDragAt(it.localToRoot(change.position)) }
                            },
                            onDragEnd = onDrop,
                            onDragCancel = onDrop,
                        )
                    }
                    // Content padding: it is inside the scroll, so the first tile
                    // starts clear of the edge and scrolls away under it — and
                    // [indexUnder] counts from it. The frame is on the Box outside,
                    // which is the rect that stays still.
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Counted over the band without the carried item, so the gap opens
                // exactly where [moveMedia] will put it.
                var placed = 0
                mine.forEach { item ->
                    if (item.id == draggingId) return@forEach
                    if (slotAt == placed) LandingSlot(accent, wash)
                    MediaTile(
                        item = item,
                        arranging = arranging,
                        loadPreview = loadPreview,
                        onOpen = onOpen,
                        onRemove = onRemove,
                        onArrange = onArrange,
                    )
                    Spacer(Modifier.width(ItemGap))
                    placed++
                }
                if (slotAt != null && slotAt >= placed) LandingSlot(accent, wash)
                received.forEach { item ->
                    MediaTile(
                        item = item,
                        arranging = arranging,
                        loadPreview = loadPreview,
                        onOpen = onOpen,
                        onRemove = onRemove,
                        onArrange = onArrange,
                    )
                    Spacer(Modifier.width(ItemGap))
                }
                if (mine.none { it.id != draggingId } && received.isEmpty() && slotAt == null) {
                    // Rendered empty rather than hidden: a band nobody can see is a
                    // gesture nobody can find on a fresh install.
                    Box(Modifier.height(GigPhotoSize), contentAlignment = Alignment.CenterStart) {
                        Text(
                            if (label == "Shared") "Nothing shared yet" else "Nothing held back",
                            color = Faint,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
            if (offering) {
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(6.dp))
                        .background(wash),
                    contentAlignment = Alignment.Center,
                ) { Text(offerText, color = Ink, fontSize = 12.sp) }
            }
        }
    }
}

/**
 * The night's prose, both bands of it, drawn below the setlist (#50).
 *
 * **Below the set, not beside the photographs.** Analysis happens after the show —
 * the Journalist writes when the lights are up and the Reliver reads after they have
 * been through the night again — so the write-line sits at the end of the room rather
 * than in the middle of it, where it would interrupt the scroll through the material
 * with a demand for a sentence.
 *
 * **Shared above vault, always**, which is the same order the **Bands** are drawn in
 * and the same claim: up is what my **Audience** reads, down is what reaches nobody.
 * The prose leaves the band frames but not the model — a long-press still lifts a note
 * between them, and [MediaBands] still never learns that any of this is text.
 */
@Composable
private fun GigNotes(
    media: List<StoredMedia>,
    /** The night's own facts, already composed. Empty when the record knows nothing. */
    preamble: String,
    senderName: (String) -> String?,
    /**
     * Still needed on its own: the vault row is *absent* under the light rather than
     * merely read-only, because a **Contact** cannot see the vault and an empty row
     * drawn there would claim nothing is held back over a vault that holds something.
     */
    contactLight: Boolean,
    /** Whether this night is mine to write on, and not under the light (#327). */
    editable: Boolean,
    onWrite: (Band, String) -> Unit,
    onVerdict: (String, String?) -> Unit,
    onMove: (String, Band, Int) -> Unit,
) {
    val noteBands = bandsOf(media.filter { it.kind == StoredMedia.Kind.NOTE })
    Column {
        BandNotes(
            band = Band.SHARED,
            mine = noteBands.shared.firstOrNull(),
            received = noteBands.received,
            // The prose's own crossing, not the night's: this outline is a statement
            // about what is written here (#268).
            crossed = noteBands.crossed,
            // Once per night, over whichever note is uppermost. The same sentence
            // twice is noise, and it is a fact about the night rather than about
            // either band.
            preamble = if (noteBands.shared.isNotEmpty()) preamble else "",
            senderName = senderName,
            editable = editable,
            onWrite = { onWrite(Band.SHARED, it) },
            onVerdict = { v -> noteBands.shared.firstOrNull()?.let { onVerdict(it.id, v) } },
            // Withdrawing: the same move a photograph makes, through the same
            // function. One note per band, so there is no index.
            onLift = { id -> onMove(id, Band.VAULT, 0) },
        )
        // Absent under the contact light for the reason the vault strip is: a Contact
        // cannot see the vault, and an empty row drawn there would claim nothing is
        // held back over a vault that holds something.
        if (!contactLight) {
            BandNotes(
                band = Band.VAULT,
                mine = noteBands.vault.firstOrNull(),
                // Nothing arrives here. A **Contact**'s note is something they put in
                // the commons; there is no path by which one lands in my vault.
                received = emptyList(),
                // Never — the vault outlines amber whatever it holds.
                crossed = false,
                preamble = if (noteBands.shared.isEmpty()) preamble else "",
                senderName = senderName,
                // Was `true`: the vault is only ever mine, which is true of the *band*
                // and says nothing about whose *night* this is (#327).
                editable = editable,
                onWrite = { onWrite(Band.VAULT, it) },
                onVerdict = { v -> noteBands.vault.firstOrNull()?.let { onVerdict(it.id, v) } },
                // Publishing a draft. The upward move earns the green promise for
                // free, because [hintForMoving] never asked what kind of item it was
                // holding.
                onLift = { id -> onMove(id, Band.SHARED, 0) },
            )
        }
    }
}

/**
 * One band's prose: my **Note**, then any **Received** ones (#50).
 *
 * **Position is the bit here too.** There is no switch and no badge — a note in the
 * lower band reaches nobody, a note in the upper one reaches my **Audience**, and
 * moving it is the act that changes its mind. Which write-line you tapped is which
 * question you answered, so nothing has to ask a second time.
 *
 * The empty line renders on a night nothing was written about, for the same reason
 * the empty vault strip does: a surface nobody can see is a surface nobody finds.
 *
 * **The write-line stays on top, and everything written sits under it** — mine, then
 * anyone else's. It reads backwards for a second and then stops: you come here to
 * write, and reading what a **Contact** said *after* saying your own piece is the
 * order that keeps the sentence yours (#268).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BandNotes(
    band: Band,
    mine: StoredMedia?,
    received: List<StoredMedia>,
    /** More than one of us in this band's prose — see [bandAccent]. */
    crossed: Boolean,
    /** The night's own facts. Rendered, never stored — see [preamble]. */
    preamble: String,
    senderName: (String) -> String?,
    editable: Boolean,
    onWrite: (String) -> Unit,
    onVerdict: (String?) -> Unit,
    onLift: (String) -> Unit,
) {
    var editing by remember(mine?.id, band) { mutableStateOf(false) }
    var draft by remember(mine?.id, band) { mutableStateOf(mine?.text.orEmpty()) }
    var expanded by remember(mine?.id) { mutableStateOf(false) }
    // The tap that opens the field is the tap that means "I am writing now" — asking
    // for a second one to raise the keyboard is the whole cost of capture doubled, on
    // the surface ADR-0012 says has to be one-handed and cheap.
    val focus = remember { FocusRequester() }
    LaunchedEffect(editing) { if (editing) focus.requestFocus() }

    // A **Contact** looking at a night nobody wrote about gets no frame around the
    // nothing. The write-line is what the empty frame is *for*, and there isn't one.
    if (!editable && mine == null && received.isEmpty()) return

    val accent = bandAccent(band, crossed)
    // One frame for the whole band, thickening while it is being written in — the
    // same language the strips use, and for the same reason: a second outline around
    // the field inside this one is two boundaries drawn for one boundary. It was
    // Amber besides, which on a shared note is the colour of the other answer (#268).
    Column(
        Modifier
            .padding(start = 20.dp, end = 20.dp, top = 6.dp)
            .border(if (editing) 2.dp else 1.dp, accent, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        // Always first, whether it opens the field or reopens it over what is
        // already there. Everything written lands underneath.
        if (editable && !editing) {
            Text(
                when {
                    mine != null -> "Edit"
                    band == Band.SHARED -> "Write something to share"
                    else -> "Write something just for you"
                },
                color = if (mine != null) accent else Faint,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { draft = mine?.text.orEmpty(); editing = true }
                    .padding(vertical = 6.dp),
            )
        }
        when {
            editing -> {
                // One field, no toolbar. The phone is the wrong surface for long form
                // (ADR-0012) and the answer is to keep the room visible around it,
                // not to grow an editor.
                //
                // Tall enough to invite several sentences, though: a one-line box asks
                // for a caption, and the thing being asked for is what the night was
                // like. The floor is the invitation; the field grows past it as it
                // fills, and nothing truncates what gets typed.
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    textStyle = LocalTextStyle.current.copy(color = Ink, fontSize = 13.sp),
                    cursorBrush = SolidColor(Amber),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 108.dp)
                        .clip(RoundedCornerShape(6.dp))
                        // No border of its own: the band's frame is the boundary, and
                        // the darker ground is enough to say "type here".
                        .background(UnlitField)
                        .padding(10.dp)
                        .focusRequester(focus),
                )
                Row(Modifier.padding(top = 6.dp)) {
                    Text(
                        "done",
                        color = Amber,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clickable { onWrite(draft); editing = false }
                            .padding(vertical = 4.dp, horizontal = 2.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        "discard",
                        color = Faint,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clickable { draft = mine?.text.orEmpty(); editing = false }
                            .padding(vertical = 4.dp, horizontal = 2.dp),
                    )
                }
            }

            mine != null -> {
                if (preamble.isNotEmpty()) {
                    // Not editable, and drawn apart from the typed text: nothing
                    // generated may be mistaken for something I said.
                    Text(preamble, color = Faint, fontSize = 11.sp)
                    Spacer(Modifier.height(3.dp))
                }
                Text(
                    mine.text,
                    color = Ink,
                    fontSize = 13.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        // A long-press lifts the note into the other band. The same
                        // act as dragging a photograph across, minus the index —
                        // one note per band means there is no position to choose.
                        .combinedClickable(
                            onClick = { expanded = !expanded },
                            onLongClick = { if (editable) onLift(mine.id) },
                        ),
                )
                // Editing is the line above now, so this row is the verdict alone.
                if (editable) {
                    Box(Modifier.padding(top = 5.dp)) {
                        VerdictThumbs(mine.verdict, onVerdict)
                    }
                }
            }
        }

        received.forEach { note ->
            Spacer(Modifier.height(8.dp))
            Text(
                // A name where the key resolves to one, and never an invented name:
                // the same degradation the green promise makes.
                senderName(note.from.orEmpty()) ?: "Someone else",
                color = Slate,
                fontSize = 11.sp,
            )
            Text(note.text, color = Slate, fontSize = 13.sp)
            if (note.verdict != null) {
                Spacer(Modifier.height(2.dp))
                Text(verdictGlyph(note.verdict), color = Slate, fontSize = 13.sp)
            }
        }
    }
}

/**
 * Down, up, up twice — and unset, which is reachable by tapping the one that is set.
 *
 * Choosing one takes the others away: the row is a question while it is open and an
 * answer once it is closed, and three glyphs left standing beside the chosen one read
 * as three unmade choices (#268). Tapping what is left reopens the question.
 */
@Composable
private fun VerdictThumbs(current: String?, onVerdict: (String?) -> Unit) {
    Row {
        listOf(
            StoredMedia.Verdict.DOWN,
            StoredMedia.Verdict.UP,
            StoredMedia.Verdict.DOUBLE_UP,
        ).filter { current == null || current == it }.forEach { v ->
            val selected = current == v
            Text(
                verdictGlyph(v),
                color = if (selected) Amber else Faint,
                fontSize = 15.sp,
                modifier = Modifier
                    .clickable { onVerdict(if (selected) null else v) }
                    .semantics {
                        contentDescription = verdictLabel(v)
                        this.selected = selected
                        role = Role.Button
                    }
                    .padding(end = 10.dp, top = 2.dp, bottom = 2.dp),
            )
        }
    }
}

internal fun verdictGlyph(verdict: String?): String = when (verdict) {
    StoredMedia.Verdict.DOWN -> "👎"
    StoredMedia.Verdict.UP -> "👍"
    StoredMedia.Verdict.DOUBLE_UP -> "👍👍"
    else -> ""
}

private fun verdictLabel(verdict: String?): String = when (verdict) {
    StoredMedia.Verdict.DOWN -> "Rate down"
    StoredMedia.Verdict.UP -> "Rate up"
    StoredMedia.Verdict.DOUBLE_UP -> "Rate up twice"
    else -> "Rate"
}

/**
 * Where the photograph will land, opened as a real gap in the row.
 *
 * In the band's own colour, not Amber: it appears mid-drag, and a drag *upward* that
 * lights amber is saying "private" about the thing you are about to share (#268).
 */
@Composable
private fun LandingSlot(accent: Color, wash: Color) {
    Box(
        Modifier
            .size(GigPhotoSize)
            .clip(RoundedCornerShape(10.dp))
            .background(wash)
            .border(1.dp, accent, RoundedCornerShape(10.dp)),
    )
    Spacer(Modifier.width(ItemGap))
}

/**
 * One photograph. **Amber** if it is mine, the cooler light if it was given to me.
 *
 * Carries no drag gesture of its own — the strip owns that (see [MediaBand]). Tap and
 * long-press only, and only while not arranging, so a press that begins a drag is not
 * competing with a click.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaTile(
    item: StoredMedia,
    arranging: Boolean,
    loadPreview: suspend (Uri) -> MediaThumb,
    onOpen: (Uri) -> Unit,
    onRemove: (StoredMedia) -> Unit,
    onArrange: () -> Unit,
) {
    val uri = remember(item.ref) { Uri.parse(item.ref) }

    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .border(
                1.5.dp,
                if (item.from == null) Amber else Slate,
                RoundedCornerShape(10.dp),
            )
            .then(
                if (!arranging) {
                    Modifier.combinedClickable(
                        onClick = { onOpen(uri) },
                        onLongClick = onArrange,
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        PhotoThumb(uri, size = GigPhotoSize, loadPreview = loadPreview)
        if (arranging) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    // The visible chip stays 20dp, but the tap target itself is
                    // padded out to the 48dp minimum so it's actually reachable.
                    .minimumInteractiveComponentSize()
                    .clickable { onRemove(item) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Danger),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Remove",
                        tint = Color.White,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
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
    //
    // The claim decides this, not `gigPlanned` membership (#127) — see [isPlanned].
    val planned = setlist != null && isPlanned(state.attendanceByGig[setlist.id]?.provenance)
    // Read off state rather than asked of the view model, so checking in redraws
    // this screen instead of leaving the button sitting there.
    val checkedIn = setlist != null &&
        state.attendanceByGig[setlist.id]?.provenance == StoredAttendance.Provenance.CHECKED_IN
    // What this night already became. Every one of them: each url may be in
    // somebody's hands, so none of them stops being reachable from here.
    val made = setlist?.let { state.playlistsBySetlist[it.id] }.orEmpty()
    val heldMedia = setlist?.let { state.mediaBySetlist[it.id] }.orEmpty()
    // Under the contact light the room holds what a Contact can see, through the one
    // rule that also builds their manifest (#145). Withheld items never come back as
    // content here — only as a count, and only when asked for.
    val gigMedia = if (state.contactLight) visibleToContacts(heldMedia) else heldMedia
    val withheld = if (state.contactLight) withheldFromContacts(heldMedia) else emptyList()
    // A **Note** has no bytes and an empty [StoredMedia.ref], so every path that
    // resolves a reference has to be handed the visual run instead of the night.
    // Split once, here, rather than guarded at each of the six call sites below.
    val gigVisuals = gigMedia.filterNot { it.kind == StoredMedia.Kind.NOTE }
    val gigPhotos = gigVisuals.map { Uri.parse(it.ref) }
    // The night's own facts, for the **Preamble** over a **Note** (#50). Derived on
    // every composition and never stored: **Reconcile** has no time bound, so who the
    // record knows was here changes, and a frozen sentence would be the app putting
    // words in my mouth about an evening it has since learned more about.
    val alsoThere = setlist?.let { s ->
        state.friends.filter { f ->
            f.setlistfm.isNotBlank() && state.showsByFriend[f.setlistfm].orEmpty().any { it.id == s.id }
        }.map { it.name }
    }.orEmpty()
    val gigPreamble = preamble(
        people = alsoThere,
        venue = setlist?.venue?.name,
        songCount = setlist?.performed()?.size ?: 0,
    )
    // Whether this night is one of my own, through the one rule (#327). A **Contact**'s
    // night is reachable from the timeline exactly like mine — it has to be — and every
    // control that *changes* it has to ask this first, because attaching to their night
    // acquires it: the **Gig** becomes a record here and their Shared media for it
    // routes to me on the next **Reconcile**.
    val mineNight = setlist != null && isMyNight(
        setlist.id,
        state.attendanceByGig[setlist.id],
        state.setlists,
        state.plannedGigs,
    )
    // The two reasons the room is read-only, folded once. They are different questions —
    // the light previews someone else's view of *my* night, this is *their* night — and
    // either one is enough.
    val editable = mineNight && !state.contactLight
    // Arranging belongs to the Room, not to the strip: that is the whole of "a tap
    // anywhere that is not an [x] leaves it".
    var arranging by remember(setlist?.id) { mutableStateOf(false) }
    // Which band the handle was released over, held across the picker's round trip —
    // the answer is given by the gesture and the picker cannot carry it.
    var attachTo by remember { mutableStateOf(Band.VAULT) }
    // The Reliver picks straight from the system photo (and video) picker — no
    // gallery permission needed for that path, unlike the suggestions below.
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> if (uris.isNotEmpty()) setlist?.let { viewModel.addPickedGigPhotos(it.id, uris, attachTo) } }
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
    val recordingMedia = gigVisuals.firstOrNull { it.kind == StoredMedia.Kind.VIDEO }
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
    // What there is to convert: setlist.fm's songs, or a **Log** I said was complete.
    // A night I checked into never reaches the convert branch below — `canLog` claims
    // the bottom bar first — so this has to be offered there too, or the one night the
    // app itself is the record of is the one night that cannot become a playlist.
    val convertible = setlist != null &&
        (setlist.performed().isNotEmpty() || (log.closed && log.named().isNotEmpty()))
    // The Act this night was minted from, when it came off a Bill: it carries the
    // candidate pool and — the part that matters — which artist that pool came from.
    val act = setlist?.let { viewModel.actFor(it.id) }
    val localGig = setlist != null && setlist.isLocal()
    val canLog = setlist != null && (checkedIn || localGig)
    // Whose catalogue to offer when correcting an entry: the night's own setlist.fm
    // record first, the **Bill** **Act** behind it second. Hoisted above the Log
    // editor because the pull-to-refresh curtain (below) needs the same answer.
    val catalogueArtist = setlist?.artist?.mbid?.ifBlank { null } ?: act?.mbid
    val catalogue = catalogueArtist?.let { state.catalogueByArtist[it] }.orEmpty()
    val catalogueLoading = catalogueArtist != null && state.catalogueFetching == catalogueArtist
    // Which of my **Log**'s entries has its correction panel open, if any. One at a
    // time: this is a room you are standing in, not a list of forms. It lives here
    // rather than in the editor because the entries themselves are on the spine now.
    var correctingLog by remember(setlist?.id) { mutableStateOf<Int?>(null) }
    // The state of this **Gig**, as known — one value, decided once (#129). Everything
    // on this screen is a rendering of this link's state, and before this each part
    // worked it out again from a different subset and they disagreed.
    val gigAsKnown = GigAsKnown(
        window = setlist?.localDate()?.let { nightWindow(it) },
        provenance = if (checkedIn) StoredAttendance.Provenance.CHECKED_IN else null,
        // An editor nobody has typed in is not a **Log**. `log` above defaults to an
        // empty one so there is always something to render; the decision needs the
        // difference between "never started" and "started and still open".
        log = log.takeIf { it.songs.isNotEmpty() || it.closed },
        setlistId = setlist?.id?.takeUnless { localGig },
        songCount = setlist?.performed()?.size ?: 0,
        calendarEvent = calendarEventUri,
    )
    // The phase and the curtain come off the same value as the offers, so they cannot
    // disagree. The alcove is still not dispatched from — the swipe's action order is
    // a separate, deliberately deferred change (#129).
    val offers = gigOffers(gigAsKnown, LocalDateTime.now())
    val leaf = offers.phase
    // What pulling the curtain down asks for, decided by the same fold that draws the
    // chip — never the same request on a night three weeks away, a night being stood
    // at, and a night from 1992. The dispatch itself (`curtainAction`) is pure and
    // tested; only the plumbing it names lives here.
    val onPullToRefresh: () -> Unit = {
        when (curtainAction(offers.curtain)) {
            CurtainAction.FETCH_CATALOGUE -> catalogueArtist?.let(viewModel::fetchCatalogue)
            CurtainAction.FETCH_SETLIST -> viewModel.refreshSelectedSetlist()
            CurtainAction.NONE -> {}
        }
    }
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
                            // "above" was true when the editor sat over the set. The
                            // entries are the set now and the way in is under it (#268).
                            GigLeaf.CAPTURE -> "noting the set — add what they play below"
                            else -> "your log · add anything you remember below"
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
                    // A set I said was complete is a set, so it converts. Offered here
                    // rather than only in the branch below, which a checked-in night
                    // never reaches.
                    if (convertible) {
                        Text(
                            "make a playlist of this set",
                            color = Slate,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clickable { viewModel.selectSetlist(setlist); onConvert() }
                                .padding(vertical = 6.dp),
                        )
                    }
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
        // One list, not two (#268). setlist.fm's record and my **Log** are two
        // descriptions of the same night, and printing them one under the other made
        // the reader do the alignment in their head. Woven, a song both hold is a
        // single line that says so — and neither record is changed by the other,
        // which is still the rule: this decides reading order and nothing else.
        val woven = remember(rows, log.songs) {
            weaveSetlist(rows.map { (it as? EventRow.SongItem)?.song?.name }, log.songs)
        }
        val canConvert = convertible
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
            isRefreshing = state.setlistsLoading ||
                (catalogueArtist != null && state.catalogueFetching == catalogueArtist),
            onRefresh = onPullToRefresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    // No imePadding here, deliberately: the Scaffold already insets for
                    // the keyboard, and a second one shrinks the viewport past where
                    // the list draws — the note field kept its layout and lost its
                    // bottom border, its "done" and the vault's write-line under it.
                    // Arranging is the Room's mode, so the whole Room dismisses it —
                    // every tap that is not an [x] on a thumbnail, not merely a tap on
                    // the strip that opened it (#162). Registered before the swipe so
                    // it never eats a horizontal gesture.
                    .pointerInput(arranging) {
                        if (arranging) detectTapGestures(onTap = { arranging = false })
                    }
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
                            // Once the night has passed the record has the last word:
                            // "no setlist yet" is a fact about what is stored, so a Gig
                            // holding fifteen songs cannot print it and one holding none
                            // keeps printing it (#127).
                            EventTag(
                                gigStatus(planned, setlist.localDate(), setlist.performed().size),
                                color = if (planned) Slate else Muted,
                            )
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
                            // The review, where the sharing decision is actually made:
                            // one night at a time (#145). At the timeline the lit and
                            // unlit versions look almost identical; the difference is
                            // visible here, which is the right place for it.
                            if (state.contactLight) {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    when {
                                        gigMedia.isEmpty() && withheld.isEmpty() ->
                                            "Nothing to see on this night. They see that you were here."
                                        gigMedia.isEmpty() ->
                                            "They see none of the ${withheld.size} here. They see that you were here."
                                        else ->
                                            "They see ${gigMedia.size} of ${gigMedia.size + withheld.size} here."
                                    },
                                    color = Muted,
                                    fontSize = 12.sp,
                                )
                                if (withheld.isNotEmpty()) {
                                    Text(
                                        if (state.showWithheld) "hide what you are keeping back"
                                        else "show the ${withheld.size} you are keeping back",
                                        color = Slate,
                                        fontSize = 12.sp,
                                        modifier = Modifier
                                            .clickable { viewModel.setShowWithheld(!state.showWithheld) }
                                            .padding(vertical = 8.dp),
                                    )
                                }
                                // Placeholders, never content: the question this answers
                                // is "how much am I keeping back", and re-rendering the
                                // photographs would answer a different one.
                                if (state.showWithheld) {
                                    Row(Modifier.padding(bottom = 6.dp)) {
                                        withheld.forEach { _ ->
                                            Box(
                                                Modifier
                                                    .padding(end = 6.dp)
                                                    .size(44.dp)
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(UnlitField)
                                                    .border(1.dp, LineCol, RoundedCornerShape(6.dp)),
                                            )
                                        }
                                    }
                                }
                                // Stopping is a drag down into the vault, one photograph
                                // at a time (#162), so there is no button here — and
                                // there must not be one: nothing retrieves what already
                                // left, and no control may look as though it does.
                            }
                            Spacer(Modifier.height(12.dp))
                            GigMediaBands(
                                media = gigMedia,
                                loadPreview = viewModel::photoPreview,
                                // Remove and the drag both hang off arrange mode, so
                                // withholding it is the whole of gating them.
                                arranging = arranging && editable,
                                // The light shows what they see, so the vault band and
                                // the handle are absent under it rather than drawn over
                                // a filtered list they could only misreport.
                                contactLight = state.contactLight,
                                editable = editable,
                                // A sender is a public key (#28) and a Contact's name
                                // lives on the friends list under a setlist.fm handle.
                                // Nothing joins the two yet, so the promise degrades to
                                // "someone else" rather than inventing a name.
                                senderName = { key -> state.friends.firstOrNull { it.setlistfm == key }?.name },
                                onArrange = { arranging = true },
                                onAdd = { band ->
                                    attachTo = band
                                    photoPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                                    )
                                },
                                // Opens in the in-app viewer below rather than handing the uri to
                                // whatever app the phone picks: an external app can fail to read
                                // it (permission scoped to us, or the phone's own quirks) and
                                // leave the user staring at a viewer with nothing in it.
                                onOpen = { uri -> viewerUri = uri },
                                onRemove = { item -> viewModel.removeGigPhoto(setlist.id, Uri.parse(item.ref)) },
                                onMove = { id, band, index -> viewModel.moveGigMedia(setlist.id, id, band, index) },
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
                                // A suggestion has no gesture behind it, so it takes the
                                // safe band. Moving it up is one drag.
                                onAdd = { uri -> viewModel.addGigPhotos(setlist.id, listOf(uri), Band.VAULT) },
                            )
                        }
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
                itemsIndexed(woven) { _, line ->
                    // Mine is an index into the **Log**, and the × and the correction
                    // panel act on it there — the published row beside it is never
                    // touched by either.
                    val logAt = line.logged?.takeIf { canLog }
                    val remembered = line.logged?.let { log.rememberedAt(it) }
                    val remove = logAt?.let { j ->
                        { correctingLog = null; viewModel.removeFromLog(setlist.id, j) }
                    }
                    when (val row = line.published?.let { rows[it] }) {
                        is EventRow.Encore -> EncoreLabel()
                        is EventRow.SongItem -> {
                            val at = offsets.getOrElse(songIndexByRow[line.published!!]) { NOT_STAMPED }
                            SongRow(
                                number = row.number,
                                song = row.song,
                                offsetMs = at,
                                mine = line.both,
                                remembered = remembered,
                                onRemoveLog = remove,
                                // Only a stamped song knows where it is in the recording;
                                // the rest are inert until someone marks them.
                                onClick = if (at > NOT_STAMPED && recording != null) {
                                    { viewerStartMs = at; viewerUri = recording }
                                } else null,
                            )
                        }
                        // Only mine. A **Gap** offers no correction: "one I couldn't
                        // name" is an acknowledged fact, not an invitation to guess.
                        null -> {
                            val j = line.logged!!
                            val title = log.songs[j]
                            LoggedRow(
                                title = title,
                                // Only when nothing was published: then my Log is the
                                // record of this night and its order is the set's.
                                number = (j + 1).takeIf { rows.isEmpty() },
                                remembered = remembered,
                                onCorrect = if (canLog && title.isNotBlank()) {
                                    { correctingLog = if (correctingLog == j) null else j }
                                } else null,
                                onRemove = remove,
                            )
                            if (correctingLog == j) {
                                LaunchedEffect(j) { catalogueArtist?.let(viewModel::fetchCatalogue) }
                                val written = log.rememberedAt(j) ?: title
                                CorrectEntry(
                                    written = written,
                                    // Both sources, played first and recorded after,
                                    // ranked as one list. A song they played tonight
                                    // and have recorded appears once.
                                    candidates = rankTitles(
                                        written,
                                        (act?.candidates.orEmpty() + catalogue).distinctBy { it.lowercase() },
                                    ),
                                    canRestore = log.rememberedAt(j) != null,
                                    loading = catalogueLoading,
                                    onPick = {
                                        correctingLog = null
                                        viewModel.correctLogEntry(setlist.id, j, it)
                                    },
                                    onRestore = {
                                        correctingLog = null
                                        viewModel.restoreLogEntry(setlist.id, j)
                                    },
                                )
                            }
                        }
                    }
                }
                // My own Log, and it is never taken away. A partial capture you can no
                // longer correct from inside the app is the exact trap this feature is
                // built to avoid, so this renders on a night's page forever after.
                //
                // **Under the set, not above it.** The entries themselves are on the
                // spine now (#268), so what is left here is the way in — what to add
                // and whether the set is complete — and a way in belongs below the
                // thing it adds to. It also puts the field next to the end of the
                // list, which is where a song lands when you tap it in.
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
                            onAdd = { viewModel.addToLog(setlist.id, it) },
                            onClosed = { viewModel.setLogClosed(setlist.id, it) },
                            onDisambiguate = { viewModel.disambiguateAct(setlist.id, it) },
                            searching = state.billFetching != null,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }
                // Last of the night's own material, and after the set on purpose: the
                // sentence is written once the songs have been read back, which is
                // what "analysis happens after the show" means as a layout. Still
                // above the **Alcove**, which is the room's fixture rather than the
                // night's record.
                item {
                    Spacer(Modifier.height(14.dp))
                    GigNotes(
                        media = gigMedia,
                        preamble = gigPreamble,
                        senderName = { key -> state.friends.firstOrNull { it.setlistfm == key }?.name },
                        contactLight = state.contactLight,
                        editable = editable,
                        onWrite = { band, text -> viewModel.setGigNote(setlist.id, band, text) },
                        onVerdict = { id, v -> viewModel.setGigVerdict(setlist.id, id, v) },
                        onMove = { id, band, index -> viewModel.moveGigMedia(setlist.id, id, band, index) },
                    )
                }
                item { Spacer(Modifier.height(96.dp)) }
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

/**
 * One song on the night's spine.
 *
 * [mine] is the overlap: this song is in setlist.fm's record *and* in my **Log**, and
 * the two records agreeing is the strongest thing a line here can say. It is drawn as
 * the ring going **Amber** — mine, the same as everywhere else — rather than as a
 * second copy of the song further down the screen (#268).
 */
@Composable
private fun SongRow(
    number: Int?,
    song: FmSong,
    offsetMs: Long = NOT_STAMPED,
    mine: Boolean = false,
    /** The words I wrote before a title replaced them, where there were any. */
    remembered: String? = null,
    /** Drops my **Log** entry, leaving setlist.fm's row where it was. */
    onRemoveLog: (() -> Unit)? = null,
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
                    // The page's own colour, not [Raised]: the line has to pass
                    // *underneath* the number, and a lighter disc reads as the line
                    // showing through it (#268).
                    .background(Ground)
                    .border(1.5.dp, if (mine) Amber else LineLit, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (number != null) Text(
                    number.toString(),
                    color = if (mine) Amber else Faint,
                    fontSize = 10.sp,
                    // Default font padding pads above the ascent, so a centred digit
                    // sits high in a circle this small. Dropping it is not enough on
                    // its own — the line box still carries the font's leading, and
                    // pinning lineHeight to the glyph size only moved the baseline.
                    // Trim both ends and centre what is left, which is the one
                    // arrangement where Center means the digit's centre (#268).
                    lineHeight = 10.sp,
                    textAlign = TextAlign.Center,
                    style = LocalTextStyle.current.copy(
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle = LineHeightStyle(
                            alignment = LineHeightStyle.Alignment.Center,
                            trim = LineHeightStyle.Trim.Both,
                        ),
                    ),
                )
            }
        }
        Column(Modifier.weight(1f).padding(top = 1.dp, bottom = 15.dp)) {
            Text(song.name, color = if (number == null) Muted else Ink, fontSize = 15.sp)
            val note = cover?.let { "$it cover" } ?: "tape".takeIf { song.tape }
            if (note != null) Text(note, color = Faint, fontSize = 11.sp)
            // What I wrote in the dark, under the title the record settled on. Kept
            // for the reason it is always kept: it is often *the* memory (#126).
            if (remembered != null) Text("\"$remembered\"", color = Faint, fontSize = 12.sp)
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
        if (onRemoveLog != null) RemoveLogEntry(onRemoveLog)
    }
}

/**
 * The × that takes one entry out of my **Log**.
 *
 * It never touches setlist.fm's row — on a line both records hold, removing mine
 * leaves the published song exactly where it was and only puts the ring out.
 */
@Composable
private fun RemoveLogEntry(onRemove: () -> Unit) {
    Text(
        "×",
        color = Faint,
        fontSize = 20.sp,
        modifier = Modifier
            .clickable(onClick = onRemove)
            .semantics { contentDescription = "Remove" }
            .padding(horizontal = 10.dp),
    )
}

/**
 * A song only my **Log** has: I wrote it down and setlist.fm's record does not hold it
 * — either because nobody has published it or because nobody else caught it (#268).
 *
 * **A number is a position in a record.** Where setlist.fm has a set, the numbers are
 * its numbers and mine gets a bare dot instead — the same mark a tape track gets, and
 * for the same reason: it happened, it is on the line, and it is not one of the
 * numbered songs. Where there is no published set my **Log** *is* the record of the
 * night, so [number] is its own position and the running order reads back.
 */
@Composable
private fun LoggedRow(
    title: String,
    number: Int?,
    remembered: String?,
    onCorrect: (() -> Unit)?,
    onRemove: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .then(if (onCorrect != null) Modifier.clickable(onClick = onCorrect) else Modifier)
            .padding(end = 20.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(50.dp).fillMaxHeight()) {
            Box(Modifier.align(Alignment.TopCenter).width(2.dp).fillMaxHeight().background(LineCol))
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = if (number == null) 7.dp else 2.dp)
                    .size(if (number == null) 8.dp else 18.dp)
                    .clip(CircleShape)
                    .background(if (number == null && title.isNotBlank()) Amber else Ground)
                    .border(1.5.dp, Amber, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (number != null) Text(
                    number.toString(),
                    color = Amber,
                    fontSize = 10.sp,
                    lineHeight = 10.sp,
                    textAlign = TextAlign.Center,
                    // The same trimming SongRow needs, and for the same reason (#268).
                    style = LocalTextStyle.current.copy(
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle = LineHeightStyle(
                            alignment = LineHeightStyle.Alignment.Center,
                            trim = LineHeightStyle.Trim.Both,
                        ),
                    ),
                )
            }
        }
        Column(Modifier.weight(1f).padding(top = 1.dp, bottom = 15.dp)) {
            // A **Gap** is a song that was played and could not be named. It is in the
            // record on purpose: an acknowledged hole is a true fact, and the same
            // song silently absent is the record lying about what it knows.
            Text(
                title.ifBlank { "— one I couldn't name —" },
                color = if (title.isBlank()) Faint else Ink,
                fontSize = 15.sp,
            )
            if (remembered != null) Text("\"$remembered\"", color = Faint, fontSize = 12.sp)
        }
        if (onRemove != null) RemoveLogEntry(onRemove)
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
