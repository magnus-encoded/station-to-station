import Foundation

/// The gossip channel: the one place that holds the **Contact** list, the ledger and this
/// device's signing scopes together, and the only thing `GossipTransport` is allowed to ask
/// questions of.
///
/// An actor, and a single one, because the radio calls into it from a background queue while
/// the app calls into it from the main one, and because `GossipLedger` is a mutable store that
/// two meetings at once would otherwise interleave writes into.
///
/// Nothing here decides anything. Acceptance is `PublicGossipState.receive`, which judges every
/// envelope `GossipEnvelope.valid()` lets through; retention and pruning are `GossipLedger`;
/// the wire format is `PublicGossip.swift`. This is the wiring between them, kept out of the
/// CoreBluetooth file so that file is only ever about CoreBluetooth.
///
/// **The one judgement that is not here either, and where it went:** whether the peer handing
/// over a batch is who it says it is. `receive` takes `from` as already-established fact, so
/// somebody has to establish it — `GossipTransport.peripheralManager(_:didReceiveWrite:)` is
/// that somebody, checking the peer's signature over `publicGossipAuthPayload` of the nonce
/// this device issued before a single envelope reaches this actor. That check is the reason the
/// possession proof exists, and it is deliberately at the radio's edge: nothing that failed it
/// is ever named to the ledger at all.
actor GossipChannel {

    static let shared = GossipChannel()

    private let ledger: GossipLedger
    private let timeline: TimelineStore

    /// The **Contacts** this device holds, by public key — the same base64 SPKI strings
    /// `Friend.publicKey` carries and the wire moves.
    ///
    /// Plain keys, not derived secrets. This file used to hold one ECDH shared secret per
    /// **Contact**; the reconciliation with Android (ADR-0019, 2026-09-08) replaced that
    /// derivation with one keyed on the pair of *public* keys, because Android's identity key
    /// is `PURPOSE_SIGN`-only and cannot perform key agreement at all. The practical
    /// consequence here is that there is no longer any
    /// secret material in this actor to be careful with, and removing a **Contact** is still
    /// the whole of revocation.
    private var contacts: Set<String> = []
    /// Their names, for the snapshot `recognizeContacts` keeps so attribution outlives removal.
    private var contactNames: [String: String] = [:]

    /// Gig id → the end of that night, for the gigs this device happens to know about.
    ///
    /// Kept, and currently read by nothing: it was the v1 gate's expiry ceiling, and the v2
    /// envelope carries its own `expiresAt` that `PublicGossipState.prune` enforces instead.
    /// `AppModel` still supplies it every time the **Line** changes, which costs nothing and
    /// leaves the ceiling available to a v2 rule that wants to cap a peer's claimed expiry at
    /// what this device believes about the night. Delete it only together with that decision.
    private var nightEnds: [String: Date] = [:]

    /// What has been handed to a **Contact** but not yet acknowledged. Delivery is recorded
    /// when the bytes are known to have landed, never when they were prepared.
    private var pending: [String: [String]] = [:]

    /// How many of `pending`'s envelopes were receipts, for `GossipTally` alone.
    private var pendingReceipts: [String: Int] = [:]

    /// Told whenever the set of this device's witnessed check-ins may have changed (#442).
    ///
    /// A callback rather than the app polling, because a witness lands on the radio's queue
    /// at a moment nothing on the main one is watching for. `AppModel` is the only caller;
    /// the set it receives is the whole answer, so a missed call costs a repaint and not a
    /// fact — the ledger is still the record, and the next launch reads it back.
    private var onPublic: (@Sendable (PublicGossipState) -> Void)?
    func observePublic(_ handler: @escaping @Sendable (PublicGossipState) -> Void) async {
        onPublic = handler
        await publishWitnessed(now: Date())
    }

    func blockAuthor(_ author: String) async {
        await ledger.blockAuthor(author)
        await publishWitnessed(now: Date())
    }

    private var onWitnessed: (@Sendable (Set<String>) -> Void)?

    /// Told who a **Pass** just proved is also here, and when (#484).
    ///
    /// Only ever fired from `receivePublic`, never from `publishWitnessed`: presence is about
    /// the arrival of a batch, so replaying it on every repaint — the thing `observePublic`
    /// exists to do — would keep naming people who left. `AppModel` merges rather than
    /// replaces, and `gossipNearby` is what forgets.
    /// The **Gigs** still running come with it, because `metAt` is keyed by **Contact** alone
    /// and a name on the wrong night's page would be a lie the window could not catch. Sent as
    /// the whole current answer rather than added to: participation deadlines are recomputed
    /// from the timeline on every **Pass**, so the latest is the only one that is true.
    private var onPresence: (@Sendable ([String: Date], Set<String>) -> Void)?
    func observePresence(_ handler: @escaping @Sendable ([String: Date], Set<String>) -> Void) { onPresence = handler }

    init(ledger: GossipLedger = GossipLedger(), timeline: TimelineStore = TimelineStore()) {
        self.ledger = ledger
        self.timeline = timeline
    }

    /// Watch this device's witnessed check-ins, and answer once with what is already known.
    func observeWitnessed(_ handler: @escaping @Sendable (Set<String>) -> Void) async {
        onWitnessed = handler
        await publishWitnessed(now: Date())
    }

    private func publishWitnessed(now: Date) async {
        let millis = Int64(now.timeIntervalSince1970 * 1000)
        let state = await ledger.publicSnapshot(now: millis)
        onWitnessed?(state.witnessedGigIds())
        onPublic?(state)
    }

    // --- What the app tells the channel ---

    /// Take the current **Contact** list — the audience, and the answer to "is there anybody
    /// to gossip with at all". Removing a Friend removes their key here, which is the whole of
    /// revocation, the same way `Friend.publicKey` already documents.
    ///
    /// The public channel does not gate a **Pass** on this set (a v2 fact is signed by a Gig
    /// scope, not by a Contact, and is meant to travel further than one hop). It is what
    /// `AppModel` turns into "run the radio or do not".
    func setContacts(_ friends: [Friend]) async {
        contacts = contactKeysOf(friends)
        contactNames = contactNamesOf(friends)
        await ledger.recognizeContacts(contacts, names: contactNames)
        await publishWitnessed(now: Date())
    }

    func setNightEnds(_ ends: [String: Date]) { nightEnds = ends }

    /// The neighbour handles holding live routing credit right now (#444, story 38).
    ///
    /// Credit is what a receipt bought: `PublicGossipState.useful` maps a neighbour's relay key
    /// to the moment its credit lapses, and `publicReceiptMs` decides how long that is. Read as
    /// a whole set rather than asked peer by peer, because the caller is a pick window ranking
    /// several candidates at once and the actor hop should be paid once.
    ///
    /// Routing evidence only, and deliberately shaped so it cannot be anything else: the answer
    /// is a set of keys that are *preferred*, never a set that is excluded. Nothing downstream
    /// is given the vocabulary to refuse a peer for being absent from it.
    func creditedPeers(now: Date = Date()) async -> Set<String> {
        let millis = Int64(now.timeIntervalSince1970 * 1000)
        let state = await ledger.publicSnapshot(now: millis)
        return Set(state.useful.filter { $0.value > millis }.keys)
    }

    /// I checked in. Mint the signed envelope and hold it — this device is now the first hop.
    ///
    /// Returns whether anything entered the channel, which is false on a phone that cannot mint
    /// a signing scope for this Gig, or for a gig id the wire format will not carry.
    ///
    /// The author is the Gig's own scope key, never the durable **Contact** identity: what
    /// travels the public channel is attributable to whoever already holds the scope and to
    /// nobody else (`GigIdentity`). A phone that has never met me learns a random per-Gig key
    /// and the night, not a key that also sits on my **Card**.
    @discardableResult
    func checkedIn(gigId: String, localGigId: String, gigDate: String?, now: Date = Date()) async -> Bool {
        guard let scope = await ledger.authorScope(localGigId: localGigId) else { return false }
        if GigIdentity.key(scope: scope) != nil,
           let author = GigIdentity.publicKeyBase64(scope: scope) {
            let seconds = Int64(now.timeIntervalSince1970 * 1000)
            let expiry = Int64((gossipExpiry(gigDate: gigDate ?? "")?.timeIntervalSince1970 ?? now.timeIntervalSince1970 + 86400) * 1000)
            var fact = GossipEnvelope(gigId: gigId, scope: scope, author: author,
                                      createdAt: seconds, expiresAt: expiry,
                                      kind: "request")
            fact.attribution = GigIdentity.attribution(scope: scope, author: author) ?? ""
            if let signed = fact.signed({ GigIdentity.sign(scope: scope, $0) }) {
                let accepted = await ledger.receivePublic([signed], from: "", now: seconds, local: true)
                return accepted == 1
            }
        }
        return false
    }

    /// This night took a setlist.fm id while the radio was still running (#497).
    ///
    /// Authored with the **Gig**'s own scope key — the same key that signed the **Check-in**
    /// being relabelled, which is the only thing that makes the link believable to a phone
    /// that receives it. An **Update** is not a second **Check-in**: it carries no text and no
    /// line, it names nobody as newly present, and it neither starts nor extends
    /// participation. It rides the **Pass** the same way a witness does, so a receiver can
    /// carry my earlier request onto the identified **Gig** without waiting for a **Log** line
    /// I may never write.
    ///
    /// `until` is participation's existing deadline, so an adoption after the night is over
    /// authors nothing and stays local.
    @discardableResult
    func adoptedGigId(gigId: String, formerGigId: String, localGigId: String,
                      until: Int64, now: Date = Date()) async -> Bool {
        let millis = Int64(now.timeIntervalSince1970 * 1000)
        guard millis < until, gigId != formerGigId,
              let scope = await ledger.authorScope(localGigId: localGigId),
              let author = GigIdentity.publicKeyBase64(scope: scope) else { return false }
        var fact = GossipEnvelope(gigId: gigId, formerIds: [formerGigId], scope: scope, author: author,
                                  createdAt: millis, expiresAt: until, kind: "update")
        fact.attribution = GigIdentity.attribution(scope: scope, author: author) ?? ""
        guard let signed = fact.signed({ GigIdentity.sign(scope: scope, $0) }),
              await ledger.receivePublic([signed], from: "", now: millis, local: true) == 1
        else { return false }
        // The witnessed mark is read back from state rather than pushed at the moment of
        // witnessing, and the adoption has just changed which ids carry it.
        await publishWitnessed(now: now)
        return true
    }

    func publishLog(gigId: String, localGigId: String, expiry: Date, changes: [Int: String], now: Date = Date()) async {
        guard !changes.isEmpty, let scope = await ledger.authorScope(localGigId: localGigId),
              let author = GigIdentity.publicKeyBase64(scope: scope) else { return }
        let millis = Int64(now.timeIntervalSince1970 * 1000)
        let state = await ledger.publicSnapshot(now: millis)
        let previous = state.facts.values.filter { $0.author == author }
        let former = Set(previous.flatMap { $0.formerIds + [$0.gigId] }).subtracting([gigId]).sorted()
        let revision = max(millis, (previous.map(\.createdAt).max() ?? 0) + 1)
        let facts = changes.sorted { $0.key < $1.key }.compactMap { line, text -> GossipEnvelope? in
            var fact = GossipEnvelope(gigId: gigId, formerIds: former, scope: scope, author: author,
                createdAt: revision, expiresAt: Int64(expiry.timeIntervalSince1970 * 1000), kind: "log", line: line, text: text)
            fact.attribution = GigIdentity.attribution(scope: scope, author: author) ?? ""
            return fact.signed { GigIdentity.sign(scope: scope, $0) }
        }
        _ = await ledger.receivePublic(facts, from: "", now: millis, local: true)
        await publishWitnessed(now: now)
    }

    /// Independent nightly relay key: no durable Contact identity on the public wire.
    private func relayScope(_ now: Date) -> String {
        let day = Calendar.current.startOfDay(for: now.addingTimeInterval(-6 * 3600))
        return "relay-\(Int64(day.timeIntervalSince1970))"
    }

    func publicPass(to peer: String, nonce: Data, now: Date = Date()) async -> Data? {
        let millis = Int64(now.timeIntervalSince1970 * 1000)
        var state = await ledger.publicSnapshot(now: millis)
        let cache = await timeline.load()
        let ends = gossipParticipationEnds(cache: cache, stoppedAt: GossipTransport.shared.stoppedAt)
        guard ends.values.contains(where: { millis < $0 }) else { return nil }
        let offered = state.offer(to: peer, now: millis, participationEnds: ends)
        let request = passAuthor(offered, localAuthors: state.localAuthors)
        let scope = request?.scope ?? relayScope(now)
        guard let me = GigIdentity.publicKeyBase64(scope: scope) else { return nil }
        let batch = passBatch(offered, request: request, signer: me)
        guard !batch.isEmpty, let proof = GigIdentity.sign(scope: scope, publicGossipAuthPayload(nonce)),
              let bytes = encodePublicGossipPass(PublicGossipPass(from: me, proof: proof.base64EncodedString(), batch: batch)),
              let encoded = decodePublicGossipPass(bytes) else { return nil }
        pending[peer] = encoded.batch.map { $0.id }
        // Counted off the encoded batch rather than `batch`, so what is tallied as offered is
        // what actually fitted on the wire.
        pendingReceipts[peer] = encoded.batch.filter { $0.kind == "receipt" }.count
        GossipTally.shared.offered(pendingReceipts[peer] ?? 0)
        return bytes
    }

    func receivePublic(_ pass: PublicGossipPass, from: String, now: Date = Date()) async {
        let millis = Int64(now.timeIntervalSince1970 * 1000)
        let accepted = await ledger.receivePublicFacts(pass.batch, from: from, now: millis)
        var state = await ledger.publicSnapshot(now: millis)
        for request in accepted where request.kind == "request" && request.author == from {
            // Whether to witness at all is `witnessFor`'s answer, not this loop's: this phone
            // signs for a stranger only when it checked into the same Gig itself, and that
            // rule has to be reachable by a test rather than only by a BLE callback. The
            // signer is the local claim's own Gig scope, which is why it arrives per claim.
            guard let witness = witnessFor(state, request: request, now: millis,
                    sign: { claim in { GigIdentity.sign(scope: claim.scope, $0) } }) else { continue }
            _ = await ledger.receivePublic([witness], from: "", now: millis, local: true)
            state = await ledger.publicSnapshot(now: millis)
        }
        // A witness for *this* device's own claim arrives in the same batch as anything
        // else, so the repaint is asked for after the whole batch rather than only when
        // this device was the one doing the witnessing.
        await ledger.recognizeContacts(contacts, names: contactNames)
        // Receipts are authored here and nowhere else, which is what keeps story 37
        // structural: recognition that arrives later, from an Exchange, runs through
        // `setContacts` and has no way back into this batch.
        state = await ledger.publicSnapshot(now: millis)
        // Read after `recognizeContacts`, so a **Contact** recognised by this very batch is
        // named, and only for nights still running: a known **Gig** keeps its deadline after
        // participation ends, and last night's claim arriving in tonight's **Pass** is not
        // somebody standing here. The same test `offer` applies.
        let cache = await timeline.load()
        let ends = gossipParticipationEnds(cache: cache, stoppedAt: GossipTransport.shared.stoppedAt)
        let gigIds = Set(ends.filter { millis < $0.value }.keys)
        // A **Pass** completed, so the night keeps it (#499). `pass.batch` rather than
        // `accepted`: a claim this device already holds is refused as a replay and is still
        // proof that the device handing it over is standing at the night it names.
        await ledger.rememberPass(with: from, batch: pass.batch,
            activeGigId: gossipActiveGigId(cache: cache, stoppedAt: GossipTransport.shared.stoppedAt, now: millis),
            now: millis)
        // `accepted`, not `pass.batch`: a replay of a claim this device already holds is
        // refused by `receive` and is not evidence anybody is standing here now.
        let present = state.presenceFrom(accepted: accepted, gigIds: gigIds)
        if !present.isEmpty { onPresence?(present.reduce(into: [:]) { $0[$1] = now }, gigIds) }
        let relay = relayScope(now)
        // A receipt is addressed, so it may only name a key this device can meet again: the
        // sender's relay key, which a Pass carrying the sender's own request does not prove.
        // See `passRelay`. And one receipt per Gig record, not per Fact: two lines of one log
        // author the same receipt twice, and the second would retire the first.
        let addressable = passRelay(pass)
        if addressable == nil { GossipTally.shared.declined() }
        if let me = GigIdentity.publicKeyBase64(scope: relay), let addressable {
            let receipts = receiptsFor(accepted, from: addressable,
                recognised: { state.recognition[$0.author] != nil },
                author: me, now: millis, sign: { GigIdentity.sign(scope: relay, $0) })
            if !receipts.isEmpty {
                _ = await ledger.receivePublic(receipts, from: "", now: millis, local: true)
                GossipTally.shared.authored(receipts.count)
            }
        }
        await publishWitnessed(now: now)
    }

    /// Forget the whole channel. Called when the last **Contact** goes.
    func forgetAll() async {
        contacts.removeAll()
        contactNames.removeAll()
        pending.removeAll()
        pendingReceipts.removeAll()
        await ledger.forgetAll()
    }

    // --- What the radio asks, as a listener ---

    /// The challenge a connecting device reads first: the nonce it will have to sign, and one
    /// token per **Contact** for the current bucket.
    ///
    /// The nonce is the caller's, not this actor's, because it belongs to one connection: the
    /// peripheral half issues one per central and spends it on one **Pass**. A nonce shared
    /// between two centrals would mean whichever read last decided what the other had to sign.
    func challenge(nonce: Data, now: Date) -> Data {
        let scope = relayScope(now)
        guard let me = GigIdentity.publicKeyBase64(scope: scope) else { return Data() }
        return encodePublicGossipChallenge(nonce: nonce, from: me,
                                          sign: { GigIdentity.sign(scope: scope, $0) }) ?? Data()
    }

    /// The bytes landed. Only now is anything marked delivered — a handover that died halfway
    /// is offered again the next time these two phones are in the same room.
    func confirmDelivery(to contact: String, now: Date = Date()) async {
        guard let ids = pending.removeValue(forKey: contact) else { return }
        GossipTally.shared.delivered(pendingReceipts.removeValue(forKey: contact) ?? 0)
        await ledger.deliveredPublic(ids, to: contact)
        // Dialling out is a met device too, and it is recorded here rather than in
        // `publicPass(to:nonce:)` for the same reason delivery is: bytes that never landed are
        // not a **Pass**. `contact` is the key the peer's signed challenge proved — the relay
        // key, the same space `receivePublic`'s `from` is in. This direction carries no claim
        // of theirs, so the active **Gig** is the only night it can honestly be attached to.
        let millis = Int64(now.timeIntervalSince1970 * 1000)
        let cache = await timeline.load()
        await ledger.rememberPass(with: contact, batch: [],
            activeGigId: gossipActiveGigId(cache: cache, stoppedAt: GossipTransport.shared.stoppedAt, now: millis),
            now: millis)
        // Every other ledger write on this actor ends here, and this one has to as well:
        // `onPublic` is the only route the record takes to a **Room** that is already open.
        // Android gets it from a Flow off its store; iOS's callback has to be called.
        await publishWitnessed(now: now)
    }
}
