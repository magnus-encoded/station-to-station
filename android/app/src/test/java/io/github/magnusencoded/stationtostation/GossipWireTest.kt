package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.GOSSIP_MAX_EPOCH_SECOND
import io.github.magnusencoded.stationtostation.data.GossipCheckIn
import io.github.magnusencoded.stationtostation.ble.intoChunks
import io.github.magnusencoded.stationtostation.data.gossip.GOSSIP_AUTH_V1
import io.github.magnusencoded.stationtostation.data.gossip.GOSSIP_CHALLENGE_V1
import io.github.magnusencoded.stationtostation.data.gossip.GOSSIP_MAX_WIRE_BYTES
import io.github.magnusencoded.stationtostation.data.gossip.GOSSIP_PASS_V1
import io.github.magnusencoded.stationtostation.data.gossip.GossipChallenge
import io.github.magnusencoded.stationtostation.data.gossip.GossipPass
import io.github.magnusencoded.stationtostation.data.gossip.decodeGossipChallenge
import io.github.magnusencoded.stationtostation.data.gossip.decodeGossipPass
import io.github.magnusencoded.stationtostation.data.gossip.encodeGossipChallenge
import io.github.magnusencoded.stationtostation.data.gossip.encodeGossipPass
import io.github.magnusencoded.stationtostation.data.gossip.gossipAuthPayload
import java.time.Instant
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The **Pass** on the wire (#416).
 *
 * These bytes are a contract with iOS (#417), not an implementation detail, so what is
 * asserted here is the grammar itself — the header, the field order, the separators —
 * rather than only that a round trip survives. A round trip passes just as happily against
 * a format the other platform cannot read.
 */
class GossipWireTest {

    private val alice = "QUxJQ0U="
    private val bob = "Qk9C"
    private val proof = "c2lnbmF0dXJl"

    private fun message(
        id: String = "abc123",
        gigId: String = "3ba1f9ca",
        author: String = alice,
    ) = GossipCheckIn(
        messageId = id,
        gigId = gigId,
        checkedInBy = author,
        checkedInAt = Instant.parse("2026-09-04T20:50:00Z"),
        expiresAt = Instant.parse("2026-09-05T04:00:00Z"),
        signature = "c2lnbmVk",
    )

    @Test
    fun `a Pass survives the round trip`() {
        val pass = GossipPass(bob, proof, listOf(message(), message(id = "def456")))

        assertEquals(pass, decodeGossipPass(encodeGossipPass(pass)))
    }

    @Test
    fun `the grammar is the header, the claim line, then one record per line`() {
        val encoded = String(encodeGossipPass(GossipPass(bob, proof, listOf(message())))!!)
        val lines = encoded.split('\n')

        assertEquals(GOSSIP_PASS_V1, lines[0])
        assertEquals("$bob\t$proof", lines[1])
        assertEquals(
            listOf("abc123", "3ba1f9ca", alice, "1788555000", "1788580800", "c2lnbmVk"),
            lines[2].split('\t'),
        )
        assertEquals(3, lines.size)
    }

    @Test
    fun `times cross as epoch seconds, so the two platforms cannot disagree on a format`() {
        val decoded = decodeGossipPass(encodeGossipPass(GossipPass(bob, proof, listOf(message())))!!)!!

        assertEquals(Instant.parse("2026-09-04T20:50:00Z"), decoded.batch.single().checkedInAt)
    }

    @Test
    fun `the wrong header is not a Pass`() {
        val bytes = "station-to-station/gossip-pass/2\n$bob\t$proof".toByteArray()

        assertNull(decodeGossipPass(bytes))
    }

    @Test
    fun `a Pass with no claim line is refused`() {
        assertNull(decodeGossipPass(GOSSIP_PASS_V1.toByteArray()))
        assertNull(decodeGossipPass("$GOSSIP_PASS_V1\n$bob".toByteArray()))
        assertNull(decodeGossipPass("$GOSSIP_PASS_V1\n\t$proof".toByteArray()))
        assertNull(decodeGossipPass(null))
        assertNull(decodeGossipPass(ByteArray(0)))
    }

    /**
     * The whole reason a bad record is skipped rather than fatal: otherwise any device in
     * the chain can stop a message it dislikes by corrupting the one next to it.
     */
    @Test
    fun `an unreadable record is skipped and the rest of the batch survives`() {
        val encoded = String(
            encodeGossipPass(GossipPass(bob, proof, listOf(message(), message(id = "def456"))))!!,
        )
        val lines = encoded.split('\n')
        val withRubbish = (lines.take(2) + listOf(lines[2], "not\ta\trecord", lines[3]))
            .joinToString("\n")

        val decoded = decodeGossipPass(withRubbish.toByteArray())!!

        assertEquals(listOf("abc123", "def456"), decoded.batch.map { it.messageId })
    }

    @Test
    fun `a peer's decimal cannot become an exception`() {
        val encoded = String(encodeGossipPass(GossipPass(bob, proof, listOf(message())))!!)
        val absurd = encoded.replace("1788555000", "${GOSSIP_MAX_EPOCH_SECOND + 1}")

        assertTrue(decodeGossipPass(absurd.toByteArray())!!.batch.isEmpty())
    }

    @Test
    fun `a field carrying a separator is dropped rather than encoded ambiguously`() {
        val split = message(gigId = "gig\tid")

        assertTrue(encodeGossipPass(GossipPass(bob, proof, listOf(split)))!!.let {
            decodeGossipPass(it)!!.batch.isEmpty()
        })
        assertNull(encodeGossipPass(GossipPass("bo\tb", proof, listOf(message()))))
        assertNull(encodeGossipPass(GossipPass(bob, "pro\nof", listOf(message()))))
        assertNull(encodeGossipPass(GossipPass("", proof, listOf(message()))))
    }

    @Test
    fun `an oversized write is refused whole, before anything has been decided`() {
        val huge = ByteArray(GOSSIP_MAX_WIRE_BYTES + 1) { 'x'.code.toByte() }

        assertNull(decodeGossipPass(huge))
        assertNull(
            encodeGossipPass(GossipPass(bob, proof, List(2000) { message(id = "id$it") })),
        )
    }

    @Test
    fun `the possession proof is domain-separated from every other use of the identity key`() {
        val nonce = ByteArray(32) { it.toByte() }
        val payload = String(gossipAuthPayload(nonce))

        assertTrue(payload.startsWith("$GOSSIP_AUTH_V1\n"))
        assertNotEquals(payload, String(nonce))
        assertNotEquals(
            String(gossipAuthPayload(nonce)),
            String(gossipAuthPayload(ByteArray(32) { (it + 1).toByte() })),
        )
    }

    @Test
    fun `a challenge survives the round trip`() {
        val challenge = GossipChallenge(ByteArray(32) { it.toByte() }, listOf("00112233445566aa"))

        assertEquals(challenge, decodeGossipChallenge(encodeGossipChallenge(challenge)))
    }

    /**
     * The grammar itself, because these bytes are the contract with iOS: the header, the
     * base64 nonce, then one hex token per line.
     */
    @Test
    fun `the challenge grammar is the header, the nonce, then one token per line`() {
        val challenge = GossipChallenge(
            ByteArray(32) { it.toByte() },
            listOf("00112233445566aa", "aabbccddeeff0011"),
        )
        val lines = String(encodeGossipChallenge(challenge)).split('\n')

        assertEquals(GOSSIP_CHALLENGE_V1, lines[0])
        assertEquals(Base64.getEncoder().encodeToString(challenge.nonce), lines[1])
        assertEquals(listOf("00112233445566aa", "aabbccddeeff0011"), lines.drop(2))
    }

    /**
     * The listener answers with tokens and never with its own key — the whole reason the
     * challenge is shaped this way. A stable identifier readable by any radio that connects
     * is what ADR-0019 refuses.
     */
    @Test
    fun `a challenge names nobody`() {
        val encoded = String(
            encodeGossipChallenge(
                GossipChallenge(ByteArray(32) { it.toByte() }, listOf("00112233445566aa")),
            ),
        )

        assertFalse(encoded.contains(alice))
        assertFalse(encoded.contains(bob))
    }

    @Test
    fun `a malformed token line is dropped rather than failing the whole challenge`() {
        val bytes = listOf(
            GOSSIP_CHALLENGE_V1,
            Base64.getEncoder().encodeToString(ByteArray(32)),
            "00112233445566aa",
            "not-a-token",
            "AABBCCDDEEFF0011",
        ).joinToString("\n").toByteArray()

        assertEquals(listOf("00112233445566aa"), decodeGossipChallenge(bytes)?.tokens)
    }

    @Test
    fun `a challenge with the wrong header or a short nonce is refused`() {
        val nonce = Base64.getEncoder().encodeToString(ByteArray(32))

        assertNull(decodeGossipChallenge("wrong\n$nonce".toByteArray()))
        assertNull(
            decodeGossipChallenge(
                "$GOSSIP_CHALLENGE_V1\n${Base64.getEncoder().encodeToString(ByteArray(31))}"
                    .toByteArray(),
            ),
        )
        assertNull(decodeGossipChallenge(GOSSIP_CHALLENGE_V1.toByteArray()))
        assertNull(decodeGossipChallenge(null))
    }

    /**
     * A **Pass** is written in pieces and ended by an empty one, because that is the only
     * framing a CoreBluetooth peripheral reads the same way. Reassembly is concatenation,
     * so the pieces have to put the bytes back exactly.
     */
    @Test
    fun `a Pass chunked for the wire reassembles to itself`() {
        val pass = GossipPass(bob, proof, List(8) { message(id = "id$it") })
        val payload = encodeGossipPass(pass)!!
        val chunks = payload.intoChunks(20)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.size <= 20 })
        assertEquals(pass, decodeGossipPass(chunks.reduce { a, b -> a + b }))
    }
}
