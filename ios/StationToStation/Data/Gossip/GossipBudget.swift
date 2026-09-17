import Foundation

/// The bound a per-batch decision cannot draw, drawn here (#417; Android draws the same
/// bound as `GOSSIP_PEER_COOLDOWN` in `data/gossip/GossipPolicy.kt`, and #416 owns the other
/// half of the same rule).
///
/// The argument, which the deleted v1 gate stated and which `PublicGossipState.receive`
/// inherits unchanged: every check is decided by the envelope in front of it, and every field
/// of that envelope is the author's. So an author whose phone has been taken over can sign
/// fifty thousand facts that differ only in `createdAt`. `gossipMaxBatch` caps what one
/// handover costs; what it cannot cap is how often a peer is allowed to hand one over, because
/// nothing in a pure function knows the time between calls.
///
/// This is that: how often, per peer. Pure in the same sense — the budget arrives as a value
/// and leaves as a value, `now` arrives as an argument — so the rate rule is assertable
/// without waiting an hour with two phones.
///
/// **Per Contact, not per radio.** The key is the resolved **Contact** public key, so a
/// compromised phone cannot buy itself a fresh budget by cycling its BLE address, which iOS
/// and Android both do routinely and cheaply. The cost is that an unresolved peer is not
/// budgeted here at all — that one is bounded by the transport hanging up on a token it
/// cannot resolve, before anything is decoded.

/// The shortest gap between two handovers this device will read from one **Contact**.
///
/// Two phones in the same pocket-range for a whole set will meet many times as iOS wakes the
/// scan; without this, each meeting is a full batch of signature verifications for messages
/// that were almost all already seen. A minute rather than a second because a **Pass** carries
/// the peer's *whole* live outbox, not an increment: two phones that meet have said everything
/// they have to say to each other on the first connection, and a second one a minute later
/// exists only to catch what arrived in between.
///
/// **One minute, matching Android's `GOSSIP_PEER_COOLDOWN` exactly**, and that is not
/// decoration. This is also the *sending* side's cooldown, so a value shorter than the other
/// platform's would mean every second push an iPhone made to a Pixel was refused as `cooling`
/// — a connection, a nonce and a signature spent on nothing, all night. It is local admission
/// policy rather than a wire term, so the two could differ; they cost something when they do.
let gossipPeerCooldown: TimeInterval = 60

/// How long a **Contact** counts as still being here after a verified check-in of theirs
/// arrived (#484), matching Android's `GOSSIP_NEARBY_WINDOW`.
///
/// Longer than `gossipPeerCooldown` on purpose, and a multiple of it rather than a round
/// number: two phones in the same room speak about once a minute, so a window of one minute
/// would blink out between every pair of **Passes** and report an empty room half the time.
/// Five gives a missed connection — a pocket, a wall, a radio busy elsewhere — room to be a
/// missed connection rather than a departure.
///
/// It is deliberately not a presence protocol. Nothing announces leaving, because nothing can:
/// a phone that walks away says nothing on its way out, so the only honest account of who is
/// here is who was heard from recently.
let gossipNearbyWindow: TimeInterval = 300

/// Which **Contacts** to call present, given when each was last heard from.
///
/// Ordered most recent first, so a caller with room for two names shows the two people most
/// likely to still be standing there. A pure function of a map and a clock, so the rule can be
/// tested without a radio and read the same way by every surface that asks.
func gossipNearby(_ metAt: [String: Date], now: Date) -> [String] {
    metAt.filter { now < $0.value.addingTimeInterval(gossipNearbyWindow) }
        .sorted { ($0.value, $0.key) > ($1.value, $1.key) }
        .map { $0.key }
}

/// The rolling window the ceiling below is counted over.
let gossipPeerWindow: TimeInterval = 3600

/// The most messages one **Contact** may put in front of this device per `gossipPeerWindow`.
///
/// The real bound on the attack the gate names: a taken-over phone signing as fast as it can
/// gets 240 ECDSA verifications an hour out of me and no more, however many batches it
/// offers. Generous against honest traffic by a wide margin — a night's check-ins among
/// people who have all met each other is a handful, and 240 is four full batches.
///
/// Charged on what is *offered*, not on what is accepted. Charging acceptance would make the
/// budget free to exhaust with rubbish, which is precisely the traffic it exists to stop.
let gossipPeerHourlyMessages = 240

/// What one **Contact** has spent. Persisted, because iOS relaunches this app in the
/// background whenever the radio has something to say, and a budget that reset on every
/// relaunch would bound nothing at all.
struct GossipPeerBudget: Equatable, Codable {
    var lastHandoverAt: Date?
    var windowStart: Date?
    var messagesInWindow: Int = 0
}

/// Whether to read what a peer is offering, and what that leaves of its budget.
enum GossipAdmission: Equatable {
    /// Read it. Carries the budget to store back — the caller must, or nothing is spent.
    case admit(GossipPeerBudget)
    /// Too soon after this peer's last handover. Not an accusation: the ordinary case is two
    /// phones that keep finding each other, and the right response is to hang up quietly.
    case cooling
    /// Past `gossipPeerHourlyMessages` for this window. The batch is not read at all — no
    /// signature is verified, nothing is remembered, and the same messages are judged
    /// normally when the window rolls.
    case flooding
}

/// The decision. `offered` is how many messages the peer's batch actually parsed to, so the
/// charge is what this device would have to look at, not what the peer claimed.
///
/// A `windowStart` older than `gossipPeerWindow` rolls: the window is a fresh hour from
/// `now`, not a sliding average, because a sliding one needs a list of timestamps per peer
/// and this needs to survive being written to disk on a phone that is about to be suspended.
func gossipAdmit(_ budget: GossipPeerBudget, offered: Int, now: Date) -> GossipAdmission {
    if let last = budget.lastHandoverAt, now >= last,
       now.timeIntervalSince(last) < gossipPeerCooldown {
        return .cooling
    }
    var next = budget
    if let start = next.windowStart, now >= start, now.timeIntervalSince(start) < gossipPeerWindow {
        // Still inside the window this peer opened.
    } else {
        next.windowStart = now
        next.messagesInWindow = 0
    }
    guard next.messagesInWindow + max(0, offered) <= gossipPeerHourlyMessages else {
        return .flooding
    }
    next.messagesInWindow += max(0, offered)
    next.lastHandoverAt = now
    return .admit(next)
}

/// Budgets for peers nobody has met in an hour are forgotten.
///
/// A device that keeps one entry per **Contact** it has ever stood near keeps a small, slowly
/// growing record of who it has been in a room with, which is exactly the kind of history
/// this app does not keep anywhere else. An expired window carries no information the next
/// handover would not recreate, so dropping it costs the bound nothing.
func gossipPruneBudgets(_ budgets: [String: GossipPeerBudget], now: Date) -> [String: GossipPeerBudget] {
    budgets.filter { _, budget in
        guard let start = budget.windowStart else { return false }
        return now.timeIntervalSince(start) < gossipPeerWindow
    }
}

/// How long the central would gather sightings before picking one peer, if it had to pick
/// (#444, story 38). **Android holds the same second and a half** (`GOSSIP_PICK_WINDOW_MS`).
///
/// **Provisional.** Nothing has measured it.
let gossipPickWindow: TimeInterval = 1.5

/// The order to try the peers seen in one window, best first (#444, stories 38 and 39).
///
/// Preference, never exclusion. Every candidate is returned — a neighbour with no credit is
/// still pushed to, just later in the list — because a phone that only ever spoke to the
/// neighbours that had already proved useful would never learn that any other one is. The
/// shuffle runs over the whole list before the partition, so both bands are shuffled, and
/// `generator` is a parameter rather than a global for the only reason that matters: a test
/// cannot assert "randomly" without a seed.
///
/// **Not yet wired on this platform**, and that is the honest state rather than an oversight.
/// Android's central holds one connection at a time, so a sighting it takes is a sighting it
/// spends; `GossipTransport` opens a meeting with every peripheral `didDiscover` reports and
/// has no scarce slot to ration. The rule lives here so the two platforms cannot drift apart
/// on what "useful first" means, and so the case has its twin; wiring it is a change to
/// `beginMeeting`, and it only becomes worth making when iOS caps concurrent meetings.
func gossipPreferredPeers(_ candidates: [String], credited: (String) -> Bool,
                          using generator: inout some RandomNumberGenerator) -> [String] {
    var seen = Set<String>()
    let unique = candidates.filter { seen.insert($0).inserted }
    let shuffled = unique.shuffled(using: &generator)
    return shuffled.filter(credited) + shuffled.filter { !credited($0) }
}
