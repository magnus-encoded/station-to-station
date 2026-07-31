package io.github.magnusencoded.setlist2spotify.data.nearby

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import io.github.magnusencoded.setlist2spotify.data.Friend
import io.github.magnusencoded.setlist2spotify.data.friendFromUri
import io.github.magnusencoded.setlist2spotify.data.toShareUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Finds the other phones in the room and swaps friend cards with them.
 *
 * The card *is* the advertisement. Nearby lets an endpoint carry a name of its own
 * choosing, and the friend deep link already in use for QR codes fits inside it —
 * so discovery alone tells you who is standing there, and no payload protocol has
 * to be invented, versioned, or chunked. The connection that follows is not how the
 * card travels; it is how both phones learn the swap was mutual, since only the
 * side that tapped would otherwise know anything happened.
 *
 * Android-to-Android only. iOS cannot see a Nearby endpoint, so the raw GATT probe
 * (#13/#18) is still the thing that has to exist for a mixed crowd — this is the
 * fast path where both phones are Android, not a replacement for it.
 */
class NearbyPeers(private val context: Context) {

    private val connections = Nearby.getConnectionsClient(context)

    private val _peers = MutableStateFlow<List<Friend>>(emptyList())
    val peers: StateFlow<List<Friend>> = _peers.asStateFlow()

    private val _connected = MutableStateFlow<Friend?>(null)
    /** Set when a swap completes, either side; cleared by [consumeConnected]. */
    val connected: StateFlow<Friend?> = _connected.asStateFlow()

    private val _failure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = _failure.asStateFlow()

    /** endpointId -> the card that endpoint advertised. */
    private val cards = mutableMapOf<String, Friend>()
    private var running = false

    fun consumeConnected() = _connected.update { null }
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
     * Starts advertising [me] and looking for others. Safe to call again while
     * running — a re-entered screen should not restart the radio.
     */
    fun start(me: Friend) {
        if (running) return
        if (!hasPermissions()) {
            _failure.update { "Bluetooth and nearby-device permission are needed to find people." }
            return
        }
        running = true
        cards.clear()
        _peers.update { emptyList() }

        // P2P_CLUSTER, not POINT_TO_POINT: at a festival several people are in range
        // at once, and the whole point is to see who is there before choosing.
        val strategy = Strategy.P2P_CLUSTER
        connections
            .startAdvertising(me.toShareUri().toString(), SERVICE_ID, lifecycle, AdvertisingOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { fail("Could not advertise", it) }
        connections
            .startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { fail("Could not look for people nearby", it) }
    }

    fun stop() {
        running = false
        connections.stopAdvertising()
        connections.stopDiscovery()
        connections.stopAllEndpoints()
        cards.clear()
        _peers.update { emptyList() }
    }

    /** Asks [friend]'s phone to swap. Both sides land in [connected] once it takes. */
    fun exchangeWith(friend: Friend, me: Friend) {
        val endpointId = cards.entries.firstOrNull { it.value.setlistfm == friend.setlistfm }?.key
        if (endpointId == null) {
            _failure.update { "${friend.name} is no longer nearby." }
            return
        }
        connections.requestConnection(me.toShareUri().toString(), endpointId, lifecycle)
            .addOnFailureListener { fail("Could not reach ${friend.name}", it) }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // Anything that isn't one of our cards is some other app on the same
            // service id, or a truncated name; ignore rather than show a blank row.
            val friend = cardFrom(info.endpointName) ?: return
            cards[endpointId] = friend
            _peers.update { (it + friend).distinctBy { f -> f.setlistfm.lowercase() } }
        }

        override fun onEndpointLost(endpointId: String) {
            val gone = cards.remove(endpointId) ?: return
            _peers.update { list -> list.filterNot { it.setlistfm == gone.setlistfm } }
        }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // The request carries the asker's card as its name, so a phone that was
            // never discovered (it was only listening) still becomes a known friend.
            cardFrom(info.endpointName)?.let { cards[endpointId] = it }
            // ponytail: auto-accept. info.authenticationDigits is the shoulder-surfing
            // guard — show it on both screens and make each side confirm — but that is
            // a second screen, and the threat here is someone standing next to you at
            // a gig getting your public setlist.fm username.
            connections.acceptConnection(endpointId, payloads)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            val friend = cards[endpointId]
            when {
                resolution.status.statusCode != ConnectionsStatusCodes.STATUS_OK ->
                    _failure.update { "The swap didn't take. Try again." }
                friend == null -> _failure.update { "Connected, but no card came through." }
                else -> _connected.update { friend }
            }
            // One-shot: the card is already in hand, so holding the radio open past
            // the swap only costs battery and blocks the next person.
            connections.disconnectFromEndpoint(endpointId)
        }

        override fun onDisconnected(endpointId: String) {
            cards.remove(endpointId)
        }
    }

    // Nothing is sent over the connection — see the class docs — but Nearby requires
    // a callback to accept one at all.
    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) = Unit
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
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
        const val SERVICE_ID = "io.github.magnusencoded.setlist2spotify.timelines"
    }
}
