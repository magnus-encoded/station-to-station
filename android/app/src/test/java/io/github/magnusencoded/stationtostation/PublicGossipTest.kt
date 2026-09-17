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
    @Test fun exchangeRecognizesAClaimInsideARelayedWitnessWithoutUndoingItsBlock() {
        val card = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val durable = gossipBase64(card.public.encoded)
        val author = gossipBase64(key.public.encoded)
        val binding = "station-to-station/gossip-identity/2\nclaim-scope\n$author".toByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(card.private); update(binding); sign()
        }
        val mask = java.security.MessageDigest.getInstance("SHA-256")
            .digest("station-to-station/gossip-mask/2\n$durable\nclaim-scope".toByteArray())
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(mask, "AES"))
        val claim = GossipEnvelope(gigId = "gig", scope = "claim-scope", author = author,
            createdAt = 1000, expiresAt = 100000, kind = "request",
            attribution = gossipBase64(cipher.iv + cipher.doFinal(signature))).signed { bytes ->
            Signature.getInstance("SHA256withECDSA").run { initSign(key.private); update(bytes); sign() }
        }!!
        val witness = GossipEnvelope(gigId = "gig", scope = "witness-scope", author = durable,
            createdAt = 1500, expiresAt = 100000, kind = "witness", text = claim.record()).signed { bytes ->
            Signature.getInstance("SHA256withECDSA").run { initSign(card.private); update(bytes); sign() }
        }!!
        val state = PublicGossipState()
        assertTrue(state.receive(witness, "relay", 1600))
        state.blocked.add(author)
        state.recognizeContacts(setOf(durable))
        assertEquals(durable, state.recognition[author])
        assertTrue(state.arrivals(setOf("gig")).isEmpty())
        assertEquals(listOf(witness), state.offer("next", 1700))
        state.recognizeContacts(emptySet())
        assertEquals(durable, state.recognition[author])
    }

    @Test fun alignmentRetainsReprisesAndConflictingAuthorOrderWithoutMutatingFacts() {
        val a = listOf(fact("A").copy(line = 0), fact("B").copy(line = 1), fact("A").copy(line = 2))
        val aligned = weaveGossip(listOf("A", "B", "A"), a.reversed())
        assertEquals(listOf("A", "B", "A"), aligned.map { it.text })
        assertEquals(listOf(0, 1, 2), aligned.map { it.base })
        assertTrue(aligned.all { it.facts.size == 1 })
        val disagreeing = a.take(2).mapIndexed { index, f -> f.copy(author = "other", line = 1 - index) }
        val combined = weaveGossip(emptyList(), a + disagreeing)
        for (author in listOf(a.first().author, "other")) {
            assertEquals((a + disagreeing).filter { it.author == author }.sortedBy { it.line }.map { it.text },
                combined.flatMap { it.facts }.filter { it.author == author }.map { it.text })
        }
        assertEquals(5, combined.sumOf { it.facts.size })
    }

    @Test fun durableContactBlockAppliesAfterRecognitionAndSurvivesRestartWithoutStoppingRelay() {
        val envelope = fact()
        val state = PublicGossipState()
        state.receive(envelope, "relay", 2000)
        state.blocked.add("contact-key")
        assertEquals(listOf(envelope), state.project(setOf("gig")))
        state.recognition[envelope.author] = "contact-key"
        val restored = kotlinx.serialization.json.Json.decodeFromString<PublicGossipState>(
            kotlinx.serialization.json.Json.encodeToString(PublicGossipState.serializer(), state))
        assertTrue(restored.project(setOf("gig")).isEmpty())
        assertEquals(listOf(envelope), restored.offer("another-relay", 2001))
        assertTrue(restored.facts.containsKey(envelope.id))
    }

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
    private fun fact(text: String = "Karma Police", at: Long = 1000, kind: String = "log", line: Int = 0): GossipEnvelope {
        val draft = GossipEnvelope(gigId = "gig", scope = "scope", author = Base64.getEncoder().encodeToString(key.public.encoded),
            createdAt = at, expiresAt = 100000, kind = kind, line = if (kind == "log") line else -1, text = text)
        return draft.signed { bytes -> Signature.getInstance("SHA256withECDSA").run { initSign(key.private); update(bytes); sign() } }!!
    }

    @Test fun oneHopControlsRequireTheirAuthorWithoutPoisoningTheStormGate() {
        for (kind in listOf("request", "receipt")) {
            val envelope = fact(text = if (kind == "receipt") "useful-neighbour" else "", kind = kind)
            val receiver = PublicGossipState()
            assertFalse(receiver.receive(envelope, "blind-relay", 2000))
            assertTrue(receiver.seen.isEmpty())
            assertTrue(receiver.useful.isEmpty())
            assertTrue(receiver.receive(envelope, envelope.author, 2001))
            if (kind == "request") assertEquals(listOf(envelope), receiver.facts.values.toList())
            else assertTrue(receiver.facts.isEmpty())
            assertTrue(receiver.held.isEmpty())
            // Credit follows the handle the transport proved, never the one the text names:
            // a receipt from elsewhere must not be able to nominate a third party.
            if (kind == "receipt") {
                assertEquals(setOf(envelope.author), receiver.useful.keys)
                assertFalse(receiver.useful.containsKey("useful-neighbour"))
            }

            val author = PublicGossipState()
            assertTrue(author.receive(envelope, "", 2000, local = true))
            assertFalse(author.receive(envelope, "blind-relay", 2001))
            // A receipt is addressed to the one neighbour it names; anything else is gossiped.
            val recipient = if (kind == "receipt") "useful-neighbour" else "recipient"
            assertEquals(listOf(envelope), author.offer(recipient, 2002))
            if (kind == "receipt") assertTrue(author.offer("somebody-else", 2002).isEmpty())
        }
    }

    /** Stories 35 and 36: who a receipt is for, and when there is not one. Story 37 is not
     * in here — see the comment below and ADR-0022 §3. */
    @Test fun receiptNamesTheDeliveringNeighbourAndOnlyForAPromptlyRecognisedFact() {
        val relay = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val me = gossipBase64(relay.public.encoded)
        val sign: (ByteArray) -> ByteArray? = { bytes ->
            Signature.getInstance("SHA256withECDSA").run { initSign(relay.private); update(bytes); sign() }
        }
        val delivered = fact()
        // Not recognised as a Contact's at receive time, so nothing is owed. This is story 36
        // and *not* story 37: it asserts what the function does with `recognised = false`, which
        // is the argument's contract. Story 37 — that late attribution cannot reach here at all —
        // is a fact about the call graph (one production caller per platform, the receive path)
        // and no call of this function can witness it. ADR-0022 §3 says so plainly.
        assertNull(receiptFor(delivered, "neighbour", false, me, 2000, sign))
        // A blind relay that proved no handle cannot be credited.
        assertNull(receiptFor(delivered, "", true, me, 2000, sign))
        val receipt = requireNotNull(receiptFor(delivered, "neighbour", true, me, 2000, sign))
        assertEquals("receipt", receipt.kind)
        assertEquals("neighbour", receipt.text)
        assertEquals(me, receipt.author)
        assertEquals(-1, receipt.line)
        assertTrue(receipt.valid())
        // Only the neighbour that delivered this Fact directly, so a receipt never begets one.
        assertNull(receiptFor(receipt, "neighbour", true, me, 2000, sign))
    }

    /** Stories 36, 38 and 40: where a receipt may go, and what it may not do on arrival. */
    @Test fun receiptReachesOnlyItsNeighbourOnARelaySignedPassAndRetiresNothing() {
        val relay = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val me = gossipBase64(relay.public.encoded)
        val sign: (ByteArray) -> ByteArray? = { bytes ->
            Signature.getInstance("SHA256withECDSA").run { initSign(relay.private); update(bytes); sign() }
        }
        val carried = fact()
        val receipt = requireNotNull(receiptFor(carried, "neighbour", true, me, 2000, sign))
        val state = PublicGossipState()
        assertTrue(state.receive(carried, "neighbour", 2000))
        assertTrue(state.receive(receipt, "", 2000, local = true))
        // Never a durable assertion, and the Fact it is about is untouched — still held and
        // still offered to everyone it has not reached.
        assertTrue(state.facts.values.none { it.kind == "receipt" })
        assertEquals(listOf(carried), state.offer("somebody-else", 2001))
        // Credit for the neighbour that delivered it, on this device, from this device's own note.
        assertEquals(2000 + PUBLIC_RECEIPT_MS, state.useful["neighbour"]!!)
        assertEquals(listOf(receipt), state.offer("neighbour", 2001))
        // The Storm gate is exactly where receiving the Fact left it: a second copy still
        // retires it, and authoring a receipt about it changed nothing.
        assertFalse(state.receive(carried, "third-relay", 2001))
        assertTrue(state.offer("somebody-else", 2002).isEmpty())
        // It rides only a Pass signed as the key it was authored under.
        assertTrue(passBatch(listOf(receipt), null, "some-other-key").isEmpty())
        assertEquals(listOf(receipt), passBatch(listOf(receipt), null, me))
        // And at the far end it credits its sender, never the third party its text names.
        val neighbour = PublicGossipState()
        assertTrue(neighbour.receive(receipt, me, 2002))
        assertNull(neighbour.useful["neighbour"])
        assertEquals(setOf(me), neighbour.useful.keys)
        assertTrue(neighbour.facts.isEmpty())
        assertTrue(neighbour.held.isEmpty())
    }

    /**
     * The address namespace. A meeting only ever proves a relay key — the challenge carries
     * nothing else — so a receipt naming the Gig key that happened to sign a Pass names
     * something no peer will equal, and is never delivered and never read.
     */
    @Test fun receiptAddressesTheRelayKeyAMeetingProvesAndNeverTheGigKeyThatSignedThePass() {
        val neighbourRelay = "neighbour-relay-key"
        val neighbourGig = "neighbour-gig-key"
        assertNotEquals(neighbourRelay, neighbourGig)
        fun envelope(author: String, kind: String) = GossipEnvelope(gigId = "gig", scope = "scope",
            author = author, createdAt = 1000, expiresAt = 100000, kind = kind,
            line = if (kind == "log") 0 else -1)
        val log = envelope(neighbourGig, "log")

        // A Pass signed as the relay is addressable; passBatch admits no request onto one.
        val relaySigned = PublicGossipPass(neighbourRelay, "proof", listOf(log))
        assertEquals(neighbourRelay, passRelay(relaySigned))
        // A Pass the receiver would admit a request from was signed as a Gig, and that key is
        // not one this device can ever meet. No receipt is owed rather than an undeliverable one.
        val gigSigned = PublicGossipPass(neighbourGig, "proof",
            listOf(log, envelope(neighbourGig, "request")))
        assertNull(passRelay(gigSigned))
        // Another device's request riding a relay-signed Pass does not make it unaddressable.
        assertEquals(neighbourRelay, passRelay(
            PublicGossipPass(neighbourRelay, "proof", listOf(envelope(neighbourGig, "request")))))

        // And this is why it matters: the Gig key is unreachable at both ends of the design.
        val relay = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val me = gossipBase64(relay.public.encoded)
        val sign: (ByteArray) -> ByteArray? = { bytes ->
            Signature.getInstance("SHA256withECDSA").run { initSign(relay.private); update(bytes); sign() }
        }
        val carried = fact()
        val misaddressed = requireNotNull(receiptFor(carried, neighbourGig, true, me, 2000, sign))
        val state = PublicGossipState()
        assertTrue(state.receive(misaddressed, "", 2000, local = true))
        // The one egress takes the key the challenge proved, so it never matches, and the
        // credit the ranker reads under that same key was never written.
        assertTrue(state.offer(neighbourRelay, 2001).isEmpty())
        assertNull(state.useful[neighbourRelay])
        // Addressed as the meeting will prove it, both halves work.
        val addressed = requireNotNull(receiptFor(carried, neighbourRelay, true, me, 2000, sign))
        val correct = PublicGossipState()
        assertTrue(correct.receive(addressed, "", 2000, local = true))
        assertEquals(listOf(addressed), correct.offer(neighbourRelay, 2001))
        assertEquals(2000 + PUBLIC_RECEIPT_MS, correct.useful[neighbourRelay]!!)
        assertTrue(correct.offer(neighbourGig, 2001).isEmpty())
    }

    /** A whole batch from one neighbour owes one receipt, and it survives to be offered. */
    @Test fun aMultiFactBatchFromOneNeighbourStillLeavesOneReceiptHeldAndOfferable() {
        val relay = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val me = gossipBase64(relay.public.encoded)
        val sign: (ByteArray) -> ByteArray? = { bytes ->
            Signature.getInstance("SHA256withECDSA").run { initSign(relay.private); update(bytes); sign() }
        }
        // Two lines of one log: same author, same gig, same scope. The ordinary case.
        val batch = listOf(fact("Karma Police", line = 0), fact("No Surprises", line = 1))
        assertNotEquals(batch[0].id, batch[1].id)
        val state = PublicGossipState()
        batch.forEach { assertTrue(state.receive(it, "neighbour", 2000)) }
        val receipts = receiptsFor(batch, "neighbour", { true }, me, 2000, sign)
        // Both Facts name the same record, so there was only ever one thing to say.
        assertEquals(1, receipts.size)
        receipts.forEach { assertTrue(state.receive(it, "", 2000, local = true)) }
        // The receipt is actually held, actually offered, and actually credited the neighbour.
        assertEquals(receipts, state.held.values.filter { it.envelope.kind == "receipt" }.map { it.envelope })
        assertEquals(receipts, state.offer("neighbour", 2001))
        assertEquals(2000 + PUBLIC_RECEIPT_MS, state.useful["neighbour"]!!)
        // The rails: no receipt in facts, both Facts still held and still offered onward.
        assertTrue(state.facts.values.none { it.kind == "receipt" })
        assertEquals(batch.size, state.facts.size)
        assertEquals(batch.map { it.id }.toSet(), state.offer("somebody-else", 2001).map { it.id }.toSet())
        // An unrecognised Fact in the batch owes nothing, and does not mask a recognised one.
        assertTrue(receiptsFor(batch, "neighbour", { false }, me, 2000, sign).isEmpty())
        assertEquals(1, receiptsFor(batch, "neighbour", { it.line == 1 }, me, 2000, sign).size)
        // A receipt admitted from the same neighbour carries the record's own gigId, formerIds
        // and scope, so it collides with the Facts on the de-duplication key. It must be gone
        // before the key is taken, or it wins the slot and then owes nothing.
        val theirs = requireNotNull(receiptFor(batch[0], "somebody", true, me, 1999, sign))
        assertEquals(Triple(batch[0].gigId, batch[0].formerIds, batch[0].scope),
            Triple(theirs.gigId, theirs.formerIds, theirs.scope))
        assertEquals(1, receiptsFor(listOf(theirs) + batch, "neighbour", { true }, me, 2000, sign).size)
    }

    /** Story 41: the decay is its own clock, not a slice of the carry window. */
    @Test fun usefulnessDecaysOnItsOwnClockWhileTheEnvelopeIsStillAlive() {
        assertNotEquals(PUBLIC_CARRY_MS, PUBLIC_RECEIPT_MS)
        val me = gossipBase64(key.public.encoded)
        val long = GossipEnvelope(gigId = "gig", scope = "scope", author = me, createdAt = 1000,
            expiresAt = 9_000_000, kind = "receipt", text = "somebody").signed { bytes ->
            Signature.getInstance("SHA256withECDSA").run { initSign(key.private); update(bytes); sign() }
        }!!
        val state = PublicGossipState()
        assertTrue(state.receive(long, me, 2000))
        assertEquals(2000 + PUBLIC_RECEIPT_MS, state.useful[me]!!)
        state.prune(2000 + PUBLIC_RECEIPT_MS - 1)
        assertEquals(setOf(me), state.useful.keys)
        state.prune(2000 + PUBLIC_RECEIPT_MS)
        assertTrue(state.useful.isEmpty())
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

    @Test fun directRequestIsPersistedWitnessedAndProjectedSeparatelyFromSelfAssertion() {
        val witnessKey = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        fun signedBy(pair: java.security.KeyPair, author: String, kind: String, text: String = "") =
            GossipEnvelope(gigId = "gig", scope = "scope-${author.take(6)}",
                author = gossipBase64(pair.public.encoded), createdAt = 1000, expiresAt = 100000,
                kind = kind, text = text).signed { bytes ->
                Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(bytes); sign() }
            }!!
        val request = signedBy(key, "request", "request")
        val local = signedBy(witnessKey, "witness", "request")
        val state = PublicGossipState()
        assertTrue(state.receive(local, "", 1500, local = true))
        assertFalse(state.receive(request, "relay", 1600))
        assertTrue(state.receive(request, request.author, 1601))
        assertEquals(false, state.checkInEvidence(setOf("gig"), request.author).second)
        val witness = requireNotNull(witnessRequest(request, local, 1700) { bytes ->
            Signature.getInstance("SHA256withECDSA").run { initSign(witnessKey.private); update(bytes); sign() }
        })
        assertTrue(state.receive(witness, witness.author, 1701))
        assertEquals(true to true, state.checkInEvidence(setOf("gig"), request.author))
        assertTrue(state.facts.containsKey(request.id))
        assertTrue(state.facts.containsKey(witness.id))
        assertFalse(state.held.containsKey(request.id))
        assertTrue(state.held.containsKey(witness.id))
        // This device witnessed someone else. Its own night is not witnessed by that.
        assertEquals(emptySet<String>(), state.witnessedGigIds())
        val remote = PublicGossipState()
        assertTrue(remote.receive(witness, "blind-relay", 1800))
        assertEquals(listOf(request), remote.arrivals(setOf("gig")))
        assertTrue(remote.arrivals(setOf("unrelated-gig")).isEmpty())
        assertFalse(remote.facts.containsKey(request.id))
        assertTrue(remote.receive(request, request.author, 1801))
        assertEquals(listOf(request), remote.arrivals(setOf("gig")))
        remote.prune(100001)
        assertEquals(listOf(request), remote.arrivals(setOf("gig")))
        remote.blocked.add(request.author)
        assertTrue(remote.arrivals(setOf("gig")).isEmpty())
        assertTrue(remote.facts.containsKey(witness.id))
    }

    @Test fun onlyMyOwnWitnessedClaimProjects() {
        val strangerKey = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        fun signedBy(pair: java.security.KeyPair, kind: String, text: String = "", gigId: String = "gig",
                     formerIds: List<String> = emptyList()) =
            GossipEnvelope(gigId = gigId, formerIds = formerIds, scope = "scope-$kind-${text.length}",
                author = gossipBase64(pair.public.encoded), createdAt = 1000, expiresAt = 100000,
                kind = kind, text = text).signed { bytes ->
                Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(bytes); sign() }
            }!!
        val mine = signedBy(key, "request", formerIds = listOf("local-gig"))
        val state = PublicGossipState()
        assertTrue(state.receive(mine, "", 1500, local = true))
        // Claimed, but nobody has signed for it yet.
        assertEquals(emptySet<String>(), state.witnessedGigIds())
        val witness = requireNotNull(witnessRequest(mine, signedBy(strangerKey, "request"), 1700) { bytes ->
            Signature.getInstance("SHA256withECDSA").run { initSign(strangerKey.private); update(bytes); sign() }
        })
        assertTrue(state.receive(witness, witness.author, 1701))
        // Both the id it carries now and the one it was known by before, so a setlist.fm id
        // arriving after the night still matches the row.
        assertEquals(setOf("gig", "local-gig"), state.witnessedGigIds())
    }

    @Test fun aPassIsSignedForTheRequestItCarriesAndCarriesNoOtherAuthorsRequest() {
        val log = fact().copy(kind = "log")
        val mine = fact().copy(kind = "request", line = -1, author = "me")
        val theirs = fact().copy(kind = "request", line = -1, author = "them")
        // Nothing of mine to prove: sign as the relay, and drop a request I cannot prove
        // rather than spend the Pass on bytes the receiver is bound to reject.
        assertEquals(null, passAuthor(listOf(log, theirs), setOf("me")))
        assertEquals(listOf(log), passBatch(listOf(log, theirs), null, "relay-key"))
        // Mine to prove: sign as its author. Facts still ride along under that key.
        assertEquals(mine, passAuthor(listOf(log, mine, theirs), setOf("me")))
        assertEquals(listOf(log, mine), passBatch(listOf(log, mine, theirs), mine, "me"))
    }

    /**
     * Story 8: a witness means shared presence, so this phone signs one only when it checked
     * into the same **Gig** itself. Both ways of not having done so are here, because they
     * fail for different reasons and only one of them is obvious: never having checked in at
     * all, and having checked into a *different* night while this request arrives.
     */
    @Test fun noWitnessIsSignedWithoutThisPhonesOwnClaimAtTheSameGig() {
        val strangerKey = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        fun signedBy(pair: java.security.KeyPair, gigId: String, scope: String) =
            GossipEnvelope(gigId = gigId, scope = scope, author = gossipBase64(pair.public.encoded),
                createdAt = 1000, expiresAt = 100000, kind = "request").signed { bytes ->
                Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(bytes); sign() }
            }!!
        val signer: (GossipEnvelope) -> ((ByteArray) -> ByteArray?) = { { bytes ->
            Signature.getInstance("SHA256withECDSA").run { initSign(key.private); update(bytes); sign() }
        } }
        val request = signedBy(strangerKey, "gig", "their-scope")

        // Carrying for a night I am not at. A request admitted from its author is still only
        // their claim; nothing about receiving it makes this device able to speak for it.
        val bystander = PublicGossipState()
        assertTrue(bystander.receive(request, request.author, 1500))
        assertNull(bystander.localClaimFor(request))
        assertNull(witnessFor(bystander, request, 1600, signer))

        // Checked in — at the wrong Gig. `sameGig` is the discriminator, and a claim that
        // shares neither a current nor a former id is somebody else's night.
        val elsewhere = PublicGossipState()
        assertTrue(elsewhere.receive(signedBy(key, "other-gig", "my-scope"), "", 1500, local = true))
        assertTrue(elsewhere.receive(request, request.author, 1501))
        assertNull(elsewhere.localClaimFor(request))
        assertNull(witnessFor(elsewhere, request, 1600, signer))

        // Checked in, same night: now there is something to sign with, and the witness
        // embeds the whole signed claim rather than a reference to it.
        val here = PublicGossipState()
        val mine = signedBy(key, "gig", "my-scope")
        assertTrue(here.receive(mine, "", 1500, local = true))
        assertTrue(here.receive(request, request.author, 1501))
        assertEquals(mine, here.localClaimFor(request))
        val witness = requireNotNull(witnessFor(here, request, 1600, signer))
        assertEquals("witness", witness.kind)
        assertEquals(mine.author, witness.author)
        assertEquals(request, decodePublicEnvelope(witness.text))
        assertTrue(here.receive(witness, "", 1601, local = true))
        assertEquals(true to true, here.checkInEvidence(setOf("gig"), request.author))
    }

    /**
     * Story 2: nobody was near enough to witness, and the night is captured anyway.
     *
     * The point is that the absence is not a gate anywhere — the claim is held and offered,
     * the **Log** lines behind it publish, project and travel, and the only thing missing is
     * the second half of [PublicGossipState.checkInEvidence]. A witness strengthens a claim;
     * it never authorises one.
     */
    @Test fun aClaimNobodyWitnessedStillCapturesProjectsAndTravels() {
        val state = PublicGossipState()
        val request = fact(text = "", kind = "request")
        assertTrue(state.receive(request, "", 1500, local = true))
        assertEquals(true to false, state.checkInEvidence(setOf("gig"), request.author))
        assertEquals(emptySet<String>(), state.witnessedGigIds())

        // Capture proceeds. Two Log lines written with no witness in the room are admitted,
        // projected and offered exactly as they would be with one.
        val first = fact("Choke", at = 1600)
        val second = fact("Evolve", at = 1700, line = 1)
        assertTrue(state.receive(first, "", 1601, local = true))
        assertTrue(state.receive(second, "", 1701, local = true))
        assertEquals(listOf(request, first, second), state.project(setOf("gig")))
        assertEquals(setOf(request.id, first.id, second.id),
            state.offer("first-peer-of-the-night", 1800).map { it.id }.toSet())

        // A witness arriving late changes only the second answer. Nothing captured before it
        // is revisited, and nothing was waiting on it.
        val witnessKey = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val theirs = GossipEnvelope(gigId = "gig", scope = "their-scope",
            author = gossipBase64(witnessKey.public.encoded), createdAt = 1000, expiresAt = 100000,
            kind = "request").signed { bytes ->
            Signature.getInstance("SHA256withECDSA").run { initSign(witnessKey.private); update(bytes); sign() }
        }!!
        val witness = requireNotNull(witnessRequest(request, theirs, 1900) { bytes ->
            Signature.getInstance("SHA256withECDSA").run { initSign(witnessKey.private); update(bytes); sign() }
        })
        assertTrue(state.receive(witness, witness.author, 1901))
        assertEquals(true to true, state.checkInEvidence(setOf("gig"), request.author))
        assertEquals(setOf("gig"), state.witnessedGigIds())
        assertEquals(listOf(request, first, second),
            state.project(setOf("gig")).filter { it.author == request.author })
    }
}
