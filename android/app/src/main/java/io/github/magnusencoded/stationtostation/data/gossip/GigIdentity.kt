package io.github.magnusencoded.stationtostation.data.gossip

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.magnusencoded.stationtostation.data.exchange.contactIdentityPublicKeyBase64
import io.github.magnusencoded.stationtostation.data.exchange.signWithContactIdentity
import io.github.magnusencoded.stationtostation.data.exchange.verifyChallenge
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Per-Gig signing key. Durable identity is disclosed only to a holder of the exchanged Card key. */
class GigIdentity(private val scope: String) {
    private companion object { val keyCreationLock = Any() }
    private val alias = "gossip-gig-$scope"
    private fun store(): KeyStore = synchronized(keyCreationLock) { KeyStore.getInstance("AndroidKeyStore").apply {
        load(null)
        if (!containsAlias(alias)) {
            KeyPairGenerator.getInstance("EC", "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setDigests(KeyProperties.DIGEST_SHA256).setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).build())
            }.generateKeyPair()
        }
    } }
    fun publicKey(): String = gossipBase64(store().getCertificate(alias).publicKey.encoded)
    fun sign(bytes: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(store().getKey(alias, null) as PrivateKey); update(bytes); sign()
    }
    fun attribution(): String {
        val durable = contactIdentityPublicKeyBase64()
        val payload = identityBinding(scope, publicKey())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, recognitionKey(durable, scope))
        return gossipBase64(cipher.iv + cipher.doFinal(signWithContactIdentity(payload)))
    }
}

private fun identityBinding(scope: String, author: String) =
    "station-to-station/gossip-identity/2\n$scope\n$author".toByteArray(Charsets.UTF_8)
private fun recognitionKey(durable: String, scope: String) = SecretKeySpec(MessageDigest.getInstance("SHA-256")
    .digest("station-to-station/gossip-mask/2\n$durable\n$scope".toByteArray(Charsets.UTF_8)), "AES")

/** The Card public key is the recognition capability, not a content-encryption promise. */
fun recognizeGossip(envelope: GossipEnvelope, contacts: Set<String>): String? {
    val sealed = gossipUnbase64(envelope.attribution) ?: return null
    if (sealed.size < 28) return null
    return contacts.firstOrNull { durable ->
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, recognitionKey(durable, envelope.scope), GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
            val signature = cipher.doFinal(sealed.copyOfRange(12, sealed.size))
            verifyChallenge(identityBinding(envelope.scope, envelope.author), signature, durable)
        }.getOrDefault(false)
    }
}
