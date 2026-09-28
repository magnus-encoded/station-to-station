/**
 * The Festival grammar: what a night *is* before anything draws it — [TimelineNode],
 * [groupIntoFestivals], [weaveTimelines] — plus the one row composable that renders a
 * collapsed one, [FestivalItem].
 *
 * It was FestivalScreen.kt until #340. There is no screen: Android's live Festival
 * resolution is the in-place expansion in StationScreen.kt (`openFestivals`,
 * `inside = row.depth > 0`), and the screen this file was named for went with it. The
 * name outlived the thing, which is how #176 came to be filed against a file that
 * described the app as it no longer was.
 */
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.magnusencoded.stationtostation.data.Festivals
import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.data.laneKey
import io.github.magnusencoded.stationtostation.data.StoredFestival
import io.github.magnusencoded.stationtostation.data.billedAs
import io.github.magnusencoded.stationtostation.data.isLocal
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
private val Slate = Color(0xFF6F809D)
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
    /**
     * Their Night id → my Night id, for every Night I said is the same Night as one of
     * mine (#405): by accepting an offer for it, or by answering a *maybe*. Under the ids
     * the **Spine** uses. A joined pair counts as **Together** exactly as a shared id does.
     */
    val joins: Map<String, String> = emptyMap(),
    /**
     * The **Contacts** who were out on this row's date under a Night nothing links to
     * mine (#405): a *maybe*, never a **Crossing**. See [maybeNights]. Only ever on a
     * row of mine, and never for someone this row already crosses.
     */
    val maybe: List<Friend> = emptyList(),
    /**
     * On a row of theirs the weave put directly below one of my Nights (#580): the
     * *maybes* the merge row above it asks about, one per pair. Empty everywhere else.
     */
    val maybeAbove: List<MaybeNight> = emptyList(),
    /**
     * The part of [maybe] no merge row can ask (#580), because their Night has no row of
     * its own to sit below mine — folded into another node, or already under another Night
     * of mine that date. Said in words on my row, as #405 did everywhere.
     */
    val maybeInWords: List<Friend> = emptyList(),
    /**
     * The **Contacts** whose Night I joined to one of this row's Nights (#405, #580):
     * "With Mia" under my node. Only ever on a row of mine.
     */
    val joinedWith: List<Friend> = emptyList(),
) {
    /** Whether my [mine] and their [theirs] are one Night: a shared id, #433's match, or a join. */
    private fun together(mine: FmSetlist, theirs: FmSetlist): Boolean =
        mine.sameAttendance(theirs) || joins[theirs.id] == mine.id

    /**
     * Shows I was at with company: the thing this whole resolution exists to surface.
     * Zero on a node that isn't mine — there, [shows] are already a friend's, so
     * intersecting them with what friends attended matched everything and called a
     * festival I never went to "3 together".
     */
    val sharedCount: Int
        get() {
            if (!mine) return 0
            return shows.count { mine -> showsHereByFriends.any { together(mine, it) } }
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
            return showsHereByFriends.count { theirs -> shows.none { together(it, theirs) } }
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
 *
 * [joins] and [apart] are what I have said about a Contact's Night that nothing else
 * links to mine (#405), under the ids the Spine uses: their Night id → my Night id I
 * said it is, and their Night id → my Night ids I said it is not. A joined pair folds
 * and counts as **Together** like a shared id. A *maybe* ([maybeNights]) never folds:
 * its Night stays on their **Lane**, and my row carries them in [WovenRow.maybe]
 * until I answer — or, once I have said "not the same", carries nothing at all.
 */
fun weaveTimelines(
    mine: List<FmSetlist>,
    festivals: Festivals,
    friends: List<Friend>,
    theirs: Map<String, List<FmSetlist>>,
    expanded: Set<String> = emptySet(),
    joins: Map<String, String> = emptyMap(),
    apart: Map<String, Set<String>> = emptyMap(),
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
        val shows = theirs[friend.laneKey].orEmpty()
        if (shows.isEmpty()) continue
        for (node in groupIntoFestivals(shows, festivals)) {
            val at = hosts.indices.firstOrNull { i ->
                hosts[i].hosts(node, festivals, joins, mineHost = i < myNodes.size)
            }
            val host = if (at != null) hosts[at] else node.also { hosts.add(it) }
            friendsAt.getOrPut(host) { mutableListOf() }
                .let { if (it.none { f -> f.laneKey == friend.laneKey }) it.add(friend) }
            val here = showsAt.getOrPut(host) { LinkedHashMap() }
            node.shows.forEach { here.putIfAbsent(it.id, it) }
        }
    }

    // Who may share each of my Nights, by my Night id. Decided once, over the whole
    // Spine, so a Night of theirs one of my other Nights already answers is no question.
    val pairs = maybeNights(mine, friends, theirs, festivals, joins, apart)
    val pairsAt = pairs.groupBy { it.mine.id }
    fun maybeOn(shows: List<FmSetlist>): List<Friend> =
        shows.flatMap { s -> pairsAt[s.id].orEmpty().map { it.friend } }.distinctBy { it.laneKey }
    fun joinedOn(shows: List<FmSetlist>, others: List<Friend>): List<Friend> {
        val ids = shows.mapTo(HashSet()) { it.id }
        return others.filter { f -> theirs[f.laneKey].orEmpty().any { joins[it.id] in ids } }
    }

    val sorted = hosts.mapIndexed { i, node ->
        val isMine = i < myNodes.size
        val others = friendsAt[node].orEmpty()
        WovenRow(
            node,
            mine = isMine,
            others = others,
            showsHereByFriends = showsAt[node]?.values?.toList().orEmpty(),
            joins = joins,
            maybe = if (isMine) maybeOn(node.shows) else emptyList(),
            joinedWith = if (isMine) joinedOn(node.shows, others) else emptyList(),
        )
    }.sortedByDescending { it.date }

    val (rows, placed) = maybesBelowMine(sorted, pairsAt)
    fun inWords(shows: List<FmSetlist>): List<Friend> =
        shows.flatMap { s -> pairsAt[s.id].orEmpty().filterNot { it in placed }.map { it.friend } }
            .distinctBy { it.laneKey }

    val woven = rows.map { row -> if (row.mine) row.copy(maybeInWords = inWords(row.shows)) else row }
    if (expanded.isEmpty()) return woven
    // Open festivals list their gigs underneath, each tagged with who was at that one.
    return woven.flatMap { row ->
        val node = row.node
        if (node !is TimelineNode.Several || row.key !in expanded) return@flatMap listOf(row)
        // Whose a gig is comes from my own timeline, never from the node holding it —
        // reading it off node.shows made every gig inside a friend's festival look mine.
        val myIds = mine.map { it.id }.toSet()
        // A Night of theirs I joined to one of mine is listed once, as mine — not again
        // as a row of theirs beside it.
        val also = row.showsHereByFriends.filterNot { joins[it.id] in myIds }
        val inner = node.runningOrder(also)
            .map { show ->
                val alsoHere = row.others.filter { f ->
                    theirs[f.laneKey].orEmpty().any { it.id == show.id || joins[it.id] == show.id }
                }
                val isMine = show.id in myIds
                WovenRow(
                    node = TimelineNode.Concert(show),
                    mine = isMine,
                    others = alsoHere,
                    depth = 1,
                    maybe = if (isMine) maybeOn(listOf(show)) else emptyList(),
                    maybeInWords = if (isMine) inWords(listOf(show)) else emptyList(),
                    joinedWith = if (isMine) joinedOn(listOf(show), alsoHere) else emptyList(),
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
 * **A maybe pair is always neighbours** (#580). Rows arrive newest first; this moves
 * each row of theirs that holds a *maybe* against one of my Nights to directly below
 * that Night, so the merge row between the two has both of them to point at.
 *
 * Several maybes on one Night of mine stack below it, in the order [maybeNights] names
 * them (Lane order): mine → Mia's → Tom's, each with its merge row above it. A row of
 * theirs goes below the first Night of mine that asks about it and nowhere else; a
 * pair whose Night has no row of theirs — folded onto a node, or already placed under
 * another Night of mine — is not placed, and is said in words instead.
 *
 * Only rows move, never what is on them, and nothing moves when there is no maybe —
 * which is every Resolution but the zoomed-out one, where the other Lanes are drawn.
 * Returns the rows, with [WovenRow.maybeAbove] set, and the pairs that were placed.
 */
private fun maybesBelowMine(
    rows: List<WovenRow>,
    pairsAt: Map<String, List<MaybeNight>>,
): Pair<List<WovenRow>, Set<MaybeNight>> {
    if (pairsAt.isEmpty()) return rows to emptySet()
    val ownerOf = HashMap<Int, Int>()
    val below = HashMap<Int, MutableList<Int>>()
    val above = HashMap<Int, MutableList<MaybeNight>>()
    rows.forEachIndexed { i, row ->
        if (!row.mine) return@forEachIndexed
        for (pair in row.shows.flatMap { pairsAt[it.id].orEmpty() }) {
            val j = rows.indices.firstOrNull { j ->
                !rows[j].mine && rows[j].date == row.date && (rows[j].shows + rows[j].showsHereByFriends).any { it.id == pair.theirs.id }
            } ?: continue
            val owner = ownerOf.getOrPut(j) { i.also { below.getOrPut(i) { mutableListOf() }.add(j) } }
            if (owner != i) continue
            above.getOrPut(j) { mutableListOf() }.add(pair)
        }
    }
    if (above.isEmpty()) return rows to emptySet()
    val out = ArrayList<WovenRow>(rows.size)
    rows.forEachIndexed { i, row ->
        if (i in ownerOf) return@forEachIndexed
        out += row
        below[i]?.forEach { j -> out += rows[j].copy(maybeAbove = above[j].orEmpty()) }
    }
    return out to above.values.flatten().toSet()
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
 *
 * Two more, from #405. A Night I **joined** by hand ([joins]) folds like a shared id.
 * And on one of my own nodes ([mineHost]), the same evening in the same room is not
 * enough when every pair of Nights across the two is only a *maybe*
 * ([couldBeSameNight]): two hand-logged Nights share no id, and folding them would be
 * the app answering the question it is supposed to ask.
 */
private fun TimelineNode.hosts(
    other: TimelineNode,
    festivals: Festivals = Festivals(),
    joins: Map<String, String> = emptyMap(),
    mineHost: Boolean = false,
): Boolean =
    sameIdentity(other) ||
        shows.any { a ->
            other.shows.any { b -> a.id == b.id || joins[b.id] == a.id || joins[a.id] == b.id }
        } ||
        (sameEvening(other) &&
            (!mineHost || shows.any { a -> other.shows.any { b -> !couldBeSameNight(a, b, festivals) } }))

/**
 * A Night of a **Contact**'s that may be the same Night as one of mine, and nothing
 * says either way (#405): the *maybe*. A question on my row, never a **Crossing**.
 */
data class MaybeNight(val friend: Friend, val mine: FmSetlist, val theirs: FmSetlist)

/**
 * One line of the *maybe*'s comparison (#580): what [label] says on my Night ([yours])
 * and on theirs ([theirs]), and whether the two disagree. "From" never [differs]: at
 * least one side of every maybe is typed by hand, so where each came from is the reason
 * for the question, not a disagreement about the night.
 */
data class MaybeField(val label: String, val yours: String, val theirs: String, val differs: Boolean)

/** Where a Night's record came from, in the comparison's words. */
private fun FmSetlist.fromWords(): String = if (isLocal()) "typed by hand" else "setlist.fm"

private fun String?.orDash(): String = this?.trim()?.takeIf { it.isNotEmpty() } ?: "—"

/** Two values the same when a person would read them as the same: case and spacing aside. */
private fun sameWords(a: String, b: String): Boolean =
    a.trim().lowercase(Locale.ROOT) == b.trim().lowercase(Locale.ROOT)

/**
 * The *maybe* side by side (#580): Artist, Date, Venue, City, From — mine on the left,
 * theirs on the right — so "were you both there" is answered by reading, never by
 * choosing field by field. Nothing here is kept or dropped; see [sameNightLine].
 */
fun compareMaybe(maybe: MaybeNight): List<MaybeField> {
    val mine = maybe.mine
    val theirs = maybe.theirs
    fun field(label: String, a: String, b: String) = MaybeField(label, a, b, differs = !sameWords(a, b))
    return listOf(
        field("Artist", mine.artist?.name.orDash(), theirs.artist?.name.orDash()),
        field("Date", mine.readableDateShort().orDash(), theirs.readableDateShort().orDash()),
        field("Venue", mine.venue?.name.orDash(), theirs.venue?.name.orDash()),
        field("City", mine.venue?.city?.name.orDash(), theirs.venue?.city?.name.orDash()),
        MaybeField("From", mine.fromWords(), theirs.fromWords(), differs = false),
    )
}

/**
 * The one line under the comparison (#580): what "Same night" keeps. Mine, and their
 * Night joins it (`nightJoins`), with whichever side came from setlist.fm named so the
 * line matches the case.
 *
 * - mine from setlist.fm: "Same night keeps your setlist.fm entry. Mia's joins it."
 * - both typed by hand: "Same night keeps your entry. Mia's joins it."
 * - only theirs from setlist.fm: "Mia's is on setlist.fm. After Same night you can take
 *   it as yours." — "Same night" then asks ([maybeAdoptable]); taking it is an adoption.
 */
fun sameNightLine(maybe: MaybeNight): String {
    val whose = maybeWhose(maybe)
    return when {
        !maybe.mine.isLocal() -> "Same night keeps your setlist.fm entry. $whose joins it."
        maybeAdoptable(maybe) -> "${if (maybe.friend.name.isBlank()) "Theirs" else whose} is on setlist.fm. " +
            "After Same night you can take it as yours."
        else -> "Same night keeps your entry. $whose joins it."
    }
}

/**
 * Mine typed by hand, theirs from setlist.fm (#580): "Same night" then offers to take
 * their setlist.fm entry — an adoption, which merges the two for good. Asked, never
 * assumed, because the adoption is the one answer that cannot be undone.
 */
fun maybeAdoptable(maybe: MaybeNight): Boolean = maybe.mine.isLocal() && !maybe.theirs.isLocal()

/** The adoption question's heading (#580): "Take Mia's setlist.fm entry?" */
fun maybeAdoptQuestion(maybe: MaybeNight): String = "Take ${maybeTheir(maybe, "their")} setlist.fm entry?"

/** What taking it does, and what keeping yours does instead (#580). */
fun maybeAdoptLine(maybe: MaybeNight): String =
    "Your Night becomes ${maybeTheir(maybe, "their")} setlist.fm entry, setlist and all. " +
        "This can't be undone. Keep mine joins the two Nights, and that can be undone."

/** "Mia's" — or "Theirs" for a Contact with no name. */
fun maybeWhose(maybe: MaybeNight): String =
    maybe.friend.name.takeIf { it.isNotBlank() }?.let { "$it's" } ?: "Theirs"

/** "Mia's" mid-sentence, where a Contact with no name reads [fallback]. */
private fun maybeTheir(maybe: MaybeNight, fallback: String): String =
    maybe.friend.name.takeIf { it.isNotBlank() }?.let { "$it's" } ?: fallback

/** The merge row's pill (#580): "Same night as Mia's?", then "Compare". */
fun maybePill(maybe: MaybeNight): String = "Same night as ${maybeTheir(maybe, "theirs")}?"

/** What a screen reader says for the merge row (#580). */
fun maybeMergeLabel(maybe: MaybeNight): String =
    "Maybe the same night: yours above, ${maybeTheir(maybe, "theirs")} below. Compare them."

/** One line of the comparison, read aloud: "Venue: yours Blå, Mia's Brenneriveien 9, differs". */
fun maybeFieldSpoken(maybe: MaybeNight, field: MaybeField): String =
    "${field.label}: yours ${field.yours}, ${maybeWhose(maybe)} ${field.theirs}" +
        if (field.differs) ", differs" else ""

/** The snackbar after an answer (#580): "Joined with Mia's night" / "Kept apart from Mia's night". */
fun maybeAnswered(maybe: MaybeNight, same: Boolean): String =
    (if (same) "Joined with " else "Kept apart from ") + maybeTheir(maybe, "their") + " night"

/**
 * **The maybe-shared rule** (#405), decided where the Spine is woven.
 *
 * A Contact was out on the same date as one of my Nights, under a different id, and
 * neither record claims the other. Pair by pair:
 *
 * - **the same id** is a **Crossing**, as it always was — never a maybe;
 * - **a different date** is neither;
 * - **two setlist.fm ids** are two catalogued records, which is a fact rather than a
 *   question — a shared bill folds by the **Section** rule and two rooms are two Nights;
 * - **#433's match** (one side hand-logged, same room, same date) is already the same
 *   attendance — unless one side is a **Festival** day and the other is not, which is a
 *   difference of granularity and is asked, never asserted (story 24);
 * - a Night of theirs I **joined** ([joins]) to any Night of mine is answered, and so
 *   is a Night of mine they already cross;
 * - a pair I said is **not the same** ([apart]: their id → my ids) never comes back.
 *
 * No venue, artist or date-window matching: the date is the only thing compared, and a
 * person answers the rest.
 */
fun maybeNights(
    mine: List<FmSetlist>,
    friends: List<Friend>,
    theirs: Map<String, List<FmSetlist>>,
    festivals: Festivals = Festivals(),
    joins: Map<String, String> = emptyMap(),
    apart: Map<String, Set<String>> = emptyMap(),
): List<MaybeNight> {
    val myIds = mine.mapTo(HashSet()) { it.id }
    val mineByDate = mine.filter { it.localDate() != null }.groupBy { it.localDate() }
    val out = mutableListOf<MaybeNight>()
    for (friend in friends) {
        val lane = theirs[friend.laneKey].orEmpty()
        if (lane.isEmpty()) continue
        val laneIds = lane.mapTo(HashSet()) { it.id }
        val laneByDate = lane.groupBy { it.localDate() }
        for ((date, myThatDay) in mineByDate) {
            val theirThatDay = laneByDate[date].orEmpty()
            if (theirThatDay.isEmpty()) continue
            // A Night of theirs one of mine already answers to is no question.
            val open = theirThatDay.filter { t ->
                t.id !in myIds && t.id !in joins && myThatDay.none { m -> sameRecord(m, t, festivals) }
            }
            if (open.isEmpty()) continue
            for (m in myThatDay) {
                val crossed = m.id in laneIds ||
                    lane.any { joins[it.id] == m.id } ||
                    theirThatDay.any { sameRecord(m, it, festivals) }
                if (crossed) continue
                for (t in open) {
                    if (!couldBeSameNight(m, t, festivals)) continue
                    if (m.id in apart[t.id].orEmpty()) continue
                    out += MaybeNight(friend, m, t)
                }
            }
        }
    }
    return out
}

/**
 * The pair-level half of [maybeNights], before anything I said: the same date, not the
 * same record, and at least one side with no setlist.fm id behind it.
 */
private fun couldBeSameNight(a: FmSetlist, b: FmSetlist, festivals: Festivals): Boolean {
    if (a.id == b.id) return false
    if (!a.isLocal() && !b.isLocal()) return false
    val date = a.localDate() ?: return false
    if (date != b.localDate()) return false
    return !sameRecord(a, b, festivals)
}

/**
 * [sameAttendance], short of granularity (#405, story 24): #433's hand-logged match is
 * not made between a **Festival** day and a Night that is not one.
 */
private fun sameRecord(a: FmSetlist, b: FmSetlist, festivals: Festivals): Boolean {
    if (a.id == b.id) return true
    return a.sameAttendance(b) && festivals.of(a.id)?.id == festivals.of(b.id)?.id
}

private fun TimelineNode.sameIdentity(other: TimelineNode): Boolean =
    this is TimelineNode.Festival && other is TimelineNode.Festival &&
        identity.id == other.identity.id

/** Every night on both nodes in one room on one date. See [groupKey]. */
private fun TimelineNode.sameEvening(other: TimelineNode): Boolean {
    val all = shows + other.shows
    val date = all.first().localDate() ?: return false
    val venue = all.first().venue?.name?.takeUnless { it.isBlank() } ?: return false
    return all.all { it.localDate() == date && sameVenueName(venue, it.venue?.name.orEmpty()) }
}

/**
 * Whether two venue names are the same room, loosely (#433). A **Gig** with no
 * setlist.fm id behind it — typed by hand, or guessed from a ticket — has whatever
 * venue string a person or an OCR pass produced, and that essentially never matches a
 * friend's setlist.fm-formatted name character for character: "Parkteatret" against
 * "Parkteatret Scene, Oslo, Norway" is the same room and used to never fold together.
 *
 * Loosened to: equal after lowercasing and dropping everything from the first comma
 * on (the city/country a source tacks on), or one of those trimmed names contained in
 * the other. Still keyed on the exact date, so this cannot resurrect the four-day
 * festival window #166 removed — only makes the venue half of the check forgiving.
 */
private fun sameVenueName(a: String, b: String): Boolean {
    val na = a.substringBefore(',').trim().lowercase(Locale.ROOT)
    val nb = b.substringBefore(',').trim().lowercase(Locale.ROOT)
    if (na.isEmpty() || nb.isEmpty()) return false
    return na == nb || na.contains(nb) || nb.contains(na)
}

/**
 * Whether two shows are the same real-world attendance rather than two different
 * records that merely landed on the same [hosts]ed node (#433). An exact id match is
 * the strongest signal there is; short of that, this only stands in for a missing id
 * — never for two ids that both claim to be real. [isLocal] is what tells the two
 * apart: a setlist.fm show always has one, so two of them with *different* ids at the
 * same venue and date are two different real things (a shared bill — Verandaen's own
 * test below has a friend at a third act I wasn't at, same night, same room — must
 * stay two attendances, not one). It is only when exactly one side has no id at all —
 * a local **Gig**, typed by hand or guessed from a ticket with no setlist.fm account
 * behind it — that the same date at the same room (per [sameVenueName]) is taken as
 * good enough: that is exactly the rule [sameEvening] already used to fold the two
 * *nodes* together, applied per show instead of only at the node level, which is what
 * keeps a multi-day festival honest too — my day 2 and a friend's day 3 share neither
 * date nor id nor this asymmetry, so they still count as unshared even though the
 * festival identity node holds both (#166).
 */
private fun FmSetlist.sameAttendance(other: FmSetlist): Boolean {
    if (id == other.id) return true
    if (isLocal() == other.isLocal()) return false
    val date = localDate() ?: return false
    if (date != other.localDate()) return false
    return sameVenueName(venue?.name.orEmpty(), other.venue?.name.orEmpty())
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
    unlit: Boolean = false,
    rails: @Composable () -> Unit = {},
    /**
     * The non-gestural route into the **Collection resolution** (#313): a reader with
     * no fingers to aim a pinch with gets the node named instead, one action calling
     * the same function the gesture will call. Null where a node has nothing to walk
     * yet — a still-planned run, say — so the action does not appear for a door with
     * nothing behind it.
     */
    onWalk: (() -> Unit)? = null,
    /** Who may have shared one of these nights (#405). See [MaybeLine]. */
    maybeWith: List<String> = emptyList(),
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
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(onClick = onClick)
            .let { m ->
                if (onWalk == null) m else m.semantics {
                    customActions = listOf(
                        CustomAccessibilityAction("Walk the whole run") { onWalk(); true },
                    )
                }
            },
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
                    // Opaque, like every other node: the spine stops at the rim instead
                    // of running through the ring and behind the count inside it.
                    .background(Ground)
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
            if (maybeWith.isNotEmpty()) MaybeLine(maybeWith)
        }
    }
}
