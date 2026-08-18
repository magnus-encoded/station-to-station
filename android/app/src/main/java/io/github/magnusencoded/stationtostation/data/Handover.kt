package io.github.magnusencoded.stationtostation.data

import kotlinx.serialization.Serializable

/**
 * Two devices, one **Line**: what should happen when the same person's other phone
 * offers its timeline (#141).
 *
 * Not **Reconcile**. That word is taken, and taken for a different operation — the
 * pairwise sync between two *Contacts*, over the whole shared band rather than nights
 * both **Attended** (#28, #102–#104, #257). This is a **handover** between two devices belonging to one
 * person, so it is a union of everything rather than an intersection of what is
 * shared, and the far end is not a stranger.
 *
 * The whole decision is [handoverPlan], and it is pure: no radio, no socket, no file,
 * no clock. It says what should happen; applying it is a separate, dumb step. That
 * split is the point — every rule below is assertable on a laptop.
 */

/** A **Gig**'s facts, media, logs and attendance: the timeline itself. */
const val CATEGORY_SETLISTS = "setlists"

/**
 * What the source ticked. Media splits four ways rather than two, because
 * **Personal** and shared media are separate checkboxes on the source side: sending
 * everything you ever marked personal must be a distinct act from sending your photos.
 */
internal fun categoryOf(kind: String, personal: Boolean): String =
    if (personal) "personal_$kind" else kind

/**
 * One photo or video the source is offering.
 *
 * Self-contained on purpose: this is the wire format, and it has to be evaluable
 * before the timeline it describes is trusted. [id] is identity — a UUID assigned at
 * **Attach** and carried forever (#97) — and [hash] is resolution and integrity. Two
 * different jobs, deliberately not conflated.
 *
 * [hash] is whole-file for a photo and sampled for a video: head and tail bytes plus
 * the size. Reading 233 MB to decide whether to send 233 MB is the wrong trade, and
 * hashing *decoded frames* instead would be worse than either — YUV to RGB rounding
 * differs between SoCs, so the same video on two phones can hash differently and the
 * resolver degrades silently into "send the bytes anyway". How the bytes are sampled
 * is the sender's business; this only ever compares two of them for equality.
 */
@Serializable
data class OfferedMedia(
    val id: String = "",
    /** The **Gig** it hangs off, in the source's own ids. */
    val gigId: String = "",
    val kind: String = StoredMedia.Kind.PHOTO,
    val hash: String = "",
    val bytes: Long = 0L,
    val capturedAt: Long? = null,
    val personal: Boolean = false,
    /**
     * Whose camera it came from — null for my own devices, a **Contact**'s public key
     * when they are the one offering (#144).
     *
     * In the envelope from the first version even though a device handover makes the
     * answer trivially "me", because it is one of the two fields that cannot be
     * recovered later: once a **Contact**'s photographs are mingled into someone's
     * nights with no attribution, which were whose is unrecoverable.
     */
    val from: String? = null,
    /**
     * A **Note**'s text, inline (#50).
     *
     * **The one kind that arrives complete with the manifest.** A note has no bytes,
     * so it skips the manifest-then-bytes phase entirely: [hash] is empty, [bytes] is
     * zero, and there is nothing to drain afterwards. That makes it the most durable
     * thing here — below even the **Thumbnail** on the floor, because a **Pointer**
     * can rot and a sentence cannot.
     */
    val text: String = "",
    /** The **Verdict** riding that note, or null. See `StoredMedia.Verdict`. */
    val verdict: String? = null,
) {
    val category: String get() = categoryOf(kind, personal)
}

/**
 * What the far end is offering: its timeline, and a description of every item on it.
 *
 * [counts] is declared per category by the source and signed with everything else, so
 * a manifest whose item list has been truncated in transit is visible rather than
 * looking like a smaller library.
 */
@Serializable
data class HandoverManifest(
    val timeline: TimelineCache = TimelineCache(),
    val media: List<OfferedMedia> = emptyList(),
    val counts: Map<String, Int> = emptyMap(),
    /**
     * Who I am: which setlist.fm user, which Spotify account. **Records, not secrets**
     * (#143), so they travel with the records whether or not accounts are being moved —
     * the new phone knowing who it is costs nothing in blast radius.
     *
     * There is deliberately no field here for a credential, and there must never be one.
     * A bearer secret travels only in [AccountsPayload], as its own acknowledged step, so
     * that no combination of ticked media categories can move one as a side effect.
     */
    val identities: Identities = Identities(),
)

/**
 * A candidate already on this device, from the receiver's own gallery.
 *
 * The caller narrows candidates by capture time before hashing any of them — the same
 * date matching that already drives the night's media strip — so this is a short list,
 * never the whole gallery. Narrowing is a prefilter and nothing more: the match itself
 * is by [hash], because a timestamp alone would happily grab a neighbouring frame from
 * the same minute.
 */
data class GalleryItem(val ref: String, val hash: String)

/**
 * What should happen. Data, not effects.
 *
 * [merged] holds everything already resolvable — my timeline, unioned with theirs,
 * with locally-matched media attached from my own gallery. Items in [request] are
 * deliberately *not* in it: their bytes have not arrived, and a **Media** record whose
 * reference points at nothing is exactly the dead reference #97 exists to prevent.
 * They join as they land.
 *
 * [withheld] and [refused] are kept apart because they mean different things: the
 * source did not offer this, versus I will not take it.
 */
data class HandoverPlan(
    val merged: TimelineCache = TimelineCache(),
    /** Media id → the reference to my own copy. No bytes cross the wire for these. */
    val fromGallery: Map<String, String> = emptyMap(),
    /** Media ids to ask for. */
    val request: List<String> = emptyList(),
    /** Media ids I already hold under the same id. */
    val held: List<String> = emptyList(),
    /** Media ids whose category the source did not allow, or whose night is not here. */
    val withheld: List<String> = emptyList(),
    /** Media ids blocked by hash. The bytes never transfer. */
    val refused: List<String> = emptyList(),
    /** Per category, how many items should end up here when this is applied. */
    val expected: Map<String, Int> = emptyMap(),
    /** The manifest's own counts disagree with the items it lists. */
    val countMismatch: Boolean = false,
    /**
     * Their **Gigs** with no `setlistId` and no id of mine to land on. These duplicate,
     * because a **Bill**-minted night has a random per-device id and ADR-0002's
     * `artist|venue|day` key is not implemented. Surfaced rather than solved.
     */
    val unkeyed: List<String> = emptyList(),
)

/**
 * The handover decision.
 *
 * [verified] is the verdict on the manifest's signature, reached by the transport
 * (#142) and passed in rather than computed here, so this function stays free of
 * crypto and of anything that can fail. False yields an empty plan: a manifest that
 * does not verify writes *nothing*, because the highest-stakes bit in the payload is
 * [OfferedMedia.personal] — flipping it exposes something marked **Personal** with
 * nothing in the UI to say so — and the second is which night an item attaches to.
 *
 * The operation is a **union**, never a copy of the larger side. Neither device is a
 * superset: the old phone has the history, the new one may hold the only copy of last
 * night. Picking a winner by volume is the same silent discard as #128's projection
 * collapse, one level up.
 *
 * Nothing is removed from the source. Publishing is not moving, so the old phone stays
 * a complete second copy for free. (Accounts are the one exception, and they are #143.)
 */
fun handoverPlan(
    mine: TimelineCache,
    offer: HandoverManifest,
    allow: Set<String>,
    verified: Boolean,
    refusedHashes: Set<String> = emptySet(),
    gallery: List<GalleryItem> = emptyList(),
): HandoverPlan {
    if (!verified) return HandoverPlan()

    val facts = CATEGORY_SETLISTS in allow
    val (gigs, rename) = mine.absorbingGigs(offer.timeline, facts)

    val mineIds = mine.gigMedia.values.flatten().mapTo(HashSet()) { it.id }
    val byHash = gallery.associateBy { it.hash }

    val held = ArrayList<String>()
    val withheld = ArrayList<String>()
    val refused = ArrayList<String>()
    val request = ArrayList<String>()
    val fromGallery = LinkedHashMap<String, String>()
    for (item in offer.media) when {
        item.hash in refusedHashes -> refused += item.id
        item.category !in allow -> withheld += item.id
        // A photo whose night did not come across has nowhere to hang. One rule, and
        // it covers both a source that offered media without setlists and a manifest
        // naming a gig it did not send.
        rename[item.gigId] == null -> withheld += item.id
        item.id in mineIds -> held += item.id
        else -> {
            val match = byHash[item.hash]
            if (match != null) fromGallery[item.id] = match.ref else request += item.id
        }
    }

    // Attribution comes off the manifest, not off the record: the sender's own copy
    // says `from = null` because it is theirs, and it is *mine* that has to remember it
    // was not. My media and received media stay distinguishable at every layer.
    val attribution = offer.media.associate { it.id to it.from }
    val landing = offer.timeline.gigMedia
        .mapValues { (_, list) ->
            list.mapNotNull { m ->
                fromGallery[m.id]?.let { m.copy(ref = it, from = attribution[m.id] ?: m.from) }
            }
        }
        .filterValues { it.isNotEmpty() }

    val expected = offer.media
        .filter { it.id in fromGallery || it.id in request || it.id in held }
        .groupingBy { it.category }
        .eachCount()

    return HandoverPlan(
        merged = mine.absorbing(offer.timeline, gigs, rename, landing, facts),
        fromGallery = fromGallery,
        request = request,
        held = held,
        withheld = withheld,
        refused = refused,
        expected = expected,
        countMismatch = offer.counts.isNotEmpty() &&
            offer.counts != offer.media.groupingBy { it.category }.eachCount(),
        unkeyed = offer.timeline.gigs.values
            .filter { it.setlistId == null && !mine.gigs.containsKey(it.id) }
            .map { it.id },
    )
}

/**
 * Their **Gigs** landed on mine, and the id translation that lands everything else.
 *
 * Two records of one night collapse and **the older id wins**, by `createdAt` and then
 * by the id itself — so two devices reach the same answer with no synchronised clock,
 * exactly as [TimelineCache] merges a pair within one device. The rename table covers
 * *both* directions, because the survivor can be theirs: when it is, my own maps have
 * to move onto their id too.
 *
 * Nights sourced from setlist.fm need no matching cleverness — both devices derive the
 * same gig id from the same setlist id — but a night adopted on only one of them will
 * have two ids for one `setlistId`, which is the case this matches.
 */
private fun TimelineCache.absorbingGigs(
    theirs: TimelineCache,
    facts: Boolean,
): Pair<Map<String, StoredGig>, Map<String, String>> {
    val rename = HashMap<String, String>()
    var out = gigs
    if (!facts) {
        // Media only: their items may still land, but only on nights I already have.
        for (id in theirs.gigs.keys) if (out.containsKey(id)) rename[id] = id
        return out to rename
    }
    for ((theirId, theirGig) in theirs.gigs) {
        val ours = theirGig.setlistId
            ?.let { sid -> out.values.firstOrNull { it.setlistId == sid } }
            ?: out[theirId]
        if (ours == null) {
            out = out + (theirId to theirGig)
            rename[theirId] = theirId
            continue
        }
        val older = if (ours.createdAt != theirGig.createdAt) {
            if (ours.createdAt < theirGig.createdAt) ours else theirGig
        } else {
            if (ours.id <= theirGig.id) ours else theirGig
        }
        val gone = if (older.id == ours.id) theirGig else ours
        val kept = older.copy(
            setlistId = older.setlistId ?: gone.setlistId,
            date = older.date.ifBlank { gone.date },
            artist = older.artist.ifBlank { gone.artist },
            venue = older.venue.ifBlank { gone.venue },
        )
        out = out - ours.id - theirId + (kept.id to kept)
        rename[theirId] = kept.id
        if (ours.id != kept.id) rename[ours.id] = kept.id
    }
    return out to rename
}

/**
 * The union itself, map by map, through the same unions a merge within one device uses
 * (`unionMedia`, `unionAttendance`, `unionLog`, `unionPlaylists`).
 *
 * Reusing them is not tidiness. Two implementations of "combine two nights" is how one
 * of them ends up quietly dropping a map the other unions — a **Log** someone typed at
 * a gig, gone with no error and no trace.
 */
private fun TimelineCache.absorbing(
    theirs: TimelineCache,
    gigs: Map<String, StoredGig>,
    rename: Map<String, String>,
    media: Map<String, List<StoredMedia>>,
    facts: Boolean,
): TimelineCache {
    fun <V> join(theirMap: Map<String, V>, mineMap: Map<String, V>, union: (V, V) -> V): Map<String, V> {
        val out = LinkedHashMap<String, V>(mineMap.size + theirMap.size)
        // Mine first, so it is the `kept` side of every union — the same position the
        // survivor takes when one device merges a pair of its own.
        for ((k, v) in mineMap) {
            val key = rename[k] ?: k
            out[key] = out[key]?.let { union(it, v) } ?: v
        }
        for ((k, v) in theirMap) {
            val key = rename[k] ?: continue
            out[key] = out[key]?.let { union(it, v) } ?: v
        }
        return out
    }

    val merged = copy(
        gigs = gigs,
        gigMedia = join(media, gigMedia, ::unionMedia),
    )
    if (!facts) return merged
    return merged.copy(
        gigAttendance = join(theirs.gigAttendance, gigAttendance, ::unionAttendance),
        gigLogs = join(theirs.gigLogs, gigLogs, ::unionLog),
        gigPlaylists = join(theirs.gigPlaylists, gigPlaylists, ::unionPlaylists),
        // One current value per night, and mine is the one I am standing in front of.
        gigPlanned = join(theirs.gigPlanned, gigPlanned) { k, _ -> k },
        gigCalendarEvent = join(theirs.gigCalendarEvent, gigCalendarEvent) { k, _ -> k },
        gigSongOffsets = join(theirs.gigSongOffsets, gigSongOffsets) { k, _ -> k },
        shows = (shows.keys + theirs.shows.keys).associateWith { user ->
            val kept = shows[user].orEmpty()
            kept + theirs.shows[user].orEmpty().filterNot { s -> kept.any { it.id == s.id } }
        },
        bills = theirs.bills + bills,
        festivalNames = theirs.festivalNames + festivalNames,
        attendedTotals = (attendedTotals.keys + theirs.attendedTotals.keys).associateWith { user ->
            maxOf(attendedTotals[user] ?: 0, theirs.attendedTotals[user] ?: 0)
        },
    )
}
