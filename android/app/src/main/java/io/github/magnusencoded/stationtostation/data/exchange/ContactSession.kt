package io.github.magnusencoded.stationtostation.data.exchange

import io.github.magnusencoded.stationtostation.data.GalleryItem
import io.github.magnusencoded.stationtostation.data.HandoverManifest
import io.github.magnusencoded.stationtostation.data.StoredMedia
import io.github.magnusencoded.stationtostation.data.TimelineCache
import io.github.magnusencoded.stationtostation.data.contactLanding
import io.github.magnusencoded.stationtostation.data.contactReconcilePlan
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
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
): Map<String, List<StoredMedia>>? {
    mutualContactAuth(socket, isServer, ownCert, privateKey, candidates) ?: return null

    val theirManifest = exchangeManifests(socket, isServer, myManifest) ?: return null
    val plan = contactReconcilePlan(mine, theirManifest, verified = true, gallery = gallery)
    val theirRequest = exchangeRequests(socket, isServer, plan.request)

    val theirKinds = theirManifest.media.associate { it.id to it.kind }
    val resolved = LinkedHashMap<String, String>(plan.fromGallery)
    if (isServer) {
        sendRequested(socket, theirRequest, mediaSource)
        resolved += receiveRequested(socket, receivedFile, refForReceivedFile, theirKinds)
    } else {
        resolved += receiveRequested(socket, receivedFile, refForReceivedFile, theirKinds)
        sendRequested(socket, theirRequest, mediaSource)
    }

    return contactLanding(mine, theirManifest, resolved)
}

private val manifestJson = Json { encodeDefaults = true }

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
 * Media id → the local ref its received bytes now live at. Always drains the socket up to
 * the end-of-items marker [sendRequested] writes even when nothing was actually asked
 * for — the marker is unconditional on the sending side, so skipping the read here would
 * leave it sitting unread on a socket meant for more frames afterwards.
 */
private fun receiveRequested(
    socket: Socket,
    receivedFile: (String, String) -> File,
    refFor: (File) -> String,
    kinds: Map<String, String>,
): Map<String, String> {
    val landed = LinkedHashMap<String, String>()
    while (true) {
        val (id, length) = readItemHeader(socket) ?: break
        val file = receivedFile(id, kinds[id] ?: StoredMedia.Kind.PHOTO)
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { copyExactly(socket.getInputStream(), it, length) }
        landed[id] = refFor(file)
    }
    return landed
}
