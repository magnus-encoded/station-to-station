package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.ble.gossipWriteLimit
import io.github.magnusencoded.stationtostation.ble.intoChunks
import io.github.magnusencoded.stationtostation.data.gossip.GossipEnvelope
import io.github.magnusencoded.stationtostation.data.gossip.PublicGossipPass
import io.github.magnusencoded.stationtostation.data.gossip.decodePublicGossipPass
import io.github.magnusencoded.stationtostation.data.gossip.encodePublicGossipPass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a **Pass** is cut up for the wire (#416).
 *
 * These are the radio's own helpers rather than the codec's, and they are a contract with
 * iOS (#417) just as the byte grammar is: both platforms have to cut at the same places or
 * a push arrives truncated. The rest of the wire grammar is asserted in `PublicGossipTest`,
 * against the v2 format that actually crosses; what is left here is framing, which is a
 * property of GATT and not of any one payload version.
 */
class GossipRadioFramingTest {

    private fun envelope(id: String) = GossipEnvelope(
        id = id,
        gigId = "3ba1f9ca",
        scope = "5f2a19cc",
        author = "QUxJQ0U=",
        createdAt = 1_757_019_000_000,
        expiresAt = 1_757_045_000_000,
        kind = "log",
        line = 0,
        text = "Checked in",
        signature = "c2lnbmVk",
    )

    /**
     * A **Pass** is written in pieces and ended by an empty one, because that is the only
     * framing a CoreBluetooth peripheral reads the same way. Reassembly is concatenation,
     * so the pieces have to put the bytes back exactly.
     */
    @Test
    fun `a Pass chunked for the wire reassembles to itself`() {
        val pass = PublicGossipPass("Qk9C", "c2lnbmF0dXJl", List(8) { envelope("id$it") })
        val payload = encodePublicGossipPass(pass)!!
        val chunks = payload.intoChunks(20)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.size <= 20 })
        assertEquals(pass, decodePublicGossipPass(chunks.reduce { a, b -> a + b }))
    }

    /**
     * The regression this exists for: a **Pass** written in `attMtu - 3` pieces is 514 bytes
     * at the 517 the radio requests, and 514 is not a legal attribute value. An iPhone
     * refuses it before its write handler runs, so the push dies with nothing on the other
     * phone to show for it — the failure this test makes impossible to reintroduce silently.
     */
    @Test
    fun `a write is bounded by the attribute limit as well as the MTU`() {
        assertEquals(512, gossipWriteLimit(517))
        assertEquals(512, gossipWriteLimit(1024))
        // Below the crossover the MTU is still the binding constraint, including the 23 every
        // connection starts at before a negotiation has happened.
        assertEquals(20, gossipWriteLimit(23))
        assertEquals(197, gossipWriteLimit(200))
        assertEquals(512, gossipWriteLimit(515))
    }
}
