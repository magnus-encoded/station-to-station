package io.github.magnusencoded.stationtostation.data

import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import kotlinx.serialization.Serializable

/**
 * The other pairwise sync (#257): two **Contacts**, same WiFi, whatever's sitting in the
 * shared band that the far end is still missing. Not [handoverPlan] — that is a union of
 * one person's whole timeline across their own devices; this is the opposite trust model,
 * over a manifest [contactManifest] has already narrowed to exactly what a Contact may see.
 *
 * That narrowing is why this is so much smaller than [handoverPlan]: there is no category
 * allow-list to apply and no gig to decide is off-limits — [offer] arrives pre-filtered, so
 * every item in it is one this Contact is entitled to. What's left is only "do I already
 * have this."
 */
data class ContactReconcilePlan(
    /** Media ids I already hold under the same id. */
    val held: List<String> = emptyList(),
    /** Media id → the reference to my own copy, matched by hash. No bytes cross the wire for these. */
    val fromGallery: Map<String, String> = emptyMap(),
    /**
     * Media ids that are already complete: a **Note** is text and a **Verdict**, and both
     * rode the manifest. There is nothing to fetch, so asking for them would be asking for
     * zero bytes and then dropping the note when zero bytes arrived — which is what this
     * did before, on both platforms.
     */
    val noBytes: List<String> = emptyList(),
    /** Media ids to ask for. */
    val request: List<String> = emptyList(),
    /**
     * The **Nights** they offered that their **Lane** on this phone does not hold yet
     * (#405) — hand-logged and imported alike, because where a Night came from stops
     * mattering once it is theirs. Complete as they stand, like a **Note**: nothing is
     * fetched for them. See [landNights] for where they go.
     */
    val nights: List<FmSetlist> = emptyList(),
)

/**
 * Whether a media id from a peer is safe to use as an identity and, downstream, as a
 * **filename**.
 *
 * A media id is a UUID this app minted at **Attach** (#97) — but an id arriving over the
 * wire is whatever the far end chose to send, and it reaches
 * [io.github.magnusencoded.stationtostation.data.photos.PhotoRepository.receivedMediaFile]
 * as a path component. `File(dir, name)` resolves `..` like any other path, so an id of
 * `../../…` would write outside the directory it was meant for.
 *
 * Checked at the one door every peer-supplied id comes through rather than at each of
 * those call sites: a check that has to be remembered three times is a check that will be
 * forgotten once. An allow-list, for the reason `isPlausibleSetlistFmUser` is one — the
 * interesting characters are the ones nobody thought of. iOS's twin, character for
 * character, is `isSafeMediaId` in `ContactReconcile.swift`.
 */
fun isSafeMediaId(id: String): Boolean =
    id.isNotEmpty() && id.length <= 64 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

/**
 * The LAN reconcile decision. Pure: no radio, no socket, no clock — the same split
 * [handoverPlan] makes, for the same reason.
 *
 * [verified] is the challenge-response outcome (signature over a nonce, checked against
 * the Contact's persisted [Friend.publicKey]), reached by the caller and passed in rather
 * than computed here. False yields an empty plan — the same fail-safe posture as
 * [handoverPlan]: a peer that hasn't proven who they are gets nothing.
 *
 * Idempotent by construction: running it twice against the same [mine]/[offer] yields the
 * same plan, which is what lets an Exchange visit simply re-diff on every discovery rather
 * than track any session state of its own.
 */
fun contactReconcilePlan(
    mine: TimelineCache,
    offer: HandoverManifest,
    verified: Boolean,
    gallery: List<GalleryItem> = emptyList(),
    /** The Lane I already hold for this Contact — whatever [laneKey] files it under. */
    heldLane: List<FmSetlist> = emptyList(),
): ContactReconcilePlan {
    if (!verified) return ContactReconcilePlan()

    // Held by id, the one thing that says two records are one Night. A Night without an
    // id is no Night, and one offered twice is taken once.
    val heldNights = heldLane.mapTo(HashSet()) { it.id }
    val nights = offer.nights.filter { it.id.isNotBlank() && heldNights.add(it.id) }

    val mineIds = mine.gigMedia.values.flatten().mapTo(HashSet()) { it.id }
    // Empty hashes excluded, which is not tidiness: a **Note** has no bytes and hashes to
    // nothing, and so does anything the hasher could not read. Without this, every one of
    // them matches whichever unhashable thing the gallery happened to list first, and a
    // note lands wearing a photograph's ref.
    val byHash = gallery.filter { it.hash.isNotEmpty() }.associateBy { it.hash }

    val held = ArrayList<String>()
    val noBytes = ArrayList<String>()
    val request = ArrayList<String>()
    val fromGallery = LinkedHashMap<String, String>()
    for (item in offer.media) when {
        !isSafeMediaId(item.id) -> Unit
        item.id in mineIds -> held += item.id
        item.kind == StoredMedia.Kind.NOTE -> noBytes += item.id
        else -> byHash[item.hash]?.let { fromGallery[item.id] = it.ref } ?: run { request += item.id }
    }

    return ContactReconcilePlan(held = held, fromGallery = fromGallery,
                                noBytes = noBytes, request = request, nights = nights)
}

/**
 * The dumb half of [contactReconcilePlan]: turns resolved items into what
 * [TimelineStore.mergeContactMedia] should write. [resolved] is media id → my own local ref —
 * [ContactReconcilePlan.fromGallery] and [ContactReconcilePlan.noBytes] entries as soon as the
 * plan exists, [ContactReconcilePlan.request] entries once their bytes have actually arrived
 * over the wire. A **Note**'s ref is the empty string, which is what a note's ref is
 * everywhere else too.
 *
 * A received item only lands on a Night I have **joined** ([joinedNights]): one I hold under
 * the same `setlistId`, the one key that means the same thing on both timelines (#28), or
 * one I joined by accepting an offer for it. Unlike [handoverPlan], this never mints a new
 * gig. Media for a Night I have not joined is not filed here at all: it is
 * [contactOffers]'s, and waits for me to say yes (#405).
 */
fun contactLanding(
    mine: TimelineCache,
    offer: HandoverManifest,
    resolved: Map<String, String>,
    joined: Map<String, String> = joinedNights(mine),
): Map<String, List<StoredMedia>> {
    val attribution = offer.media.associate { it.id to it.from }
    return offer.timeline.gigMedia.entries.mapNotNull { (theirGigId, items) ->
        if (theirGigId !in offer.timeline.gigs) return@mapNotNull null
        val myGigId = joined[offer.timeline.keyOf(theirGigId)] ?: return@mapNotNull null
        // Re-checked here rather than trusted from the plan: these items come from
        // `offer.timeline.gigMedia`, a different part of the peer's message than
        // `offer.media`, and the two could disagree.
        val landed = items.mapNotNull { m ->
            if (!isSafeMediaId(m.id)) null
            else resolved[m.id]?.let { m.copy(ref = it, from = attribution[m.id] ?: m.from) }
        }
        if (landed.isEmpty()) null else myGigId to landed
    }.toMap()
}

// ---- Media a Contact sends is offered, never filed (#405) ----------------------------
//
// [contactLanding] used to be the whole of the receive path: a Contact's media went
// straight onto whichever of my Gigs shared its setlist.fm id. That is safe only while the
// id is a catalogue key both of us derived on our own, so that agreeing is structural.
// Once a Night can be joined by hand it is not: a Contact's belief that we shared a Night
// would be written onto my record. So media for a Night I have not joined is held apart,
// as an **Offer**, and nothing on my timeline moves until I say.

/**
 * Media a **Contact** sent for a Night of theirs I have not joined, held apart from my
 * timeline until I accept it or decline it (#405). Keyed in [TimelineCache.mediaOffers]
 * by *their* Night id.
 *
 * [date], [artist] and [venue] are their Night's facts, kept so the offer can be shown on
 * the Night of mine it might be — a date is how it is found — and described without their
 * Lane at hand. [declined] is the media ids I said no to: they are not offered again, so a
 * no stays a no across every later **Reconcile**.
 */
@Serializable
data class MediaOffer(
    val date: String = "",
    val artist: String = "",
    val venue: String = "",
    /** Waiting for an answer: ref is the local copy, `from` is the Contact's key. */
    val media: List<StoredMedia> = emptyList(),
    val declined: List<String> = emptyList(),
)

/**
 * The Nights I have joined, as Night id → the id of my own **Gig** it is (#405).
 *
 * **This is the seam hand-joins will use.** Today a Night is joined in two ways: I hold it
 * under that very catalogue id (the setlist.fm id, which is the one key that means the
 * same thing on both timelines), or I accepted a Contact's offer for it, which wrote
 * [TimelineCache.nightJoins]. A later "yes, same Night" writes the same map and arrives
 * here without anything else changing.
 *
 * My own local ids are deliberately not in it. A hand-logged Night's id is a UUID minted
 * on this phone, and a peer who names it has named it on purpose — which is exactly the
 * belief that must be offered rather than filed.
 */
fun joinedNights(mine: TimelineCache): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for ((night, gigId) in mine.nightJoins) if (night.isNotBlank() && gigId in mine.gigs) out[night] = gigId
    for (gig in mine.gigs.values) gig.setlistId?.takeIf { it.isNotBlank() }?.let { out[it] = gig.id }
    return out
}

/**
 * The other half of [contactLanding]: what a Contact sent for Nights of theirs I have not
 * joined, as their Night id → [MediaOffer]. Pure, like the plan. Nothing here touches my
 * timeline; [TimelineCache.holdingOffers] is where these are kept.
 *
 * An offer is only made for a Night on a date I was out myself ([myNights], my **Spine**).
 * A Contact's manifest carries every item they share, not only the ones for Nights we
 * might have in common, so without the date every photograph of every Night of theirs
 * would arrive as a question. A Night I hold under its own id is never an offer: it is
 * [contactLanding]'s, as it always was.
 *
 * Items are checked with [isSafeMediaId] again, for [contactLanding]'s reason, and an
 * item I already hold, or already declined, is not offered.
 */
fun contactOffers(
    mine: TimelineCache,
    offer: HandoverManifest,
    resolved: Map<String, String>,
    myNights: List<FmSetlist>,
    joined: Map<String, String> = joinedNights(mine),
): Map<String, MediaOffer> {
    val myIds = myNights.mapTo(HashSet()) { it.id }
    val myDates = myNights.mapNotNullTo(HashSet()) { it.eventDate?.takeIf(String::isNotBlank) }
    val held = mine.gigMedia.values.flatten().mapTo(HashSet()) { it.id }
    val theirNights = offer.nights.associateBy { it.id }
    val attribution = offer.media.associate { it.id to it.from }

    val out = LinkedHashMap<String, MediaOffer>()
    for ((theirGigId, items) in offer.timeline.gigMedia) {
        val gig = offer.timeline.gigs[theirGigId] ?: continue
        val night = offer.timeline.keyOf(theirGigId)
        if (night.isBlank() || night in joined || night in myIds) continue
        val facts = theirNights[night]
        val date = gig.date.ifBlank { facts?.eventDate.orEmpty() }
        if (date !in myDates) continue
        val declined = mine.mediaOffers[night]?.declined.orEmpty().toSet()
        val waiting = items.mapNotNull { m ->
            if (!isSafeMediaId(m.id) || m.id in held || m.id in declined) null
            else resolved[m.id]?.let { m.copy(ref = it, from = attribution[m.id] ?: m.from) }
        }
        if (waiting.isEmpty()) continue
        out[night] = MediaOffer(
            date = date,
            artist = gig.artist.ifBlank { facts?.artist?.name.orEmpty() },
            venue = gig.venue.ifBlank { facts?.venue?.name.orEmpty() },
            media = waiting,
        )
    }
    return out
}

/**
 * Offers that just arrived, kept (#405). Adds to an offer already waiting for the same
 * Night rather than replacing it, and never brings back an item I declined.
 */
fun TimelineCache.holdingOffers(arrived: Map<String, MediaOffer>): TimelineCache {
    if (arrived.isEmpty()) return this
    val out = LinkedHashMap(mediaOffers)
    for ((night, offer) in arrived) {
        val had = out[night]
        val fresh = offer.media.filter { it.id !in had?.declined.orEmpty() }
        out[night] = if (had == null) offer.copy(media = fresh)
        else had.copy(media = unionMedia(had.media, fresh))
    }
    return copy(mediaOffers = out)
}

/**
 * Yes (#405): the offer for their Night [night] is filed onto my **Gig** [gigId], and the
 * Night is joined — so what they send for it later lands there directly, like any shared
 * Night. Nothing else on my timeline moves.
 */
fun TimelineCache.acceptingOffer(night: String, gigId: String): TimelineCache {
    val offer = mediaOffers[night] ?: return this
    return copy(
        gigMedia = gigMedia + (gigId to unionMedia(gigMedia[gigId].orEmpty(), offer.media)),
        nightJoins = nightJoins + (night to gigId),
        mediaOffers = mediaOffers - night,
    )
}

/**
 * No (#405). My timeline is untouched — that is the whole of declining — and the items
 * are remembered as declined so the next **Reconcile** does not ask again.
 */
fun TimelineCache.decliningOffer(night: String): TimelineCache {
    val offer = mediaOffers[night] ?: return this
    return copy(
        mediaOffers = mediaOffers + (night to offer.copy(
            media = emptyList(),
            declined = (offer.declined + offer.media.map { it.id }).distinct(),
        )),
    )
}

/** The offers waiting on a Night of mine dated [date] (dd-MM-yyyy), their Night id first. */
fun Map<String, MediaOffer>.waitingOn(date: String?): List<Pair<String, MediaOffer>> =
    if (date.isNullOrBlank()) emptyList()
    else entries.filter { it.value.date == date && it.value.media.isNotEmpty() }.map { it.key to it.value }
