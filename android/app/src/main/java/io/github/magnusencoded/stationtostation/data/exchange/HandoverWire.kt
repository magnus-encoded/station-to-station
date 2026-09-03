package io.github.magnusencoded.stationtostation.data.exchange

import io.github.magnusencoded.stationtostation.data.AccountsMove
import io.github.magnusencoded.stationtostation.data.AccountsPayload
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
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
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

// ignoreUnknownKeys, because the two ends of a handover are two *installs*, and the older
// one has to survive a frame from the newer one that carries a field it has never heard of.
// Without it, adding one optional field anywhere turns every cross-version transfer into a
// dropped connection.
private val wireJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

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
fun sslServerContext(keyStore: KeyStore, keyPassword: CharArray, alias: String? = null): SSLContext {
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    kmf.init(keyStore, keyPassword)
    return SSLContext.getInstance("TLS").apply {
        init(keyManagersFor(kmf, alias), null, SecureRandom())
    }
}

/**
 * Which key TLS presents, said out loud. `AndroidKeyStore` is a single store for the whole
 * app, so a [KeyManagerFactory] built from it sees every key this app ever generated — the
 * durable Contact identity, every leftover session key — and picks whichever one it likes.
 * [alias] pins that choice to the key this session actually minted. Without it a handshake
 * can present a certificate the QR never named (pinning fails) or a key TLS cannot sign
 * with at all (`Incompatible digest`, silently, mid-handshake).
 *
 * A null alias leaves the default selection alone — for a keystore holding exactly one
 * identity, which is what the off-device tests hand it.
 */
internal fun keyManagersFor(kmf: KeyManagerFactory, alias: String?): Array<KeyManager> =
    if (alias == null) kmf.keyManagers
    else kmf.keyManagers
        .map { if (it is X509ExtendedKeyManager) FixedAliasKeyManager(it, alias) else it }
        .toTypedArray()

private class FixedAliasKeyManager(
    private val delegate: X509ExtendedKeyManager,
    private val alias: String,
) : X509ExtendedKeyManager() {
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = alias
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = alias
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = alias
    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = alias
    override fun getCertificateChain(forAlias: String?): Array<X509Certificate>? = delegate.getCertificateChain(forAlias)
    override fun getPrivateKey(forAlias: String?): PrivateKey? = delegate.getPrivateKey(forAlias)
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        delegate.getClientAliases(keyType, issuers)
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        delegate.getServerAliases(keyType, issuers)
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
 * rather than a silently truncated item.
 *
 * [onBytes] is called with each chunk as it moves, which is where a progress bar gets its
 * numbers (#142 story 14). Cancellation is not a flag checked here: the caller closes the
 * socket, and this loop dies on the next read, because a blocking socket read is not
 * interruptible by anything gentler. */
fun copyExactly(
    inp: InputStream,
    out: OutputStream,
    length: Long,
    bufferSize: Int = 64 * 1024,
    onBytes: (Int) -> Unit = {},
) {
    val buf = ByteArray(bufferSize)
    var remaining = length
    while (remaining > 0) {
        val n = inp.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
        if (n < 0) throw EOFException("connection closed after ${length - remaining} of $length bytes")
        out.write(buf, 0, n)
        onBytes(n)
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

// --- Accounts step (#143) -----------------------------------------------------------
//
// Ordering only: this file decides nothing about *whether* accounts move — that is
// `Accounts.kt`'s `AccountsMove`/`mayClearCredentials`. What belongs here is putting the
// payload on the wire first, and giving the source a real ack to gate its clear on,
// exactly as #142 excludes ("Accounts moving rather than copying, specified separately")
// and #143's own testing decision asks for: driving the two ends over an in-memory pair,
// no radio required.

/** Source: the first frame of a handover, before the manifest or any item. [payload] is
 * whichever `Accounts.kt` built — the full thing when accounts were ticked, identities
 * only when declined (#143 story 11) — this function does not tell those apart. */
fun writeAccountsStep(socket: Socket, payload: AccountsPayload) =
    writeFrame(socket.getOutputStream(), wireJson.encodeToString(payload).toByteArray(Charsets.UTF_8))

/** Receiver: null only means the connection dropped before any accounts frame arrived —
 * a genuinely declined row still arrives as an (identities-only) [AccountsPayload], not
 * as nothing. Store what arrives, *then* ack: acking is the promise the source's clear
 * is gated on, so it must not be sent a moment before the payload is durable. */
fun readAccountsStep(socket: Socket): AccountsPayload? {
    val frame = readFrame(socket.getInputStream()) ?: return null
    return runCatching { wireJson.decodeFromString<AccountsPayload>(frame.toString(Charsets.UTF_8)) }
        .getOrNull()
}

private val ACCOUNTS_ACK = "accounts-stored".toByteArray(Charsets.UTF_8)

/** Receiver: send only once [readAccountsStep]'s payload is durably stored. */
fun writeAccountsAck(socket: Socket) = writeFrame(socket.getOutputStream(), ACCOUNTS_ACK)

/** Source: true only for the exact ack frame, so a dropped connection (null from
 * [readFrame]) or any other bytes read as "not acknowledged" — the source must keep its
 * credential ([Accounts.kt]'s `mayClearCredentials`) on anything but a clean true here. */
fun readAccountsAck(socket: Socket): Boolean =
    readFrame(socket.getInputStream())?.contentEquals(ACCOUNTS_ACK) == true

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
fun writeItem(socket: Socket, id: String, length: Long, body: InputStream, onBytes: (Int) -> Unit = {}) {
    writeFrame(socket.getOutputStream(), wireJson.encodeToString(ItemHeader(id, length)).toByteArray(Charsets.UTF_8))
    copyExactly(body, socket.getOutputStream(), length, onBytes = onBytes)
}

/** Marker frame: no more items are coming, sent in place of an [ItemHeader]. Explicit
 * rather than inferred from a closed socket, because the socket may still carry an ack
 * afterwards (the receipt, #12) — "done sending items" and "hanging up" are different
 * events and only one of them belongs here. */
private val END_OF_ITEMS = ByteArray(0)

fun writeEndOfItems(socket: Socket) = writeFrame(socket.getOutputStream(), END_OF_ITEMS)

/**
 * The receiver's ask, sent once the manifest has verified and the plan is computed: the
 * ids whose bytes it does not already hold. The wire carries this list, it does not
 * decide it — `handoverPlan` did that, on the receiving side, where the gallery it
 * matches against lives.
 */
fun writeRequest(socket: Socket, ids: List<String>) =
    writeFrame(socket.getOutputStream(), wireJson.encodeToString(ids).toByteArray(Charsets.UTF_8))

/** Empty on a dropped connection or an unreadable list — both mean "send nothing", which
 * is the safe direction: no bytes move that were not asked for. */
fun readRequest(socket: Socket): List<String> {
    val frame = readFrame(socket.getInputStream()) ?: return emptyList()
    return runCatching { wireJson.decodeFromString<List<String>>(frame.toString(Charsets.UTF_8)) }
        .getOrDefault(emptyList())
}

/**
 * The plain receipt (#142 story 12): what actually landed, counted by the receiver and
 * sent back so **both** phones can say the same thing about the transfer. A deliberate
 * act deserves a visible outcome, and the source is the phone the person is usually
 * holding.
 *
 * [countMismatch] is the one field that is not a tally but a verdict: the manifest's own
 * per-category counts, sealed inside the tag, disagreed with the items it listed.
 *
 * [accountsMove] is #143 story 9: whether the accounts step was part of this handover and
 * what became of it, reusing `Accounts.kt`'s own [AccountsMove] rather than a second
 * vocabulary. [AccountsMove.NOT_OFFERED] is both "the row was not ticked" and "an older
 * peer's receipt, decoded with no such field" — the same honest default either way: say
 * nothing about a step that was not offered. **Never a credential value** — this is a
 * verdict on the step, not the payload that carried it.
 */
@Serializable
data class HandoverReceipt(
    val landed: Int = 0,
    val bytes: Long = 0L,
    val held: Int = 0,
    val fromGallery: Int = 0,
    val withheld: Int = 0,
    val refused: Int = 0,
    val requested: Int = 0,
    val countMismatch: Boolean = false,
    /** Empty when it went through; otherwise what went wrong, in the receiver's words. */
    val trouble: String = "",
    val accountsMove: AccountsMove = AccountsMove.NOT_OFFERED,
)

fun writeReceipt(socket: Socket, receipt: HandoverReceipt) =
    writeFrame(socket.getOutputStream(), wireJson.encodeToString(receipt).toByteArray(Charsets.UTF_8))

/** Null if the receiver hung up before sending one — the source then knows only what it
 * sent, which is exactly the honest thing to show. */
fun readReceipt(socket: Socket): HandoverReceipt? {
    val frame = readFrame(socket.getInputStream()) ?: return null
    return runCatching { wireJson.decodeFromString<HandoverReceipt>(frame.toString(Charsets.UTF_8)) }.getOrNull()
}

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
