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

/**
 * My relationship to one gig: how sure the app is I was there, and — for a live
 * check-in — when. setlist.fm's own "I was there" is a flat, retroactive,
 * self-reported flag; this is stronger evidence for the same claim, not a
 * competing record.
 *
 * [provenance] is a plain string, not an enum: an unknown value (a future
 * `attested` written by a newer version of the app) should leave this one gig
 * un-decodable at worst, never fail the whole cache the way an unrecognised
 * enum constant would.
 */
@Serializable
data class StoredAttendance(
    val provenance: String = Provenance.PLANNED,
    /** Epoch millis of a live check-in. Null until #33 sets one. */
    val checkedInAt: Long? = null,
    /** Geocoded once by #33, reused for its proximity check. Null until resolved. */
    val venueLat: Double? = null,
    val venueLon: Double? = null,
) {
    /** Evidence strength, weakest first. Room for `attested` later; not built yet. */
    object Provenance {
        const val PLANNED = "planned"
        const val ATTENDED = "attended"
        const val CHECKED_IN = "checked_in"
    }
}

@Serializable
data class TimelineCache(
    /** Attended shows by setlist.fm username — mine and every friend's alike. */
    val shows: Map<String, List<FmSetlist>> = emptyMap(),
    /** Festival name by its cluster's first show id; see AppViewModel.resolveFestivalNames. */
    val festivalNames: Map<String, String> = emptyMap(),
    /**
     * The playlists made from a night, by that night's setlist id, oldest first.
     *
     * A list rather than one entry because a playlist url is not a local handle —
     * it is the thing you send someone. Converting a night a second time must not
     * overwrite the link a friend is already holding.
     *
     * Named apart from the `playlists` field it replaces so an existing cache still
     * parses: the old key is simply unknown now and ignored, where a changed type
     * under the same name would have failed to decode and dropped the timelines
     * with it.
     */
    val playlistsMade: Map<String, List<StoredPlaylist>> = emptyMap(),
    /**
     * How many shows setlist.fm says a user has attended, by username — not how many
     * we happen to hold. Without it a restored spine looks complete at whatever page
     * it got to, and there is no way to tell "you have all of them" from "you have
     * the first eighty", so paging back into your own history stops for good.
     */
    val attendedTotals: Map<String, Int> = emptyMap(),
    /**
     * The Reliver's own photos on a gig's single-night view, by setlist id — content
     * URIs from the system photo picker, stored as strings since Uri isn't
     * @Serializable. Replaced wholesale per setlist on every edit (add or remove),
     * unlike [playlistsMade]: there's no outside link to preserve, just the user's
     * current choice of pictures.
     */
    val photosBySetlist: Map<String, List<String>> = emptyMap(),
    /**
     * Where each song starts inside a night's full recording, in milliseconds, by
     * setlist id — one entry per song in setlist order, -1 for "not stamped yet".
     *
     * A positional list rather than a map keyed by song name: a set can play the same
     * song twice, and the running order is the only thing that tells the two apart.
     * Goes stale if the setlist is edited on setlist.fm afterwards; the length check
     * on read is what catches that.
     */
    val songOffsetsBySetlist: Map<String, List<Long>> = emptyMap(),
    /**
     * My attendance, by gig id — a setlist.fm id where the gig has one, or a
     * local id where it doesn't yet (#34): a gig sourced outside setlist.fm has
     * no vendor id until someone creates one, and may never get one, so the key
     * can't require it. Whatever id a gig is known by elsewhere on the timeline
     * is the id to use here too; this store doesn't mint or resolve ids itself.
     */
    val attendanceByGig: Map<String, StoredAttendance> = emptyMap(),
    /**
     * The gigs I hold a ticket for — the facts of a night that hasn't happened,
     * kept apart from [shows] on purpose. [shows] is what setlist.fm says a user
     * attended and is replaced wholesale per username on every import, so a planned
     * gig parked in there would be wiped by the next refresh; and it isn't attended,
     * so it must not be counted among the nights that were.
     *
     * My relationship to it still lives in [attendanceByGig] under the same id, with
     * provenance `planned` — this list is the record, that map is the claim.
     */
    val plannedShows: List<FmSetlist> = emptyList(),
    /**
     * The calendar event made for a gig I'm going to, by gig id → its content URI
     * (content://com.android.calendar/events/<id>). Presence is what "added" means;
     * the URI is what the gig screen opens with ACTION_VIEW. The event itself lives
     * in the OS calendar — this only holds the handle back to it, which the old
     * ACTION_INSERT intent could never give us. Its own field, not a provenance
     * value: adding a calendar entry says nothing about whether I was there (#29's
     * attendanceByGig owns that claim).
     *
     * Replaces the earlier `calendarAddedGigs` set. An older cache that still carries
     * that key just ignores it (ignoreUnknownKeys) and starts with this map empty —
     * no migration, and no real users to migrate.
     */
    val calendarEventByGig: Map<String, String> = emptyMap(),
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
        attendedTotals: Map<String, Int> = emptyMap(),
    ): Unit = writeMerged {
        it.copy(
            shows = it.shows + shows.filterValues { list -> list.isNotEmpty() },
            festivalNames = it.festivalNames + festivalNames,
            attendedTotals = it.attendedTotals + attendedTotals,
            // Appended, never replaced — see [TimelineCache.playlistsMade].
            // De-duped on url so re-recording the same playlist is a no-op.
            playlistsMade = it.playlistsMade + playlists.mapValues { (night, made) ->
                val had = it.playlistsMade[night].orEmpty()
                if (had.any { p -> p.url == made.url }) had else had + made
            },
        )
    }

    /** The Reliver's current set of photos for one gig, replacing whatever was there. */
    suspend fun savePhotos(setlistId: String, uris: List<String>): Unit = writeMerged {
        it.copy(photosBySetlist = it.photosBySetlist + (setlistId to uris))
    }

    /** A night's song start times inside its recording, replacing whatever was there. */
    suspend fun saveSongOffsets(setlistId: String, offsets: List<Long>): Unit = writeMerged {
        it.copy(songOffsetsBySetlist = it.songOffsetsBySetlist + (setlistId to offsets))
    }

    /**
     * My current attendance record for one gig, by [gigId] — a setlist.fm id where
     * one exists, otherwise a local id (see [TimelineCache.attendanceByGig]).
     * Replaces whatever was there for that gig, same as [savePhotos]: this is the
     * current state of one relationship, not an append-only log.
     */
    suspend fun saveAttendance(gigId: String, attendance: StoredAttendance): Unit = writeMerged {
        it.copy(attendanceByGig = it.attendanceByGig + (gigId to attendance))
    }

    /**
     * Adds a gig I'm going to, with the attendance claim that goes with it. One
     * write, because the record and the claim are useless apart: a planned gig
     * whose provenance didn't land would read as attended on the next launch.
     * Re-adding the same gig replaces its record rather than duplicating it.
     */
    suspend fun savePlanned(setlist: FmSetlist): Unit = writeMerged {
        it.copy(
            plannedShows = it.plannedShows.filterNot { s -> s.id == setlist.id } + setlist,
            // Never downgrades: re-storing the record when the night's setlist finally
            // lands must not throw away a check-in that happened in between.
            attendanceByGig = it.attendanceByGig + (
                setlist.id to (
                    it.attendanceByGig[setlist.id]
                        ?: StoredAttendance(provenance = StoredAttendance.Provenance.PLANNED)
                    )
                ),
        )
    }

    /**
     * Forgets a gig I'm no longer going to. Drops the attendance claim with it —
     * but only when it is still `planned`: a gig that has since been checked into
     * or attended is a night that happened, and removing it from the plans must
     * not quietly erase the evidence that I was there.
     */
    suspend fun removePlanned(gigId: String): Unit = writeMerged {
        val stillPlanned =
            it.attendanceByGig[gigId]?.provenance == StoredAttendance.Provenance.PLANNED
        it.copy(
            plannedShows = it.plannedShows.filterNot { s -> s.id == gigId },
            attendanceByGig = if (stillPlanned) it.attendanceByGig - gigId else it.attendanceByGig,
        )
    }

    /** Remembers the calendar event made for a gig, by its content URI. */
    suspend fun markCalendarAdded(gigId: String, eventUri: String): Unit = writeMerged {
        it.copy(calendarEventByGig = it.calendarEventByGig + (gigId to eventUri))
    }

    /**
     * Drops one playlist link from a night — the Spotify playlist itself was deleted
     * outside the app, so the pointer to it is now just dead weight.
     */
    suspend fun removePlaylist(setlistId: String, url: String): Unit = writeMerged {
        it.copy(
            playlistsMade = it.playlistsMade +
                (setlistId to it.playlistsMade[setlistId].orEmpty().filterNot { p -> p.url == url }),
        )
    }

    private suspend fun writeMerged(transform: (TimelineCache) -> TimelineCache): Unit =
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                val merged = transform(load())
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
