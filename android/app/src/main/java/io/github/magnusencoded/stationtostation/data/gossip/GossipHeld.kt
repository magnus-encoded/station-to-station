package io.github.magnusencoded.stationtostation.data.gossip

import io.github.magnusencoded.stationtostation.data.GOSSIP_MAX_BATCH
import io.github.magnusencoded.stationtostation.data.GossipCheckIn
import io.github.magnusencoded.stationtostation.data.GossipPlan
import java.time.Instant

/**
 * What this device is still carrying, and who it may still be handed to (#416).
 *
 * Pure: a list in, a list out, "now" as an argument. The persistence is
 * [GossipStore][io.github.magnusencoded.stationtostation.data.gossip.GossipStore]'s
 * problem and the radio is [GossipRadio][io.github.magnusencoded.stationtostation.ble.GossipRadio]'s;
 * what is *held*, *offered* and *forgotten* is decided here so it can be asserted without
 * either.
 *
 * **One list, not two.** The storm-gate takes a seen set (id → expiry) and returns the one
 * to keep; this device also has to keep the messages themselves, because a **Contact** who
 * was out of range when a check-in first arrived is exactly who the relay exists to reach,
 * and the gate says plainly that holding a message and offering it again is the
 * transport's call to make. Those would be two stores of the same fact with the same expiry
 * rule, so they are one: [seenFrom] derives the gate's argument from what is held, and
 * nothing can drift because there is nothing to drift from.
 */
data class GossipHeld(
    val message: GossipCheckIn,
    /**
     * The **Contact** who handed this over, or null for a check-in this phone authored.
     *
     * Kept for one reason: [gossipOutboxFor] must not hand a message back to the peer it
     * came from. That is [GossipRelay.to][io.github.magnusencoded.stationtostation.data.GossipRelay]'s
     * first exclusion, and on a radio that cannot address one **Contact** at a time it can
     * only be honoured by remembering where each message entered.
     */
    val arrivedFrom: String?,
    /**
     * When this device stops holding it — the gate's `effectiveExpiry`, not the author's
     * claim. Taken from the plan rather than recomputed, so the value that decided
     * acceptance is the value that decides forgetting.
     */
    val expiry: Instant,
)

/** The storm-gate's seen argument, derived rather than stored alongside. */
fun seenFrom(held: List<GossipHeld>): Map<String, Instant> =
    held.associate { it.message.messageId to it.expiry }

/** Everything still live. The gate prunes its own copy the same way; this prunes the store. */
fun pruneGossipHeld(held: List<GossipHeld>, now: Instant): List<GossipHeld> =
    held.filter { it.expiry.isAfter(now) }

/**
 * Fold a plan's acceptances into what this device carries.
 *
 * Only [GossipPlan.accepted] is added, and it is added with the expiry the plan wrote into
 * [GossipPlan.seen] — the gate capped the author's claim at what this device is willing to
 * hold, and taking the author's field back at this point would quietly undo that.
 *
 * [from] is the peer the batch arrived from, or null when this device is the author. An
 * accepted message that is somehow already held keeps its original entry: the gate rejects
 * a repeat as `ALREADY_SEEN` long before this, so reaching that case at all means something
 * upstream is wrong and the older record is the one that has been believed.
 */
fun gossipHold(held: List<GossipHeld>, plan: GossipPlan, from: String?): List<GossipHeld> {
    if (plan.accepted.isEmpty()) return held
    val known = held.mapTo(HashSet()) { it.message.messageId }
    val added = plan.accepted.mapNotNull { message ->
        if (!known.add(message.messageId)) null
        else plan.seen[message.messageId]?.let { GossipHeld(message, from, it) }
    }
    return held + added
}

/**
 * What to push to [peer] right now.
 *
 * This is [GossipRelay.to][io.github.magnusencoded.stationtostation.data.GossipRelay]'s
 * membership test, re-expressed for a radio that hands over a batch rather than addressing
 * one **Contact**: never back to the peer it arrived from (which would echo it straight
 * home) and never to its own author (who plainly knows). The gate computes that audience
 * from a **Contact** set; here the audience is whoever connected, and the same two
 * exclusions decide what they are shown.
 *
 * Newest first, then by id, so the cap keeps tonight's news rather than an arbitrary
 * slice and two runs over the same input push the same bytes.
 */
fun gossipOutboxFor(held: List<GossipHeld>, peer: String, now: Instant): List<GossipCheckIn> =
    held.asSequence()
        .filter { it.expiry.isAfter(now) }
        .filter { it.message.checkedInBy != peer && it.arrivedFrom != peer }
        .sortedWith(compareByDescending<GossipHeld> { it.message.checkedInAt }
            .thenBy { it.message.messageId })
        .take(GOSSIP_MAX_BATCH)
        .map { it.message }
        .toList()

/**
 * What this device carries, as it goes to disk.
 *
 * The same tab-and-newline shape [encodeGossipPass] uses, with two fields in front:
 * where it came from (empty for mine) and the expiry this device settled on. Reusing the
 * wire's grammar rather than reaching for a serialization library keeps one answer to "what
 * separates two fields" in this feature instead of two.
 */
fun encodeGossipHeld(held: List<GossipHeld>): String =
    held.mapNotNull { entry ->
        val message = entry.message
        val fields = listOf(
            entry.arrivedFrom.orEmpty(),
            entry.expiry.epochSecond.toString(),
            message.messageId,
            message.gigId,
            message.checkedInBy,
            message.checkedInAt.epochSecond.toString(),
            message.expiresAt.epochSecond.toString(),
            message.signature,
        )
        if (fields.drop(1).any { it.isEmpty() }) null
        else if (fields.any { it.contains('\t') || it.contains('\n') }) null
        else fields.joinToString("\t")
    }.joinToString("\n")

/**
 * Back off disk, skipping anything unreadable.
 *
 * A record this device cannot parse is dropped rather than treated as corruption of the
 * whole store: the cost of losing one held message is that one **Contact** hears a check-in
 * from someone else in the chain instead, and the cost of discarding the file is a night's
 * worth of dedup memory — which is what stops a message this device already relayed from
 * being accepted and relayed all over again.
 */
fun decodeGossipHeld(stored: String?): List<GossipHeld> {
    if (stored.isNullOrBlank()) return emptyList()
    return stored.split('\n').mapNotNull { line ->
        val f = line.split('\t')
        if (f.size != 8) return@mapNotNull null
        if (f.drop(1).any { it.isEmpty() }) return@mapNotNull null
        val expiry = f[1].toLongOrNull()?.let(::boundedInstant) ?: return@mapNotNull null
        val checkedInAt = f[5].toLongOrNull()?.let(::boundedInstant) ?: return@mapNotNull null
        val expiresAt = f[6].toLongOrNull()?.let(::boundedInstant) ?: return@mapNotNull null
        GossipHeld(
            message = GossipCheckIn(
                messageId = f[2],
                gigId = f[3],
                checkedInBy = f[4],
                checkedInAt = checkedInAt,
                expiresAt = expiresAt,
                signature = f[7],
            ),
            arrivedFrom = f[0].ifEmpty { null },
            expiry = expiry,
        )
    }
}
