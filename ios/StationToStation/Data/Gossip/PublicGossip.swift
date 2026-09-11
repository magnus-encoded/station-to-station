import Foundation
import CryptoKit

let publicGossipHeader = "station-to-station/gossip-fact/2"
let publicGossipPassHeader = "station-to-station/gossip-pass/2"
let publicCarryMs: Int64 = 15 * 60 * 1000

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
        if envelope.kind != "receipt" && !blocked.contains(envelope.author) { facts[envelope.id] = envelope }
        if local { localAuthors.insert(envelope.author) }
        if local || !["request", "receipt"].contains(envelope.kind) {
            held[envelope.id] = PublicHeld(envelope: envelope, until: min(envelope.expiresAt, now + publicCarryMs), delivered: [from])
            while held.count > 128 || held.values.reduce(0, { $0 + $1.envelope.record().utf8.count }) > 128000 {
                guard let oldest = held.min(by: { ($0.value.until, $0.key) < ($1.value.until, $1.key) })?.key else { break }
                held.removeValue(forKey: oldest)
            }
        }
        if envelope.kind == "receipt" { useful[envelope.text] = min(envelope.expiresAt, now + 120000) }
        return true
    }
    mutating func offer(to peer: String, now: Int64) -> [GossipEnvelope] {
        prune(now: now)
        // The encoder owns the byte budget, including the actual relay proof header.
        return Array(held.values.filter { !$0.delivered.contains(peer) }.sorted {
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
            return versions.filter { !blocked.contains($0.author) }
        }
        let lines = Dictionary(grouping: eligible, by: { $0.author + "\n" + $0.scope + "\n" + ($0.kind == "log" ? "line:\($0.line)" : $0.id) })
        return lines.values.compactMap { $0.max(by: { ($0.createdAt, $0.id) < ($1.createdAt, $1.id) }) }
            .sorted { ($0.createdAt, $0.id) < ($1.createdAt, $1.id) }
    }

    func localClaim(for request: GossipEnvelope) -> GossipEnvelope? {
        facts.values.filter { $0.kind == "request" && localAuthors.contains($0.author) && $0.sameGig(request) }
            .max { ($0.createdAt, $0.id) < ($1.createdAt, $1.id) }
    }

    /// Self-asserted and witnessed are deliberately independent answers.
    func checkInEvidence(gigIds: Set<String>, author: String) -> (asserted: Bool, witnessed: Bool) {
        let claims = facts.values.filter {
            $0.kind == "request" && $0.author == author && !Set($0.formerIds + [$0.gigId]).intersection(gigIds).isEmpty
        }
        let ids = Set(claims.map(\.id))
        let witnessed = facts.values.contains {
            $0.kind == "witness" && decodePublicEnvelope($0.text).map { ids.contains($0.id) } == true
        }
        return (!claims.isEmpty, witnessed)
    }
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
