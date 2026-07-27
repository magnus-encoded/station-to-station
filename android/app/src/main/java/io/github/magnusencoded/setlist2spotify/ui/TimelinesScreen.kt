package io.github.magnusencoded.setlist2spotify.ui

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import io.github.magnusencoded.setlist2spotify.AppViewModel
import io.github.magnusencoded.setlist2spotify.data.Friend
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmSetlist

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

// --- Swipe left from the timeline: look for someone to swap timelines with ---

/**
 * The live "someone's standing next to me" connect flow. ponytail: discovery is
 * mocked (see [AppViewModel.startNearbyDiscovery]); the QR card on the People
 * screen is the transport that actually works today.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onConnected: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Kick off discovery when the screen opens; the peer surfaces after a beat.
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.startNearbyDiscovery() }

    Scaffold(
        containerColor = Ground,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ground, titleContentColor = Ink),
                title = { Text("Connect a timeline", fontFamily = Serif, fontSize = 18.sp, color = Ink) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Faint)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(20.dp))
            Text(
                "Hold your phones close. You'll swap setlist.fm ↔ Spotify cards, and each of you gets the other's timeline woven onto your own.",
                color = Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Spacer(Modifier.height(24.dp))
            Radar(active = state.discovering || state.nearbyPeers.isEmpty())
            Spacer(Modifier.height(28.dp))
            if (state.discovering && state.nearbyPeers.isEmpty()) {
                Text("Looking for people nearby…", color = Faint, fontSize = 13.sp)
            } else {
                Text(
                    "NEARBY",
                    color = Faint,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.align(Alignment.Start).padding(bottom = 8.dp),
                )
                state.nearbyPeers.forEach { peer ->
                    PeerRow(peer, onExchange = { viewModel.connectWithPeer(peer); onConnected() })
                }
            }
        }
    }
}

/** A slow pulse to say the app is listening for other phones. */
@Composable
private fun Radar(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "radar")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Restart),
        label = "pulse",
    )
    Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
        if (active) {
            Box(
                Modifier
                    .size((60 + pulse * 60).dp)
                    .alpha((1f - pulse) * 0.5f)
                    .clip(CircleShape)
                    .border(1.5.dp, Amber, CircleShape),
            )
        }
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(AmberSoft).border(1.5.dp, Amber, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("◦", color = Amber, fontSize = 20.sp) }
    }
}

@Composable
private fun PeerRow(peer: Friend, onExchange: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Raised)
            .border(1.dp, LineLit, RoundedCornerShape(12.dp))
            .clickable(onClick = onExchange)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(Raised2).border(1.5.dp, Slate, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text(peer.name.take(1).uppercase(), color = Slate, fontSize = 15.sp, fontFamily = Serif) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(peer.name, color = Ink, fontFamily = Serif, fontSize = 16.sp)
            Text("@${peer.setlistfm}", color = Muted, fontSize = 12.sp)
        }
        Text("Exchange ›", color = Amber, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

// --- The zoomed-out resolution: my line braided with everyone else's ---

/** One row of the woven view: a show, whether I was there, and which friends were. */
private data class CompRow(val setlist: FmSetlist, val mine: Boolean, val others: List<Friend>) {
    val shared: Boolean get() = mine && others.isNotEmpty()
}

/** Each rail gets its own light: mine is amber, the rest cycle through cooler ones. */
private val RailColors = listOf(Slate, Color(0xFF8A6DA0), Color(0xFF5F8E8A), Color(0xFFA07E6D))

private fun railColor(index: Int) = RailColors[index % RailColors.size]

/**
 * Merges my timeline and every known friend's into one date-ordered spine (most
 * recent first), tagging each show with who was there. Shows several of us attended
 * are where the rails merge.
 */
private fun weave(mine: List<FmSetlist>, friends: List<Friend>, theirs: Map<String, List<FmSetlist>>): List<CompRow> {
    val mineIds = mine.map { it.id }.toSet()
    val byId = LinkedHashMap<String, FmSetlist>()
    mine.forEach { byId.putIfAbsent(it.id, it) }
    theirs.values.forEach { list -> list.forEach { byId.putIfAbsent(it.id, it) } }
    return byId.values
        .map { show ->
            val others = friends.filter { f -> theirs[f.setlistfm]?.any { it.id == show.id } == true }
            CompRow(show, mine = show.id in mineIds, others = others)
        }
        .sortedByDescending { it.setlist.localDate() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultipleTimelinesScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onOpenEvent: () -> Unit,
    onFindNearby: () -> Unit,
    onZoomIn: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Every known timeline is loaded fresh when the view opens — this is "me and
    // everyone I know", not a comparison with one chosen person.
    androidx.compose.runtime.LaunchedEffect(state.friends) { viewModel.loadFriendTimelines() }
    // Newest friend gets the lane closest to mine — you've just added them, so their
    // line is the one you're looking for.
    val lanes = androidx.compose.runtime.remember(state.friends) { state.friends.reversed() }
    val rows = androidx.compose.runtime.remember(state.setlists, lanes, state.friendTimelines) {
        weave(state.setlists, lanes, state.friendTimelines)
    }
    val sharedCount = rows.count { it.shared }

    Scaffold(
        containerColor = Ground,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ground, titleContentColor = Ink),
                title = {
                    Text(
                        "Timelines",
                        fontFamily = Serif,
                        fontSize = 18.sp,
                        color = Ink,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Faint)
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
                // Pinch back in (spread) to drop into your own single timeline.
                .pointerInput(Unit) { detectPinch(onZoomIn = onZoomIn) },
        ) {
            when {
                state.friends.isEmpty() -> NoComparisonYet(onFindNearby)

                state.timelinesLoading && state.friendTimelines.isEmpty() -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(color = Amber, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.height(14.dp))
                    Text("Weaving the other timelines into yours…", color = Faint, fontSize = 13.sp)
                }

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item { ComparisonHeader(lanes, sharedCount) }
                    items(rows, key = { it.setlist.id }) { row ->
                        CompRowItem(
                            row = row,
                            friends = lanes,
                            onClick = {
                                viewModel.openShow(row.setlist)
                                onOpenEvent()
                            },
                        )
                    }
                    item {
                        Spacer(Modifier.height(24.dp))
                        Text(
                            "Pinch in to zoom back to your own timeline.",
                            color = Faint,
                            fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComparisonHeader(friends: List<Friend>, sharedCount: Int) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp)) {
        Column {
            Legend(color = Amber, label = "You")
            friends.forEachIndexed { i, friend ->
                Spacer(Modifier.height(4.dp))
                Legend(color = railColor(i), label = friend.name)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            if (sharedCount > 0) {
                "You were both at $sharedCount ${if (sharedCount == 1) "show" else "shows"} — lit up below."
            } else {
                "No shows in common yet. The nights you were both there light up here."
            },
            color = Muted,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Muted, fontSize = 12.sp)
    }
}

/**
 * A show on the woven view. One rail per person runs the whole length — mine on the
 * left, each friend to the right of it — and on a show several of us attended those
 * rails merge into one node before separating again, like a branch graph.
 */
@Composable
private fun CompRowItem(row: CompRow, friends: List<Friend>, onClick: () -> Unit) {
    val setlist = row.setlist
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(onClick = onClick),
    ) {
        Box(Modifier.width(gutterWidth(friends.size)).fillMaxHeight()) {
            Braid(row, friends)
        }
        Column(Modifier.padding(end = 18.dp, bottom = 22.dp)) {
            Text(
                setlist.readableDate() ?: "Unknown date",
                color = if (row.shared) Amber else Faint,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.0.sp,
            )
            Spacer(Modifier.height(3.dp))
            Text(setlist.artist?.name ?: "Unknown artist", fontFamily = Serif, fontSize = 17.sp, color = Ink)
            Spacer(Modifier.height(2.dp))
            Text(setlist.venueLine(), color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(7.dp))
            // Who was there — the point of the woven view.
            Row(verticalAlignment = Alignment.CenterVertically) {
                val names = row.others.joinToString(", ") { it.name }
                when {
                    row.shared -> Presence("You and $names were here", Amber)
                    row.mine -> Presence("You", Amber)
                    else -> Presence(names, Slate)
                }
            }
        }
    }
}

private val LaneStep = 20.dp
private val LaneFirst = 18.dp

/** Wide enough for my rail plus one per friend. */
private fun gutterWidth(friendCount: Int) = LaneFirst + LaneStep * friendCount + 16.dp

/**
 * The rails for one row: lane 0 is me, lane 1..n each friend, in the order they were
 * added. A rail bends into the node only if its owner was at this show and someone
 * else was too — that merge is the whole point of the view. Everyone else's rail
 * passes straight through, dimmed.
 */
@Composable
private fun Braid(row: CompRow, friends: List<Friend>) {
    Canvas(Modifier.fillMaxSize()) {
        val nodeY = 15.dp.toPx()
        val h = size.height
        val step = LaneStep.toPx()
        val first = LaneFirst.toPx()
        fun laneX(index: Int) = first + step * index

        // The merge point: the leftmost attending rail, so shared shows pull right to left.
        val attending = buildList {
            if (row.mine) add(0)
            row.others.forEach { f -> add(friends.indexOfFirst { it.setlistfm == f.setlistfm } + 1) }
        }.filter { it >= 0 }.sorted()
        val nodeX = laneX(attending.firstOrNull() ?: 0)
        val merging = attending.size > 1

        fun rail(index: Int, color: Color, here: Boolean) {
            val lane = laneX(index)
            val path = Path()
            path.moveTo(lane, 0f)
            if (merging && here && lane != nodeX) {
                val pull = nodeY * 0.6f
                path.cubicTo(lane, pull, nodeX, nodeY - pull, nodeX, nodeY)
                path.cubicTo(nodeX, nodeY + pull, lane, h - pull, lane, h)
            } else {
                path.lineTo(lane, h)
            }
            drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
        }

        rail(0, if (row.mine) Amber.copy(alpha = 0.75f) else LineCol, row.mine)
        friends.forEachIndexed { i, friend ->
            val here = row.others.any { it.setlistfm == friend.setlistfm }
            rail(i + 1, if (here) railColor(i).copy(alpha = 0.85f) else LineCol, here)
        }

        val r = if (merging) 9.dp.toPx() else 7.dp.toPx()
        val tint = if (row.mine) Amber else railColor(attending.firstOrNull()?.minus(1) ?: 0)
        drawCircle(if (merging) AmberSoft else Raised, r, Offset(nodeX, nodeY))
        drawCircle(tint, r, Offset(nodeX, nodeY), style = Stroke(width = 2.dp.toPx()))
    }
}

@Composable
private fun Presence(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Raised2)
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

/** Shown when you've zoomed out but haven't connected anyone yet. */
@Composable
private fun NoComparisonYet(onFindNearby: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row {
            Box(Modifier.width(2.dp).height(80.dp).background(LineCol))
            Spacer(Modifier.width(40.dp))
            Box(Modifier.width(2.dp).height(80.dp).background(LineCol).alpha(0.4f))
        }
        Spacer(Modifier.height(20.dp))
        Text("Your line, and everyone else's", fontFamily = Serif, fontSize = 20.sp, color = Ink)
        Spacer(Modifier.height(6.dp))
        Text(
            "Swap cards with someone and their line runs alongside yours — the nights you were at the same show merge into one.",
            color = Muted,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onFindNearby,
            colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color(0xFF241A06)),
        ) { Text("Find someone nearby", fontWeight = FontWeight.SemiBold) }
    }
}
