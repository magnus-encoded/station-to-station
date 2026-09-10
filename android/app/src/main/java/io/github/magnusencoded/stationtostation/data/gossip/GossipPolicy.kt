package io.github.magnusencoded.stationtostation.data.gossip

import io.github.magnusencoded.stationtostation.data.gossipExpiry
import io.github.magnusencoded.stationtostation.ui.nightWindow
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * When the radio is on, and how often one peer may speak (#416).
 *
 * Two decisions that have nothing to do with each other except that both are about *time*
 * rather than about a message, which is exactly why neither could live in the storm-gate —
 * a pure function over one batch cannot know how long it has been since the last one. The
 * gate says so itself, in as many words: "#416 and #417 own that, and a per-peer rate is
 * the shape of it."
 */

/**
 * The shortest gap between two **Passes** this device will read from the same **Contact**.
 *
 * The bound the gate could not draw. Every gate it applies is decided by fields the author
 * controls, so a **Contact** whose phone has been taken over can sign an endless stream of
 * distinct, valid, honestly-identified check-ins; [GOSSIP_MAX_BATCH][io.github.magnusencoded.stationtostation.data.GOSSIP_MAX_BATCH]
 * caps what one **Pass** costs, and this caps how often one may be offered. Sixty-four
 * messages a minute per peer is the ceiling the two together describe.
 *
 * A minute rather than a second because a **Pass** carries the peer's *whole* live outbox,
 * not an increment: two phones that meet have said everything they have to say to each
 * other on the first connection, and a second one a minute later exists only to catch what
 * arrived in between. It is also the sending side's cooldown — there is no point pushing to
 * someone who will not read it.
 *
 * **iOS holds the same minute** (`gossipPeerCooldown` in `Data/Gossip/GossipBudget.swift`).
 * Local admission policy rather than a wire term, so the two *could* differ — but because it
 * is the sending cooldown as well, a platform with a shorter one spends a connection, a nonce
 * and a signature on every push the other refuses. They are kept equal for that reason.
 *
 * A refused **Pass** is not remembered. Nothing is written, so the same messages are
 * accepted normally on the next connection, exactly as a
 * [BATCH_LIMIT][io.github.magnusencoded.stationtostation.data.GossipReject.BATCH_LIMIT]
 * overflow is.
 */
val GOSSIP_PEER_COOLDOWN: Duration = Duration.ofMinutes(1)

/**
 * May this device read from, or push to, [peer] right now?
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
 * The storm-gate's `nightEndFor`, built from the **Gigs** this timeline knows about.
 *
 * A relayed message names a **Gig** by id and claims an expiry, and the gate caps that claim
 * at the end of the gig's own night *where it can*. It can only do that for a **Gig** this
 * device has heard of; for anything else the gate falls back to the author's claim under its
 * own thirty-hour ceiling, which is the right answer — a **Contact** may well be at a show
 * that is on nobody else's timeline.
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
