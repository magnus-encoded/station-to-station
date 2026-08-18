import CryptoKit
import Security
import XCTest
@testable import StationToStation

/// The one check that makes hand-written ASN.1 defensible (#265).
///
/// iOS has no API for minting a self-signed certificate and `Network.framework` will not
/// start a TLS server without one, so `ContactCert` encodes the DER itself. That is only
/// acceptable because the platform will tell us when it is wrong:
/// `SecCertificateCreateWithData` refuses malformed DER outright, and the public key it
/// parses back out has to be the one that went in. Both are asserted here rather than
/// discovered on a phone.
///
/// The encoding is therefore tested through `selfSignedCertificate` with a software key,
/// not through `ContactTlsIdentity.make`: an unsigned test host has no keychain
/// entitlement, so anything reaching for `SecItemAdd` skips here and checks nothing. The
/// tests below that do need a keychain are kept anyway — they run when this is executed
/// against a signed build, and skip loudly rather than passing vacuously otherwise.
final class ContactCertTests: XCTestCase {

    /// The assertion the hand-written ASN.1 stands on: iOS itself parses it, and the key
    /// it parses back out is the one that went in. No keychain anywhere in this test.
    func testTheEncodedCertificateIsValidDerCarryingTheKeyItWasMadeFor() throws {
        let key = P256.Signing.PrivateKey()

        let der = try XCTUnwrap(selfSignedCertificate(
            commonName: "a-session",
            spki: key.publicKey.derRepresentation,
            sign: { try? key.signature(for: $0).derRepresentation }
        ))

        // Refuses anything that is not well-formed DER, which is the whole check.
        let parsed = try XCTUnwrap(SecCertificateCreateWithData(nil, der as CFData),
                                   "the encoded certificate is not valid DER")
        let publicKey = try XCTUnwrap(SecCertificateCopyKey(parsed))
        let x963 = try XCTUnwrap(SecKeyCopyExternalRepresentation(publicKey, nil) as Data?)
        XCTAssertEqual(key.publicKey.x963Representation, x963,
                       "the certificate carries a different key than it was signed with")
    }

    /// The certificate is comfortably past 127 bytes, so its length is encoded long-form —
    /// a branch that is easy to get wrong and impossible to notice, because a short-form
    /// mistake produces bytes that look plausible and parse as garbage.
    func testTheLengthEncodingSurvivesABodyPastTheShortFormLimit() throws {
        let key = P256.Signing.PrivateKey()
        let longName = String(repeating: "n", count: 300)

        let der = try XCTUnwrap(selfSignedCertificate(
            commonName: longName,
            spki: key.publicKey.derRepresentation,
            sign: { try? key.signature(for: $0).derRepresentation }
        ))

        XCTAssertNotNil(SecCertificateCreateWithData(nil, der as CFData))
    }

    /// A key that refuses to sign is a certificate that does not exist, not one with an
    /// empty signature on it.
    func testASigningFailureYieldsNoCertificate() {
        let key = P256.Signing.PrivateKey()

        XCTAssertNil(selfSignedCertificate(commonName: "a-session",
                                           spki: key.publicKey.derRepresentation,
                                           sign: { _ in nil }))
    }

    func testTheMintedIdentityPairsTheCertificateWithItsKey() throws {
        guard let tls = ContactTlsIdentity.make() else {
            throw XCTSkip("no keychain available: \(keychainDiagnosis())")
        }
        defer { tls.discard() }

        var fromIdentity: SecCertificate?
        XCTAssertEqual(errSecSuccess, SecIdentityCopyCertificate(tls.identity, &fromIdentity))
        // The identity has to pair *this* certificate with its key, or the TLS handshake
        // presents somebody else's session cert and the fingerprint binding is nonsense.
        XCTAssertEqual(tls.certificate,
                       SecCertificateCopyData(try XCTUnwrap(fromIdentity)) as Data)
    }

    /// Every session gets its own — nothing here is meant to be recognised across
    /// sessions, and trust comes from the challenge rather than from this key.
    func testEachSessionMintsADistinctCertificate() throws {
        guard let first = ContactTlsIdentity.make(), let second = ContactTlsIdentity.make() else {
            throw XCTSkip("no keychain available: \(keychainDiagnosis())")
        }
        defer { first.discard(); second.discard() }

        XCTAssertNotEqual(first.certificate, second.certificate)
        XCTAssertNotEqual(certFingerprint(first.certificate), certFingerprint(second.certificate))
    }

    /// The session is over and the keychain does not clean up after itself.
    func testDiscardingRemovesTheIdentityFromTheKeychain() throws {
        guard let tls = ContactTlsIdentity.make() else {
            throw XCTSkip("no keychain available: \(keychainDiagnosis())")
        }
        let der = tls.certificate
        tls.discard()

        var items: CFTypeRef?
        let status = SecItemCopyMatching([
            kSecClass as String: kSecClassIdentity,
            kSecMatchLimit as String: kSecMatchLimitAll,
            kSecReturnRef as String: true,
        ] as CFDictionary, &items)
        let identities = (status == errSecSuccess ? items as? [SecIdentity] : []) ?? []

        XCTAssertFalse(identities.contains { identity in
            var certificate: SecCertificate?
            guard SecIdentityCopyCertificate(identity, &certificate) == errSecSuccess,
                  let certificate else { return false }
            return SecCertificateCopyData(certificate) as Data == der
        })
    }

    func testTheFingerprintIsSha256OfTheCertificateBytes() {
        let der = Data("not really a certificate".utf8)

        XCTAssertEqual(Data(SHA256.hash(data: der)), certFingerprint(der))
        XCTAssertEqual(32, certFingerprint(der).count)
    }

    /// Why the keychain refused, in the skip message rather than left to be guessed at.
    ///
    /// A skip that says only "unavailable" is indistinguishable from a skip hiding a real
    /// bug, and the difference costs a device round trip to establish. `-34018`
    /// (`errSecMissingEntitlement`) is the expected answer for an unsigned test host and
    /// says nothing is wrong with the code; anything else is worth reading.
    private func keychainDiagnosis() -> String {
        var error: Unmanaged<CFError>?
        let tag = Data("station-to-station-keychain-probe".utf8)
        let key = SecKeyCreateRandomKey([
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
            kSecPrivateKeyAttrs as String: [
                kSecAttrIsPermanent as String: true,
                kSecAttrApplicationTag as String: tag,
            ],
        ] as CFDictionary, &error)
        defer {
            SecItemDelete([kSecClass as String: kSecClassKey,
                           kSecAttrApplicationTag as String: tag] as CFDictionary)
        }
        if key != nil { return "a bare key generated fine, so the failure is later than this" }
        return String(describing: error?.takeRetainedValue())
    }
}
