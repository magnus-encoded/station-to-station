package io.github.magnusencoded.stationtostation.ui

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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.magnusencoded.stationtostation.AppViewModel
import io.github.magnusencoded.stationtostation.data.Festivals
import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.data.StoredFestival
import io.github.magnusencoded.stationtostation.data.billedAs
import io.github.magnusencoded.stationtostation.data.parseFmDate
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The night two lines became one. Neither mine (amber) nor anyone's lane colour —
 * a meeting is its own thing.
 */
internal val Crossed = Color(0xFF6FBF9C)

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

/**
 * What one **Node** on the **Line** stands for: a lone **Gig**, an evening of several,
 * or a **Festival**.
 *
 * The three are not three shapes of the same claim. A **Section** says *these
 * performances were the same night in the same room* — a fact we have, from the date
 * and the venue we were given. A **Festival** says *this evening was Øyafestivalen
 * 2025*, which is a claim about what happened and needs a source that knows. #166 is
 * the fifth application of ADR-0002's thesis: festivalhood is demoted from a shape the
 * app computes to an identity a **Section** may acquire.
 */
sealed interface TimelineNode {
    /** The nights this **Node** stands for, one or many. */
    val shows: List<FmSetlist>

    data class Concert(val setlist: FmSetlist) : TimelineNode {
        override val shows: List<FmSetlist> get() = listOf(setlist)
    }

    /**
     * Several **Gigs** drawn as one **Node** — the two things on the **Line** that are
     * more than one night, and the only thing the screens need to tell from a
     * **Concert**. What kind of *more than one* it is stays here, in the seam that
     * decided it; nothing downstream asks.
     */
    sealed interface Several : TimelineNode {
        /**
         * What to draw. **Computed, never stored** — for the **Preamble**'s reason:
         * **Reconcile** has no time bound, a support act can be corrected upstream
         * years later, and a stored label would be the record freezing a fact it has
         * since learned better.
         */
        val label: String

        /** When each act went on, `HH:mm` by setlist.fm id, where a source published it. */
        val setTimes: Map<String, String> get() = emptyMap()

        /**
         * The evening as it went: earliest set first, where the source said. Nights
         * with no published time keep the order they arrived in, after the ones that
         * have one — a running order is a fact, and the absence of one is not a reason
         * to invent a different order.
         *
         * [also] is what other people were at here and I was not, so opening a node
         * lists the whole evening rather than my half of it.
         */
        fun runningOrder(also: List<FmSetlist> = emptyList()): List<FmSetlist> =
            (shows + also).distinctBy { it.id }.sortedWith(
                compareByDescending<FmSetlist> { it.localDate() }
                    .thenBy { setTimes[it.id] ?: LAST },
            )
    }

    /**
     * One evening: two or more **Gigs** on the same date at the same venue, and nothing
     * else. It makes no claim about what the evening *was*.
     *
     * Named from its own acts — the headliner, then its supports, "Devin Townsend
     * (Haken)" — because a room is not an event. The venue string used to be the label
     * whenever the name lookup had not landed, which is the visible half of #166; the
     * serious half was calling the night a **Festival** at all.
     *
     * "Coarse is not incomplete": a **Section** is not a **Festival** missing its
     * identity, and nothing in the app offers to complete it.
     */
    data class Section(override val shows: List<FmSetlist>) : Several {
        override val label: String get() = billedAs(shows)
    }

    /**
     * A **Section** that has an identity — and the identity is the whole of it. It
     * arrives from setlist.fm's own festival page or from a **Bill** typed in by hand,
     * and it is never inferred: a run of nights at one venue that nothing has named is
     * a run of nights.
     */
    data class Festival(
        val identity: StoredFestival,
        override val shows: List<FmSetlist>,
    ) : Several {
        override val label: String get() = identity.name
        override val setTimes: Map<String, String> get() = identity.setTimes.orEmpty()
    }
}

/** Sorts after every real `HH:mm` — see [TimelineNode.Several.runningOrder]. */
private const val LAST = "~"

/**
 * What a **Node** of several nights is called wherever it is drawn — the woven spine
 * and the future lane both, since a node that opens is the same node in either.
 *
 * A **Festival** is keyed by the identity's own id, which is the point of it having one
 * (#166): the key used to be the cluster's first show, so adopting a setlist or
 * correcting a venue typo moved it and took the row's open state — and, before #256,
 * its stored name — with it.
 */
val TimelineNode.Several.key: String get() = when (this) {
    is TimelineNode.Section -> "s-${shows.first().id}"
    is TimelineNode.Festival -> "f-${identity.id}"
}

/**
 * What a **Section** calls itself above its label: "ONE NIGHT" for several acts on one
 * date. Something the data actually says, unlike the word FESTIVAL.
 */
private fun eveningKicker(shows: List<FmSetlist>): String {
    val nights = shows.mapNotNull { it.localDate() }.distinct().size
    return if (nights <= 1) "ONE NIGHT" else "$nights NIGHTS"
}

/**
 * **The one seam: what becomes one Node.** Everything that draws a **Line** — the
 * **Spine**, every **Lane** beside it, the future lane — comes through here, which is
 * why the rule can be changed in one place and why nothing downstream needs to know
 * which kind it got.
 *
 * Three rules, and there is no fourth:
 *
 * - **An identity supplied for a set of Gigs → a `Festival`.** Membership comes from
 *   the identity's own day grouping where the source published one, and otherwise from
 *   the **Gigs** carrying that identity. One night of a four-day festival is still that
 *   festival: going for one day does not shrink it.
 * - **Same date, same venue → a `Section`.** One evening, drawn as one **Node**, named
 *   from its acts.
 * - **Nothing else groups.** Two nights at one venue with no identity are two
 *   **Nodes** — a residency, a local haunt, or a coincidence, and the record says the
 *   true, smaller thing rather than inventing an event that never happened.
 *
 * The four-day window that used to make the second decision is gone. It guessed in
 * both directions: it invented festivals out of a headline show with support, and it
 * named the real ones after their room whenever the lookup had not landed.
 *
 * Nodes come back in the order their first member appears in [setlists], so a
 * date-ordered list stays date-ordered.
 */
fun groupIntoFestivals(
    setlists: List<FmSetlist>,
    festivals: Festivals = Festivals(),
): List<TimelineNode> {
    val groups = LinkedHashMap<String, MutableList<FmSetlist>>()
    for (show in setlists) {
        groups.getOrPut(groupKey(show, festivals)) { mutableListOf() }.add(show)
    }
    return groups.values.map { shows ->
        val identity = festivals.of(shows.first().id)
        when {
            identity != null -> TimelineNode.Festival(identity, shows)
            shows.size >= 2 -> TimelineNode.Section(shows)
            else -> TimelineNode.Concert(shows.first())
        }
    }
}

/**
 * What decides that two **Gigs** are the same **Node**: an identity, or one evening in
 * one room.
 *
 * A show missing either half of "which evening" is keyed to itself and groups with
 * nothing — unknown is not a venue, and it is not a date either, so two nights that
 * cannot say where or when they were must never land on one **Node** together.
 */
private fun groupKey(show: FmSetlist, festivals: Festivals): String {
    festivals.of(show.id)?.let { return "f:${it.id}" }
    val venue = show.venue?.name?.lowercase(Locale.ROOT)
    val date = show.localDate()
    if (venue.isNullOrBlank() || date == null) return "x:${show.id}"
    return "e:$date|$venue"
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
    /**
     * The shows on this node that friends attended — a union across all of them,
     * deduped by id, and some of them are mine too. Not a partition: this was
     * called `theirShows`, which is exactly why concatenating two friends' lists
     * looked fine and double-counted every gig they both went to.
     */
    val showsHereByFriends: List<FmSetlist> = emptyList(),
) {
    /**
     * Shows I was at with company: the thing this whole resolution exists to surface.
     * Zero on a node that isn't mine — there, [shows] are already a friend's, so
     * intersecting them with what friends attended matched everything and called a
     * festival I never went to "3 together".
     */
    val sharedCount: Int
        get() {
            if (!mine) return 0
            val alsoTheirs = showsHereByFriends.map { it.id }.toSet()
            return shows.count { it.id in alsoTheirs }
        }

    /**
     * Shows a friend was at here **and I was not** — which is what **Theirs** means:
     * *"a **Gig** on a friend's timeline and not on mine"*.
     *
     * [showsHereByFriends] is a union and not a partition, so counting it directly says
     * "theirs" about nights we were at together. On a node where their list is a subset
     * of mine that reads as "4 together · 4 yours · 4 theirs" — four unjoined nights of
     * theirs that do not exist. The **Crossings** were real; the arithmetic beside them
     * was not.
     */
    val theirsCount: Int
        get() {
            if (!mine) return showsHereByFriends.size
            val mineHere = shows.map { it.id }.toSet()
            return showsHereByFriends.count { it.id !in mineHere }
        }

    val key: String get() = when (val n = node) {
        is TimelineNode.Concert -> "c-${n.setlist.id}-$depth"
        is TimelineNode.Several -> n.key
    }
    val date: LocalDate? get() = node.shows.mapNotNull { it.localDate() }.maxOrNull()
    val shows: List<FmSetlist> get() = node.shows
    val shared: Boolean get() = mine && others.isNotEmpty()
}

/**
 * Everything on one spine: my nodes, plus the ones only other people were at. A run of
 * shows nobody but a friend attended doesn't compress my line — it just makes the edge
 * between my own nodes longer, which is the whole point of zooming out.
 *
 * A friend's shows go through the same [groupIntoFestivals] mine do, and a node of
 * theirs that [hosts] says is the same thing as one of mine — the same **Festival**
 * identity, the same **Gig**, or the same evening in the same room — is folded into
 * mine rather than sitting beside it: one Tons of Rock, marked as shared. Expanding
 * that node ([expanded] holds row keys) lists the individual gigs so the two
 * attendances can be compared inside it.
 */
fun weaveTimelines(
    mine: List<FmSetlist>,
    festivals: Festivals,
    friends: List<Friend>,
    theirs: Map<String, List<FmSetlist>>,
    expanded: Set<String> = emptySet(),
): List<WovenRow> {
    val myNodes = groupIntoFestivals(mine, festivals)
    // Every node on the spine, mine first so a night I was at always hosts the meeting.
    // A cluster of theirs that no existing host takes becomes a host itself, which is
    // what lets two friends at a gig I missed land on one node instead of one each.
    val hosts = myNodes.toMutableList()
    val friendsAt = mutableMapOf<TimelineNode, MutableList<Friend>>()
    // Keyed by show id: two friends at the same gig contribute it once, or every
    // count taken off this node double-counts as soon as there are two of them.
    val showsAt = mutableMapOf<TimelineNode, LinkedHashMap<String, FmSetlist>>()

    for (friend in friends) {
        val shows = theirs[friend.setlistfm].orEmpty()
        if (shows.isEmpty()) continue
        for (node in groupIntoFestivals(shows, festivals)) {
            val host = hosts.firstOrNull { it.hosts(node) } ?: node.also { hosts.add(it) }
            friendsAt.getOrPut(host) { mutableListOf() }
                .let { if (it.none { f -> f.setlistfm == friend.setlistfm }) it.add(friend) }
            val here = showsAt.getOrPut(host) { LinkedHashMap() }
            node.shows.forEach { here.putIfAbsent(it.id, it) }
        }
    }

    val rows = hosts.mapIndexed { i, node ->
        WovenRow(
            node,
            mine = i < myNodes.size,
            others = friendsAt[node].orEmpty(),
            showsHereByFriends = showsAt[node]?.values?.toList().orEmpty(),
        )
    }.sortedByDescending { it.date }

    if (expanded.isEmpty()) return rows
    // Open festivals list their gigs underneath, each tagged with who was at that one.
    return rows.flatMap { row ->
        val node = row.node
        if (node !is TimelineNode.Several || row.key !in expanded) return@flatMap listOf(row)
        // Whose a gig is comes from my own timeline, never from the node holding it —
        // reading it off node.shows made every gig inside a friend's festival look mine.
        val myIds = mine.map { it.id }.toSet()
        val inner = node.runningOrder(row.showsHereByFriends)
            .map { show ->
                val alsoHere =
                    row.others.filter { f -> theirs[f.setlistfm].orEmpty().any { it.id == show.id } }
                WovenRow(
                    node = TimelineNode.Concert(show),
                    mine = show.id in myIds,
                    others = alsoHere,
                    depth = 1,
                    // Carried, not defaulted: [WovenRow.sharedCount] is an intersection
                    // with this list, so leaving it empty made it structurally zero at
                    // depth 1 and no member gig could ever draw a **Crossing**. The
                    // **Festival** above said "2 together" and both of the nights it
                    // counted drew amber. Amber means mine at *every* **Resolution**
                    // (ADR-0006), and a Resolution that cannot say green is not saying
                    // amber — it is saying nothing.
                    //
                    // [alsoHere] is already exactly the friends who were at this show, so
                    // this needs no rule of its own: the show is in the list when anyone
                    // else was there, and the list is empty when nobody was.
                    showsHereByFriends = if (alsoHere.isEmpty()) emptyList() else listOf(show),
                )
            }
        listOf(row) + inner
    }
}

/**
 * Whether [other]'s node belongs on this one rather than beside it — the same three
 * facts the grouping seam uses, read across two **Lines** instead of down one, so a
 * **Crossing** is decided by exactly what makes a **Node**:
 *
 * - **the same identity** — their nights at Øyafestivalen 2025 land on my Øya node,
 *   however few of the days either of us went to;
 * - **the same Gig** on both lists, which is what **Together** means;
 * - **the same evening in the same room**, which is the **Section** rule.
 *
 * Anything looser — same venue, different nights, an identity nobody supplied — would
 * mark unshared nights as shared, which is the four-day window #166 removed.
 */
private fun TimelineNode.hosts(other: TimelineNode): Boolean =
    sameIdentity(other) ||
        shows.any { a -> other.shows.any { b -> a.id == b.id } } ||
        sameEvening(other)

private fun TimelineNode.sameIdentity(other: TimelineNode): Boolean =
    this is TimelineNode.Festival && other is TimelineNode.Festival &&
        identity.id == other.identity.id

/** Every night on both nodes in one room on one date. See [groupKey]. */
private fun TimelineNode.sameEvening(other: TimelineNode): Boolean {
    val all = shows + other.shows
    val date = all.first().localDate() ?: return false
    val venue = all.first().venue?.name?.takeUnless { it.isBlank() } ?: return false
    return all.all { it.localDate() == date && venue.equals(it.venue?.name, ignoreCase = true) }
}

/**
 * The dates under a **Node**'s label. A **Festival** says its *own* range where the
 * source published one — "Tons of Rock 2026" is four days whether or not I went to
 * four — and falls back to the nights on the node when it does not.
 */
private fun festivalDateRange(node: TimelineNode.Several): String {
    val identity = (node as? TimelineNode.Festival)?.identity
    val from = identity?.rangeFrom?.let(::parseFmDate)
    val to = identity?.rangeTo?.let(::parseFmDate)
    val dates = listOfNotNull(from, to).ifEmpty { node.shows.mapNotNull { it.localDate() }.sorted() }
    if (dates.isEmpty()) return ""
    val full = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    val a = dates.first()
    val b = dates.last()
    return if (a == b) a.format(full)
    else "${a.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))} – ${b.format(full)}"
}

/**
 * A **Node** standing for several nights: one evening of several acts, or a
 * **Festival**. [laneWidth] is the strip between my spine and the text where other
 * people's lines are drawn when zoomed out; it is zero-width at the single-timeline
 * resolution, so the row is the same size either way.
 */
@Composable
fun FestivalItem(
    festival: TimelineNode.Several,
    highlight: Boolean,
    onClick: () -> Unit,
    open: Boolean = false,
    mine: Boolean = true,
    laneWidth: Dp = 0.dp,
    nodeX: Dp = SpineX,
    sharedCount: Int = 0,
    theirCount: Int = 0,
    theirColor: Color = Slate,
    /** Under the contact light (#145): the amber comes off, and the meeting green with it. */
    unlit: Boolean = false,
    rails: @Composable () -> Unit = {},
) {
    val amber = if (unlit) Color(0xFF7C7788) else Color(0xFFE7B24C)
    // Amber means mine, at every resolution; brightness means most recent or shared.
    val accent = when {
        // A generic contact view has no "we", so a night marked as shared would claim a
        // relationship this view deliberately does not have.
        unlit -> amber
        sharedCount > 0 -> Crossed
        highlight -> amber
        mine -> amber.copy(alpha = 0.6f)
        else -> theirColor
    }
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(onClick = onClick),
    ) {
        Box(Modifier.width(SpineWidth + laneWidth).fillMaxHeight()) {
            rails()
            if (laneWidth <= 0.dp) {
                Box(
                    Modifier.padding(start = SpineX).width(2.dp).fillMaxHeight()
                        .background(amber.copy(alpha = 0.3f)),
                )
            }
            Box(
                Modifier
                    .padding(start = nodeX - 10.dp, top = 4.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    // See-through: a node is a ring; nothing is drawn inside one.
                    .background(Color.Transparent)
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
            // Only a Node with an identity is called a festival. Without one this is
            // still one evening drawn as one Node — which is a fact we have — and the
            // eyebrow says only that (#166).
            Text(
                if (festival is TimelineNode.Festival) "FESTIVAL" else eveningKicker(festival.shows),
                color = Slate,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.height(3.dp))
            Text(festival.label, fontFamily = Serif, fontSize = 17.sp, color = if (mine) Ink else Muted)
            Spacer(Modifier.height(2.dp))
            Text(festivalDateRange(festival), color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(7.dp))
            Text(
                buildAnnotatedString {
                    // Whose is only worth saying when someone else is on screen.
                    if (theirCount == 0 && sharedCount == 0) {
                        append("${festival.shows.size} gigs")
                    } else if (!mine) {
                        // Not my node: one count, covering whoever of them was there.
                        // Saying it twice — once off the node, once off the union —
                        // is what produced "3 theirs · 3 theirs".
                        withStyle(SpanStyle(color = theirColor)) { append("$theirCount theirs") }
                    } else {
                        if (sharedCount > 0) {
                            withStyle(SpanStyle(color = Crossed, fontWeight = FontWeight.SemiBold)) {
                                append("$sharedCount together")
                            }
                            append(" · ")
                        }
                        withStyle(SpanStyle(color = amber.copy(alpha = 0.75f))) {
                            append("${festival.shows.size} yours")
                        }
                        if (theirCount > 0) {
                            append(" · ")
                            withStyle(SpanStyle(color = theirColor)) { append("$theirCount theirs") }
                        }
                    }
                    if (open) append(" · tap to close")
                },
                color = Faint,
                fontSize = 12.sp,
            )
        }
    }
}
