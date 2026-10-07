package io.github.magnusencoded.stationtostation.features.tour

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.magnusencoded.stationtostation.UiState
import io.github.magnusencoded.stationtostation.data.DeviceLocation
import io.github.magnusencoded.stationtostation.data.ParsedTicket
import io.github.magnusencoded.stationtostation.data.SettingsRepository
import io.github.magnusencoded.stationtostation.data.TimelineStore
import io.github.magnusencoded.stationtostation.features.contacts.ContactsController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    contacts: ContactsController,
    location: DeviceLocation,
    importTicket: suspend (ParsedTicket, String) -> Boolean,
): TourController {
    val meetFriend = TourMeetFriendEffects(
        friendName = { friendName { context.assets.open("character.json").bufferedReader().use { it.readText() } } },
        contacts = { settings.friends.first() },
        demoGig = {
            val cache = timelines.load()
            val demo = cache.gigs.values.filter { it.demo }.map { it.setlistId ?: it.id }.toSet()
            cache.planned().firstOrNull { it.id in demo }
        },
        locate = { location.currentFix()?.let { Place(it.first, it.second) } },
        placeVenue = { gig ->
            timelines.savePlanned(gig)
            state.update { it.copy(plannedGigs = it.plannedGigs.map { held -> if (held.id == gig.id) gig else held }) }
        },
        addContact = { contacts.addDemoFriend(it) },
        landNights = { key, nights -> contacts.landContactNights(key, nights) },
        importTicket = importTicket,
        purgeGigs = { timelines.purgeDemoWorld(it) },
        purgeContacts = { settings.purgeDemoContacts() },
        update = { transform -> state.update(transform) },
    )
    return TourController(
        state = { state.value },
        update = { transform -> state.update(transform) },
        store = object : TourStore {
            override suspend fun saveTour(state: TourState) = settings.saveTour(state)
            override suspend fun setOnboarded() = settings.setOnboarded()
            override suspend fun purgeDemoWorld() = meetFriend.purge()
            override suspend fun markDemo(gigId: String) = timelines.markDemo(gigId)
        },
        isOnline = { context.isOnline() },
        scope = scope,
        meetFriend = meetFriend,
        askLocation = { !location.hasPermission() && settings.askTourLocationOnce() },
    )
}

internal fun friendName(readCharacter: () -> String): String = runCatching {
    Json.parseToJsonElement(readCharacter()).jsonObject["name"]?.jsonPrimitive?.takeIf { it.isString }?.content.orEmpty()
}.getOrDefault("")

private fun Context.isOnline(): Boolean {
    val manager = getSystemService(ConnectivityManager::class.java) ?: return false
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
