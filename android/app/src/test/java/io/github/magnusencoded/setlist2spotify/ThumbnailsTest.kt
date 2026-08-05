package io.github.magnusencoded.setlist2spotify

import io.github.magnusencoded.setlist2spotify.data.photos.Thumbnails
import io.github.magnusencoded.setlist2spotify.data.photos.thumbnailSize
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #98's one new seam, kept as narrow as the spec asks: the scaling decision, which
 * is shared, rather than the encoders, which are idiomatic per platform and differ.
 *
 * Every number here is also asserted in the iOS `ThumbnailsTests`, against the same
 * fixed values rather than against this file — so neither platform can drift by
 * agreeing with itself.
 *
 * ponytail: the bytes-out half of the seam — that a real encode lands at the right
 * dimensions and roughly the right size — is asserted on iOS, where XCTest runs on
 * a simulator with a real ImageIO. Android unit tests get stub Bitmaps, so the same
 * assertion here would test the stubs. Add Robolectric only if the Android encoder
 * ever needs to be wrong before anyone notices.
 */
class ThumbnailsTest {

    @Test
    fun `the tier sizes are the ones #104's transfer arithmetic was built on`() {
        // Not a rendering detail: the grid figure is what makes a backlog trickle
        // feasible at all, so changing it is changing another spec's premise.
        assertEquals(512, Thumbnails.GRID_EDGE_PX)
        assertEquals(80, Thumbnails.GRID_QUALITY)
        assertEquals(1440, Thumbnails.FULL_EDGE_PX)
        assertEquals(85, Thumbnails.FULL_QUALITY)
    }

    @Test
    fun `the longest edge becomes the tier's edge and the ratio is kept`() {
        assertEquals(512 to 384, thumbnailSize(4000, 3000, 512))
        assertEquals(384 to 512, thumbnailSize(3000, 4000, 512))
        assertEquals(1440 to 1080, thumbnailSize(4000, 3000, 1440))
    }

    @Test
    fun `a square source stays square`() {
        assertEquals(512 to 512, thumbnailSize(2000, 2000, 512))
    }

    @Test
    fun `a source smaller than the tier is kept at its own size`() {
        // Blowing it up costs bytes and adds nothing.
        assertEquals(300 to 200, thumbnailSize(300, 200, 512))
        assertEquals(512 to 100, thumbnailSize(512, 100, 512))
    }

    @Test
    fun `a panorama keeps at least one pixel of height`() {
        // 4000x80 scaled to 512 rounds the short edge to zero, and a zero-height
        // bitmap is a crash rather than a thumbnail.
        assertEquals(512 to 10, thumbnailSize(4000, 80, 512))
        assertEquals(512 to 1, thumbnailSize(4000, 1, 512))
    }

    @Test
    fun `a source with no size at all produces no box`() {
        assertEquals(0 to 0, thumbnailSize(0, 0, 512))
    }

    @Test
    fun `the filename is derived from the media id, so there is no second mapping`() {
        assertEquals("a1b2.jpg", Thumbnails.fileName("a1b2"))
    }

    @Test
    fun `the two tiers live in two directories, so eviction cannot reach the floor`() {
        // The invariant is structural rather than a policy someone has to remember.
        assertEquals("thumbs", Thumbnails.GRID_DIR)
        assertEquals("thumb-cache", Thumbnails.CACHE_DIR)
    }
}
