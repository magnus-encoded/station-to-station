import CryptoKit
import Foundation
import Security

/// The self-signed identity a device presents for one reconcile session (#265) — the iOS
/// answer to Android's `AndroidKeyStoreCert`, and the one place the two platforms had to
/// solve the same problem differently.
///
/// **Why this file writes ASN.1 by hand.** Android asks `AndroidKeyStore` for
/// `setCertificateSubject` and gets a self-signed X.509 back; iOS has no equivalent at
/// any layer — not in Security.framework, not in CryptoKit, not in Network.framework —
/// and `Network.framework` will not start a TLS server without a certificate to present.
/// So the certificate is encoded here: one fixed shape, no parsing, no extensions, no
/// options. `SecCertificateCreateWithData` refuses malformed DER, which is what makes
/// `ContactCertTests` a real check on it rather than a restatement.
///
/// **The certificate is not the trust decision, and nothing here should be read as if it
/// were.** Both ends accept whatever certificate the other presents — exactly as
/// Android's `AcceptAnyTrustManager` does — because mDNS announces presence, not
/// identity, so neither side has anything to pin *before* connecting. Trust arrives
/// afterwards, from a signature over this certificate's fingerprint plus a nonce, checked
/// against the peer's persisted `Friend.publicKey`. What the certificate must do is
/// exist, be unique to this session, and be bindable — nothing more.
///
/// Which is why the key underneath is a fresh software key rather than the Secure Enclave
/// identity: it is thrown away when the session ends (#142's call, for its reason — a key
/// that never outlives what generated it is one fewer long-lived secret), and it keeps
/// this whole path working in the Simulator, where the enclave does not exist.
struct ContactTlsIdentity {
    let identity: SecIdentity
    /// The DER the peer will fingerprint. Held so the challenge can bind to it without
    /// asking the TLS stack what it sent.
    let certificate: Data

    private let keyTag: Data
    private let label: String

    /// Removes both keychain items. Nothing here is meant to outlive the session, and the
    /// keychain does not clean up after itself.
    func discard() {
        SecItemDelete([
            kSecClass as String: kSecClassKey,
            kSecAttrApplicationTag as String: keyTag,
        ] as CFDictionary)
        SecItemDelete([
            kSecClass as String: kSecClassCertificate,
            kSecAttrLabel as String: label,
        ] as CFDictionary)
    }

    /// A fresh keypair, a certificate over it, and the `SecIdentity` pairing the two —
    /// which on iOS can only be obtained by putting both in the keychain and asking for
    /// them back together (`SecIdentityCreateWithCertificate` is macOS-only).
    static func make() -> ContactTlsIdentity? {
        let label = "station-to-station-session-\(UUID().uuidString)"
        let keyTag = Data(label.utf8)

        guard let key = SecKeyCreateRandomKey([
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
            kSecPrivateKeyAttrs as String: [
                kSecAttrIsPermanent as String: true,
                kSecAttrApplicationTag as String: keyTag,
            ],
        ] as CFDictionary, nil),
            let publicKey = SecKeyCopyPublicKey(key),
            let x963 = SecKeyCopyExternalRepresentation(publicKey, nil) as Data?,
            let spki = try? P256.Signing.PublicKey(x963Representation: x963).derRepresentation,
            let der = selfSignedCertificate(commonName: label, spki: spki, sign: { tbs in
                SecKeyCreateSignature(key, .ecdsaSignatureMessageX962SHA256, tbs as CFData, nil) as Data?
            }),
            let certificate = SecCertificateCreateWithData(nil, der as CFData)
        else {
            SecItemDelete([kSecClass as String: kSecClassKey,
                           kSecAttrApplicationTag as String: keyTag] as CFDictionary)
            return nil
        }

        let added = SecItemAdd([
            kSecClass as String: kSecClassCertificate,
            kSecValueRef as String: certificate,
            kSecAttrLabel as String: label,
        ] as CFDictionary, nil)
        guard added == errSecSuccess || added == errSecDuplicateItem,
              let identity = identityMatching(der)
        else {
            SecItemDelete([kSecClass as String: kSecClassKey,
                           kSecAttrApplicationTag as String: keyTag] as CFDictionary)
            SecItemDelete([kSecClass as String: kSecClassCertificate,
                           kSecAttrLabel as String: label] as CFDictionary)
            return nil
        }
        return ContactTlsIdentity(identity: identity, certificate: der,
                                  keyTag: keyTag, label: label)
    }

    /// Every identity this app holds, matched on the certificate's own bytes.
    ///
    /// Deliberately not a query by label: whether an identity search honours a
    /// certificate attribute is the sort of thing that is true until it isn't, and the
    /// consequence of it quietly not matching is a feature that never runs on a device
    /// nobody can attach a debugger to. Comparing the DER cannot be wrong. This keychain
    /// holds at most a handful of session certificates, so walking them costs nothing.
    private static func identityMatching(_ der: Data) -> SecIdentity? {
        var items: CFTypeRef?
        guard SecItemCopyMatching([
            kSecClass as String: kSecClassIdentity,
            kSecMatchLimit as String: kSecMatchLimitAll,
            kSecReturnRef as String: true,
        ] as CFDictionary, &items) == errSecSuccess,
            let identities = items as? [SecIdentity]
        else { return nil }

        return identities.first { identity in
            var certificate: SecCertificate?
            guard SecIdentityCopyCertificate(identity, &certificate) == errSecSuccess,
                  let certificate else { return false }
            return SecCertificateCopyData(certificate) as Data == der
        }
    }
}

/// SHA-256 of the DER encoding — what each side signs, and the only thing binding a
/// challenge answer to the TLS session it was given over. A signature captured off one
/// connection and replayed on another carries the wrong fingerprint and fails.
func certFingerprint(_ der: Data) -> Data {
    Data(SHA256.hash(data: der))
}

// MARK: - The certificate itself

/// One X.509 v3 certificate: self-issued, ECDSA P-256 over SHA-256, no extensions.
///
/// No Subject Alternative Name and no key usage, because nothing checks them — the peer's
/// verify block accepts every certificate by design (see the note at the top of this
/// file). Adding fields nobody reads would be more surface to encode wrongly.
///
/// `sign` is a closure rather than the `SecKey` itself so that the encoding — the part
/// written by hand here, and the part that can be silently wrong — is assertable without a
/// keychain. That is not hypothetical tidiness: an unsigned test host has no keychain
/// entitlement, so `ContactTlsIdentity.make` cannot run in CI at all, and a test that
/// needed it would skip rather than check anything.
func selfSignedCertificate(commonName: String, spki: Data, sign: (Data) -> Data?) -> Data? {
    let name = derName(commonName)
    let tbs = derSequence([
        derExplicit(0, derInteger([2])),            // v3
        derInteger([1]),                            // serial, as Android's is
        ecdsaWithSha256,
        name,                                       // issuer, and subject below: self-issued
        derValidity(),
        name,
        [UInt8](spki),
    ])

    guard let signature = sign(Data(tbs)) else { return nil }

    return Data(derSequence([
        tbs,
        ecdsaWithSha256,
        // A BIT STRING's first byte counts the unused bits in its last byte. Always zero
        // for a whole number of bytes, and a silent parse failure everywhere if omitted.
        der(0x03, [0x00] + [UInt8](signature)),
    ]))
}

/// `ecdsa-with-SHA256`, 1.2.840.10045.4.3.2, with no parameters — which for ECDSA means
/// the field is absent rather than NULL.
private let ecdsaWithSha256: [UInt8] =
    [0x30, 0x0A, 0x06, 0x08, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x04, 0x03, 0x02]

/// `Name ::= RDNSequence`, holding exactly one CN. The value is never read by anything —
/// it names the session so a human reading a packet capture knows what they are looking at.
private func derName(_ commonName: String) -> [UInt8] {
    let cn: [UInt8] = [0x06, 0x03, 0x55, 0x04, 0x03] // 2.5.4.3
    let attribute = derSequence([cn, der(0x0C, [UInt8](commonName.utf8))]) // UTF8String
    return derSequence([der(0x31, attribute)]) // SET OF
}

/// Backdated a minute against two phones whose clocks disagree, and generous at the far
/// end because the listener lives as long as the Exchange screen is open. Nothing checks
/// either bound — the certificate is discarded with the session regardless.
private func derValidity(now: Date = Date()) -> [UInt8] {
    derSequence([derUtcTime(now.addingTimeInterval(-60)),
                 derUtcTime(now.addingTimeInterval(60 * 60))])
}

private func derUtcTime(_ date: Date) -> [UInt8] {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.timeZone = TimeZone(identifier: "UTC")
    formatter.dateFormat = "yyMMddHHmmss'Z'"
    return der(0x17, [UInt8](formatter.string(from: date).utf8))
}

private func derInteger(_ bytes: [UInt8]) -> [UInt8] { der(0x02, bytes) }

private func derSequence(_ parts: [[UInt8]]) -> [UInt8] { der(0x30, parts.flatMap { $0 }) }

/// A context-specific EXPLICIT tag: the tagged number wrapping an already-encoded value.
private func derExplicit(_ number: UInt8, _ body: [UInt8]) -> [UInt8] {
    der(0xA0 | number, body)
}

/// Tag, length, value. Long-form length once the body passes 127 bytes — which the
/// certificate itself always does, so this branch is not theoretical.
private func der(_ tag: UInt8, _ body: [UInt8]) -> [UInt8] {
    var out: [UInt8] = [tag]
    if body.count < 0x80 {
        out.append(UInt8(body.count))
    } else {
        var length: [UInt8] = []
        var remaining = body.count
        while remaining > 0 {
            length.insert(UInt8(remaining & 0xFF), at: 0)
            remaining >>= 8
        }
        out.append(0x80 | UInt8(length.count))
        out += length
    }
    return out + body
}
