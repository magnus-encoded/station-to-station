package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.data.GOSSIP_CLOCK_SKEW
import io.github.magnusencoded.stationtostation.data.GOSSIP_MAX_BATCH
import io.github.magnusencoded.stationtostation.data.GOSSIP_MAX_EPOCH_SECOND
import io.github.magnusencoded.stationtostation.data.GOSSIP_MAX_LIFETIME
import io.github.magnusencoded.stationtostation.data.GossipCheckIn
import io.github.magnusencoded.stationtostation.data.GossipReject
import io.github.magnusencoded.stationtostation.data.contactKeysOf
import io.github.magnusencoded.stationtostation.data.exchange.signChallenge
import io.github.magnusencoded.stationtostation.data.gossipExpiry
import io.github.magnusencoded.stationtostation.data.gossipMessageId
import io.github.magnusencoded.stationtostation.data.gossipPayload
import io.github.magnusencoded.stationtostation.data.gossipStormGate
import io.github.magnusencoded.stationtostation.data.isSafeGossipId
import io.github.magnusencoded.stationtostation.data.verifyGossipSignature
import io.github.magnusencoded.stationtostation.ui.nightWindow
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gossip storm-gate (#410). No radio, no service, no clock and no keystore — the
 * whole decision is a function of its arguments, which is what lets the part where a
 * mistake is *silent* be asserted without a phone.
 *
 * Signatures here are real ECDSA P-256 over the real canonical payload: the one thing a
 * stub verifier could not catch is a payload that does not actually bind what it claims
 * to bind, and that is the failure this feature cannot afford.
 *
 * Names and ids are invented — this repository is public and real timeline data never
 * enters a fixture.
 */
class GossipStormGateTest {

    private class Identity(val publicKey: String, val privateKey: PrivateKey)

    private fun identity(): Identity {
        val pair = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        return Identity(Base64.getEncoder().encodeToString(pair.public.encoded), pair.private)
    }

    private val now: Instant = Instant.parse("2026-09-04T21:00:00Z")
    private val tonight: Instant = now.plus(Duration.ofHours(8))

    /**
     * Alice checked in, Bob is the **Contact** who handed me the batch, and Carol is
     * somebody else I have met — the smallest set with anyone left to relay to, since a
     * message is never sent back to the peer it came from or on to its own author.
     */
    private val alice = identity()
    private val bob = identity()
    private val carol = identity()
    private val contacts = setOf(alice.publicKey, bob.publicKey, carol.publicKey)

    private fun signed(
        author: Identity,
        gigId: String = "3ba1f9ca",
        checkedInAt: Instant = now.minus(Duration.ofMinutes(10)),
        expiresAt: Instant = tonight,
    ): GossipCheckIn {
        val unsigned = GossipCheckIn(
            messageId = "",
            gigId = gigId,
            checkedInBy = author.publicKey,
            checkedInAt = checkedInAt,
            expiresAt = expiresAt,
            signature = "",
        )
        val signature = signChallenge(gossipPayload(unsigned)!!, author.privateKey)
        return unsigned.copy(
            messageId = gossipMessageId(unsigned)!!,
            signature = Base64.getEncoder().encodeToString(signature),
        )
    }

    @Test
    fun `a signed message from a Contact is accepted and relayed on`() {
        val message = signed(alice)

        val plan = gossipStormGate(emptyMap(), listOf(message), bob.publicKey, now, contacts)

        assertEquals(listOf(message), plan.accepted)
        assertEquals(listOf(message), plan.relay.map { it.message })
        assertEquals(listOf(carol.publicKey), plan.relay.single().to)
        assertEquals(mapOf(message.messageId to tonight), plan.seen)
        assertTrue(plan.rejected.isEmpty())
    }

    @Test
    fun `a message seen once is not relayed again`() {
        val message = signed(alice)

        val first = gossipStormGate(emptyMap(), listOf(message), bob.publicKey, now, contacts)
        val second = gossipStormGate(first.seen, listOf(message), bob.publicKey, now, contacts)

        assertEquals(listOf(message), first.relay.map { it.message })
        assertTrue(second.accepted.isEmpty())
        assertTrue(second.relay.isEmpty())
        assertEquals(listOf(GossipReject.ALREADY_SEEN), second.rejected.map { it.reason })
        // The seen set is unchanged by a re-offer: nothing new to remember.
        assertEquals(first.seen, second.seen)
    }

    @Test
    fun `the same message twice inside one batch is accepted once`() {
        val message = signed(alice)

        val plan = gossipStormGate(
            emptyMap(), listOf(message, message), bob.publicKey, now, contacts,
        )

        assertEquals(listOf(message), plan.accepted)
        assertEquals(1, plan.relay.size)
        assertEquals(listOf(GossipReject.ALREADY_SEEN), plan.rejected.map { it.reason })
    }

    @Test
    fun `an expired message is dropped however new it is`() {
        val stale = signed(alice, expiresAt = now.minusSeconds(1))

        val plan = gossipStormGate(emptyMap(), listOf(stale), bob.publicKey, now, contacts)

        assertTrue(plan.accepted.isEmpty())
        assertTrue(plan.relay.isEmpty())
        assertEquals(listOf(GossipReject.EXPIRED), plan.rejected.map { it.reason })
        // And it never enters the seen set: an expired message needs no memory.
        assertTrue(plan.seen.isEmpty())
    }

    /** The edge itself: expiry is exclusive, matching the night window's own 06:00 line. */
    @Test
    fun `a message expiring exactly now is expired`() {
        val plan = gossipStormGate(
            emptyMap(), listOf(signed(alice, expiresAt = now)), bob.publicKey, now, contacts,
        )

        assertEquals(listOf(GossipReject.EXPIRED), plan.rejected.map { it.reason })
    }

    @Test
    fun `a message whose signature does not verify is rejected outright`() {
        val genuine = signed(alice)
        val forged = genuine.copy(signature = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)))

        val plan = gossipStormGate(emptyMap(), listOf(forged), bob.publicKey, now, contacts)

        assertTrue(plan.accepted.isEmpty())
        assertTrue(plan.relay.isEmpty())
        assertEquals(listOf(GossipReject.BAD_SIGNATURE), plan.rejected.map { it.reason })
        // Nothing forged may occupy a message id: otherwise a forgery poisons the seen
        // set and the genuine message with that id is dropped as a duplicate for ever.
        assertTrue(plan.seen.isEmpty())
    }

    @Test
    fun `an unsigned message is rejected`() {
        val unsigned = signed(alice).copy(signature = "")

        val plan = gossipStormGate(emptyMap(), listOf(unsigned), bob.publicKey, now, contacts)

        assertEquals(listOf(GossipReject.BAD_SIGNATURE), plan.rejected.map { it.reason })
    }

    /** A real key, a real signature over the real payload — and nobody I have met. */
    @Test
    fun `a message from a real key that is not a Contact is rejected`() {
        val stranger = identity()
        val message = signed(stranger)

        val plan = gossipStormGate(emptyMap(), listOf(message), bob.publicKey, now, contacts)

        assertTrue(plan.accepted.isEmpty())
        assertTrue(plan.relay.isEmpty())
        assertEquals(listOf(GossipReject.AUTHOR_NOT_A_CONTACT), plan.rejected.map { it.reason })
    }

    /** The other half of the same rule: bytes only ever arrive along a Contact edge. */
    @Test
    fun `a batch handed over by someone who is not a Contact yields an empty plan`() {
        val stranger = identity()
        val message = signed(alice)

        val plan = gossipStormGate(emptyMap(), listOf(message), stranger.publicKey, now, contacts)

        assertTrue(plan.accepted.isEmpty())
        assertTrue(plan.relay.isEmpty())
        assertTrue(plan.seen.isEmpty())
        assertEquals(listOf(GossipReject.SENDER_NOT_A_CONTACT), plan.rejected.map { it.reason })
    }

    @Test
    fun `a message id that is not the hash of its payload is rejected`() {
        val message = signed(alice)
        // Same signed payload, a new id: unchecked, this is the whole storm — one
        // message re-labelled for ever, never a duplicate, relayed by everyone.
        val relabelled = message.copy(messageId = "0".repeat(64))

        val plan = gossipStormGate(emptyMap(), listOf(relabelled), bob.publicKey, now, contacts)

        assertEquals(listOf(GossipReject.FORGED_ID), plan.rejected.map { it.reason })
    }

    @Test
    fun `a message id is the hash of the payload and nothing else`() {
        val message = signed(alice)

        // Neither the id nor the signature is part of what the id is taken over, so a
        // message can be identified and signed without a circular definition.
        assertEquals(message.messageId, gossipMessageId(message))
        assertEquals(message.messageId, gossipMessageId(message.copy(signature = "different")))
        // Every field that is signed changes it.
        assertNotEquals(message.messageId, gossipMessageId(message.copy(gigId = "other")))
        assertNotEquals(
            message.messageId,
            gossipMessageId(message.copy(checkedInAt = message.checkedInAt.plusSeconds(1))),
        )
        assertNotEquals(
            message.messageId,
            gossipMessageId(message.copy(expiresAt = message.expiresAt.plusSeconds(1))),
        )
        assertNotEquals(message.messageId, gossipMessageId(message.copy(checkedInBy = bob.publicKey)))
    }

    /**
     * The one fact the two platforms cannot renegotiate later: the same envelope has to
     * hash to the same id on both, or a relayed message is a new message on every hop.
     * iOS's twin asserts these exact bytes.
     */
    @Test
    fun `the canonical payload is exactly the agreed bytes`() {
        val message = GossipCheckIn(
            messageId = "",
            gigId = "3ba1f9ca",
            checkedInBy = "AAAA",
            checkedInAt = Instant.ofEpochSecond(1_788_555_600),
            expiresAt = Instant.ofEpochSecond(1_788_584_400),
            signature = "",
        )

        assertEquals(
            "station-to-station/gossip-checkin/1\n3ba1f9ca\nAAAA\n1788555600\n1788584400",
            gossipPayload(message)!!.toString(Charsets.UTF_8),
        )
        // Sub-second precision is not part of the identity, on either platform.
        assertEquals(
            gossipMessageId(message),
            gossipMessageId(message.copy(checkedInAt = message.checkedInAt.plusMillis(400))),
        )
    }

    /**
     * The sub-second part of either time is outside the payload, so a relay can change it
     * without breaking the signature or the id. It must therefore decide nothing.
     */
    @Test
    fun `a sub-second wobble a relay could add decides nothing`() {
        val message = signed(alice)
        val wobbled = message.copy(
            checkedInAt = message.checkedInAt.plusMillis(400),
            expiresAt = message.expiresAt.plusMillis(900),
        )

        val plan = gossipStormGate(emptyMap(), listOf(wobbled), bob.publicKey, now, contacts)

        assertEquals(listOf(wobbled), plan.accepted)
        // The expiry held is the signed one, to the second — not the one the wobble asked for.
        assertEquals(mapOf(message.messageId to tonight), plan.seen)
    }

    @Test
    fun `a check-in dated in the future is rejected`() {
        val ahead = signed(alice, checkedInAt = now.plus(GOSSIP_CLOCK_SKEW).plusSeconds(1))

        val plan = gossipStormGate(emptyMap(), listOf(ahead), bob.publicKey, now, contacts)

        assertEquals(listOf(GossipReject.FUTURE_DATED), plan.rejected.map { it.reason })
    }

    @Test
    fun `a clock a few minutes out is tolerated`() {
        val ahead = signed(alice, checkedInAt = now.plus(GOSSIP_CLOCK_SKEW).minusSeconds(1))

        val plan = gossipStormGate(emptyMap(), listOf(ahead), bob.publicKey, now, contacts)

        assertEquals(listOf(ahead), plan.accepted)
    }

    @Test
    fun `a claimed expiry beyond the gig's own night end is clamped to it`() {
        val nightEnd = now.plus(Duration.ofHours(2))
        val greedy = signed(alice, expiresAt = now.plus(Duration.ofDays(365)))

        val plan = gossipStormGate(
            emptyMap(), listOf(greedy), bob.publicKey, now, contacts,
            nightEndFor = { if (it == greedy.gigId) nightEnd else null },
        )

        assertEquals(listOf(greedy), plan.accepted)
        assertEquals(mapOf(greedy.messageId to nightEnd), plan.seen)
    }

    @Test
    fun `a night that has already ended expires the message whatever it claims`() {
        val greedy = signed(alice, expiresAt = now.plus(Duration.ofDays(365)))

        val plan = gossipStormGate(
            emptyMap(), listOf(greedy), bob.publicKey, now, contacts,
            nightEndFor = { now.minusSeconds(1) },
        )

        assertEquals(listOf(GossipReject.EXPIRED), plan.rejected.map { it.reason })
    }

    /** A gig I have never heard of still cannot buy an unbounded lifetime. */
    @Test
    fun `an unknown gig caps the lifetime at one night from the check-in`() {
        val checkedInAt = now.minus(Duration.ofMinutes(10))
        val greedy = signed(alice, checkedInAt = checkedInAt, expiresAt = now.plus(Duration.ofDays(365)))

        val plan = gossipStormGate(emptyMap(), listOf(greedy), bob.publicKey, now, contacts)

        assertEquals(mapOf(greedy.messageId to checkedInAt.plus(GOSSIP_MAX_LIFETIME)), plan.seen)
    }

    @Test
    fun `an earlier claimed expiry is honoured rather than widened`() {
        val early = now.plus(Duration.ofMinutes(30))
        val message = signed(alice, expiresAt = early)

        val plan = gossipStormGate(
            emptyMap(), listOf(message), bob.publicKey, now, contacts,
            nightEndFor = { now.plus(Duration.ofHours(9)) },
        )

        assertEquals(mapOf(message.messageId to early), plan.seen)
    }

    @Test
    fun `the seen set is pruned as its entries expire`() {
        val message = signed(alice)
        val seen = mapOf(
            "old" to now.minusSeconds(1),
            "ending-now" to now,
            "still-live" to now.plusSeconds(1),
        )

        val plan = gossipStormGate(seen, listOf(message), bob.publicKey, now, contacts)

        assertEquals(
            mapOf("still-live" to now.plusSeconds(1), message.messageId to tonight),
            plan.seen,
        )
    }

    @Test
    fun `relay goes to every other Contact and never back to the sender or the author`() {
        val dave = identity()
        val message = signed(alice)

        val plan = gossipStormGate(
            emptyMap(), listOf(message), bob.publicKey, now,
            contacts = contacts + dave.publicKey,
        )

        assertEquals(listOf(carol.publicKey, dave.publicKey).sorted(), plan.relay.single().to)
    }

    /** Nobody left to tell is not an error, and it is not a relay either. */
    @Test
    fun `a message with nobody to relay it to is accepted and not relayed`() {
        val message = signed(alice)

        val plan = gossipStormGate(
            emptyMap(), listOf(message), bob.publicKey, now,
            contacts = setOf(alice.publicKey, bob.publicKey),
        )

        assertEquals(listOf(message), plan.accepted)
        assertTrue(plan.relay.isEmpty())
    }

    /**
     * The load-bearing distinction: a **Followed line** holds no key, so it can neither
     * author a message that is accepted nor appear in a relay audience. Only a
     * **Contact** — a person whose key arrived in person at an **Exchange** — can.
     */
    @Test
    fun `a Followed line is not in the relay audience and cannot author a message`() {
        val friends = listOf(
            Friend(setlistfm = "alice", publicKey = alice.publicKey),
            Friend(setlistfm = "bob", publicKey = bob.publicKey),
            Friend(setlistfm = "carol", publicKey = carol.publicKey),
            // Followed lines: pulled from setlist.fm, never met, no key. Including a
            // blank one, which is the shape an older cache can hold.
            Friend(setlistfm = "dave"),
            Friend(setlistfm = "erin", publicKey = " "),
        )
        val keys = contactKeysOf(friends)

        assertEquals(setOf(alice.publicKey, bob.publicKey, carol.publicKey), keys)

        val plan = gossipStormGate(emptyMap(), listOf(signed(alice)), bob.publicKey, now, keys)

        assertEquals(listOf(carol.publicKey), plan.relay.single().to)
    }

    @Test
    fun `the same input twice gives the same plan`() {
        val messages = listOf(signed(alice), signed(bob, gigId = "another-gig"))
        val seen = mapOf("older" to now.plusSeconds(60))

        val first = gossipStormGate(seen, messages, bob.publicKey, now, contacts)
        val second = gossipStormGate(seen, messages, bob.publicKey, now, contacts)

        assertEquals(first, second)
        assertEquals(2, first.accepted.size)
    }

    @Test
    fun `a gig id that could escape a directory is refused`() {
        val nasty = signed(alice, gigId = "../../etc/passwd")

        val plan = gossipStormGate(emptyMap(), listOf(nasty), bob.publicKey, now, contacts)

        assertEquals(listOf(GossipReject.MALFORMED), plan.rejected.map { it.reason })
    }

    /**
     * A gig id is ASCII or it is nothing. The two platforms read "alphanumeric" differently
     * once a character leaves ASCII, and a message that propagates through iPhones and dies
     * at every Android hop is the asymmetry the port exists to prevent.
     */
    @Test
    fun `a gig id outside ASCII is refused on both platforms`() {
        assertTrue(isSafeGossipId("3ba1f9ca"))
        assertTrue(isSafeGossipId("a-b_C9"))
        assertTrue(isSafeGossipId("f".repeat(64)))

        assertFalse(isSafeGossipId(""))
        assertFalse(isSafeGossipId("f".repeat(65)))
        // A letter Kotlin and Swift agree is a letter, and one they do not.
        assertFalse(isSafeGossipId("cafe\u0301"))
        assertFalse(isSafeGossipId("gig\uD835\uDFCE"))
        assertFalse(isSafeGossipId("gig.1"))
        assertFalse(isSafeGossipId("gig id"))
    }

    /** Nothing is lost by hitting the limit: it is not remembered, so it can come again. */
    @Test
    fun `one handover is read for at most a batch's worth`() {
        val batch = (1..GOSSIP_MAX_BATCH + 2).map {
            signed(alice, checkedInAt = now.minus(Duration.ofMinutes(it.toLong())))
        }

        val plan = gossipStormGate(emptyMap(), batch, bob.publicKey, now, contacts)

        assertEquals(GOSSIP_MAX_BATCH, plan.accepted.size)
        assertEquals(
            listOf(GossipReject.BATCH_LIMIT, GossipReject.BATCH_LIMIT),
            plan.rejected.map { it.reason },
        )
        assertEquals(GOSSIP_MAX_BATCH, plan.seen.size)

        // The overflow is judged on its merits the next time it is offered.
        val again = gossipStormGate(plan.seen, batch.takeLast(2), bob.publicKey, now, contacts)
        assertEquals(batch.takeLast(2), again.accepted)
    }

    /**
     * A time no envelope could honestly carry. On iOS this is the difference between a
     * rejection and the process trapping on a peer's bytes; both twins refuse the same
     * envelopes so that one cannot relay what the other cannot read.
     */
    @Test
    fun `a time outside the agreed range is not encodable`() {
        val message = signed(alice)

        assertNull(gossipPayload(message.copy(
            expiresAt = Instant.ofEpochSecond(GOSSIP_MAX_EPOCH_SECOND + 1),
        )))
        assertNull(gossipPayload(message.copy(
            checkedInAt = Instant.ofEpochSecond(-GOSSIP_MAX_EPOCH_SECOND - 1),
        )))
        assertEquals(
            listOf(GossipReject.FORGED_ID),
            gossipStormGate(
                emptyMap(),
                listOf(message.copy(expiresAt = Instant.ofEpochSecond(GOSSIP_MAX_EPOCH_SECOND + 1))),
                bob.publicKey, now, contacts,
            ).rejected.map { it.reason },
        )
    }

    /**
     * A peer that hands over five thousand copies of one real message should cost five
     * thousand lookups, not five thousand signature verifications.
     */
    @Test
    fun `a repeat of a message already seen is not verified again`() {
        val message = signed(alice)
        var verifications = 0
        val counting: (GossipCheckIn) -> Boolean = { verifications++; verifyGossipSignature(it) }

        val plan = gossipStormGate(
            emptyMap(), List(50) { message }, bob.publicKey, now, contacts, verify = counting,
        )

        assertEquals(listOf(message), plan.accepted)
        assertEquals(1, verifications)
    }

    /**
     * The expiry a checking-in device stamps is the night window this app already
     * draws — [nightWindow], off [io.github.magnusencoded.stationtostation.ui.NIGHT_ENDS]
     * — and not a second rule that could drift from it.
     */
    @Test
    fun `the expiry a check-in carries is the gig's own night end`() {
        val zone = ZoneId.of("Europe/Oslo")
        val gigDate = LocalDate.of(2026, 9, 4)

        assertEquals(
            nightWindow(gigDate).endInclusive.atZone(zone).toInstant(),
            gossipExpiry(gigDate, zone),
        )
        assertEquals(Instant.parse("2026-09-05T04:00:00Z"), gossipExpiry(gigDate, zone))
    }

    @Test
    fun `one night is the longest a message can live`() {
        assertEquals(Duration.ofHours(30), GOSSIP_MAX_LIFETIME)
    }
}
