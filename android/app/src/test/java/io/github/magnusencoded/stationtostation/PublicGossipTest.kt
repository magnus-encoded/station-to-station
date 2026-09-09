package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.gossip.*
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class PublicGossipTest {
    private val key = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private fun fact(text: String = "Karma Police", at: Long = 1000): GossipEnvelope {
        val draft = GossipEnvelope(gigId = "gig", scope = "scope", author = Base64.getEncoder().encodeToString(key.public.encoded),
            createdAt = at, expiresAt = 100000, kind = "log", line = 0, text = text)
        return draft.signed { bytes -> Signature.getInstance("SHA256withECDSA").run { initSign(key.private); update(bytes); sign() } }!!
    }

    @Test fun strangerCanCarryAndSecondCopyClosesStormGate() {
        val state = PublicGossipState()
        val envelope = fact()
        assertTrue(state.receive(envelope, "stranger", 2000))
        assertEquals(listOf(envelope), state.offer("another-stranger", 2001))
        assertFalse(state.receive(envelope, "third-stranger", 2002))
        assertTrue(state.offer("another-stranger", 2003).isEmpty())
        assertEquals(listOf(envelope), state.facts.values.toList())
    }

    @Test fun blockedAuthorIsStillCarriedAndSuccessfulHandoffIsNotRepeated() {
        val state = PublicGossipState()
        val envelope = fact()
        state.blocked.add(envelope.author)
        state.receive(envelope, "Carol", 2000)
        assertTrue(state.facts.isEmpty())
        assertEquals(listOf(envelope), state.offer("Bob", 2001))
        state.delivered("Bob", listOf(envelope.id))
        assertTrue(state.offer("Bob", 2002).isEmpty())
    }

    @Test fun replacementsConvergeRegardlessOfArrivalOrderAndKeepHistory() {
        val state = PublicGossipState()
        val old = fact("Karma police")
        val corrected = fact("Karma Police", 1100)
        state.receive(corrected, "Carol", 2000)
        state.receive(old, "Dave", 2001)
        assertEquals(listOf(corrected), state.project(setOf("gig")))
        assertEquals(2, state.facts.size)
        assertTrue(state.offer("Erin", 100001).isEmpty())
        assertEquals(listOf(corrected), state.project(setOf("gig")))
    }

    @Test fun alteredPayloadCannotCloseAnExistingStormGate() {
        val state = PublicGossipState()
        val envelope = fact()
        state.receive(envelope, "Carol", 2000)
        assertFalse(state.receive(envelope.copy(text = "forged"), "Dave", 2001))
        assertEquals(listOf(envelope), state.offer("Bob", 2002))
    }
}
