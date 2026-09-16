package io.github.magnusencoded.stationtostation.data.gossip

import io.github.magnusencoded.stationtostation.ui.nightWindow
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * When the radio is on, and how often one peer may speak (#416).
 *
 * Two decisions that have nothing to do with each other except that both are about *time*
 * rather than about an envelope, which is exactly why neither belongs beside the acceptance
 * rules in
 * [PublicGossipState.receive][io.github.magnusencoded.stationtostation.data.gossip.PublicGossipState.receive]:
 * that is a fold over one batch against one snapshot, and it cannot know how long it has
 * been since the last one. The transport owns the rate, and this is the transport's half.
 */

/**
 * The shortest gap between two **Passes** this device will read from the same peer.
 *
 * The bound acceptance could not draw. Every check `receive` applies is decided by fields
 * the author signed, so a device that has been taken over can sign an endless stream of
 * distinct, valid, honestly-identified envelopes; the encoder's byte and record ceilings cap
 * what one **Pass** costs, and this caps how often one may be offered. Sixty-four envelopes
 * a minute per peer is the ceiling the two together describe.
 *
 * A minute rather than a second because a **Pass** carries the peer's *whole* live carry
 * set, not an increment: two phones that meet have said everything they have to say to each
 * other on the first connection, and a second one a minute later exists only to catch what
 * arrived in between. It is also the sending side's cooldown — there is no point pushing to
 * someone who will not read it.
 *
 * **iOS holds the same minute** (`gossipPeerCooldown` in `Data/Gossip/GossipBudget.swift`).
 * Local admission policy rather than a wire term, so the two *could* differ — but because it
 * is the sending cooldown as well, a platform with a shorter one spends a connection, a nonce
 * and a signature on every push the other refuses. They are kept equal for that reason.
 *
 * A refused **Pass** is not remembered. Nothing is written, so the same envelopes are
 * accepted normally on the next connection, exactly as a **Pass** trimmed at the byte
 * ceiling is.
 */
val GOSSIP_PEER_COOLDOWN: Duration = Duration.ofMinutes(1)

/**
 * May this device read from, or push to, a peer right now?
 *
 * [lastAt] is when it last did — null for a **Contact** met for the first time since the
 * service started, which is always allowed.
 */
fun gossipPassDue(lastAt: Instant?, now: Instant): Boolean =
    lastAt == null || !now.isBefore(lastAt.plus(GOSSIP_PEER_COOLDOWN))

/** Check-in is participation consent. Held facts and Contacts do not start a radio. */
fun gossipRelayShouldRun(activeUntil: Instant?, now: Instant): Boolean =
    activeUntil != null && now.isBefore(activeUntil)

/** A completed set gets 30 minutes, bounded by its night. Legacy closed logs get no new grace. */
fun gossipParticipationUntil(
    checkedInAt: Long?, closed: Boolean, completedAt: Long?, nightEnd: Instant,
    stoppedAt: Long = 0,
): Instant? {
    if (checkedInAt == null || checkedInAt <= stoppedAt) return null
    if (closed && completedAt == null) return null
    return completedAt?.let { minOf(nightEnd, Instant.ofEpochMilli(it).plusSeconds(1800)) } ?: nightEnd
}

/**
 * When each **Gig** this timeline knows about stops being news.
 *
 * A relayed envelope names a **Gig** by id and carries an expiry its author signed, and this
 * is what that claim would be judged against by a device that has heard of the gig. Nothing
 * calls it in the v2 relay path: a public envelope's lifetime is bounded by its own signed
 * `expiresAt` and the fixed ceiling
 * [receive][io.github.magnusencoded.stationtostation.data.gossip.PublicGossipState.receive]
 * applies, which needs no local knowledge of the night — and needing none is what lets a
 * stranger's phone carry news about a show on nobody else's timeline.
 *
 * Kept because per-gig expiry is the ceiling an authoring device already asks for
 * ([gossipExpiry]) and the obvious input to any later decision to *tighten* what a relay
 * holds for a night it recognises. Delete it if that decision is made the other way.
 */
fun gossipNightEnds(
    gigDates: Map<String, LocalDate>,
    zone: ZoneId = ZoneId.systemDefault(),
): Map<String, Instant> = gigDates.mapValues { (_, date) -> gossipExpiry(date, zone) }

/**
 * Is one of these **Gigs** happening right now — [gossipRelayShouldRun]'s second reason?
 *
 * The same window the manual check-in offer uses
 * ([nightWindow][io.github.magnusencoded.stationtostation.ui.nightWindow]), and deliberately
 * so: the hours in which this device would let its owner say "I'm here" are exactly the hours
 * in which it should be listening for a **Contact** saying it.
 */
fun gossipGigTonight(
    gigDates: Map<String, LocalDate>,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): Boolean = gigDates.values.any { date ->
    val opens = nightWindow(date).start.atZone(zone).toInstant()
    !now.isBefore(opens) && now.isBefore(gossipExpiry(date, zone))
}

/**
 * How long a **Contact** counts as still being here after the last time these two phones
 * actually spoke.
 *
 * Longer than [GOSSIP_PEER_COOLDOWN] on purpose, and a multiple of it rather than a round
 * number: two phones in the same room speak about once a minute, so a window of one minute
 * would blink out between every pair of exchanges and report an empty room half the time.
 * Five gives a missed connection — a pocket, a wall, a radio busy elsewhere — room to be a
 * missed connection rather than a departure.
 *
 * It is deliberately not a presence protocol. Nothing announces leaving, because nothing can:
 * a phone that walks away says nothing on its way out, so the only honest account of who is
 * here is who was heard from recently.
 */
val GOSSIP_NEARBY_WINDOW: Duration = Duration.ofMinutes(5)

/**
 * Which **Contacts** to call present, given when each was last heard from.
 *
 * Ordered most recent first, so a caller that has room for two names shows the two people
 * most likely to still be standing there.
 */
fun gossipNearby(metAt: Map<String, Instant>, now: Instant): List<String> =
    metAt.entries
        .filter { now.isBefore(it.value.plus(GOSSIP_NEARBY_WINDOW)) }
        .sortedByDescending { it.value }
        .map { it.key }

/**
 * How long the central gathers sightings before it picks one peer to push to (#444, story 38).
 *
 * **Provisional.** The scan reports one device at a time, so without a window there is no set
 * to prefer *within* — the first advertisement seen always wins, and usefulness could never
 * change a decision. A second and a half is long enough to see a second radio in the same
 * room and short enough to stay well inside the time two people stand near each other, which
 * is the same budget the scan mode was chosen against. It costs that much latency on every
 * push; a real night's figures should settle whether that is the right trade.
 */
const val GOSSIP_PICK_WINDOW_MS = 1500L

/**
 * The order to try the peers seen in one window, best first (#444, stories 38 and 39).
 *
 * Preference, never exclusion. Every candidate is returned — a neighbour with no credit is
 * still pushed to, just later in the list — because a phone that only ever spoke to the
 * neighbours that had already proved useful would never learn that any other one is.
 *
 * Ties break randomly, and the shuffle runs over the *whole* list before the partition, so
 * the uncredited band is shuffled too. [random] is a parameter rather than a global for the
 * only reason that matters: a test cannot assert "randomly" without a seed.
 *
 * [credited] answers whether a candidate holds live credit right now; expiry is the caller's
 * business, and `PublicGossipState.prune` has already done it.
 */
fun gossipPreferredPeers(
    candidates: List<String>,
    credited: (String) -> Boolean,
    random: kotlin.random.Random,
): List<String> = candidates.distinct().shuffled(random).sortedByDescending(credited)
