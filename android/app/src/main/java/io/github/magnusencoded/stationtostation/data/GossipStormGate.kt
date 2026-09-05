package io.github.magnusencoded.stationtostation.data

import io.github.magnusencoded.stationtostation.data.exchange.verifyChallenge
import io.github.magnusencoded.stationtostation.ui.nightWindow
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import kotlin.math.abs

/**
 * The gossip storm-gate (#410, part of #408): given what this device has already seen, a
 * batch of check-in messages handed over by one peer, and the time, decide what is
 * accepted and what is passed on.
 *
 * Pure, in the sense [contactReconcilePlan] is pure and for the same reasons: no radio,
 * no service, no persistence, no clock and no keystore. The seen-set arrives as an
 * argument and leaves as a value, "now" arrives as an argument, and the Contact keys
 * arrive as an argument — so the decision that must never be wrong can be asserted
 * without a phone, a second phone, and a gig to stand at. The transport issues (#416
 * Android, #417 iOS) call this as it stands and add nothing to it: a rule that grew a
 * second copy inside a BLE service is a rule that will disagree with itself.
 *
 * **The propagation rule this file exists to encode** — from `CONTEXT.md`, where the
 * distinction is spelled out as the ambiguity most likely to become a privacy bug: bytes
 * only ever cross a **Contact** edge. A **Contact** is someone whose public key was
 * handed over in person at an **Exchange**; a **Followed line** is public setlist.fm data
 * that grants nothing and holds no key at all. So a message is accepted only from a
 * Contact ([from]), only when it was signed by a Contact ([GossipCheckIn.checkedInBy]),
 * and is relayed only to Contacts. There is no path in this file by which a Followed line
 * authors, receives or forwards anything — [contactKeysOf] is where that is enforced, and
 * it is enforced by a **Followed line** having no key to offer.
 *
 * **What a relaying device discloses, deliberately. Read this before extending it.** A
 * message reaches anyone beyond the author's own Contacts only by my handing it to my
 * Contacts, who then drop it if the author is nobody they have met. Dropping it is not
 * unseeing it: what Carol receives, having never met Alice, is Alice's **stable identity
 * public key**, the gig, and the minute she arrived. That key is the same one Alice's
 * **Card** carries, so it is not an opaque token — anyone who ever scans Alice's card, or
 * correlates it with a card they already hold, can put a name to every check-in they once
 * relayed, retroactively. This is a real location disclosure across a Contact edge that
 * does not exist, and it is the price of a message travelling more than one hop.
 *
 * It is bounded — by my own Contact list, and by a device that rejects a message never
 * relaying it — and it is what the ADR (#415) has to argue for explicitly. Narrowing it
 * without encryption would need me to know somebody else's Contact list, which is exactly
 * the thing this app has no server to ask; the honest alternative is a payload encrypted
 * per recipient, which nothing here does. Say so out loud rather than describing this as
 * "some key checked in somewhere".
 *
 * **What this does not do: store and forward.** A message is relayed once, when it is
 * first accepted, because "relay only if it has not been seen" is the rule the feature
 * asked for (#408, and this issue's own criteria). A Contact who was out of range at that
 * moment therefore never hears about it from me, however much of the night is left. This
 * function does not stop a transport keeping the message and offering it again later — the
 * seen set governs *acceptance*, not what a radio holds — but no second relay is planned
 * here. Whether that is worth building is a decision for #415 and the transports.
 */

/**
 * The domain separator every gossip payload starts with.
 *
 * A **Contact**'s identity key also answers the LAN reconcile challenge (#265) over bytes
 * of that session's choosing. Prefixing the payload keeps the two uses apart: a signature
 * made here can never be replayed as an answer there, or the reverse, because neither
 * side will ever be asked to sign bytes beginning with the other's prefix. The version is
 * in the string so a later envelope is a different payload rather than an ambiguous one.
 */
const val GOSSIP_PAYLOAD_V1 = "station-to-station/gossip-checkin/1"

/**
 * How far ahead of this device's clock a check-in may be stamped and still be believed.
 *
 * Two phones at the same gig can disagree by a minute or two, and dropping a real arrival
 * over that would be the feature failing at the one moment it exists for. Small on
 * purpose all the same: without any bound, a future-dated check-in would ride
 * [GOSSIP_MAX_LIFETIME] out from a date of the sender's choosing and outlive the night it
 * claims to be about.
 */
val GOSSIP_CLOCK_SKEW: Duration = Duration.ofMinutes(5)

/**
 * The longest a message may live when this device cannot work the gig's night out for
 * itself: one night, measured off the same window [nightWindow] draws rather than a
 * number typed here again.
 *
 * A relay hop is usually a device that has never heard of the gig — that is the whole
 * point of relaying — so it has no night end to check the claim against. It still refuses
 * to hold anything longer than a night could possibly last (00:00 through
 * [io.github.magnusencoded.stationtostation.ui.NIGHT_ENDS] the next morning, so 30 hours),
 * which is what stops one message with a generous `expiresAt` from circulating for ever.
 */
val GOSSIP_MAX_LIFETIME: Duration = LocalDate.of(2000, 1, 1).let {
    Duration.between(it.atStartOfDay(), nightWindow(it).endInclusive)
}

/**
 * The most messages one peer's batch is read for in a single run.
 *
 * Every gate below is under the author's control except membership of my **Contact** list,
 * so a Contact whose phone has been taken over can mint valid check-ins as fast as it can
 * sign them. This does not stop that — see the note on [gossipStormGate] — but it does stop
 * one handover from costing an unbounded number of signature verifications and an unbounded
 * number of seen entries.
 *
 * Nothing is lost by hitting it: the overflow is rejected as [GossipReject.BATCH_LIMIT] and
 * *not* remembered, so the same messages are accepted normally when they are offered again.
 * Generous against real traffic — a night's check-ins among people who have all met each
 * other is a handful, not sixty-four.
 */
const val GOSSIP_MAX_BATCH = 64

/**
 * The furthest from the epoch a check-in may claim to be, in seconds: about the year 33,658.
 *
 * A range check rather than a taste check. On iOS a time is a `Double`, so a peer's decoded
 * `expiresAt` of `1e30`, or a NaN, is a value the platform will happily build and then
 * **trap** on when it is narrowed to an integer — a crash reachable from a peer's bytes.
 * Kotlin cannot reach that, but it applies the same bound so that both twins refuse exactly
 * the same envelopes rather than one refusing what the other relays.
 */
const val GOSSIP_MAX_EPOCH_SECOND = 1_000_000_000_000L

/**
 * One check-in, as it travels.
 *
 * [messageId] is content-addressed: the SHA-256 of [gossipPayload], hex. It is not a name
 * the sender chooses, and [gossipStormGate] recomputes it rather than trusting it — an id
 * a relay could choose freely is a message that is never a duplicate, which is the storm
 * this gate is named for.
 *
 * [gigId] is the gig the check-in is about, in whatever form means the same thing on two
 * people's timelines — setlist.fm's id where the night has one (#28). This file treats it
 * as an opaque key: it is only ever compared, and handed to the caller's own
 * `nightEndFor` lookup.
 *
 * [checkedInBy] is the checking-in **Contact**'s public key, base64 X.509
 * SubjectPublicKeyInfo — the same encoding [Friend.publicKey] holds and the radio carries.
 * [signature] is base64 DER over [gossipPayload], by that key.
 *
 * Times are [Instant]s rather than wall-clock: a relay hop can be in another timezone
 * from the gig, and a local 06:00 would mean a different moment to each of them.
 */
data class GossipCheckIn(
    val messageId: String,
    val gigId: String,
    val checkedInBy: String,
    val checkedInAt: Instant,
    val expiresAt: Instant,
    val signature: String,
)

/** Why a candidate did not get in. Local diagnosis only; nothing is reported to a peer. */
enum class GossipReject {
    /** The peer that handed over the batch is not a **Contact**. Nothing in it is read. */
    SENDER_NOT_A_CONTACT,

    /** A field that could not be an id at all — empty, over-long, or path-shaped. */
    MALFORMED,

    /** Signed by a key I hold no **Exchange** with. A **Followed line** lands here too. */
    AUTHOR_NOT_A_CONTACT,

    /** The id is not the hash of the payload: a re-labelled message, dodging dedup. */
    FORGED_ID,

    /** No valid signature by [GossipCheckIn.checkedInBy] over the payload. */
    BAD_SIGNATURE,

    /** Stamped further ahead than [GOSSIP_CLOCK_SKEW] allows. */
    FUTURE_DATED,

    /** Past its expiry, or past the night it claims to be about. */
    EXPIRED,

    /** Already in the seen set, or earlier in this same batch. */
    ALREADY_SEEN,

    /**
     * Past [GOSSIP_MAX_BATCH] in one handover, and so not read at all. Not remembered
     * either: offer it again and it is judged on its merits.
     */
    BATCH_LIMIT,
}

data class GossipRejected(val messageId: String, val reason: GossipReject)

/**
 * One accepted message and the **Contacts** to pass it to. [to] never contains the peer it
 * arrived from (which would echo it straight back) nor its own author (who plainly knows),
 * and is sorted so that the same input yields the same plan.
 */
data class GossipRelay(val message: GossipCheckIn, val to: List<String>)

data class GossipPlan(
    /** New, unexpired, and signed by a **Contact**: safe to act on. */
    val accepted: List<GossipCheckIn> = emptyList(),
    /** The subset with somebody left to tell, and who to tell. */
    val relay: List<GossipRelay> = emptyList(),
    /** The seen set to keep: message id → the expiry this device will hold it to. */
    val seen: Map<String, Instant> = emptyMap(),
    /** Everything dropped, and why. */
    val rejected: List<GossipRejected> = emptyList(),
)

/**
 * Whether a [GossipCheckIn.gigId] is safe to hold, compare and propagate.
 *
 * Deliberately **stricter than [isSafeMediaId]**, and deliberately not it, though it is the
 * same shape and exists for the same reason (this id reaches a store as a key and could
 * reach a file path). [isSafeMediaId] is Unicode-aware on both platforms in ways that do
 * not agree: Kotlin measures UTF-16 code units and asks `Char.isLetterOrDigit`, Swift
 * measures grapheme clusters and asks `CharacterSet.alphanumerics`, so an astral-plane
 * alphanumeric or a combining mark is accepted by one twin and refused by the other. On a
 * media id that is a nuisance; on a gossip id it is a message that propagates through
 * iPhones and dies at every Android hop, which is the exact asymmetry a ported file exists
 * to prevent.
 *
 * ASCII only, therefore: with no character above 0x7F, code units, scalars, graphemes and
 * bytes are all the same count, and the two implementations cannot read the rule
 * differently. Nothing real is lost — a gig id is a UUID or a setlist.fm id.
 */
fun isSafeGossipId(id: String): Boolean =
    id.isNotEmpty() && id.length <= 64 && id.all {
        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_'
    }

/**
 * The bytes a check-in is signed over and identified by.
 *
 * Newline-separated, with the fields that can hold arbitrary text checked for newlines
 * first: without that, a value could carry the separator and make two different messages
 * encode identically. Times are epoch **seconds** in decimal, which is the one
 * representation that cannot drift between the platforms — no formatter, no locale, no
 * calendar, no timezone.
 *
 * Neither [GossipCheckIn.messageId] nor [GossipCheckIn.signature] is part of it: the id is
 * the hash of this, so it cannot also be inside it, and the signature is over it.
 *
 * Null for anything that cannot be canonically encoded, which [gossipStormGate] treats as
 * a rejection. iOS's twin is `gossipPayload` in `GossipStormGate.swift`, byte for byte.
 */
fun gossipPayload(message: GossipCheckIn): ByteArray? {
    if (message.gigId.isEmpty() || message.checkedInBy.isEmpty()) return null
    if (message.gigId.contains('\n') || message.checkedInBy.contains('\n')) return null
    val checkedInAt = message.checkedInAt.epochSecond
    val expiresAt = message.expiresAt.epochSecond
    if (abs(checkedInAt) > GOSSIP_MAX_EPOCH_SECOND || abs(expiresAt) > GOSSIP_MAX_EPOCH_SECOND) {
        return null
    }
    return listOf(
        GOSSIP_PAYLOAD_V1,
        message.gigId,
        message.checkedInBy,
        checkedInAt.toString(),
        expiresAt.toString(),
    ).joinToString("\n").toByteArray(Charsets.UTF_8)
}

/** The content address of a message: SHA-256 of [gossipPayload], lower-case hex. */
fun gossipMessageId(message: GossipCheckIn): String? {
    val payload = gossipPayload(message) ?: return null
    return MessageDigest.getInstance("SHA-256").digest(payload)
        .joinToString("") { "%02x".format(it) }
}

/**
 * Does [GossipCheckIn.signature] prove that [GossipCheckIn.checkedInBy] wrote exactly this
 * payload?
 *
 * [verifyChallenge] is reused rather than restated: it is the same key material, the same
 * curve and the same "SHA256withECDSA", already tested, and a second signature routine
 * would be a second place to get this wrong. Anything malformed — no signature, a
 * signature that is not base64, a key that is not a key — verifies false rather than
 * throwing, which is the same posture the rest of this app takes towards a peer's bytes.
 */
fun verifyGossipSignature(message: GossipCheckIn): Boolean {
    val payload = gossipPayload(message) ?: return false
    val signature = runCatching { Base64.getDecoder().decode(message.signature) }.getOrNull()
        ?: return false
    return verifyChallenge(payload, signature, message.checkedInBy)
}

/**
 * When a check-in for [gigDate] stops being news: the end of that gig's own night.
 *
 * [nightWindow] is the app's one answer to "is this night still going on", drawn off
 * `NIGHT_ENDS` — a show that ends at 01:30 is still that night — and this reuses it rather
 * than inventing a second expiry rule that could drift from what the **Room** believes.
 */
fun gossipExpiry(gigDate: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Instant =
    nightWindow(gigDate).endInclusive.atZone(zone).toInstant()

/**
 * The keys of everyone I have actually met: the gossip channel's whole audience.
 *
 * Holding a key is exactly what makes a **Contact** — only the radio ever fills
 * [Friend.publicKey] in, in person, and a link or QR code can never carry one (#271). So a
 * **Followed line** simply has nothing to put in this set, which is how "never a Followed
 * line" is enforced here: not by a check that could be forgotten, but by there being no
 * key to check.
 */
fun contactKeysOf(friends: List<Friend>): Set<String> =
    friends.mapNotNullTo(LinkedHashSet<String>()) { friend ->
        friend.publicKey?.takeIf { it.isNotBlank() }
    }

/**
 * The storm-gate decision.
 *
 * [seen] is message id → the expiry this device is holding it to, [batch] is what one peer
 * just handed over, [from] is that peer's already-authenticated **Contact** key (the
 * transport proves possession before calling; this only asks whether the key is one of
 * mine), [now] is the clock, and [contacts] is [contactKeysOf].
 *
 * [nightEndFor] resolves a [GossipCheckIn.gigId] to the end of that night as this device
 * understands it, or null for a gig it has never heard of — the ordinary case on a relay
 * hop. [verify] is the signature check, defaulting to the real one; a test passes its own,
 * and nothing else should.
 *
 * The order of the checks is deliberate, and it is cheapest-first *after* one hard rule:
 * [GossipReject.FORGED_ID] comes before everything that follows it, because until the id
 * has been recomputed from the payload it is not an identity, and a check that treated it
 * as one would be reading a name the sender chose. Nothing forged can then occupy a message
 * id, since an id that survives that check is a hash of content signed by a **Contact** —
 * and nothing but acceptance ever writes to the seen set.
 *
 * With that settled, [GossipReject.ALREADY_SEEN] sits immediately after it, ahead of the
 * signature check: a peer that hands over five thousand copies of one real message should
 * cost five thousand hash-table lookups, not five thousand ECDSA verifications. Expiry
 * comes last because it is the only gate that needs [nightEndFor], and asking a caller's
 * lookup about a gig id that has not been through [isSafeGossipId] would be handing an
 * unvalidated string to somebody else's store.
 *
 * Idempotent: feeding [GossipPlan.seen] back in leaves the accepted and relay lists empty,
 * and running the same arguments twice yields an equal plan — the relay audience is sorted
 * for that reason, since a [Set]'s iteration order is nobody's guarantee.
 *
 * **What this cannot bound, and the transports must.** Every gate here is decided by the
 * message, and every field of the message is the author's except its key's membership of my
 * **Contact** list. So a Contact whose phone has been taken over can sign fifty thousand
 * check-ins that differ only in [GossipCheckIn.checkedInAt]: each has a distinct honest id
 * and a valid signature, and each would be accepted, remembered for a night and relayed to
 * everyone else I have met. [GOSSIP_MAX_BATCH] caps what one handover costs; what it cannot
 * cap is how often a peer is allowed to hand one over, because nothing in a pure function
 * knows the time between calls. #416 and #417 own that, and a per-peer rate is the shape of
 * it. Named here rather than left to be discovered: the honest bound on this gate is "per
 * batch", not "per night".
 */
fun gossipStormGate(
    seen: Map<String, Instant>,
    batch: List<GossipCheckIn>,
    from: String,
    now: Instant,
    contacts: Set<String>,
    nightEndFor: (String) -> Instant? = { null },
    verify: (GossipCheckIn) -> Boolean = ::verifyGossipSignature,
): GossipPlan {
    // Pruned on every run, including the ones that decide nothing: the set is a device's
    // memory of live messages, and an entry past its expiry can never be resurrected by
    // this gate anyway — the same expiry that pruned it also rejects the message.
    val kept = LinkedHashMap(seen.filterValues { it.isAfter(now) })

    // A peer I have never exchanged keys with is handed nothing and read for nothing —
    // the same fail-safe shape `contactReconcilePlan` takes to an unverified peer.
    if (from.isEmpty() || from !in contacts) {
        return GossipPlan(
            seen = kept,
            rejected = batch.map { GossipRejected(it.messageId, GossipReject.SENDER_NOT_A_CONTACT) },
        )
    }

    val accepted = ArrayList<GossipCheckIn>()
    val relay = ArrayList<GossipRelay>()
    val rejected = ArrayList<GossipRejected>()

    for ((index, message) in batch.withIndex()) {
        if (index >= GOSSIP_MAX_BATCH) {
            rejected += GossipRejected(message.messageId, GossipReject.BATCH_LIMIT)
            continue
        }
        val reason = rejectionFor(message, kept, now, contacts, verify)
        if (reason != null) {
            rejected += GossipRejected(message.messageId, reason)
            continue
        }
        // Worked out once, here, and used for both the gate and what is remembered: it is
        // the only value that leans on the caller's [nightEndFor], and a lookup that
        // answered differently twice would let a message pass the gate and be written into
        // the seen set already expired — pruned on the next run, offered again, accepted
        // again, relayed again.
        val expiry = effectiveExpiry(message, nightEndFor)
        if (!expiry.isAfter(now)) {
            rejected += GossipRejected(message.messageId, GossipReject.EXPIRED)
            continue
        }
        kept[message.messageId] = expiry
        accepted += message
        val to = contacts.filter { it != from && it != message.checkedInBy }.sorted()
        if (to.isNotEmpty()) relay += GossipRelay(message, to)
    }

    return GossipPlan(accepted = accepted, relay = relay, seen = kept, rejected = rejected)
}

/** Null when the message gets in. Every gate but expiry, in the order argued for above. */
private fun rejectionFor(
    message: GossipCheckIn,
    seen: Map<String, Instant>,
    now: Instant,
    contacts: Set<String>,
    verify: (GossipCheckIn) -> Boolean,
): GossipReject? = when {
    !isSafeGossipId(message.gigId) || message.messageId.isEmpty() -> GossipReject.MALFORMED
    message.checkedInBy !in contacts -> GossipReject.AUTHOR_NOT_A_CONTACT
    gossipMessageId(message) != message.messageId -> GossipReject.FORGED_ID
    message.messageId in seen -> GossipReject.ALREADY_SEEN
    !verify(message) -> GossipReject.BAD_SIGNATURE
    message.signedCheckedInAt().isAfter(now.plus(GOSSIP_CLOCK_SKEW)) -> GossipReject.FUTURE_DATED
    else -> null
}

/**
 * How long this device will hold a message: what it claims, capped by what this device
 * knows.
 *
 * `expiresAt` is inside the signed payload, so it is the author's own claim and a genuine
 * **Contact** could still be a compromised phone claiming a year. The cap is the gig's own
 * night where that is known, and one night from the check-in where it is not. Never
 * widened — an early expiry is honoured as it stands.
 */
private fun effectiveExpiry(message: GossipCheckIn, nightEndFor: (String) -> Instant?): Instant {
    val ceiling = nightEndFor(message.gigId)
        ?: message.signedCheckedInAt().plus(GOSSIP_MAX_LIFETIME)
    return minOf(message.signedExpiresAt(), ceiling)
}

/**
 * The times exactly as they were signed and hashed: whole seconds, because that is all
 * [gossipPayload] carries.
 *
 * Anything finer is a part of the envelope no signature covers, so a relay could add up to
 * a second of life to a message it passes on and the id would still check out. Under a
 * second is not much, but "a field a peer can change without breaking the signature decides
 * something" is the shape of the bug, not its size — so no decision here reads one.
 */
private fun GossipCheckIn.signedCheckedInAt(): Instant = Instant.ofEpochSecond(checkedInAt.epochSecond)

private fun GossipCheckIn.signedExpiresAt(): Instant = Instant.ofEpochSecond(expiresAt.epochSecond)
