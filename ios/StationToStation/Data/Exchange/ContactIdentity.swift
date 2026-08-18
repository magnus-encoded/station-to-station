import CryptoKit
import Foundation
import Security

/// The device's durable **Contact**-facing identity (#265): one Secure Enclave keypair,
/// generated once and reused across every Exchange and every later LAN reconcile session.
/// The iOS twin of Android's `exchange/ContactIdentity.kt` and its `AndroidKeyStore` key.
///
/// This is what a **Friend**'s `publicKey` pins to, and what a LAN peer later proves
/// possession of via a signature instead of a fresh QR scan. Unlike the per-session TLS
/// certificate (`ContactCert`), it is meant to outlive everything: rotating it silently
/// unmakes every Contact relationship this device has.
///
/// ECDSA P-256, non-exportable, hardware-protected. Both platforms land on the same curve
/// without either having chosen it freely — the Secure Enclave only does P-256, and
/// `AndroidKeyStore` only does Ed25519 from API 33 — which is a coincidence worth relying
/// on and worth writing down.
///
/// **Not unit-tested**, for the same reason `AndroidKeyStoreCert` is not: the Secure
/// Enclave is real hardware that does not exist off-device. `signChallenge` and
/// `verifyChallenge` are where the signature math is pure and covered.
enum ContactIdentity {

    /// One keychain item, named once. Changing this string is a device-wide identity
    /// rotation, not a rename.
    private static let tag = Data("io.github.magnusencoded.stationtostation.contact-identity".utf8)

    /// The Exchange runs several sessions at once and each one wants the identity. Two
    /// threads racing `create()` would leave two keys under one tag and hand out
    /// whichever the keychain answered with — an identity that changes between sessions,
    /// which is exactly the failure this key exists to prevent.
    private static let lock = NSLock()
    private static var cached: SecKey?

    /// Base64 X.509 SubjectPublicKeyInfo — what goes on a `ProbeCard`, what is persisted
    /// on the far end's `Friend`, and what a challenge is later verified against.
    static func publicKeyBase64() -> String? {
        guard let key = key(),
              let publicKey = SecKeyCopyPublicKey(key),
              let x963 = SecKeyCopyExternalRepresentation(publicKey, nil) as Data?,
              // CryptoKit does the SubjectPublicKeyInfo wrapping, so the one place the
              // encoding could drift from Android's is a framework's problem, not ours.
              let parsed = try? P256.Signing.PublicKey(x963Representation: x963)
        else { return nil }
        return parsed.derRepresentation.base64EncodedString()
    }

    /// Signs `data` with the persisted identity — the proof a LAN peer checks against the
    /// public key already on their Friend record. Key material never leaves the enclave;
    /// only a reference to it is ever held here.
    ///
    /// `ecdsaSignatureMessageX962SHA256` is SHA-256 then ECDSA with a DER-encoded
    /// signature: byte for byte what Android's `"SHA256withECDSA"` produces.
    static func sign(_ data: Data) -> Data? {
        guard let key = key() else { return nil }
        return SecKeyCreateSignature(key, .ecdsaSignatureMessageX962SHA256,
                                     data as CFData, nil) as Data?
    }

    private static func key() -> SecKey? {
        lock.lock()
        defer { lock.unlock() }
        if let cached { return cached }
        let key = load() ?? create()
        cached = key
        return key
    }

    private static func load() -> SecKey? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecAttrApplicationTag as String: tag,
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecReturnRef as String: true,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let result = item, CFGetTypeID(result) == SecKeyGetTypeID()
        else { return nil }
        return (result as! SecKey)
    }

    /// The enclave first, a plain keychain key only if it refuses.
    ///
    /// The fallback is not a security compromise being waved through — it is the
    /// Simulator, which has no enclave, and without it every test run and every CI build
    /// would exercise a code path that simply returns nil. On a real phone the first
    /// branch is the one that runs.
    private static func create() -> SecKey? {
        let privateAttributes: [String: Any] = [
            kSecAttrIsPermanent as String: true,
            kSecAttrApplicationTag as String: tag,
        ]
        let attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
            kSecPrivateKeyAttrs as String: privateAttributes,
        ]

        // `.privateKeyUsage` alone: no biometry, no passcode prompt. The enclave protects
        // the key from extraction, and nothing here should ever interrupt someone to ask
        // permission for a sync they did not initiate.
        if let access = SecAccessControlCreateWithFlags(
            nil, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly, .privateKeyUsage, nil
        ) {
            var enclave = attributes
            enclave[kSecAttrTokenID as String] = kSecAttrTokenIDSecureEnclave
            enclave[kSecPrivateKeyAttrs as String] = privateAttributes.merging(
                [kSecAttrAccessControl as String: access], uniquingKeysWith: { _, new in new }
            )
            if let key = SecKeyCreateRandomKey(enclave as CFDictionary, nil) { return key }
        }
        return SecKeyCreateRandomKey(attributes as CFDictionary, nil)
    }
}
