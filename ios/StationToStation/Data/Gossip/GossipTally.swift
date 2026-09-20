import Foundation

/// Six numbers that say whether routing receipts did anything tonight (#444).
///
/// The twin of Android's `GossipTally`, and for the same reason: ADR-0022 ships receipts
/// knowing that several independent things could make the whole feature a no-op in the field,
/// and that all of them look identical from the outside — "no preference", which is also what
/// correct looks like when nobody is around. A receipt never authored, one never owed because
/// the **Pass** could not be addressed (ADR-0022 §2a), one authored but never offered, one
/// offered but never delivered, and a ranking that never finds live credit are five different
/// failures with one symptom, and this is what tells them apart.
///
/// **Cheap and off the hot path by construction.** Six counters behind one lock, incremented
/// at a handful of places, nothing persisted. Losing it all to a process death is correct
/// rather than a gap.
///
/// `creditHits` is live on both platforms as of #486: `GossipTransport` gathers a pick window
/// and ranks it against `gossipMaxConcurrentMeetings` free slots, so a window that finds no
/// credit is now a measurement rather than a missing caller. A zero here means the ranking ran
/// and found nothing — which, given that credit is only learnable after a peer has been met
/// once (ADR-0022 §4), is still the expected reading early in a night.
///
/// These are counts of events, never of peers, and nothing here names anybody.
final class GossipTally: @unchecked Sendable {
    static let shared = GossipTally()

    private let lock = NSLock()
    private var authoredCount: Int64 = 0
    private var declinedCount: Int64 = 0
    private var offeredCount: Int64 = 0
    private var deliveredCount: Int64 = 0
    private var windowCount: Int64 = 0
    private var creditHitCount: Int64 = 0

    /// Receipts this device wrote, on admitting **Facts** it recognised as a **Contact**'s.
    func authored(_ count: Int = 1) { bump(count) { $0.authoredCount += $1 } }

    /// A **Pass** this device owed nothing on because it could not address its sender
    /// (ADR-0022 §2a: signed as a **Gig**, because it carried its signer's own request).
    ///
    /// One per **Pass**, not per **Fact**. The ratio of this to `authored` is what decides
    /// whether §2a's "deferred, not lost" argument survives a real room.
    func declined() { bump(1) { $0.declinedCount += $1 } }

    /// Receipts put into a **Pass** — offered is not delivered, and the gap is the interesting part.
    func offered(_ count: Int) { bump(count) { $0.offeredCount += $1 } }

    /// Receipts in a **Pass** the peer took to the end.
    func delivered(_ count: Int) { bump(count) { $0.deliveredCount += $1 } }

    /// One closed pick window: how many candidates it ranked, and how many held live credit.
    func ranked(candidates: Int, hits: Int) {
        guard candidates > 0 else { return }
        lock.lock(); defer { lock.unlock() }
        windowCount += 1
        if hits > 0 { creditHitCount += Int64(hits) }
    }

    /// The whole night in one line, for a log at shutdown.
    func summary() -> String {
        lock.lock(); defer { lock.unlock() }
        return "receipts authored=\(authoredCount) declined=\(declinedCount) "
            + "offered=\(offeredCount) delivered=\(deliveredCount) · "
            + "pick windows=\(windowCount) credit hits=\(creditHitCount)"
    }

    /// Only for tests; a running relay never resets its own tally.
    func reset() {
        lock.lock(); defer { lock.unlock() }
        authoredCount = 0; declinedCount = 0; offeredCount = 0; deliveredCount = 0
        windowCount = 0; creditHitCount = 0
    }

    private func bump(_ count: Int, _ apply: (GossipTally, Int64) -> Void) {
        guard count > 0 else { return }
        lock.lock(); defer { lock.unlock() }
        apply(self, Int64(count))
    }
}
