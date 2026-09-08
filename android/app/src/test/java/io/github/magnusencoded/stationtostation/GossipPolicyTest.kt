package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.gossip.GOSSIP_PEER_COOLDOWN
import io.github.magnusencoded.stationtostation.data.gossip.gossipGigTonight
import io.github.magnusencoded.stationtostation.data.gossip.gossipNightEnds
import io.github.magnusencoded.stationtostation.data.gossip.gossipPassDue
import io.github.magnusencoded.stationtostation.data.gossip.gossipRelayShouldRun
import io.github.magnusencoded.stationtostation.data.gossipExpiry
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `with no Contacts there is nothing the radio could do`() {
        assertFalse(gossipRelayShouldRun(contacts = 0, holding = true, gigTonight = true, alwaysRelay = true))
    }

    @Test
    fun `carrying a live message is enough — that is the sender's case`() {
        assertTrue(gossipRelayShouldRun(contacts = 1, holding = true, gigTonight = false, alwaysRelay = false))
    }

    @Test
    fun `a gig on tonight is enough — that is what makes a relay chain possible`() {
        assertTrue(gossipRelayShouldRun(contacts = 1, holding = false, gigTonight = true, alwaysRelay = false))
    }

    @Test
    fun `always carry is opt-in and enough on its own`() {
        assertTrue(gossipRelayShouldRun(contacts = 1, holding = false, gigTonight = false, alwaysRelay = true))
    }

    /** The service stops on its own when the night's messages expire — nothing schedules it. */
    @Test
    fun `a Contact with nothing on and nothing carried does not run a radio`() {
        assertFalse(gossipRelayShouldRun(contacts = 3, holding = false, gigTonight = false, alwaysRelay = false))
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
}
