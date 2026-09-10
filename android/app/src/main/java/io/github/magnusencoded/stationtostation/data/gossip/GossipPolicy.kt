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

/**
 * Should the foreground service be running?
 *
 * **The lifecycle chosen, stated for review, because the issue asks for exactly that.** A
 * persistent notification is the price ADR-0019 named, and the honest way to pay it is to
 * only charge it on the nights the feature does anything.
 *
 * Public relays do not require Contacts. Any one of three reasons is enough:
 *
 * - [holding] — this device carries at least one live message. That covers the sender: a
 *   check-in mints a message, which starts the service, which runs until that message
 *   expires at the end of its own night and then stops on its own. Nothing schedules that
 *   shutdown; it falls out of the expiry the gate already enforces.
 * - [gigTonight] — a **Gig** on this timeline is inside its night window right now. That
 *   covers the receiver, and it is the condition that makes a relay chain possible at all:
 *   without it, a **Contact** standing at the same gig would not be listening when the
 *   person next to them checked in.
 * - [alwaysRelay] — the user has said they want to carry other people's news whenever they
 *   have their phone on them. Off by default, because it is the one setting that means "run
 *   all the time", and a background radio nobody asked for is precisely the failure this
 *   whole decision is trying to avoid.
 *
 * **Deliberately not a reason: the app being open.** Gossip is not a foreground feature and
 * tying it to a screen would recreate exactly the limitation ADR-0019 carved itself out of.
 *
 * **Deliberately not built: a boot receiver.** The service does not come back by itself
 * after a restart; opening the app restores it. Relaying a friend's check-in does not earn
 * the right to run before the user has touched the phone, and a night interrupted by a
 * reboot is a night with less news, not a broken feature.
 */
fun gossipRelayShouldRun(
    contacts: Int,
    holding: Boolean,
    gigTonight: Boolean,
    alwaysRelay: Boolean,
): Boolean = holding || gigTonight || alwaysRelay

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
