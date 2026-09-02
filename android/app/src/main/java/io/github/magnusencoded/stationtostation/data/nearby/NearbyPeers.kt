package io.github.magnusencoded.stationtostation.data.nearby

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import io.github.magnusencoded.stationtostation.ble.EXCHANGE_TIMEOUT_MS
import io.github.magnusencoded.stationtostation.ble.ProbeCard
import io.github.magnusencoded.stationtostation.ble.fitsAnEndpointName
import io.github.magnusencoded.stationtostation.ble.parseProbeCard
import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.data.friendFromUri
import io.github.magnusencoded.stationtostation.data.toShareUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.charset.StandardCharsets

/**
 * Who has been standing near you while this screen was open, and the swap when you tap one.
 *
 * **The advertisement announces presence; the connection carries identity (#272).** The
 * endpoint name holds the keyless share URI — a display name, a username, a Spotify id —
 * which is enough to draw a row and nothing more. The public key #28/#257 made the identity
 * does not fit: Nearby's endpoint info tops out at 131 bytes
 * ([NearbyNameLimitProbe.NEARBY_ENDPOINT_NAME_LIMIT], probed on hardware under #30), a real
 * P-256 SubjectPublicKeyInfo is 132 bytes once URL-encoded, and the overflow is silent.
 *
 * So a tap opens a real connection and both ends send their [ProbeCard] as a payload. This
 * used to add the advertised card directly, which looked like an **Exchange** and produced a
 * **Followed line** with no key — two Androids walked away believing they were **Contacts**
 * when neither was. That is #272, and it is what this shape fixes.
 *
 * Three properties are deliberate:
 *
 * - **Nothing is ever removed from the list.** A row stays for the life of the screen even
 *   after its owner walks off. It disposes of the flicker you get from believing Nearby's
 *   endpoint-lost, which fires whenever an advertising window is missed. A row whose owner
 *   has really gone now fails on tap and falls through to QR, which is the honest outcome.
 * - **The swap is mutual, and one tap is enough.** Both ends send once the connection is up,
 *   so the person who did not tap becomes a **Contact** too — the same thing the BLE path
 *   already does by writing its card back.
 * - **Presence is the consent.** An inbound connection is accepted while the **Exchange**
 *   screen is open and refused otherwise, which is exactly the window BLE has.
 *
 * Android-to-Android only. iOS cannot see a Nearby endpoint, so the raw GATT probe
 * (#13/#18) is still the thing that has to exist for a mixed crowd.
 */
class NearbyPeers(private val context: Context) {

    private val connections = Nearby.getConnectionsClient(context)

    private val _peers = MutableStateFlow<List<Friend>>(emptyList())
    /** Everyone seen since [start], in the order they turned up. */
    val peers: StateFlow<List<Friend>> = _peers.asStateFlow()

    private val _failure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = _failure.asStateFlow()

    private var running = false

    /** My own card, sent once a connection is up. Held only while advertising. */
    private var myCard: ProbeCard? = null

    /**
     * Where the endpoint id lives. The screen sees people, not endpoints, so the id that
     * [exchangeCards] needs is kept here and looked up by username — the same identity the
     * peer list already dedupes on. Keeping it out of [peers] leaves the merge decision in
     * `mergePeers` untouched.
     */
    private val endpoints = mutableMapOf<String, String>()

    /** In-flight taps, by endpoint id. Removing one is what makes its result fire once. */
    private val pending = mutableMapOf<String, (ProbeCard?) -> Unit>()

    /**
     * Each in-flight tap's giving-up timer, held so [deliver] can cancel it.
     *
     * Kept rather than fired-and-forgotten because the timer is keyed to the endpoint and a
     * person can be tapped twice: `connectWith` deliberately leaves the radios running after
     * a failed tap so the QR offer stays reachable, so "tap, fail fast, tap again" is the
     * ordinary retry. Without this the first tap's timer fires seven seconds later into the
     * *second* tap's callback and reports a failure that did not happen.
     */
    private val timeouts = mutableMapOf<String, Runnable>()

    private val handler = Handler(Looper.getMainLooper())

    /**
     * A card that arrived because *they* tapped. The twin of the BLE peripheral's
     * inbound write: presence is the consent, so it is delivered, not prompted.
     */
    var onCardReceived: ((ProbeCard) -> Unit)? = null

    fun consumeFailure() = _failure.update { null }

    /**
     * True when every permission Nearby needs on this Android version is granted.
     * Checked rather than assumed: discovery fails silently without them, which
     * looks exactly like "nobody is nearby".
     */
    fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun requiredPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            // Below 33 a BLE scan counts as a location fix, whatever it is used for.
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    /**
     * Starts advertising [me] and collecting whoever answers. Safe to call again
     * while running — a re-entered screen should not restart the radio.
     *
     * **[me] and [myCard] are null for someone with no setlist.fm username**, and then
     * only discovery runs: with no public history to point at there is nothing to
     * advertise, but seeing who is in the room needs no card of your own. Looking is
     * not the half that requires an account.
     */
    fun start(me: Friend?, myCard: ProbeCard?) {
        if (running) return
        if (!hasPermissions()) {
            _failure.update { "Bluetooth and nearby-device permission are needed to find people." }
            return
        }
        // Keyless by construction — see `AppViewModel.myCard()`. The key rides the
        // connection instead (#272); a name carrying one would silently overflow.
        val advertisement = me?.toShareUri()?.toString()
        if (advertisement != null && !fitsAnEndpointName(advertisement)) {
            // Refused rather than sent. Nearby does not report an over-long name: over
            // Bluetooth Classic it truncates, over BLE it drops the advertisement, so the
            // owner is simply invisible with nothing to read. Being invisible *and* silent
            // is #272's whole failure mode, so this says so instead. BLE and QR still work.
            running = false
            _failure.update { "Your setlist.fm username is too long to announce nearby. BLE and the QR card still work." }
            return
        }
        running = true
        this.myCard = myCard

        // P2P_CLUSTER, not POINT_TO_POINT: at a festival several people are in range
        // at once, and the whole point is to see who is there.
        val strategy = Strategy.P2P_CLUSTER
        if (advertisement != null) {
            connections
                .startAdvertising(
                    advertisement,
                    SERVICE_ID,
                    lifecycle,
                    AdvertisingOptions.Builder().setStrategy(strategy).build(),
                )
                .addOnFailureListener { fail("Could not advertise", it) }
        }
        connections
            .startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { fail("Could not look for people nearby", it) }
    }

    fun stop() {
        running = false
        myCard = null
        connections.stopAdvertising()
        connections.stopDiscovery()
        // Every live connection goes with the screen. Nothing is disconnected per swap:
        // dropping the link the moment their card lands can cut them off before mine
        // reaches them, and a handful of connections bounded by one screen costs nothing.
        connections.stopAllEndpoints()
        handler.removeCallbacksAndMessages(null)
        timeouts.clear()
        pending.keys.toList().forEach { deliver(it, null) }
        endpoints.clear()
        _peers.update { emptyList() }
    }

    /** Stop and start again — the "nothing is appearing, try harder" gesture. */
    fun restart(me: Friend?, myCard: ProbeCard?) {
        stop()
        start(me, myCard)
    }

    /**
     * The tap. Opens a connection to whoever advertised [card] and hands back the full
     * card they send over it — the one carrying the key that makes them a **Contact**.
     *
     * Null on any failure, and deliberately *not* the advertised card as a consolation.
     * A keyless add that looks like a completed **Exchange** is #272 itself; failing lets
     * the caller fall through to the QR offer, the same as a failed BLE read.
     */
    fun exchangeCards(card: Friend, onCard: (ProbeCard?) -> Unit) {
        val endpointId = endpoints[card.setlistfm.lowercase()]
        val me = myCard
        if (endpointId == null || me == null) {
            onCard(null)
            return
        }
        // A second tap while the first is still out would replace the waiting callback and
        // leave the screen saying "Connecting with …" forever.
        if (pending.containsKey(endpointId)) return
        pending[endpointId] = onCard
        val timeout = Runnable { deliver(endpointId, null) }
        timeouts[endpointId] = timeout
        handler.postDelayed(timeout, EXCHANGE_TIMEOUT_MS)
        connections
            .requestConnection(me.name, endpointId, lifecycle)
            .addOnFailureListener { e ->
                Log.w(TAG, "could not reach $endpointId: ${e.message}")
                deliver(endpointId, null)
            }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // Anything that isn't one of our cards is some other app on the same
            // service id, or a truncated name; ignore rather than show a blank row.
            val friend = cardFrom(info.endpointName) ?: return
            // Last one wins: a phone that restarted advertising has a new endpoint id,
            // and the stale one would fail on tap.
            endpoints[friend.setlistfm.lowercase()] = endpointId
            _peers.update { seen ->
                if (seen.any { it.setlistfm.equals(friend.setlistfm, ignoreCase = true) }) seen
                else seen + friend
            }
        }

        // Deliberately empty: see the class docs. A phone that stops advertising has
        // not taken its card back, and the row it left behind is still usable.
        override fun onEndpointLost(endpointId: String) = Unit
    }

    /**
     * Both halves of a swap. A request arrives here whether I tapped or they did, and the
     * two cases differ only in whether [pending] is holding a callback for it.
     */
    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Presence is the consent, and the screen is the window: both phones have the
            // Exchange open or neither is here. No auth token is shown — a PIN to compare
            // is exactly the ceremony two people standing together do not need.
            if (running) connections.acceptConnection(endpointId, payloads)
            else connections.rejectConnection(endpointId)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            val me = myCard
            if (!resolution.status.isSuccess || me == null) {
                deliver(endpointId, null)
                return
            }
            // Sent by both ends, unprompted: one tap is what makes contacts of two people.
            connections.sendPayload(endpointId, Payload.fromBytes(me.bytes()))
        }

        override fun onDisconnected(endpointId: String) = deliver(endpointId, null)
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            deliver(endpointId, parseProbeCard(String(bytes, StandardCharsets.UTF_8)))
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    /**
     * One swap ends exactly once. A tap gets its answer, a card that arrived because they
     * tapped goes to [onCardReceived], and a failure after either has already been answered
     * is a no-op — which is what lets the timeout, the disconnect and the payload all call
     * this without racing.
     */
    private fun deliver(endpointId: String, card: ProbeCard?) {
        timeouts.remove(endpointId)?.let(handler::removeCallbacks)
        val waiting = pending.remove(endpointId)
        when {
            waiting != null -> waiting(card)
            card != null -> onCardReceived?.invoke(card)
        }
    }

    private fun cardFrom(endpointName: String): Friend? =
        runCatching { friendFromUri(Uri.parse(endpointName)) }.getOrNull()

    private fun fail(what: String, e: Throwable) {
        Log.w(TAG, "$what: ${e.message}")
        running = false
        _failure.update { what }
    }

    private companion object {
        const val TAG = "NearbyPeers"
        // Namespaced to this app: two phones only see each other if both run it.
        const val SERVICE_ID = "io.github.magnusencoded.stationtostation.timelines"
    }
}
