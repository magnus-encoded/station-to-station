package io.github.magnusencoded.stationtostation.data.gossip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import io.github.magnusencoded.stationtostation.MainActivity
import io.github.magnusencoded.stationtostation.R
import io.github.magnusencoded.stationtostation.data.SettingsRepository
import io.github.magnusencoded.stationtostation.data.TimelineStore
import io.github.magnusencoded.stationtostation.ble.GossipCentral
import io.github.magnusencoded.stationtostation.ble.GossipPeripheral
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The gossip channel's radio, running where ADR-0019 says it may: in the background, behind a
 * notification the user can see and stop (#416).
 *
 * **What it does not do.** Not one acceptance decision is made here. Everything that arrives
 * goes to [PublicGossipState.receive] inside a [GossipStore.updatePublic] transaction, which
 * is the one place an envelope is judged — its own signature, its expiry, whether it has been
 * seen, and the rule that a one-hop `request` or `receipt` is believed only from its own
 * author. This class moves bytes, keeps a clock, and writes the answer down. When you are
 * tempted to add an `if` about an envelope to this file, the `if` belongs in `receive`.
 *
 * That division was the v1 storm-gate's argument and it did not change when the transport
 * did: a rule that grows a second copy inside a BLE service is a rule that will disagree
 * with itself.
 *
 * **Why a service and not a worker.** WorkManager schedules against Doze, and a check-in is
 * only worth relaying to somebody standing in the same room *now*. A radio that wakes up
 * fifteen minutes later is a radio in a different room. That is the trade ADR-0019 accepted
 * on Android's side, and the notification is the price it named.
 *
 * **Its state is not a source of truth.** [GossipStore] is, and every fold through it is one
 * atomic read-edit-write — which is also why nothing here serialises the folds by hand:
 * [PublicGossipState] is read, edited and written inside a single `updatePublic`, so two
 * overlapping deliveries cannot interleave a read with each other's write. The service holds
 * only what would be pointless to persist: the last time it spoke to each peer (see
 * [GOSSIP_PEER_COOLDOWN]) and a snapshot of the **Contacts** it refreshes as it goes.
 */
class GossipService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var settings: SettingsRepository
    private lateinit var timeline: TimelineStore
    private lateinit var store: GossipStore
    private var publicState = PublicGossipState()
    private val publicLock = Any()
    private val publicPending = mutableMapOf<String, List<String>>()

    /** How many of [publicPending]'s envelopes were receipts, for the tally alone. */
    private val publicPendingReceipts = mutableMapOf<String, Int>()

    @Volatile private var participationEnds: Map<String, Long> = emptyMap()
    private val activeUntil: Instant?
        get() = participationEnds.values.maxOrNull()?.takeIf { it > 0 }?.let(Instant::ofEpochMilli)
    private var starting = false
    private var peripheral: GossipPeripheral? = null
    private var central: GossipCentral? = null

    /** Refreshed on every fold, so a **Contact** made tonight is gossiped with tonight. */
    @Volatile private var contacts: Set<String> = emptySet()

    /**
     * Contact key to the name to show for them, refreshed alongside [contacts].
     *
     * The radio deals in keys, because a key is what a signature proves; a name is what the
     * **Exchange** wrote down next to it. The notification is the one place the two meet.
     */
    @Volatile private var names: Map<String, String> = emptyMap()

    private val spokenAt = mutableMapOf<String, Instant>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(applicationContext)
        timeline = TimelineStore(applicationContext)
        store = GossipStore(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch {
                gossipStop(timeline, store, System.currentTimeMillis())
                withContext(Dispatchers.Main) { stopSelf() }
            }
            return START_NOT_STICKY
        }
        // Before anything else, and on every delivery of the intent: Android kills a service
        // that has not called this within five seconds, and an already-started service being
        // asked to start again is normal.
        startForegroundNotification(publicCount())
        if (!starting) {
            starting = true
            scope.launch {
                participationEnds = gossipParticipationEnds(timeline, store.stoppedAt())
                withContext(Dispatchers.Main) {
                    starting = false
                    if (!gossipRelayShouldRun(activeUntil, Instant.now())) stopSelf()
                    else if (peripheral == null) startRadio()
                }
            }
        }
        // NOT_STICKY, matching "deliberately not a boot receiver" in GossipPolicy: a service
        // the system killed for resources should not silently reappear. Opening the app is
        // what brings it back.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        peripheral?.stop()
        central?.stop()
        peripheral = null
        central = null
        GossipPresence.forget()
        // The end of the night, which is the only moment the tally is worth reading — and the
        // note goes on *after* `radioStopped`, which clears every field but the gate. The tally
        // object itself is not reset, so a second look still has it.
        Log.i(TAG, GossipTally.summary())
        GossipRadioStatus.radioStopped()
        GossipRadioStatus.note(GossipTally.summary())
        scope.cancel()
        super.onDestroy()
    }

    private fun startRadio() {
        fun relayIdentity(): GigIdentity = GigIdentity("relay-" +
            java.time.ZonedDateTime.now().minusHours(6).toLocalDate().toString())
        scope.launch {
            settings.friends.collect { friends ->
                contacts = contactKeysOf(friends)
                names = friends.mapNotNull { friend -> friend.publicKey?.let { it to friend.name } }.toMap()
                store.updatePublic(System.currentTimeMillis()) { it.recognizeContacts(contacts) }
            }
        }
        scope.launch {
            store.publicStates.collect { state ->
                synchronized(publicLock) { publicState = state }
                startForegroundNotification(publicCount())
            }
        }

        peripheral = GossipPeripheral(
            context = applicationContext,
            myKey = { relayIdentity().publicKey() },
            sign = { relayIdentity().sign(it) },
        ).also {
            it.onPublicDelivery = { delivery ->
                scope.launch {
                    val now = System.currentTimeMillis()
                    if (!gossipRelayShouldRun(activeUntil, Instant.ofEpochMilli(now))) return@launch
                    var accepted = 0
                    store.updatePublic(now) { state ->
                        val directRequests = delivery.pass.batch.filter { envelope ->
                            envelope.kind == "request" && state.receive(envelope, delivery.from, now)
                        }
                        accepted += directRequests.size
                        val admitted = mutableListOf<GossipEnvelope>()
                        delivery.pass.batch.filter { it.kind != "request" }.forEach { envelope ->
                            if (state.receive(envelope, delivery.from, now)) {
                                accepted++
                                admitted.add(envelope)
                            }
                        }
                        state.recognizeContacts(contacts)
                        // Receipts are authored here and nowhere else, which is what keeps
                        // story 37 structural: recognition that arrives later, from an
                        // Exchange, runs through the `settings.friends` collector above and
                        // has no way back into this batch.
                        val relay = relayIdentity()
                        // A receipt is addressed, so it may only name a key this device can
                        // meet again: the sender's relay key, which a Pass carrying the
                        // sender's own request does not prove. See `passRelay`. And one
                        // receipt per Gig record, not per Fact: two lines of one log author
                        // the same receipt twice, and the second would retire the first.
                        val addressable = passRelay(delivery.pass)
                        if (addressable == null) GossipTally.declined()
                        else {
                            val receipts = receiptsFor(directRequests + admitted, addressable,
                                { state.recognition[it.author] != null }, relay.publicKey(), now, relay::sign)
                            receipts.forEach { state.receive(it, "", now, local = true) }
                            GossipTally.authored(receipts.size)
                        }
                        // Witnessing is decided in `witnessFor`, not here: this phone signs
                        // for a stranger only when it checked into the same Gig itself, and
                        // that rule has to be reachable by a test rather than only by a BLE
                        // callback. The signer is the local claim's own Gig key.
                        directRequests.forEach { request ->
                            witnessFor(state, request, now) { claim -> GigIdentity(claim.scope)::sign }
                                ?.let { witness -> state.receive(witness, "", now, local = true) }
                        }
                    }
                    Log.i(TAG, "accepted $accepted of ${delivery.pass.batch.size} public envelopes")
                }
            }
            it.start()
        }

        central = GossipCentral(
            context = applicationContext,
            publicPassFor = { peer, nonce -> synchronized(publicLock) {
                if (!gossipRelayShouldRun(activeUntil, Instant.now())) return@synchronized null
                val offered = publicState.offer(peer, System.currentTimeMillis(), participationEnds)
                val request = passAuthor(offered, publicState.localAuthors)
                val identity = request?.let { GigIdentity(it.scope) } ?: relayIdentity()
                val batch = passBatch(offered, request, identity.publicKey())
                val proof = runCatching { identity.sign(publicGossipAuthPayload(nonce)) }.getOrNull()
                if (batch.isEmpty() || proof == null) null
                else encodePublicGossipPass(PublicGossipPass(identity.publicKey(), gossipBase64(proof), batch))?.also { bytes ->
                    val encoded = decodePublicGossipPass(bytes)?.batch.orEmpty()
                    publicPending[peer] = encoded.map { it.id }
                    // Counted off the encoded batch rather than `batch`, so what is tallied as
                    // offered is what actually fitted on the wire.
                    publicPendingReceipts[peer] = encoded.count { it.kind == "receipt" }
                    GossipTally.offered(publicPendingReceipts[peer] ?: 0)
                }
            } },
            due = { peer -> synchronized(spokenAt) { gossipPassDue(spokenAt[peer], Instant.now()) } },
            credited = { peer -> synchronized(publicLock) { publicState.useful[peer]?.let { it > System.currentTimeMillis() } == true } },
            onPushed = { peer ->
                val now = Instant.now()
                synchronized(spokenAt) { spokenAt[peer] = now }
                // Both maps are taken under the one lock, in one acquisition: the tally is a
                // diagnostic and must not be a reason to take `publicLock` a second time.
                val ids = synchronized(publicLock) {
                    GossipTally.delivered(publicPendingReceipts.remove(peer) ?: 0)
                    publicPending.remove(peer).orEmpty()
                }
                scope.launch {
                    store.updatePublic(now.toEpochMilli()) { it.delivered(peer, ids) }
                }
            },
        ).also { it.start() }

        GossipRadioStatus.radioStarted()

        // Presence lapses in silence — nobody announces leaving — so the notification has to
        // be rebuilt on a clock as well as on events, or a **Contact** who walked off would
        // be named in the shade until the next thing happened, which may be never.
        scope.launch {
            while (isActive) {
                val remaining = activeUntil?.toEpochMilli()?.minus(System.currentTimeMillis()) ?: 0
                delay(remaining.coerceIn(1, 30_000))
                participationEnds = gossipParticipationEnds(timeline, store.stoppedAt())
                if (!gossipRelayShouldRun(activeUntil, Instant.now())) {
                    withContext(Dispatchers.Main) { stopSelf() }
                    return@launch
                }
                store.updatePublic(System.currentTimeMillis()) { }
                withContext(Dispatchers.Main) { startForegroundNotification(publicCount()) }
            }
        }
    }

    /**
     * Re-read who this device has met.
     *
     * Only the names and the count survive the move to public gossip v2: an envelope is no
     * longer addressed to a **Contact**, so there is no audience to recompute — but the
     * notification still names the **Contacts** who have been heard from, and
     * [gossipRelayShouldRun] still asks how many exist.
     */
    private suspend fun refresh() {
        val friends = settings.friends.first()
        contacts = contactKeysOf(friends)
        names = friends.mapNotNull { friend -> friend.publicKey?.let { it to friend.name } }.toMap()
    }

    /**
     * The one line the notification gets.
     *
     * Who is here beats what is being carried, because it is the thing the owner of this
     * phone can act on — the carried count is bookkeeping, and it is still what shows when
     * the room is empty. A **Contact** with no name to show is "someone" rather than being
     * left out: dropping them would report an empty room while a radio was plainly busy.
     */
    private fun publicCount(): Int = synchronized(publicLock) { publicState.held.size }

    private fun presenceText(carrying: Int): String {
        val here = gossipNearby(GossipPresence.metAt.value, Instant.now())
            .map { names[it] ?: getString(R.string.gossip_notification_someone) }
        return when {
            here.isEmpty() && carrying > 0 ->
                getString(R.string.gossip_notification_carrying, carrying)
            here.isEmpty() -> getString(R.string.gossip_notification_idle)
            here.size == 1 -> getString(R.string.gossip_notification_here_one, here[0])
            here.size == 2 -> getString(R.string.gossip_notification_here_two, here[0], here[1])
            else -> getString(R.string.gossip_notification_here_many, here[0], here.size - 1)
        }
    }

    private fun startForegroundNotification(carrying: Int) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                getString(R.string.gossip_channel_name),
                // LOW: visible in the shade, never a sound or a heads-up. This notification
                // exists to be *auditable*, not to be read — the news itself surfaces on the
                // timeline where the rest of a night's facts live.
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.gossip_channel_description) },
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, GossipService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(getString(R.string.gossip_notification_title))
            .setContentText(presenceText(carrying))
            // The content names people this phone has been near. That belongs behind the
            // lock screen: the shade is the audit trail ADR-0019 promised its owner, not a
            // list of who somebody is with, readable off a table by anyone walking past.
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(null, getString(R.string.gossip_notification_stop), stop)
                    .build(),
            )
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "GossipService"
        private const val CHANNEL = "gossip"
        private const val NOTIFICATION_ID = 4160
        /** Internal rather than private so a test can name what the notification's action sends. */
        internal const val ACTION_STOP = "io.github.magnusencoded.stationtostation.GOSSIP_STOP"

        /**
         * Bring the service into line with [gossipRelayShouldRun] — start it, stop it, or
         * leave it alone.
         *
         * Called from wherever one of the answer's inputs can have changed: a check-in, a new
         * **Contact**, the setting being toggled, the app being opened. Cheap enough to call
         * on every one of those, because the decision is made from state that is already
         * loaded and the system ignores a start for a service that is running.
         */
        fun sync(context: Context, activeUntil: Instant?) {
            val intent = Intent(context, GossipService::class.java)
            val run = gossipRelayShouldRun(activeUntil, Instant.now())
            GossipRadioStatus.gate("activeUntil=$activeUntil")
            if (run) {
                runCatching { context.startForegroundService(intent) }
                    .onFailure { Log.w(TAG, "could not start the gossip service: $it") }
            } else {
                runCatching { context.stopService(intent) }
            }
        }
    }
}

/** dd-MM-yyyy, the shape [TimelineStore] keeps a **Gig**'s date in. */
private val GIG_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy")

/**
 * Every **Gig** on this timeline that has a date, by the id a gossip envelope would name it by.
 *
 * [TimelineCache.keyOf][io.github.magnusencoded.stationtostation.data.TimelineCache.keyOf],
 * because that is the id the rest of the app shares with other people — a setlist.fm id where
 * the night has one. A local id would never match what a **Contact** sent.
 */
suspend fun gigDatesOf(timeline: TimelineStore): Map<String, LocalDate> {
    val cache = timeline.load()
    return cache.gigs.values.mapNotNull { gig ->
        val date = runCatching { LocalDate.parse(gig.date, GIG_DATE) }.getOrNull() ?: return@mapNotNull null
        cache.keyOf(gig.id) to date
    }.toMap()
}

/**
 * The whole of what the notification's stop action does (#448, story 30).
 *
 * A function rather than three lines inside `onStartCommand` because stop is the one
 * lifecycle transition with no other way in: every other end — grace running out, the night
 * boundary, no **Gig** left active — is a clock the tests can move, while this one arrives as
 * a `PendingIntent` on a `Service` that this project deliberately does not stand up under
 * Robolectric. Here it is an ordinary suspend call over the two stores, and what it returns is
 * the thing a caller acts on: the participation deadline afterwards, which is `null`.
 *
 * It writes the moment rather than a flag, because
 * [gossipParticipationUntil] compares it against the check-in it would have to outlive:
 * stopping tonight ends tonight, and checking in again tomorrow is not affected by it. By the
 * same comparison, reopening a **Log** after a stop does *not* resume — a stop the next edit
 * undid would not be the off switch the story promises.
 *
 * It does not touch the received **Facts** (story 32). Nothing in [GossipStore.stopParticipation]
 * can: the deadline and the record are different keys, and only the radio's reason to run ends.
 */
suspend fun gossipStop(timeline: TimelineStore, store: GossipStore, now: Long): Instant? {
    store.stopParticipation(now)
    return gossipActiveUntil(timeline, store.stoppedAt())
}

/** Read persisted attendance and completion so shutdown works with no Activity alive. */
suspend fun gossipActiveUntil(timeline: TimelineStore, stoppedAt: Long = 0): Instant? =
    gossipParticipationEnds(timeline, stoppedAt).values.maxOrNull()?.takeIf { it > 0 }?.let(Instant::ofEpochMilli)

/** Known Gig ids retain a deadline even after participation ends. Unknown nights can
 * still be carried blindly while another checked-in Gig keeps the radio running. */
suspend fun gossipParticipationEnds(timeline: TimelineStore, stoppedAt: Long = 0): Map<String, Long> {
    val cache = timeline.load()
    val attendance = cache.attendance()
    val logs = cache.logs()
    return buildMap {
        cache.gigs.values.forEach { gig ->
            val id = cache.keyOf(gig.id)
            val date = runCatching { LocalDate.parse(gig.date, GIG_DATE) }.getOrNull()
            val log = logs[id] ?: io.github.magnusencoded.stationtostation.data.StoredLog()
            val until = date?.let {
                gossipParticipationUntil(attendance[id]?.checkedInAt, log.closed, log.completedAt, gossipExpiry(it), stoppedAt)
            }?.toEpochMilli() ?: 0L
            put(gig.id, until)
            put(id, until)
        }
    }
}
