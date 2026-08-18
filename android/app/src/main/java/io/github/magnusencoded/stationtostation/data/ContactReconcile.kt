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
    /** Media ids to ask for. */
    val request: List<String> = emptyList(),
)

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
    val byHash = gallery.associateBy { it.hash }

    val held = ArrayList<String>()
    val request = ArrayList<String>()
    val fromGallery = LinkedHashMap<String, String>()
    for (item in offer.media) when {
        item.id in mineIds -> held += item.id
        else -> byHash[item.hash]?.let { fromGallery[item.id] = it.ref } ?: run { request += item.id }
    }

    return ContactReconcilePlan(held = held, fromGallery = fromGallery, request = request)
}
