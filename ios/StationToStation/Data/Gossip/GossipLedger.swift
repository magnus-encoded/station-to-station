import Foundation

/// What the gossip channel remembers between one meeting and the next (#417).
///
/// Three things, and they are three because they answer three different questions:
///
///   * `publicState` — the facts themselves: what this device has seen, what it still carries
///     for other people, and who has already had each. `PublicGossipState` owns every rule
///     about them; this file only carries them across a process death.
///   * `authorScopes` — local Gig id → the random scope this device signs that Gig's facts
///     with. Durable by definition: a scope that rotated would orphan everything already
///     signed under the old one.
///   * `budgets` — what each peer has spent, per `gossipAdmit`.
///
/// All three have to survive a relaunch, because on iOS a relaunch is the normal case: the
/// app is not running when a friend walks past, CoreBluetooth wakes it, and a seen set that
/// started empty every time would accept and re-relay the same fact all night. That is
/// the storm the channel is defended against, arriving through the back door.
///
/// An actor for the reason `TimelineStore` is one: the radio has two halves that both write
/// here, on a queue that is not the main one, and two overlapping saves would each read the
/// old file and the loser's writes would vanish.
///
/// **This is a location record, and it is written down as one.** What is carried is a list of
/// who was where and when, including people this device has never met (ADR-0019, "Disclosure
/// to the relaying device"). It is kept in Application Support — backed up, protected by the
/// device passcode after first unlock — and it is pruned on every single write rather than on a
/// schedule, so a night's worth of other people's movements does not outlive the night. There
/// is no path here that keeps an expired fact.
actor GossipLedger {

    private let file: URL
    private var cache: StoredGossip?

    /// `file` is injectable only so the pruning and the offer rules can be tested off a
    /// device — the same reason `TimelineStore` takes one.
    init(file: URL = GossipLedger.defaultFile) {
        self.file = file
    }

    static var defaultFile: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("gossip.json")
    }

    /// A local Gig keeps its random signing scope when its external ID changes.
    /// Only return a new scope once it is durable, so a failed write cannot rotate it.
    func authorScope(localGigId: String) -> String? {
        guard !localGigId.isEmpty else { return nil }
        var next = load()
        if let scope = next.authorScopes?[localGigId] { return scope }
        let scope = UUID().uuidString.lowercased()
        var bindings = next.authorScopes ?? [:]
        bindings[localGigId] = scope
        next.authorScopes = bindings
        return persist(next) ? scope : nil
    }

    /// Detached public state for projection and radio offers. Relay expiry keeps facts.
    func publicSnapshot(now: Int64) -> PublicGossipState {
        var state = load().publicState ?? PublicGossipState()
        state.prune(now: now)
        return state
    }

    /// One actor transaction for the whole Pass, shared with local authoring.
    @discardableResult
    func receivePublic(_ batch: [GossipEnvelope], from: String, now: Int64,
                       local: Bool = false) -> Int {
        var next = load()
        var state = next.publicState ?? PublicGossipState()
        state.prune(now: now)
        var accepted = 0
        for envelope in batch {
            if state.receive(envelope, from: from, now: now, local: local) { accepted += 1 }
        }
        next.publicState = state
        return persist(next) ? accepted : 0
    }

    func deliveredPublic(_ ids: [String], to peer: String) {
        var next = load()
        var state = next.publicState ?? PublicGossipState()
        state.delivered(to: peer, ids: ids)
        next.publicState = state
        _ = persist(next)
    }

    /// What this **Contact** has spent so far, as of `now` — a window that has rolled is a
    /// window that is gone, so an old entry answers `GossipPeerBudget()` rather than a stale
    /// count.
    func budget(for contact: String, now: Date) -> GossipPeerBudget {
        pruned(load(), now: now).budgets[contact] ?? GossipPeerBudget()
    }

    func spend(_ budget: GossipPeerBudget, for contact: String, now: Date) {
        write { stored in
            var next = pruned(stored, now: now)
            next.budgets[contact] = budget
            return next
        }
    }

    /// Clear what belongs to the radio while retaining this device's own Gig identity
    /// bindings and the facts it authored.
    func forgetAll() {
        // Removing the last Contact clears per-peer transport memory, not a Gig that is mine.
        let stored = load()
        if stored.authorScopes != nil || stored.publicState != nil {
            write { _ in StoredGossip(authorScopes: stored.authorScopes, publicState: stored.publicState) }
        } else {
            cache = StoredGossip()
            try? FileManager.default.removeItem(at: file)
        }
    }

    // --- The file ---

    private func load() -> StoredGossip {
        if let cache { return cache }
        guard let data = try? Data(contentsOf: file),
              let stored = try? JSONDecoder().decode(StoredGossip.self, from: data)
        else {
            let empty = StoredGossip()
            cache = empty
            return empty
        }
        cache = stored
        return stored
    }

    private func write(_ change: (StoredGossip) -> StoredGossip) {
        _ = persist(change(load()))
    }

    private func persist(_ next: StoredGossip) -> Bool {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        do {
            let data = try encoder.encode(next)
            try data.write(to: file, options: .atomic)
            cache = next
            return true
        } catch {
            return false
        }
    }

    /// Pruned on every read and every write, never on a timer: a spend whose window has rolled
    /// can never be resurrected, so there is no state in which keeping one is useful.
    ///
    /// The facts prune themselves — `PublicGossipState.prune` is called on every snapshot and
    /// every receive, for the stronger reason that they are other people's movements and every
    /// extra minute they survive is a minute they sit on this disk.
    private func pruned(_ stored: StoredGossip, now: Date) -> StoredGossip {
        var next = stored
        next.budgets = gossipPruneBudgets(stored.budgets, now: now)
        return next
    }
}

/// The file's shape.
///
/// Deliberately not `PublicGossipState`'s own types restated: dates are epoch **milliseconds**
/// inside an envelope, exactly as on the wire and exactly as in the signed payload, and the one
/// thing the two twins must never disagree about is how a time encodes. Nothing here gives a
/// `Date` a `Codable` conformance whose encoding would then be a property of whichever encoder
/// happened to touch it.
private struct StoredGossip: Codable {
    // Optional so a ledger written before the public channel existed still decodes.
    var authorScopes: [String: String]? = nil
    var publicState: PublicGossipState? = nil
    var budgets: [String: GossipPeerBudget] = [:]
}
