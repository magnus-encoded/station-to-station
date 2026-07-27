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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Station to Station — the timeline face of the app (working title).
// First pass: renders the mock with static example data so the look can be
// judged on a device. No networking yet; tapping a show opens the mock night.
// ponytail: static data + a single hardcoded event. Wire to the setlist.fm /
// media layers once the look is signed off.

// --- Nocturnal palette (a darkened room before the lights). Amber only ever
// marks a live/lit moment; the cool slate marks a future show reflected in
// from a connected source. Values mirror the HTML mock. ---
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
private val Slate = Color(0xFF6D7E9B)

private val Serif = FontFamily.Serif

/** A faux photo/clip thumbnail — a stage-lit gradient, since real media isn't wired yet. */
private data class Thumb(val top: Color, val bottom: Color, val video: Boolean = false)

private val T1 = Thumb(Color(0xFF7A2F4D), Color(0xFF2A1230))
private val T2 = Thumb(Color(0xFF8A5A1E), Color(0xFF2A1A08))
private val T3 = Thumb(Color(0xFF39406B), Color(0xFF141726))
private val T4 = Thumb(Color(0xFF6B2F5E), Color(0xFF221033))

private data class ShowNode(
    val date: String,
    val artist: String,
    val venue: String,
    val thumbs: List<Thumb> = emptyList(),
    val more: String? = null,
    val note: String? = null,
    val upcoming: Boolean = false,
    val recent: Boolean = false,
)

// Newest / what's ahead at the top, the years falling away below.
private val sampleShows = listOf(
    ShowNode(
        date = "MAR 2025 · UPCOMING",
        artist = "Godspeed You! Black Emperor",
        venue = "Sentrum Scene, Oslo",
        note = "On your line via Bandsintown",
        upcoming = true,
    ),
    ShowNode(
        date = "21 SEP 2024",
        artist = "The National",
        venue = "Oslo Spektrum",
        thumbs = listOf(T1, T3.copy(video = true), T2),
        more = "+11",
        recent = true,
    ),
    ShowNode(
        date = "14 NOV 2023",
        artist = "Fontaines D.C.",
        venue = "Rockefeller, Oslo",
        thumbs = listOf(T4, T1),
        more = "+6",
    ),
    ShowNode(
        date = "08 JUN 2022",
        artist = "Big Thief",
        venue = "Sentrum Scene, Oslo",
        thumbs = listOf(T2),
        more = "+2",
    ),
    ShowNode(
        date = "12 OCT 2019",
        artist = "Nick Cave & The Bad Seeds",
        venue = "Oslo Spektrum",
        note = "setlist only · no media",
    ),
)

private data class SongLine(val num: Int, val title: String, val thumb: Thumb? = null)

private val eventSongs = listOf(
    SongLine(1, "Once Upon a Poison Couch"),
    SongLine(2, "Don't Swallow the Cap", T1),
    SongLine(3, "Bloodbuzz Ohio"),
    SongLine(4, "The System Only Dreams in Total Darkness"),
    SongLine(5, "I Need My Girl", T3.copy(video = true)),
    SongLine(6, "Rylan"),
    SongLine(7, "Light Years"),
    SongLine(8, "Fake Empire", T2),
)
private val eventEncore = listOf(
    SongLine(9, "About Today"),
    SongLine(10, "Terrible Love", T4.copy(video = true)),
    SongLine(11, "Mr. November", T1),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationTimelineScreen(
    onOpenEvent: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    Scaffold(
        containerColor = Ground,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Ground,
                    titleContentColor = Muted,
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("◦ ", color = Amber, fontSize = 13.sp)
                        Text("Station to Station", fontFamily = Serif, fontSize = 16.sp, color = Muted)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "Search", tint = Faint)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                "34 shows · 19 artists · since 2016",
                color = Faint,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 20.dp, top = 2.dp, bottom = 14.dp),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(sampleShows) { show ->
                    TimelineItem(show, onClick = onOpenEvent)
                }
            }
        }
    }
}

@Composable
private fun TimelineItem(show: ShowNode, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clickable(enabled = !show.upcoming, onClick = onClick),
    ) {
        // Left rail: a continuous line with this show's node on it.
        Box(Modifier.width(52.dp).fillMaxHeight()) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(LineCol),
            )
            val fill = if (show.upcoming) Color.Transparent else Raised
            val ring = when {
                show.recent -> Amber
                show.upcoming -> Slate
                else -> LineLit
            }
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 6.dp)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(if (show.recent) AmberSoft else fill)
                    .border(2.dp, ring, CircleShape),
            )
        }
        Column(Modifier.padding(end = 18.dp, bottom = 22.dp)) {
            Text(
                show.date,
                color = Faint,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                show.artist,
                fontFamily = Serif,
                fontSize = 17.sp,
                color = if (show.upcoming) Slate else Ink,
            )
            Spacer(Modifier.height(2.dp))
            Text(show.venue, color = Muted, fontSize = 13.sp)

            if (show.upcoming) {
                Spacer(Modifier.height(7.dp))
                Text("↗ ${show.note}", color = Slate, fontSize = 11.sp)
            } else if (show.thumbs.isNotEmpty()) {
                Spacer(Modifier.height(9.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    show.thumbs.forEach { t ->
                        MediaThumb(t, 34.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    show.more?.let { Text(it, color = Faint, fontSize = 12.sp) }
                }
            } else if (show.note != null) {
                Spacer(Modifier.height(8.dp))
                Text(show.note, color = Faint, fontSize = 12.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationEventScreen(onBack: () -> Unit) {
    Scaffold(
        containerColor = Ground,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Ground,
                    titleContentColor = Faint,
                ),
                title = { Text("SEP 2024", color = Faint, fontSize = 12.sp, letterSpacing = 1.5.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Faint)
                    }
                },
            )
        },
        bottomBar = {
            Button(
                onClick = { /* ponytail: wire to the existing Spotify flow once the look lands */ },
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color(0xFF241A06)),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Text("♫  Open as a Spotify playlist", fontWeight = FontWeight.SemiBold)
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp)) {
                    Text("The National", fontFamily = Serif, fontSize = 28.sp, color = Ink)
                    Spacer(Modifier.height(5.dp))
                    Text("Oslo Spektrum · 21 Sep 2024", color = Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(11.dp))
                    Row {
                        EventTag("23 songs")
                        Spacer(Modifier.width(6.dp))
                        EventTag("14 photos · 2 clips")
                        Spacer(Modifier.width(6.dp))
                        EventTag("self-logged", color = Slate)
                    }
                }
            }
            items(eventSongs) { SongRow(it) }
            item { EncoreLabel() }
            items(eventEncore) { SongRow(it) }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun SongRow(song: SongLine) {
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(end = 20.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(50.dp).fillMaxHeight()) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(LineCol),
            )
            val hasMedia = song.thumb != null
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 2.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Raised)
                    .border(1.5.dp, if (hasMedia) Amber else LineLit, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    song.num.toString(),
                    color = if (hasMedia) Amber else Faint,
                    fontSize = 10.sp,
                )
            }
        }
        Text(
            song.title,
            color = Ink,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f).padding(top = 1.dp, bottom = 15.dp),
        )
        song.thumb?.let {
            Spacer(Modifier.width(10.dp))
            MediaThumb(it, 40.dp)
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

@Composable
private fun MediaThumb(thumb: Thumb, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(7.dp))
            .background(Brush.linearGradient(listOf(thumb.top, thumb.bottom))),
        contentAlignment = Alignment.Center,
    ) {
        if (thumb.video) {
            Text("▶", color = Color.White.copy(alpha = 0.9f), fontSize = 10.sp)
        }
    }
}
