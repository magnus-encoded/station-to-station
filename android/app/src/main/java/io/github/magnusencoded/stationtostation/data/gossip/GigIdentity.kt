package io.github.magnusencoded.stationtostation.data.gossip

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.magnusencoded.stationtostation.data.exchange.contactIdentityPublicKeyBase64
import io.github.magnusencoded.stationtostation.data.exchange.signWithContactIdentity
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher

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
        val payload = gossipIdentityBinding(scope, publicKey())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, gossipRecognitionKey(durable, scope))
        return gossipBase64(cipher.iv + cipher.doFinal(signWithContactIdentity(payload)))
    }
}
