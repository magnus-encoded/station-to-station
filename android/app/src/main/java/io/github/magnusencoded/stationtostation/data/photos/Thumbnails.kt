package io.github.magnusencoded.stationtostation.data.photos

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The **durable floor** of a keepsake (#98).
 *
 * **Attach** referenced the gallery and copied nothing, so a night emptied whenever
 * the gallery moved underneath it. Copying full-res instead is not the alternative:
 * a 12 MP JPEG is 3–5 MB and a full-gig recording is hundreds, so a night of thirty
 * photos plus a video is ~350 MB, and this is software for someone with 31 gigs on
 * one friend's line. Duplicating a collector's gallery inside the app container is a
 * defect, not a trade-off.
 *
 * So two derived copies, and never the original:
 *
 * - **Grid tier** — small, durable, kept forever. What survives a gallery deletion,
 *   what renders in 2035, what a **Vault** export carries. *Never evicted.*
 * - **Full-screen cache** — larger, evictable, nice-to-have. What makes a lost
 *   original degrade to *slightly soft* rather than to *thumbnail*. Absent is a
 *   normal state and nothing may depend on it being present.
 *
 * The sizes are **cross-platform constants fixed here**, not each platform's
 * defaults. The grid figure in particular is not a rendering detail: it is the
 * input to #104's transfer arithmetic, where ~30–60 KB per item is what makes a
 * backlog trickle feasible at all.
 */
object Thumbnails {
    /** Longest edge, px. Covers a three-across cell on a 3× phone (~390 px) with room. */
    const val GRID_EDGE_PX = 512
    const val GRID_QUALITY = 80

    /** Longest edge, px. Enough for a full-bleed view on a 3× display. */
    const val FULL_EDGE_PX = 1440
    const val FULL_QUALITY = 85

    /**
     * The two directories, app-private on both platforms. Separate directories and
     * not one with a suffix, so "evict the cache" is a directory the durable tier
     * is not in — the one rule whose breach silently destroys the product's promise.
     */
    const val GRID_DIR = "thumbs"
    const val CACHE_DIR = "thumb-cache"

    /**
     * Derived from the media id (#97), by a convention stated once and shared by
     * both platforms and by the vault export (#106). A stored path would duplicate
     * a deterministic function and add a second thing to keep true.
     */
    fun fileName(mediaId: String): String = "$mediaId.jpg"
}

/**
 * The box a [width]×[height] source scales into for [maxEdge] — the longest edge
 * becomes [maxEdge] and the aspect ratio is kept.
 *
 * Never upscales: a source already smaller than [maxEdge] is copied at its own
 * size. Blowing up a small photo costs bytes and adds nothing, and the grid tier's
 * size budget is load-bearing (#104).
 *
 * Pure arithmetic, and the seam #98 is tested through: the encoders are idiomatic
 * per platform and differ, but this decision is shared and both platforms assert
 * the same fixed answers for it.
 */
fun thumbnailSize(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
    if (width <= 0 || height <= 0) return 0 to 0
    val longest = max(width, height)
    if (longest <= maxEdge) return width to height
    val scale = maxEdge.toDouble() / longest
    // Coerced to at least 1: a panorama 4000×80 would otherwise round its short
    // edge to zero, and a zero-height bitmap is a crash rather than a thumbnail.
    return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
}
