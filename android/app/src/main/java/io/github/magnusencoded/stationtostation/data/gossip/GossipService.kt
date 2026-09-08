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
import io.github.magnusencoded.stationtostation.data.contactKeysOf
import io.github.magnusencoded.stationtostation.data.exchange.contactIdentityPublicKeyBase64
import io.github.magnusencoded.stationtostation.data.exchange.signWithContactIdentity
import io.github.magnusencoded.stationtostation.data.gossipStormGate
import io.github.magnusencoded.stationtostation.ble.GossipCentral
import io.github.magnusencoded.stationtostation.ble.GossipDelivery
import io.github.magnusencoded.stationtostation.ble.GossipPeripheral
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The gossip channel's radio, running where ADR-0019 says it may: in the background, behind a
 * notification the user can see and stop (#416).
 *
 * **What it does not do.** Not one acceptance decision is made here. Everything that arrives
 * goes to [gossipStormGate], which was built and tested for exactly that in #410; this class
 * moves bytes, keeps a clock, and writes the answer down. When you are tempted to add an
 * `if` about a message to this file, the `if` belongs in the gate.
 *
 * **Why a service and not a worker.** WorkManager schedules against Doze, and a check-in is
 * only worth relaying to somebody standing in the same room *now*. A radio that wakes up
 * fifteen minutes later is a radio in a different room. That is the trade ADR-0019 accepted
 * on Android's side, and the notification is the price it named.
 *
 * **Its state is not a source of truth.** [GossipStore] is, and every fold through it is one
 * atomic read-edit-write. The service holds only what would be pointless to persist: the last
 * time it spoke to each peer (see [GOSSIP_PEER_COOLDOWN]) and a snapshot of the timeline it
 * refreshes as it goes.
 */
class GossipService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Serialises the fold.
     *
     * Deliveries arrive on binder threads and can overlap; [GossipStore.update] is atomic per
     * call, but a delivery is a *read the timeline, run the gate, then write* — and two of
     * those interleaved would run the gate twice against the same seen set and accept the
     * same message twice.
     */
    private val gate = Mutex()

    private lateinit var settings: SettingsRepository
    private lateinit var timeline: TimelineStore
    private lateinit var store: GossipStore

    private var peripheral: GossipPeripheral? = null
    private var central: GossipCentral? = null

    /** Refreshed on every fold, so a **Contact** made tonight is gossiped with tonight. */
    @Volatile private var contacts: Set<String> = emptySet()

    /** Gig id to the end of its night — the gate's `nightEndFor`. */
    @Volatile private var nightEnds: Map<String, Instant> = emptyMap()

    /** Live outbox, kept in memory so a scan hit can be answered without touching disk. */
    @Volatile private var held: List<GossipHeld> = emptyList()

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
            stopSelf()
            return START_NOT_STICKY
        }
        // Before anything else, and on every delivery of the intent: Android kills a service
        // that has not called this within five seconds, and an already-started service being
        // asked to start again is normal.
        startForegroundNotification(held.size)
        if (peripheral == null) startRadio()
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
        scope.cancel()
        super.onDestroy()
    }

    private fun startRadio() {
        val myKey = runCatching { contactIdentityPublicKeyBase64() }.getOrNull()
        if (myKey == null) {
            // No identity means no Exchange has ever happened, so there is nobody to gossip
            // with and nothing to sign with. Nothing to report — this is a state, not a fault.
            stopSelf()
            return
        }
        scope.launch { refresh() }

        peripheral = GossipPeripheral(
            context = applicationContext,
            myKey = { myKey },
            contacts = { contacts },
        ).also {
            it.onDelivery = { delivery -> scope.launch { accept(delivery) } }
            it.start()
        }

        central = GossipCentral(
            context = applicationContext,
            myKey = { myKey },
            contacts = { contacts },
            sign = { payload -> runCatching { signWithContactIdentity(payload) }.getOrNull() },
            outboxFor = { peer -> gossipOutboxFor(held, peer, Instant.now()) },
            due = { peer -> synchronized(spokenAt) { gossipPassDue(spokenAt[peer], Instant.now()) } },
            onPushed = { peer -> synchronized(spokenAt) { spokenAt[peer] = Instant.now() } },
        ).also { it.start() }
    }

    /**
     * A **Contact** in range handed over a batch, and proved it was them.
     *
     * The proof happened in [GossipPeripheral] — that is the sentence the gate's `from`
     * parameter is written against — so everything left is the gate's, and this is where the
     * cooldown the gate delegated is charged.
     */
    private suspend fun accept(delivery: GossipDelivery) = gate.withLock {
        val now = Instant.now()
        val due = synchronized(spokenAt) { gossipPassDue(spokenAt[delivery.from], now) }
        if (!due) return@withLock
        synchronized(spokenAt) { spokenAt[delivery.from] = now }

        refresh()
        var accepted = 0
        held = store.update(now) { current ->
            val plan = gossipStormGate(
                seen = seenFrom(current),
                batch = delivery.batch,
                from = delivery.from,
                now = now,
                contacts = contacts,
                nightEndFor = { gigId -> nightEnds[gigId] },
            )
            accepted = plan.accepted.size
            gossipHold(current, plan, delivery.from)
        }
        if (accepted > 0) {
            Log.i(TAG, "accepted $accepted of ${delivery.batch.size} from a contact")
            // A newly accepted message is news to push on, and the notification is the only
            // honest account of what this service is doing with the battery it is spending.
            startForegroundNotification(held.size)
        }
    }

    /** Re-read the two things that decide what is worth saying, and to whom. */
    private suspend fun refresh() {
        contacts = contactKeysOf(settings.friends.first())
        nightEnds = gossipNightEnds(gigDatesOf(timeline))
        held = store.held()
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
            .setContentText(
                if (carrying > 0) getString(R.string.gossip_notification_carrying, carrying)
                else getString(R.string.gossip_notification_idle),
            )
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
        private const val ACTION_STOP = "io.github.magnusencoded.stationtostation.GOSSIP_STOP"

        /**
         * Bring the service into line with [gossipRelayShouldRun] — start it, stop it, or
         * leave it alone.
         *
         * Called from wherever one of the answer's inputs can have changed: a check-in, a new
         * **Contact**, the setting being toggled, the app being opened. Cheap enough to call
         * on every one of those, because the decision is made from state that is already
         * loaded and the system ignores a start for a service that is running.
         */
        fun sync(context: Context, contacts: Int, holding: Boolean, gigTonight: Boolean, alwaysRelay: Boolean) {
            val intent = Intent(context, GossipService::class.java)
            if (gossipRelayShouldRun(contacts, holding, gigTonight, alwaysRelay)) {
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
 * Every **Gig** on this timeline that has a date, by the id a gossip message would name it by.
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
