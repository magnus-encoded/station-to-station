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
              formerIds.allSatisfy(isSafeGossipId), ["log", "request", "witness", "receipt", "update"].contains(kind),
              (-1...4096).contains(line), createdAt >= 0, expiresAt > createdAt,
              expiresAt - createdAt <= 108_000_000, author.utf8.count <= 256,
              attribution.utf8.count <= 1024, signature.utf8.count <= 256, record().utf8.count <= 8192,
              fields().allSatisfy({ !$0.contains("\n") && !$0.contains("\t") }),
              id == gossipHash(payload()), let signature = Data(base64Encoded: signature),
              verifyChallenge(payload(), signature: signature, publicKeyBase64: author)
        else { return false }
        if kind == "log" && (line < 0 || text.utf8.count > 512) { return false }
        if kind == "request" && line != -1 { return false }
        // An **Update** says only "this night I already spoke for is now known by that id".
        // It carries no text and no line, so it can never read as a Log line or a second
        // human **Check-in**, and it must name at least one former id other than its own.
        if kind == "update" && (line != -1 || !text.isEmpty || formerIds.isEmpty || formerIds.contains(gigId)) { return false }
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

/// The **Seen with** line: who this phone can name, and how many devices it met but cannot.
///
/// A **Gig** keeps this after its **Gossip** ends, which is what makes it a different thing from
/// presence rather than a persisted copy of it. Presence answers "is someone here now" and must
/// die with the process; this answers "who was there", and a night does not stop having
/// happened because the radio stopped.
struct SeenWith: Equatable {
    let named: [String]
    let others: Int
    var isEmpty: Bool { named.isEmpty && others == 0 }
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
    /// Per **Gig**, every device this phone completed a **Pass** with and when it last did.
    ///
    /// The one genuinely new durable fact in the **Seen with** record. Everything else the line
    /// needs is already in `facts` — a verified **Check-in** is a `request` and `arrivals` finds
    /// it — but a completed **Pass** leaves no **Fact** behind at all. `useful` remembers the
    /// neighbour for two minutes and then prunes it, because that entry is a routing preference;
    /// this is evidence about a night, and evidence does not expire with the radio.
    ///
    /// Keyed by the key the transport proved, which is the peer's night-scoped **Gig** key when
    /// their **Pass** carried their own claim and their nightly relay key otherwise. Those two
    /// never unify — nothing on the wire links them, and inventing the link would be this device
    /// asserting something it cannot see. See `seenWith`.
    var metDevices: [String: [String: Int64]] = [:]

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
        metDevices = try container.decodeIfPresent([String: [String: Int64]].self, forKey: .metDevices) ?? [:]
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
        let latestInScope = scopes.mapValues { $0.max(by: { ($0.createdAt, $0.id) < ($1.createdAt, $1.id) }) }
        let eligible = facts.values.filter { fact in
            guard !isBlocked(fact.author) else { return false }
            let latest = latestInScope[fact.author + "\n" + fact.scope].flatMap { $0 }
            var ids = linkedIds(fact).union(latest.map { Set($0.formerIds + [$0.gigId]) } ?? [])
            // A witness is authored by whoever stood there, so its own author signed no
            // **Update** — the link to the adopted id lives on the claim it carries.
            if fact.kind == "witness", let claim = decodePublicEnvelope(fact.text) {
                ids.formUnion(linkedIds(claim))
            }
            return !ids.intersection(gigIds).isEmpty
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

    /// Write down that a **Pass** with [peer] completed (#498).
    ///
    /// Called for both directions, because a completed **Pass** is a completed **Pass**: the two
    /// phones were in BLE range of each other and each proved a key to the other, and which one
    /// dialled is an artefact of who happened to be scanning. An advertisement is deliberately
    /// not this — the token in it names nobody and a scan hit only says something is
    /// transmitting.
    ///
    /// Which **Gig** it attaches to: the night [peer]'s own claim names, when their **Pass**
    /// carried one, and the active **Gig** otherwise. Only their *own* `request` counts, by the
    /// same one-hop rule `receive` applies — a batch is mostly other people's **Facts** being
    /// **Carried**, and a stranger's relayed claim about last Tuesday says nothing about where
    /// the device handing it over is standing.
    ///
    /// Repeats are a timestamp move, never a second entry: `metDevices` is keyed by device.
    mutating func rememberPass(_ peer: String, batch: [GossipEnvelope], activeGigId: String?, now: Int64) {
        guard !peer.isEmpty else { return }
        let claimed = Set(batch
            .filter { $0.kind == "request" && $0.author == peer && $0.valid() }
            .flatMap { linkedIds($0) })
        let gigs = claimed.isEmpty ? Set([activeGigId].compactMap { $0 }) : claimed
        for gig in gigs {
            var devices = metDevices[gig] ?? [:]
            devices[peer] = max(devices[peer] ?? 0, now)
            metDevices[gig] = devices
        }
    }

    /// Who this phone can say was at [gigIds] with it, and how many more it cannot name (#498).
    ///
    /// Two kinds of evidence, deliberately not merged into one rank. A completed **Pass** is this
    /// device's own eyes: those phones met, and that is true of a stranger's phone as much as a
    /// **Contact**'s. A verified **Check-in** is somebody's signed assertion, which counts for
    /// the **Gig** it names even when a witness **Carried** the last hop — attribution is the
    /// gate, not proximity (#483). So directly met **Contacts** come first, most recently met
    /// first, and **Contacts** known only from a **Check-in** follow.
    ///
    /// Everything is folded onto the durable identity `recognition` resolves a key to before it
    /// is counted, so a **Contact** met on their **Gig** key and again on their relay key is one
    /// person — and one device with no recognition at all is still one device. What cannot be
    /// folded is two keys of the same *stranger*: nothing links them, and this device does not
    /// get to guess. That is the honest reading of "count each device once", not a dedup bug.
    ///
    /// [others] counts unnamed *directly met* devices and is never a subtraction. A **Contact**
    /// known only from a relayed **Check-in** is named while no device of theirs was met, so
    /// `named.count - others` would be a number this phone never observed; and a blocked
    /// **Contact** is dropped from the naming and stays in the count, which is Block being
    /// admission rather than a rewrite of what happened (ADR-0021).
    func seenWith(gigIds: Set<String>, live: [String: String] = [:]) -> SeenWith {
        func identity(_ key: String) -> String { recognition[key] ?? key }
        var directAt: [String: Int64] = [:]
        for gig in gigIds {
            for (device, at) in metDevices[gig] ?? [:] {
                let who = identity(device)
                directAt[who] = max(directAt[who] ?? 0, at)
            }
        }
        var checkedInAt: [String: Int64] = [:]
        for claim in arrivals(gigIds: gigIds) {
            let who = identity(claim.author)
            checkedInAt[who] = max(checkedInAt[who] ?? 0, claim.createdAt)
        }
        func name(_ who: String) -> String? { blocked.contains(who) ? nil : (live[who] ?? contactNames[who]) }
        func byRecencyThenKey(_ a: (key: String, value: Int64), _ b: (key: String, value: Int64)) -> Bool {
            a.value != b.value ? a.value > b.value : a.key < b.key
        }
        let met = directAt.filter { name($0.key) != nil }.sorted(by: byRecencyThenKey)
        let onlyCheckedIn = checkedInAt.filter { directAt[$0.key] == nil && name($0.key) != nil }
            .sorted(by: byRecencyThenKey)
        return SeenWith(
            named: (met + onlyCheckedIn).compactMap { name($0.key) },
            others: directAt.keys.filter { name($0) == nil }.count)
    }

    /// Self-asserted and witnessed are deliberately independent answers.
    func checkInEvidence(gigIds: Set<String>, author: String) -> (asserted: Bool, witnessed: Bool) {
        let claims = facts.values.filter {
            $0.kind == "request" && $0.author == author && !linkedIds($0).intersection(gigIds).isEmpty
        }
        let ids = Set(claims.map(\.id))
        return (!claims.isEmpty, witnessedClaims().contains { ids.contains($0.id) })
    }

    /// Which recognised **Contacts** the envelopes that just arrived put at one of [gigIds] (#484).
    ///
    /// Takes the batch this **Pass** accepted rather than reading `facts`, and that is the whole
    /// design. Presence is a claim about *now*, and a `request` is authored once and admitted
    /// once — so a stored check-in has no freshness left in it, and recomputing presence from
    /// the store would let any later **Pass**, from anyone, relight "is also here" for every
    /// **Contact** who checked in that night. What arrived in this batch is the only thing whose
    /// timing this device can honestly speak to; the caller stamps it with the arrival clock and
    /// `gossipNearby` is what reads those stamps back.
    ///
    /// Attribution is the gate, not proximity: a stranger's check-in resolves to no **Contact**
    /// and therefore to nothing here — until an **Exchange** recognises them, a nearby phone is
    /// a relay key and nothing more. They are still carried and still shown by `arrivals`; they
    /// are simply not named. Blocked authors are dropped on both halves of a witness, the signer
    /// and the claim it carries, against the durable key `isBlocked` resolves through
    /// `recognition` — so a block applied after the **Fact** was stored still takes effect. This
    /// device's own claims never name it present.
    ///
    /// A witness-carried claim counts, so somebody standing between two **Contacts** can be the
    /// reason one sees the other. That does mean presence can outrun the radio by one hop: the
    /// relay may hand over a claim made earlier in the night. It is bounded by `offer`'s
    /// participation deadlines — a relay only carries claims for a **Gig** still running — and by
    /// `gossipNearbyWindow`, which the stamps are read through.
    ///
    /// Nothing re-validates the claim a witness carries, and nothing needs to: `valid()`
    /// validates a witness's inner claim recursively, so `receive` never admits a validly-signed
    /// witness wrapped around a forged one.
    func presenceFrom(accepted: [GossipEnvelope], gigIds: Set<String>) -> Set<String> {
        var here = Set<String>()
        for envelope in accepted where !isBlocked(envelope.author) {
            let claim: GossipEnvelope?
            switch envelope.kind {
            case "request": claim = envelope
            case "witness": claim = decodePublicEnvelope(envelope.text)
            default: claim = nil
            }
            guard let claim, claim.kind == "request", !isBlocked(claim.author),
                  !localAuthors.contains(claim.author),
                  !Set(claim.formerIds + [claim.gigId]).intersection(gigIds).isEmpty,
                  let durable = recognition[claim.author] else { continue }
            here.insert(durable)
        }
        return here
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
        Set(witnessedClaims().filter { localAuthors.contains($0.author) }.flatMap { linkedIds($0) })
    }

    /// The ids one **Fact** stands for, following its own author's **Update** Facts (#497).
    ///
    /// An **Update** is how a night that was only ever local says, while the radio is still
    /// running, that it now also answers to a setlist.fm id — so a **Check-in** made before
    /// the adoption, and the witness that attests it, still find the **Gig** afterwards
    /// without a later **Log** line.
    ///
    /// Author and scope are both required to match, and that is the whole of the rule: an
    /// **Update** may relabel only the assertions its own signer already made. Nobody else's
    /// **Update** can move a stranger's **Check-in** — or their witness — onto a **Gig** they
    /// chose. Applied oldest first, so a chain of adoptions resolves in the order it happened.
    private func linkedIds(_ fact: GossipEnvelope) -> Set<String> {
        var ids = Set(fact.formerIds + [fact.gigId])
        for update in facts.values.filter({ $0.kind == "update" && $0.author == fact.author && $0.scope == fact.scope })
            .sorted(by: { ($0.createdAt, $0.id) < ($1.createdAt, $1.id) }) {
            if update.formerIds.contains(where: ids.contains) { ids.insert(update.gigId) }
        }
        return ids
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

/// The witness this device owes a **request** it just admitted, or nothing (#442, story 8).
///
/// The whole of the self-witness rule in one named place: `PublicGossipState.localClaim(for:)`
/// answers "did *I* check into this **Gig**", and only an answer makes a witness. It lived in
/// `GossipChannel.receivePublic` as a loop over the accepted batch, where no unit test could
/// reach it — a rule deciding what this phone signs for a stranger should not be reachable
/// only through a BLE callback. `sign` is taken per claim rather than given, because the key
/// a witness is signed with is the *local* claim's Gig scope, which the caller cannot know
/// before this function has picked the claim.
///
/// Nothing about the request's own admissibility is re-decided here: `receive` already applied
/// the one-hop rule that a request is believed only from its author, and it is the caller's
/// business to have admitted it first.
func witnessFor(_ state: PublicGossipState, request: GossipEnvelope, now: Int64,
                sign: (GossipEnvelope) -> (Data) -> Data?) -> GossipEnvelope? {
    guard let local = state.localClaim(for: request) else { return nil }
    return witnessRequest(request, with: local, now: now, sign: sign(local))
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
