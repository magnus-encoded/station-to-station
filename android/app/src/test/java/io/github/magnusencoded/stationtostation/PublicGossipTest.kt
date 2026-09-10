package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.gossip.*
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.io.File
import io.github.magnusencoded.stationtostation.data.exchange.verifyChallenge

class PublicGossipTest {
    @Test fun sharedSignedPassVerifiesAndRoundTripsExactly() {
        val dir = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "fixtures/gossip/signed-pass") }.first { it.isDirectory }
        val bytes = File(dir, "pass.txt").readBytes()
        val pass = requireNotNull(decodePublicGossipPass(bytes))
        assertEquals(3, pass.batch.size)
        assertTrue(pass.batch.all { it.valid() })
        assertNotEquals(pass.from, pass.batch.first().author)
        assertTrue(verifyChallenge(File(dir, "proof-payload.txt").readBytes(),
            requireNotNull(gossipUnbase64(pass.proof)), pass.from))
        assertArrayEquals(bytes, encodePublicGossipPass(pass))
        assertEquals(File(dir, "envelope.txt").readText(), pass.batch.first().record())
        assertEquals("Björk — Jóga 🎵", pass.batch.first().text)
        assertEquals("", pass.batch[1].text)
        assertEquals(-1, pass.batch[2].line)
        assertFalse(pass.batch.first().copy(text = "tampered").valid())
    }

    private val key = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    @Test fun publicChallengeProvesTemporaryKeyAndRejectsTampering() {
        val nonce = ByteArray(32) { it.toByte() }
        val author = gossipBase64(key.public.encoded)
        val bytes = requireNotNull(encodePublicGossipChallenge(nonce, author) { payload ->
            Signature.getInstance("SHA256withECDSA").run { initSign(key.private); update(payload); sign() }
        })
        val decoded = requireNotNull(decodePublicGossipChallenge(bytes))
        assertEquals(author, decoded.from)
        assertArrayEquals(nonce, decoded.nonce)
        assertNull(decodePublicGossipChallenge(bytes.toString(Charsets.UTF_8)
            .replace(gossipBase64(nonce), gossipBase64(ByteArray(32))).toByteArray()))
        assertNull(decodePublicGossipChallenge(bytes.toString(Charsets.UTF_8)
            .replace("challenge/2", "challenge/1").toByteArray()))
        assertFalse(verifyChallenge(publicGossipAuthPayload(nonce),
            requireNotNull(gossipUnbase64(bytes.toString(Charsets.UTF_8).substringAfterLast('\n'))), author))
    }
    @Test fun oversizedPassKeepsTheBatchPrefixThatFitsItsActualHeader() {
        val envelope = fact("x".repeat(512))
        val pass = PublicGossipPass("k".repeat(256), "s".repeat(256), List(64) { envelope })
        val bytes = requireNotNull(encodePublicGossipPass(pass))
        val decoded = requireNotNull(decodePublicGossipPass(bytes))
        assertTrue(bytes.size <= GOSSIP_MAX_WIRE_BYTES)
        assertTrue(decoded.batch.size in 1..63)
        assertEquals(pass.batch.take(decoded.batch.size), decoded.batch)
        assertTrue(bytes.size + envelope.record().toByteArray().size + 1 > GOSSIP_MAX_WIRE_BYTES)
        assertNull(encodePublicGossipPass(pass.copy(from = "k".repeat(257))))
    }
    private fun fact(text: String = "Karma Police", at: Long = 1000, kind: String = "log"): GossipEnvelope {
        val draft = GossipEnvelope(gigId = "gig", scope = "scope", author = Base64.getEncoder().encodeToString(key.public.encoded),
            createdAt = at, expiresAt = 100000, kind = kind, line = if (kind == "log") 0 else -1, text = text)
        return draft.signed { bytes -> Signature.getInstance("SHA256withECDSA").run { initSign(key.private); update(bytes); sign() } }!!
    }

    @Test fun oneHopControlsRequireTheirAuthorWithoutPoisoningTheStormGate() {
        for (kind in listOf("request", "receipt")) {
            val envelope = fact(text = "useful-neighbour", kind = kind)
            val receiver = PublicGossipState()
            assertFalse(receiver.receive(envelope, "blind-relay", 2000))
            assertTrue(receiver.seen.isEmpty())
            assertTrue(receiver.useful.isEmpty())
            assertTrue(receiver.receive(envelope, envelope.author, 2001))
            assertTrue(receiver.facts.isEmpty())
            assertTrue(receiver.held.isEmpty())
            if (kind == "receipt") assertTrue(receiver.useful.containsKey("useful-neighbour"))

            val author = PublicGossipState()
            assertTrue(author.receive(envelope, "", 2000, local = true))
            assertFalse(author.receive(envelope, "blind-relay", 2001))
            assertEquals(listOf(envelope), author.offer("recipient", 2002))
        }
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
