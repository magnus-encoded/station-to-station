package io.github.magnusencoded.stationtostation.data.exchange

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.Inet4Address
import java.net.InetSocketAddress

/**
 * Who is reachable over the same WiFi right now (#257): mDNS via `NsdManager`, thin the
 * same way [io.github.magnusencoded.stationtostation.data.nearby.NearbyPeers] is thin —
 * discovery only, no business logic here.
 *
 * Unlike Nearby's endpoint name, an mDNS service instance carries no room for a Friend
 * card, and this deliberately does not try to make it: presence is not identity. A
 * discovered address is just somewhere to open a TLS socket; [mutualContactAuth] is what
 * turns "something answered" into "a known Contact answered", over on [ContactSession].
 */
/**
 * This device's own address on whatever network it is standing in, for the one case that
 * has no discovery to lean on: a handover's QR has to say *where* (#142), and it is
 * generated before anyone is listening for a beacon.
 *
 * `ConnectivityManager` rather than `WifiManager.connectionInfo`, which is deprecated and
 * wifi-only — a handover over a phone's hotspot or an ethernet dongle is the same
 * transfer. Null when there is no non-loopback IPv4 address to name, which is a real
 * answer: there is no local link to hand over across.
 */
internal fun localLinkAddress(context: Context): String? {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
    val links = cm.getLinkProperties(cm.activeNetwork)?.linkAddresses ?: return null
    return links.map { it.address }
        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isAnyLocalAddress }
        ?.hostAddress
}

class ContactPeers(private val context: Context) {

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _peers = MutableStateFlow<List<InetSocketAddress>>(emptyList())
    /** Every address seen answering the service since [startDiscovery], deduplicated. */
    val peers: StateFlow<List<InetSocketAddress>> = _peers.asStateFlow()

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /**
     * What this device actually published, so its own advertisement answering back is not
     * treated as a peer. mDNS renames on collision, so the name asked for and the name
     * registered are not always the same string, and only [NsdManager.RegistrationListener.onServiceRegistered]
     * knows which one this device ended up with.
     *
     * Matching against [SERVICE_NAME] instead — which is what this did — is not merely
     * imprecise, it is fatal for interop (#267): every device that publishes under the
     * plain unrenamed name, which is exactly what an iPhone with no name collision does,
     * gets skipped here as if it were us.
     *
     * Volatile because the NSD callbacks arrive on the framework's own thread and
     * [onServiceFound] reads this from wherever discovery is dispatched.
     */
    @Volatile private var registeredName: String? = null

    /** Advertises this device's reconcile listener at [port]. The instance name is
     * arbitrary — nothing reads it for identity, only the resolved address matters. */
    fun startAdvertising(port: Int) {
        if (registrationListener != null) return
        val info = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registeredName = info.serviceName
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "advertise failed: $errorCode")
                registrationListener = null
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stopAdvertising() {
        registrationListener?.let { runCatching { nsd.unregisterService(it) } }
        registrationListener = null
        registeredName = null
    }

    fun startDiscovery() {
        if (discoveryListener != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery failed to start: $errorCode")
                discoveryListener = null
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                // Our own advertisement answering back is not a peer — matched against the
                // name mDNS actually gave us, never against the one we asked for. See
                // [registeredName]. Null until registration lands: dialing ourselves in that
                // window costs one connection that fails [mutualContactAuth], since this
                // device's own key is not among its Contacts' keys.
                if (info.serviceName == registeredName) return
                nsd.resolveService(info, resolveListener())
            }

            // Deliberately empty, same reasoning as NearbyPeers.onEndpointLost: an
            // address that stops answering has not un-happened, and a stale entry here
            // just fails to connect later rather than showing something false now.
            override fun onServiceLost(info: NsdServiceInfo) = Unit
        }
        discoveryListener = listener
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stopDiscovery() {
        discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discoveryListener = null
        _peers.update { emptyList() }
    }

    /**
     * A **fresh listener per resolve**, which is the whole of the fix for #287.
     *
     * `NsdManager` allows a `ResolveListener` instance to be in flight for at most one
     * resolve at a time, and throws `IllegalArgumentException: listener already in use`
     * out of `onServiceFound` — on the framework's own callback thread, so it is an
     * uncaught crash rather than a resolve that failed. One shared listener is fine
     * with a single peer, because nothing overlaps; the second device on the network is
     * what finds it.
     *
     * Nothing needs cleaning up after: the object closes over nothing but [_peers], and
     * the framework releases it on either terminal callback. API 34's
     * `registerServiceInfoCallback` lifts the restriction, but this app is minSdk 26.
     */
    private fun resolveListener() = object : NsdManager.ResolveListener {
        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        override fun onServiceResolved(info: NsdServiceInfo) {
            val address = InetSocketAddress(info.host, info.port)
            _peers.update { seen -> if (address in seen) seen else seen + address }
        }
    }

    private companion object {
        const val TAG = "ContactPeers"
        const val SERVICE_TYPE = "_stationtostation._tcp."
        const val SERVICE_NAME = "station-to-station"
    }
}
