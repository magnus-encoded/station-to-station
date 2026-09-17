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

/// How long the central gathers sightings before ranking them (#444, story 38). **Android
/// holds the same second and a half** (`GOSSIP_PICK_WINDOW_MS`).
///
/// One advertisement is not a choice. `didDiscover` reports one peripheral at a time, so
/// without a window the first radio to answer takes a slot regardless of what the second one
/// would have been worth, and usefulness could never order anything.
///
/// The cost is real and is paid here rather than hidden: the first meeting of a scan session
/// opens 1.5 s later than it used to, on a platform that measures a background wake in
/// seconds. That is the price of the ordering existing at all, and it is bounded — the window
/// closes on its own timer whether a second sighting arrives or not, so a session that reports
/// exactly one peer and then goes quiet still meets it.
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
/// Wired on both platforms as of #486. Android's central spends the order on one connection at
/// a time; `GossipTransport` spends it on the free slots under `gossipMaxConcurrentMeetings`,
/// through `gossipPeersToMeet` below.
func gossipPreferredPeers(_ candidates: [String], credited: (String) -> Bool,
                          using generator: inout some RandomNumberGenerator) -> [String] {
    var seen = Set<String>()
    let unique = candidates.filter { seen.insert($0).inserted }
    let shuffled = unique.shuffled(using: &generator)
    return shuffled.filter(credited) + shuffled.filter { !credited($0) }
}

/// Which of the peers seen so far to open meetings with now, and which to keep for later
/// (#444, stories 38 and 39).
///
/// `free` is how many of `gossipMaxConcurrentMeetings` are unused. The first `free` of the
/// preferred order are taken; **everyone else is returned in `remaining`, not dropped**, and
/// that half is the point rather than a convenience.
///
/// **Why iOS must retain what Android may discard.** Android's picker clears its sighted set
/// because `onScanResult` fires again for the same device a moment later — "the peers it skips
/// are still advertising a minute later" is true there. It is not true here: a background scan
/// never redelivers a duplicate advertisement (`CBCentralManagerScanOptionAllowDuplicatesKey`
/// is ignored in the background), so a sighting this device declines to spend is a peer it may
/// not be told about again for the rest of the scan session. Discarding the overflow would turn
/// preference into exactly the permanent exclusion story 39 forbids — not as a ranking bug, but
/// as an artefact of the platform. So the pool survives the window, and the transport reopens a
/// window over it whenever a slot frees.
///
/// `free <= 0` is therefore a normal answer and not a no-op: nothing is met, nothing is lost,
/// and the whole pool comes back ordered for the next attempt.
func gossipPeersToMeet(_ pool: [String], free: Int, credited: (String) -> Bool,
                       using generator: inout some RandomNumberGenerator)
    -> (chosen: [String], remaining: [String]) {
    let order = gossipPreferredPeers(pool, credited: credited, using: &generator)
    let take = max(0, min(free, order.count))
    return (Array(order.prefix(take)), Array(order.dropFirst(take)))
}
