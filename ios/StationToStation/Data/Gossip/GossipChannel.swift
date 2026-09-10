import Foundation

/// The gossip channel: the one place that holds the **Contact** list, the ledger, and the
/// storm gate together, and the only thing `GossipTransport` is allowed to ask questions of.
///
/// An actor, and a single one, because the radio calls into it from a background queue while
/// the app calls into it from the main one, and because `GossipLedger` is a mutable store that
/// two meetings at once would otherwise interleave writes into.
///
/// Nothing here decides anything either. Admission is `gossipAdmit`, acceptance is
/// `gossipStormGate`, retention is `GossipLedger`, and recognition is `GossipToken`. This is
/// the wiring between them, kept out of the CoreBluetooth file so that file is only ever about
/// CoreBluetooth.
///
/// **It does own one judgement the gate cannot make for itself:** whether the peer handing
/// over a batch is who it says it is. `gossipStormGate` takes `from` as already-established
/// fact, so somebody has to establish it — `receive` is that somebody, and it is the reason
/// the possession proof exists.
actor GossipChannel {

    static let shared = GossipChannel()

    private let ledger: GossipLedger
    private var publicState = PublicGossipState()

    /// The **Contacts** this device holds, by public key — the same base64 SPKI strings
    /// `Friend.publicKey` carries and the wire moves.
    ///
    /// Plain keys, not derived secrets. This file used to hold one ECDH shared secret per
    /// **Contact**; the reconciliation with Android (ADR-0019, 2026-09-08) replaced that
    /// derivation with one keyed on the pair of *public* keys, because Android's identity key
    /// is `PURPOSE_SIGN`-only and cannot perform key agreement at all. See `GossipToken.swift`
    /// for the whole argument. The practical consequence here is that there is no longer any
    /// secret material in this actor to be careful with, and removing a **Contact** is still
    /// the whole of revocation.
    private var contacts: Set<String> = []

    /// Gig id → the end of that night, for the gigs this device happens to know about. The
    /// gate uses it as a ceiling; nil for everything else, which is the ordinary case on a
    /// relay hop and is exactly what the gate expects.
    private var nightEnds: [String: Date] = [:]

    /// What has been handed to a **Contact** but not yet acknowledged. Delivery is recorded
    /// when the bytes are known to have landed, never when they were prepared.
    private var pending: [String: [String]] = [:]

    init(ledger: GossipLedger = GossipLedger()) { self.ledger = ledger }

    // --- What the app tells the channel ---

    /// Take the current **Contact** list. Removing a Friend removes their key here, and with
    /// it every token that would have recognised them — the same "removing the Contact is the
    /// whole of revocation" that `Friend.publicKey` already documents.
    func setContacts(_ friends: [Friend]) { contacts = contactKeysOf(friends) }

    func setNightEnds(_ ends: [String: Date]) { nightEnds = ends }

    /// I checked in. Mint the signed envelope and hold it — this device is now the first hop.
    ///
    /// Returns whether anything entered the channel, which is false on a phone with no
    /// identity key or for a gig id the wire format will not carry.
    @discardableResult
    func checkedIn(gigId: String, gigDate: String?, now: Date = Date()) async -> Bool {
        guard let me = ContactIdentity.publicKeyBase64(),
              let message = gossipCheckInMessage(gigId: gigId, gigDate: gigDate, publicKey: me,
                                                 now: now, sign: ContactIdentity.sign)
        else { return false }
        await ledger.hold(message, now: now)
        let scope = "gig-\(gigId)"
        if GigIdentity.key(scope: scope) != nil,
           let author = GigIdentity.publicKeyBase64(scope: scope) {
            let seconds = Int64(now.timeIntervalSince1970 * 1000)
            let expiry = Int64((gossipExpiry(gigDate: gigDate ?? "")?.timeIntervalSince1970 ?? now.timeIntervalSince1970 + 86400) * 1000)
            var fact = GossipEnvelope(gigId: gigId, scope: scope, author: author,
                                      createdAt: seconds, expiresAt: expiry,
                                      kind: "log", line: 0, text: "Checked in")
            fact.attribution = GigIdentity.attribution(scope: scope, author: author) ?? ""
            if let signed = fact.signed({ GigIdentity.sign(scope: scope, $0) }) {
                publicState.receive(signed, from: "", now: seconds, local: true)
            }
        }
        return true
    }

    /// A public v2 pass uses the same authenticated transport peer as v1, but carries
    /// temporary Gig-authored envelopes and never exposes a durable author key.
    func publicPass(to contact: String, nonce: Data, now: Date = Date()) async -> Data? {
        guard contacts.contains(contact), let me = ContactIdentity.publicKeyBase64() else { return nil }
        let batch = await publicState.offer(to: contact, now: Int64(now.timeIntervalSince1970 * 1000))
        guard !batch.isEmpty, let proof = ContactIdentity.sign(gossipAuthPayload(nonce)) else { return nil }
        return encodePublicGossipPass(PublicGossipPass(from: me, proof: proof.base64EncodedString(), batch: batch))
    }

    func receivePublic(_ pass: PublicGossipPass, from: String, now: Date = Date()) {
        let millis = Int64(now.timeIntervalSince1970 * 1000)
        for envelope in pass.batch { _ = publicState.receive(envelope, from: from, now: millis) }
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
        guard let me = ContactIdentity.publicKeyBase64() else {
            return encodeGossipChallenge(GossipChallenge(nonce: nonce, tokens: []))
        }
        let offer = gossipTokenOffer(mine: me, contacts: Array(contacts), now: now)
        return encodeGossipChallenge(GossipChallenge(nonce: nonce, tokens: offer))
    }

    /// A **Pass** arrived. Returns the **Contact** it came from, or nil if it was not one.
    ///
    /// **This is where "the transport proves possession before calling" is actually done** —
    /// the sentence `gossipStormGate`'s `from` parameter is written against. Three things have
    /// to hold, and a failure of any of them is silent: a readable envelope, a claimed key that
    /// is a **Contact**, and a signature over the nonce *this* connection issued. Nothing is
    /// reported back, for the reason the gate gives for its own rejections — a diagnosis is a
    /// probe's oracle.
    ///
    /// The proof is what makes a token safe to be only a hint. A token is derived from public
    /// material, so any device holding both keys could present one; a signature over a fresh
    /// nonce is not something it can produce.
    func receive(_ data: Data, nonce: Data, now: Date) async -> String? {
        guard let pass = decodeGossipPass(data), contacts.contains(pass.from),
              let signature = Data(base64Encoded: pass.proof),
              verifyChallenge(gossipAuthPayload(nonce), signature: signature,
                              publicKeyBase64: pass.from)
        else { return nil }
        let contact = pass.from

        // The bound the storm gate says in so many words it cannot apply: how *often* a peer
        // may hand something over. Charged on what was offered, not what survived, so a peer
        // that floods with rubbish pays for the rubbish.
        switch gossipAdmit(await ledger.budget(for: contact, now: now), offered: pass.batch.count,
                           now: now) {
        case .cooling, .flooding:
            return contact
        case .admit(let spent):
            await ledger.spend(spent, for: contact, now: now)
        }

        let plan = gossipStormGate(seen: await ledger.seen(now: now), batch: pass.batch,
                                   from: contact, now: now, contacts: contacts,
                                   nightEndFor: { [nightEnds] in nightEnds[$0] })
        await ledger.record(plan, from: contact, now: now)
        return contact
    }

    // --- What the radio asks, as a pusher ---

    /// Which **Contact**, if any, a challenge's offer names.
    func resolve(_ offered: [String], now: Date) -> String? {
        guard let me = ContactIdentity.publicKeyBase64() else { return nil }
        return gossipResolveOffer(offered,
                                  table: gossipTokenTable(mine: me, contacts: Array(contacts),
                                                          now: now))
    }

    /// The **Pass** for one **Contact**: this device's key, its signature over the nonce that
    /// **Contact** just issued, and everything held for them.
    ///
    /// Nil when there is nothing to say, which is the ordinary reason to hang up without
    /// writing: an empty batch is a connection spent for nothing, and the peer is still
    /// advertising a minute later.
    func pass(to contact: String, nonce: Data, now: Date) async -> Data? {
        guard contacts.contains(contact), let me = ContactIdentity.publicKeyBase64() else {
            return nil
        }
        let batch = await ledger.offer(to: contact, now: now)
        guard !batch.isEmpty, let signature = ContactIdentity.sign(gossipAuthPayload(nonce))
        else { return nil }
        guard let payload = encodeGossipPass(
            GossipPass(from: me, proof: signature.base64EncodedString(), batch: batch)
        ) else { return nil }
        pending[contact] = batch.map { $0.messageId }
        return payload
    }

    /// The bytes landed. Only now is anything marked delivered — a handover that died halfway
    /// is offered again the next time these two phones are in the same room.
    func confirmDelivery(to contact: String) async {
        guard let ids = pending.removeValue(forKey: contact) else { return }
        await ledger.delivered(ids, to: contact)
    }
}
