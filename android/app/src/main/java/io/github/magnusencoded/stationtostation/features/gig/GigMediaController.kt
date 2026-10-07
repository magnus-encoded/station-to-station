package io.github.magnusencoded.stationtostation.features.gig

import android.graphics.Bitmap
import android.net.Uri
import io.github.magnusencoded.stationtostation.CoverCandidate
import io.github.magnusencoded.stationtostation.MediaThumb
import io.github.magnusencoded.stationtostation.NOT_STAMPED
import io.github.magnusencoded.stationtostation.UiState
import io.github.magnusencoded.stationtostation.data.Band
import io.github.magnusencoded.stationtostation.data.StoredMedia
import io.github.magnusencoded.stationtostation.data.TimelineStore
import io.github.magnusencoded.stationtostation.data.bandsOf
import io.github.magnusencoded.stationtostation.data.moveMedia
import io.github.magnusencoded.stationtostation.data.photos.PhotoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The **Tour**'s claim on one attach, taken before the copy. [keep] runs [save] and reports
 * what was attached, or returns false, without saving, when the claim has lapsed.
 */
fun interface AttachClaim {
    suspend fun keep(fresh: List<StoredMedia>, band: Band, save: suspend () -> Unit): Boolean
}

/**
 * A gig's keepsakes: attaching, moving between **Bands**, removing, song stamps and
 * the pictures drawn for them. Reads and writes [UiState] only through [state] and
 * [update].
 */
class GigMediaController(
    private val state: () -> UiState,
    private val update: ((UiState) -> UiState) -> Unit,
    private val timelines: TimelineStore,
    private val photos: PhotoRepository,
    private val scope: CoroutineScope,
    private val claimAttach: (String) -> AttachClaim? = { null },
) {

    /**
     * Moves one of my photographs into [band] at [index] — the drag between bands, and
     * the reorder within one, which are the same operation.
     *
     * A move between bands *is* the change to its **Personal** bit; there is no separate
     * gesture and no night-level grant above it. **Received media** is refused by
     * [moveMedia] rather than here: whose disposition it is belongs with the rule, not
     * with the caller.
     */
    fun moveGigMedia(setlistId: String, mediaId: String, band: Band, index: Int) = setGigMedia(
        setlistId,
        moveMedia(state().mediaBySetlist[setlistId].orEmpty(), mediaId, band, index),
    )

    /**
     * The Reliver's own pictures pinned to a gig, chosen freely from the system photo
     * picker rather than matched by date — this is "my picture of that night", not the
     * same-night search a playlist cover does.
     */
    fun addGigPhotos(setlistId: String, uris: List<Uri>, band: Band = Band.VAULT) {
        val claim = claimAttach(setlistId)
        scope.launch {
            val had = state().mediaBySetlist[setlistId].orEmpty()
            val wanted = uris.filterNot { u -> had.any { it.ref == u.toString() } }
            attach(setlistId, had, wanted.map { it to it }, band, claim)
        }
    }

    /**
     * Same as [addGigPhotos], but for uris fresh out of the system photo picker: those
     * only grant read access for the running process, so they're copied into our own
     * storage first — otherwise the keepsake goes blank the next time the app launches.
     *
     * Kind and capture time are read off the *picked* uri, before the copy: that is
     * the one moment the gallery is guaranteed to answer. The copy is what the record
     * points at afterwards.
     */
    fun addPickedGigPhotos(setlistId: String, uris: List<Uri>, band: Band = Band.VAULT) {
        val claim = claimAttach(setlistId)
        scope.launch {
            val had = state().mediaBySetlist[setlistId].orEmpty()
            attach(
                setlistId,
                had,
                uris.mapNotNull { picked -> photos.persistCopy(picked)?.let { it to picked } },
                band,
                claim,
            )
        }
    }

    /**
     * Attaches [wanted] — each a `stored reference to read the facts from` pair,
     * which differ when the app has just copied the picked item into its own
     * storage. Generates both thumbnail tiers first, and drops anything whose
     * durable copy could not be written: an item with no floor under it is a
     * keepsake that will silently empty later, so a failure is said out loud here
     * rather than discovered in 2035.
     *
     * ponytail: sequential, which is the bounded queue — twenty photos at once is
     * the normal case, and one at a time on the IO dispatcher keeps the app
     * responsive without a scheduler. Widen it if attaching a night's worth ever
     * feels slow.
     */
    private suspend fun attach(
        setlistId: String,
        had: List<StoredMedia>,
        wanted: List<Pair<Uri, Uri>>,
        band: Band,
        claim: AttachClaim?,
    ) {
        val fresh = mutableListOf<StoredMedia>()
        var failed = 0
        for ((ref, from) in wanted) {
            val id = java.util.UUID.randomUUID().toString()
            if (!photos.generateThumbnails(id, from)) {
                failed++
                continue
            }
            fresh += StoredMedia(
                id = id,
                kind = if (photos.isVideo(from)) StoredMedia.Kind.VIDEO else StoredMedia.Kind.PHOTO,
                ref = ref.toString(),
                capturedAt = photos.capturedAtMs(from),
                // The band the handle was released over *is* the answer. There is no
                // default path into this: every caller names one.
                personal = band == Band.VAULT,
            )
        }
        // Normalised through the bands so a fresh item lands at the end of its own
        // run rather than after somebody else's media.
        if (fresh.isNotEmpty()) {
            val media = bandsOf(had + fresh).let { it.shared + it.received + it.vault }
            if (claim == null) {
                setGigMedia(setlistId, media)
            } else if (!claim.keep(fresh, band) { saveGigMedia(setlistId, media) }) {
                fresh.forEach { photos.deleteOwnedBytes(it.id, it.ref) }
            }
        }
        if (failed > 0) {
            update {
                it.copy(
                    error = "Couldn't read ${if (failed == 1) "that one" else "$failed of those"} — not attached.",
                    errorKind = null,
                )
            }
        }
    }

    fun removeGigPhoto(setlistId: String, uri: Uri) {
        val had = state().mediaBySetlist[setlistId].orEmpty()
        val (gone, kept) = had.partition { it.ref == uri.toString() }
        setGigMedia(setlistId, kept)
        // Removing means removing: the derived copies this app owns go with it.
        scope.launch { gone.forEach { photos.deleteThumbnails(it.id) } }
    }

    /**
     * Where each song of [setlistId] starts in its recording, padded/trimmed to
     * [songCount]. Sized on read rather than trusted from disk: the setlist can be
     * edited on setlist.fm after a night was stamped, and a stored list of the old
     * length would otherwise shift every song's time by one.
     */
    fun songOffsets(mediaId: String?, songCount: Int): List<Long> {
        val stored = mediaId?.let { id ->
            state().mediaBySetlist.values.firstNotNullOfOrNull { media ->
                media.firstOrNull { it.id == id }
            }
        }?.songOffsets.orEmpty()
        return List(songCount) { stored.getOrElse(it) { NOT_STAMPED } }
    }

    /**
     * Records that song [index] starts at [atMs] in the night's recording, or clears
     * it with [NOT_STAMPED].
     *
     * Only this one song moves. The recording and the setlist need not hold the same
     * songs — a clip setlist.fm left out sits in the gap between two stamps — so
     * nothing may be inferred about its neighbours from one stamp.
     */
    fun stampSong(mediaId: String, index: Int, atMs: Long, songCount: Int) {
        val offsets = songOffsets(mediaId, songCount).toMutableList()
        if (index !in offsets.indices) return
        offsets[index] = atMs
        update {
            it.copy(
                mediaBySetlist = it.mediaBySetlist.mapValues { (_, media) ->
                    media.map { m -> if (m.id == mediaId) m.copy(songOffsets = offsets) else m }
                },
            )
        }
        scope.launch { timelines.saveSongOffsets(mediaId, offsets) }
    }

    private suspend fun saveGigMedia(setlistId: String, media: List<StoredMedia>) {
        update { it.copy(mediaBySetlist = it.mediaBySetlist + (setlistId to media)) }
        timelines.saveMedia(setlistId, media)
    }

    private fun setGigMedia(setlistId: String, media: List<StoredMedia>) {
        update { it.copy(mediaBySetlist = it.mediaBySetlist + (setlistId to media)) }
        scope.launch { timelines.saveMedia(setlistId, media) }
    }

    /**
     * Same same-night gallery search a playlist cover does, offered here as one-tap
     * adds to the gig's keepsakes instead of a single chosen cover. Silent when
     * permission is missing, for the same reason: the prompt only ever follows a tap,
     * never just opening the gig.
     */
    fun loadGigPhotoSuggestions() {
        val date = state().selectedSetlist?.localDate() ?: return
        val granted = photos.hasPermission()
        update { it.copy(gigPhotoSuggestionsPermissionGranted = granted) }
        if (!granted) return
        scope.launch {
            update { it.copy(gigPhotoSuggestionsLoading = true) }
            val found = photos.photosFrom(date)
            val candidates = found.map { CoverCandidate(it.uri, photos.preview(it.uri)) }
            update {
                it.copy(
                    gigPhotoSuggestions = candidates,
                    gigPhotoSuggestionsLoading = false,
                    gigPhotoSuggestionsSearched = true,
                )
            }
        }
    }

    /**
     * The grid picture for a gig keepsake.
     *
     * The **durable floor** first: the copy the app owns is the thing still
     * here when the gallery reference is not, and reading a 30–60 KB JPEG is also
     * why the strip draws instantly rather than decoding a 12 MP original per cell.
     * The source is the fallback only for media attached before thumbnails existed.
     */
    suspend fun photoPreview(uri: Uri): MediaThumb {
        val record = mediaFor(uri)
        val bitmap = record?.let { photos.gridThumbnail(it.id) } ?: photos.preview(uri, sizePx = 320)
        return MediaThumb(bitmap, record?.kind?.let { it == StoredMedia.Kind.VIDEO } ?: photos.isVideo(uri))
    }

    private fun mediaFor(uri: Uri): StoredMedia? =
        state().mediaBySetlist.values.firstNotNullOfOrNull { media ->
            media.firstOrNull { it.ref == uri.toString() }
        }

    /** Whether a gig keepsake is a video clip rather than a photo — cheap metadata
     *  lookup, checked before opening the in-app viewer so it knows which to show. */
    fun isVideo(uri: Uri): Boolean = photos.isVideo(uri)

    /** A bigger decode of the same photo, for the in-app viewer rather than the
     *  strip's thumbnail. */
    suspend fun fullPhoto(uri: Uri): Bitmap? {
        val record = mediaFor(uri)
        // Cache tier, then the source, then the floor. The cache tier is already
        // full-screen quality and costs one small local read; the grid tier at the
        // end is what makes a lost original degrade to *slightly soft* rather than
        // to nothing. Absent cache is a normal state — nothing here depends on it.
        return record?.let { photos.cachedFullThumbnail(it.id) }
            ?: photos.preview(uri, sizePx = 1600)
            ?: record?.let { photos.gridThumbnail(it.id) }
    }
}
