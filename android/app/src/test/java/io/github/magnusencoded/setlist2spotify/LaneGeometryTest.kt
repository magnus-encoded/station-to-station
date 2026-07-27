package io.github.magnusencoded.setlist2spotify

import io.github.magnusencoded.setlist2spotify.data.Friend
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmArtist
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmSetlist
import io.github.magnusencoded.setlist2spotify.ui.Spine
import io.github.magnusencoded.setlist2spotify.ui.TimelineNode
import io.github.magnusencoded.setlist2spotify.ui.WovenRow
import io.github.magnusencoded.setlist2spotify.ui.hostLane
import io.github.magnusencoded.setlist2spotify.ui.joinedAt
import io.github.magnusencoded.setlist2spotify.ui.laneStep
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
 */
class LaneGeometryTest {

    private val carlitos = Friend(setlistfm = "Carlitos2", name = "Carlitos2")
    private val trummis = Friend(setlistfm = "Trummispojken", name = "Trummispojken")

    /** Lane 0 is nearest my spine and belongs to the most recently added friend. */
    private val lanes = listOf(carlitos, trummis)

    private fun row(mine: Boolean, vararg present: Friend) = WovenRow(
        node = TimelineNode.Concert(FmSetlist(id = "n", artist = FmArtist(name = "A"))),
        mine = mine,
        others = present.toList(),
    )

    @Test
    fun `a friend who wasn't there stays in their own lane`() {
        assertEquals(0, hostLane(row(mine = true), carlitos, lanes))
        assertEquals(1, hostLane(row(mine = true), trummis, lanes))
    }

    @Test
    fun `a night I was at pulls their line onto my spine`() {
        val night = row(mine = true, carlitos)
        assertEquals(Spine, hostLane(night, carlitos, lanes))
        assertEquals(1, hostLane(night, trummis, lanes)) // not there, own lane
    }

    @Test
    fun `two friends at a night I missed merge onto the lane nearest my spine`() {
        val night = row(mine = false, carlitos, trummis)
        assertEquals(0, nodeHost(night, lanes))
        assertEquals(0, hostLane(night, carlitos, lanes))
        assertEquals(0, hostLane(night, trummis, lanes)) // came to meet the inner lane
    }

    @Test
    fun `one friend alone at a night I missed keeps their own lane`() {
        val night = row(mine = false, trummis)
        assertEquals(1, nodeHost(night, lanes))
        assertEquals(1, hostLane(night, trummis, lanes))
        assertFalse(joinedAt(night, trummis)) // alone is not company
    }

    @Test
    fun `company is green whoever it is with`() {
        assertTrue(joinedAt(row(mine = true, carlitos), carlitos))
        assertTrue(joinedAt(row(mine = false, carlitos, trummis), trummis))
        assertFalse(joinedAt(row(mine = true), carlitos))
    }

    @Test
    fun `one parting on the row the other joins is two independent answers`() {
        // Above: I was out with Trummispojken. Here: with Carlitos2 instead.
        val above = row(mine = true, trummis)
        val here = row(mine = true, carlitos)

        // Carlitos2 comes in from their lane to my spine.
        assertEquals(0, hostLane(above, carlitos, lanes))
        assertEquals(Spine, hostLane(here, carlitos, lanes))
        // Trummispojken leaves my spine for theirs, on the same row. Neither
        // answer depends on the other, which is what the old shared Boolean got wrong.
        assertEquals(Spine, hostLane(above, trummis, lanes))
        assertEquals(1, hostLane(here, trummis, lanes))
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
}
