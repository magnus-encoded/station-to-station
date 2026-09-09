import Foundation
import CryptoKit
import Security

/// A signing key for one stable local Gig. It is unrelated to the mutable external Gig ID.
enum GigIdentity {
    private static let lock = NSLock()
    static func key(scope: String) -> P256.Signing.PrivateKey? {
        lock.lock()
        defer { lock.unlock() }
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                   kSecAttrService as String: "station-to-station.gossip-gig",
                                   kSecAttrAccount as String: scope]
        var read = query
        read[kSecReturnData as String] = true
        var found: CFTypeRef?
        let status = SecItemCopyMatching(read as CFDictionary, &found)
        if status == errSecSuccess, let bytes = found as? Data {
            return try? P256.Signing.PrivateKey(rawRepresentation: bytes)
        }
        guard status == errSecItemNotFound else { return nil }
        let key = P256.Signing.PrivateKey()
        var write = query
        write[kSecValueData as String] = key.rawRepresentation
        write[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(write as CFDictionary, nil) == errSecSuccess ? key : nil
    }
    static func attribution(scope: String, author: String) -> String? {
        guard let durable = ContactIdentity.publicKeyBase64(),
              let proof = ContactIdentity.sign(identityBinding(scope: scope, author: author)),
              let sealed = try? AES.GCM.seal(proof, using: recognitionKey(durable: durable, scope: scope)).combined
        else { return nil }
        return sealed.base64EncodedString()
    }
}
private func identityBinding(scope: String, author: String) -> Data {
    Data("station-to-station/gossip-identity/2\n\(scope)\n\(author)".utf8)
}
private func recognitionKey(durable: String, scope: String) -> SymmetricKey {
    SymmetricKey(data: SHA256.hash(data: Data("station-to-station/gossip-mask/2\n\(durable)\n\(scope)".utf8)))
}
func recognizeGossip(_ envelope: GossipEnvelope, contacts: Set<String>) -> String? {
    guard let bytes = Data(base64Encoded: envelope.attribution), let sealed = try? AES.GCM.SealedBox(combined: bytes) else { return nil }
    return contacts.first { durable in
        guard let proof = try? AES.GCM.open(sealed, using: recognitionKey(durable: durable, scope: envelope.scope)) else { return false }
        return verifyChallenge(identityBinding(scope: envelope.scope, author: envelope.author), signature: proof, publicKeyBase64: durable)
    }
}
