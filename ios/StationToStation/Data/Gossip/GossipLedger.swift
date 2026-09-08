import Foundation

/// What the gossip channel remembers between one meeting and the next (#417).
///
/// Three things, and they are three because they answer three different questions:
///
///   * `seen` — message id → the expiry this device holds it to. `gossipStormGate`'s own
///     argument and its own result; this file only carries it across a process death.
///   * `held` — the messages this device will offer onward, and who has already had each.
///   * `budgets` — what each **Contact** has spent, per `gossipAdmit`.
///
/// All three have to survive a relaunch, because on iOS a relaunch is the normal case: the
/// app is not running when a friend walks past, CoreBluetooth wakes it, and a seen set that
/// started empty every time would accept and re-relay the same check-in all night. That is
/// the storm the gate is named for, arriving through the back door.
///
/// An actor for the reason `TimelineStore` is one: the radio has two halves that both write
/// here, on a queue that is not the main one, and two overlapping saves would each read the
/// old file and the loser's writes would vanish.
///
/// **This is a location record, and it is written down as one.** `held` is a list of who was
/// where and when, including people this device has never met (ADR-0019, "Disclosure to the
/// relaying device"). It is kept in Application Support — backed up, protected by the device
/// passcode after first unlock — and it is pruned on every single write rather than on a
/// schedule, so a night's worth of other people's movements does not outlive the night. There
/// is no path here that keeps an expired message.
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

    /// The seen set as `gossipStormGate` wants it, already pruned.
    func seen(now: Date) -> [String: Date] {
        Dictionary(uniqueKeysWithValues: pruned(load(), now: now).seen.map {
            ($0.key, Date(timeIntervalSince1970: TimeInterval($0.value)))
        })
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

    /// The batch to hand one **Contact**: everything still live that they have not already
    /// had from this device, soonest to expire last.
    ///
    /// Bounded by `gossipMaxBatch`, so the far end never sees an offer it would reject as
    /// `.batchLimit` — that rejection exists for a hostile peer, and spending it on our own
    /// honest overflow would silently drop the tail of a real night's check-ins.
    ///
    /// Ordered by expiry descending so the messages with the most night left go first: an
    /// offer that has to be truncated should lose the ones that were about to lapse anyway.
    func offer(to contact: String, now: Date) -> [GossipCheckIn] {
        pruned(load(), now: now).held
            .filter { !$0.deliveredTo.contains(contact) }
            .sorted { $0.expiresAt > $1.expiresAt }
            .prefix(gossipMaxBatch)
            .map { $0.message() }
    }

    /// Note that a batch has actually been handed over, so it is not offered to the same
    /// **Contact** again.
    ///
    /// Recorded after the write lands, not before: a handover that failed halfway is a
    /// handover that should be tried again next time these two phones meet.
    func delivered(_ messageIds: [String], to contact: String) {
        guard !messageIds.isEmpty else { return }
        let ids = Set(messageIds)
        write { stored in
            var next = stored
            for index in next.held.indices where ids.contains(next.held[index].messageId) {
                next.held[index].deliveredTo.insert(contact)
            }
            return next
        }
    }

    /// Take the gate's decision and keep it: the whole seen set it returned, and every message
    /// it accepted, held for onward relay.
    ///
    /// `from` is the peer that handed the batch over; it and each message's own author are
    /// seeded into `deliveredTo`, which is how "never echo it back, never tell the author"
    /// survives being written to disk. The gate's `relay.to` audience is deliberately *not*
    /// stored — see `hold`.
    func record(_ plan: GossipPlan, from: String?, now: Date) {
        write { stored in
            var next = stored
            next.seen = plan.seen.compactMapValues { gossipEpochSeconds($0) }
            for message in plan.accepted {
                next.upsert(message, alreadyHad: [from, message.checkedInBy].compactMap { $0 })
            }
            return pruned(next, now: now)
        }
    }

    /// This device's own check-in, entering the channel.
    ///
    /// Seeded with its own author — me — so the one device that certainly does not need to be
    /// told is not offered it.
    ///
    /// **Held until it expires, rather than relayed once.** `gossipStormGate` names this as
    /// the transport's decision to make and it is made here: on iOS the moment a message is
    /// accepted is almost never a moment when the **Contact** who needs it is in range, so a
    /// relay that fired only at acceptance would deliver nothing to anybody who was not
    /// already standing there — which is the entire population this feature exists for
    /// (ADR-0019). The bound is the same expiry the gate applies, so nothing is held past the
    /// night it is about. Recorded as an amendment on ADR-0019 rather than left implicit.
    func hold(_ message: GossipCheckIn, now: Date) {
        write { stored in
            var next = stored
            next.upsert(message, alreadyHad: [message.checkedInBy])
            return pruned(next, now: now)
        }
    }

    /// Everything, gone. The whole of forgetting a night: no separate revocation, no server
    /// to ask.
    func forgetAll() {
        cache = StoredGossip()
        try? FileManager.default.removeItem(at: file)
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
        let next = change(load())
        cache = next
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        guard let data = try? encoder.encode(next) else { return }
        try? data.write(to: file, options: .atomic)
    }

    /// Pruned on every read and every write, never on a timer: an entry past its expiry can
    /// never be resurrected — the same expiry that prunes it also rejects the message — so
    /// there is no state in which keeping one is useful, and every extra minute it survives is
    /// a minute somebody else's movements sit on this disk.
    private func pruned(_ stored: StoredGossip, now: Date) -> StoredGossip {
        guard let seconds = gossipEpochSeconds(now) else { return stored }
        var next = stored
        next.seen = stored.seen.filter { $0.value > seconds }
        next.held = stored.held
            .filter { $0.expiresAt > seconds }
            .sorted { $0.expiresAt > $1.expiresAt }
            .prefix(gossipMaxHeld)
            .map { $0 }
        next.budgets = gossipPruneBudgets(stored.budgets, now: now)
        return next
    }
}

/// The most messages this device will carry for other people at once.
///
/// A ceiling on the disclosure as much as on the disk: `held` is other people's whereabouts,
/// and "as many as arrive" is not a quantity anybody chose. Well above a real night — a
/// festival's worth of Contacts checking into a weekend of gigs is tens, not hundreds — and
/// the overflow is the messages closest to expiring, which had least left to give.
let gossipMaxHeld = 512

/// The file's shape. Deliberately not `GossipCheckIn` itself: that type belongs to
/// `gossipStormGate`, which is a pure decision with no opinion about disks, and giving it a
/// `Codable` conformance would make a `Date`'s encoding — the one thing the two twins must
/// never disagree about — a property of whichever encoder happened to touch it. Epoch seconds
/// here, exactly as on the wire and exactly as in the signed payload.
private struct StoredGossip: Codable {
    var seen: [String: Int64] = [:]
    var held: [HeldGossip] = []
    var budgets: [String: GossipPeerBudget] = [:]

    mutating func upsert(_ message: GossipCheckIn, alreadyHad: [String]) {
        guard let checkedInAt = gossipEpochSeconds(message.checkedInAt),
              let expiresAt = gossipEpochSeconds(message.expiresAt)
        else { return }
        if let index = held.firstIndex(where: { $0.messageId == message.messageId }) {
            held[index].deliveredTo.formUnion(alreadyHad)
            return
        }
        held.append(HeldGossip(
            messageId: message.messageId, gigId: message.gigId,
            checkedInBy: message.checkedInBy, checkedInAt: checkedInAt,
            expiresAt: expiresAt, signature: message.signature,
            deliveredTo: Set(alreadyHad)
        ))
    }
}

private struct HeldGossip: Codable {
    var messageId: String
    var gigId: String
    var checkedInBy: String
    var checkedInAt: Int64
    var expiresAt: Int64
    var signature: String
    /// Who has already had it from this device, plus its author and whoever handed it over.
    var deliveredTo: Set<String> = []

    func message() -> GossipCheckIn {
        GossipCheckIn(
            messageId: messageId, gigId: gigId, checkedInBy: checkedInBy,
            checkedInAt: Date(timeIntervalSince1970: TimeInterval(checkedInAt)),
            expiresAt: Date(timeIntervalSince1970: TimeInterval(expiresAt)),
            signature: signature
        )
    }
}
