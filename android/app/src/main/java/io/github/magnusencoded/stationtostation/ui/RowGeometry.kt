package io.github.magnusencoded.stationtostation.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.magnusencoded.stationtostation.data.Friend

/**
 * What the Timelines resolution actually draws, as numbers.
 *
 * The whole resolution is geometry — a **Spine** down the page, **Lanes** sliding out
 * beside it, **Lines** bending together at a **Crossing** and peeling away at a
 * **Parting** — and until this existed none of it could be checked except by looking.
 * #69 made *which* **Line** a **Node** sits on one function the canvas calls; this is
 * the same move one step further out, for *where in points*.
 *
 * [rowGeometry] is the one seam. It is arithmetic over the primitives below it
 * ([laneStep], [stripWidth], [laneXf], [linesAt], [nodeHost], [lineOffset]) — it holds
 * no state, touches no canvas, and needs no device. Three consumers read the same
 * value and none recomputes any part of it: the canvas strokes it ([PeopleRails]), the
 * lane geometry suite asserts it, and the woven-row dump prints it ([logWovenRows]).
 *
 * Everything here is in density-independent points. The canvas is the only thing that
 * converts to pixels, which is what makes the same numbers meaningful in a log, in an
 * assertion, and on a platform with a different point system — see `RowGeometry.swift`,
 * which carries these constants term for term.
 */
internal data class DrawnLine(
    /** [Spine] for mine, or a lane index for a friend's. */
    val line: Int,
    /** Where the line enters the row, and where it leaves it into the next one. */
    val x: Dp,
    val toX: Dp,
    /** Where this row's **Node** sits down the row. */
    val nodeY: Dp,
    /**
     * The gap the line leaves at the **Node**'s rim — the node's outer radius when
     * this line was there, and zero when it wasn't. A **Node** is a ring you see
     * through and a line drawn inside one fills it in; a line belonging to nobody
     * present is not that night's business, so it runs straight past without a notch.
     */
    val nodeR: Dp,
    /**
     * How much of the tail is spent bending toward the next row's x. Never longer than
     * the room below the **Node**, or a short row draws its straight stretch backwards
     * before turning.
     */
    val bendLen: Dp,
    /** Was this line at this row. */
    val present: Boolean,
    /** How many **Lines** lie on this one where it runs through the row, and the weight that implies. */
    val people: Int,
    val colour: LineColour,
    val width: Dp,
    /**
     * The same three, for the last stretch of the row — which belongs to the **edge
     * ahead**, and which *every* line gets, not only the ones that bend. Company
     * peeling away leaves a line alone even when that line never moves, so a **Spine**
     * has to stop reading green there too or it claims a **Crossing** that ended.
     */
    val peopleAhead: Int,
    val colourAhead: LineColour,
    val widthAhead: Dp,
)

/**
 * What a stretch of **Line** means, not what it looks like. The canvas resolves a role
 * to a colour; the palette stays where palettes belong, and "more than one **Line**
 * here means meeting green" becomes assertable without graphics in a JVM test.
 */
internal sealed interface LineColour {
    /** More than one **Line** on this stretch: they *are* one line and must read as one. */
    data object Meeting : LineColour

    /** My own **Spine**. Dimmer on a night I wasn't at. */
    data class Mine(val present: Boolean) : LineColour

    /**
     * A friend's **Lane**, by *colour* index — which is their position in the
     * unfiltered lane list, not the lane they are drawn on. The two are the same
     * until someone is hidden; after that the drawn lanes re-pack inward and the
     * colours must not follow them, or hiding one person recolours everyone
     * outside them and a colour you have learned stops meaning a person (#266).
     */
    data class Rail(val colourIndex: Int) : LineColour

    /** A **Line** running past a night nobody on it attended. */
    data object Absent : LineColour
}

/**
 * How much of a row's tail is spent bending toward the next node. A junction belongs
 * to the **edge** between two nodes, not to the sliver above the lower one: with the
 * whole turn crammed into the node's y, a line that steps out for one gig and comes
 * back drew a rounded rectangle instead of parting and rejoining.
 */
internal val EdgeBend = 56.dp

/** One person walking alone, and what each extra one on the same line adds. */
internal val LineWidth = 2.dp
internal val PerPerson = 1.2.dp

/**
 * The x the canvas actually strokes my **Spine** at: half a stroke off [SpineX], the
 * same nudge the node marker in `TimelineItem` uses to centre itself on it.
 */
internal val SpineLineX = SpineX + 1.dp

/**
 * Every **Line** this row draws, in points.
 *
 * [laneWidth] is the strip's current width — the animation runs through here, so a
 * half-open strip is a case with an answer rather than a moment only a screenshot
 * could catch. [rowHeight] is the row's laid-out height; only [DrawnLine.bendLen]
 * depends on it.
 *
 * A **Lane** that has not slid into view yet is absent from the result: the strip does
 * not stroke lines nobody can see.
 *
 * [lanes] is the *visible* lane list ([visibleLanes]), so hiding a person is nothing
 * more than a shorter list — they take no **Lane**, notch no **Node** and are counted
 * into no **Crossing**, with no case written for it anywhere below. [colours] is the
 * other half: the colour index each visible lane keeps, from [laneColours]. Empty
 * means "nobody is hidden", where drawn index and colour index are the same thing.
 */
internal fun rowGeometry(
    row: WovenRow,
    next: WovenRow?,
    lanes: List<Friend>,
    laneWidth: Dp,
    rowHeight: Dp,
    colours: List<Int> = emptyList(),
): List<DrawnLine> {
    val step = laneStep(lanes.size)
    val strip = stripWidth(lanes.size)
    // Openness is scaled by the lane count so the lanes slide out one after another
    // rather than together — which is why a per-line slide exists at all.
    val open = if (strip <= 0.dp) 0f else (laneWidth / strip).coerceIn(0f, 1f) * lanes.size

    // The geometry asks how many nights a node stands for, never what kind it is: a
    // Section and a Festival are one ring of the same size, drawn once (#166).
    val nodeY = if (row.node is TimelineNode.Several) 15.dp else 13.dp
    // The outer radius of whichever node this row draws. A member gig's smaller ring
    // keeps its proportion by having its own radius, not by scaling the gig's.
    val nodeR = when {
        row.node is TimelineNode.Several -> 11.dp
        row.depth > 0 -> 5.dp
        else -> 7.dp
    }

    // Every line is described the same way, mine included. Mine is [Spine] and is only
    // special in that its own lane is the spine — so it never slides, and a crossing I
    // was at happens where I already am.
    val lines = listOf(Spine) + lanes.indices

    // Which colour a drawn lane carries. The one place the two indices are told
    // apart; everything else in this function is geometry and uses the drawn one.
    fun colourOf(line: Int) = colours.getOrElse(line) { line }

    fun slideOf(line: Int) = if (line == Spine) 1f else (open - line).coerceIn(0f, 1f)
    fun xOf(offset: Int, line: Int): Dp = SpineLineX + (laneXf(offset, step) - SpineLineX) * slideOf(line)

    // Was this line at this row? A membership check on the one which-line answer,
    // not another open-coded copy of it.
    fun thereAt(r: WovenRow, line: Int) = linesAt(r, lanes).contains(line)

    // How many lines lie on this one where it runs. Merged lines are one line by
    // definition, so without this two of them draw the same path twice and look
    // exactly like one — the weight is what says how many.
    fun peopleAt(r: WovenRow, line: Int): Int {
        val here = lineOffset(r, line, lanes)
        return lines.count { lineOffset(r, it, lanes) == here && thereAt(r, it) }
            .coerceAtLeast(1)
    }

    // How many travel a bend together: they must share *both* of its ends.
    fun peopleAlong(to: WovenRow?, line: Int): Int {
        if (to == null) return peopleAt(row, line)
        val a = lineOffset(row, line, lanes)
        val b = lineOffset(to, line, lanes)
        return lines.count {
            lineOffset(row, it, lanes) == a && lineOffset(to, it, lanes) == b
        }.coerceAtLeast(1)
    }

    return lines.mapNotNull { line ->
        if (slideOf(line) <= 0f) return@mapNotNull null

        val x = xOf(lineOffset(row, line, lanes), line)
        // A row with no next row leaves where it entered: the end of the spine is
        // defined rather than whatever the loop happened to leave.
        val toX = if (next == null) x else xOf(lineOffset(next, line, lanes), line)
        val here = thereAt(row, line)
        val gap = if (here) nodeR else 0.dp

        val people = peopleAt(row, line)
        val ahead = peopleAlong(next, line)
        DrawnLine(
            line = line,
            x = x,
            toX = toX,
            nodeY = nodeY,
            nodeR = gap,
            bendLen = minOf((rowHeight - nodeY - gap) * 0.8f, EdgeBend).coerceAtLeast(0.dp),
            present = here,
            people = people,
            colour = roleOf(people, here, line, colourOf(line)),
            width = widthOf(people),
            peopleAhead = ahead,
            colourAhead = roleOf(ahead, here, line, colourOf(line)),
            widthAhead = widthOf(ahead),
        )
    }
}

/**
 * Colour follows the geometry rather than the endpoints: where **Lines** lie on top of
 * each other they *are* one **Line** and have to read as one, and where one peels away
 * it is alone again and takes its own colour back. Green comes from the *count*, so a
 * **Crossing** between two friends is green without me being one of them.
 */
private fun roleOf(people: Int, present: Boolean, line: Int, colourIndex: Int): LineColour = when {
    people > 1 -> LineColour.Meeting
    line == Spine -> LineColour.Mine(present)
    present -> LineColour.Rail(colourIndex)
    else -> LineColour.Absent
}

/** Weight says how many walk this stretch together. */
private fun widthOf(people: Int): Dp = LineWidth + PerPerson * (people - 1)

/** Whose line this is, for a log a person reads. */
internal fun lineLabel(line: Int, lanes: List<Friend>): String =
    if (line == Spine) "spine" else "lane$line(${lanes.getOrNull(line)?.setlistfm ?: "?"})"
