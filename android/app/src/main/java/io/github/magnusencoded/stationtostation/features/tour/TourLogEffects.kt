package io.github.magnusencoded.stationtostation.features.tour

import io.github.magnusencoded.stationtostation.UiState
import io.github.magnusencoded.stationtostation.data.StoredLog
import io.github.magnusencoded.stationtostation.data.sameSong
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Demo Logs live outside the timeline and radio ledgers, including across relaunch. */
class TourLogEffects(
    private val file: File,
    private val update: ((UiState) -> UiState) -> Unit,
    private val songs: suspend (FmSetlist) -> List<String>,
) : DemoWorld {
    @Serializable data class Record(val log: StoredLog = StoredLog(), val gapSong: String? = null, val filled: Boolean = false)
    private var generation = 0
    private val pools = mutableMapOf<String, List<String>>()

    suspend fun pool(gig: FmSetlist): List<String> {
        pools[gig.id]?.let { return it }
        val token = generation
        val titles = songs(gig).filter { it.isNotBlank() }
        if (token == generation && titles.isNotEmpty()) pools[gig.id] = titles
        return titles
    }
    private var records: Map<String, Record> = if (file.exists()) Json.decodeFromString(file.readText()) else emptyMap()

    fun restore() { update { it.copy(logsByGig = it.logsByGig + records.mapValues { row -> row.value.log }) } }
    fun record(id: String): Record? = records[id]
    fun write(id: String, log: StoredLog) = save(id, (records[id] ?: Record()).copy(log = log))

    suspend fun deliver(gig: FmSetlist, active: () -> Boolean): Boolean {
        val token = generation
        val pool = pool(gig)
        if (token != generation || !active()) return false
        val record = records[gig.id] ?: return false
        if (record.gapSong != null) return true
        val gap = record.log.songs.indexOfFirst { it.isBlank() }
        val song = pool.drop(1).firstOrNull { title -> title.isNotBlank() && record.log.songs.none { sameSong(it, title) } } ?: return false
        if (gap < 0) return false
        val titles = record.log.songs.toMutableList().apply { this[gap] = song }
        // Naming a Gap preserves its entry time and stable line number.
        save(gig.id, record.copy(log = record.log.copy(songs = titles), gapSong = song))
        return true
    }

    suspend fun fill(gig: FmSetlist, now: Long, active: () -> Boolean): Boolean {
        val token = generation
        val pool = pool(gig)
        if (token != generation || !active()) return false
        val record = records[gig.id] ?: Record()
        if (record.filled) return true
        val additions = tourSetlistFill(record.log.songs, pool)
        if (additions.isEmpty()) return false
        save(gig.id, record.copy(log = additions.fold(record.log) { log, song -> log.adding(song, now) }, filled = true))
        return true
    }

    private fun save(id: String, record: Record) {
        records = records + (id to record)
        file.parentFile?.mkdirs()
        val pending = File(file.path + ".tmp")
        pending.writeText(Json.encodeToString(records))
        check(pending.renameTo(file))
        update { it.copy(logsByGig = it.logsByGig + (id to record.log)) }
    }

    override suspend fun purge() {
        generation++
        pools.clear()
        val ids = records.keys
        records = emptyMap()
        file.delete()
        update { it.copy(logsByGig = it.logsByGig - ids) }
    }
}

internal fun tourSetlistFill(userSongs: List<String>, pool: List<String>): List<String> {
    val unique = pool.filter { it.isNotBlank() }.fold(emptyList<String>()) { held, song ->
        if (held.any { sameSong(it, song) }) held else held + song
    }
    return unique.filter { title -> userSongs.none { sameSong(it, title) } }
        .take((10 - userSongs.count { it.isNotBlank() }).coerceAtLeast(0))
}
