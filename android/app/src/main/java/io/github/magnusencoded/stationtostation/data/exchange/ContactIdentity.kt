package io.github.magnusencoded.stationtostation.data.exchange

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

private const val ALIAS = "contact-identity"

/**
 * The device's durable Contact-facing identity (#257): one AndroidKeyStore keypair,
 * generated once and reused across every Exchange and every later LAN reconcile
 * session — unlike #142's ephemeral per-handover cert, this is the identity a Friend's
 * [io.github.magnusencoded.stationtostation.data.Friend.publicKey] pins to, and what a
 * LAN beacon later proves possession of via [signWithContactIdentity] instead of a
 * fresh QR scan.
 *
 * ECDSA P-256, not Ed25519: native Ed25519 in AndroidKeyStore needs API 33+, and this
 * app's minSdk is 26. Same call #142's AndroidKeyStoreCert.kt already made, for the
 * same reason.
 *
 * Not unit-tested, for the same reason as AndroidKeyStoreCert.kt: AndroidKeyStore is a
 * real, often hardware-backed provider that doesn't exist off-device. [signChallenge]
 * and [verifyChallenge] are where the signature math is pure and covered.
 */
private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

private fun ensureGenerated(keyStore: KeyStore) {
    if (keyStore.containsAlias(ALIAS)) return
    val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
        // SHA-256 only, and it has to stay that way: this key is already on every device
        // that has ever made a Contact, and AndroidKeyStore keys are immutable — widening
        // this would mean a new key, and a new key is a new identity every existing
        // Contact would stop recognising. It signs challenges, never a TLS handshake:
        // TLS needs DIGEST_NONE, which is why a reconcile session mints its own
        // certificate (`AndroidKeyStoreCert.generateContactSessionIdentity`).
        .setDigests(KeyProperties.DIGEST_SHA256)
        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
        .build()
    KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        .apply { initialize(spec) }
        .generateKeyPair()
}

/** Base64 X.509 SubjectPublicKeyInfo for the device's persisted identity — what goes on a Friend/ProbeCard. */
fun contactIdentityPublicKeyBase64(): String {
    val keyStore = keyStore()
    ensureGenerated(keyStore)
    return Base64.getEncoder().encodeToString(keyStore.getCertificate(ALIAS).publicKey.encoded)
}

/** The device's persisted identity key, for [mutualContactAuth] to sign with directly —
 * a reference into AndroidKeyStore, never the raw key material. */
fun contactIdentityPrivateKey(): PrivateKey {
    val keyStore = keyStore()
    ensureGenerated(keyStore)
    return keyStore.getKey(ALIAS, null) as PrivateKey
}

/** Signs [nonce] with the device's persisted identity — the proof a LAN peer checks against the public key already on their Friend record. */
fun signWithContactIdentity(nonce: ByteArray): ByteArray = signChallenge(nonce, contactIdentityPrivateKey())
