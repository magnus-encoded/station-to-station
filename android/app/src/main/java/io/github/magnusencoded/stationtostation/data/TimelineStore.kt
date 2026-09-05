package io.github.magnusencoded.stationtostation.data

import android.content.Context
import android.net.Uri
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
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
    /**
     * A ticket PDF's decoded QR, base64 (#411), kept even when the rest of that
     * PDF's parse failed (#413 needs it for the day-of view regardless). Base64
     * rather than a raw `ByteArray` field for the same reason [Friends.kt]'s public
     * keys are: kotlinx.serialization has no default codec for binary.
     */
    val ticketQr: String? = null,
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
 * One item on a night: a photo, a video, or a **Note** (#97, #50).
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
    /**
     * A **Note**'s text: what I wrote about the night (#50). Empty otherwise.
     *
     * A **Note** is **Media** rather than a record of its own, because ADR-0012 said
     * so — *"notes are media with a Personal bit"* — and because #162 built the only
     * thing that claim was missing: two **Bands**, with position carrying the bit.
     * Everything a note needs is inherited rather than re-implemented: a band, a
     * disposition, a drag that changes its mind, and arrival as **Received media**.
     */
    val text: String = "",
    /**
     * A **Verdict** on the night, carried by the **Note** it was written on: one of
     * [Verdict], or null for unset.
     *
     * On the note and not on the **Gig** so that its **Band** decides who reads it.
     * A verdict in the vault is mine, a verdict in the shared band travels, and
     * neither needed a rule written for it — which is the whole reason it lives
     * here rather than beside [StoredGig.setlistId].
     *
     * A string and not an enum, for the reason [kind] is one: an unknown value from
     * a future version must cost that field, never the night.
     */
    val verdict: String? = null,
) {
    object Kind {
        const val PHOTO = "photo"
        const val VIDEO = "video"
        /**
         * A **Note**: text, and no bytes at all. [ref] is empty and [pointer] is
         * null, which is why every path that resolves a reference has to skip it.
         */
        const val NOTE = "note"
        /** The reference was already dead when we looked. Not a guess. */
        const val UNKNOWN = "unknown"
    }

    /**
     * Thumb down, thumb up, or thumb up twice — and unset, which is a real state
     * and never rendered as a middling one.
     *
     * Three values because a concert does not support five. Stars and the Norwegian
     * die roll were both rejected: they invite a precision nobody has about a night,
     * and they look like a score that wants averaging. **Nothing aggregates one** —
     * that is the line ADR-0011 draws, and a verdict that could be averaged across
     * people would be the merit primitive it says needs solving first (#50).
     */
    object Verdict {
        const val DOWN = "down"
        const val UP = "up"
        const val DOUBLE_UP = "double_up"
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

/**
 * A **Festival**: an identity, not a shape (#166). It exists only when something
 * knows it — setlist.fm's own festival page, or an authored identity from the
 * **Programme** — and it is never inferred from a venue string and a date window.
 *
 * [id] is local and ours, the same "the identity is ours; the vendors' are
 * attributes" move #107 made for a **Gig**: [setlistFmSlug] and [mbid] are
 * enrichment that may land later or never, and storage never moves because of them.
 *
 * [rangeFrom]/[rangeTo] are dd-MM-yyyy, the shape setlist.fm sends everywhere else in
 * this app. Null when the range isn't known — an authored identity with no dates, or a
 * scrape that came back empty for that field, per ADR-0004's "every field degrades
 * independently to null".
 *
 * [dayMembership] is per-day membership from the source, date (dd-MM-yyyy) to the
 * setlist.fm ids that played that day — present only for a scraped identity whose
 * festival page carried it. `groupIntoFestivals` prefers this over the **Gigs** that
 * merely carry this identity's [id], because the source's own grouping is evidence
 * and "which gigs happen to be mine" is not: my attendance decides which nights I
 * see, never which nights belong to the festival.
 *
 * [setTimes] is when each act was scheduled to go on, `HH:mm`, by setlist.fm id — the
 * evidence for who played last, which is the question the headliner rule is really
 * asking. Present only where the festival page published them.
 *
 * [source] decides who wins on conflict: an authored identity is never overwritten
 * by a scrape, which is why [source] is carried on the record rather than inferred
 * from which fields are set.
 */
@Serializable
data class StoredFestival(
    val id: String = "",
    val name: String = "",
    val rangeFrom: String? = null,
    val rangeTo: String? = null,
    val setlistFmSlug: String? = null,
    val mbid: String? = null,
    val source: String = FestivalSource.SCRAPED,
    val dayMembership: Map<String, List<String>>? = null,
    val setTimes: Map<String, String>? = null,
) {
    /** Which source wins on conflict; see [source]. A plain string, for [StoredMedia.Kind]'s reason. */
    object FestivalSource {
        const val SCRAPED = "scraped"
        const val AUTHORED = "authored"
    }
}

/** The id a scraped **Festival** gets the first time its setlist.fm slug is seen. */
internal fun festivalIdForSlug(slug: String): String = uuidFrom("festival:$slug")

/**
 * **Precedence: setlist.fm, then the author, and the author wins.** An authored
 * identity is never overwritten by a scrape — what I know beats what was guessed at
 * upstream — which is a rule about the record and so lives in one place rather than at
 * each of the two seams that merge these.
 */
internal fun Map<String, StoredFestival>.mergedWith(
    found: Map<String, StoredFestival>,
): Map<String, StoredFestival> =
    this + found.filterKeys { this[it]?.source != StoredFestival.FestivalSource.AUTHORED }

/**
 * Every **Festival** identity this device knows, and which **Gigs** carry one.
 *
 * This is what `groupIntoFestivals` decides with, and it replaces the name map it used
 * to take (#166). The difference is the whole issue: a name keyed by a cluster's first
 * show could only ever *label* a shape the app had already inferred, so festivalhood
 * was arithmetic. An identity is evidence — it came from setlist.fm's own festival page
 * or from an identity authored through the **Programme** — and nothing else makes a
 * **Node** a **Festival**.
 */
data class Festivals(
    val byId: Map<String, StoredFestival> = emptyMap(),
    /** Festival id by the **Gig**'s setlist.fm id. */
    val idByShow: Map<String, String> = emptyMap(),
    /** Which **Gigs** have already been asked about. See [TimelineCache.festivalsAsked]. */
    val asked: Set<String> = emptySet(),
) {
    /**
     * Which identity each **Gig** belongs to, the source's own day grouping winning
     * over membership carried on the Gig: the festival page saying "these played on
     * the Thursday" is evidence, and "this is one of the nights I happened to attend"
     * is not. My attendance decides which nights I *see*, never which nights belong.
     */
    private val identityOfShow: Map<String, String> by lazy {
        idByShow + byId.values.flatMap { f ->
            f.dayMembership?.values?.flatten().orEmpty().map { it to f.id }
        }
    }

    /** The identity this **Gig** belongs to, or null — which is the common answer. */
    fun of(showId: String): StoredFestival? = identityOfShow[showId]?.let { byId[it] }

    fun isEmpty(): Boolean = byId.isEmpty()

    /** What was known, plus what has just been learned. See [mergedWith] for who wins. */
    operator fun plus(found: Festivals): Festivals = Festivals(
        byId = byId.mergedWith(found.byId),
        idByShow = idByShow + found.idByShow,
        asked = asked + found.asked,
    )
}

/** The identities as the timeline reads them. See [Festivals]. */
fun TimelineCache.festivalIdentities(): Festivals =
    Festivals(festivals, festivalIdByShow, festivalsAsked)

@Serializable
data class TimelineCache(
    /** Attended shows by setlist.fm username — mine and every friend's alike. */
    val shows: Map<String, List<FmSetlist>> = emptyMap(),
    /**
     * Festival name by its cluster's first show id.
     *
     * **Dead since #166**, which gave a **Festival** an identity of its own — [festivals]
     * keyed by an id the app owns, rather than a name filed under a key that moves the
     * moment the cluster's first show changes. Read once by [withFestivals], never
     * written again; kept declared so an existing cache still decodes and migrates.
     */
    val festivalNames: Map<String, String> = emptyMap(),
    /** Every **Festival** identity this app knows, by [StoredFestival.id]. See #166. */
    val festivals: Map<String, StoredFestival> = emptyMap(),
    /**
     * A **Gig**'s membership of a **Festival**, by the Gig's setlist.fm id — the
     * fallback `groupIntoFestivals` uses when [StoredFestival.dayMembership] doesn't
     * already say which nights belong to it.
     */
    val festivalIdByShow: Map<String, String> = emptyMap(),
    /**
     * The **Gigs** whose setlist.fm page has already been read for a **Festival**
     * identity, by setlist.fm id — *asked*, not *answered*.
     *
     * The same distinction matters here as everywhere a fetch can fail: "there is no
     * festival behind this night" is a correct, final answer, while "the page could
     * not be reached" is a question still open. Without it every multi-act night with
     * no festival costs a page fetch on every single launch, forever.
     */
    val festivalsAsked: Set<String> = emptySet(),
    /** Whether [withFestivals] has run. See [mediaTierMigrated] for why this is a flag. */
    val festivalsMigrated: Boolean = false,
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
    /**
     * The **Lines** tapped out of the legend, by setlist.fm username, to the moment
     * each was turned off — epoch millis (#396). The legend's recency order sorts by
     * this; a username absent from the map is active. Replaced wholesale on every
     * write, never merged: an unhide has to actually remove an entry, which a merge
     * that only adds could never do.
     */
    val hiddenLines: Map<String, Long> = emptyMap(),

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
    /**
     * My own **Log** of each night, by **Gig** id. Keyed like every other gig map, so
     * adoption moves nothing: the notes I took survive the night acquiring a
     * setlist.fm id, which is the entire point of keeping them apart from one.
     */
    val gigLogs: Map<String, StoredLog> = emptyMap(),
    /**
     * **Legacy, and read exactly once.** The nights whose **Media** was shared with my
     * **Audience** under #144's night-level grant.
     *
     * #162 removed that grant: there is one tier line now, it is
     * [StoredMedia.personal], and it is always set by an act. Nothing reads this any
     * more except [toBands], which needs it for one pass to tell a night somebody
     * actually shared from a night that merely defaulted to shareable — and then
     * empties it. The field stays declared because an old file on disk still carries
     * it, and dropping the declaration would silently discard the very answer the
     * upgrade depends on.
     */
    val sharedNights: Set<String> = emptySet(),
    /**
     * Whether [toBands] has run. Set once, never unset.
     *
     * A flag rather than an inference, because after the upgrade an unshared night
     * and a night whose media was all vaulted deliberately are indistinguishable —
     * so there would be nothing left to test to know whether to run it again.
     */
    val mediaTierMigrated: Boolean = false,
    /**
     * An artist's own song titles, by MusicBrainz id (#126).
     *
     * Kept **forever**, deliberately. MusicBrainz is CC0 and carries no cache clause, so
     * ADR-0005's rule does not bite, and the moment correction is wanted is standing in a
     * field with no signal — which is where the gig was. A catalogue that expired would
     * be absent exactly when it is needed.
     *
     * By mbid rather than by name, because a name is not an identity: two bands are
     * called Norma and only one of them played.
     */
    val catalogueByArtist: Map<String, List<String>> = emptyMap(),
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
    fun media(): Map<String, List<StoredMedia>> = gigMedia.combined(::unionMedia)
    fun attendance(): Map<String, StoredAttendance> = gigAttendance.combined(::unionAttendance)
    fun calendarEvents(): Map<String, String> = gigCalendarEvent.combined { kept, _ -> kept }
    fun playlists(): Map<String, List<StoredPlaylist>> = gigPlaylists.combined(::unionPlaylists)
    fun planned(): List<FmSetlist> = gigPlanned.values.toList()
    fun logs(): Map<String, StoredLog> = gigLogs.combined(::unionLog)

    /**
     * Re-keys a gig-keyed map to [keyOf], **combining** entries whose keys collide
     * rather than letting the last one win (#128).
     *
     * `mapKeys` was the obvious way to write this and it silently loses data: two
     * **Gigs** sharing one `setlistId` collapse to one key and the earlier value is
     * overwritten — a **Log** someone typed at the gig, or a night's photos, gone
     * from every screen with no error and no trace. A collision should be impossible
     * (a `setlistId` names one **Gig**; [TimelineStore.adoptSetlistId] merges rather
     * than letting a second claim exist), but a read path that discards a memory the
     * moment its assumption breaks is not a read path worth keeping.
     *
     * Oldest **Gig** first, so [union]'s first argument is the record a merge would
     * have kept — the same "older id wins" rule, so reading a collision and merging
     * it give the same answer.
     */
    private fun <V> Map<String, V>.combined(union: (V, V) -> V): Map<String, V> {
        val out = LinkedHashMap<String, V>(size)
        val oldestFirst = compareBy<Map.Entry<String, V>>({ gigs[it.key]?.createdAt ?: 0L }, { it.key })
        for ((gigId, value) in entries.sortedWith(oldestFirst)) {
            val key = keyOf(gigId)
            val kept = out[key]
            out[key] = if (kept == null) value else union(kept, value)
        }
        return out
    }
}

// The unions two records of one night combine by, shared by the merge that collapses
// them (TimelineStore.mergeGigs), the read that has to survive one that wasn't, and the
// device handover, which is this same combination across a whole timeline (#141).

/** Every photo and video from both, de-duped on the id each carries forever. */
internal fun unionMedia(kept: List<StoredMedia>, dropped: List<StoredMedia>): List<StoredMedia> =
    kept + dropped.filterNot { m -> kept.any { it.id == m.id } }

/** Every playlist link from both: a url is the thing you send someone, so none go. */
internal fun unionPlaylists(kept: List<StoredPlaylist>, dropped: List<StoredPlaylist>): List<StoredPlaylist> =
    kept + dropped.filterNot { p -> kept.any { it.url == p.url } }

/**
 * The longer **Log** survives, and stays **Open** unless both were **Closed** — a
 * merge must not upgrade a claim nobody made.
 */
internal fun unionLog(kept: StoredLog, dropped: StoredLog): StoredLog =
    (if (dropped.songs.size > kept.songs.size) dropped else kept)
        .copy(closed = kept.closed && dropped.closed)

/**
 * One claim about one night, and the stronger evidence wins: a check-in reached by
 * one route must not be flattened back to `planned` by the other. Never downgrades,
 * for the reason `savePlanned` never does.
 *
 * An unrecognised provenance ranks lowest rather than throwing — the field is a
 * plain string precisely so a newer app's value costs this one gig, not the cache.
 */
internal fun unionAttendance(kept: StoredAttendance, dropped: StoredAttendance): StoredAttendance =
    if (evidence(dropped.provenance) > evidence(kept.provenance)) dropped else kept

private fun evidence(provenance: String): Int = when (provenance) {
    StoredAttendance.Provenance.CHECKED_IN -> 2
    StoredAttendance.Provenance.ATTENDED -> 1
    else -> 0
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
        festivals: Map<String, StoredFestival> = emptyMap(),
        festivalIdByShow: Map<String, String> = emptyMap(),
        festivalsAsked: Set<String> = emptySet(),
        playlists: Map<String, StoredPlaylist> = emptyMap(),
        attendedTotals: Map<String, Int> = emptyMap(),
    ): Unit = writeMerged { cache ->
        var c = cache.copy(
            shows = cache.shows + shows.filterValues { list -> list.isNotEmpty() },
            festivalNames = cache.festivalNames + festivalNames,
            festivals = cache.festivals.mergedWith(festivals),
            festivalIdByShow = cache.festivalIdByShow + festivalIdByShow,
            festivalsAsked = cache.festivalsAsked + festivalsAsked,
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

    /** An artist's catalogue, kept for good. See [TimelineCache.catalogueByArtist]. */
    suspend fun saveCatalogue(mbid: String, titles: List<String>): Unit = writeMerged {
        if (mbid.isBlank() || titles.isEmpty()) it
        else it.copy(catalogueByArtist = it.catalogueByArtist + (mbid to titles))
    }

    /**
     * The legend's hidden set, replacing whatever was there wholesale (#396) — unlike
     * [save]'s merge, this has to be able to remove an entry, which is what an unhide
     * is.
     */
    suspend fun saveHiddenLines(hiddenLines: Map<String, Long>): Unit = writeMerged {
        it.copy(hiddenLines = hiddenLines)
    }

    /** The Reliver's current media for one gig, replacing whatever was there. */
    suspend fun saveMedia(setlistId: String, media: List<StoredMedia>): Unit = writeMerged {
        val (c, gigId) = it.withGig(setlistId)
        c.copy(gigMedia = c.gigMedia + (gigId to media))
    }

    /**
     * A Contact reconcile's [contactLanding], folded in. Unlike [saveMedia] this adds rather
     * than replaces — a Contact's offer is never the whole truth for a gig I already have my
     * own media on — via the same [unionMedia] every device-to-device merge uses, so an item
     * landing twice (once matched by hash, once later confirmed by id) never duplicates.
     */
    suspend fun mergeContactMedia(landing: Map<String, List<StoredMedia>>): Unit = writeMerged { c ->
        if (landing.isEmpty()) return@writeMerged c
        var out = c.gigMedia
        for ((gigId, items) in landing) out = out + (gigId to unionMedia(out[gigId].orEmpty(), items))
        c.copy(gigMedia = out)
    }

    /**
     * A device handover's union, written (#142). Unlike [mergeContactMedia] this is the
     * whole timeline rather than media alone: [HandoverPlan.merged] already *is* the two
     * devices combined — nights, **Log**, attendance, playlists — so all that is left is
     * putting it down.
     *
     * [plan] is a function rather than a plan, and runs inside the write lock against the
     * cache as it stands at that instant. A 4.6 GB transfer takes long enough for this
     * device's own timeline to have moved on, and writing a union computed against a cache
     * read before all that would silently discard whatever landed in between.
     */
    suspend fun applyHandover(plan: (TimelineCache) -> HandoverPlan): Unit = writeMerged { plan(it).merged }

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
     *
     * **Returns the claim it settled on**, which callers need rather than merely
     * may want. `plannedLane` draws a gig only if an attendance record says it is
     * planned, so a caller that writes the record here and does not put the same
     * claim into its own state has saved a night the timeline will not draw until
     * the next cold start. Two callers had done exactly that. Returning it is what
     * stops a third from inventing its own answer to a question this function has
     * already answered — including the never-downgrade rule below, which a caller
     * writing `Provenance.PLANNED` by hand would get wrong.
     */
    suspend fun savePlanned(setlist: FmSetlist): StoredAttendance {
        // Assigned inside the transform because that is where the existing claim is
        // visible, and read after because writeMerged holds the lock across it.
        var settled = StoredAttendance(provenance = StoredAttendance.Provenance.PLANNED)
        writeMerged {
            val (c, gigId) = it.withGig(setlist.id)
            // Never downgrades: re-storing the record when the night's setlist finally
            // lands must not throw away a check-in that happened in between.
            settled = c.gigAttendance[gigId]
                ?: StoredAttendance(provenance = StoredAttendance.Provenance.PLANNED)
            c.copy(
                gigPlanned = c.gigPlanned + (gigId to setlist),
                gigAttendance = c.gigAttendance + (gigId to settled),
            )
        }
        return settled
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
     * Attaches a ticket's decoded QR (#411) to whatever attendance record the gig
     * already has, or a fresh [StoredAttendance.Provenance.PLANNED] one if it has
     * none yet. Kept separate from [savePlanned] because the QR is preserved even
     * when the rest of a ticket's parse failed (#413's day-of view needs it
     * regardless) — there may be no artist/venue/date guess worth writing at all,
     * only a gig this QR is being attached to after the fact.
     *
     * Returns the settled record, same reason as [savePlanned]: a caller's own
     * state must reflect what was actually written, not reinvent it.
     */
    suspend fun attachTicketQr(gigId: String, qrBase64: String): StoredAttendance {
        var settled = StoredAttendance()
        writeMerged {
            val (c, id) = it.withGig(gigId)
            settled = (c.gigAttendance[id] ?: StoredAttendance()).copy(ticketQr = qrBase64)
            c.copy(gigAttendance = c.gigAttendance + (id to settled))
        }
        return settled
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
     *
     * **A `setlistId` names one Gig** (#128). If another **Gig** already holds this
     * one they are the same night, so this merges into the older record instead of
     * minting a second claim on the id — nothing else enforced it, and a duplicate
     * pair meant writes landing on one record while the reads came from the other.
     * The merge takes the union: no media, **Log**, check-in or playlist is lost to
     * the collapse.
     */
    suspend fun adoptSetlistId(gigId: String, setlistId: String): Boolean {
        var adopted = false
        writeMerged { cache ->
            val gig = cache.gigs[gigId]
            if (gig == null || gig.setlistId != null) return@writeMerged cache
            adopted = true
            val holder = cache.gigForSetlist(setlistId)
                ?: return@writeMerged cache.copy(
                    gigs = cache.gigs + (gigId to gig.copy(setlistId = setlistId)),
                )
            // The holder carries the id, so the survivor takes it either way round.
            cache.merging(gig, holder).first
        }
        return adopted
    }

    /**
     * Deletes a **Local** **Gig** and everything hanging off it — the mistyped
     * **Surprise**, the act tapped by accident. Returns whether it went.
     *
     * The only destructive operation in this store, so it is fenced by what it
     * refuses rather than by what it does:
     *
     * - **A gig with a setlist.fm id stays.** It is no longer only ours; it is a
     *   night other people's lines can meet at, and adoption is not undone by a
     *   long press.
     * - **A gig with any media stays, unless [withMedia].** Media is irreplaceable
     *   and a night someone photographed is not a mistap. [withMedia] is the
     *   deliberate delete from the night's own screen, where the person is looking
     *   at the night and means it; the mistap undo never passes it.
     *
     * Everything keyed by the gig goes together. A half-delete would leave an
     * attendance claim for a night that no longer exists, which is worse than
     * either outcome — `removePlanned` deliberately refuses to erase a check-in,
     * and that refusal is exactly what strands one here.
     */
    suspend fun deleteGig(gigId: String, withMedia: Boolean = false): Boolean {
        var deleted = false
        writeMerged { cache ->
            val id = cache.gigIdOrNull(gigId) ?: return@writeMerged cache
            val gig = cache.gigs[id] ?: return@writeMerged cache
            if (gig.setlistId != null) return@writeMerged cache
            if (!withMedia && cache.gigMedia[id].orEmpty().isNotEmpty()) return@writeMerged cache
            deleted = true
            cache.copy(
                gigs = cache.gigs - id,
                gigPlanned = cache.gigPlanned - id,
                gigAttendance = cache.gigAttendance - id,
                gigLogs = cache.gigLogs - id,
                gigMedia = cache.gigMedia - id,
                gigCalendarEvent = cache.gigCalendarEvent - id,
                gigPlaylists = cache.gigPlaylists - id,
                gigSongOffsets = cache.gigSongOffsets - id,
            )
        }
        return deleted
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
            val (merged, kept) = cache.merging(a, b)
            survivor = kept
            merged
        }
        return survivor
    }

    /**
     * My **Log** of one night, replacing whatever was there — this is the current
     * state of one observation, not an append-only journal. Nothing outside the app
     * ever writes here: **Publish** is one-way, and a setlist coming back from
     * setlist.fm must not touch it.
     */
    suspend fun saveLog(gigId: String, log: StoredLog): Unit = writeMerged {
        val (c, id) = it.withGig(gigId)
        c.copy(gigLogs = c.gigLogs + (id to log))
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
    withGigs().withMedia(mimeOf).withBands().withFestivals()

/**
 * #162's upgrade: the night-level grant becomes each item's own bit.
 *
 * Runs on read like every other migration here, so a backup or a sideload restored
 * later migrates too — and the flag rather than the data is what makes it idempotent,
 * since afterwards a vaulted night is indistinguishable from one that was never
 * shared. See [toBands] for why the direction is always toward the vault.
 */
private fun TimelineCache.withBands(): TimelineCache {
    if (mediaTierMigrated) return this
    return copy(
        gigMedia = gigMedia.mapValues { (gigId, media) -> toBands(media, gigId in sharedNights) },
        sharedNights = emptySet(),
        mediaTierMigrated = true,
    )
}

/**
 * #166's migration: a name filed under "whichever show happened to be first" becomes
 * an identity with its own id. [festivalNames] is read once here and never again — see
 * its own "Dead since #166" note — so this reconstructs the cluster the old venue+date
 * window would have drawn, purely to find which shows a preserved name belongs to.
 *
 * This is the *only* caller of [legacyClusterByVenueWindow]; the live seam
 * (`groupIntoFestivals` in FestivalGrammar.kt) never clusters on venue and date again.
 */
private fun TimelineCache.withFestivals(): TimelineCache {
    if (festivalsMigrated) return this
    if (festivalNames.isEmpty()) return copy(festivalsMigrated = true)
    val allShows = (shows.values.flatten() + gigPlanned.values).distinctBy { it.id }
    val clusters = legacyClusterByVenueWindow(allShows)
    val newFestivals = mutableMapOf<String, StoredFestival>()
    val newIdByShow = mutableMapOf<String, String>()
    for ((firstId, name) in festivalNames) {
        // By membership, not by `first()`: the old key was the *newest* show of the
        // cluster, because the lane grouped a newest-first list, while the replay
        // below sorts ascending. Matching on the head would migrate nothing at all.
        //
        // A name whose show is nowhere in the cache is dropped rather than filed
        // under a guessed identity — the name alone cannot say what it named.
        val cluster = clusters.firstOrNull { c -> c.any { it.id == firstId } } ?: continue
        val festivalId = uuidFrom("festival:$firstId")
        newFestivals[festivalId] = StoredFestival(
            id = festivalId,
            name = name,
            source = StoredFestival.FestivalSource.SCRAPED,
        )
        cluster.forEach { newIdByShow[it.id] = festivalId }
    }
    return copy(
        festivals = festivals + newFestivals,
        festivalIdByShow = festivalIdByShow + newIdByShow,
        festivalsMigrated = true,
    )
}

/**
 * Replays the pre-#166 clustering — same venue, within [LEGACY_FESTIVAL_WINDOW_DAYS] —
 * so [withFestivals] can find which shows an already-resolved name belongs to. Not the
 * live grouping rule any more; kept private to this migration only.
 */
private fun legacyClusterByVenueWindow(setlists: List<FmSetlist>): List<List<FmSetlist>> {
    val sorted = setlists.sortedBy { it.localDate() }
    val clusters = mutableListOf<List<FmSetlist>>()
    var i = 0
    while (i < sorted.size) {
        val cluster = mutableListOf(sorted[i])
        var j = i + 1
        while (j < sorted.size && legacySameFestival(cluster.last(), sorted[j])) {
            cluster.add(sorted[j])
            j++
        }
        clusters.add(cluster)
        i = j
    }
    return clusters
}

private const val LEGACY_FESTIVAL_WINDOW_DAYS = 4L

private fun legacySameFestival(a: FmSetlist, b: FmSetlist): Boolean {
    val venueA = a.venue?.name ?: return false
    val venueB = b.venue?.name ?: return false
    if (!venueA.equals(venueB, ignoreCase = true)) return false
    val da = a.localDate() ?: return false
    val db = b.localDate() ?: return false
    return kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(da, db)) <= LEGACY_FESTIVAL_WINDOW_DAYS
}

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
 * Fills in the facts of any **Gig** that is missing one, from a setlist.fm record
 * already in the cache. A gig minted by attaching a photo knows only its id and its
 * setlist id; this is what makes it a night — a date, an artist, a venue — as soon as
 * the import that describes it arrives, in whichever order the two happen.
 *
 * **Any** missing fact, not only a missing date (#128). A **Gig** minted from a
 * planned **Departures** row knows its date and artist the moment it is made and
 * may have no venue at all. Gating on a blank date
 * meant that when such a night was later adopted onto its setlist.fm record — the one
 * event that finally knows the room — the venue was passed over and stayed blank
 * forever. Each field fills only if blank, so this never overwrites what is known.
 */
private fun TimelineCache.withGigFacts(): TimelineCache {
    if (gigs.isEmpty()) return this
    val known = (shows.values.flatten() + gigPlanned.values).associateBy { it.id }
    val filled = gigs.mapValues { (_, gig) ->
        val wanting = gig.date.isBlank() || gig.artist.isBlank() || gig.venue.isBlank()
        val fm = gig.setlistId?.takeIf { wanting }?.let(known::get)
            ?: return@mapValues gig
        gig.copy(
            date = gig.date.ifBlank { fm.eventDate.orEmpty() },
            artist = gig.artist.ifBlank { fm.artist?.name.orEmpty() },
            venue = gig.venue.ifBlank { fm.venue?.name.orEmpty() },
        )
    }
    return if (filled == gigs) this else copy(gigs = filled)
}

/**
 * [a] and [b] are the same night: one record, and the older id wins. Returns the
 * cache with the two collapsed, and the id that survived.
 *
 * Its own function rather than living inside `mergeGigs` because adoption needs it
 * too — a **Gig** taking a `setlistId` another one already holds *is* this case, and
 * two implementations of "combine two nights" is how one of them ends up dropping a
 * map the other unions.
 */
private fun TimelineCache.merging(a: StoredGig, b: StoredGig): Pair<TimelineCache, String> {
    // createdAt, then the id itself, so two devices merging the same pair
    // reach the same answer without a synchronised clock.
    val older = if (a.createdAt != b.createdAt) {
        if (a.createdAt < b.createdAt) a else b
    } else {
        if (a.id < b.id) a else b
    }
    val gone = if (older.id == a.id) b else a
    return copy(
        gigs = gigs - gone.id + (
            older.id to older.copy(
                setlistId = older.setlistId ?: gone.setlistId,
                date = older.date.ifBlank { gone.date },
                artist = older.artist.ifBlank { gone.artist },
                venue = older.venue.ifBlank { gone.venue },
            )
            ),
        // Photos and playlists are collections of separate things, so the union is
        // every one of them. The rest are one current value per night, where the
        // survivor's own answer is the one to keep — except attendance, where the
        // stronger claim is, so a check-in cannot be flattened by a tie-break.
        gigMedia = gigMedia.folded(older.id, gone.id, ::unionMedia),
        gigPlaylists = gigPlaylists.folded(older.id, gone.id, ::unionPlaylists),
        gigSongOffsets = gigSongOffsets.folded(older.id, gone.id) { k, _ -> k },
        gigAttendance = gigAttendance.folded(older.id, gone.id, ::unionAttendance),
        gigCalendarEvent = gigCalendarEvent.folded(older.id, gone.id) { k, _ -> k },
        gigPlanned = gigPlanned.folded(older.id, gone.id) { k, _ -> k },
        gigLogs = gigLogs.folded(older.id, gone.id, ::unionLog),
    ) to older.id
}

/** Moves [drop]'s entry onto [keep], combining the two with [union] if both exist. */
private fun <V> Map<String, V>.folded(keep: String, drop: String, union: (V, V) -> V): Map<String, V> {
    val dropped = this[drop] ?: return this
    val kept = this[keep]
    return this - drop + (keep to if (kept == null) dropped else union(kept, dropped))
}
