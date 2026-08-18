package io.github.magnusencoded.stationtostation.data.exchange

import io.github.magnusencoded.stationtostation.data.SealedManifest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * The transport for a device handover (#142): TLS over the local link, keyed from the
 * QR/deep-link handshake that [ExchangeSession] already establishes between two devices.
 *
 * **Not the wire's cryptography.** [SealedManifest] (`Wire.kt`) is the manifest's own
 * integrity — signed as a unit, verified before any write. This file is what carries
 * bytes: a standard TLS socket, authenticated in one mechanism rather than three:
 *
 *  - **Confidentiality and cipher agility** come from TLS itself. Cipher suite
 *    negotiation is what picks AES-GCM where there is hardware acceleration and
 *    ChaCha20-Poly1305 where there is not — a capability switch obtained for free
 *    rather than one we would write and maintain.
 *  - **The server is who the QR said it would be.** There is no certificate authority
 *    and no server to ask, so the client pins the exact SHA-256 fingerprint of the
 *    leaf certificate the QR carried, via [PinnedTrustManager]. A device presenting any
 *    other certificate — spoofed, self-signed by someone else, anything — fails the
 *    handshake before a byte of application data moves.
 *  - **The client proves it read the same QR**, because certificate pinning alone only
 *    authenticates the server to the client, not the other way around: anyone can open
 *    a TCP connection and complete a TLS handshake against a cert they merely observed.
 *    [proveLinkKey]/[verifyLinkKey] close that gap with a nonce challenge answered by
 *    HMAC-SHA256 over the same `linkKey` the QR carried — the same primitive
 *    [io.github.magnusencoded.stationtostation.data.sealManifest] already uses, not a
 *    new one. A device that cannot produce the key gets a TLS session and nothing else:
 *    the server closes without ever sending the manifest.
 *
 * **What this does not do.** It does not decide what to transfer — that is
 * `handoverPlan`, specified separately, and this carries whatever list of item ids the
 * caller hands it. It does not generate the server's certificate: that is
 * `AndroidKeyStoreCert.generateHandoverIdentity`, backed by `AndroidKeyStore` on-device
 * and deliberately kept out of this file so everything here runs — and is tested — as
 * plain JVM sockets over loopback, no radio and no device required.
 */

private val wireJson = Json { encodeDefaults = true }

/** SHA-256 of the DER encoding — what the QR carries and what the client pins against. */
fun certFingerprint(cert: Certificate): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(cert.encoded)

/**
 * Trusts exactly one certificate: the one whose fingerprint the QR carried. Everything
 * else — expiry, hostname, chain, CA — is beside the point for a certificate nobody but
 * the two devices in the room will ever see, and checking it anyway would only be a
 * chance to get that checking wrong. The fingerprint compare is the whole trust decision.
 */
class PinnedTrustManager(private val expectedFingerprint: ByteArray) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        throw CertificateException("handover pinning is server-only; no client certificate is requested")

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        val presented = chain.firstOrNull()
            ?: throw CertificateException("no certificate presented")
        if (!MessageDigest.isEqual(certFingerprint(presented), expectedFingerprint)) {
            throw CertificateException("certificate fingerprint does not match the one the QR carried")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** The receiving side: trust nobody but the fingerprint the QR carried. No client cert. */
fun sslClientContext(pinnedFingerprint: ByteArray): SSLContext =
    SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(PinnedTrustManager(pinnedFingerprint)), SecureRandom())
    }

/**
 * The offering side: present whatever self-signed identity [keyStore] holds (see
 * `AndroidKeyStoreCert` for how that identity is produced on-device). No client
 * certificate is requested — the client authenticates itself with [proveLinkKey]
 * instead, over the channel TLS has already secured.
 */
fun sslServerContext(keyStore: KeyStore, keyPassword: CharArray): SSLContext {
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    kmf.init(keyStore, keyPassword)
    return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, SecureRandom()) }
}

// --- Framing -----------------------------------------------------------------------

/** Small control frames only (manifest, item headers, the auth exchange). Item bodies
 * are streamed separately at their declared length — never buffered whole, so a 4.6 GB
 * transfer never allocates a 4.6 GB byte array.
 *
 * ponytail: 8 MiB is a guess at "bigger than any real manifest, small enough to refuse
 * a hostile length outright". A library large enough to blow this on the manifest frame
 * alone would need the manifest itself chunked; raise the cap or split it if that shows
 * up in practice. */
private const val MAX_FRAME_BYTES = 8 * 1024 * 1024

fun writeFrame(out: OutputStream, bytes: ByteArray) {
    val d = DataOutputStream(out)
    d.writeInt(bytes.size)
    d.write(bytes)
    d.flush()
}

/** Null on a clean close between frames. Throws if the stream dies mid-frame — that is
 * not "no more items", it is a dropped connection, and the caller (resumption) needs to
 * tell the two apart. */
fun readFrame(inp: InputStream): ByteArray? {
    val d = DataInputStream(inp)
    val len = try {
        d.readInt()
    } catch (e: EOFException) {
        return null
    }
    require(len in 0..MAX_FRAME_BYTES) { "frame of $len bytes refused" }
    val buf = ByteArray(len)
    d.readFully(buf)
    return buf
}

/** Copies exactly [length] bytes — no more, no less — so a short body is a hard error
 * rather than a silently truncated item. */
fun copyExactly(inp: InputStream, out: OutputStream, length: Long, bufferSize: Int = 64 * 1024) {
    val buf = ByteArray(bufferSize)
    var remaining = length
    while (remaining > 0) {
        val n = inp.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
        if (n < 0) throw EOFException("connection closed after ${length - remaining} of $length bytes")
        out.write(buf, 0, n)
        remaining -= n
    }
}

// --- Link-key proof of possession ---------------------------------------------------

private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

/**
 * The client's half: answer the server's nonce with HMAC(linkKey, nonce). Sent as one
 * frame; the server checks it before anything else crosses the wire.
 */
fun proveLinkKey(socket: Socket, linkKey: ByteArray) {
    val nonce = readFrame(socket.getInputStream()) ?: throw EOFException("no challenge from server")
    writeFrame(socket.getOutputStream(), hmac(linkKey, nonce))
}

/**
 * The server's half: send a fresh nonce, and only proceed if the answer proves the
 * peer holds the same `linkKey` the QR carried. Returns false — and the caller must
 * close the connection without sending the manifest — on any mismatch or dropped
 * connection. This is the whole anti-spoofing guarantee: pinning authenticates us to
 * them, this authenticates them to us.
 */
fun verifyLinkKey(socket: Socket, linkKey: ByteArray): Boolean {
    val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
    writeFrame(socket.getOutputStream(), nonce)
    val answer = readFrame(socket.getInputStream()) ?: return false
    return MessageDigest.isEqual(answer, hmac(linkKey, nonce))
}

// --- Manifest and items ---------------------------------------------------------------

@Serializable
private data class ItemHeader(val id: String, val bytes: Long)

fun writeManifest(socket: Socket, sealed: SealedManifest) =
    writeFrame(socket.getOutputStream(), wireJson.encodeToString(sealed).toByteArray(Charsets.UTF_8))

fun readManifest(socket: Socket): SealedManifest? {
    val frame = readFrame(socket.getInputStream()) ?: return null
    return runCatching { wireJson.decodeFromString<SealedManifest>(frame.toString(Charsets.UTF_8)) }.getOrNull()
}

/**
 * One item, streamed straight from [body] to the socket at its declared [length] — the
 * sender never holds the whole file in memory, which matters at 4.6 GB.
 */
fun writeItem(socket: Socket, id: String, length: Long, body: InputStream) {
    writeFrame(socket.getOutputStream(), wireJson.encodeToString(ItemHeader(id, length)).toByteArray(Charsets.UTF_8))
    copyExactly(body, socket.getOutputStream(), length)
}

/** Marker frame: no more items are coming, sent in place of an [ItemHeader]. Explicit
 * rather than inferred from a closed socket, because the socket may still carry an ack
 * afterwards (the receipt, #12) — "done sending items" and "hanging up" are different
 * events and only one of them belongs here. */
private val END_OF_ITEMS = ByteArray(0)

fun writeEndOfItems(socket: Socket) = writeFrame(socket.getOutputStream(), END_OF_ITEMS)

/**
 * Blocks for the next item header, then hands the caller the id and declared length and
 * lets *them* drain [socket]'s stream into wherever an item belongs — a temp file the
 * caller renames into place only once [copyExactly] returns without throwing. That
 * caller-owned rename is what makes a cancelled or dropped transfer leave a coherent
 * subset rather than a half-written file: nothing is visible under its real name until
 * every declared byte of it has arrived.
 *
 * Returns null once [writeEndOfItems] arrives: the sender is genuinely done, not merely
 * paused. A dropped connection instead surfaces as an exception out of the next
 * [readFrame] or [copyExactly] — a different outcome on purpose, since "resume later" and
 * "nothing more was ever coming" cannot share a return value here.
 */
fun readItemHeader(socket: Socket): Pair<String, Long>? {
    val frame = readFrame(socket.getInputStream())
        ?: throw EOFException("connection closed before the end-of-items marker")
    if (frame.isEmpty()) return null
    val header = wireJson.decodeFromString<ItemHeader>(frame.toString(Charsets.UTF_8))
    return header.id to header.bytes
}
