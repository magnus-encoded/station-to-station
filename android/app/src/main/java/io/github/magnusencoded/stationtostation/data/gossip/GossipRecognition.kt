package io.github.magnusencoded.stationtostation.data.gossip

import io.github.magnusencoded.stationtostation.data.exchange.verifyChallenge
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * How a Contact's Gig key is recognised from an envelope's sealed attribution.
 *
 * Pure JCA, split out of the Keystore-backed `GigIdentity` so the v2 core can land without
 * the identity store (#461). The binding and mask key are `internal` because `GigIdentity`
 * must seal attribution with exactly these bytes; it should call them rather than keep
 * private copies.
 */

internal fun gossipIdentityBinding(scope: String, author: String) =
    "station-to-station/gossip-identity/2\n$scope\n$author".toByteArray(Charsets.UTF_8)
internal fun gossipRecognitionKey(durable: String, scope: String) = SecretKeySpec(MessageDigest.getInstance("SHA-256")
    .digest("station-to-station/gossip-mask/2\n$durable\n$scope".toByteArray(Charsets.UTF_8)), "AES")

/** The Card public key is the recognition capability, not a content-encryption promise. */
fun recognizeGossip(envelope: GossipEnvelope, contacts: Set<String>): String? {
    val sealed = gossipUnbase64(envelope.attribution) ?: return null
    if (sealed.size < 28) return null
    return contacts.firstOrNull { durable ->
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, gossipRecognitionKey(durable, envelope.scope), GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
            val signature = cipher.doFinal(sealed.copyOfRange(12, sealed.size))
            verifyChallenge(gossipIdentityBinding(envelope.scope, envelope.author), signature, durable)
        }.getOrDefault(false)
    }
}
