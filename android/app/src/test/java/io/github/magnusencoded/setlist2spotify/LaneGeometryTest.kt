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
        assertFalse(joinedAt(night, lemmy)) // alone is not company
    }

    @Test
    fun `company is green whoever it is with`() {
        assertTrue(joinedAt(row(mine = true, ozzy), ozzy))
        assertTrue(joinedAt(row(mine = false, ozzy, lemmy), lemmy))
        assertFalse(joinedAt(row(mine = true), ozzy))
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
}
