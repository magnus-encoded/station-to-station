package io.github.magnusencoded.stationtostation.data.exchange

import android.content.Context
import io.github.magnusencoded.stationtostation.data.GalleryItem
import io.github.magnusencoded.stationtostation.data.HandoverManifest
import io.github.magnusencoded.stationtostation.data.StoredMedia
import io.github.magnusencoded.stationtostation.data.TimelineCache
import io.github.magnusencoded.stationtostation.data.photos.PhotoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetSocketAddress
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** A stalled peer (open TCP, no bytes) must not tie up an IO thread forever. */
private const val SESSION_TIMEOUT_MS = 15_000

/**
 * One device's whole participation in #257 while the app is in the foreground: advertise
 * and discover over the same WiFi via [ContactPeers], accept or open a TLS socket for
 * whoever answers, and run [runContactSession] over it.
 *
 * Foreground-scoped on purpose, not a background service: no new permissions, no
 * notification, no battery-use question to answer — matching how the in-person Exchange
 * ([ExchangeSession]) already only runs while its screen is open. [start]/[stop] are
 * meant to sit on the same lifecycle edge that already drives that session.
 *
 * Each discovered address is dialed at most once per [start] — [handled] — so a peer that
 * keeps answering mDNS queries does not get reconciled with on every beacon.
 */
class ContactExchange(
    private val context: Context,
    private val scope: CoroutineScope,
    private val photos: PhotoRepository,
    /** Every currently-known Contact's public key (#28) — the candidate list a peer's
     * signature is checked against. Re-read on every session, not cached at [start], so a
     * Contact added mid-session is reachable without a restart. */
    private val contactKeys: suspend () -> List<String>,
    private val manifest: suspend () -> HandoverManifest,
    private val mine: suspend () -> TimelineCache,
    private val gallery: suspend () -> List<GalleryItem>,
    private val onLanded: suspend (Map<String, List<StoredMedia>>) -> Unit,
) {
    private val peers = ContactPeers(context)
    private var server: SSLServerSocket? = null
    private val handled = mutableSetOf<InetSocketAddress>()
    private var running = false

    // Manifest/gallery hashing walks the whole library — computed once per start(), not
    // once per discovered peer, so several Contacts on the same WiFi don't each trigger
    // a full re-hash of every photo and video.
    private val cacheLock = Mutex()
    private var manifestCache: HandoverManifest? = null
    private var galleryCache: List<GalleryItem>? = null

    private suspend fun cachedManifest(): HandoverManifest = cacheLock.withLock {
        manifestCache ?: manifest().also { manifestCache = it }
    }

    private suspend fun cachedGallery(): List<GalleryItem> = cacheLock.withLock {
        galleryCache ?: gallery().also { galleryCache = it }
    }

    fun start() {
        if (running) return
        running = true
        // A per-session certificate, not the durable Contact identity: that key is
        // SHA-256-only and TLS cannot sign a handshake with it (see [selfSignedIdentity]).
        // Nothing about trust moves — [proveContactIdentity] below still signs with the
        // durable key, over *this* certificate's fingerprint.
        val (_, keyStore) = generateContactSessionIdentity()
        val sessionContext = contactSessionContext(keyStore, CharArray(0), CONTACT_SESSION_ALIAS)
        val socket = sessionContext.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        server = socket
        peers.startAdvertising(socket.localPort)
        peers.startDiscovery()
        scope.launch(Dispatchers.IO) { acceptLoop(socket) }
        scope.launch(Dispatchers.IO) {
            peers.peers.collect { addresses ->
                for (address in addresses) {
                    if (handled.add(address)) {
                        scope.launch(Dispatchers.IO) { connectTo(address, sessionContext) }
                    }
                }
            }
        }
    }

    fun stop() {
        running = false
        peers.stopAdvertising()
        peers.stopDiscovery()
        runCatching { server?.close() }
        server = null
        handled.clear()
        runCatching { forgetContactSessionIdentity() }
        manifestCache = null
        galleryCache = null
    }

    private suspend fun acceptLoop(socket: SSLServerSocket) {
        while (running) {
            val accepted = runCatching {
                (socket.accept() as SSLSocket).apply {
                    wantClientAuth = true
                    soTimeout = SESSION_TIMEOUT_MS
                }
            }.getOrNull() ?: break
            scope.launch(Dispatchers.IO) { runSession(accepted, isServer = true) }
        }
    }

    private suspend fun connectTo(address: InetSocketAddress, sessionContext: SSLContext) {
        val socket = runCatching {
            (sessionContext.socketFactory.createSocket(address.address, address.port) as SSLSocket)
                .apply { soTimeout = SESSION_TIMEOUT_MS }
        }.getOrNull() ?: return
        runSession(socket, isServer = false)
    }

    private suspend fun runSession(socket: SSLSocket, isServer: Boolean) {
        runCatching {
            val candidates = contactKeys()
            val ownCert = socket.session.localCertificates?.firstOrNull()
            if (candidates.isEmpty() || ownCert == null) return@runCatching
            val cache = mine()
            val refById = cache.gigMedia.values.flatten().associate { it.id to it.ref }
            val landing = runContactSession(
                socket = socket,
                isServer = isServer,
                ownCert = ownCert,
                privateKey = contactIdentityPrivateKey(),
                candidates = candidates,
                myManifest = cachedManifest(),
                mine = cache,
                gallery = cachedGallery(),
                mediaSource = { id -> refById[id]?.let { photos.mediaSource(it) } },
                receivedFile = { id, kind -> photos.receivedMediaFile(id, kind) },
                refForReceivedFile = photos::fileProviderRef,
                // Straight to the timeline: a **Note** has no bytes to fetch and no
                // thumbnail to cut, so there is nothing between arriving and landing.
                // Launched rather than awaited so the transfer is not held up by a disk
                // write, and safe to race the landing below because [writeMerged] is
                // serialized and [unionMedia] is keyed by id.
                landNotes = { notes -> scope.launch { onLanded(notes) } },
            )
            if (!landing.isNullOrEmpty()) onLanded(landing)
        }
        runCatching { socket.close() }
    }
}
