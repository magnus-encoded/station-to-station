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
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Strategy
import io.github.magnusencoded.setlist2spotify.data.Friend
import io.github.magnusencoded.setlist2spotify.data.friendFromUri
import io.github.magnusencoded.setlist2spotify.data.toShareUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Who has been standing near you while this screen was open.
 *
 * The card *is* the advertisement: Nearby lets an endpoint carry a name of its own
 * choosing, and the friend deep link already used for QR codes fits inside it. That
 * makes this discovery and nothing else — there is no connection, no payload, no
 * handshake, because by the time a card is on screen everything it holds has
 * already arrived.
 *
 * Adding is therefore a purely local act, and two consequences follow:
 *
 * - **Nothing is ever removed.** A card stays for the life of the screen even after
 *   its owner walks off, and that is correct rather than stale: tapping it needs to
 *   reach nobody. It also disposes of the flicker you get from believing Nearby's
 *   endpoint-lost, which fires whenever an advertising window is missed.
 * - **There is no mutual handshake.** If two people want each other, each taps. That
 *   matches the QR card, which has always allowed a one-sided add of exactly this
 *   data. The consent that matters is advertising at all, and that is opted into by
 *   opening the screen.
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
     */
    fun start(me: Friend) {
        if (running) return
        if (!hasPermissions()) {
            _failure.update { "Bluetooth and nearby-device permission are needed to find people." }
            return
        }
        running = true

        // P2P_CLUSTER, not POINT_TO_POINT: at a festival several people are in range
        // at once, and the whole point is to see who is there.
        val strategy = Strategy.P2P_CLUSTER
        connections
            .startAdvertising(
                me.toShareUri().toString(),
                SERVICE_ID,
                lifecycle,
                AdvertisingOptions.Builder().setStrategy(strategy).build(),
            )
            .addOnFailureListener { fail("Could not advertise", it) }
        connections
            .startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { fail("Could not look for people nearby", it) }
    }

    fun stop() {
        running = false
        connections.stopAdvertising()
        connections.stopDiscovery()
        _peers.update { emptyList() }
    }

    /** Stop and start again — the "nothing is appearing, try harder" gesture. */
    fun restart(me: Friend) {
        stop()
        start(me)
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // Anything that isn't one of our cards is some other app on the same
            // service id, or a truncated name; ignore rather than show a blank row.
            val friend = cardFrom(info.endpointName) ?: return
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
     * Nothing connects, so nothing here should ever fire. Advertising requires a
     * lifecycle callback to be handed in, and refusing every request is how this
     * stays discovery-only even if another build of the app asks to connect.
     */
    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            connections.rejectConnection(endpointId)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) = Unit
        override fun onDisconnected(endpointId: String) = Unit
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
