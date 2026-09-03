package io.github.magnusencoded.stationtostation.data.exchange

import io.github.magnusencoded.stationtostation.data.AccountsMove
import io.github.magnusencoded.stationtostation.data.AccountsPayload
import io.github.magnusencoded.stationtostation.data.GalleryItem
import io.github.magnusencoded.stationtostation.data.HandoverManifest
import io.github.magnusencoded.stationtostation.data.HandoverPlan
import io.github.magnusencoded.stationtostation.data.TimelineCache
import io.github.magnusencoded.stationtostation.data.bulkMayStart
import io.github.magnusencoded.stationtostation.data.categoriesFor
import io.github.magnusencoded.stationtostation.data.handoverPlan
import io.github.magnusencoded.stationtostation.data.openManifest
import io.github.magnusencoded.stationtostation.data.sealManifest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.Socket

/**
 * One device handover, end to end, over an already-connected socket (#142).
 *
 * Two halves of one conversation, in a fixed order, with the **source** — the phone being
 * replaced — as the server, because it is the one that showed the QR and the one whose
 * approval the whole transfer hangs off:
 *
 *  1. the link key, proved by the joining phone against the nonce the source sends;
 *  2. the accounts step, small and structured and acknowledged, before any bulk byte
 *     moves (#143) — so a failure there costs seconds, not 4.6 GB;
 *  3. the sealed manifest, verified by the receiver before it plans anything;
 *  4. the receiver's request list, computed by `handoverPlan` and carried, not decided,
 *     by the wire;
 *  5. the items, streamed; then the end-of-items marker;
 *  6. the receipt, counted by the receiver and sent back so both phones can say the same
 *     thing about what happened.
 *
 * **Cancelling is closing the socket.** A blocking socket read is not interruptible by a
 * flag, so there is no flag: the screen closes the socket and both sides fall out of their
 * loops. What already landed stays landed — see [runHandoverReceiver]'s `finally`.
 *
 * Everything here is plain [Socket], so the whole flow is exercised over a loopback pair in
 * `HandoverSessionTest` with no radio, no TLS and no device. TLS is the same socket with a
 * different factory ([sslServerContext]/[sslClientContext]), which is exactly the point of
 * having kept it out of this file.
 */

enum class HandoverPhase { CONNECTING, ACCOUNTS, MANIFEST, TRANSFER, DONE, FAILED }

data class HandoverProgress(
    val phase: HandoverPhase = HandoverPhase.CONNECTING,
    val bytesDone: Long = 0L,
    /** What was committed to up front (#142 story 14), not what has been seen so far. */
    val bytesTotal: Long = 0L,
    val items: Int = 0,
    val itemsTotal: Int = 0,
)

// --- The QR, which is the trust anchor ------------------------------------------------
//
// Out-of-band key material in one mechanism: where to connect, whose certificate to pin,
// and the key that proves the joining phone read this screen and not a photograph of some
// other one. It is a deep link for the same reason a friend card is — any phone camera
// opens it, so there is no in-app scanner and no camera permission on the receiving side.

/** Where the source is listening, and the two secrets that make the session that phone's. */
data class HandoverInvite(
    val host: String,
    val port: Int,
    val fingerprint: ByteArray,
    val linkKey: ByteArray,
) {
    // ByteArray in a data class: identity equals, which is never what a caller means.
    override fun equals(other: Any?): Boolean = other is HandoverInvite &&
        host == other.host && port == other.port &&
        fingerprint.contentEquals(other.fingerprint) && linkKey.contentEquals(other.linkKey)

    override fun hashCode(): Int =
        ((host.hashCode() * 31 + port) * 31 + fingerprint.contentHashCode()) * 31 + linkKey.contentHashCode()
}

fun HandoverInvite.toUri(): String =
    "station-to-station://handover?h=$host&p=$port&f=${encodeHex(fingerprint)}&k=${encodeHex(linkKey)}"

/**
 * Null for anything that is not a well-formed handover invite — a friend card, a link
 * someone typed by hand, a truncated scan. Parsed with plain string operations rather than
 * `android.net.Uri` so it is testable on a laptop, which is where the interesting cases
 * (missing parameter, odd-length hex, junk port) are cheap to assert.
 */
fun parseHandoverInvite(uri: String): HandoverInvite? {
    if (!uri.startsWith("station-to-station://handover?")) return null
    val params = uri.substringAfter('?').split('&').mapNotNull {
        val k = it.substringBefore('=', "")
        val v = it.substringAfter('=', "")
        if (k.isEmpty() || v.isEmpty()) null else k to v
    }.toMap()
    val host = params["h"] ?: return null
    val port = params["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    val fingerprint = params["f"]?.let(::decodeHexOrNull)?.takeIf { it.isNotEmpty() } ?: return null
    val linkKey = params["k"]?.let(::decodeHexOrNull)?.takeIf { it.isNotEmpty() } ?: return null
    return HandoverInvite(host, port, fingerprint, linkKey)
}

internal fun encodeHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

internal fun decodeHexOrNull(hex: String): ByteArray? = runCatching {
    val clean = hex.trim()
    require(clean.length % 2 == 0 && clean.isNotEmpty())
    ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}.getOrNull()

// --- The source's half -----------------------------------------------------------------

/**
 * The phone being replaced. Returns the receiver's [HandoverReceipt], or null if the
 * joining phone could not prove it read the QR — in which case nothing at all was sent,
 * not even the manifest, which is the whole anti-spoofing guarantee.
 *
 * [manifest] is already narrowed by the tick list (`deviceManifest`), so this function
 * cannot send what was not approved: an id requested but absent from the manifest is
 * simply not sent, however loudly it is asked for.
 */
suspend fun runHandoverSource(
    socket: Socket,
    linkKey: ByteArray,
    allow: Set<String>,
    manifest: HandoverManifest,
    /** The accounts step, whole — see `AppViewModel.sendHandoverAccounts`. Always called;
     * what the tick list decides is whether the payload it sends carries a credential or
     * only identities ([AccountsMove] then reports what the far end did with it). */
    accounts: suspend (Socket) -> AccountsMove,
    mediaSource: (id: String) -> Pair<Long, InputStream>?,
    onProgress: (HandoverProgress) -> Unit = {},
): HandoverReceipt? {
    if (!verifyLinkKey(socket, linkKey)) return null

    onProgress(HandoverProgress(phase = HandoverPhase.ACCOUNTS))
    // Unconditional, whatever was ticked: the frame always travels, carrying identities
    // only when the row was declined (#143 story 11). Skipping it when accounts are
    // unticked would leave the receiver — which always reads one — parked on the manifest
    // frame, reading a sealed manifest as an accounts payload and desyncing everything
    // after it. What the tick list changes is the *payload*, not whether it is sent.
    val step = accounts(socket)
    // Accounts complete before bytes begin. A half-finished credential move is the one
    // state worth refusing to build on.
    if (!bulkMayStart(allow, step)) {
        // Never ACKNOWLEDGED here — bulkMayStart would then be true — so `step` is exactly
        // #143 story 9's "offered but did not complete".
        return HandoverReceipt(trouble = "the accounts step did not complete", accountsMove = step)
    }

    onProgress(HandoverProgress(phase = HandoverPhase.MANIFEST))
    writeManifest(socket, sealManifest(linkKey, manifest))

    val offered = manifest.media.associateBy { it.id }
    val ids = readRequest(socket).filter { it in offered }
    val total = ids.sumOf { offered.getValue(it).bytes }
    var sent = 0L
    var done = 0
    onProgress(HandoverProgress(HandoverPhase.TRANSFER, 0L, total, 0, ids.size))
    for (id in ids) {
        val (length, stream) = mediaSource(id) ?: continue
        stream.use { body ->
            writeItem(socket, id, length, body) { chunk ->
                sent += chunk
                onProgress(HandoverProgress(HandoverPhase.TRANSFER, sent, total, done, ids.size))
            }
        }
        done++
        onProgress(HandoverProgress(HandoverPhase.TRANSFER, sent, total, done, ids.size))
    }
    writeEndOfItems(socket)

    val receipt = readReceipt(socket) ?: HandoverReceipt(trouble = "the other phone hung up before saying what landed")
    onProgress(HandoverProgress(HandoverPhase.DONE, sent, total, done, ids.size))
    return receipt
}

// --- The receiver's half ---------------------------------------------------------------

/**
 * The new phone. Returns the receipt it sent back, or null if the manifest failed to
 * verify — and in that case **nothing is written**, which is the contract `openManifest`
 * exists to make total rather than conditional on a caller remembering to check.
 *
 * [apply] is handed a *replan* rather than a plan: a function from "the cache as it stands
 * at the moment of writing" to the union to write. A 4.6 GB transfer takes long enough for
 * this device's own timeline to have moved on — a Contact reconcile landing, a note typed —
 * and writing a union computed against a cache read before all that would quietly discard
 * it. The store runs it under its own write lock (`TimelineStore.applyHandover`).
 *
 * It is called whatever happens, cancellation included, so a stopped transfer still lands
 * exactly what arrived: items are independently addressed and complete-or-absent, so a
 * smaller union is a coherent library rather than a corrupt one (#142 stories 13 and 16).
 * Nothing is checkpointed anywhere, because nothing needs to be — re-running the handover
 * asks for precisely the remainder.
 */
suspend fun runHandoverReceiver(
    socket: Socket,
    linkKey: ByteArray,
    /** See `AppViewModel.receiveHandoverAccounts`: stores durably, *then* acks, and hands
     * back whatever arrived — null only on a connection dropped before any accounts frame,
     * which desyncs the frames that follow and so never reaches the receipt below. */
    accounts: suspend (Socket) -> AccountsPayload?,
    mine: TimelineCache,
    gallery: List<GalleryItem>,
    receivedFile: (id: String, kind: String) -> File,
    refForReceivedFile: (File) -> String = { it.toURI().toString() },
    apply: suspend (replan: (TimelineCache) -> HandoverPlan) -> Unit,
    onProgress: (HandoverProgress) -> Unit = {},
): HandoverReceipt? {
    proveLinkKey(socket, linkKey)

    onProgress(HandoverProgress(phase = HandoverPhase.ACCOUNTS))
    // Not "was the row ticked" — this side never sees the tick list — but "did a
    // credential actually arrive": a declined row still sends an identities-only payload
    // (#143 story 11), which is the same shape as "not part of this handover" from the
    // receipt's point of view (#143 story 9).
    val accountsMove = accounts(socket)
        ?.takeUnless { it.credentials.spotifyRefreshToken.isNullOrBlank() }
        ?.let { AccountsMove.ACKNOWLEDGED }
        ?: AccountsMove.NOT_OFFERED

    onProgress(HandoverProgress(phase = HandoverPhase.MANIFEST))
    val sealed = readManifest(socket) ?: return null
    val offer = openManifest(linkKey, sealed) ?: return null

    // The receiver's `allow` is every category a device handover may carry, deliberately:
    // the source's tick list was applied when the manifest was built, and restating it
    // here would be a second copy of the same decision, in the one place that cannot see
    // what was ticked.
    val allow = categoriesFor(contact = false)
    val plan = handoverPlan(mine, offer, allow, verified = true, gallery = gallery)

    writeRequest(socket, plan.request)

    val kinds = offer.media.associate { it.id to it.kind }
    val expected = plan.request.toSet()
    val total = offer.media.filter { it.id in expected }.sumOf { it.bytes }
    val arrived = LinkedHashMap<String, String>()
    var bytes = 0L
    var trouble = ""
    try {
        receiveRequested(
            socket = socket,
            expected = expected,
            receivedFile = receivedFile,
            refFor = refForReceivedFile,
            kinds = kinds,
            onBytes = { chunk ->
                bytes += chunk
                onProgress(HandoverProgress(HandoverPhase.TRANSFER, bytes, total, arrived.size, expected.size))
            },
            onItem = { id, ref -> arrived[id] = ref },
        )
    } catch (e: Exception) {
        // Cancelled here, cancelled there, or the wifi went: the same outcome, and the
        // same coherent smaller library. Named for the receipt rather than swallowed.
        trouble = "the transfer stopped early — ${arrived.size} of ${expected.size} items arrived"
    }

    val replan = { current: TimelineCache ->
        handoverPlan(current, offer, allow, verified = true, gallery = gallery, received = arrived)
    }
    // NonCancellable, and this is the line that makes the paragraph above true rather
    // than aspirational. Cancelling a handover closes the socket *and* cancels the job, so
    // by the time execution reaches here the coroutine is already Cancelling — and both
    // `withContext(Dispatchers.IO)` and the store's write lock throw on entry in that
    // state. The write would be skipped, and every complete item that just landed would
    // sit on disk with nothing in the timeline pointing at it, under a screen that says
    // what arrived was kept.
    withContext(NonCancellable) { apply(replan) }
    // The same function again for the receipt's tallies, against the cache the plan was
    // first computed from. Counting what *this* transfer resolved, not what the union
    // happens to hold once everything else on the device is folded in.
    val landed = replan(mine)

    val receipt = HandoverReceipt(
        landed = arrived.size,
        bytes = bytes,
        held = landed.held.size,
        fromGallery = landed.fromGallery.size - arrived.size,
        withheld = landed.withheld.size,
        refused = landed.refused.size,
        requested = expected.size,
        countMismatch = landed.countMismatch,
        trouble = trouble,
        accountsMove = accountsMove,
    )
    // Best-effort: if the socket is already gone (the usual reason `trouble` is set), the
    // source simply reports what it sent. Failing to hand back a receipt must not undo a
    // transfer that actually landed.
    runCatching { writeReceipt(socket, receipt) }
    onProgress(
        HandoverProgress(
            phase = if (trouble.isEmpty()) HandoverPhase.DONE else HandoverPhase.FAILED,
            bytesDone = bytes, bytesTotal = total, items = arrived.size, itemsTotal = expected.size,
        )
    )
    return receipt
}
