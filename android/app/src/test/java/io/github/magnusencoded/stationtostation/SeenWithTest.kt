package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.gossip.*
import io.github.magnusencoded.stationtostation.ui.seenWithLine
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature

/**
 * The **Seen with** record (#498): who a **Gig**'s **Room** can still name afterwards.
 *
 * Its subject is the *evidence*, not the radio, which is why everything here is
 * [PublicGossipState] and nothing here is a BLE callback. A completed **Pass** is the one thing
 * the transport has to tell it about, through [PublicGossipState.rememberPass]; a **Check-in**
 * it already holds as a **Fact**.
 */
class SeenWithTest {

    /**
     * The whole of the line at once: order, dedup, and the two kinds of evidence side by side.
     *
     * A **Pass** is what puts a device in the count — hearing one of their **Facts** relayed by
     * somebody else does not, and neither does an advertisement, which reaches nothing in here
     * at all. A second **Pass** with the same device moves its place in the order and adds no
     * second entry, because [PublicGossipState.metDevices] is keyed by device.
     */
    @Test fun directPassesOrderTheNamesAndStrangersAreCountedNotNamed() {
        val state = PublicGossipState()
        val ada = pair()
        val bo = pair()
        val adaGig = pair()
        val boGig = pair()
        state.receive(checkIn(adaGig, ada, "ada-scope", at = 1000), key(adaGig), 1100)
        state.receive(checkIn(boGig, bo, "bo-scope", at = 1200), key(boGig), 1300)
        state.recognizeContacts(setOf(key(ada), key(bo)), mapOf(key(ada) to "Ada", key(bo) to "Bo"))
        // Two Facts admitted and nothing met: only a completed Pass writes a device down, and
        // an advertisement — which reaches nothing in here at all — certainly does not.
        assertTrue(state.metDevices.isEmpty())

        state.rememberPass(key(adaGig), emptyList(), "gig", 5_000)
        state.rememberPass(key(boGig), emptyList(), "gig", 6_000)
        state.rememberPass("a-stranger-relay-key", emptyList(), "gig", 7_000)
        // Met again later: Bo moves ahead of nobody new, and nothing is duplicated.
        state.rememberPass(key(adaGig), emptyList(), "gig", 8_000)

        val seen = state.seenWith(setOf("gig"))
        assertEquals(listOf("Ada", "Bo"), seen.named)
        assertEquals(1, seen.others)
        assertEquals(3, state.metDevices["gig"]?.size)
        assertEquals("Seen with Ada, Bo + 1 other", seenWithLine(seen))
    }

    /**
     * A **Contact** known only from a **Check-in** is named and adds nothing to the device count,
     * even when a witness **Carried** the last hop — attribution is the gate, not proximity —
     * and they sort after everyone actually met.
     */
    @Test fun aWitnessCarriedCheckInNamesItsContactWithoutRaisingTheDeviceCount() {
        val state = PublicGossipState()
        val ada = pair()
        val cleo = pair()
        val adaGig = pair()
        val cleoGig = pair()
        val carried = checkIn(cleoGig, cleo, "cleo-scope", at = 2000)
        val witness = GossipEnvelope(gigId = "gig", scope = "witness-scope", author = key(adaGig),
            createdAt = 2100, expiresAt = 100000, kind = "witness", text = carried.record())
            .signed { bytes -> sign(adaGig, bytes) }!!
        assertTrue(state.receive(witness, key(adaGig), 2200))
        state.receive(checkIn(adaGig, ada, "ada-scope", at = 1000), key(adaGig), 1100)
        state.recognizeContacts(setOf(key(ada), key(cleo)),
            mapOf(key(ada) to "Ada", key(cleo) to "Cleo"))
        state.rememberPass(key(adaGig), emptyList(), "gig", 3000)

        val seen = state.seenWith(setOf("gig"))
        assertEquals(listOf("Ada", "Cleo"), seen.named)
        assertEquals(0, seen.others)
        assertEquals("Seen with Ada, Cleo", seenWithLine(seen))
    }

    /**
     * Where a **Pass** lands. Their own signed claim names its night; anything else is this
     * phone's own position, which is the active **Gig**. A **Fact** they were merely **Carrying**
     * for somebody else says nothing about where the device handing it over is standing.
     */
    @Test fun aPassLandsOnTheGigItsOwnClaimNamesOrElseOnTheActiveGig() {
        val state = PublicGossipState()
        val theirs = pair()
        val claim = checkIn(theirs, null, "their-scope", at = 1000, gigId = "their-gig")
        state.rememberPass(key(theirs), listOf(claim), "tonight", 4000)
        assertEquals(setOf("their-gig"), state.metDevices.keys)

        // Someone else's claim in the same batch is Carried, not evidence about the carrier.
        val other = pair()
        val relayed = checkIn(other, null, "other-scope", at = 1000, gigId = "elsewhere")
        state.rememberPass("a-relay-key", listOf(relayed), "tonight", 5000)
        assertEquals(setOf("their-gig", "tonight"), state.metDevices.keys)
        assertEquals(1, state.seenWith(setOf("tonight")).others)
        assertTrue(state.seenWith(setOf("elsewhere")).isEmpty)
    }

    /**
     * Block is admission, not a rewrite of the evening (ADR-0021). Their device was met and is
     * counted; their name is not said. A **Check-in** of theirs that only ever arrived relayed
     * leaves no trace at all, because [PublicGossipState.receive] never stored it.
     */
    @Test fun aBlockedContactIsCountedAnonymouslyWhenMetAndNotAtAllWhenOnlyRelayed() {
        val state = PublicGossipState()
        val blockedCard = pair()
        val blockedGig = pair()
        val awayCard = pair()
        val awayGig = pair()
        state.receive(checkIn(blockedGig, blockedCard, "blocked-scope", at = 1000), key(blockedGig), 1100)
        state.receive(checkIn(awayGig, awayCard, "away-scope", at = 1200), "some-relay", 1300)
        state.recognizeContacts(setOf(key(blockedCard), key(awayCard)),
            mapOf(key(blockedCard) to "Blocked", key(awayCard) to "Away"))
        state.blocked.add(key(blockedCard))
        state.blocked.add(key(awayCard))
        state.rememberPass(key(blockedGig), emptyList(), "gig", 4000)

        val seen = state.seenWith(setOf("gig"))
        assertEquals(emptyList<String>(), seen.named)
        assertEquals(1, seen.others)
        assertEquals("Seen with 1 other", seenWithLine(seen))
    }

    /**
     * The record is not presence. It is written down, it comes back after a restart in the same
     * order, and [PublicGossipState.prune] — the end of the night for everything the transport
     * keeps — does not touch it.
     */
    @Test fun theRecordSurvivesRestartAndOutlivesTheNightsGossip() {
        val state = PublicGossipState()
        val ada = pair()
        val adaGig = pair()
        state.receive(checkIn(adaGig, ada, "ada-scope", at = 1000), key(adaGig), 1100)
        state.recognizeContacts(setOf(key(ada)), mapOf(key(ada) to "Ada"))
        state.rememberPass(key(adaGig), emptyList(), "gig", 2000)
        state.rememberPass("stranger", emptyList(), "gig", 3000)

        val restored = kotlinx.serialization.json.Json.decodeFromString<PublicGossipState>(
            kotlinx.serialization.json.Json.encodeToString(PublicGossipState.serializer(), state))
        restored.prune(1_000_000)
        assertTrue(restored.held.isEmpty())
        val seen = restored.seenWith(setOf("gig"))
        assertEquals(listOf("Ada"), seen.named)
        assertEquals(1, seen.others)
    }

    /**
     * Adoption (#496) renames the night, and the record has to follow without the device being
     * counted once under each id. The union is over device identity, not over map entries.
     */
    @Test fun anAdoptedGigIdSeesOneRecordAndNotTwo() {
        val state = PublicGossipState()
        state.rememberPass("their-relay", emptyList(), "local-id", 1000)
        state.rememberPass("their-relay", emptyList(), "setlist-id", 2000)
        assertEquals(1, state.seenWith(setOf("local-id", "setlist-id")).others)
        assertEquals(1, state.seenWith(setOf("local-id")).others)
    }

    /**
     * The attribution rule: recognition cannot be revoked, so removing a **Contact** does not
     * turn a name this device already learned back into a stranger on an old night.
     */
    @Test fun removingAContactLeavesTheirNameOnTheNightAlreadyRecognised() {
        val state = PublicGossipState()
        val ada = pair()
        val adaGig = pair()
        state.receive(checkIn(adaGig, ada, "ada-scope", at = 1000), key(adaGig), 1100)
        state.recognizeContacts(setOf(key(ada)), mapOf(key(ada) to "Ada"))
        state.rememberPass(key(adaGig), emptyList(), "gig", 2000)
        state.recognizeContacts(emptySet(), emptyMap())

        assertEquals(listOf("Ada"), state.seenWith(setOf("gig")).named)
        assertEquals(0, state.seenWith(setOf("gig")).others)
    }

    /**
     * The known divergence, pinned so nobody "fixes" it into a lie.
     *
     * A **Pass** proves the peer's night-scoped **Gig** key when it carried their own claim and
     * their nightly relay key otherwise, and **Attribution** is exactly the reason nothing links
     * the two: the relay key names nobody, by design (ADR-0021). So a **Contact** met both ways
     * is named once *and* counted once among the others. This device cannot see that they are
     * the same phone, and folding them would be it asserting something it does not know.
     */
    @Test fun aContactMetOnBothKeysIsNamedOnceAndStillCountedOnce() {
        val state = PublicGossipState()
        val ada = pair()
        val adaGig = pair()
        state.receive(checkIn(adaGig, ada, "ada-scope", at = 1000), key(adaGig), 1100)
        state.recognizeContacts(setOf(key(ada)), mapOf(key(ada) to "Ada"))
        state.rememberPass(key(adaGig), emptyList(), "gig", 2000)
        state.rememberPass("adas-nightly-relay-key", emptyList(), "gig", 3000)

        val seen = state.seenWith(setOf("gig"))
        assertEquals(listOf("Ada"), seen.named)
        assertEquals(1, seen.others)
        assertEquals("Seen with Ada + 1 other", seenWithLine(seen))
    }

    private fun pair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

    private fun key(of: KeyPair) = gossipBase64(of.public.encoded)

    private fun sign(of: KeyPair, bytes: ByteArray) =
        Signature.getInstance("SHA256withECDSA").run { initSign(of.private); update(bytes); sign() }

    /** A check-in signed by a nightly Gig key, sealed to [card] when there is one to recognise. */
    private fun checkIn(
        gig: KeyPair,
        card: KeyPair?,
        scope: String,
        at: Long = 1000,
        gigId: String = "gig",
    ): GossipEnvelope {
        val author = key(gig)
        val attribution = card?.let {
            val durable = key(it)
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest("station-to-station/gossip-mask/2\n$durable\n$scope".toByteArray()), "AES"))
            gossipBase64(cipher.iv + cipher.doFinal(
                sign(it, "station-to-station/gossip-identity/2\n$scope\n$author".toByteArray())))
        }.orEmpty()
        return GossipEnvelope(gigId = gigId, scope = scope, author = author, createdAt = at,
            expiresAt = 100000, kind = "request", attribution = attribution)
            .signed { bytes -> sign(gig, bytes) }!!
    }
}
