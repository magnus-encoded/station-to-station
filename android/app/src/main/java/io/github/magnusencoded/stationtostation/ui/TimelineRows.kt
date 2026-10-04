package io.github.magnusencoded.stationtostation.ui

import android.net.Uri
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.magnusencoded.stationtostation.BuildConfig
import io.github.magnusencoded.stationtostation.MediaThumb
import io.github.magnusencoded.stationtostation.caption
import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.data.laneKey
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSong
import io.github.magnusencoded.stationtostation.data.setlistfm.line
import io.github.magnusencoded.stationtostation.data.visibleToContacts

private val Amber = Color(0xFFE7B24C)
private val Raised = Color(0xFF17121F)
private val Ink = Color(0xFFEDE9F2)
private val Muted = Color(0xFF8B8299)
private val Faint = Color(0xFF5A5368)
private val Slate = Color(0xFF6F809D) // the future / a connected-source, a cooler light
private val Serif = FontFamily.Serif
private val Ground = Color(0xFF0E0B14)
private val LineCol = Color(0xFF2E2740)
private val LineLit = Color(0xFF4A3F63)
private val AmberSoft = Color(0x29E7B24C)
private val Danger = Color(0xFFE08A8A)

/** "Maybe with Mia" — the *maybe*'s tag on a Night of mine (#405). */
internal fun maybeTagLine(maybe: MaybeNight): String =
    "Maybe with ${maybe.friend.name.ifBlank { "a Contact" }}"

/** What their Night says it was: "Mia logged Kvelertak at Rockefeller". */
internal fun maybeTheirNight(maybe: MaybeNight): String {
    val who = maybe.friend.name.ifBlank { "A Contact" }
    val what = listOfNotNull(
        maybe.theirs.artist?.name?.takeIf { it.isNotBlank() },
        maybe.theirs.venue?.name?.takeIf { it.isNotBlank() },
    ).joinToString(" at ")
    return if (what.isBlank()) "$who logged a night on this date" else "$who logged $what"
}

/** One line of a **Gig**'s long-press menu. */
internal class GigMenuEntry(val label: String, val danger: Boolean = false, val run: () -> Unit)

/** A **Gig**'s long-press menu: where it is held, and what can be done. */
internal class GigMenuSpec(val caption: String, val entries: List<GigMenuEntry>)

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
    /**
     * The **Contacts** who may have shared this Night (#405): out the same date under a
     * Night nothing links to mine. A question, so it is said in words and never drawn as
     * a **Crossing** — the node stays mine until I answer in the **Room**.
     */
    maybeWith: List<String> = emptyList(),
    /** Whose Night I said was this one (#580): "With Mia" under the joined node. */
    joinedWith: List<String> = emptyList(),
    /** What a long press offers; null, and the row has no menu. */
    menu: GigMenuSpec? = null,
) {
    val songCount = setlist.performed().size
    val zoomedOut = laneWidth > 0.dp
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).combinedClickable(
            onClick = onClick,
            onLongClickLabel = if (menu == null) null else "More",
            onLongClick = if (menu == null) null else ({ menuOpen = true }),
        ),
    ) {
        if (menu != null) {
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                modifier = Modifier.background(Raised),
            ) {
                Text(
                    menu.caption,
                    color = Faint,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                menu.entries.forEach { entry ->
                    DropdownMenuItem(
                        text = { Text(entry.label, color = if (entry.danger) Danger else Ink) },
                        onClick = { menuOpen = false; entry.run() },
                    )
                }
            }
        }
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
            if (joinedWith.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text("With ${joinedWith.joinToString(" and ")}", color = Crossed, fontSize = 12.sp)
            }
            if (maybeWith.isNotEmpty()) MaybeLine(maybeWith)
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

/**
 * The *maybe* on a row of the Spine (#405): "maybe with Mia". In the meeting's green,
 * because it is a meeting that might have been, but as words beside my node rather than
 * a line that bends to it — a line is a claim, and this is a question.
 */
@Composable
internal fun MaybeLine(names: List<String>) {
    val who = names.joinToString(" and ")
    Spacer(Modifier.height(3.dp))
    Text(
        "maybe with $who",
        color = Crossed.copy(alpha = 0.85f),
        fontSize = 12.sp,
        modifier = Modifier.semantics {
            contentDescription = "Maybe a night shared with $who. Open it to say whether it was."
        },
    )
}

@Composable
internal fun LaneKey(
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
internal val MaxStripWidth = 132.dp

/**
 * The legend's `+ N more` disclosure never appears below this many names (#396): a
 * floor, not a cap — every active Lane is always in the head regardless.
 */
internal const val LegendHeadSize = 6

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
    lanes.indices.filterNot { lanes[it].laneKey in hidden }

/**
 * One order for the whole lane legend: most recently toggled off first, and any
 * active (currently shown) Lane ahead of every hidden one (#396). [hiddenAt] is
 * toggle-off time by setlist.fm username; a username absent from it is active.
 *
 * One sort key, not an active list and a hidden list stitched together — which is
 * what makes [legendSplit]'s head simply the front of this order rather than a
 * second rule.
 */
internal fun legendOrder(lanes: List<Friend>, hiddenAt: Map<String, Long>): List<Friend> =
    lanes.sortedWith(compareByDescending { hiddenAt[it.laneKey] ?: Long.MAX_VALUE })

/**
 * Where the legend's `+ N more` disclosure takes over (#396). The head holds every
 * active Lane — never cut, because losing the group you are actually comparing is
 * the bug this exists to fix — plus the most recently hidden ones, up to [headSize].
 * [headSize] is a floor, not a cap: more active Lanes than that only grow the head.
 *
 * `rest` is a disclosure, never a truncation (#266): every name [legendOrder] puts
 * there is still in it, in the same order, just not drawn until it is opened.
 */
internal fun legendSplit(
    lanes: List<Friend>,
    hiddenAt: Map<String, Long>,
    headSize: Int,
): Pair<List<Friend>, List<Friend>> {
    val ordered = legendOrder(lanes, hiddenAt)
    val activeCount = lanes.count { it.laneKey !in hiddenAt }
    val count = maxOf(headSize, activeCount)
    return ordered.take(count) to ordered.drop(count)
}

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
        if (row.others.any { it.laneKey == f.laneKey }) add(i)
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
    lineOffset(row, lanes.indexOfFirst { it.laneKey == friend.laneKey }, lanes)

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
internal val DumpRowHeight = 96.dp

/**
 * What a **Node** is, in the log's own vocabulary. The three are a real distinction —
 * a **Section** claims one evening in one room, a **Festival** claims an identity — and
 * a dump that flattened them would hide exactly the bug #166 fixed.
 */
internal fun nodeKind(node: TimelineNode): String = when (node) {
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
        "--- ${rows.size} rows, lanes=${lanes.map { it.laneKey }}, " +
            "geometry in dp at laneWidth=${laneWidth.value} rowHeight=${DumpRowHeight.value} ---",
    )
    rows.forEachIndexed { i, row ->
        val where = lanes.joinToString(" ") { f ->
            val lane = hostLane(row, f, lanes)
            "${f.laneKey}@${if (lane == Spine) "spine" else "lane$lane"}"
        }
        Log.d(
            "Woven",
            "${row.date} d${row.depth} ${if (row.mine) "mine" else "theirs"} " +
                "node=${nodeKind(row.node)} " +
                "with=[${row.others.joinToString(",") { it.laneKey }}] " +
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
internal fun LineColour.paint(): Color = when (this) {
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

/**
 * A **Contact**'s **Lane colour**, the one their rail carries: the lane order is the
 * friends list reversed, and a colour is kept by the unfiltered index (#266).
 */
internal fun laneColourOf(friend: Friend, friends: List<Friend>): Color =
    railColor(friends.reversed().indexOfFirst { it.laneKey == friend.laneKey }.coerceAtLeast(0))

/**
 * The merge row (#580): its own row between my Night ([mine]) and the Night of theirs
 * the weave put right below it ([theirs]). Every **Line** runs straight on through it,
 * at the x and in the colour the edge between the two rows already has — the tails of
 * [mine] finished their bend above — and a dashed link runs from my node down to theirs.
 * A link is a question, not a **Crossing**, so it is dashed and in no one's colour.
 *
 * With no [maybes] (the contact light) the row keeps its height and draws the rails
 * alone, so flipping the switch moves nothing.
 */
@Composable
internal fun MergeRow(
    mine: WovenRow,
    theirs: WovenRow,
    lanes: List<Friend>,
    laneWidth: Dp,
    colours: List<Int>,
    maybes: List<MaybeNight>,
    onCompare: (MaybeNight) -> Unit,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).height(IntrinsicSize.Min)) {
        Box(Modifier.width(SpineWidth + laneWidth).fillMaxHeight()) {
            Canvas(Modifier.fillMaxSize()) {
                val h = size.height
                val drawn = rowGeometry(mine, theirs, lanes, laneWidth, h.toDp(), colours)
                drawn.forEach { d ->
                    val x = d.toX.toPx()
                    drawLine(d.colourAhead.paint(), Offset(x, 0f), Offset(x, h), strokeWidth = d.widthAhead.toPx())
                }
                if (maybes.isNotEmpty()) {
                    val from = SpineLineX.toPx()
                    val host = nodeHost(theirs, lanes)
                    val to = drawn.firstOrNull { it.line == host }?.toX?.toPx() ?: from
                    val link = Path().apply {
                        moveTo(from, 0f)
                        cubicTo(from, h * 0.5f, to, h * 0.5f, to, h)
                    }
                    drawPath(
                        link,
                        Ink.copy(alpha = 0.7f),
                        style = Stroke(
                            width = 1.5.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())),
                        ),
                    )
                }
            }
        }
        Column(
            Modifier.padding(end = 18.dp, top = 8.dp, bottom = 8.dp).align(Alignment.CenterVertically),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            maybes.forEach { maybe -> MaybePill(maybe, onClick = { onCompare(maybe) }) }
        }
    }
}

/** "Same night as Mia's? Compare" — the merge row's one control (#580). */
@Composable
internal fun MaybePill(maybe: MaybeNight, onClick: () -> Unit) {
    val label = maybeMergeLabel(maybe)
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Raised)
            .drawBehind {
                val w = 1.dp.toPx()
                drawRoundRect(
                    Muted,
                    topLeft = Offset(w / 2, w / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                    cornerRadius = CornerRadius(22.dp.toPx() - w / 2),
                    style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            }
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(start = 14.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(maybePill(maybe), color = Ink, fontSize = 14.sp, modifier = Modifier.weight(1f, fill = false).clearAndSetSemantics {})
        Text("Compare", color = Amber, fontSize = 14.sp, modifier = Modifier.clearAndSetSemantics {})
    }
}

/**
 * The *maybe*, compared (#580): my Night and theirs side by side — Artist, Date, Venue,
 * City, From — with the rows that disagree lit, and read out row by row with
 * "differs" said, never only shown. Then the one line that says what "Same night"
 * keeps, and the three answers. Nothing is chosen field by field.
 *
 * The one sheet for both places the question is asked: the merge row on the Spine,
 * and the **Room**, where going to share media from a *maybe* Night asks it first
 * ([sharing], story 22 of #405) — which is why the reason is said out loud there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MaybeCompareSheet(
    maybe: MaybeNight,
    theirColour: Color,
    sharing: Boolean,
    onSame: (adopt: Boolean) -> Unit,
    onApart: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val whose = maybeWhose(maybe)
    // "Same night" on a Night of mine typed by hand, against theirs from setlist.fm,
    // asks one more thing: take their entry? Asked, because taking it can't be undone.
    var adopting by remember(maybe) { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Raised,
        contentColor = Ink,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = {
            Box(
                Modifier.padding(top = 12.dp).size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp)).background(LineCol),
            )
        },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (adopting) {
                Text(
                    maybeAdoptQuestion(maybe),
                    fontFamily = Serif,
                    fontSize = 22.sp,
                    color = Ink,
                    modifier = Modifier.asHeading(),
                )
                Text(maybeAdoptLine(maybe), color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onSame(true) },
                        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ground),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("Take it", fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
                    OutlinedButton(
                        onClick = { onSame(false) },
                        border = BorderStroke(1.dp, LineLit),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("Keep mine", color = Ink, fontSize = 15.sp) }
                }
            } else {
                Text(
                    "Were you both at this night?",
                    fontFamily = Serif,
                    fontSize = 22.sp,
                    color = Ink,
                    modifier = Modifier.asHeading(),
                )
                Column {
                    // The column heads are said in every row below, so a reader moving row
                    // by row never has to remember which side is whose.
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clearAndSetSemantics {}) {
                        Spacer(Modifier.width(64.dp))
                        Text("Yours", color = Amber, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(end = 8.dp))
                        Text(whose, color = theirColour, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    }
                    compareMaybe(maybe).forEach { field ->
                        val value = if (field.differs) Amber else Ink
                        Box(Modifier.fillMaxWidth().height(1.dp).background(LineCol))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(if (field.differs) AmberSoft else Color.Transparent)
                                .clearAndSetSemantics { contentDescription = maybeFieldSpoken(maybe, field) }
                                .padding(vertical = 10.dp, horizontal = if (field.differs) 6.dp else 0.dp),
                        ) {
                            Text(field.label, color = Muted, fontSize = 14.sp, modifier = Modifier.width(if (field.differs) 58.dp else 64.dp))
                            Text(field.yours, color = value, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(end = 8.dp))
                            Text(field.theirs, color = value, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
                Text(
                    sameNightLine(maybe) +
                        if (sharing) " You're sharing from this night, so it's worth knowing first." else "",
                    color = Muted,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { if (maybeAdoptable(maybe)) adopting = true else onSame(false) },
                        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ground),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("Same night", fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onApart,
                            border = BorderStroke(1.dp, LineLit),
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) { Text("Not the same", color = Ink, fontSize = 15.sp) }
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) { Text("Not now", color = Muted, fontSize = 15.sp) }
                    }
                }
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
