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

    /// Told whenever the set of this device's witnessed check-ins may have changed (#442).
    ///
    /// A callback rather than the app polling, because a witness lands on the radio's queue
    /// at a moment nothing on the main one is watching for. `AppModel` is the only caller;
    /// the set it receives is the whole answer, so a missed call costs a repaint and not a
    /// fact — the ledger is still the record, and the next launch reads it back.
    private var onWitnessed: (@Sendable (Set<String>) -> Void)?

    init(ledger: GossipLedger = GossipLedger()) { self.ledger = ledger }

    /// Watch this device's witnessed check-ins, and answer once with what is already known.
    func observeWitnessed(_ handler: @escaping @Sendable (Set<String>) -> Void) async {
        onWitnessed = handler
        await publishWitnessed(now: Date())
    }

    private func publishWitnessed(now: Date) async {
        guard let onWitnessed else { return }
        let millis = Int64(now.timeIntervalSince1970 * 1000)
        onWitnessed(await ledger.publicSnapshot(now: millis).witnessedGigIds())
    }

    // --- What the app tells the channel ---

    /// Take the current **Contact** list — the audience, and the answer to "is there anybody
    /// to gossip with at all". Removing a Friend removes their key here, which is the whole of
    /// revocation, the same way `Friend.publicKey` already documents.
    ///
    /// The public channel does not gate a **Pass** on this set (a v2 fact is signed by a Gig
    /// scope, not by a Contact, and is meant to travel further than one hop). It is what
    /// `AppModel` turns into "run the radio or do not".
    func setContacts(_ friends: [Friend]) { contacts = contactKeysOf(friends) }

    func setNightEnds(_ ends: [String: Date]) { nightEnds = ends }

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

    /// Independent nightly relay key: no durable Contact identity on the public wire.
    private func relayScope(_ now: Date) -> String {
        let day = Calendar.current.startOfDay(for: now.addingTimeInterval(-6 * 3600))
        return "relay-\(Int64(day.timeIntervalSince1970))"
    }

    func publicPass(to peer: String, nonce: Data, now: Date = Date()) async -> Data? {
        let millis = Int64(now.timeIntervalSince1970 * 1000)
        var state = await ledger.publicSnapshot(now: millis)
        let offered = state.offer(to: peer, now: millis)
        let request = passAuthor(offered, localAuthors: state.localAuthors)
        let batch = passBatch(offered, request: request)
        let scope = request?.scope ?? relayScope(now)
        guard let me = GigIdentity.publicKeyBase64(scope: scope) else { return nil }
        guard !batch.isEmpty, let proof = GigIdentity.sign(scope: scope, publicGossipAuthPayload(nonce)),
              let bytes = encodePublicGossipPass(PublicGossipPass(from: me, proof: proof.base64EncodedString(), batch: batch)),
              let encoded = decodePublicGossipPass(bytes) else { return nil }
        pending[peer] = encoded.batch.map { $0.id }
        return bytes
    }

    func receivePublic(_ pass: PublicGossipPass, from: String, now: Date = Date()) async {
        let millis = Int64(now.timeIntervalSince1970 * 1000)
        _ = await ledger.receivePublic(pass.batch, from: from, now: millis)
        var state = await ledger.publicSnapshot(now: millis)
        for request in pass.batch where request.kind == "request" && request.author == from {
            guard let local = state.localClaim(for: request),
                  let witness = witnessRequest(request, with: local, now: millis,
                    sign: { GigIdentity.sign(scope: local.scope, $0) }) else { continue }
            _ = await ledger.receivePublic([witness], from: "", now: millis, local: true)
            state = await ledger.publicSnapshot(now: millis)
        }
        // A witness for *this* device's own claim arrives in the same batch as anything
        // else, so the repaint is asked for after the whole batch rather than only when
        // this device was the one doing the witnessing.
        await publishWitnessed(now: now)
    }

    /// Forget the whole channel. Called when the last **Contact** goes.
    func forgetAll() async {
        contacts.removeAll()
        pending.removeAll()
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
    func confirmDelivery(to contact: String) async {
        guard let ids = pending.removeValue(forKey: contact) else { return }
        await ledger.deliveredPublic(ids, to: contact)
    }
}
