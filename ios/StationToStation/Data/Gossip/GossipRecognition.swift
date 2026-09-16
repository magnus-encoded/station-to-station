import Foundation
import CryptoKit

// How a Contact's Gig key is recognised from an envelope's sealed attribution.
//
// Pure CryptoKit, split out of the Keychain-backed `GigIdentity` so the v2 core lands without
// the identity store (#461). The binding and mask key are module-internal because
// `GigIdentity.attribution` must seal with exactly these bytes; it should call them rather
// than keep private copies.

func gossipIdentityBinding(scope: String, author: String) -> Data {
    Data("station-to-station/gossip-identity/2\n\(scope)\n\(author)".utf8)
}
func gossipRecognitionKey(durable: String, scope: String) -> SymmetricKey {
    SymmetricKey(data: SHA256.hash(data: Data("station-to-station/gossip-mask/2\n\(durable)\n\(scope)".utf8)))
}
func recognizeGossip(_ envelope: GossipEnvelope, contacts: Set<String>) -> String? {
    guard let bytes = Data(base64Encoded: envelope.attribution), let sealed = try? AES.GCM.SealedBox(combined: bytes) else { return nil }
    return contacts.first { durable in
        guard let proof = try? AES.GCM.open(sealed, using: gossipRecognitionKey(durable: durable, scope: envelope.scope)) else { return false }
        return verifyChallenge(gossipIdentityBinding(scope: envelope.scope, author: envelope.author), signature: proof, publicKeyBase64: durable)
    }
}
