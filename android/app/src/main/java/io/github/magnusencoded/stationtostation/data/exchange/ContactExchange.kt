package io.github.magnusencoded.stationtostation.data.exchange

import android.content.Context
import android.util.Log
import io.github.magnusencoded.stationtostation.data.GalleryItem
import io.github.magnusencoded.stationtostation.data.HandoverManifest
import io.github.magnusencoded.stationtostation.data.MediaOffer
import io.github.magnusencoded.stationtostation.data.StoredMedia
import io.github.magnusencoded.stationtostation.data.TimelineCache
import io.github.magnusencoded.stationtostation.data.photos.PhotoRepository
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetSocketAddress
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** A stalled peer (open TCP, no bytes) must not tie up an IO thread forever. */
private const val SESSION_TIMEOUT_MS = 15_000

/** How often one peer is dialed per [ContactExchange.start] before it is left alone, and
 * how long between tries. A failed dial is usually the far end still coming up. */
private const val DIAL_ATTEMPTS = 3
private const val DIAL_RETRY_MS = 4_000L

private const val TAG = "ContactExchange"

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
 * keeps answering mDNS queries does not get reconciled with on every beacon. A dial that
 * fails before the session runs is tried again, up to [DIAL_ATTEMPTS].
 *
 * Every coroutine [start] launches is held in [jobs] and cancelled by [stop]. They used to
 * outlive it: each opening of the screen left its peer collector running with its own,
 * by-then-deleted session key, and whichever stale collector reached a new peer first
 * dialed it with a key the store no longer had (`Key permanently invalidated`).
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
    /** The **Lane** held for each Contact, by their key (#405). Re-read per session. */
    private val lanesByKey: suspend () -> Map<String, List<FmSetlist>> = { emptyMap() },
    /** A verified Contact's **Nights** that I did not hold yet, by their key (#405). */
    private val onNights: suspend (contactKey: String, nights: List<FmSetlist>) -> Unit = { _, _ -> },
    /** My own **Spine**, which decides which Nights an offer could be about (#405). */
    private val myNights: suspend () -> List<FmSetlist> = { emptyList() },
    /** Media for Nights I have not joined: offered, never filed (#405). */
    private val onOffers: suspend (Map<String, MediaOffer>) -> Unit = {},
) {
    private val peers = ContactPeers(context)
    private var server: SSLServerSocket? = null
    private val handled = mutableSetOf<InetSocketAddress>()
    private var running = false
    private val jobs = mutableListOf<Job>()
    private var sessionAlias: String? = null

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
        val (alias, _, keyStore) = generateContactSessionIdentity()
        sessionAlias = alias
        val sessionContext = contactSessionContext(keyStore, CharArray(0), alias)
        val socket = sessionContext.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        server = socket
        peers.startAdvertising(socket.localPort)
        peers.startDiscovery()
        Log.i(TAG, "started on port ${socket.localPort}")
        jobs += scope.launch(Dispatchers.IO) { acceptLoop(socket) }
        jobs += scope.launch(Dispatchers.IO) {
            peers.peers.collect { addresses ->
                for (address in addresses) {
                    if (handled.add(address)) {
                        Log.i(TAG, "peer found: $address")
                        jobs += scope.launch(Dispatchers.IO) { connectTo(address, sessionContext) }
                    }
                }
            }
        }
    }

    fun stop() {
        if (running) Log.i(TAG, "stopped")
        running = false
        jobs.forEach { it.cancel() }
        jobs.clear()
        peers.stopAdvertising()
        peers.stopDiscovery()
        runCatching { server?.close() }
        server = null
        handled.clear()
        sessionAlias?.let { alias -> runCatching { forgetContactSessionIdentity(alias) } }
        sessionAlias = null
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
            Log.i(TAG, "accepted ${accepted.inetAddress}")
            jobs += scope.launch(Dispatchers.IO) { runSession(accepted, isServer = true) }
        }
    }

    private suspend fun connectTo(address: InetSocketAddress, sessionContext: SSLContext) {
        for (attempt in 1..DIAL_ATTEMPTS) {
            if (!running) return
            val socket = runCatching {
                (sessionContext.socketFactory.createSocket(address.address, address.port) as SSLSocket)
                    .apply { soTimeout = SESSION_TIMEOUT_MS }
            }.onFailure { Log.w(TAG, "dial $address failed (try $attempt): $it") }.getOrNull()
            if (socket != null && runSession(socket, isServer = false)) return
            if (attempt < DIAL_ATTEMPTS) delay(DIAL_RETRY_MS)
        }
    }

    /** True once the handshake is through, whatever the session then lands: a peer that
     * got that far has had its turn, and dialing it again would only repeat the same plan. */
    private suspend fun runSession(socket: SSLSocket, isServer: Boolean): Boolean {
        val side = if (isServer) "server" else "client"
        var handshook = false
        runCatching {
            val candidates = contactKeys()
            val ownCert = socket.session.localCertificates?.firstOrNull()
            if (ownCert == null) {
                Log.w(TAG, "$side: handshake failed with ${socket.inetAddress}")
                return@runCatching
            }
            handshook = true
            if (candidates.isEmpty()) {
                Log.i(TAG, "$side: no contact keys to check against")
                return@runCatching
            }
            val cache = mine()
            val lanes = lanesByKey()
            val spine = myNights()
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
                heldLane = { key -> lanes[key].orEmpty() },
                // Launched for the notes' reason: text, complete the moment the manifest
                // is, and not to be held up behind a photograph.
                landNights = { key, nights ->
                    Log.i(TAG, "$side: ${nights.size} nights landing")
                    scope.launch { onNights(key, nights) }
                },
                myNights = spine,
                landOffers = { offers -> scope.launch { onOffers(offers) } },
            )
            if (landing == null) Log.w(TAG, "$side: session ended without a landing (not a contact, or dropped)")
            else Log.i(TAG, "$side: session done, ${landing.values.sumOf { it.size }} media landed")
            if (!landing.isNullOrEmpty()) onLanded(landing)
        }.onFailure { Log.w(TAG, "$side: session failed: $it") }
        runCatching { socket.close() }
        return handshook
    }
}
