package io.github.magnusencoded.stationtostation.features.tour

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.magnusencoded.stationtostation.UiState
import io.github.magnusencoded.stationtostation.data.SettingsRepository
import io.github.magnusencoded.stationtostation.data.TimelineStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * The Tour's wiring into `AppViewModel`, kept here so each later step adds its engines in
 * this file rather than in the view model.
 */
fun tourController(
    context: Context,
    state: MutableStateFlow<UiState>,
    settings: SettingsRepository,
    timelines: TimelineStore,
    scope: CoroutineScope,
) = TourController(
    state = { state.value },
    update = { transform -> state.update(transform) },
    store = object : TourStore {
        override suspend fun saveTour(state: TourState) = settings.saveTour(state)
        override suspend fun setOnboarded() = settings.setOnboarded()
        override suspend fun purgeDemoWorld() = timelines.purgeDemoWorld()
    },
    isOnline = { context.isOnline() },
    scope = scope,
)

private fun Context.isOnline(): Boolean {
    val manager = getSystemService(ConnectivityManager::class.java) ?: return false
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
