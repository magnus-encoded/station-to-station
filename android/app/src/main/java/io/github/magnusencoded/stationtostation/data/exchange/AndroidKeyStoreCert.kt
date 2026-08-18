package io.github.magnusencoded.stationtostation.data.exchange

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * The self-signed identity a device presents for one handover session (#142), generated
 * with `AndroidKeyStore` rather than a crypto library: asking for `setCertificateSubject`
 * on an `AndroidKeyStore` keypair is the platform's own way to get a self-signed X.509
 * certificate, so this is the standard tool rather than a construction of our own — there
 * is no `sun.security.x509` on Android and no Bouncy Castle in this project, and neither
 * is needed.
 *
 * **Not unit-tested.** `AndroidKeyStore` is a real, often hardware-backed provider that
 * does not exist off-device, so this file is the one part of the handover transport that
 * only runs on a phone. Everything downstream of "here is a certificate and a private
 * key" — [sslServerContext], pinning, framing, the link-key proof — is plain JVM code and
 * is covered by `HandoverWireTest` against a fixture certificate instead.
 *
 * A fresh identity per session, not a long-lived one: nothing here needs to be recognised
 * across sessions, and a key that never outlives the handover that generated it is one
 * fewer long-lived secret on the device.
 */
private const val KEYSTORE_ALIAS_PREFIX = "handover-"

fun generateHandoverIdentity(sessionId: String): Pair<X509Certificate, KeyStore> {
    val alias = KEYSTORE_ALIAS_PREFIX + sessionId
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    if (!keyStore.containsAlias(alias)) {
        val now = Date()
        val tenMinutesOut = Date(now.time + 10 * 60 * 1000)
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setCertificateSubject(X500Principal("CN=$alias"))
            .setCertificateSerialNumber(BigInteger.ONE)
            .setCertificateNotBefore(now)
            // Comfortably outlives any single handover; the alias itself is one-shot, so
            // an expired leftover key is inert rather than a certificate anyone reuses.
            .setCertificateNotAfter(tenMinutesOut)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            .apply { initialize(spec) }
            .generateKeyPair()
    }
    val cert = keyStore.getCertificate(alias) as X509Certificate
    return cert to keyStore
}

/** Removes the ephemeral identity once the session is over — nothing here is meant to
 * outlive it, and `AndroidKeyStore` does not clean up after itself. */
fun forgetHandoverIdentity(sessionId: String) {
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    keyStore.deleteEntry(KEYSTORE_ALIAS_PREFIX + sessionId)
}
