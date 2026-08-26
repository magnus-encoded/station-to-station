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

/** Where a Contact reconcile session's own certificate lives (#257). Deliberately *not*
 * the durable [contactIdentityPrivateKey]: that key was generated SHA-256-only and so
 * cannot sign a TLS handshake at all (see [selfSignedIdentity]), and it predates every
 * Contact already on a device, so it cannot be regenerated without breaking them. It
 * still signs the proof inside the session — only never the handshake. */
const val CONTACT_SESSION_ALIAS = "contact-session"

/** The alias a handover session's key sits under. Callers need it because `AndroidKeyStore`
 * is one store for the whole app: see [keyManagersFor] for why TLS has to be told which
 * key of the app's many it is meant to present. */
fun handoverAlias(sessionId: String): String = KEYSTORE_ALIAS_PREFIX + sessionId

private fun selfSignedIdentity(alias: String, lifetimeMs: Long): Pair<X509Certificate, KeyStore> {
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    if (!keyStore.containsAlias(alias)) {
        val now = Date()
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            // DIGEST_NONE as well as SHA-256, and it is the whole reason TLS works here: a
            // TLS stack signs its CertificateVerify with *raw* ECDSA over an already-hashed
            // input (`NONEwithECDSA`). A key restricted to SHA-256 refuses that —
            // `Incompatible digest` out of keymaster, "Could not find provider for
            // algorithm: NONEwithECDSA" out of Conscrypt — and the handshake dies with no
            // exception any of our own code can see.
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setCertificateSubject(X500Principal("CN=$alias"))
            .setCertificateSerialNumber(BigInteger.ONE)
            .setCertificateNotBefore(now)
            // Comfortably outlives any single session; the alias is one-shot and deleted
            // when the session ends, so an expired leftover key is inert rather than a
            // certificate anyone reuses.
            .setCertificateNotAfter(Date(now.time + lifetimeMs))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            .apply { initialize(spec) }
            .generateKeyPair()
    }
    val cert = keyStore.getCertificate(alias) as X509Certificate
    return cert to keyStore
}

fun generateHandoverIdentity(sessionId: String): Pair<X509Certificate, KeyStore> =
    selfSignedIdentity(handoverAlias(sessionId), lifetimeMs = 10 * 60 * 1000L)

/** A fresh certificate for one foreground reconcile session, replacing any leftover from a
 * session that never got to call [forgetContactSessionIdentity]. A day of validity rather
 * than ten minutes: this one lives as long as the screen stays open. */
fun generateContactSessionIdentity(): Pair<X509Certificate, KeyStore> {
    forgetContactSessionIdentity()
    return selfSignedIdentity(CONTACT_SESSION_ALIAS, lifetimeMs = 24 * 60 * 60 * 1000L)
}

/** Removes the ephemeral identity once the session is over — nothing here is meant to
 * outlive it, and `AndroidKeyStore` does not clean up after itself. */
fun forgetHandoverIdentity(sessionId: String) = deleteAlias(handoverAlias(sessionId))

fun forgetContactSessionIdentity() = deleteAlias(CONTACT_SESSION_ALIAS)

private fun deleteAlias(alias: String) {
    KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
}
