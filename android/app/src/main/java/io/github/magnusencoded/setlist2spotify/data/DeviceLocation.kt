package io.github.magnusencoded.setlist2spotify.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Where the phone is, and where a venue is. One fix, in the foreground, when the
 * timeline is opened — nothing here is scheduled and nothing runs with the app
 * closed. There is no service, no callback registration that outlives the call,
 * and deliberately no background-location permission to go with it.
 *
 * ponytail: the platform LocationManager, not Play Services' fused provider. One
 * coarse "am I at this venue" fix does not need the fused provider's accuracy or
 * its dependency. Swap it in if a real venue proves the platform fix too slow.
 */
class DeviceLocation(private val context: Context) {

    companion object {
        /**
         * How long to wait for a fix before giving up and showing no prompt.
         *
         * The user is looking at their timeline; a check-in offer that arrives after
         * they have started scrolling is worse than none. Indoors at a venue a GPS
         * fix can take far longer than this — that is the case being given up on.
         */
        private const val FIX_TIMEOUT_MS = 8_000L

        /** Either grant is enough: coarse is ~100 m, well inside the venue radius. */
        fun requiredPermissions(): Array<String> = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

    fun hasPermission(): Boolean = requiredPermissions().any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * One fix, or null — no permission, no provider, location switched off, or it
     * took too long. Every one of those is "no prompt", never an error on screen.
     */
    // The permission is checked on the line below; lint can't see through hasPermission().
    @SuppressLint("MissingPermission")
    suspend fun currentFix(): Pair<Double, Double>? {
        if (!hasPermission()) return null
        val manager = ContextCompat.getSystemService(context, LocationManager::class.java) ?: return null
        // Whichever provider the phone will actually answer on: GPS is off indoors
        // often enough that network-only devices must still get a fix.
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return null
        val signal = CancellationSignal()
        return withTimeoutOrNull(FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine<Pair<Double, Double>?> { cont ->
                cont.invokeOnCancellation { runCatching { signal.cancel() } }
                runCatching {
                    LocationManagerCompat.getCurrentLocation(
                        manager,
                        provider,
                        signal,
                        // The main executor rather than one spun up per call: all the
                        // callback does is resume this coroutine.
                        ContextCompat.getMainExecutor(context),
                    ) { location ->
                        if (cont.isActive) cont.resume(location?.let { it.latitude to it.longitude })
                    }
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
    }

    /**
     * The venue's own coordinates, from the keyless native forward geocoder — the
     * refinement setlist.fm's city-level coords can't give. Null when the venue
     * name means nothing to the geocoder, which is a gig that simply gets no
     * prompt.
     *
     * The reverse direction (coordinates → venue candidates, so the app could
     * notice where you were and offer the gig) is not built and nothing here
     * forecloses it.
     */
    suspend fun geocodeVenue(query: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        // The blocking overload is deprecated on 33+ but still functional, and the
        // callback replacement would buy nothing here — this is already off the
        // main thread and already inside a timeout at the caller.
        @Suppress("DEPRECATION")
        val results = runCatching {
            Geocoder(context, Locale.ENGLISH).getFromLocationName(query, 1)
        }.getOrNull().orEmpty()
        results.firstOrNull()?.let { it.latitude to it.longitude }
    }
}
