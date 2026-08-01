package io.github.magnusencoded.setlist2spotify.ble

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Strategy
import java.nio.charset.StandardCharsets

/**
 * #30, scope item 4: how long a Nearby endpoint name may be.
 *
 * It decides whether the Android fast path survives. Nearby's trick is that the
 * card *is* the advertisement — discovery hands you everything, no connection
 * needed. Add a 32-byte public key and the card lands near 130 bytes, which is
 * the edge of the ceiling.
 *
 * The ceiling is **131 bytes** (`kMaxEndpointInfoLength` in google/nearby, the
 * same implementation Play services ships; the length rides in a one-byte field).
 * There is no status code for exceeding it and `startAdvertising` still succeeds:
 * over Bluetooth Classic the name is **silently truncated**, over BLE the
 * advertisement is silently dropped. So "advertise and see if it fails" measures
 * nothing — the only way to see the ceiling is to advertise a name of known
 * length and have a second phone report the length that arrived.
 *
 * Hence two halves. Run [advertiseLength] on one phone, [watch] on the other.
 */
class NearbyNameLimitProbe(context: Context) {

    private val connections = Nearby.getConnectionsClient(context)

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            connections.rejectConnection(endpointId)
        }
        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) = Unit
        override fun onDisconnected(endpointId: String) = Unit
    }

    /**
     * Advertises a name of exactly [length] bytes, shaped `NAME<length>-xxxx…` so
     * the watching phone can compare what was claimed against what it got.
     */
    fun advertiseLength(length: Int, onResult: (String) -> Unit) {
        val prefix = "NAME$length-"
        val name = prefix + "x".repeat((length - prefix.length).coerceAtLeast(0))
        connections
            .startAdvertising(
                name,
                SERVICE_ID,
                lifecycle,
                AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build(),
            )
            .addOnSuccessListener {
                onResult("advertising a ${name.toByteArray(StandardCharsets.UTF_8).size}-byte name (accepted — it always is)")
            }
            .addOnFailureListener { e -> onResult("advertise refused: ${e.message}") }
    }

    /** Reports the byte length of every endpoint name that turns up. */
    fun watch(onSeen: (claimed: Int?, received: Int, name: String) -> Unit, onFailure: (String) -> Unit) {
        val discovery = object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                val name = info.endpointName
                val claimed = Regex("^NAME(\\d+)-").find(name)?.groupValues?.get(1)?.toIntOrNull()
                onSeen(claimed, name.toByteArray(StandardCharsets.UTF_8).size, name)
            }
            override fun onEndpointLost(endpointId: String) = Unit
        }
        connections
            .startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
            .addOnFailureListener { e -> onFailure("discovery failed: ${e.message}") }
    }

    fun stop() {
        connections.stopAdvertising()
        connections.stopDiscovery()
    }

    companion object {
        /** `kMaxEndpointInfoLength`. Enforce it yourself; nothing else will. */
        const val NEARBY_ENDPOINT_NAME_LIMIT = 131

        // Separate from NearbyPeers' service id so a probe run cannot show up as a
        // person on the real connect screen.
        private const val SERVICE_ID = "io.github.magnusencoded.setlist2spotify.namelimitprobe"
    }
}
