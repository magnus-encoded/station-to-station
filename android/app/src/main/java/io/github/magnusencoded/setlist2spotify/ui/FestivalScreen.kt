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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.magnusencoded.setlist2spotify.AppViewModel
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmSetlist
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import java.time.temporal.ChronoUnit

private val Ground = Color(0xFF0E0B14)
private val Raised = Color(0xFF17121F)
private val Ink = Color(0xFFEDE9F2)
private val Muted = Color(0xFF8B8299)
private val Faint = Color(0xFF5A5368)
private val LineCol = Color(0xFF2E2740)
private val Slate = Color(0xFF6D7E9B)
private val Serif = FontFamily.Serif

/** A timeline is a mix of single concerts and festivals (a run of shows at one venue). */
sealed interface TimelineNode {
    data class Concert(val setlist: FmSetlist) : TimelineNode
    data class Festival(val venue: String, val shows: List<FmSetlist>) : TimelineNode
}

private const val FESTIVAL_WINDOW_DAYS = 4L

/**
 * Groups a date-ordered list of shows into festivals — two or more shows at the
 * same venue within a few days of each other — leaving lone shows as concerts.
 * setlist.fm carries no festival name, so a festival is labelled by its venue.
 */
fun groupIntoFestivals(setlists: List<FmSetlist>): List<TimelineNode> {
    val nodes = mutableListOf<TimelineNode>()
    var i = 0
    while (i < setlists.size) {
        val cluster = mutableListOf(setlists[i])
        var j = i + 1
        while (j < setlists.size && sameFestival(cluster.last(), setlists[j])) {
            cluster.add(setlists[j])
            j++
        }
        if (cluster.size >= 2) {
            nodes.add(TimelineNode.Festival(cluster.first().venue?.name ?: "Festival", cluster))
        } else {
            nodes.add(TimelineNode.Concert(cluster.first()))
        }
        i = j
    }
    return nodes
}

/** Two adjacent shows belong together when they share a venue and fall within the window. */
private fun sameFestival(a: FmSetlist, b: FmSetlist): Boolean {
    val venueA = a.venue?.name ?: return false
    val venueB = b.venue?.name ?: return false
    if (!venueA.equals(venueB, ignoreCase = true)) return false
    val da = a.localDate() ?: return false
    val db = b.localDate() ?: return false
    return abs(ChronoUnit.DAYS.between(da, db)) <= FESTIVAL_WINDOW_DAYS
}

private fun festivalDateRange(shows: List<FmSetlist>): String {
    val dates = shows.mapNotNull { it.localDate() }.sorted()
    if (dates.isEmpty()) return ""
    val full = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    val a = dates.first()
    val b = dates.last()
    return if (a == b) a.format(full)
    else "${a.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))} – ${b.format(full)}"
}

/** A clustered node on the timeline: a venue that hosted several shows over a few days. */
@Composable
fun FestivalItem(festival: TimelineNode.Festival, highlight: Boolean, onClick: () -> Unit) {
    val accent = if (highlight) Color(0xFFE7B24C) else Slate
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(onClick = onClick),
    ) {
        Box(Modifier.width(52.dp).fillMaxHeight()) {
            Box(Modifier.align(Alignment.TopCenter).width(2.dp).fillMaxHeight().background(LineCol))
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 4.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Raised)
                    .border(2.dp, accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("${festival.shows.size}", color = accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold) }
        }
        Column(Modifier.padding(end = 18.dp, bottom = 22.dp)) {
            Text("FESTIVAL", color = Slate, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(3.dp))
            Text(festival.venue, fontFamily = Serif, fontSize = 17.sp, color = Ink)
            Spacer(Modifier.height(2.dp))
            Text(festivalDateRange(festival.shows), color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(7.dp))
            Text("${festival.shows.size} shows", color = Faint, fontSize = 12.sp)
        }
    }
}

/** The concerts inside a festival, opened by tapping its node. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FestivalScreen(viewModel: AppViewModel, onBack: () -> Unit, onOpenEvent: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = Ground,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ground, titleContentColor = Ink),
                title = { Text(state.festivalTitle, fontFamily = Serif, fontSize = 18.sp, color = Ink) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Faint)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Text(
                    "${state.festivalShows.size} shows · ${festivalDateRange(state.festivalShows)}",
                    color = Faint,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 20.dp, top = 6.dp, bottom = 14.dp),
                )
            }
            items(state.festivalShows, key = { it.id }) { setlist ->
                TimelineItem(
                    setlist = setlist,
                    highlight = false,
                    onClick = {
                        viewModel.openShow(setlist)
                        onOpenEvent()
                    },
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
