package io.github.magnusencoded.stationtostation.data

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
): ContactReconcilePlan {
    if (!verified) return ContactReconcilePlan()

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
                                noBytes = noBytes, request = request)
}

/**
 * The dumb half of [contactReconcilePlan]: turns resolved items into what
 * [TimelineStore.mergeContactMedia] should write. [resolved] is media id → my own local ref —
 * [ContactReconcilePlan.fromGallery] and [ContactReconcilePlan.noBytes] entries as soon as the
 * plan exists, [ContactReconcilePlan.request] entries once their bytes have actually arrived
 * over the wire. A **Note**'s ref is the empty string, which is what a note's ref is
 * everywhere else too.
 *
 * A received item only lands on a gig I already have, matched by `setlistId` — the one key
 * that means the same thing on both timelines (#28). Unlike [handoverPlan], this never mints a
 * new gig: a Contact's offer is narrowed to a shared band already, not a device's own history,
 * so a night I have no record of attending is not one for their photos to create.
 */
fun contactLanding(
    mine: TimelineCache,
    offer: HandoverManifest,
    resolved: Map<String, String>,
): Map<String, List<StoredMedia>> {
    val setlistToGigId = mine.gigs.values.mapNotNull { g -> g.setlistId?.let { it to g.id } }.toMap()
    val attribution = offer.media.associate { it.id to it.from }
    return offer.timeline.gigMedia.entries.mapNotNull { (theirGigId, items) ->
        val setlistId = offer.timeline.gigs[theirGigId]?.setlistId ?: return@mapNotNull null
        val myGigId = setlistToGigId[setlistId] ?: return@mapNotNull null
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
