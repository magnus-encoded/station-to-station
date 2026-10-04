package io.github.magnusencoded.stationtostation.features.handover

import android.app.Application
import android.net.Uri
import io.github.magnusencoded.stationtostation.HandoverRole
import io.github.magnusencoded.stationtostation.HandoverUi
import io.github.magnusencoded.stationtostation.UiState
import io.github.magnusencoded.stationtostation.data.AccountsMove
import io.github.magnusencoded.stationtostation.data.AccountsPayload
import io.github.magnusencoded.stationtostation.data.CATEGORY_ACCOUNTS
import io.github.magnusencoded.stationtostation.data.Credentials
import io.github.magnusencoded.stationtostation.data.GalleryItem
import io.github.magnusencoded.stationtostation.data.HandoverManifest
import io.github.magnusencoded.stationtostation.data.Identities
import io.github.magnusencoded.stationtostation.data.SettingsRepository
import io.github.magnusencoded.stationtostation.data.TimelineCache
import io.github.magnusencoded.stationtostation.data.TimelineStore
import io.github.magnusencoded.stationtostation.data.deviceManifest
import io.github.magnusencoded.stationtostation.data.exchange.HandoverInvite
import io.github.magnusencoded.stationtostation.data.exchange.HandoverPhase
import io.github.magnusencoded.stationtostation.data.exchange.certFingerprint
import io.github.magnusencoded.stationtostation.data.exchange.forgetHandoverIdentity
import io.github.magnusencoded.stationtostation.data.exchange.generateHandoverIdentity
import io.github.magnusencoded.stationtostation.data.exchange.handoverAlias
import io.github.magnusencoded.stationtostation.data.exchange.localLinkAddress
import io.github.magnusencoded.stationtostation.data.exchange.parseHandoverInvite
import io.github.magnusencoded.stationtostation.data.exchange.readAccountsAck
import io.github.magnusencoded.stationtostation.data.exchange.readAccountsStep
import io.github.magnusencoded.stationtostation.data.exchange.runHandoverReceiver
import io.github.magnusencoded.stationtostation.data.exchange.runHandoverSource
import io.github.magnusencoded.stationtostation.data.exchange.sslClientContext
import io.github.magnusencoded.stationtostation.data.exchange.sslServerContext
import io.github.magnusencoded.stationtostation.data.exchange.toUri
import io.github.magnusencoded.stationtostation.data.exchange.writeAccountsAck
import io.github.magnusencoded.stationtostation.data.exchange.writeAccountsStep
import io.github.magnusencoded.stationtostation.data.identitiesOnly
import io.github.magnusencoded.stationtostation.data.mayClearCredentials
import io.github.magnusencoded.stationtostation.data.parseFmDate
import io.github.magnusencoded.stationtostation.data.photos.PhotoRepository
import io.github.magnusencoded.stationtostation.data.spotify.SpotifyClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.net.Socket
import java.security.SecureRandom
import java.util.UUID

/** Moving to a new phone: both ends of a device handover, their sockets and lifecycle. */
class HandoverController(
    private val state: () -> UiState,
    private val update: ((UiState) -> UiState) -> Unit,
    private val application: Application,
    private val settings: SettingsRepository,
    private val spotify: SpotifyClient,
    private val photos: PhotoRepository,
    private val timelines: TimelineStore,
    private val scope: CoroutineScope,
    private val restoreTimelines: suspend () -> Unit,
) {

    // Both accounts functions take a socket whose TLS handshake and link-key challenge
    // already passed. Not unit-testable here (real sockets); the sequencing is covered by
    // `HandoverWireTest` and the clear-gating by `AccountsTest`.

    /**
     * Receiving device's half. Stores whatever arrives — durably, via [settings] —
     * *before* acking, because the source's clear is gated on the ack having meant
     * something real. Returns null only if the connection dropped before any accounts
     * frame arrived; a genuinely declined row still arrives as identities-only, not as
     * null.
     */
    suspend fun receiveHandoverAccounts(socket: Socket): AccountsPayload? =
        withContext(Dispatchers.IO) {
            val payload = readAccountsStep(socket) ?: return@withContext null

            payload.identities.setlistFmUser?.let { settings.saveMySetlistFmUser(it) }
            val token = payload.credentials.spotifyRefreshToken
            if (!token.isNullOrBlank()) {
                settings.saveHandoverCredentials(token, payload.credentials.spotifyScope)
            }
            writeAccountsAck(socket)

            update {
                it.copy(
                    mySetlistFmUser = payload.identities.setlistFmUser ?: it.mySetlistFmUser,
                    spotifyConnected = it.spotifyConnected || !token.isNullOrBlank(),
                    grantedScope = payload.credentials.spotifyScope ?: it.grantedScope,
                )
            }
            payload
        }

    /**
     * Sending device's half — the phone being replaced. Sends [payload], then signs out
     * *here* only if the receiver's ack genuinely arrives ([mayClearCredentials]): a
     * dropped connection after the send must never clear a credential that may exist
     * nowhere else. This is the one call site of a handover-triggered
     * [SettingsRepository.clearSpotifyAuth] — manual sign-out (`AppViewModel.disconnectSpotify`)
     * does not go through it.
     */
    suspend fun sendHandoverAccounts(socket: Socket, payload: AccountsPayload): AccountsMove =
        withContext(Dispatchers.IO) {
            writeAccountsStep(socket, payload)
            val step = if (readAccountsAck(socket)) AccountsMove.ACKNOWLEDGED else AccountsMove.SENT
            // The payload, not the step, decides whether there is anything to let go of:
            // an identities-only frame (accounts row unticked) is acked like a full
            // one, and signing out on that ack would move an account nobody asked to move.
            if (mayClearCredentials(step) && !payload.credentials.spotifyRefreshToken.isNullOrBlank()) {
                settings.clearSpotifyAuth()
                update { it.copy(spotifyConnected = false, grantedScope = null) }
            }
            step
        }

    // Each end of a transfer is one job holding one socket. Decidable parts live in
    // `HandoverSession.kt`; this is the device half: keystore certificate, TLS socket,
    // real gallery and store.

    private var handoverJob: Job? = null

    /** Closed by [cancelHandover]. A blocking socket read cannot be interrupted by a flag,
     * so cancelling is closing the socket out from under it — see `HandoverSession`. */
    @Volatile private var handoverCloseables: List<Closeable> = emptyList()

    private fun handoverUi(change: (HandoverUi) -> HandoverUi) =
        update { it.copy(handover = change(it.handover)) }

    /**
     * The old phone. Generates a session identity, listens, and puts the invite on screen
     * as a QR: where to connect, the fingerprint to pin, and the link key that proves the
     * other phone read *this* screen. Serves exactly one joining device and then stops.
     *
     * [allow] is the tick list, applied to the manifest at construction — see
     * [deviceManifest]. Nothing outside it reaches the wire.
     */
    fun offerHandover(allow: Set<String>) {
        // Not just a cancel: the previous session may be parked on a blocking accept, and
        // overwriting `handoverCloseables` below would drop the only reference to it.
        endHandover()
        handoverUi { HandoverUi(role = HandoverRole.SOURCE) }
        val sessionId = UUID.randomUUID().toString().take(8)
        handoverJob = scope.launch(Dispatchers.IO) {
            try {
                // Asked before anything is bound: there is nothing to put in the QR
                // without it, and a socket opened first would be one the throw below
                // leaves bound with nothing holding a reference to close it.
                val host = localLinkAddress(application)
                    ?: throw IllegalStateException("this phone is not on a network to hand over across")
                val (cert, keyStore) = generateHandoverIdentity(sessionId)
                val linkKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val server = sslServerContext(keyStore, CharArray(0), handoverAlias(sessionId))
                    .serverSocketFactory.createServerSocket(0)
                handoverCloseables = listOf(server)
                handoverUi {
                    it.copy(
                        inviteUri = HandoverInvite(host, server.localPort, certFingerprint(cert), linkKey).toUri(),
                    )
                }
                server.use { listening ->
                    val socket = listening.accept()
                    handoverCloseables = listOf(server, socket)
                    socket.use { live ->
                        val cache = timelines.load()
                        val manifest = hashedManifest(deviceManifest(cache, allow, myIdentities()), cache)
                        val payload = accountsPayload(allow)
                        val refById = cache.gigMedia.values.flatten().associate { it.id to it.ref }
                        val receipt = runHandoverSource(
                            socket = live,
                            linkKey = linkKey,
                            allow = allow,
                            manifest = manifest,
                            accounts = { s -> sendHandoverAccounts(s, payload) },
                            mediaSource = { id -> refById[id]?.let { photos.mediaSource(it) } },
                            onProgress = { p -> handoverUi { it.copy(progress = p) } },
                        )
                        handoverUi {
                            if (receipt == null) it.copy(error = "That phone could not prove it read this code.")
                            else it.copy(receipt = receipt)
                        }
                    }
                }
            } catch (e: Exception) {
                handoverUi { it.copy(error = it.error ?: handoverTrouble(e)) }
            } finally {
                handoverCloseables = emptyList()
                runCatching { forgetHandoverIdentity(sessionId) }
            }
        }
    }

    /**
     * The new phone, arriving from the QR's deep link. Connects, pinning the exact
     * certificate the code named, and takes whatever the old phone approved.
     *
     * A link that is not a handover invite is simply not one: null, no error, nothing
     * started — [MainActivity] hands every `station-to-station://handover` link here and
     * a malformed one is indistinguishable from a mistyped anything else.
     */
    fun joinHandover(uri: Uri) {
        val invite = parseHandoverInvite(uri.toString()) ?: return
        endHandover()
        handoverUi { HandoverUi(role = HandoverRole.RECEIVER) }
        handoverJob = scope.launch(Dispatchers.IO) {
            try {
                val socket = sslClientContext(invite.fingerprint).socketFactory
                    .createSocket(invite.host, invite.port)
                handoverCloseables = listOf(socket)
                socket.use { live ->
                    val cache = timelines.load()
                    val receipt = runHandoverReceiver(
                        socket = live,
                        linkKey = invite.linkKey,
                        accounts = { s -> receiveHandoverAccounts(s) },
                        mine = cache,
                        gallery = galleryForMatching(cache),
                        receivedFile = { id, kind -> photos.receivedMediaFile(id, kind) },
                        refForReceivedFile = photos::fileProviderRef,
                        apply = { replan -> timelines.applyHandover(replan) },
                        onProgress = { p -> handoverUi { it.copy(progress = p) } },
                    )
                    handoverUi {
                        if (receipt == null) it.copy(error = "That transfer did not verify, so nothing was written.")
                        else it.copy(receipt = receipt)
                    }
                }
                restoreTimelines()
            } catch (e: Exception) {
                handoverUi { it.copy(error = it.error ?: handoverTrouble(e)) }
            } finally {
                handoverCloseables = emptyList()
            }
        }
    }

    /**
     * The one way a handover session ends: **close, then cancel**.
     *
     * That order is the whole of it. A blocking `accept()` or socket read cannot be
     * interrupted by cancelling the job — the coroutine is parked in a native call — so a
     * cancel on its own leaves a bound port, an IO thread and an un-forgotten
     * `AndroidKeyStore` identity behind, and leaves a phone that already read the QR able
     * to complete the whole transfer against a screen nobody is looking at. Closing the
     * socket is what makes the parked read throw and the coroutine's own `finally` run.
     */
    private fun endHandover() {
        handoverCloseables.forEach { runCatching { it.close() } }
        handoverCloseables = emptyList()
        handoverJob?.cancel()
        handoverJob = null
    }

    /** Starting is not a commitment: what already arrived stays. */
    fun cancelHandover() {
        endHandover()
        handoverUi {
            if (it.receipt != null) it else it.copy(
                progress = it.progress.copy(phase = HandoverPhase.FAILED),
                error = "Stopped. Anything that had already arrived is on this phone.",
            )
        }
    }

    /**
     * Leaves the handover screen's state behind, once it has been read — and takes the
     * session with it, because this is also what a *back gesture* off the screen calls
     * (see `HandoverScreen`'s `DisposableEffect`). Leaving the screen with a listener
     * still up would mean a transfer completing behind it.
     */
    fun dismissHandover() {
        endHandover()
        handoverUi { HandoverUi() }
    }

    /** A dropped wifi, a refused certificate and a closed socket all read the same from
     * here, and naming the exception at the user is not information. */
    private fun handoverTrouble(e: Exception): String = when (e) {
        is CancellationException -> throw e
        else -> "The connection to the other phone failed."
    }

    /**
     * The bytes and content hash of everything a manifest offers, filled in from real
     * storage — the one part of building a manifest that has to read files, which is why
     * the pure builders ([deviceManifest], [contactManifest]) leave both at their defaults.
     *
     * Shared by both far ends on purpose: a Contact's offer and my own other phone's are
     * the same shape, and hashing them two different ways is how the same photograph ends
     * up looking like two different files.
     */
    internal suspend fun hashedManifest(bare: HandoverManifest, cache: TimelineCache): HandoverManifest =
        bare.copy(media = bare.media.map { item ->
            val ref = cache.gigMedia[item.gigId]?.firstOrNull { it.id == item.id }?.ref
            val uri = ref?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) } ?: return@map item
            item.copy(hash = photos.mediaHash(uri) ?: "", bytes = runCatching {
                application.contentResolver
                    .openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
            }.getOrDefault(0L))
        })

    /**
     * My own gallery, narrowed to the nights I have records of, hashed — the candidates an
     * incoming offer is matched against so that a photograph already on this phone is
     * never sent over the wire a second time.
     */
    internal suspend fun galleryForMatching(cache: TimelineCache): List<GalleryItem> =
        cache.gigs.values.mapNotNull { it.date.takeIf { d -> d.isNotBlank() } }
            .distinct()
            // No cap: photosFrom's default limit=20 is sized for cover-photo picking,
            // not for reconcile matching — truncating here would send bytes the peer
            // already has locally just because they fell past position 20.
            .flatMap { d -> parseFmDate(d)?.let { photos.photosFrom(it, limit = Int.MAX_VALUE) }.orEmpty() }
            .distinctBy { it.uri }
            .mapNotNull { p -> photos.mediaHash(p.uri)?.let { GalleryItem(ref = p.uri.toString(), hash = it) } }

    internal suspend fun myIdentities(): Identities {
        val user = runCatching { spotify.currentUser() }.getOrNull()
        return Identities(
            setlistFmUser = state().mySetlistFmUser.trim().ifBlank { null },
            spotifyAccount = user?.id,
        )
    }

    /** Credentials only when the row was ticked; identities travel either way. */
    private suspend fun accountsPayload(allow: Set<String>): AccountsPayload {
        val identities = myIdentities()
        if (CATEGORY_ACCOUNTS !in allow) return identitiesOnly(identities)
        return AccountsPayload(
            identities = identities,
            credentials = Credentials(
                spotifyRefreshToken = settings.refreshTokenValue(),
                spotifyScope = settings.grantedScope(),
            ),
        )
    }
}
