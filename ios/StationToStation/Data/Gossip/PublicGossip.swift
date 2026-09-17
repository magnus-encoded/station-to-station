import Foundation
import CryptoKit

let publicGossipHeader = "station-to-station/gossip-fact/2"
let publicGossipPassHeader = "station-to-station/gossip-pass/2"
let publicCarryMs: Int64 = 15 * 60 * 1000

/// How long one receipt's credit for a neighbour survives (#444, story 41).
///
/// **Provisional.** Nothing has measured it. Two minutes is a guess about how long a crowd
/// holds still, to be replaced by a figure from a real night; `sim/SWEEPS.md`, on the
/// `gossip-sim` branch, compares policies and is not evidence for it. See
/// `docs/adr/0022-gossip-receipts.md`.
///
/// Its own constant rather than a fraction of `publicCarryMs` or of the grace period, because
/// the three answer different questions and tying any two together means tuning one silently
/// retunes another. **Android holds the same two minutes** (`PUBLIC_RECEIPT_MS`).
let publicReceiptMs: Int64 = 2 * 60 * 1000

func publicGossipAuthPayload(_ nonce: Data) -> Data {
    Data("station-to-station/gossip-auth/2\n\(nonce.base64EncodedString())".utf8)
}
struct PublicGossipChallenge { var nonce: Data; var from: String }
private func publicChallengeProof(_ nonce: Data) -> Data {
    Data("station-to-station/gossip-challenge-proof/2\n\(nonce.base64EncodedString())".utf8)
}
func encodePublicGossipChallenge(nonce: Data, from: String, sign: (Data) -> Data?) -> Data? {
    guard nonce.count == 32, let proof = sign(publicChallengeProof(nonce)) else { return nil }
    let bytes = Data("station-to-station/gossip-challenge/2\n\(nonce.base64EncodedString())\n\(from)\n\(proof.base64EncodedString())".utf8)
    return bytes.count <= 512 ? bytes : nil
}
func decodePublicGossipChallenge(_ bytes: Data) -> PublicGossipChallenge? {
    guard bytes.count <= 512, let text = String(data: bytes, encoding: .utf8) else { return nil }
    let fields = text.components(separatedBy: "\n")
    guard fields.count == 4, fields[0] == "station-to-station/gossip-challenge/2",
          let nonce = Data(base64Encoded: fields[1]), nonce.count == 32,
          let proof = Data(base64Encoded: fields[3]),
          verifyChallenge(publicChallengeProof(nonce), signature: proof, publicKeyBase64: fields[2])
    else { return nil }
    return PublicGossipChallenge(nonce: nonce, from: fields[2])
}

struct GossipEnvelope: Codable, Equatable, Identifiable {
    var id = ""
    var gigId: String
    var formerIds: [String] = []
    var scope: String
    var author: String
    var createdAt: Int64
    var expiresAt: Int64
    var kind: String
    var line: Int = -1
    var text = ""
    var attribution = ""
    var signature = ""

    func fields() -> [String] {
        [gigId, formerIds.joined(separator: ","), scope, author, String(createdAt), String(expiresAt),
         kind, String(line), Data(text.utf8).base64EncodedString(), attribution]
    }
    func payload() -> Data { Data(([publicGossipHeader] + fields()).joined(separator: "\n").utf8) }
    func record() -> String { ([id] + fields() + [signature]).joined(separator: "\t") }
    func signed(_ sign: (Data) -> Data?) -> GossipEnvelope? {
        guard let signature = sign(payload()) else { return nil }
        var result = self
        result.id = gossipHash(payload())
        result.signature = signature.base64EncodedString()
        return result
    }
    func sameGig(_ other: GossipEnvelope) -> Bool {
        !(Set(formerIds + [gigId]).intersection(other.formerIds + [other.gigId])).isEmpty
    }
    func valid() -> Bool {
        guard isSafeGossipId(gigId), isSafeGossipId(scope), formerIds.count <= 32,
              formerIds.allSatisfy(isSafeGossipId), ["log", "request", "witness", "receipt"].contains(kind),
              (-1...4096).contains(line), createdAt >= 0, expiresAt > createdAt,
              expiresAt - createdAt <= 108_000_000, author.utf8.count <= 256,
              attribution.utf8.count <= 1024, signature.utf8.count <= 256, record().utf8.count <= 8192,
              fields().allSatisfy({ !$0.contains("\n") && !$0.contains("\t") }),
              id == gossipHash(payload()), let signature = Data(base64Encoded: signature),
              verifyChallenge(payload(), signature: signature, publicKeyBase64: author)
        else { return false }
        if kind == "log" && (line < 0 || text.utf8.count > 512) { return false }
        if kind == "request" && line != -1 { return false }
        if ["witness", "receipt"].contains(kind) && line != -1 { return false }
        if kind == "witness" {
            guard let claim = decodePublicEnvelope(text), claim.kind == "request", claim.author != author,
                  claim.valid(), sameGig(claim), createdAt >= claim.createdAt,
                  createdAt < claim.expiresAt, expiresAt <= claim.expiresAt else { return false }
        }
        return true
    }
}
func gossipHash(_ bytes: Data) -> String { SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined() }
func decodePublicEnvelope(_ record: String) -> GossipEnvelope? {
    guard record.utf8.count <= 8192 else { return nil }
    let f = record.components(separatedBy: "\t")
    guard f.count == 12, let created = Int64(f[5]), let expires = Int64(f[6]), let line = Int(f[8]),
          let textData = Data(base64Encoded: f[9]), let text = String(data: textData, encoding: .utf8)
    else { return nil }
    return GossipEnvelope(id: f[0], gigId: f[1], formerIds: f[2].split(separator: ",").map(String.init),
                          scope: f[3], author: f[4], createdAt: created, expiresAt: expires,
                          kind: f[7], line: line, text: text, attribution: f[10], signature: f[11])
}
struct PublicGossipPass {
    var from: String
    var proof: String
    var batch: [GossipEnvelope]
}

struct PublicGossipDelivery {
    let from: String
    let pass: PublicGossipPass
}
func encodePublicGossipPass(_ pass: PublicGossipPass) -> Data? {
    guard [pass.from, pass.proof].allSatisfy({ !$0.isEmpty && $0.utf8.count <= 256 && !$0.contains("\t") && !$0.contains("\n") }) else { return nil }
    var bytes = Data("\(publicGossipPassHeader)\n\(pass.from)\t\(pass.proof)".utf8)
    for envelope in pass.batch.prefix(gossipMaxBatch) {
        let record = Data(envelope.record().utf8)
        guard record.count <= 8192, bytes.count + record.count + 1 <= gossipMaxWireBytes else { break }
        bytes.append(10)
        bytes.append(record)
    }
    return bytes
}
func decodePublicGossipPass(_ bytes: Data) -> PublicGossipPass? {
    guard bytes.count <= gossipMaxWireBytes, let text = String(data: bytes, encoding: .utf8) else { return nil }
    let lines = text.components(separatedBy: "\n")
    guard lines.count >= 2, lines[0] == publicGossipPassHeader else { return nil }
    let claim = lines[1].components(separatedBy: "\t")
    guard claim.count == 2, claim.allSatisfy({ !$0.isEmpty && $0.utf8.count <= 256 }) else { return nil }
    return PublicGossipPass(from: claim[0], proof: claim[1], batch: lines.dropFirst(2).prefix(gossipMaxBatch).compactMap(decodePublicEnvelope))
}
struct PublicHeld: Codable {
    var envelope: GossipEnvelope
    var until: Int64
    var delivered: Set<String> = []
}
struct PublicGossipState: Codable {
    var facts: [String: GossipEnvelope] = [:]
    var seen: [String: Int64] = [:]
    var held: [String: PublicHeld] = [:]
    var blocked: Set<String> = []
    var recognition: [String: String] = [:]
    var useful: [String: Int64] = [:]
    /// Authors backed by a private Gig key on this phone. Durable so a CoreBluetooth
    /// restoration can still tell my claim from a stranger's after relaunch.
    var localAuthors: Set<String> = []
    /// What a recognised **Contact** was called, held here rather than looked up.
    ///
    /// Recognition cannot be revoked (story 16), so the name it resolves to must not depend on
    /// the **Contact** still being on this device. Removing someone deletes their `Friend`
    /// record, and with it the only live source of their name; without this map their
    /// already-attributed **Facts** would quietly become "Nearby listener" — the app
    /// pretending not to know something it does know. Local only: this never goes on the wire,
    /// which carries `GossipEnvelope` and nothing else.
    var contactNames: [String: String] = [:]

    init() {}

    /// Every field read with `decodeIfPresent`, because Swift's synthesized `init(from:)`
    /// throws on a missing key even where the property has a default — and `GossipLedger`
    /// decodes the whole file with `try?`, so one absent key would silently empty a night's
    /// record instead of adding a field to it.
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        facts = try container.decodeIfPresent([String: GossipEnvelope].self, forKey: .facts) ?? [:]
        seen = try container.decodeIfPresent([String: Int64].self, forKey: .seen) ?? [:]
        held = try container.decodeIfPresent([String: PublicHeld].self, forKey: .held) ?? [:]
        blocked = try container.decodeIfPresent(Set<String>.self, forKey: .blocked) ?? []
        recognition = try container.decodeIfPresent([String: String].self, forKey: .recognition) ?? [:]
        useful = try container.decodeIfPresent([String: Int64].self, forKey: .useful) ?? [:]
        localAuthors = try container.decodeIfPresent(Set<String>.self, forKey: .localAuthors) ?? []
        contactNames = try container.decodeIfPresent([String: String].self, forKey: .contactNames) ?? [:]
    }

    func isBlocked(_ author: String) -> Bool {
        blocked.contains(author) || recognition[author].map { blocked.contains($0) } == true
    }

    /// Who this device says authored [author]'s **Facts**: the live **Contact** name where
    /// there still is one, the name held from recognition otherwise, and `nil` for a stranger,
    /// whose **Facts** are shown but unattributed.
    ///
    /// Not named `attribution`: that is `GossipEnvelope`'s sealed proof on the wire.
    func attributedName(_ author: String, live: [String: String] = [:]) -> String? {
        recognition[author].map { live[$0] ?? contactNames[$0] } ?? nil
    }

    mutating func recognizeContacts(_ contacts: Set<String>, names: [String: String] = [:]) {
        let envelopes = Array(facts.values) + held.values.map(\.envelope)
        let claims = envelopes.filter { $0.kind == "witness" && $0.valid() }.compactMap { decodePublicEnvelope($0.text) }
        for envelope in envelopes + claims where recognition[envelope.author] == nil {
            if envelope.valid(), let durable = recognizeGossip(envelope, contacts: contacts) {
                recognition[envelope.author] = durable
            }
        }
        // Refreshed for everyone recognised, not only the authors matched just now, so a
        // **Contact** who renames themselves is followed while they are still here. Only ever
        // written, never removed: that is the half that has to outlive them.
        for durable in Set(recognition.values) {
            if let name = names[durable] { contactNames[durable] = name }
        }
    }

    mutating func prune(now: Int64) {
        seen = seen.filter { $0.value > now }
        held = held.filter { $0.value.until > now && $0.value.envelope.expiresAt > now }
        useful = useful.filter { $0.value > now }
    }
    @discardableResult
    mutating func receive(_ envelope: GossipEnvelope, from: String, now: Int64, local: Bool = false) -> Bool {
        prune(now: now)
        guard envelope.valid(), envelope.expiresAt > now, envelope.createdAt <= now + 300000 else { return false }
        // The transport has proved `from`; only the author can deliver one-hop controls.
        if !local && ["request", "receipt"].contains(envelope.kind) && from != envelope.author { return false }
        if seen[envelope.id] != nil { held.removeValue(forKey: envelope.id); return false }
        guard seen.count < 8192 else { return false }
        seen[envelope.id] = envelope.expiresAt
        if envelope.kind != "receipt" && !isBlocked(envelope.author) { facts[envelope.id] = envelope }
        if local { localAuthors.insert(envelope.author) }
        if local || !["request", "receipt"].contains(envelope.kind) {
            held[envelope.id] = PublicHeld(envelope: envelope, until: min(envelope.expiresAt, now + publicCarryMs), delivered: [from])
            while held.count > 128 || held.values.reduce(0, { $0 + $1.envelope.record().utf8.count }) > 128000 {
                guard let oldest = held.min(by: { ($0.value.until, $0.key) < ($1.value.until, $1.key) })?.key else { break }
                held.removeValue(forKey: oldest)
            }
        }
        // Credit is always for a neighbour *this* device should prefer, which is why the two
        // directions read different fields. A receipt this phone authored names the neighbour
        // that delivered the Fact, in `text`. A receipt arriving over the air names its own
        // sender, and `from` is the handle the transport proved — never `text`, which on a
        // stranger's receipt would be some third party's handle this device cannot route to.
        if envelope.kind == "receipt" {
            let neighbour = local ? envelope.text : from
            if !neighbour.isEmpty { useful[neighbour] = min(envelope.expiresAt, now + publicReceiptMs) }
        }
        return true
    }
    mutating func offer(to peer: String, now: Int64, participationEnds: [String: Int64] = [:]) -> [GossipEnvelope] {
        prune(now: now)
        // The encoder owns the byte budget, including the actual relay proof header.
        return Array(held.values.filter { held in
            let deadlines = (held.envelope.formerIds + [held.envelope.gigId]).compactMap { participationEnds[$0] }
            // A receipt is addressed, not gossiped: it names one neighbour and is worth
            // nothing to anyone else, who would only learn that this phone stood near them.
            let addressed = held.envelope.kind != "receipt" || held.envelope.text == peer
            return addressed && !held.delivered.contains(peer) && (deadlines.isEmpty || deadlines.contains { now < $0 })
        }.sorted {
            ($0.envelope.createdAt, $0.envelope.id) > ($1.envelope.createdAt, $1.envelope.id)
        }.prefix(gossipMaxBatch).map { $0.envelope })
    }
    mutating func delivered(to peer: String, ids: [String]) {
        for id in ids { held[id]?.delivered.insert(peer) }
    }
    func project(gigIds: Set<String>) -> [GossipEnvelope] {
        let scopes = Dictionary(grouping: facts.values, by: { $0.author + "\n" + $0.scope })
        let eligible = scopes.values.flatMap { versions -> [GossipEnvelope] in
            guard let latest = versions.max(by: { ($0.createdAt, $0.id) < ($1.createdAt, $1.id) }),
                  !Set(latest.formerIds + [latest.gigId]).intersection(gigIds).isEmpty else { return [] }
            return versions.filter { !isBlocked($0.author) }
        }
        let lines = Dictionary(grouping: eligible, by: { $0.author + "\n" + $0.scope + "\n" + ($0.kind == "log" ? "line:\($0.line)" : $0.id) })
        return lines.values.compactMap { $0.max(by: { ($0.createdAt, $0.id) < ($1.createdAt, $1.id) }) }
            .sorted { ($0.createdAt, $0.id) < ($1.createdAt, $1.id) }
    }

    func localClaim(for request: GossipEnvelope) -> GossipEnvelope? {
        facts.values.filter { $0.kind == "request" && localAuthors.contains($0.author) && $0.sameGig(request) }
            .max { ($0.createdAt, $0.id) < ($1.createdAt, $1.id) }
    }

    /// A relayed witness carries the arrival even when its one-hop request never reached us.
    func arrivals(gigIds: Set<String>) -> [GossipEnvelope] {
        var seen = Set<String>()
        return project(gigIds: gigIds).compactMap { fact -> GossipEnvelope? in
            let claim: GossipEnvelope?
            switch fact.kind {
            case "request": claim = fact
            case "witness": claim = decodePublicEnvelope(fact.text)
            default: claim = nil
            }
            guard let claim, !isBlocked(claim.author), !localAuthors.contains(claim.author),
                  seen.insert(claim.id).inserted else { return nil }
            return claim
        }
    }

    /// Self-asserted and witnessed are deliberately independent answers.
    func checkInEvidence(gigIds: Set<String>, author: String) -> (asserted: Bool, witnessed: Bool) {
        let claims = facts.values.filter {
            $0.kind == "request" && $0.author == author && !Set($0.formerIds + [$0.gigId]).intersection(gigIds).isEmpty
        }
        let ids = Set(claims.map(\.id))
        return (!claims.isEmpty, witnessedClaims().contains { ids.contains($0.id) })
    }

    /// The claims some directly-present device signed a witness for, whoever wrote them.
    private func witnessedClaims() -> [GossipEnvelope] {
        facts.values.filter { $0.kind == "witness" && !isBlocked($0.author) }.compactMap { decodePublicEnvelope($0.text) }
    }

    /// Every **Gig** id this phone claimed and a directly-present device witnessed (#442).
    ///
    /// One pass for the whole timeline, because asking per **Gig** would mint a **Gig**
    /// identity per row just to learn its author key. Both the claim's current id and the
    /// ids it was known by before are returned, so a setlist.fm id arriving after the night
    /// still matches the row it belongs to.
    ///
    /// Self-assertion is deliberately not in here. That is `StoredAttendance`'s answer and
    /// this decorates it; a night nobody witnessed is still a night the user says they
    /// were at.
    func witnessedGigIds() -> Set<String> {
        Set(witnessedClaims().filter { localAuthors.contains($0.author) }
            .flatMap { $0.formerIds + [$0.gigId] })
    }
}

/// Which of this device's own claims a **Pass** is signed for (#442).
///
/// A one-hop request is admissible to the receiver only when the **Pass** proves the request
/// author's key, so carrying a request means signing as its author rather than as the nightly
/// relay. Returns the request to sign as, or `nil` to sign as the relay.
func passAuthor(_ batch: [GossipEnvelope], localAuthors: Set<String>) -> GossipEnvelope? {
    batch.first { $0.kind == "request" && localAuthors.contains($0.author) }
}

/// The key a **Pass** proves that this device can also *address* later, or `nil` when it
/// proves one it cannot.
///
/// A receipt is delivered by naming a peer and waiting to meet it, and the only identity a
/// meeting ever presents is the one in the challenge, which is always the nightly relay key.
/// A **Pass** is signed as the relay too — except when it carries its signer's own request,
/// the one case `passAuthor` reaches for a Gig key for. That Gig key is a *signing* namespace,
/// never an addressing one: a receipt naming it names something no peer will ever equal, so it
/// would sit in `held` until it expired and its credit would sit in `useful` unreadable.
///
/// So the relay key is read off the wire rule rather than guessed: a **Pass** the receiver
/// would admit a request from is signed as a Gig, and this device has no way to address its
/// sender. It authors no receipt then, rather than an undeliverable one. The neighbour's next
/// push carries no request — `passBatch` admits none without one to sign as — and is therefore
/// addressable.
func passRelay(_ pass: PublicGossipPass) -> String? {
    pass.batch.contains { $0.kind == "request" && $0.author == pass.from } ? nil : pass.from
}

/// The batch `passAuthor` leaves admissible, given the request it chose to sign as and the
/// key `signer` the **Pass** will actually be signed with.
///
/// Both one-hop kinds are governed here for the same reason: the receiver admits a `request`
/// or a `receipt` only when the **Pass** proves its author. A receipt whose turn this is not
/// is not lost — it waits for a **Pass** signed as the relay, as another device's request does.
///
/// `signer` has no default on purpose. An empty signer matches no author, so a defaulted call
/// silently drops every receipt in the batch — a whole feature turned off by an argument nobody
/// typed. Requiring it means a caller has to say which key the **Pass** is signed with.
func passBatch(_ batch: [GossipEnvelope], request: GossipEnvelope?, signer: String) -> [GossipEnvelope] {
    batch.filter { envelope in
        switch envelope.kind {
        case "request": return request.map { envelope.author == $0.author } ?? false
        case "receipt": return envelope.author == signer
        default: return true
        }
    }
}

/// The receipt owed for a **Fact** this device has just admitted, or nothing (#444, stories 35-37).
///
/// Pure and one-shot: handed the delivering neighbour and whether the Fact was recognised as a
/// **Contact**'s, it authors a **Fact** of kind `receipt` naming that neighbour. It cannot be
/// reached except from the receive path, which is what makes story 37 structural rather than a
/// rule — attribution that arrives later, through `recognizeContacts` after an **Exchange**,
/// runs somewhere else entirely and authors nothing. There is deliberately no timestamp
/// comparison here; one would imply lateness is reachable.
///
/// `author` is the key the **Pass** carrying this will be signed with, because the receiver
/// admits a receipt only from its author; `passBatch` holds the other half of that bargain.
func receiptFor(_ fact: GossipEnvelope, from: String, recognised: Bool, author: String,
                now: Int64, sign: (Data) -> Data?) -> GossipEnvelope? {
    guard recognised, fact.kind != "receipt", !from.isEmpty, !author.isEmpty,
          ![from, author].contains(where: { $0.contains("\n") || $0.contains("\t") })
    else { return nil }
    var result = GossipEnvelope(gigId: fact.gigId, formerIds: fact.formerIds, scope: fact.scope,
        author: author, createdAt: now, expiresAt: now + publicReceiptMs, kind: "receipt", text: from)
    return result.signed(sign)
}

/// The receipts owed to one neighbour for one batch, which is at most one per **Gig** record.
///
/// `receiptFor` copies `gigId`, `formerIds` and `scope` from the **Fact** and fills everything
/// else from the batch, so two **Facts** of the same record — two lines of one `log`, the
/// ordinary case — author byte-identical receipts with the same `id`. Fed one at a time into
/// `receive`, the second is a duplicate, and the Storm gate answers a duplicate by dropping the
/// held copy: two recognised **Facts** from a neighbour used to yield no receipt at all.
///
/// A receipt says "this neighbour handed me something I wanted", which is a fact about the
/// neighbour and not about the line, so one per record is the whole of what there was to say.
/// De-duplicating here rather than in `receive` keeps `receiptFor` pure and leaves the Storm
/// gate exactly where it was.
///
/// Every filter `receiptFor` would apply runs *before* the de-duplication, never after. A batch
/// admitted from a neighbour can contain a `receipt` of its own, and a receipt carries the
/// `gigId`, `formerIds` and `scope` of the **Fact** it was for — so it can share a record
/// identity with a **Fact** in the same batch. De-duplicating first would let it win the slot
/// and then yield nothing, silently swallowing the receipt that record actually owed.
func receiptsFor(_ facts: [GossipEnvelope], from: String, recognised: (GossipEnvelope) -> Bool,
                 author: String, now: Int64, sign: (Data) -> Data?) -> [GossipEnvelope] {
    var records = Set<String>()
    return facts.filter { $0.kind != "receipt" && recognised($0) }
        .filter { records.insert([$0.gigId, $0.formerIds.joined(separator: ","), $0.scope]
            .joined(separator: "\u{1}")).inserted }
        .compactMap { receiptFor($0, from: from, recognised: true, author: author, now: now, sign: sign) }
}

/// A witness is a separate signed fact containing the complete signed request.
func witnessRequest(_ request: GossipEnvelope, with witness: GossipEnvelope, now: Int64,
                    sign: (Data) -> Data?) -> GossipEnvelope? {
    guard request.kind == "request", witness.kind == "request", request.valid(),
          request.author != witness.author, request.sameGig(witness), now >= request.createdAt,
          now < request.expiresAt else { return nil }
    var result = GossipEnvelope(gigId: witness.gigId, formerIds: witness.formerIds,
        scope: witness.scope, author: witness.author, createdAt: now,
        expiresAt: min(request.expiresAt, witness.expiresAt), kind: "witness",
        text: request.record(), attribution: witness.attribution)
    return result.signed(sign)
}

func gossipLogChanges(before: StoredLog, after: StoredLog) -> [Int: String] {
    let old = Dictionary(uniqueKeysWithValues: before.songs.indices.map { (before.lineNumberAt($0), before.songs[$0]) })
    let new = Dictionary(uniqueKeysWithValues: after.songs.indices.map { (after.lineNumberAt($0), after.songs[$0]) })
    return Dictionary(uniqueKeysWithValues: Set(old.keys).union(new.keys).compactMap { line in
        old[line] == new[line] ? nil : (line, new[line] ?? "")
    })
}


struct GossipLogRow {
    var base: Int?
    var text: String?
    var facts: [GossipEnvelope] = []
}

/// Align complete author sequences, keeping reprises and the source of each observation.
func weaveGossip(base: [String?], facts: [GossipEnvelope]) -> [GossipLogRow] {
    var rows = base.enumerated().map { GossipLogRow(base: $0.offset, text: $0.element) }
    let authors = Dictionary(grouping: facts.filter { $0.kind == "log" }, by: { $0.author + "\n" + $0.scope })
    for author in authors.keys.sorted() {
        let source = authors[author]!.sorted { $0.line < $1.line }
        rows = weaveSetlist(published: rows.map(\.text), logged: source.map(\.text)).map { match in
            let previous = match.published.map { rows[$0] }
            let fact = match.logged.map { source[$0] }
            return GossipLogRow(base: previous?.base, text: previous?.text ?? fact?.text,
                facts: (previous?.facts ?? []) + (fact.map { [$0] } ?? []))
        }
    }
    return rows
}
