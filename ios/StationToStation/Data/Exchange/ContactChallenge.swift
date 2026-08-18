import CryptoKit
import Foundation

/// The proof a LAN reconcile session (#265) substitutes for a fresh QR handoff: sign a
/// nonce with the identity already pinned to a **Friend** at Exchange time, verify it
/// against that persisted key. Ported from Android's `exchange/ContactChallenge.kt`.
///
/// Pure — no Secure Enclave, no socket — so this half is checkable in a plain XCTest run;
/// `ContactIdentity` is the on-device counterpart it composes with. That split is the
/// point: the signature *math* is where a mistake is silent, and it is the part that
/// needs no hardware to assert.
///
/// SHA-256 over the nonce, ECDSA P-256, DER signature encoding — the same three choices
/// Android's `"SHA256withECDSA"` makes, so the bytes on the wire are already the ones a
/// later cross-platform pass needs. CryptoKit's `signature(for:)` hashes with SHA-256 for
/// a P-256 key, which is why no digest is computed here by hand.
func signChallenge(_ nonce: Data, privateKey: P256.Signing.PrivateKey) -> Data? {
    try? privateKey.signature(for: nonce).derRepresentation
}

/// True only if `signature` proves possession of the private key behind
/// `publicKeyBase64` over exactly `nonce`.
///
/// A malformed or missing key verifies false rather than throwing — a **Friend** added
/// before the key field existed, or a beacon that matches nobody, is a candidate this
/// simply drops. Absence is a state, not an error: there is no "unknown device found"
/// anywhere in this feature.
func verifyChallenge(_ nonce: Data, signature: Data, publicKeyBase64: String?) -> Bool {
    guard let publicKey = decodeContactPublicKey(publicKeyBase64),
          let parsed = try? P256.Signing.ECDSASignature(derRepresentation: signature)
    else { return false }
    return publicKey.isValidSignature(parsed, for: nonce)
}

/// A **Contact**'s persisted key as it travels: base64 X.509 SubjectPublicKeyInfo, which
/// is what `ProbeCard.publicKey` carries and what Android's `publicKey.encoded` produces.
/// Nil for anything that isn't one.
func decodeContactPublicKey(_ base64: String?) -> P256.Signing.PublicKey? {
    guard let base64 = base64?.nilIfBlank,
          let der = Data(base64Encoded: base64, options: .ignoreUnknownCharacters)
    else { return nil }
    return try? P256.Signing.PublicKey(derRepresentation: der)
}

/// 32 bytes from the system CSPRNG: what a verifier sends, and the whole of its
/// freshness guarantee. Never reused — a nonce a peer has answered once is a signature
/// somebody could replay.
///
/// Nil rather than a fallback when the CSPRNG refuses: a predictable nonce would verify
/// perfectly well and mean nothing, so the session has to end instead.
func contactNonce() -> Data? {
    var bytes = [UInt8](repeating: 0, count: 32)
    guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess
    else { return nil }
    return Data(bytes)
}
