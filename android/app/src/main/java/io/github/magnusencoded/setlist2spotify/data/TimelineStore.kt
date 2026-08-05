package io.github.magnusencoded.setlist2spotify.data

import android.content.Context
import android.net.Uri
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
import java.security.MessageDigest

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

/**
 * One night, as *this app* knows it — the identity everything else hangs off (#107).
 *
 * The setlist.fm id is an attribute here, not the key. #28 made exactly this change
 * for people ("the public key is the identity; setlistfm becomes a nullable
 * attribute") and named the same gap for events; this is that half. A night from a
 * poster in a window has no vendor id and may never get one, and once media (#97)
 * hangs off a gig the data is irreplaceable, so a key that can change — or that a
 * night can fail to have — is a key that can orphan a keepsake.
 *
 * [setlistId] is still the correspondence key *between people*: two devices assign
 * different local ids to the same night, so **Crossings** and anything cross-person
 * resolve through it. A local-only Gig is local-only by design (#34 accepts this).
 *
 * [createdAt] exists for one rule: two local gigs found to be the same night merge,
 * and the older id wins. Migrated gigs carry 0 — they predate everything minted
 * since, and every device agrees on that without a clock.
 */
@Serializable
data class StoredGig(
    // Defaulted for the same reason every other field here is: a cache missing one
    // field should cost that field, never the whole timeline.
    val id: String = "",
    /** dd-MM-yyyy, the shape setlist.fm sends. Blank until the facts are known. */
    val date: String = "",
    val artist: String = "",
    val venue: String = "",
    /** Null for a night setlist.fm has never heard of. Set once, by adoption (#34). */
    val setlistId: String? = null,
    /** Epoch millis. 0 means "came in with the migration". */
    val createdAt: Long = 0L,
)

/**
 * One photo or video on a night (#97).
 *
 * Before this, **Attach** stored a raw gallery URI and copied nothing, so the app
 * owned no bytes: tidying the gallery, reinstalling, switching to "Select photos…"
 * or letting Google Photos free up space each emptied a night with nothing deleted.
 * A `List<Uri>` also had nowhere to put a capture time, a **Pointer**, the
 * **Personal** bit, provenance, or a stable id — every planned feature needed a
 * field that shape could not hold.
 *
 * [id] is assigned by the owner at **Attach** and carried forever: it names the
 * thumbnail files #98 writes, and it is what makes any future sync idempotent —
 * the same item arriving twice is one item. A UUID and not a content hash: hashing
 * full-res means reading a 233 MB recording at attach time, and the dedup a hash
 * would buy only applies to the same bytes attached twice, which is rare.
 *
 * [kind] is *stored*, not sniffed from the reference at read time. Asking the
 * ContentResolver for a MIME type works right up until the reference dies — which
 * is the entire premise of this record.
 */
@Serializable
data class StoredMedia(
    val id: String = "",
    /** [Kind]. A plain string, not an enum, for the reason `provenance` is one. */
    val kind: String = Kind.PHOTO,
    /** The local reference: a content URI on Android, an asset id on iOS. */
    val ref: String = "",
    /** When the camera took it — not when it was attached. Null when unknowable. */
    val capturedAt: Long? = null,
    /**
     * Whose camera it came from: a **Contact**'s public key, per #28 — the key is
     * the identity. Null means mine. **My media** and **Received media** must stay
     * distinguishable at every layer above this.
     */
    val from: String? = null,
    /** **Personal**: attached, but never sent. One bit, default off. */
    val personal: Boolean = false,
    /**
     * A **Pointer** into the owner's own cloud. A single nullable string, because
     * sharing is deferred (#101–#104 are parked) — this holds an absolute URL and
     * nothing more. A folder-relative form, if #100 ever calls for one, is an
     * additive field rather than a reshape.
     */
    val pointer: String? = null,
    /**
     * For a video: where each song starts *inside this recording*, in milliseconds,
     * one entry per song in setlist order, `-1` for "not stamped yet".
     *
     * On the record and not on the night, because a night with two recordings has
     * to put the second one's stamps somewhere (#27). Positional rather than keyed
     * by song name: a set can play the same song twice, and the running order is
     * the only thing that tells the two apart. Local to the recording — "two
     * seconds into the video that song starts" is the whole of what is observed,
     * and a recording's absolute start is not knowable in general.
     */
    val songOffsets: List<Long> = emptyList(),
) {
    object Kind {
        const val PHOTO = "photo"
        const val VIDEO = "video"
        /** The reference was already dead when we looked. Not a guess. */
        const val UNKNOWN = "unknown"
    }
}

/**
 * A UUID derived from [name] rather than drawn at random — RFC 4122 version 5, the
 * SHA-1 flavour.
 *
 * Random would have been less code, and wrong: the same old cache has to migrate to
 * the same ids on Android and on iOS, or a user with both phones ends up with two
 * histories of the same nights. Deriving it also makes the migration idempotent and
 * lets both platforms' tests assert *fixed* expected ids rather than "some uuid",
 * so neither can drift by agreeing with itself.
 */
internal fun uuidFrom(name: String): String {
    val h = MessageDigest.getInstance("SHA-1").digest(name.toByteArray(Charsets.UTF_8))
    h[6] = ((h[6].toInt() and 0x0f) or 0x50).toByte() // version 5
    h[8] = ((h[8].toInt() and 0x3f) or 0x80).toByte() // RFC 4122 variant
    val hex = h.take(16).joinToString("") { "%02x".format(it) }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
        "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
}

/** What a reference's MIME type is, while the reference may still answer. */
private fun mimeResolver(context: Context): (String) -> String? =
    { ref -> runCatching { context.contentResolver.getType(Uri.parse(ref)) }.getOrNull() }

/** The id a **Gig** gets the first time it is seen through a setlist.fm id. */
internal fun gigIdForSetlistId(setlistId: String): String = uuidFrom("gig:$setlistId")

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
     *
     * Dead since #107. See [gigPlaylists].
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
     *
     * Dead since #107: read once by the migration, never written again. See [gigPhotos].
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
     *
     * Dead since #107. See [gigSongOffsets].
     */
    val songOffsetsBySetlist: Map<String, List<Long>> = emptyMap(),
    /**
     * My attendance, by gig id — a setlist.fm id where the gig has one, or a
     * local id where it doesn't yet (#34): a gig sourced outside setlist.fm has
     * no vendor id until someone creates one, and may never get one, so the key
     * can't require it. Whatever id a gig is known by elsewhere on the timeline
     * is the id to use here too; this store doesn't mint or resolve ids itself.
     *
     * Dead since #107, which made that "whatever id" a real record. See [gigAttendance].
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
     *
     * Dead since #107. See [gigPlanned].
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
     *
     * Dead since #107. See [gigCalendarEvent].
     */
    val calendarEventByGig: Map<String, String> = emptyMap(),

    // --- Keyed by the app's own Gig id (#107) ---------------------------------
    //
    // Every map above that was keyed by a night is re-keyed here, and the six of
    // them moved together on purpose: a half-migration leaves two identity schemes
    // and is worse than either. New keys rather than changed value shapes, per the
    // playlistsMade precedent — the old keys stay in the format, are read exactly
    // once by [migrated], and are never written again, so an older build still
    // round-trips its own cache instead of failing to decode ours.

    /** Every night this app knows about, by its own id. See [StoredGig]. */
    val gigs: Map<String, StoredGig> = emptyMap(),
    /**
     * Replaced [photosBySetlist]; dead in turn since #97, which gave media a record
     * instead of a bare reference. Read once by the migration, never written again.
     * See [gigMedia].
     */
    val gigPhotos: Map<String, List<String>> = emptyMap(),
    /**
     * Replaced [songOffsetsBySetlist]; dead in turn since #97, which moved offsets
     * onto the video they belong to. See [StoredMedia.songOffsets].
     */
    val gigSongOffsets: Map<String, List<Long>> = emptyMap(),
    /** Replaces [attendanceByGig]. */
    val gigAttendance: Map<String, StoredAttendance> = emptyMap(),
    /** Replaces [calendarEventByGig]. */
    val gigCalendarEvent: Map<String, String> = emptyMap(),
    /** Replaces [playlistsMade]. */
    val gigPlaylists: Map<String, List<StoredPlaylist>> = emptyMap(),
    /**
     * Replaces [plannedShows]. A map rather than a list because the gig id is now
     * the identity; the value is unchanged, and the order it used to carry was
     * re-sorted on read anyway (AppViewModel.sortedPlanned).
     */
    val gigPlanned: Map<String, FmSetlist> = emptyMap(),
    /**
     * The media on each night, by **Gig** id, in the order the user arranged it
     * (#97). No sort field on the record: deriving and correcting a night's
     * arrangement is #75's whole subject, and a speculative field would prejudge it.
     */
    val gigMedia: Map<String, List<StoredMedia>> = emptyMap(),
) {
    /**
     * The id this gig is known by *outside* the store: its setlist.fm id where it
     * has one, otherwise its own. Exactly the convention [attendanceByGig] already
     * documented ("a setlist.fm id where the gig has one, or a local id where it
     * doesn't yet"), which is why the screens above need no re-keying — adoption
     * changes what this returns for one gig and moves no data at all.
     */
    fun keyOf(gigId: String): String = gigs[gigId]?.let { it.setlistId ?: it.id } ?: gigId

    /**
     * Given a setlist.fm id — from a friend's timeline, say — the local **Gig**.
     *
     * ponytail: a scan, not a second index. A collection is hundreds of nights and
     * this runs on write. Add a reverse map when a scan is actually felt.
     */
    fun gigForSetlist(setlistId: String): StoredGig? =
        gigs.values.firstOrNull { it.setlistId == setlistId }

    /** The other direction: given a local **Gig**, its setlist.fm record's id. */
    fun setlistIdFor(gigId: String): String? = gigs[gigId]?.setlistId

    // What the screens read: the gig-keyed maps, back under the id the UI uses.
    fun media(): Map<String, List<StoredMedia>> = gigMedia.mapKeys { keyOf(it.key) }
    fun attendance(): Map<String, StoredAttendance> = gigAttendance.mapKeys { keyOf(it.key) }
    fun calendarEvents(): Map<String, String> = gigCalendarEvent.mapKeys { keyOf(it.key) }
    fun playlists(): Map<String, List<StoredPlaylist>> = gigPlaylists.mapKeys { keyOf(it.key) }
    fun planned(): List<FmSetlist> = gigPlanned.values.toList()
}

/**
 * [file] rather than a Context only so the merge can be tested on the JVM.
 *
 * [mimeOf] is how #97's migration learns whether an old bare reference was a photo
 * or a video, in the last moment that reference may still be alive. Null off-device.
 */
class TimelineStore(
    private val file: File,
    private val mimeOf: ((String) -> String?)? = null,
) {

    constructor(context: Context) : this(
        File(context.filesDir, "timelines.json"),
        // A named function, not a lambda written here: a lambda inside a delegating
        // constructor call reads as capturing `this`, which does not exist yet.
        mimeResolver(context),
    )

    // encodeDefaults so an empty cache round-trips; ignoreUnknownKeys so a field
    // added to FmSetlist doesn't make an existing cache unreadable.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // save() is read-modify-write and three call sites fire independently (my import,
    // the friend lanes, the festival names) — without this, two overlapping saves
    // both read the old cache and the loser's writes vanish.
    private val writeLock = Mutex()

    /**
     * The cache as last written, with #107's migration applied. Empty (never null)
     * on first run or unreadable file.
     *
     * Migrating on read rather than in a one-shot upgrade step: there is no schema
     * version to hang one off, and this way an old cache restored onto the device
     * later (a backup, a sideload) migrates too. It is a no-op once [TimelineCache.gigs]
     * is populated, which the first write after a migration makes permanent.
     */
    suspend fun load(): TimelineCache = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext TimelineCache()
        runCatching { json.decodeFromString<TimelineCache>(file.readText()) }
            .getOrDefault(TimelineCache())
            .migrated(mimeOf)
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
    ): Unit = writeMerged { cache ->
        var c = cache.copy(
            shows = cache.shows + shows.filterValues { list -> list.isNotEmpty() },
            festivalNames = cache.festivalNames + festivalNames,
            attendedTotals = cache.attendedTotals + attendedTotals,
        )
        for ((night, made) in playlists) {
            val (resolved, gigId) = c.withGig(night)
            // Appended, never replaced — see [TimelineCache.gigPlaylists].
            // De-duped on url so re-recording the same playlist is a no-op.
            val had = resolved.gigPlaylists[gigId].orEmpty()
            c = resolved.copy(
                gigPlaylists = resolved.gigPlaylists +
                    (gigId to if (had.any { p -> p.url == made.url }) had else had + made),
            )
        }
        c
    }

    /** The Reliver's current media for one gig, replacing whatever was there. */
    suspend fun saveMedia(setlistId: String, media: List<StoredMedia>): Unit = writeMerged {
        val (c, gigId) = it.withGig(setlistId)
        c.copy(gigMedia = c.gigMedia + (gigId to media))
    }

    /**
     * Where each song starts inside one recording, replacing whatever was there.
     *
     * By media id, not by night: a night with two recordings has two answers, and
     * before #97 the second one had nowhere to live. A stamp for a video that is no
     * longer attached is dropped rather than resurrecting the record.
     */
    suspend fun saveSongOffsets(mediaId: String, offsets: List<Long>): Unit = writeMerged { cache ->
        val gigId = cache.gigMedia.entries
            .firstOrNull { (_, media) -> media.any { it.id == mediaId } }
            ?.key
            ?: return@writeMerged cache
        cache.copy(
            gigMedia = cache.gigMedia + (
                gigId to cache.gigMedia.getValue(gigId)
                    .map { if (it.id == mediaId) it.copy(songOffsets = offsets) else it }
                ),
        )
    }

    /**
     * My current attendance record for one gig, by [gigId] — a setlist.fm id where
     * one exists, otherwise a local id (see [TimelineCache.keyOf]).
     * Replaces whatever was there for that gig, same as [saveMedia]: this is the
     * current state of one relationship, not an append-only log.
     */
    suspend fun saveAttendance(gigId: String, attendance: StoredAttendance): Unit = writeMerged {
        val (c, id) = it.withGig(gigId)
        c.copy(gigAttendance = c.gigAttendance + (id to attendance))
    }

    /**
     * Adds a gig I'm going to, with the attendance claim that goes with it. One
     * write, because the record and the claim are useless apart: a planned gig
     * whose provenance didn't land would read as attended on the next launch.
     * Re-adding the same gig replaces its record rather than duplicating it.
     */
    suspend fun savePlanned(setlist: FmSetlist): Unit = writeMerged {
        val (c, gigId) = it.withGig(setlist.id)
        c.copy(
            gigPlanned = c.gigPlanned + (gigId to setlist),
            // Never downgrades: re-storing the record when the night's setlist finally
            // lands must not throw away a check-in that happened in between.
            gigAttendance = c.gigAttendance + (
                gigId to (
                    c.gigAttendance[gigId]
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
    suspend fun removePlanned(gigId: String): Unit = writeMerged { cache ->
        val id = cache.gigIdOrNull(gigId) ?: return@writeMerged cache
        val stillPlanned =
            cache.gigAttendance[id]?.provenance == StoredAttendance.Provenance.PLANNED
        cache.copy(
            gigPlanned = cache.gigPlanned - id,
            gigAttendance = if (stillPlanned) cache.gigAttendance - id else cache.gigAttendance,
        )
    }

    /** Remembers the calendar event made for a gig, by its content URI. */
    suspend fun markCalendarAdded(gigId: String, eventUri: String): Unit = writeMerged {
        val (c, id) = it.withGig(gigId)
        c.copy(gigCalendarEvent = c.gigCalendarEvent + (id to eventUri))
    }

    /**
     * A night setlist.fm has never heard of — the poster in the window, the small
     * venue nobody catalogues. Returns the id everything else keys by; it is also
     * the id the screens use, until [adoptSetlistId] gives the night a vendor one.
     *
     * Random rather than derived: there is no setlist.fm id to derive from, and the
     * facts are exactly what cannot be trusted as a key (venues get renamed, artists
     * rename, festival days split) — that is why the natural key was rejected.
     */
    suspend fun createLocalGig(date: String, artist: String, venue: String): String {
        val id = java.util.UUID.randomUUID().toString()
        writeMerged {
            it.copy(
                gigs = it.gigs + (
                    id to StoredGig(
                        id = id,
                        date = date,
                        artist = artist,
                        venue = venue,
                        createdAt = it.nextCreatedAt(),
                    )
                    ),
            )
        }
        return id
    }

    /**
     * A night that setlist.fm has now catalogued takes their id (#34's search found
     * the match; this is all that is left to do). One field on one record — no data
     * moves, because nothing was ever keyed by the vendor id.
     *
     * Refuses a gig that already has one: two setlist.fm ids for one night is a bug
     * upstream, not a merge case, and silently overwriting would hide it. Returns
     * whether the id was taken.
     */
    suspend fun adoptSetlistId(gigId: String, setlistId: String): Boolean {
        var adopted = false
        writeMerged { cache ->
            val gig = cache.gigs[gigId]
            if (gig == null || gig.setlistId != null) return@writeMerged cache
            adopted = true
            cache.copy(gigs = cache.gigs + (gigId to gig.copy(setlistId = setlistId)))
        }
        return adopted
    }

    /**
     * Two records found to be the same night become one — the case where a night
     * added by hand is later also imported.
     *
     * The older id wins, and the survivor takes the union: nothing a merge touches
     * may cost the user a photo, a check-in or a playlist link. Returns the id that
     * survived, or null if either gig is unknown.
     */
    suspend fun mergeGigs(gigIdA: String, gigIdB: String): String? {
        var survivor: String? = null
        writeMerged { cache ->
            val a = cache.gigs[gigIdA]
            val b = cache.gigs[gigIdB]
            if (a == null || b == null || a.id == b.id) return@writeMerged cache
            // createdAt, then the id itself, so two devices merging the same pair
            // reach the same answer without a synchronised clock.
            val older = if (a.createdAt != b.createdAt) {
                if (a.createdAt < b.createdAt) a else b
            } else {
                if (a.id < b.id) a else b
            }
            val gone = if (older.id == a.id) b else a
            survivor = older.id
            cache.copy(
                gigs = cache.gigs - gone.id + (
                    older.id to older.copy(
                        setlistId = older.setlistId ?: gone.setlistId,
                        date = older.date.ifBlank { gone.date },
                        artist = older.artist.ifBlank { gone.artist },
                        venue = older.venue.ifBlank { gone.venue },
                    )
                    ),
                // Photos and playlists are collections of separate things, so the
                // union is every one of them. The rest are one current value per
                // night, where the survivor's own answer is the one to keep.
                gigMedia = cache.gigMedia.folded(older.id, gone.id) { k, d ->
                    k + d.filterNot { m -> k.any { it.id == m.id } }
                },
                gigPlaylists = cache.gigPlaylists.folded(older.id, gone.id) { k, d ->
                    k + d.filterNot { p -> k.any { it.url == p.url } }
                },
                gigSongOffsets = cache.gigSongOffsets.folded(older.id, gone.id) { k, _ -> k },
                gigAttendance = cache.gigAttendance.folded(older.id, gone.id) { k, _ -> k },
                gigCalendarEvent = cache.gigCalendarEvent.folded(older.id, gone.id) { k, _ -> k },
                gigPlanned = cache.gigPlanned.folded(older.id, gone.id) { k, _ -> k },
            )
        }
        return survivor
    }

    /**
     * Drops one playlist link from a night — the Spotify playlist itself was deleted
     * outside the app, so the pointer to it is now just dead weight.
     */
    suspend fun removePlaylist(setlistId: String, url: String): Unit = writeMerged { cache ->
        val id = cache.gigIdOrNull(setlistId) ?: return@writeMerged cache
        cache.copy(
            gigPlaylists = cache.gigPlaylists +
                (id to cache.gigPlaylists[id].orEmpty().filterNot { p -> p.url == url }),
        )
    }

    private suspend fun writeMerged(transform: (TimelineCache) -> TimelineCache): Unit =
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                val merged = transform(load()).withGigFacts()
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

/**
 * #107's migration: every map that was keyed by a night gets re-keyed to a **Gig**
 * the app owns, and one record per night appears to hang them off.
 *
 * All six move at once, deliberately — a half-migration leaves two identity schemes
 * and is worse than either. Nothing is deleted: the old keys keep their values and
 * are simply never written again, so an older build reading this file still finds
 * everything where it left it.
 *
 * Every old key is taken to be a setlist.fm id. The old comment on `attendanceByGig`
 * allowed for a local id there too, but #34 — the only thing that would ever have
 * minted one — was never built, so no cache in existence contains one.
 */
internal fun TimelineCache.migrated(mimeOf: ((String) -> String?)? = null): TimelineCache =
    withGigs().withMedia(mimeOf)

private fun TimelineCache.withGigs(): TimelineCache {
    if (gigs.isNotEmpty()) return this
    val oldKeys = LinkedHashSet<String>().apply {
        addAll(photosBySetlist.keys)
        addAll(songOffsetsBySetlist.keys)
        addAll(attendanceByGig.keys)
        addAll(calendarEventByGig.keys)
        addAll(playlistsMade.keys)
        addAll(plannedShows.map { it.id })
    }
    if (oldKeys.isEmpty()) return this
    // One id per *distinct* night, so a setlist id appearing in five maps lands on
    // one Gig with five associations rather than five gigs with one each.
    val idOf = oldKeys.associateWith(::gigIdForSetlistId)
    return copy(
        gigs = idOf.entries.associate { (old, id) -> id to StoredGig(id = id, setlistId = old) },
        gigPhotos = photosBySetlist.mapKeys { idOf.getValue(it.key) },
        gigSongOffsets = songOffsetsBySetlist.mapKeys { idOf.getValue(it.key) },
        gigAttendance = attendanceByGig.mapKeys { idOf.getValue(it.key) },
        gigCalendarEvent = calendarEventByGig.mapKeys { idOf.getValue(it.key) },
        gigPlaylists = playlistsMade.mapKeys { idOf.getValue(it.key) },
        gigPlanned = plannedShows.associateBy { idOf.getValue(it.id) },
    ).withGigFacts()
}

/**
 * #97's migration: a bare gallery reference becomes a record with an identity, and
 * a night's song stamps move onto the recording they describe.
 *
 * [mimeOf] resolves a reference's MIME type while it is still alive — the one
 * moment kind can still be learned, since a dead reference is exactly what this
 * record exists to survive. Absent (the JVM tests, and iOS, which cannot resolve an
 * Android content URI at all), kind falls back to the reference's extension and
 * then to `unknown`; a wrong guess would be worse than an honest one.
 *
 * The offsets rule is **exactly one video, or nothing**. A night whose media holds
 * one video takes its stamps; a night with none or with two leaves the old entry
 * untouched in the dead key rather than guessing, because a wrong guess silently
 * mis-stamps a recording and nothing is lost by declining.
 */
private fun TimelineCache.withMedia(mimeOf: ((String) -> String?)?): TimelineCache {
    if (gigMedia.isNotEmpty() || gigPhotos.isEmpty()) return this
    val media = gigPhotos.mapValues { (gigId, refs) ->
        refs.map { ref ->
            StoredMedia(
                // Derived, like the gig ids, so both platforms migrate one cache to
                // one set of ids — and so #98's thumbnail filenames are stable.
                id = uuidFrom("media:$gigId:$ref"),
                kind = kindOf(ref, mimeOf),
                ref = ref,
            )
        }
    }
    return copy(
        gigMedia = media.mapValues { (gigId, items) ->
            val offsets = gigSongOffsets[gigId] ?: return@mapValues items
            val videos = items.filter { it.kind == StoredMedia.Kind.VIDEO }
            if (videos.size != 1) return@mapValues items
            items.map { if (it.id == videos[0].id) it.copy(songOffsets = offsets) else it }
        },
    )
}

private fun kindOf(ref: String, mimeOf: ((String) -> String?)?): String {
    val mime = mimeOf?.invoke(ref)
    return when {
        mime?.startsWith("video/") == true -> StoredMedia.Kind.VIDEO
        mime?.startsWith("image/") == true -> StoredMedia.Kind.PHOTO
        // A picker URI usually has no extension, so this catches the copies the app
        // made for itself and little else. Honest ignorance beats a guess.
        ref.extension() in VIDEO_EXTENSIONS -> StoredMedia.Kind.VIDEO
        ref.extension() in PHOTO_EXTENSIONS -> StoredMedia.Kind.PHOTO
        else -> StoredMedia.Kind.UNKNOWN
    }
}

/** The last path segment's extension, lowercased — "" when there isn't one. */
private fun String.extension(): String = substringAfterLast('/').substringAfterLast('.', "").lowercase()

private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "m4v", "3gp", "mkv", "webm")
private val PHOTO_EXTENSIONS = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif")

/**
 * The **Gig** [key] names, minting one if this is the first thing ever hung off that
 * night. [key] is what the screens use — a setlist.fm id, or a gig id for a night
 * setlist.fm has never heard of.
 *
 * ponytail: minted on demand rather than at import. A Gig record for a night with
 * nothing attached to it holds nothing the FmSetlist doesn't already, and minting
 * here happens under the write lock, so two writes for the same night can't race
 * into two gigs. Mint at import when #34 needs a night to exist before anything
 * hangs off it.
 */
private fun TimelineCache.withGig(key: String): Pair<TimelineCache, String> {
    gigIdOrNull(key)?.let { return this to it }
    // The same derivation the migration uses, so attaching to a night here and
    // migrating a cache that already knew it produce one id, not two.
    val gig = StoredGig(
        id = gigIdForSetlistId(key),
        setlistId = key,
        createdAt = nextCreatedAt(),
    )
    return copy(gigs = gigs + (gig.id to gig)) to gig.id
}

/**
 * The stamp a new **Gig** gets: the clock, unless the clock has not moved since the
 * last one — two gigs created in the same millisecond would otherwise be the same
 * age, and "the older id wins" needs an answer for every pair. Strictly increasing
 * records the order they were created in, which is the thing the rule actually means.
 */
private fun TimelineCache.nextCreatedAt(): Long =
    maxOf(System.currentTimeMillis(), (gigs.values.maxOfOrNull { it.createdAt } ?: 0L) + 1)

/** The Gig [key] names, or null — the read-side of [withGig], which mints nothing. */
private fun TimelineCache.gigIdOrNull(key: String): String? =
    gigForSetlist(key)?.id ?: key.takeIf { gigs.containsKey(it) }

/**
 * Fills in the facts of any **Gig** that has none, from a setlist.fm record already
 * in the cache. A gig minted by attaching a photo knows only its id and its setlist
 * id; this is what makes it a night — a date, an artist, a venue — as soon as the
 * import that describes it arrives, in whichever order the two happen.
 */
private fun TimelineCache.withGigFacts(): TimelineCache {
    if (gigs.isEmpty()) return this
    val known = (shows.values.flatten() + gigPlanned.values).associateBy { it.id }
    val filled = gigs.mapValues { (_, gig) ->
        val fm = gig.setlistId?.takeIf { gig.date.isBlank() }?.let(known::get)
            ?: return@mapValues gig
        gig.copy(
            date = fm.eventDate.orEmpty(),
            artist = fm.artist?.name.orEmpty(),
            venue = fm.venue?.name.orEmpty(),
        )
    }
    return if (filled == gigs) this else copy(gigs = filled)
}

/** Moves [drop]'s entry onto [keep], combining the two with [union] if both exist. */
private fun <V> Map<String, V>.folded(keep: String, drop: String, union: (V, V) -> V): Map<String, V> {
    val dropped = this[drop] ?: return this
    val kept = this[keep]
    return this - drop + (keep to if (kept == null) dropped else union(kept, dropped))
}
