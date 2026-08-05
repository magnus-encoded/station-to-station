package io.github.magnusencoded.setlist2spotify

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.magnusencoded.setlist2spotify.data.Friend
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmArtist
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmSetlist
import io.github.magnusencoded.setlist2spotify.ui.DrawnLine
import io.github.magnusencoded.setlist2spotify.ui.LineColour
import io.github.magnusencoded.setlist2spotify.ui.Spine
import io.github.magnusencoded.setlist2spotify.ui.SpineX
import io.github.magnusencoded.setlist2spotify.ui.TimelineNode
import io.github.magnusencoded.setlist2spotify.ui.WovenRow
import io.github.magnusencoded.setlist2spotify.ui.crossingX
import io.github.magnusencoded.setlist2spotify.ui.hostLane
import io.github.magnusencoded.setlist2spotify.ui.laneStep
import io.github.magnusencoded.setlist2spotify.ui.laneXf
import io.github.magnusencoded.setlist2spotify.ui.linesAt
import io.github.magnusencoded.setlist2spotify.ui.nodeHost
import io.github.magnusencoded.setlist2spotify.ui.rowGeometry
import io.github.magnusencoded.setlist2spotify.ui.stripWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which line each person is drawn on at a given row. The merge rule lives here:
 * lines that share a node become one, and my spine is only special in that it
 * never moves to meet anyone.
 *
 * These assertions now cover the code that *draws*: the canvas asks [linesAt] and
 * [nodeHost] directly rather than keeping its own copy of the rule (#69).
 */
class LaneGeometryTest {

    private val ozzy = Friend(setlistfm = "Ozzy", name = "Ozzy")
    private val lemmy = Friend(setlistfm = "Lemmy", name = "Lemmy")

    /** Lane 0 is nearest my spine and belongs to the most recently added friend. */
    private val lanes = listOf(ozzy, lemmy)

    private fun row(mine: Boolean, vararg present: Friend) = WovenRow(
        node = TimelineNode.Concert(FmSetlist(id = "n", artist = FmArtist(name = "A"))),
        mine = mine,
        others = present.toList(),
    )

    @Test
    fun `a friend who wasn't there stays in their own lane`() {
        assertEquals(0, hostLane(row(mine = true), ozzy, lanes))
        assertEquals(1, hostLane(row(mine = true), lemmy, lanes))
    }

    @Test
    fun `a night I was at pulls their line onto my spine`() {
        val night = row(mine = true, ozzy)
        assertEquals(Spine, hostLane(night, ozzy, lanes))
        assertEquals(1, hostLane(night, lemmy, lanes)) // not there, own lane
    }

    @Test
    fun `two friends at a night I missed merge onto the lane nearest my spine`() {
        val night = row(mine = false, ozzy, lemmy)
        assertEquals(0, nodeHost(night, lanes))
        assertEquals(0, hostLane(night, ozzy, lanes))
        assertEquals(0, hostLane(night, lemmy, lanes)) // came to meet the inner lane
    }

    @Test
    fun `one friend alone at a night I missed keeps their own lane`() {
        val night = row(mine = false, lemmy)
        assertEquals(1, nodeHost(night, lanes))
        assertEquals(1, hostLane(night, lemmy, lanes))
        assertFalse(linesAt(night, lanes).size > 1) // alone is not company
    }

    /**
     * Meeting green comes from the *count* of lines on a stretch, not from a boolean
     * about me: a joined run between two friends is green without me being one of them.
     * This is the expression the canvas paints with.
     */
    @Test
    fun `company is green whoever it is with`() {
        assertTrue(linesAt(row(mine = true, ozzy), lanes).size > 1)
        assertTrue(linesAt(row(mine = false, ozzy, lemmy), lanes).size > 1)
        assertFalse(linesAt(row(mine = true), lanes).size > 1)
    }

    @Test
    fun `one parting on the row the other joins is two independent answers`() {
        // Above: I was out with Lemmy. Here: with Ozzy instead.
        val above = row(mine = true, lemmy)
        val here = row(mine = true, ozzy)

        // Ozzy comes in from their lane to my spine.
        assertEquals(0, hostLane(above, ozzy, lanes))
        assertEquals(Spine, hostLane(here, ozzy, lanes))
        // Lemmy leaves my spine for theirs, on the same row. Neither
        // answer depends on the other, which is what the old shared Boolean got wrong.
        assertEquals(Spine, hostLane(above, lemmy, lanes))
        assertEquals(1, hostLane(here, lemmy, lanes))
    }

    @Test
    fun `the strip stops widening once there are enough friends to fill it`() {
        // Few friends: full spacing, strip grows with each one.
        assertTrue(stripWidth(2) < stripWidth(4))
        assertEquals(laneStep(2).value, laneStep(4).value, 0.01f)
        // Many: lanes tighten instead of pushing the timeline off the phone.
        assertEquals(stripWidth(8).value, stripWidth(20).value, 0.01f)
        assertTrue(laneStep(20) < laneStep(8))
    }

    /**
     * The int→points conversion the whole grammar rests on, and the only step in it
     * the collapse does not cover by construction: which line is a whole number, but
     * where that line sits depends on how far the strip has slid open.
     */
    @Test
    fun `the node sits on its host lane when open and on my spine when shut`() {
        val night = row(mine = false, lemmy) // hosted by lane 1
        assertEquals(
            laneXf(nodeHost(night, lanes), laneStep(lanes.size)).value,
            crossingX(night, lanes, stripWidth(lanes.size)).value,
            0.01f,
        )
        assertEquals(SpineX, crossingX(night, lanes, 0.dp))
    }

    // --- The drawn row ---
    //
    // Everything below asserts `rowGeometry`, the one value the canvas strokes, the
    // dump prints and these tests read. It is in points, so there is no density here
    // and nothing renders: the numbers *are* the drawn geometry (#116).

    /** Both lanes fully out. */
    private val fullyOpen = stripWidth(lanes.size)

    /** A row with a line of text on it. Only the tail bend depends on this. */
    private val ordinary = 96.dp

    private fun drawn(
        row: WovenRow,
        next: WovenRow? = null,
        laneWidth: Dp = fullyOpen,
        height: Dp = ordinary,
        over: List<Friend> = lanes,
    ) = rowGeometry(row, next, over, laneWidth, height)

    private fun List<DrawnLine>.line(i: Int) = firstOrNull { it.line == i }
    private fun List<DrawnLine>.at(i: Int) = line(i) ?: error("line $i was not drawn")
    private fun assertDp(expected: Float, actual: Dp) = assertEquals(expected, actual.value, 0.01f)

    private fun festivalRow(mine: Boolean, vararg present: Friend) = WovenRow(
        node = TimelineNode.Festival("Tons of Rock", listOf(FmSetlist(id = "f", artist = FmArtist(name = "A")))),
        mine = mine,
        others = present.toList(),
    )

    /**
     * My line never moves. The strip opening beside it is the whole gesture, so this
     * is the rule most exposed to a refactor of the slide — and the one the spine jog
     * broke once already.
     */
    @Test
    fun `the spine's x is untouched by how far the strip has opened`() {
        listOf(0.dp, 5.dp, 10.dp, 20.dp, 30.dp, fullyOpen).forEach { w ->
            assertDp(SpineX.value, drawn(row(mine = true), laneWidth = w).at(Spine).x)
            assertDp(SpineX.value, drawn(row(mine = false, ozzy), laneWidth = w).at(Spine).x)
        }
    }

    /** A lane still behind the spine is not stroked: nobody could see it. */
    @Test
    fun `a lane that has not slid into view is absent from the drawn set`() {
        assertEquals(listOf(Spine), drawn(row(mine = true), laneWidth = 0.dp).map { it.line })
    }

    /**
     * The strip's openness is scaled by the lane count, so lanes arrive one after
     * another rather than together. Partial openness is therefore its own case, not a
     * point between two endpoints.
     */
    @Test
    fun `lanes slide out one after another as the strip opens`() {
        val step = laneStep(lanes.size)
        val lane0 = laneXf(0, step).value // 45dp
        val lane1 = laneXf(1, step).value // 65dp

        // A quarter open: the first lane is half way out, the second has not started.
        val quarter = drawn(row(mine = true), laneWidth = fullyOpen * 0.25f)
        assertDp(35.5f, quarter.at(0).x) // half way from the spine's stroke to lane 0
        assertNull(quarter.line(1))

        // Half: the first lane has arrived, the second is only now leaving.
        val half = drawn(row(mine = true), laneWidth = fullyOpen * 0.5f)
        assertDp(lane0, half.at(0).x)
        assertNull(half.line(1))

        // Three quarters: the second is half way.
        val most = drawn(row(mine = true), laneWidth = fullyOpen * 0.75f)
        assertDp(lane0, most.at(0).x)
        assertDp(45.5f, most.at(1).x)

        // Fully open: both on their own lanes.
        val all = drawn(row(mine = true))
        assertDp(lane0, all.at(0).x)
        assertDp(lane1, all.at(1).x)
    }

    /**
     * A node is a ring you see through, and a line drawn inside one fills it in. So a
     * line that was there stops at the rim and picks up on the far side.
     */
    @Test
    fun `a line stops at its node's rim and resumes past it`() {
        val d = drawn(row(mine = true)).at(Spine)
        assertTrue(d.present)
        assertTrue(d.nodeR > 0.dp)
        assertTrue("the approach must end above the rim", d.nodeY - d.nodeR > 0.dp)
        assertTrue("the trunk must start below the rim", d.nodeY + d.nodeR < ordinary)
    }

    /** Three radii, three kinds of night — a member gig's smaller ring keeps its proportion. */
    @Test
    fun `the rim gap differs by node kind`() {
        assertDp(7f, drawn(row(mine = true)).at(Spine).nodeR)
        assertDp(5f, drawn(row(mine = true).copy(depth = 1)).at(Spine).nodeR)
        assertDp(11f, drawn(festivalRow(mine = true)).at(Spine).nodeR)

        // And the node itself sits lower on a festival, which is a bigger ring.
        assertDp(13f, drawn(row(mine = true)).at(Spine).nodeY)
        assertDp(15f, drawn(festivalRow(mine = true)).at(Spine).nodeY)
    }

    /** A stranger's lane is not notched by a night they missed. */
    @Test
    fun `a line nobody present is on runs past the node with no rim gap`() {
        val d = drawn(row(mine = true)).at(0) // Ozzy wasn't there
        assertFalse(d.present)
        assertDp(0f, d.nodeR)
        assertEquals(LineColour.Absent, d.colour)
    }

    /**
     * Merged lines are one line by definition, so without the weight two of them
     * stroke the same path twice and look exactly like one. The per-person increment
     * is a stated rule, not a constant that happens to look right at two.
     */
    @Test
    fun `stroke weight says how many walk the stretch together`() {
        val dio = Friend(setlistfm = "Dio", name = "Dio")
        val three = listOf(ozzy, lemmy, dio)
        fun spineWidth(vararg with: Friend) =
            drawn(row(mine = true, *with), over = three).at(Spine).width.value

        assertEquals(2.0f, spineWidth(), 0.01f)
        assertEquals(3.2f, spineWidth(ozzy), 0.01f)
        assertEquals(4.4f, spineWidth(ozzy, lemmy), 0.01f)
        assertEquals(5.6f, spineWidth(ozzy, lemmy, dio), 0.01f)

        assertEquals(1, drawn(row(mine = true), over = three).at(Spine).people)
        assertEquals(4, drawn(row(mine = true, ozzy, lemmy, dio), over = three).at(Spine).people)
    }

    /** Colour is a role, so "more than one line here is meeting green" is testable without graphics. */
    @Test
    fun `colour is a role that follows the geometry`() {
        // Alone on my own line, on a night I was at and one I wasn't.
        assertEquals(LineColour.Mine(present = true), drawn(row(mine = true)).at(Spine).colour)
        assertEquals(LineColour.Mine(present = false), drawn(row(mine = false, lemmy)).at(Spine).colour)
        // A friend alone on their own lane takes their own light.
        assertEquals(LineColour.Rail(1), drawn(row(mine = false, lemmy)).at(1).colour)
        // Company, whoever it is with.
        assertEquals(LineColour.Meeting, drawn(row(mine = true, ozzy)).at(Spine).colour)
    }

    /** Meeting green follows the geometry, not my own presence. */
    @Test
    fun `a crossing between two friends I missed is green`() {
        val night = row(mine = false, ozzy, lemmy)
        val d = drawn(night)
        // They lie on top of each other, so they are one line and must read as one.
        assertDp(d.at(0).x.value, d.at(1).x)
        assertEquals(LineColour.Meeting, d.at(0).colour)
        assertEquals(LineColour.Meeting, d.at(1).colour)
        assertEquals(2, d.at(0).people)
        // I am not on it, and my own line says so rather than borrowing their green.
        assertEquals(LineColour.Mine(present = false), d.at(Spine).colour)
    }

    /**
     * The last stretch of a row belongs to the edge ahead and *every* line gets it,
     * not only the ones that bend — or a spine stays green after its company has left,
     * claiming a crossing that ended.
     */
    @Test
    fun `a parting returns each line to its own colour on the edge ahead`() {
        val together = row(mine = true, ozzy) // out with Ozzy
        val alone = row(mine = true) // and here they leave

        val spine = drawn(together, next = alone).at(Spine)
        assertEquals(LineColour.Meeting, spine.colour) // green through the row itself
        assertEquals(LineColour.Mine(present = true), spine.colourAhead) // and alone below it
        assertEquals(1, spine.peopleAhead)
        assertEquals(2.0f, spine.widthAhead.value, 0.01f)

        val leaving = drawn(together, next = alone).at(0)
        assertEquals(LineColour.Meeting, leaving.colour)
        assertEquals(LineColour.Rail(0), leaving.colourAhead) // takes its own colour back
        assertDp(laneXf(0, laneStep(lanes.size)).value, leaving.toX) // and swings out to its lane

        // The row after the parting: nothing green is left of it.
        drawn(alone).forEach { assertFalse(it.colour == LineColour.Meeting) }
    }

    /** A line may not jump between rows the way the spine jog once did. */
    @Test
    fun `a row's outgoing x is the next row's incoming x`() {
        val a = row(mine = true, ozzy)
        val b = row(mine = false, lemmy)
        val c = row(mine = true)

        listOf(Spine, 0, 1).forEach { line ->
            assertDp(drawn(b, next = c).at(line).x.value, drawn(a, next = b).at(line).toX)
            assertDp(drawn(c).at(line).x.value, drawn(b, next = c).at(line).toX)
        }
    }

    /** The end of the spine is defined, not whatever the loop happened to leave. */
    @Test
    fun `the last row leaves where it entered`() {
        drawn(row(mine = true, ozzy), next = null).forEach { assertDp(it.x.value, it.toX) }
    }

    /**
     * The bend is never longer than the room below the node, or a short row draws its
     * straight stretch backwards before turning.
     */
    @Test
    fun `the tail bend never exceeds the room below the node`() {
        // An ordinary row has room to spare, so the bend is the full edge bend.
        assertDp(56f, drawn(row(mine = true)).at(Spine).bendLen)

        // A short one gives up most of what is left rather than all of it.
        val short = drawn(row(mine = true), height = 30.dp).at(Spine)
        assertDp(8f, short.bendLen) // (30 - 13 - 7) * 0.8
        assertTrue(short.bendLen <= 30.dp - short.nodeY - short.nodeR)

        // Shorter than the node itself: clamped to nothing, never negative.
        assertDp(0f, drawn(row(mine = true), height = 15.dp).at(Spine).bendLen)

        listOf(0.dp, 15.dp, 24.dp, 30.dp, 60.dp, ordinary).forEach { h ->
            val d = drawn(row(mine = true), height = h).at(Spine)
            assertTrue("bend ran backwards at $h", d.bendLen >= 0.dp)
            assertTrue("bend overran the row at $h", d.bendLen <= (h - d.nodeY - d.nodeR).coerceAtLeast(0.dp))
        }
    }

    // --- The shared weave fixtures, through the drawn geometry ---

    /**
     * The nights both platforms already agree on, asserted as points rather than as
     * rows. `two-lines-crossing` is a **Crossing** and the **Parting** after it.
     */
    @Test
    fun `the fixture crossing draws both lines on my spine and parts below it`() {
        val (rows, friends) = WeaveFixture.load("two-lines-crossing")
        val strip = stripWidth(friends.size)
        fun geom(i: Int) = rowGeometry(rows[i], rows.getOrNull(i + 1), friends, strip, ordinary)

        // Row 1 is Sløtface, together. One node, two lines on it, one green stroke
        // twice as heavy as a person walking alone.
        val together = geom(1)
        assertDp(SpineX.value, together.at(Spine).x)
        assertDp(SpineX.value, together.at(0).x)
        assertEquals(LineColour.Meeting, together.at(Spine).colour)
        assertEquals(3.2f, together.at(Spine).width.value, 0.01f)
        assertDp(7f, together.at(0).nodeR)
        // And below it Ozzy leaves for Turnstile, so the edge ahead is nobody's green.
        assertEquals(LineColour.Mine(present = true), together.at(Spine).colourAhead)
        assertEquals(LineColour.Rail(0), together.at(0).colourAhead)

        // Row 2 is Turnstile, theirs. Their node sits on their lane and mine runs past
        // it without a notch, dimmed because I was not there.
        val theirs = geom(2)
        assertDp(laneXf(0, laneStep(friends.size)).value, theirs.at(0).x)
        assertEquals(LineColour.Rail(0), theirs.at(0).colour)
        assertDp(7f, theirs.at(0).nodeR)
        assertDp(SpineX.value, theirs.at(Spine).x)
        assertDp(0f, theirs.at(Spine).nodeR)
        assertEquals(LineColour.Mine(present = false), theirs.at(Spine).colour)
    }

    /**
     * `three-lines-tons-of-rock`: a festival all three of us were at, and above it a
     * gig only one friend was — the case a screenshot once misread as a merge that
     * the data said never happened. Here it is a disagreement between x values, which
     * a test can state directly.
     */
    @Test
    fun `the fixture festival merges three lines and the row below merges none`() {
        val (rows, friends) = WeaveFixture.load("three-lines-tons-of-rock")
        val strip = stripWidth(friends.size)
        val step = laneStep(friends.size)
        fun geom(i: Int) = rowGeometry(rows[i], rows.getOrNull(i + 1), friends, strip, ordinary)

        val festival = geom(1)
        listOf(Spine, 0, 1).forEach {
            assertDp(SpineX.value, festival.at(it).x)
            assertEquals(LineColour.Meeting, festival.at(it).colour)
            assertEquals(3, festival.at(it).people)
            assertEquals(4.4f, festival.at(it).width.value, 0.01f)
            assertDp(15f, festival.at(it).nodeY) // a festival's node sits lower
            assertDp(11f, festival.at(it).nodeR) // and its ring is the biggest
        }

        // The row below: Kvelertak, which only Lemmy was at. Three lines, three x's,
        // nothing merged — and each one carrying exactly one person.
        val kvelertak = geom(2)
        assertDp(SpineX.value, kvelertak.at(Spine).x)
        assertDp(laneXf(0, step).value, kvelertak.at(0).x)
        assertDp(laneXf(1, step).value, kvelertak.at(1).x)
        assertEquals(3, kvelertak.map { it.x }.toSet().size)
        kvelertak.forEach { assertEquals(1, it.people) }
        assertNotNull(kvelertak.line(1))
        assertEquals(LineColour.Rail(0), kvelertak.at(0).colour) // Lemmy, alone on lane 0
        assertEquals(LineColour.Absent, kvelertak.at(1).colour) // Ozzy, past a night he missed
    }
}
