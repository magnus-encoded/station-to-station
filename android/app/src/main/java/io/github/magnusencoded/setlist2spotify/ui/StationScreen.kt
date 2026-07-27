package io.github.magnusencoded.setlist2spotify.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.magnusencoded.setlist2spotify.AppViewModel
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmSetlist
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmSong

// Station to Station — the timeline face of the app (working title), running on
// real setlist.fm import. Enter your setlist.fm username, the app pulls your
// attended shows onto the timeline; tap one to see its real setlist; convert it
// to a Spotify playlist through the existing flow.
// ponytail: photos-from-the-night on the nodes and an in-UI Spotify-connect are
// the next pieces — the convert button still hands off to the existing confirm
// screen for login + matching.

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
private val Danger = Color(0xFFE08A8A)

private val Serif = FontFamily.Serif

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationTimelineScreen(
    viewModel: AppViewModel,
    onOpenEvent: () -> Unit,
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
                    CircularProgressIndicator(
                        color = Amber,
                        modifier = Modifier.align(Alignment.Center),
                    )

                state.setlists.isEmpty() ->
                    ImportPrompt(viewModel, state.mySetlistFmUser, state.setlistFmReady, state.error)

                else -> {
                    val earliest = state.setlists.mapNotNull { it.year()?.toIntOrNull() }.minOrNull()
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
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(state.setlists, key = { it.id }) { setlist ->
                                TimelineItem(
                                    setlist = setlist,
                                    highlight = setlist.id == state.setlists.first().id,
                                    onClick = {
                                        viewModel.openShow(setlist)
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
}

@Composable
private fun ImportPrompt(
    viewModel: AppViewModel,
    initialUser: String,
    setlistFmReady: Boolean,
    error: String?,
) {
    var username by remember { mutableStateOf(initialUser) }
    var apiKey by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        // The one lit node on an empty spine.
        Box(
            Modifier.size(60.dp).clip(CircleShape).background(Raised).border(1.5.dp, Amber, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("+", color = Amber, fontSize = 26.sp) }

        Spacer(Modifier.height(20.dp))
        Text("Bring in your shows", fontFamily = Serif, fontSize = 20.sp, color = Ink)
        Spacer(Modifier.height(6.dp))
        Text(
            "Your concert history already lives on setlist.fm. Enter your username and your line fills itself in.",
            color = Muted,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(22.dp))

        if (!setlistFmReady) {
            StationField(apiKey, { apiKey = it }, "setlist.fm API key")
            Spacer(Modifier.height(10.dp))
        }
        StationField(username, { username = it }, "setlist.fm username", imeDone = true)

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = Danger, fontSize = 12.sp)
        }

        Spacer(Modifier.height(18.dp))
        Button(
            onClick = {
                viewModel.importAttended(username.trim(), if (!setlistFmReady) apiKey else null)
            },
            enabled = username.isNotBlank() && (setlistFmReady || apiKey.isNotBlank()),
            colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color(0xFF241A06)),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Import from setlist.fm", fontWeight = FontWeight.SemiBold) }
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
private fun TimelineItem(setlist: FmSetlist, highlight: Boolean, onClick: () -> Unit) {
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
                Button(
                    onClick = {
                        viewModel.selectSetlist(setlist)
                        onConvert()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color(0xFF241A06)),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) { Text("♫  Open as a Spotify playlist", fontWeight = FontWeight.SemiBold) }
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
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
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
