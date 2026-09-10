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
    func checkedIn(gigId: String, localGigId: String, gigDate: String?, now: Date = Date()) async -> Bool {
        guard let me = ContactIdentity.publicKeyBase64(),
              let message = gossipCheckInMessage(gigId: gigId, gigDate: gigDate, publicKey: me,
                                                 now: now, sign: ContactIdentity.sign)
        else { return false }
        await ledger.hold(message, now: now)
        guard let scope = await ledger.authorScope(localGigId: localGigId) else { return false }
        if GigIdentity.key(scope: scope) != nil,
           let author = GigIdentity.publicKeyBase64(scope: scope) {
            let seconds = Int64(now.timeIntervalSince1970 * 1000)
            let expiry = Int64((gossipExpiry(gigDate: gigDate ?? "")?.timeIntervalSince1970 ?? now.timeIntervalSince1970 + 86400) * 1000)
            var fact = GossipEnvelope(gigId: gigId, scope: scope, author: author,
                                      createdAt: seconds, expiresAt: expiry,
                                      kind: "log", line: 0, text: "Checked in")
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
        let scope = relayScope(now)
        guard let me = GigIdentity.publicKeyBase64(scope: scope) else { return nil }
        let millis = Int64(now.timeIntervalSince1970 * 1000)
        var state = await ledger.publicSnapshot(now: millis)
        let batch = state.offer(to: peer, now: millis)
        guard !batch.isEmpty, let proof = GigIdentity.sign(scope: scope, publicGossipAuthPayload(nonce)),
              let bytes = encodePublicGossipPass(PublicGossipPass(from: me, proof: proof.base64EncodedString(), batch: batch)),
              let encoded = decodePublicGossipPass(bytes) else { return nil }
        pending[peer] = encoded.batch.map { $0.id }
        return bytes
    }

    func receivePublic(_ pass: PublicGossipPass, from: String, now: Date = Date()) async {
        let millis = Int64(now.timeIntervalSince1970 * 1000)
        await ledger.receivePublic(pass.batch, from: from, now: millis)
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
