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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.magnusencoded.setlist2spotify.AppViewModel
import io.github.magnusencoded.setlist2spotify.data.Friend
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmSetlist
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import java.time.temporal.ChronoUnit

/** The spine's geometry, shared by every row so nothing moves between resolutions. */
internal val SpineWidth = 52.dp
internal val SpineX = 25.dp

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
    data class Festival(val name: String, val shows: List<FmSetlist>) : TimelineNode
}

/**
 * The festival's real name — "Øyafestivalen 2025", not "Tøyenparken" — resolved from
 * setlist.fm's festival entity and passed in by [AppViewModel.resolveFestivalNames],
 * keyed by the cluster's first show. Until it lands (or if it never does) the venue
 * stands in, which is what the timeline showed before.
 */
private fun festivalName(shows: List<FmSetlist>, names: Map<String, String>): String =
    names[shows.first().id] ?: shows.first().venue?.name ?: "Festival"

private const val FESTIVAL_WINDOW_DAYS = 4L

/**
 * Groups a date-ordered list of shows into festivals — two or more shows at the
 * same venue within a few days of each other — leaving lone shows as concerts.
 * [names] maps a cluster's first show id to the festival's real name.
 */
fun groupIntoFestivals(setlists: List<FmSetlist>, names: Map<String, String> = emptyMap()): List<TimelineNode> {
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
            nodes.add(TimelineNode.Festival(festivalName(cluster, names), cluster))
        } else {
            nodes.add(TimelineNode.Concert(cluster.first()))
        }
        i = j
    }
    return nodes
}

/**
 * A row of the timeline at whatever resolution it is being shown at. [node] is always
 * my own shape of the thing — a concert or a collapsed festival — so a row keeps the
 * same size whether or not other people's lines are on screen. [others] are the
 * friends who were also there; [depth] 1 marks a gig listed inside an open festival.
 */
data class WovenRow(
    val node: TimelineNode,
    val mine: Boolean,
    val others: List<Friend>,
    val depth: Int = 0,
    /** Their shows that landed on this node — a festival can hold many of them. */
    val theirShows: List<FmSetlist> = emptyList(),
) {
    /** Shows we were both at: the thing this whole resolution exists to surface. */
    val sharedCount: Int
        get() {
            val theirIds = theirShows.map { it.id }.toSet()
            return shows.count { it.id in theirIds }
        }

    val key: String get() = when (val n = node) {
        is TimelineNode.Concert -> "c-${n.setlist.id}-$depth"
        is TimelineNode.Festival -> "f-${n.shows.first().id}"
    }
    val date: LocalDate? get() = when (val n = node) {
        is TimelineNode.Concert -> n.setlist.localDate()
        is TimelineNode.Festival -> n.shows.mapNotNull { it.localDate() }.maxOrNull()
    }
    val shows: List<FmSetlist> get() = when (val n = node) {
        is TimelineNode.Concert -> listOf(n.setlist)
        is TimelineNode.Festival -> n.shows
    }
    val shared: Boolean get() = mine && others.isNotEmpty()
}

/**
 * Everything on one spine: my nodes, plus the ones only other people were at. A run of
 * shows nobody but a friend attended doesn't compress my line — it just makes the edge
 * between my own nodes longer, which is the whole point of zooming out.
 *
 * A friend's shows are clustered into festivals the same way mine are, and a cluster
 * of theirs that lands at my venue within the same few days is folded into my festival
 * node rather than sitting beside it: one Tons of Rock, marked as shared. Expanding
 * that node ([expanded] holds row keys) lists the individual gigs so the two
 * attendances can be compared inside the festival.
 */
fun weaveTimelines(
    mine: List<FmSetlist>,
    festivalNames: Map<String, String>,
    friends: List<Friend>,
    theirs: Map<String, List<FmSetlist>>,
    expanded: Set<String> = emptySet(),
): List<WovenRow> {
    val myNodes = groupIntoFestivals(mine, festivalNames)
    val rows = mutableListOf<WovenRow>()
    val takenByMine = mutableMapOf<TimelineNode, MutableList<Friend>>()
    val takenShows = mutableMapOf<TimelineNode, MutableList<FmSetlist>>()

    // Their clusters, minus anything that belongs to a node of mine.
    val leftovers = mutableListOf<Pair<Friend, TimelineNode>>()
    for (friend in friends) {
        val shows = theirs[friend.setlistfm].orEmpty()
        if (shows.isEmpty()) continue
        for (node in groupIntoFestivals(shows, festivalNames)) {
            val host = myNodes.firstOrNull { it.absorbs(node) }
            if (host != null) {
                takenByMine.getOrPut(host) { mutableListOf() }.add(friend)
                takenShows.getOrPut(host) { mutableListOf() }.addAll(node.shows())
            } else {
                leftovers.add(friend to node)
            }
        }
    }

    myNodes.forEach {
        rows.add(
            WovenRow(
                it,
                mine = true,
                others = takenByMine[it].orEmpty(),
                theirShows = takenShows[it].orEmpty(),
            ),
        )
    }
    leftovers.forEach { (friend, node) -> rows.add(WovenRow(node, mine = false, others = listOf(friend))) }
    rows.sortByDescending { it.date }

    if (expanded.isEmpty()) return rows
    // Open festivals list their gigs underneath, each tagged with who was at that one.
    return rows.flatMap { row ->
        val node = row.node
        if (node !is TimelineNode.Festival || row.key !in expanded) return@flatMap listOf(row)
        val alsoHere = row.others.flatMap { f ->
            theirs[f.setlistfm].orEmpty().filter { node.absorbsShow(it) }
        }
        val myIds = node.shows.map { it.id }.toSet()
        val inner = (node.shows + alsoHere)
            .distinctBy { it.id }
            .sortedByDescending { it.localDate() }
            .map { show ->
                WovenRow(
                    node = TimelineNode.Concert(show),
                    mine = show.id in myIds,
                    others = row.others.filter { f -> theirs[f.setlistfm].orEmpty().any { it.id == show.id } },
                    depth = 1,
                )
            }
        listOf(row) + inner
    }
}

private fun TimelineNode.shows(): List<FmSetlist> = when (this) {
    is TimelineNode.Concert -> listOf(setlist)
    is TimelineNode.Festival -> shows
}

/** Same venue, overlapping few days — near enough to be the same festival. */
private fun TimelineNode.absorbs(other: TimelineNode): Boolean {
    val mineShows = (this as? TimelineNode.Festival)?.shows ?: return false
    val theirShows = when (other) {
        is TimelineNode.Festival -> other.shows
        is TimelineNode.Concert -> listOf(other.setlist)
    }
    return theirShows.any { show -> mineShows.any { sameFestival(it, show) } }
}

private fun TimelineNode.absorbsShow(show: FmSetlist): Boolean =
    (this as? TimelineNode.Festival)?.shows?.any { sameFestival(it, show) } == true

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

/**
 * A clustered node on the timeline: a venue that hosted several shows over a few days.
 * [gutter] is the strip between my spine and the text where other people's lines are
 * drawn when zoomed out; it is empty and zero-width at the single-timeline resolution,
 * so the row is the same size either way.
 */
@Composable
fun FestivalItem(
    festival: TimelineNode.Festival,
    highlight: Boolean,
    onClick: () -> Unit,
    open: Boolean = false,
    mine: Boolean = true,
    laneWidth: Dp = 0.dp,
    nodeX: Dp = SpineX,
    sharedCount: Int = 0,
    theirCount: Int = 0,
    rails: @Composable () -> Unit = {},
) {
    val amber = Color(0xFFE7B24C)
    val zoomedOut = laneWidth > 0.dp
    val accent = when {
        highlight || sharedCount > 0 -> amber
        // Among other people's lanes, my festivals have to look like mine too.
        zoomedOut && mine -> amber.copy(alpha = 0.65f)
        else -> Slate
    }
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(onClick = onClick),
    ) {
        Box(Modifier.width(SpineWidth + laneWidth).fillMaxHeight()) {
            rails()
            if (laneWidth <= 0.dp) {
                Box(Modifier.padding(start = SpineX).width(2.dp).fillMaxHeight().background(LineCol))
            }
            Box(
                Modifier
                    .padding(start = nodeX - 10.dp, top = 4.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Raised)
                    .border(2.dp, accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    // Zoomed out the shared count is the number that matters.
                    if (sharedCount > 0) "$sharedCount" else "${festival.shows.size}",
                    color = accent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Column(Modifier.padding(end = 18.dp, bottom = 22.dp)) {
            Text("FESTIVAL", color = Slate, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(3.dp))
            Text(festival.name, fontFamily = Serif, fontSize = 17.sp, color = Ink)
            Spacer(Modifier.height(2.dp))
            Text(festivalDateRange(festival.shows), color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(7.dp))
            Text(
                buildString {
                    // Comparing collections: what we each saw, and what we saw together.
                    if (sharedCount > 0) append("$sharedCount together · ")
                    append("${festival.shows.size} ${if (mine) "yours" else "theirs"}")
                    if (theirCount > 0) append(" · $theirCount theirs")
                    if (open) append(" · tap to close")
                },
                color = if (sharedCount > 0) amber else Faint,
                fontSize = 12.sp,
            )
        }
    }
}
