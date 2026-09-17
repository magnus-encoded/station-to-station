package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.gossip.*
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import kotlinx.serialization.json.Json

class GossipUpdateTest {
    private fun key(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

    private fun fact(key: KeyPair, gig: String, kind: String, at: Long,
                     former: List<String> = emptyList(), text: String = "", scope: String = "night"): GossipEnvelope =
        GossipEnvelope(gigId = gig, formerIds = former, scope = scope,
            author = gossipBase64(key.public.encoded), createdAt = at, expiresAt = 100_000,
            kind = kind, text = text).signed { bytes ->
            Signature.getInstance("SHA256withECDSA").run { initSign(key.private); update(bytes); sign() }
        }!!

    @Test fun signedUpdateInPassProjectsWitnessAfterRestartWithoutALogLine() {
        val alice = key()
        val bob = key()
        val request = fact(alice, "local-gig", "request", 1000)
        val bobClaim = fact(bob, "local-gig", "request", 1100, scope = "bob-night")
        val witness = requireNotNull(witnessRequest(request, bobClaim, 1200) { bytes ->
            Signature.getInstance("SHA256withECDSA").run { initSign(bob.private); update(bytes); sign() }
        })
        val update = fact(alice, "fm-gig", "update", 1300, listOf("local-gig"))
        assertTrue(update.valid())
        assertEquals(update, decodePublicEnvelope(update.record()))
        val pass = decodePublicGossipPass(encodePublicGossipPass(PublicGossipPass("relay", "proof", passBatch(listOf(update), null, "relay"))))
        assertEquals(listOf(update), pass?.batch)
        val sender = PublicGossipState()
        assertTrue(sender.receive(request, "", 1400, local = true))
        assertTrue(sender.receive(witness, bobClaim.author, 1401))
        assertTrue(sender.receive(update, "", 1402, local = true))
        val receiver = PublicGossipState()
        assertTrue(receiver.receive(request, request.author, 1400))
        assertTrue(receiver.receive(witness, bobClaim.author, 1401))
        assertTrue(receiver.receive(update, "relay", 1402))
        assertFalse(receiver.receive(update, "relay", 1403))
        val restored = Json.decodeFromString<PublicGossipState>(Json.encodeToString(PublicGossipState.serializer(), receiver))
        assertEquals(true to true, restored.checkInEvidence(setOf("fm-gig"), request.author))
        assertEquals(listOf(request), restored.arrivals(setOf("fm-gig")))
        assertTrue(restored.project(setOf("fm-gig")).contains(witness))
        assertEquals(setOf("local-gig", "fm-gig"), sender.witnessedGigIds())
        assertEquals(1, sender.facts.values.count { it.kind == "update" })
        assertTrue(restored.project(setOf("fm-gig")).none { it.kind == "log" })
    }

    @Test fun anotherAuthorsUpdateCannotRelabelAClaim() {
        val alice = key()
        val mallory = key()
        val request = fact(alice, "local-gig", "request", 1000)
        val forgedLink = fact(mallory, "fm-gig", "update", 1300, listOf("local-gig"))
        val state = PublicGossipState()
        assertTrue(state.receive(request, request.author, 1400))
        assertTrue(state.receive(forgedLink, "relay", 1401))
        assertEquals(false to false, state.checkInEvidence(setOf("fm-gig"), request.author))
        assertFalse(state.project(setOf("fm-gig")).contains(request))
    }
}
