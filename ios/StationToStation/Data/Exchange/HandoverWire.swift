import CryptoKit
import Foundation
import Network

/// The transport for a device handover (#142), ported from Android's `HandoverWire.kt`.
/// The framing itself is not repeated here — `ContactConnection` already speaks it, and a
/// second implementation of "4-byte big-endian length, then that many bytes" is exactly
/// the kind of duplication that drifts. What is here is everything a handover adds:
///
///  - **The manifest's own integrity.** `sealManifest`/`openManifest` authenticate the
///    manifest as one unit, before anything is written. A corrupt photo is a wasted
///    transfer; a corrupt manifest is silent and semantic — a photograph attaching to the
///    wrong night, or the **Personal** bit flipping.
///  - **The server is who the QR said it would be.** There is no certificate authority
///    and no server to ask, so the joining phone pins the exact SHA-256 fingerprint of the
///    leaf certificate the QR carried. A device presenting any other certificate fails the
///    handshake before a byte of application data moves.
///  - **The client proves it read the same QR**, because pinning alone authenticates the
///    server to the client and not the other way around: anyone can open a connection and
///    complete a handshake against a certificate they merely observed. `proveLinkKey` and
///    `verifyLinkKey` close that gap with a nonce answered by HMAC-SHA256 over the same
///    `linkKey` the QR carried — the same primitive the seal uses, not a new one.
///
/// **This is the one place on iOS that pins, and `ContactExchange` must never do it.**
/// That path's accept-anything verify block is load-bearing for iOS↔Android interop (see
/// the note there). A handover is the opposite situation: the fingerprint arrives
/// out of band, in the QR, *before* the connection — so there is something to pin.

// MARK: - The sealed manifest

/// A manifest and the tag that says it arrived as it left. Field for field with Android's.
///
/// `alg` is carried rather than assumed, because the Contact case will need a different
/// answer. Here both ends are the *same person's* devices holding the same key from the
/// QR, so a symmetric MAC needs no PKI: there is no third party to convince.
struct SealedManifest: Codable {
    static let hmacSha256 = "HMAC-SHA256"

    var alg: String = SealedManifest.hmacSha256
    /// The manifest, as the exact bytes that were authenticated.
    var payload: String = ""
    var mac: String = ""

    init(alg: String = SealedManifest.hmacSha256, payload: String = "", mac: String = "") {
        self.alg = alg
        self.payload = payload
        self.mac = mac
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        alg = (try? c.decodeIfPresent(String.self, forKey: .alg)) ?? nil ?? SealedManifest.hmacSha256
        payload = (try? c.decodeIfPresent(String.self, forKey: .payload)) ?? nil ?? ""
        mac = (try? c.decodeIfPresent(String.self, forKey: .mac)) ?? nil ?? ""
    }
}

/// `.sortedKeys` so the payload is the same bytes on both ends of a re-encode, and so a
/// capture is diffable against Android's.
private let sealEncoder: JSONEncoder = {
    let e = JSONEncoder()
    e.outputFormatting = [.sortedKeys]
    return e
}()

/// Seals `manifest` with the key the QR carried.
///
/// **The per-category counts are computed here**, so they are inside the tag rather than
/// beside it. "The manifest said 48 personal images and we received 48" only means
/// something if the expected count cannot be truncated alongside what it describes.
func sealManifest(key: Data, manifest: HandoverManifest) -> SealedManifest {
    var counted = manifest
    counted.counts = [:]
    for item in manifest.media { counted.counts[item.category, default: 0] += 1 }
    let payload = (try? sealEncoder.encode(counted)).flatMap { String(data: $0, encoding: .utf8) } ?? ""
    return SealedManifest(payload: payload, mac: tag(key: key, payload: payload))
}

/// The manifest, or **nil** if it does not verify — which is the whole contract: a
/// manifest that fails writes *nothing*, and `handoverPlan` takes that verdict as its
/// `verified` argument rather than deciding it. Failure is total by construction, not by a
/// caller remembering to check.
///
/// Nil covers every way this can go wrong — wrong key, altered payload, altered tag, an
/// algorithm we do not implement, a payload that will not parse — because they are the
/// same outcome, and distinguishing them for the sender's benefit is how a verifier grows
/// an oracle.
func openManifest(key: Data, sealed: SealedManifest) -> HandoverManifest? {
    guard sealed.alg == SealedManifest.hmacSha256 else { return nil }
    // CryptoKit's own check, which is constant time: a compare that returns early leaks
    // how much of a forged tag was right, which is enough to build the rest of it. A tag
    // that will not decode is a tag that does not match, never an error.
    let presented = Data(base64Encoded: sealed.mac) ?? Data()
    guard HMAC<SHA256>.isValidAuthenticationCode(presented,
                                                 authenticating: Data(sealed.payload.utf8),
                                                 using: SymmetricKey(data: key))
    else { return nil }
    return try? JSONDecoder().decode(HandoverManifest.self, from: Data(sealed.payload.utf8))
}

private func tag(key: Data, payload: String) -> String {
    Data(HMAC<SHA256>.authenticationCode(for: Data(payload.utf8), using: SymmetricKey(data: key)))
        .base64EncodedString()
}

// MARK: - Link-key proof of possession

/// The joining phone's half: answer the source's nonce with HMAC(linkKey, nonce).
func proveLinkKey(_ wire: ContactConnection, linkKey: Data) async throws {
    guard let nonce = try await wire.readFrame() else { throw ContactWireError.closedMidFrame }
    try await wire.writeFrame(linkKeyAnswer(linkKey: linkKey, nonce: nonce))
}

/// The source's half: a fresh nonce, and proceed only if the answer proves the peer holds
/// the same `linkKey` the QR carried. False — and the caller must close without sending the
/// manifest — on any mismatch or dropped connection. This is the whole anti-spoofing
/// guarantee: pinning authenticates us to them, this authenticates them to us.
func verifyLinkKey(_ wire: ContactConnection, linkKey: Data) async throws -> Bool {
    var nonce = Data(count: 32)
    let generated = nonce.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }
    guard generated == errSecSuccess else { return false }
    try await wire.writeFrame(nonce)
    guard let answer = try await wire.readFrame() else { return false }
    return HMAC<SHA256>.isValidAuthenticationCode(answer, authenticating: nonce,
                                                 using: SymmetricKey(data: linkKey))
}

private func linkKeyAnswer(linkKey: Data, nonce: Data) -> Data {
    Data(HMAC<SHA256>.authenticationCode(for: nonce, using: SymmetricKey(data: linkKey)))
}

// MARK: - The frames a handover adds

/// What actually landed, counted by the receiver and sent back so **both** phones can say
/// the same thing about the transfer (#142 story 12). A deliberate act deserves a visible
/// outcome, and the source is the phone the person is usually holding.
struct HandoverReceipt: Codable, Equatable {
    var landed: Int = 0
    var bytes: Int64 = 0
    var held: Int = 0
    var fromGallery: Int = 0
    var withheld: Int = 0
    var refused: Int = 0
    var requested: Int = 0
    /// The one field that is not a tally but a verdict: the manifest's own per-category
    /// counts, sealed inside the tag, disagreed with the items it listed.
    var countMismatch: Bool = false
    /// Empty when it went through; otherwise what went wrong, in the receiver's words.
    var trouble: String = ""
    /// #143 story 9: whether the accounts step was part of this handover and what became
    /// of it, reusing `AccountsMove` rather than a second vocabulary. `.notOffered` is
    /// both "the row was not ticked" and "an older peer's receipt, decoded with no such
    /// field" — the same honest default either way: say nothing about a step that was
    /// not offered. **Never a credential value** — this is a verdict on the step, not
    /// the payload that carried it.
    var accountsMove: AccountsMove = .notOffered

    init(landed: Int = 0, bytes: Int64 = 0, held: Int = 0, fromGallery: Int = 0,
         withheld: Int = 0, refused: Int = 0, requested: Int = 0,
         countMismatch: Bool = false, trouble: String = "", accountsMove: AccountsMove = .notOffered) {
        self.landed = landed
        self.bytes = bytes
        self.held = held
        self.fromGallery = fromGallery
        self.withheld = withheld
        self.refused = refused
        self.requested = requested
        self.countMismatch = countMismatch
        self.trouble = trouble
        self.accountsMove = accountsMove
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        landed = (try? c.decodeIfPresent(Int.self, forKey: .landed)) ?? nil ?? 0
        bytes = (try? c.decodeIfPresent(Int64.self, forKey: .bytes)) ?? nil ?? 0
        held = (try? c.decodeIfPresent(Int.self, forKey: .held)) ?? nil ?? 0
        fromGallery = (try? c.decodeIfPresent(Int.self, forKey: .fromGallery)) ?? nil ?? 0
        withheld = (try? c.decodeIfPresent(Int.self, forKey: .withheld)) ?? nil ?? 0
        refused = (try? c.decodeIfPresent(Int.self, forKey: .refused)) ?? nil ?? 0
        requested = (try? c.decodeIfPresent(Int.self, forKey: .requested)) ?? nil ?? 0
        countMismatch = (try? c.decodeIfPresent(Bool.self, forKey: .countMismatch)) ?? nil ?? false
        trouble = (try? c.decodeIfPresent(String.self, forKey: .trouble)) ?? nil ?? ""
        accountsMove = (try? c.decodeIfPresent(AccountsMove.self, forKey: .accountsMove)) ?? nil ?? .notOffered
    }
}

/// The accounts step's payload (#143). iOS never *sends* a credential — it has no
/// credential move to offer — but an Android source may, and a payload that arrives is
/// stored rather than dropped on the floor.
struct Credentials: Codable, Equatable {
    var spotifyRefreshToken: String?
    var spotifyScope: String?

    var isEmpty: Bool { spotifyRefreshToken?.nilIfBlank == nil }

    init(spotifyRefreshToken: String? = nil, spotifyScope: String? = nil) {
        self.spotifyRefreshToken = spotifyRefreshToken
        self.spotifyScope = spotifyScope
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        spotifyRefreshToken = (try? c.decodeIfPresent(String.self, forKey: .spotifyRefreshToken)) ?? nil
        spotifyScope = (try? c.decodeIfPresent(String.self, forKey: .spotifyScope)) ?? nil
    }
}

/// Separate from the records manifest by construction, not by a filter that could be
/// forgotten: no combination of ticked media categories can move a credential as a side
/// effect, because a credential has only ever one shape to travel in.
struct AccountsPayload: Codable, Equatable {
    var identities: Identities = Identities()
    var credentials: Credentials = Credentials()

    init(identities: Identities = Identities(), credentials: Credentials = Credentials()) {
        self.identities = identities
        self.credentials = credentials
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        identities = (try? c.decodeIfPresent(Identities.self, forKey: .identities)) ?? nil ?? Identities()
        credentials = (try? c.decodeIfPresent(Credentials.self, forKey: .credentials)) ?? nil ?? Credentials()
    }
}

/// The exact ack frame the source's clear is gated on — bytes, not JSON, and Android's.
let accountsAck = Data("accounts-stored".utf8)

// MARK: - The QR, which is the trust anchor

/// Where the source is listening, and the two secrets that make the session that phone's.
///
/// Out-of-band key material in one mechanism: where to connect, whose certificate to pin,
/// and the key that proves the joining phone read *this* screen and not a photograph of
/// some other one. A deep link for the same reason a friend card is one — any phone camera
/// opens it, so there is no in-app scanner and no camera permission on the joining side.
struct HandoverInvite: Equatable {
    let host: String
    let port: Int
    let fingerprint: Data
    let linkKey: Data

    var uri: String {
        "station-to-station://handover?h=\(host)&p=\(port)&f=\(hex(fingerprint))&k=\(hex(linkKey))"
    }
}

/// Nil for anything that is not a well-formed handover invite — a friend card, a link
/// someone typed by hand, a truncated scan. Plain string operations rather than
/// `URLComponents`, matching Android's, so the interesting cases (missing parameter,
/// odd-length hex, junk port) are the same cases on both platforms.
func parseHandoverInvite(_ uri: String) -> HandoverInvite? {
    let prefix = "station-to-station://handover?"
    guard uri.hasPrefix(prefix) else { return nil }
    var params: [String: String] = [:]
    for pair in uri.dropFirst(prefix.count).split(separator: "&") {
        let parts = pair.split(separator: "=", maxSplits: 1)
        guard parts.count == 2, !parts[0].isEmpty, !parts[1].isEmpty else { continue }
        params[String(parts[0])] = String(parts[1])
    }
    guard let host = params["h"],
          let port = params["p"].flatMap(Int.init), (1...65535).contains(port),
          let fingerprint = params["f"].flatMap(unhex), !fingerprint.isEmpty,
          let linkKey = params["k"].flatMap(unhex), !linkKey.isEmpty
    else { return nil }
    return HandoverInvite(host: host, port: port, fingerprint: fingerprint, linkKey: linkKey)
}

func hex(_ data: Data) -> String {
    data.map { String(format: "%02x", $0) }.joined()
}

func unhex(_ string: String) -> Data? {
    let clean = string.trimmingCharacters(in: .whitespaces)
    guard !clean.isEmpty, clean.count % 2 == 0 else { return nil }
    var out = Data(capacity: clean.count / 2)
    var index = clean.startIndex
    while index < clean.endIndex {
        let next = clean.index(index, offsetBy: 2)
        guard let byte = UInt8(clean[index..<next], radix: 16) else { return nil }
        out.append(byte)
        index = next
    }
    return out
}

/// Both fingerprints are public values, so this is belt and braces rather than a
/// requirement — but a compare that returns at the first differing byte is the habit worth
/// not having near key material.
func constantTimeEquals(_ a: Data, _ b: Data) -> Bool {
    if a.count != b.count { return false }
    var difference: UInt8 = 0
    for (x, y) in zip(a, b) { difference |= x ^ y }
    return difference == 0
}

// MARK: - TLS, pinned

/// The joining phone's parameters: trust exactly the certificate whose fingerprint the QR
/// carried, and nothing else. Expiry, hostname, chain and CA are beside the point for a
/// certificate nobody but the two phones in the room will ever see — checking them anyway
/// would only be a chance to get that checking wrong. The fingerprint compare is the whole
/// trust decision.
func pinnedHandoverParameters(fingerprint: Data) -> NWParameters {
    let options = NWProtocolTLS.Options()
    let security = options.securityProtocolOptions
    sec_protocol_options_set_verify_block(security, { _, trust, complete in
        let trust = sec_trust_copy_ref(trust).takeRetainedValue()
        guard let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
              let leaf = chain.first
        else { complete(false); return }
        let presented = certFingerprint(SecCertificateCopyData(leaf) as Data)
        complete(constantTimeEquals(presented, fingerprint))
    }, .global(qos: .utility))
    return NWParameters(tls: options, tcp: NWProtocolTCP.Options())
}

/// The source's parameters: present the session identity whose fingerprint went in the QR.
/// No client certificate is requested — the joining phone authenticates itself with
/// `proveLinkKey` instead, over the channel TLS has already secured.
func offeringHandoverParameters(_ tls: ContactTlsIdentity) -> NWParameters {
    let options = NWProtocolTLS.Options()
    let security = options.securityProtocolOptions
    if let identity = sec_identity_create(tls.identity) {
        sec_protocol_options_set_local_identity(security, identity)
    }
    sec_protocol_options_set_peer_authentication_required(security, false)
    return NWParameters(tls: options, tcp: NWProtocolTCP.Options())
}
