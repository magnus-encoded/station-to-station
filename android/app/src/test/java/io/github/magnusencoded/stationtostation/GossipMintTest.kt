package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.exchange.signChallenge
import io.github.magnusencoded.stationtostation.data.gossip.mintGossipCheckIn
import io.github.magnusencoded.stationtostation.data.gossipMessageId
import io.github.magnusencoded.stationtostation.data.gossipStormGate
import io.github.magnusencoded.stationtostation.data.verifyGossipSignature
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Minting this phone's own **Check-in** (#416).
 *
 * The property worth asserting is not "it produces a message" but that a stranger's
 * storm-gate accepts the message it produces. Anything the gate would reject is a message
 * that costs every **Contact** in range a signature verification and buys nothing, so the
 * two have to be checked against each other rather than in isolation.
 */
class GossipMintTest {

    private val pair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()
    private val mine = Base64.getEncoder().encodeToString(pair.public.encoded)
    private val peer = "peer-key"

    private val now: Instant = Instant.parse("2026-09-04T21:00:00Z")
    private val tonight: Instant = now.plus(Duration.ofHours(7))

    private fun mint(
        gigId: String = "3ba1f9ca",
        author: String = mine,
        expiresAt: Instant = tonight,
    ) = mintGossipCheckIn(gigId, author, now, expiresAt) { signChallenge(it, pair.private) }

    @Test
    fun `a minted check-in is one a stranger's storm-gate accepts`() {
        val message = mint()!!

        val plan = gossipStormGate(
            seen = emptyMap(),
            batch = listOf(message),
            from = peer,
            now = now,
            contacts = setOf(mine, peer),
        )

        assertEquals(listOf(message), plan.accepted)
    }

    @Test
    fun `the id is the payload's own digest, so it cannot be chosen`() {
        val message = mint()!!

        assertEquals(gossipMessageId(message.copy(messageId = "", signature = "")), message.messageId)
        assertTrue(verifyGossipSignature(message))
    }

    @Test
    fun `a gig id that could not be propagated safely is never minted`() {
        assertNull(mint(gigId = "gig id with spaces"))
        assertNull(mint(gigId = ""))
        assertNull(mint(gigId = "x".repeat(65)))
    }

    @Test
    fun `an author with no key mints nothing`() {
        assertNull(mint(author = "   "))
    }

    @Test
    fun `a message that is already expired is never minted`() {
        assertNull(mint(expiresAt = now))
        assertNull(mint(expiresAt = now.minusSeconds(1)))
    }

    @Test
    fun `a signer that refuses produces no message rather than an unsigned one`() {
        assertNull(mintGossipCheckIn("3ba1f9ca", mine, now, tonight) { null })
        assertNull(mintGossipCheckIn("3ba1f9ca", mine, now, tonight) { error("keystore is locked") })
    }
}
