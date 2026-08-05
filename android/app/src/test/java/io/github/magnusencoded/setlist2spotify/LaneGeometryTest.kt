package io.github.magnusencoded.setlist2spotify

import androidx.compose.ui.unit.dp
import io.github.magnusencoded.setlist2spotify.data.Friend
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmArtist
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmSetlist
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
import io.github.magnusencoded.setlist2spotify.ui.stripWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
