package io.github.magnusencoded.stationtostation.features.tour

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.core.content.ContextCompat
import io.github.magnusencoded.stationtostation.UiState
import io.github.magnusencoded.stationtostation.data.DeviceLocation
import io.github.magnusencoded.stationtostation.data.ParsedTicket
import io.github.magnusencoded.stationtostation.data.SettingsRepository
import io.github.magnusencoded.stationtostation.data.StoredMedia
import io.github.magnusencoded.stationtostation.data.TimelineStore
import io.github.magnusencoded.stationtostation.data.photos.PhotoRepository
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.features.contacts.ContactsController
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
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
    photos: PhotoRepository,
    setlistFm: io.github.magnusencoded.stationtostation.data.setlistfm.SetlistFmClient,
    musicBrainz: io.github.magnusencoded.stationtostation.data.musicbrainz.MusicBrainzClient,
    importTicket: suspend (ParsedTicket, String) -> Boolean,
): TourController {
    val character = TourCharacter.load(context)
    val demoGig: suspend () -> FmSetlist? = {
        val cache = timelines.load()
        val demo = cache.gigs.values.filter { it.demo }.map { it.setlistId ?: it.id }.toSet()
        cache.planned().firstOrNull { it.id in demo }
    }
    val night = TourNightArrivesEffects(
        file = File(context.filesDir, "tour-night.json"),
        demoGig = demoGig,
        deleteCalendarEvent = { uri ->
            withContext(Dispatchers.IO) {
                context.contentResolver.delete(Uri.parse(uri), null, null)
            }
        },
    )
    val meetFriend = TourMeetFriendEffects(
        friendName = { character.name },
        extraNights = { gig ->
            // Fictional character history, never copies of the person's chosen gig.
            character.history.mapIndexed { index, night ->
                io.github.magnusencoded.stationtostation.data.localGigSetlist(
                    "tour-history:$index:${gig.id}", night.artist,
                    requireNotNull(io.github.magnusencoded.stationtostation.data.parseFmDate(night.date)), night.venue, night.city)
            }
        },
        contacts = { settings.friends.first() },
        demoGig = demoGig,
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
    val selfie = TourSelfieEffects(
        timelines = timelines,
        friendKey = { meetFriend.friendKey() },
        selfieBytes = { selfieJpeg(context, character.selfie) },
        storeSelfie = { id, bytes ->
            withContext(Dispatchers.IO) {
                val file = photos.receivedMediaFile(id, StoredMedia.Kind.PHOTO)
                file.writeBytes(bytes)
                val ref = photos.fileProviderRef(file)
                if (photos.generateThumbnails(id, Uri.parse(ref))) ref else null.also { file.delete() }
            }
        },
        discard = { photos.deleteOwnedBytes(it.id, it.ref) },
        update = { transform -> state.update(transform) },
    )
    val log = TourLogEffects(File(context.filesDir, "tour-logs.json"), { change -> state.update(change) }) { gig ->
        val mbid = gig.artist?.mbid.orEmpty()
        if (mbid.isBlank()) {
            android.util.Log.w("Tour", "Song pool has no artist MBID for ${gig.id}")
            emptyList()
        } else {
            val recent = runCatching { setlistFm.artistSetlists(mbid).setlist }
                .onFailure { android.util.Log.w("Tour", "setlist.fm song pool failed for $mbid", it) }
                .getOrDefault(emptyList()).sortedByDescending { it.localDate() }
            recent.firstOrNull { it.performed().isNotEmpty() }?.performed()?.map { it.name }
                ?: runCatching { musicBrainz.catalogue(mbid) }
                    .onFailure { android.util.Log.w("Tour", "MusicBrainz song pool failed for $mbid", it) }
                    .getOrDefault(emptyList()).also {
                        if (it.isEmpty()) android.util.Log.w("Tour", "No performed setlist or MusicBrainz songs for $mbid")
                    }
        }
    }
    val demoWorld = DemoWorldRegistry(
        listOf(
            selfie,
            log,
            DemoWorld {
                state.update { it.copy(calendarEventByGig = it.calendarEventByGig.filterKeys { id -> !night.isDemoGig(id) }) }
                night.purge()
            },
            DemoWorld { meetFriend.purge() },
        ),
    )
    return TourController(
        state = { state.value },
        update = { transform -> state.update(transform) },
        store = object : TourStore {
            override suspend fun saveTour(state: TourState) = settings.saveTour(state)
            override suspend fun setOnboarded() = settings.setOnboarded()
            override suspend fun purgeDemoWorld() = demoWorld.purge()
            override suspend fun markDemo(gigId: String) = timelines.markDemo(gigId)
        },
        isOnline = { context.isOnline() },
        scope = scope,
        meetFriend = meetFriend,
        askLocation = { !location.hasPermission() && settings.askTourLocationOnce() },
        night = night,
        selfie = selfie,
        log = log,
        demoGig = demoGig,
        readCharacter = { character },
    )
}

internal fun friendName(readCharacter: () -> String): String = characterString(readCharacter, "name").orEmpty()

/** The character definition's string at [key], or null when the file or the key is missing. */
internal fun characterString(readCharacter: () -> String, key: String): String? = runCatching {
    Json.parseToJsonElement(readCharacter()).jsonObject[key]?.jsonPrimitive?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
}.getOrNull()

/** The character's selfie, a drawable named by the definition's `selfie` key, as JPEG bytes. */
private suspend fun selfieJpeg(context: Context, name: String): ByteArray? = withContext(Dispatchers.Default) {
    runCatching {
        val id = context.resources.getIdentifier(name, "drawable", context.packageName).takeIf { it != 0 }
            ?: return@runCatching null
        val drawable = ContextCompat.getDrawable(context, id) ?: return@runCatching null
        val bitmap = Bitmap.createBitmap(SELFIE_EDGE, SELFIE_EDGE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        drawable.setBounds(0, 0, SELFIE_EDGE, SELFIE_EDGE)
        drawable.draw(canvas)
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
    }.getOrNull()
}

private const val SELFIE_EDGE = 1024

private fun Context.isOnline(): Boolean {
    val manager = getSystemService(ConnectivityManager::class.java) ?: return false
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
