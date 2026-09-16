package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.gossip.GossipTally
import io.github.magnusencoded.stationtostation.data.gossip.gossipPreferredPeers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The receipt-driven half of `GossipPolicyTest` on the v2 branch (#444); merges back with #462. */
class GossipPeerPreferenceTest {

    @Test
    fun `the tally separates a window that found credit from one that only ranked`() {
        GossipTally.reset()
        try {
            // An empty window is not a window. Counting it would put the ranker's denominator
            // up every scan and make a zero numerator look like a busy night that found nothing.
            GossipTally.ranked(0, 0)
            assertTrue(GossipTally.summary().contains("pick windows=0"))

            GossipTally.ranked(3, 0)
            GossipTally.ranked(2, 1)
            GossipTally.authored()
            // A Pass nothing was owed on is counted once, and it is not a receipt that failed:
            // authored and declined are separate numbers because they are separate stories.
            GossipTally.declined()
            GossipTally.offered(2)
            GossipTally.delivered(1)
            // The one number this whole harness exists for: hits, not windows. Two windows and
            // one hit is a ranker doing something; two windows and zero is a shuffle.
            assertEquals(
                "receipts authored=1 declined=1 offered=2 delivered=1 · pick windows=2 credit hits=1",
                GossipTally.summary(),
            )
        } finally {
            GossipTally.reset()
        }
    }

    @Test
    fun `useful neighbours come first and the ones without credit are still offered`() {
        val seen = listOf("plain-a", "useful-a", "plain-b", "useful-b", "plain-c")
        val credited = { peer: String -> peer.startsWith("useful") }
        val order = gossipPreferredPeers(seen, credited, kotlin.random.Random(7))

        // Preference, not exclusion: both credited peers lead, and every peer seen is still
        // in the list to be tried.
        assertEquals(setOf("useful-a", "useful-b"), order.take(2).toSet())
        assertEquals(seen.toSet(), order.toSet())
        assertEquals(seen.size, order.size)
    }

    @Test
    fun `ties break randomly under a seed, and a seed reproduces its own order`() {
        val seen = List(8) { "peer-$it" }
        val none = { _: String -> false }
        val first = gossipPreferredPeers(seen, none, kotlin.random.Random(1))
        val again = gossipPreferredPeers(seen, none, kotlin.random.Random(1))
        val other = gossipPreferredPeers(seen, none, kotlin.random.Random(2))

        assertEquals(first, again)
        assertNotEquals(first, other)
        // Nothing was invented or dropped on the way through the shuffle.
        assertEquals(seen.toSet(), first.toSet())
    }

    @Test
    fun `nothing seen is nothing to push to`() {
        assertTrue(gossipPreferredPeers(emptyList(), { true }, kotlin.random.Random(0)).isEmpty())
    }
}
