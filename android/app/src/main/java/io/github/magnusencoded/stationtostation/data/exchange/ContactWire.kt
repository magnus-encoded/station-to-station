package io.github.magnusencoded.stationtostation.data.exchange

import java.io.EOFException
import java.net.Socket
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Session authentication for a LAN Contact reconcile (#257): challenge-response over
 * each side's persisted identity key, standing in for the fresh QR fingerprint #142's
 * `HandoverWire` pins against — there is no QR moment here, on purpose, since the whole
 * point of #257 is re-authenticating an already-Exchanged Contact without one.
 *
 * mDNS only announces "a device is here" (#257's presence-only decision), so neither
 * side has a fingerprint to pin *before* connecting the way the QR flow does. Trust
 * instead comes entirely after the handshake, from a signature: each side signs a nonce
 * the peer sent *plus the peer's own certificate fingerprint*, using its persisted
 * [signWithContactIdentity]/[ContactIdentity] key. Folding the fingerprint into what is
 * signed is what binds the proof to this exact TLS session — a signature captured off
 * one connection and replayed on another would carry the wrong fingerprint and fail.
 *
 * Whoever is verifying does not yet know *which* Contact is on the other end (mDNS
 * carries no identity), so [verifyContactIdentity] is handed every locally-known
 * candidate key and returns whichever one matches — or null, for a peer that is not
 * (yet) any known Contact, dropped silently per #257's "absence is a state" posture.
 */

/** Accepts any certificate at the TLS layer. There is nothing to pin ahead of the
 * handshake here — identity is established afterwards, over the signature, not during
 * it — so this is deliberately as permissive as [io.github.magnusencoded.stationtostation.data.exchange.PinnedTrustManager]
 * is strict. */
object AcceptAnyTrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Both ends of a Contact reconcile session use the same *shape* of context — nothing is
 * pinned at the TLS layer, so trust doesn't differ the way `HandoverWire`'s client/server
 * split does — but both ends present [keyStore]'s identity, mutual TLS. That's not
 * optional: [proveContactIdentity] signs the fingerprint of the certificate *this* socket
 * presented, and [verifyContactIdentity] on the far end checks it against
 * `peerCertificates` — the fingerprint has nothing to bind to on either side unless both
 * sides actually put a certificate on the wire.
 *
 * A server socket built from this context additionally needs `setWantClientAuth(true)`
 * before its handshake, since a plain [javax.net.ssl.SSLServerSocket] does not request a
 * client certificate on its own.
 *
 * **This is what makes Android↔iOS work at all (#267), and it is load-bearing.** The two
 * platforms do not present the same kind of certificate and never will: each side mints
 * whatever its own platform can actually sign a handshake with, and throws it away when
 * the screen closes — the certificate is never the identity. They interoperate only
 * because **trust never comes from the certificate** — it comes from a signature over
 * that certificate's fingerprint, made with the Contact key the two swapped in person.
 *
 * So: anything that later pins a certificate, remembers one between sessions, or ties one
 * to the identity key ends interop silently, and would still pass every Android↔Android
 * test, because there both ends are the same platform minting the same shape of
 * certificate. The same warning is on iOS's `ContactExchange.parameters`.
 */
fun contactSessionContext(keyStore: KeyStore, keyPassword: CharArray, alias: String? = null): SSLContext {
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    kmf.init(keyStore, keyPassword)
    return SSLContext.getInstance("TLS").apply {
        init(keyManagersFor(kmf, alias), arrayOf(AcceptAnyTrustManager), SecureRandom())
    }
}

/**
 * The proving half: wait for the peer's nonce, then answer with a signature over
 * `certFingerprint(peerCert) + nonce` — [peerCert] is the certificate *this* socket
 * presented to the peer (the local session's own leaf cert), which is what the peer
 * will recompute the fingerprint of on their side to check the answer against.
 */
fun proveContactIdentity(socket: Socket, ownCert: Certificate, privateKey: PrivateKey) {
    val nonce = readFrame(socket.getInputStream()) ?: throw EOFException("no challenge from peer")
    writeFrame(socket.getOutputStream(), signChallenge(certFingerprint(ownCert) + nonce, privateKey))
}

/**
 * The checking half: send a fresh nonce, then test the answer against every key in
 * [candidates] — the base64 [io.github.magnusencoded.stationtostation.data.Friend.publicKey]
 * of every currently-persisted Contact. [peerCert] is the certificate the *peer*
 * presented over this socket, matching what they signed on their side.
 *
 * Returns the matching candidate key, or null if none of them verify.
 */
fun verifyContactIdentity(socket: Socket, peerCert: Certificate, candidates: List<String>): String? {
    val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
    writeFrame(socket.getOutputStream(), nonce)
    val signature = readFrame(socket.getInputStream()) ?: return null
    val expected = certFingerprint(peerCert) + nonce
    return candidates.firstOrNull { verifyChallenge(expected, signature, it) }
}

/**
 * Both directions of [proveContactIdentity]/[verifyContactIdentity] over one socket, in
 * a fixed order so the two ends never both wait on a read: the server round (server
 * verifies, client proves) always goes first, then the client round (client verifies,
 * server proves) — every caller, on both ends, runs this same function and just says
 * which side of the socket it is.
 *
 * Returns the peer's matched Contact key, or null the moment either round fails to
 * verify — an unrecognised peer, dropped without a reason surfaced back to it.
 *
 * A failed verify closes the socket before returning null. Without that, the losing
 * side's own still-pending verify round blocks on a read the other end — having already
 * bailed out — will never answer: a real deadlock, not a hypothetical one, since the
 * server round always finishes first and the client round is what would be left hanging.
 */
fun mutualContactAuth(
    socket: Socket,
    isServer: Boolean,
    ownCert: Certificate,
    privateKey: PrivateKey,
    candidates: List<String>,
): String? {
    val peerCert = socket.session().peerCertificates[0]
    return if (isServer) {
        val matched = verifyContactIdentity(socket, peerCert, candidates)
            ?: run { socket.close(); return null }
        proveContactIdentity(socket, ownCert, privateKey)
        matched
    } else {
        proveContactIdentity(socket, ownCert, privateKey)
        verifyContactIdentity(socket, peerCert, candidates) ?: run { socket.close(); null }
    }
}

private fun Socket.session() = (this as javax.net.ssl.SSLSocket).session
