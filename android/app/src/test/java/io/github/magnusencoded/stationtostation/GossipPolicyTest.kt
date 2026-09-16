package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.gossip.GOSSIP_NEARBY_WINDOW
import io.github.magnusencoded.stationtostation.data.gossip.GOSSIP_PEER_COOLDOWN
import io.github.magnusencoded.stationtostation.data.gossip.gossipGigTonight
import io.github.magnusencoded.stationtostation.data.gossip.gossipNearby
import io.github.magnusencoded.stationtostation.data.gossip.gossipNightEnds
import io.github.magnusencoded.stationtostation.data.gossip.gossipPassDue
import io.github.magnusencoded.stationtostation.data.gossip.gossipPreferredPeers
import io.github.magnusencoded.stationtostation.data.gossip.gossipRelayShouldRun
import io.github.magnusencoded.stationtostation.data.gossip.gossipExpiry
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the radio runs, and how often one peer may speak (#416).
 *
 * The lifecycle is the thing the issue asks to have flagged for review, so it is written
 * down here as cases rather than only as prose: a reviewer who disagrees with one of the
 * three reasons has a test to point at.
 */
class GossipPolicyTest {

    private val zone: ZoneId = ZoneId.of("Europe/Oslo")
    private val gigDate: LocalDate = LocalDate.of(2026, 9, 4)
    private val gigs = mapOf("3ba1f9ca" to gigDate)

    @Test
    fun `only checked in participation starts a radio and completion cannot renew it`() {
        val end = gossipExpiry(gigDate, zone)
        val now = end.minusSeconds(3600)
        fun until(checked: Long? = now.toEpochMilli(), closed: Boolean = false, done: Long? = null) =
            io.github.magnusencoded.stationtostation.data.gossip.gossipParticipationUntil(checked, closed, done, end)
        assertFalse(gossipRelayShouldRun(until(checked = null), now))
        assertTrue(gossipRelayShouldRun(until(), now))
        assertFalse(gossipRelayShouldRun(until(closed = true), now))
        val done = now.toEpochMilli()
        assertTrue(gossipRelayShouldRun(until(closed = true, done = done), now.plusSeconds(1799)))
        assertFalse(gossipRelayShouldRun(until(closed = true, done = done), now.plusSeconds(1800)))
        assertFalse(gossipRelayShouldRun(until(done = done), now.plusSeconds(1800)))
        val log = io.github.magnusencoded.stationtostation.data.StoredLog().completing(true, done)
        assertEquals(done, log.completing(true, done + 500).completedAt)
        val reopened = log.completing(false, done + 1900000)
        assertEquals(end, until(closed = reopened.closed, done = reopened.completedAt))
        assertTrue(gossipRelayShouldRun(until(closed = reopened.closed, done = reopened.completedAt), now.plusSeconds(1900)))
        assertFalse(gossipRelayShouldRun(until(closed = reopened.closed, done = reopened.completedAt), end))
        assertEquals(done + 2000000, reopened.completing(true, done + 2000000).completedAt)
        assertFalse(gossipRelayShouldRun(until(), end))
        assertEquals(end, until(closed = true, done = end.minusSeconds(60).toEpochMilli()))
        assertEquals(null, io.github.magnusencoded.stationtostation.data.gossip.gossipParticipationUntil(
            now.toEpochMilli(), false, null, end, now.toEpochMilli()))
    }

    @Test
    fun `a peer met for the first time is heard immediately`() {
        assertTrue(gossipPassDue(null, Instant.parse("2026-09-04T21:00:00Z")))
    }

    @Test
    fun `a peer may not speak again inside the cooldown`() {
        val last = Instant.parse("2026-09-04T21:00:00Z")

        assertFalse(gossipPassDue(last, last.plus(Duration.ofSeconds(59))))
        assertTrue(gossipPassDue(last, last.plus(GOSSIP_PEER_COOLDOWN)))
    }

    @Test
    fun `a night end is the gig's own, which is what caps a relayed claim`() {
        assertEquals(
            mapOf("3ba1f9ca" to gossipExpiry(gigDate, zone)),
            gossipNightEnds(gigs, zone),
        )
    }

    @Test
    fun `a gig this device has never heard of has no night end to cap it with`() {
        assertTrue(gossipNightEnds(emptyMap(), zone).isEmpty())
    }

    @Test
    fun `the window opens at the start of the day and closes at the end of the night`() {
        val opens = gigDate.atStartOfDay(zone).toInstant()
        val closes = gossipExpiry(gigDate, zone)

        assertFalse(gossipGigTonight(gigs, opens.minusSeconds(1), zone))
        assertTrue(gossipGigTonight(gigs, opens, zone))
        assertTrue(gossipGigTonight(gigs, closes.minusSeconds(1), zone))
        assertFalse(gossipGigTonight(gigs, closes, zone))
    }

    @Test
    fun `an empty timeline has no gig on tonight`() {
        assertFalse(gossipGigTonight(emptyMap(), Instant.parse("2026-09-04T21:00:00Z"), zone))
    }

    @Test
    fun `somebody counts as here until the window since they were last heard from runs out`() {
        val now = Instant.parse("2026-09-04T21:00:00Z")
        val met = now.minus(GOSSIP_NEARBY_WINDOW).plusSeconds(1)
        val gone = now.minus(GOSSIP_NEARBY_WINDOW)

        assertEquals(listOf("here"), gossipNearby(mapOf("here" to met), now))
        assertTrue(gossipNearby(mapOf("gone" to gone), now).isEmpty())
    }

    @Test
    fun `the window outlasts the cooldown, so a pair mid-exchange never blinks out`() {
        // Two phones in a room speak about once a cooldown. If the window were the cooldown,
        // presence would lapse in the gap between every pair of exchanges.
        val now = Instant.parse("2026-09-04T21:00:00Z")
        assertTrue(GOSSIP_NEARBY_WINDOW > GOSSIP_PEER_COOLDOWN)
        assertEquals(
            listOf("mid-exchange"),
            gossipNearby(mapOf("mid-exchange" to now.minus(GOSSIP_PEER_COOLDOWN)), now),
        )
    }

    @Test
    fun `the most recently heard from come first, so a short list shows the likeliest`() {
        val now = Instant.parse("2026-09-04T21:00:00Z")
        val nearby = gossipNearby(
            mapOf(
                "oldest" to now.minusSeconds(200),
                "newest" to now.minusSeconds(5),
                "middle" to now.minusSeconds(100),
            ),
            now,
        )

        assertEquals(listOf("newest", "middle", "oldest"), nearby)
    }

    @Test
    fun `a device that has spoken to nobody reports an empty room`() {
        assertTrue(gossipNearby(emptyMap(), Instant.parse("2026-09-04T21:00:00Z")).isEmpty())
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
