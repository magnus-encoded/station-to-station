package io.github.magnusencoded.stationtostation.features.tour

import io.github.magnusencoded.stationtostation.data.parseFmDate
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class TourNightArrivesEffects(
    private val file: File,
    private val demoGig: suspend () -> FmSetlist?,
    private val deleteCalendarEvent: suspend (String) -> Unit,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : DemoWorld {
    @Serializable
    private data class Saved(
        val gigId: String? = null,
        // Resuming S11 or S13 emits no clock command; keep the last instant across launches.
        val clock: Long? = null,
        val calendarEvents: Set<String> = emptySet(),
    )

    private val writes = Mutex()
    @Volatile
    private var saved = if (file.exists()) Json.decodeFromString<Saved>(file.readText()) else Saved()
    private val clock = MutableStateFlow(saved.clock?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone) })
    val demoNow = clock.asStateFlow()

    val gigId: String? get() = saved.gigId

    fun isDemoGig(gigId: String): Boolean = saved.gigId == gigId

    suspend fun advance(to: DemoMoment, active: () -> Boolean) = writes.withLock {
        val gig = demoGig() ?: return@withLock
        val now = instant(to, gig.eventDate) ?: return@withLock
        if (!active()) return@withLock
        save(saved.copy(gigId = gig.id, clock = now.atZone(zone).toInstant().toEpochMilli()))
        clock.value = now
    }

    suspend fun recordCalendarEvent(eventUri: String, active: () -> Boolean): Boolean = writes.withLock {
        // A provider insert can return after Skip has already purged this Demo world.
        if (!active()) {
            deleteCalendarEvent(eventUri)
            return@withLock false
        }
        save(saved.copy(calendarEvents = saved.calendarEvents + eventUri))
        // Skip can also arrive while the handle is being persisted.
        if (!active()) {
            deleteCalendarEvent(eventUri)
            save(saved.copy(calendarEvents = saved.calendarEvents - eventUri))
            return@withLock false
        }
        true
    }

    override suspend fun purge() = writes.withLock {
        saved.calendarEvents.forEach { deleteCalendarEvent(it) }
        save(Saved())
        clock.value = null
    }

    private suspend fun save(after: Saved) {
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(Json.encodeToString(after))
            check(temporary.renameTo(file))
        }
        saved = after
    }

    companion object {
        fun instant(moment: DemoMoment, gigDate: String?): LocalDateTime? {
            val day = gigDate?.let(::parseFmDate) ?: return null
            return when (moment) {
                DemoMoment.Approaching -> day.minusDays(1).atTime(12, 0)
                DemoMoment.Doors -> day.atTime(19, 0)
                DemoMoment.ShowStarted -> day.atTime(21, 0)
                DemoMoment.After -> day.plusDays(1).atTime(12, 0)
            }
        }

        fun mapsUri(location: Pair<Double, Double>, venueName: String?): String {
            val (latitude, longitude) = location
            val point = "$latitude,$longitude"
            val label = venueName?.takeIf { it.isNotBlank() }?.let { "($it)" }.orEmpty()
            return "geo:$point?q=" + URLEncoder.encode(point + label, "UTF-8").replace("+", "%20")
        }
    }
}
