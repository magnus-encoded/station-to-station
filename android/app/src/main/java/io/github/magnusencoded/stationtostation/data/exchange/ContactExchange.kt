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
import java.net.InetSocketAddress
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

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

    fun start() {
        if (running) return
        running = true
        val sessionContext = contactSessionContext(contactIdentityKeyStore(), CharArray(0))
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
    }

    private suspend fun acceptLoop(socket: SSLServerSocket) {
        while (running) {
            val accepted = runCatching {
                (socket.accept() as SSLSocket).apply { wantClientAuth = true }
            }.getOrNull() ?: break
            scope.launch(Dispatchers.IO) { runSession(accepted, isServer = true) }
        }
    }

    private suspend fun connectTo(address: InetSocketAddress, sessionContext: SSLContext) {
        val socket = runCatching {
            sessionContext.socketFactory.createSocket(address.address, address.port) as SSLSocket
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
                myManifest = manifest(),
                mine = cache,
                gallery = gallery(),
                mediaSource = { id -> refById[id]?.let { photos.mediaSource(it) } },
                receivedFile = { id, kind -> photos.receivedMediaFile(id, kind) },
                refForReceivedFile = photos::fileProviderRef,
            )
            if (!landing.isNullOrEmpty()) onLanded(landing)
        }
        runCatching { socket.close() }
    }
}
