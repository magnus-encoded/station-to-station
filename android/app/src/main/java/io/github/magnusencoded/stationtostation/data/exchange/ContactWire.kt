package io.github.magnusencoded.stationtostation.data.exchange

import java.io.EOFException
import java.net.Socket
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.X509Certificate
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

/** Both ends of a Contact reconcile session use the same context: nothing is pinned at
 * the TLS layer, so client and server contexts do not differ the way `HandoverWire`'s do. */
fun contactSessionContext(): SSLContext =
    SSLContext.getInstance("TLS").apply { init(null, arrayOf(AcceptAnyTrustManager), SecureRandom()) }

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
        val matched = verifyContactIdentity(socket, peerCert, candidates) ?: return null
        proveContactIdentity(socket, ownCert, privateKey)
        matched
    } else {
        proveContactIdentity(socket, ownCert, privateKey)
        verifyContactIdentity(socket, peerCert, candidates)
    }
}

private fun Socket.session() = (this as javax.net.ssl.SSLSocket).session
