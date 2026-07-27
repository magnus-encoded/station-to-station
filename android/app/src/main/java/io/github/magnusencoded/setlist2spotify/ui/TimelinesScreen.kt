package io.github.magnusencoded.setlist2spotify.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.graphics.Color
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
    // The moment a comparison is loading/ready we've exchanged — go to the woven view.
    androidx.compose.runtime.LaunchedEffect(state.comparisonFriend) {
        if (state.comparisonFriend != null) onConnected()
    }

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
                    PeerRow(peer, onExchange = { viewModel.connectWithPeer(peer) })
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

// --- The zoomed-out resolution: two timelines woven onto one spine ---

/** One row of the comparison: a show, and which of the two people were there. */
private data class CompRow(val setlist: FmSetlist, val mine: Boolean, val theirs: Boolean) {
    val shared: Boolean get() = mine && theirs
}

/**
 * Merges my timeline and the friend's into one date-ordered spine (most recent
 * first), tagging each show with who attended. Co-attended shows — the same
 * setlist.fm id on both sides — become intersections.
 */
private fun weave(mine: List<FmSetlist>, theirs: List<FmSetlist>, shared: Set<String>): List<CompRow> {
    val theirIds = theirs.map { it.id }.toSet()
    val mineIds = mine.map { it.id }.toSet()
    val byId = LinkedHashMap<String, FmSetlist>()
    (mine + theirs).forEach { byId.putIfAbsent(it.id, it) }
    return byId.values
        .map { CompRow(it, mine = it.id in mineIds, theirs = it.id in theirIds || it.id in shared) }
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
    val friend = state.comparisonFriend
    val rows = androidx.compose.runtime.remember(state.setlists, state.comparisonTimeline, state.sharedShowIds) {
        weave(state.setlists, state.comparisonTimeline, state.sharedShowIds)
    }
    val sharedCount = state.sharedShowIds.size

    Scaffold(
        containerColor = Ground,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ground, titleContentColor = Ink),
                title = {
                    Text(
                        if (friend != null) "You & ${friend.name}" else "Timelines",
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
                // Pinch back in (zoom) to drop into your own single timeline.
                .pointerInput(Unit) {
                    var zoom = 1f
                    detectTransformGestures { _, _, z, _ ->
                        zoom *= z
                        if (zoom > 1.25f) { zoom = 1f; onZoomIn() }
                    }
                },
        ) {
            when {
                friend == null -> NoComparisonYet(onFindNearby)

                state.comparisonLoading -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(color = Amber, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.height(14.dp))
                    Text("Weaving ${friend.name}'s timeline into yours…", color = Faint, fontSize = 13.sp)
                }

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item { ComparisonHeader(friend, sharedCount) }
                    items(rows, key = { it.setlist.id }) { row ->
                        CompRowItem(
                            row = row,
                            friendName = friend.name,
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
private fun ComparisonHeader(friend: Friend, sharedCount: Int) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Legend(color = Amber, label = "You")
            Spacer(Modifier.width(16.dp))
            Legend(color = Slate, label = friend.name)
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

/** A show on the woven spine. Both-attended shows glow amber with a "both here" rung. */
@Composable
private fun CompRowItem(row: CompRow, friendName: String, onClick: () -> Unit) {
    val setlist = row.setlist
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(onClick = onClick),
    ) {
        // The spine, with a node whose fill says who was there.
        Box(Modifier.width(52.dp).fillMaxHeight()) {
            Box(Modifier.align(Alignment.TopCenter).width(2.dp).fillMaxHeight().background(LineCol))
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 6.dp)
                    .size(if (row.shared) 18.dp else 14.dp)
                    .clip(CircleShape)
                    .background(if (row.shared) AmberSoft else Raised)
                    .border(2.dp, if (row.shared) Amber else LineLit, CircleShape),
            )
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
                if (row.shared) {
                    Presence("You both were here", Amber)
                } else if (row.mine) {
                    Presence("You", Amber)
                } else {
                    Presence(friendName, Slate)
                }
            }
        }
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
        Text("Two timelines, side by side", fontFamily = Serif, fontSize = 20.sp, color = Ink)
        Spacer(Modifier.height(6.dp))
        Text(
            "Swap cards with someone and their concerts weave into yours, so the shows you were both at light up.",
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
