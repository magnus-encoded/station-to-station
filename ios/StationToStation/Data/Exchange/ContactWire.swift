import Foundation
import Network

/// The bytes on the wire, ported frame for frame from Android's `HandoverWire.kt`
/// framing and `ContactWire.kt` authentication (#265).
///
/// **This file is a cross-platform contract even though only iOS↔iOS is exercised.** The
/// framing is a 4-byte big-endian length followed by that many bytes; an item body is
/// streamed raw at its declared length rather than framed, so a 4 GB recording never
/// becomes a 4 GB allocation. Both are Android's, unchanged, because a wire format is the
/// one thing a later interop pass cannot renegotiate cheaply.
///
/// What differs is only how the bytes are moved: Android has blocking streams over an
/// `SSLSocket`, and `Network.framework` has neither. `NWConnection`'s completion handlers
/// are wrapped as `async` here and nowhere else, so everything above this file reads like
/// the Kotlin it was ported from.

/// Small control frames only — the manifest, item headers, the auth exchange. Item bodies
/// are streamed separately and are not subject to it.
///
/// ponytail: 8 MiB is Android's figure, and Android's guess: bigger than any real
/// manifest, small enough to refuse a hostile length outright. A library large enough to
/// blow this on the manifest alone would need the manifest itself chunked.
private let maxFrameBytes = 8 * 1024 * 1024

/// One end of a live reconcile session. A class rather than a struct because it owns a
/// connection, and because the receive side needs somewhere to keep the leftovers of a
/// read that overshot a frame boundary.
final class ContactConnection {
    private let connection: NWConnection
    /// The certificate *this* side presented, which is what the peer fingerprints.
    let ownCertificate: Data

    init(connection: NWConnection, ownCertificate: Data) {
        self.connection = connection
        self.ownCertificate = ownCertificate
    }

    func cancel() { connection.cancel() }

    // MARK: - Framing

    func writeFrame(_ bytes: Data) async throws {
        var header = Data(count: 4)
        let length = UInt32(bytes.count).bigEndian
        withUnsafeBytes(of: length) { header.replaceSubrange(0..<4, with: $0) }
        try await send(header + bytes)
    }

    /// Nil on a clean close between frames. Throws if the connection dies mid-frame —
    /// that is not "no more items", it is a dropped session, and the two must not share
    /// a return value.
    func readFrame() async throws -> Data? {
        guard let header = try await receiveExactly(4, allowClose: true) else { return nil }
        let length = header.withUnsafeBytes { Int($0.loadUnaligned(as: UInt32.self).bigEndian) }
        guard length >= 0, length <= maxFrameBytes else {
            throw ContactWireError.refusedFrame(length)
        }
        if length == 0 { return Data() }
        guard let body = try await receiveExactly(length, allowClose: false) else {
            throw ContactWireError.closedMidFrame
        }
        return body
    }

    /// Reads exactly `length` bytes off the wire and hands each chunk to `sink` as it
    /// arrives — never buffering the whole item, which is the entire reason an item body
    /// is not just another frame.
    func readBody(length: Int, into sink: (Data) throws -> Void) async throws {
        var remaining = length
        while remaining > 0 {
            let chunk = min(remaining, 64 * 1024)
            guard let data = try await receiveExactly(chunk, allowClose: false) else {
                throw ContactWireError.closedMidFrame
            }
            try sink(data)
            remaining -= data.count
        }
    }

    /// Streams `length` bytes from `source` straight out, at 64 KiB a time. The sender
    /// never holds the whole file either.
    func writeBody(length: Int, from source: () throws -> Data?) async throws {
        var remaining = length
        while remaining > 0 {
            guard let chunk = try source(), !chunk.isEmpty else {
                throw ContactWireError.shortBody(missing: remaining)
            }
            try await send(chunk)
            remaining -= chunk.count
        }
    }

    // MARK: - NWConnection, made to look like a stream

    private func send(_ data: Data) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.send(content: data, completion: .contentProcessed { error in
                if let error { continuation.resume(throwing: error) }
                else { continuation.resume() }
            })
        }
    }

    /// `minimumIncompleteLength == maximumLength` is `NWConnection`'s way of saying "this
    /// many bytes, no fewer" — the equivalent of `DataInputStream.readFully`, so no
    /// re-buffering of overshoot is needed anywhere in this file.
    ///
    /// `allowClose` separates the two closes that matter: a peer hanging up cleanly
    /// *between* frames is a normal end of session, and one hanging up inside a frame is
    /// a dropped connection.
    private func receiveExactly(_ length: Int, allowClose: Bool) async throws -> Data? {
        try await withCheckedThrowingContinuation { continuation in
            connection.receive(minimumIncompleteLength: length, maximumLength: length) {
                data, _, isComplete, error in
                if let error { continuation.resume(throwing: error); return }
                if let data, data.count == length { continuation.resume(returning: data); return }
                if isComplete && allowClose { continuation.resume(returning: nil); return }
                continuation.resume(throwing: ContactWireError.closedMidFrame)
            }
        }
    }
}

enum ContactWireError: Error {
    case refusedFrame(Int)
    case closedMidFrame
    case shortBody(missing: Int)
}

// MARK: - Session authentication

/// The proving half: wait for the peer's nonce, then answer with a signature over
/// `fingerprint(my own certificate) + nonce`. The peer recomputes that fingerprint from
/// the certificate they received and checks the answer against it.
private func proveContactIdentity(_ wire: ContactConnection) async throws {
    guard let nonce = try await wire.readFrame() else { throw ContactWireError.closedMidFrame }
    guard let signature = ContactIdentity.sign(certFingerprint(wire.ownCertificate) + nonce)
    else { throw ContactWireError.closedMidFrame }
    try await wire.writeFrame(signature)
}

/// The checking half: send a fresh nonce, then test the answer against every key in
/// `candidates` — the persisted `publicKey` of every Contact this device currently holds.
///
/// mDNS carries no identity, so the verifier does not know *which* Contact is on the far
/// end until this returns. Nil means none of them matched: a peer that is not (yet)
/// anybody's Contact, dropped without a reason surfaced back to it.
private func verifyContactIdentity(_ wire: ContactConnection, peerCertificate: Data,
                                   candidates: [String]) async throws -> String? {
    guard let nonce = contactNonce() else { return nil }
    try await wire.writeFrame(nonce)
    guard let signature = try await wire.readFrame() else { return nil }
    let expected = certFingerprint(peerCertificate) + nonce
    return candidates.first { verifyChallenge(expected, signature: signature, publicKeyBase64: $0) }
}

/// Both directions over one connection, in a fixed order so the two ends never both wait
/// on a read: the server round (server verifies, client proves) always goes first, then
/// the client round. Every caller on both ends runs this same function and only says
/// which side of the connection it is.
///
/// Returns the peer's matched Contact key, or nil the moment either round fails.
///
/// A failed verify cancels the connection before returning. Without that, the losing
/// side's own still-pending round blocks on a read the other end — having already bailed
/// out — will never answer: a real deadlock, not a hypothetical one, since the server
/// round always finishes first and the client round is what would be left hanging.
func mutualContactAuth(_ wire: ContactConnection, isServer: Bool, peerCertificate: Data,
                       candidates: [String]) async throws -> String? {
    if isServer {
        guard let matched = try await verifyContactIdentity(
            wire, peerCertificate: peerCertificate, candidates: candidates
        ) else { wire.cancel(); return nil }
        try await proveContactIdentity(wire)
        return matched
    }
    try await proveContactIdentity(wire)
    guard let matched = try await verifyContactIdentity(
        wire, peerCertificate: peerCertificate, candidates: candidates
    ) else { wire.cancel(); return nil }
    return matched
}

// MARK: - Manifest, requests and items

/// `encodeDefaults = true` is kotlinx's default-off switch that Android turns on; Swift's
/// encoder writes every non-nil property already, so the two agree without a setting.
private let wireEncoder = JSONEncoder()
private let wireDecoder = JSONDecoder()

/// One item's header, then its bytes. The same two fields Android writes.
struct ItemHeader: Codable {
    let id: String
    let bytes: Int64
}

/// Marker frame: no more items are coming, sent in place of a header. Explicit rather
/// than inferred from a closed connection, because the connection may still carry frames
/// afterwards — "done sending items" and "hanging up" are different events.
private let endOfItems = Data()

func writeEndOfItems(_ wire: ContactConnection) async throws {
    try await wire.writeFrame(endOfItems)
}

/// Nil once the end-of-items marker arrives — **and only then**: the sender is genuinely
/// done. A dropped connection surfaces as a thrown error instead, and so does a header
/// that will not decode, which Android's `readItemHeader` also treats as fatal.
///
/// The distinction is the whole point of the signature. A header that failed to parse is
/// followed on the wire by a body nobody is going to read; returning nil for it would end
/// the loop with those bytes still queued, and every frame read after that would be
/// somebody's photograph interpreted as a length.
func readItemHeader(_ wire: ContactConnection) async throws -> ItemHeader? {
    guard let frame = try await wire.readFrame() else { throw ContactWireError.closedMidFrame }
    if frame.isEmpty { return nil }
    guard let header = try? wireDecoder.decode(ItemHeader.self, from: frame) else {
        throw ContactWireError.refusedFrame(frame.count)
    }
    return header
}

func writeJson<T: Encodable>(_ wire: ContactConnection, _ value: T) async throws {
    try await wire.writeFrame(try wireEncoder.encode(value))
}

func readJson<T: Decodable>(_ wire: ContactConnection, _ type: T.Type) async throws -> T? {
    guard let frame = try await wire.readFrame() else { return nil }
    return try? wireDecoder.decode(type, from: frame)
}
