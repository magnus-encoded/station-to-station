package io.github.magnusencoded.setlist2spotify.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.magnusencoded.setlist2spotify.AppViewModel
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmSetlist
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmSong
import kotlinx.coroutines.launch

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
    onOpenFestival: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenConnect: () -> Unit,
    onOpenNearby: () -> Unit,
    onZoomOut: () -> Unit,
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
            when {
                state.setlistsLoading && state.setlists.isEmpty() ->
                    CircularProgressIndicator(color = Amber, modifier = Modifier.align(Alignment.Center))

                state.setlists.isEmpty() -> EmptyTimeline(onAdd = onOpenImport)

                else -> {
                    val earliest = state.setlists.mapNotNull { it.year()?.toIntOrNull() }.minOrNull()
                    val listState = rememberLazyListState()
                    // Descending toward the past pulls the next page in before you hit
                    // the bottom, so history keeps flowing without a button.
                    val nearPast by remember {
                        derivedStateOf {
                            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                            last >= state.setlists.size - 3
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
                        PlanningPull(progress = { pull.value / pullMax }, heightPx = { pull.value })
                        val nodes = remember(state.setlists) { groupIntoFestivals(state.setlists) }
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
                                // Pinch to zoom out one resolution: your single timeline →
                                // the many-timelines view where friends' lines cross yours.
                                .pointerInput(Unit) { detectPinch(onZoomOut = onZoomOut) },
                        ) {
                            // The future edge: scroll up toward what's ahead.
                            item { FuturePrompt() }
                            items(
                                nodes,
                                key = {
                                    when (it) {
                                        is TimelineNode.Concert -> it.setlist.id
                                        is TimelineNode.Festival -> "fest-${it.shows.first().id}"
                                    }
                                },
                            ) { node ->
                                val isFirst = node == nodes.first()
                                when (node) {
                                    is TimelineNode.Concert -> TimelineItem(
                                        setlist = node.setlist,
                                        highlight = isFirst,
                                        onClick = {
                                            viewModel.openShow(node.setlist)
                                            onOpenEvent()
                                        },
                                    )

                                    is TimelineNode.Festival -> FestivalItem(
                                        festival = node,
                                        highlight = isFirst,
                                        onClick = {
                                            viewModel.openFestival(node.name, node.shows)
                                            onOpenFestival()
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
internal fun TimelineItem(setlist: FmSetlist, highlight: Boolean, onClick: () -> Unit) {
    val songCount = setlist.songs().size
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(onClick = onClick),
    ) {
        Box(Modifier.width(52.dp).fillMaxHeight()) {
            Box(Modifier.align(Alignment.TopCenter).width(2.dp).fillMaxHeight().background(LineCol))
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 6.dp)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(if (highlight) AmberSoft else Raised)
                    .border(2.dp, if (highlight) Amber else LineLit, CircleShape),
            )
        }
        Column(Modifier.padding(end = 18.dp, bottom = 22.dp)) {
            Text(
                setlist.readableDate() ?: "Unknown date",
                color = Faint,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.0.sp,
            )
            Spacer(Modifier.height(3.dp))
            Text(setlist.artist?.name ?: "Unknown artist", fontFamily = Serif, fontSize = 17.sp, color = Ink)
            Spacer(Modifier.height(2.dp))
            Text(setlist.venueLine(), color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(7.dp))
            Text(
                if (songCount > 0) "$songCount songs" else "setlist not logged",
                color = Faint,
                fontSize = 12.sp,
            )
        }
    }
}

// --- Event view: a single night, its real setlist as a spine ---

private sealed interface EventRow {
    data object Encore : EventRow
    data class SongItem(val number: Int, val song: FmSong) : EventRow
}

private fun FmSetlist.eventRows(): List<EventRow> = buildList {
    var n = 0
    sets?.set.orEmpty().forEach { set ->
        if (set.encore != null) add(EventRow.Encore)
        set.song.forEach { song ->
            n++
            add(EventRow.SongItem(n, song))
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
            if (setlist != null && setlist.songs().isNotEmpty()) {
                // A quiet, tappable hint rather than a big CTA — the same action the
                // swipe fires, kept visible so it's discoverable and reachable without
                // the gesture.
                val convert = {
                    viewModel.selectSetlist(setlist)
                    onConvert()
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = convert)
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text("‹ swipe to open as a Spotify playlist", color = Amber, fontSize = 13.sp)
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
        val canConvert = setlist.songs().isNotEmpty()
        LazyColumn(
            Modifier
                .padding(padding)
                .fillMaxSize()
                // Swipe the setlist left to convert it — the "act on this level" gesture.
                .pointerInput(setlist.id, canConvert) {
                    if (!canConvert) return@pointerInput
                    val threshold = 110.dp.toPx()
                    var dragX = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dragX = 0f },
                        onDragEnd = {
                            if (dragX <= -threshold) {
                                viewModel.selectSetlist(setlist)
                                onConvert()
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
                        EventTag("${setlist.songs().size} songs")
                        setlist.tour?.name?.let {
                            Spacer(Modifier.width(6.dp))
                            EventTag(it)
                        }
                        Spacer(Modifier.width(6.dp))
                        EventTag("self-logged", color = Faint)
                    }
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
private fun SongRow(number: Int, song: FmSong) {
    val cover = song.cover?.name
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(end = 20.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(50.dp).fillMaxHeight()) {
            Box(Modifier.align(Alignment.TopCenter).width(2.dp).fillMaxHeight().background(LineCol))
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 2.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Raised)
                    .border(1.5.dp, LineLit, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text(number.toString(), color = Faint, fontSize = 10.sp) }
        }
        Column(Modifier.weight(1f).padding(top = 1.dp, bottom = 15.dp)) {
            Text(song.name, color = Ink, fontSize = 15.sp)
            if (cover != null) {
                Text("$cover cover", color = Faint, fontSize = 11.sp)
            }
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
