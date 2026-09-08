package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.GOSSIP_MAX_BATCH
import io.github.magnusencoded.stationtostation.data.GossipCheckIn
import io.github.magnusencoded.stationtostation.data.GossipPlan
import io.github.magnusencoded.stationtostation.data.gossip.GossipHeld
import io.github.magnusencoded.stationtostation.data.gossip.decodeGossipHeld
import io.github.magnusencoded.stationtostation.data.gossip.encodeGossipHeld
import io.github.magnusencoded.stationtostation.data.gossip.gossipHold
import io.github.magnusencoded.stationtostation.data.gossip.gossipOutboxFor
import io.github.magnusencoded.stationtostation.data.gossip.pruneGossipHeld
import io.github.magnusencoded.stationtostation.data.gossip.seenFrom
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a device **Carries**, and who it offers it to (#416).
 *
 * The storm-gate answers "may I accept this"; this answers "having accepted it, who still
 * needs to hear it" — which on a radio that hands a whole batch to whoever connects is the
 * only place `GossipRelay.to`'s two exclusions can actually be honoured.
 */
class GossipHeldTest {

    private val alice = "alice-key"
    private val bob = "bob-key"
    private val carol = "carol-key"
    private val now: Instant = Instant.parse("2026-09-04T21:00:00Z")
    private val tonight: Instant = now.plus(Duration.ofHours(7))

    private fun message(
        id: String,
        author: String = alice,
        at: Instant = now.minus(Duration.ofMinutes(10)),
    ) = GossipCheckIn(
        messageId = id,
        gigId = "3ba1f9ca",
        checkedInBy = author,
        checkedInAt = at,
        expiresAt = tonight,
        signature = "c2lnbmVk",
    )

    @Test
    fun `the seen set is derived from what is held, so the two cannot drift`() {
        val held = listOf(
            GossipHeld(message("one"), bob, tonight),
            GossipHeld(message("two"), null, tonight),
        )

        assertEquals(mapOf("one" to tonight, "two" to tonight), seenFrom(held))
    }

    @Test
    fun `an expired entry is forgotten`() {
        val held = listOf(
            GossipHeld(message("live"), bob, now.plusSeconds(1)),
            GossipHeld(message("gone"), bob, now),
        )

        assertEquals(listOf("live"), pruneGossipHeld(held, now).map { it.message.messageId })
    }

    @Test
    fun `holding takes the expiry the gate settled on, not the author's claim`() {
        val message = message("one")
        val capped = now.plus(Duration.ofHours(2))
        val plan = GossipPlan(accepted = listOf(message), seen = mapOf("one" to capped))

        val held = gossipHold(emptyList(), plan, bob).single()

        assertEquals(capped, held.expiry)
        assertEquals(bob, held.arrivedFrom)
    }

    @Test
    fun `my own check-in arrived from nobody`() {
        val message = message("mine", author = carol)
        val plan = GossipPlan(accepted = listOf(message), seen = mapOf("mine" to tonight))

        assertNull(gossipHold(emptyList(), plan, null).single().arrivedFrom)
    }

    @Test
    fun `a plan that accepted nothing changes nothing`() {
        val held = listOf(GossipHeld(message("one"), bob, tonight))

        assertEquals(held, gossipHold(held, GossipPlan(), bob))
    }

    @Test
    fun `an already-held message keeps its original record`() {
        val held = listOf(GossipHeld(message("one"), bob, tonight))
        val plan = GossipPlan(accepted = listOf(message("one")), seen = mapOf("one" to tonight))

        assertEquals(held, gossipHold(held, plan, carol))
    }

    /** `GossipRelay.to`'s two exclusions, re-expressed for a broadcast-shaped transport. */
    @Test
    fun `a message goes back neither to its author nor to the Contact who handed it over`() {
        val held = listOf(
            GossipHeld(message("from-alice", author = alice), bob, tonight),
            GossipHeld(message("from-carol", author = carol), bob, tonight),
            GossipHeld(message("via-carol", author = alice), carol, tonight),
        )

        assertEquals(
            listOf("from-alice", "from-carol"),
            gossipOutboxFor(held, bob, now).map { it.messageId }.sorted(),
        )
        assertEquals(
            listOf("from-alice"),
            gossipOutboxFor(held, carol, now).map { it.messageId },
        )
    }

    @Test
    fun `an expired message is never offered`() {
        val held = listOf(GossipHeld(message("stale"), bob, now))

        assertTrue(gossipOutboxFor(held, carol, now).isEmpty())
    }

    @Test
    fun `the outbox is newest first and capped at the batch limit`() {
        val held = (1..GOSSIP_MAX_BATCH + 10).map { i ->
            GossipHeld(
                message("id%03d".format(i), at = now.minus(Duration.ofMinutes(i.toLong()))),
                bob,
                tonight,
            )
        }

        val outbox = gossipOutboxFor(held.shuffled(), carol, now)

        assertEquals(GOSSIP_MAX_BATCH, outbox.size)
        assertEquals("id001", outbox.first().messageId)
        assertEquals("id%03d".format(GOSSIP_MAX_BATCH), outbox.last().messageId)
    }

    @Test
    fun `what is carried survives a round trip through the store`() {
        val held = listOf(
            GossipHeld(message("one"), bob, tonight),
            GossipHeld(message("mine"), null, tonight),
        )

        assertEquals(held, decodeGossipHeld(encodeGossipHeld(held)))
    }

    @Test
    fun `an unreadable line is dropped, not the whole store`() {
        val held = listOf(GossipHeld(message("one"), bob, tonight))
        val stored = "not\ta\trecord\n${encodeGossipHeld(held)}\n\t\t\t\t\t\t\t"

        assertEquals(held, decodeGossipHeld(stored))
        assertEquals(emptyList<GossipHeld>(), decodeGossipHeld(null))
        assertEquals(emptyList<GossipHeld>(), decodeGossipHeld(""))
    }
}
