package io.github.magnusencoded.stationtostation.data.exchange

import io.github.magnusencoded.stationtostation.data.GalleryItem
import io.github.magnusencoded.stationtostation.data.HandoverManifest
import io.github.magnusencoded.stationtostation.data.StoredMedia
import io.github.magnusencoded.stationtostation.data.TimelineCache
import io.github.magnusencoded.stationtostation.data.contactLanding
import io.github.magnusencoded.stationtostation.data.contactReconcilePlan
import io.github.magnusencoded.stationtostation.data.isSafeMediaId
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.PrivateKey
import java.security.cert.Certificate

/**
 * One LAN reconcile visit, start to finish, over an already-TLS-connected [socket] (#257):
 * [mutualContactAuth], manifest exchange, [contactReconcilePlan], then streaming whatever
 * each side actually asked for. Everything after auth is symmetric except *order* — the
 * server side always moves first at every step, the same fixed-order trick
 * [mutualContactAuth] uses, so neither end ever blocks both directions on a read at once.
 *
 * Sending uses a length+[InputStream] rather than a [File] — see [mediaSource] — because a
 * real gallery ref is a MediaStore `content://` uri under scoped storage, not a path a raw
 * [File] can open.
 *
 * Returns the landing map ready for
 * [io.github.magnusencoded.stationtostation.data.TimelineStore.mergeContactMedia], or null
 * the moment the peer fails to verify as a known Contact — nothing is exchanged with a
 * stranger, not even a manifest.
 */
fun runContactSession(
    socket: Socket,
    isServer: Boolean,
    ownCert: Certificate,
    privateKey: PrivateKey,
    candidates: List<String>,
    myManifest: HandoverManifest,
    mine: TimelineCache,
    gallery: List<GalleryItem>,
    /** The byte length and an open stream for a media id I might be asked to send, or null
     * if I no longer have it — a length + [InputStream] rather than a [File] so a real
     * gallery ref (a MediaStore `content://` uri, unreachable as a raw file path under
     * scoped storage) can be sent the same way an app-owned copy is. */
    mediaSource: (id: String) -> Pair<Long, InputStream>?,
    /** Where a received item's bytes land, named for its id and its offered
     * [StoredMedia.kind] — the caller's to place. */
    receivedFile: (id: String, kind: String) -> File,
    /** The ref a landed [StoredMedia.ref] should carry for a file [receivedFile] wrote —
     * defaults to the file's own URI, but real storage (see
     * [io.github.magnusencoded.stationtostation.data.photos.PhotoRepository.fileProviderRef])
     * needs its own scheme for later ownership checks to recognise it. */
    refForReceivedFile: (File) -> String = { it.toURI().toString() },
    /** Called with the **Notes** as soon as the manifests have been swapped, before a single
     * photo moves. Notes are text: they are complete the moment the manifest is, and holding
     * them hostage to a video transfer that may never finish is the one thing that would make
     * them *less* reliable than the bytes. Landed again in the return value — [unionMedia] is
     * keyed by id, so arriving twice is arriving once. */
    landNotes: (Map<String, List<StoredMedia>>) -> Unit = {},
): Map<String, List<StoredMedia>>? {
    mutualContactAuth(socket, isServer, ownCert, privateKey, candidates) ?: return null

    val theirManifest = exchangeManifests(socket, isServer, myManifest) ?: return null
    val plan = contactReconcilePlan(mine, theirManifest, verified = true, gallery = gallery)

    // Before the request round, not after it: everything a **Note** needs has already
    // arrived, and this is the earliest moment it can be written down.
    if (plan.noBytes.isNotEmpty()) {
        val notes = contactLanding(mine, theirManifest, plan.noBytes.associateWith { "" })
        if (notes.isNotEmpty()) landNotes(notes)
    }

    val theirRequest = exchangeRequests(socket, isServer, plan.request)

    val theirKinds = theirManifest.media.associate { it.id to it.kind }
    val resolved = LinkedHashMap<String, String>(plan.fromGallery)
    for (id in plan.noBytes) resolved[id] = ""
    val expected = plan.request.toSet()
    if (isServer) {
        sendRequested(socket, theirRequest, mediaSource)
        resolved += receiveRequested(socket, expected, receivedFile, refForReceivedFile, theirKinds)
    } else {
        resolved += receiveRequested(socket, expected, receivedFile, refForReceivedFile, theirKinds)
        sendRequested(socket, theirRequest, mediaSource)
    }

    return contactLanding(mine, theirManifest, resolved)
}

/**
 * `encodeDefaults` so every field is written even at its default — Swift's `JSONEncoder`
 * writes every non-nil property already, so this is what makes the two agree.
 *
 * `ignoreUnknownKeys` is the interop half (#267), and it is not cosmetic: kotlinx's
 * default is to *throw* on a key it does not know, which here would surface as a failed
 * decode, a null manifest, and a reconcile that silently does nothing. iOS's decoder
 * ignores unknown keys already, so without this the two platforms disagree about what a
 * newer field means the first time either one adds one.
 *
 * The models line up today — every field iOS declares on `HandoverManifest`,
 * `TimelineCache`, `OfferedMedia`, `StoredMedia` and `StoredGig` exists here, and this
 * side's extras (`identities`, `festivals`, …) all carry defaults. This is so
 * that staying lined up is not a condition of working at all.
 */
private val manifestJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

private fun exchangeManifests(socket: Socket, isServer: Boolean, mine: HandoverManifest): HandoverManifest? {
    fun write() = writeFrame(socket.getOutputStream(), manifestJson.encodeToString(mine).toByteArray(Charsets.UTF_8))
    fun read(): HandoverManifest? {
        val frame = readFrame(socket.getInputStream()) ?: return null
        return runCatching { manifestJson.decodeFromString<HandoverManifest>(frame.toString(Charsets.UTF_8)) }.getOrNull()
    }
    return if (isServer) { write(); read() } else { val theirs = read(); write(); theirs }
}

private fun exchangeRequests(socket: Socket, isServer: Boolean, mine: List<String>): List<String> {
    fun write() = writeFrame(socket.getOutputStream(), manifestJson.encodeToString(mine).toByteArray(Charsets.UTF_8))
    fun read(): List<String> {
        val frame = readFrame(socket.getInputStream()) ?: return emptyList()
        return runCatching { manifestJson.decodeFromString<List<String>>(frame.toString(Charsets.UTF_8)) }.getOrDefault(emptyList())
    }
    return if (isServer) { write(); read() } else { val theirs = read(); write(); theirs }
}

private fun sendRequested(socket: Socket, ids: List<String>, mediaSource: (String) -> Pair<Long, InputStream>?) {
    for (id in ids) {
        val (length, stream) = mediaSource(id) ?: continue
        stream.use { writeItem(socket, id, length, it) }
    }
    writeEndOfItems(socket)
}

/**
 * The largest single item worth accepting. Generous against a long video, finite against a
 * peer that declares a body no disk can hold — an unchecked length here is a device filled
 * up by someone else's arithmetic. iOS's `maxItemBytes` is the same figure.
 */
private const val MAX_ITEM_BYTES = 4L shl 30

/** Where a drained body goes. `OutputStream.nullOutputStream()` is Java 11 and needs
 * desugaring to be safe across this app's minSdk; two overrides are cheaper than finding
 * out. */
private val DISCARD = object : OutputStream() {
    override fun write(b: Int) = Unit
    override fun write(b: ByteArray, off: Int, len: Int) = Unit
}

/**
 * Media id → the local ref its received bytes now live at. Always drains the socket up to
 * the end-of-items marker [sendRequested] writes even when nothing was actually asked
 * for — the marker is unconditional on the sending side, so skipping the read here would
 * leave it sitting unread on a socket meant for more frames afterwards.
 *
 * [expected] is what I actually asked for. A header for anything else is drained and
 * dropped: a sender is free to put whatever it likes on the wire, and "you offered it and
 * I declined" must not become "you sent it anyway and I stored it".
 *
 * `internal` rather than private only so a hostile sender can be pointed at it directly —
 * [runContactSession] on the far end obeys the request list by construction, so the one
 * thing worth testing here is not reachable through it.
 */
internal fun receiveRequested(
    socket: Socket,
    expected: Set<String>,
    receivedFile: (String, String) -> File,
    refFor: (File) -> String,
    kinds: Map<String, String>,
    /** Each chunk as it arrives — a progress bar's numbers (#142 story 14). */
    onBytes: (Int) -> Unit = {},
    /**
     * Each item the moment its last declared byte has arrived, id and local ref.
     *
     * The return value is the same information, and for a session that runs to the end it
     * is the easier one to use. This exists for the session that does *not*: a cancelled or
     * dropped transfer leaves through an exception, taking the return value with it, and
     * what already landed is exactly what a coherent smaller library is made of (#142
     * stories 13 and 16).
     */
    onItem: (id: String, ref: String) -> Unit = { _, _ -> },
): Map<String, String> {
    val landed = LinkedHashMap<String, String>()
    while (true) {
        val (id, length) = readItemHeader(socket) ?: break
        // A length outside these bounds is not a bad item, it is a stream that cannot be
        // walked: a negative one makes the drain below a no-op and leaves the body sitting
        // where the next header should be, desyncing every frame after it.
        require(length in 0..MAX_ITEM_BYTES) { "item of $length bytes refused" }
        if (!isSafeMediaId(id) || id !in expected) {
            // The bytes are coming whether or not they are wanted, so they are drained
            // rather than abandoned mid-item.
            copyExactly(socket.getInputStream(), DISCARD, length, onBytes = onBytes)
            continue
        }
        val file = receivedFile(id, kinds[id] ?: StoredMedia.Kind.PHOTO)
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { copyExactly(socket.getInputStream(), it, length, onBytes = onBytes) }
        val ref = refFor(file)
        landed[id] = ref
        onItem(id, ref)
    }
    return landed
}
