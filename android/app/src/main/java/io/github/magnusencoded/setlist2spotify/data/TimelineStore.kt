package io.github.magnusencoded.setlist2spotify.data

import android.content.Context
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmSetlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Every timeline on the device, in one file.
 *
 * Only the *facts* are stored — the shows themselves, keyed by setlist.fm username,
 * plus the festival names that cost a fetch each. The spine's shape (what clusters
 * into a festival, what merges with a friend's node) is derived at render time by
 * groupIntoFestivals/weaveTimelines, so changing those rules never needs a migration.
 *
 * ponytail: one file, not one per user — no filename escaping, one read at launch.
 * Split per user if a collection ever gets big enough for the write to stutter.
 */
/**
 * A playlist this app made from a night. Kept so the night can point at it later —
 * Spotify has no way to ask "which playlist came from this setlist", and without
 * this the act of converting leaves no trace anywhere the user can find it again.
 */
@Serializable
data class StoredPlaylist(
    val url: String,
    val name: String = "",
    val trackCount: Int = 0,
)

@Serializable
data class TimelineCache(
    /** Attended shows by setlist.fm username — mine and every friend's alike. */
    val shows: Map<String, List<FmSetlist>> = emptyMap(),
    /** Festival name by its cluster's first show id; see AppViewModel.resolveFestivalNames. */
    val festivalNames: Map<String, String> = emptyMap(),
    /** The playlist made from a night, by that night's setlist id. */
    val playlists: Map<String, StoredPlaylist> = emptyMap(),
)

/** [file] rather than a Context only so the merge can be tested on the JVM. */
class TimelineStore(private val file: File) {

    constructor(context: Context) : this(File(context.filesDir, "timelines.json"))

    // encodeDefaults so an empty cache round-trips; ignoreUnknownKeys so a field
    // added to FmSetlist doesn't make an existing cache unreadable.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // save() is read-modify-write and three call sites fire independently (my import,
    // the friend lanes, the festival names) — without this, two overlapping saves
    // both read the old cache and the loser's writes vanish.
    private val writeLock = Mutex()

    /** The cache as last written. Empty (never null) on first run or unreadable file. */
    suspend fun load(): TimelineCache = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext TimelineCache()
        runCatching { json.decodeFromString<TimelineCache>(file.readText()) }
            .getOrDefault(TimelineCache())
    }

    /**
     * Merges [shows] and [festivalNames] into what's already stored and writes it back.
     * Merging, not replacing: a refresh of one lane must not wipe the others, and a
     * partial fetch (one friend's request failed) must not delete their last good copy.
     */
    suspend fun save(
        shows: Map<String, List<FmSetlist>> = emptyMap(),
        festivalNames: Map<String, String> = emptyMap(),
        playlists: Map<String, StoredPlaylist> = emptyMap(),
    ): Unit = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val merged = load().let {
                it.copy(
                    shows = it.shows + shows.filterValues { list -> list.isNotEmpty() },
                    festivalNames = it.festivalNames + festivalNames,
                    playlists = it.playlists + playlists,
                )
            }
            // Write via a temp file: a crash mid-write leaves the old cache intact
            // rather than a truncated one that fails to parse. Files.move, not
            // renameTo — renameTo won't overwrite an existing file on Windows, so
            // the second save would silently no-op under the JVM tests.
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(merged))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
