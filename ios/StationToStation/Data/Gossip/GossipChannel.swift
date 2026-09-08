import Foundation

/// The gossip channel: the one place that holds a **Contact**'s shared secret, the ledger,
/// and the storm gate together, and the only thing `GossipTransport` is allowed to ask
/// questions of.
///
/// An actor, and a single one, because the radio calls into it from a background queue while
/// the app calls into it from the main one, and because the per-**Contact** ECDH secrets are
/// the most sensitive thing this feature computes: one place that derives them, no copies
/// handed out, and `setContacts` is the whole of both granting and revoking.
///
/// Nothing here decides anything either. Admission is `gossipAdmit`, acceptance is
/// `gossipStormGate`, retention is `GossipLedger`, and identity is `GossipToken`. This is the
/// wiring between them, kept out of the CoreBluetooth file so that file is only ever about
/// CoreBluetooth.
actor GossipChannel {

    static let shared = GossipChannel()

    private let ledger: GossipLedger

    /// Contact public key → the 32 bytes only that pair can compute. Derived once when the
    /// **Contact** list changes rather than per meeting: `SecKeyCopyKeyExchangeResult` touches
    /// the Secure Enclave, and a background relaunch has milliseconds to spend.
    ///
    /// Memory only. It is recomputable from the identity key and the Friend list at any time,
    /// and a shared secret written to disk is a shared secret that outlives deleting the
    /// **Contact** that produced it.
    private var secrets: [String: Data] = [:]

    /// Gig id → the end of that night, for the gigs this device happens to know about. The
    /// gate uses it as a ceiling; nil for everything else, which is the ordinary case on a
    /// relay hop and is exactly what the gate expects.
    private var nightEnds: [String: Date] = [:]

    /// What has been handed to a **Contact** but not yet acknowledged. Delivery is recorded
    /// when the bytes are known to have landed, never when they were prepared.
    private var pending: [String: [String]] = [:]

    init(ledger: GossipLedger = GossipLedger()) { self.ledger = ledger }

    // --- What the app tells the channel ---

    /// Re-derive from the current **Contact** list. Removing a Friend removes their secret
    /// here, and with it every token that would have recognised them — the same "removing the
    /// Contact is the whole of revocation" that `Friend.publicKey` already documents.
    func setContacts(_ friends: [Friend]) {
        let keys = contactKeysOf(friends)
        secrets = secrets.filter { keys.contains($0.key) }
        for key in keys where secrets[key] == nil {
            secrets[key] = ContactIdentity.sharedSecret(with: key)
        }
    }

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
        return true
    }

    /// Forget the whole channel. Called when the last **Contact** goes.
    func forgetAll() async {
        secrets.removeAll()
        pending.removeAll()
        await ledger.forgetAll()
    }

    // --- What the radio asks ---

    /// The offer a connecting device reads first: one token per **Contact**, current bucket.
    func tokenOffer(now: Date) -> Data {
        encodeGossipTokens(gossipTokenOffer(secrets: secrets, now: now))
    }

    /// Which **Contact**, if any, an offer names.
    func resolve(_ offered: [String], now: Date) -> String? {
        gossipResolveOffer(offered, secrets: secrets, now: now)
    }

    /// The batch for one **Contact**, addressed with the token that pair shares so the far end
    /// can tell who is speaking without either side sending a name.
    func outgoing(for contact: String, now: Date) async -> Data? {
        guard let secret = secrets[contact], let bucket = gossipTokenBucket(now) else { return nil }
        let messages = await ledger.offer(to: contact, now: now)
        pending[contact] = messages.map { $0.messageId }
        return encodeGossipBatch(token: gossipToken(secret: secret, bucket: bucket),
                                 messages: messages)
    }

    /// The bytes landed. Only now is anything marked delivered — a handover that died halfway
    /// is offered again the next time these two phones are in the same room.
    func confirmDelivery(to contact: String) async {
        guard let ids = pending.removeValue(forKey: contact) else { return }
        await ledger.delivered(ids, to: contact)
    }

    /// A batch arrived. Returns the **Contact** it came from, or nil if it was not one.
    ///
    /// `expecting` is set when this device already resolved the peer (it is the central and
    /// spent its own token first) and nil when the batch itself is the introduction. Either
    /// way the token in the batch has to resolve, and it has to resolve to the same
    /// **Contact** — a peer that answers a meeting for one Contact with a batch addressed
    /// from another is not having its word taken for it.
    func receive(_ data: Data, expecting: String?, now: Date) async -> String? {
        guard let batch = decodeGossipBatch(data),
              let contact = gossipResolveToken(batch.token, secrets: secrets, now: now),
              expecting == nil || expecting == contact
        else { return nil }

        // The bound the storm gate says in so many words it cannot apply: how *often* a peer
        // may hand something over. Charged on what was offered, not what survived, so a peer
        // that floods with rubbish pays for the rubbish.
        switch gossipAdmit(await ledger.budget(for: contact, now: now), offered: batch.messages.count,
                           now: now) {
        case .cooling, .flooding:
            return contact
        case .admit(let spent):
            await ledger.spend(spent, for: contact, now: now)
        }

        let plan = gossipStormGate(seen: await ledger.seen(now: now), batch: batch.messages,
                                   from: contact, now: now, contacts: Set(secrets.keys),
                                   nightEndFor: { [nightEnds] in nightEnds[$0] })
        await ledger.record(plan, from: contact, now: now)
        return contact
    }
}
