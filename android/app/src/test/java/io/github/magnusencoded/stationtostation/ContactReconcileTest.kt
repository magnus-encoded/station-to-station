package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.GalleryItem
import io.github.magnusencoded.stationtostation.data.HandoverManifest
import io.github.magnusencoded.stationtostation.data.OfferedMedia
import io.github.magnusencoded.stationtostation.data.StoredMedia
import io.github.magnusencoded.stationtostation.data.TimelineCache
import io.github.magnusencoded.stationtostation.data.contactReconcilePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The LAN reconcile decision between two **Contacts** (#257). No radio, no socket, no
 * device, same reason as [HandoverTest].
 *
 * Names are invented — this repository is public and real timeline data never enters a
 * fixture.
 */
class ContactReconcileTest {

    private fun photo(id: String, ref: String = "content://mine/$id") =
        StoredMedia(id = id, kind = StoredMedia.Kind.PHOTO, ref = ref)

    private fun offered(id: String, hash: String) =
        OfferedMedia(id = id, gigId = "a", kind = StoredMedia.Kind.PHOTO, hash = hash, from = "their-key")

    @Test
    fun `unverified peer yields an empty plan`() {
        val offer = HandoverManifest(media = listOf(offered("m1", "h1")))

        val plan = contactReconcilePlan(TimelineCache(), offer, verified = false)

        assertTrue(plan.held.isEmpty())
        assertTrue(plan.fromGallery.isEmpty())
        assertTrue(plan.request.isEmpty())
    }

    @Test
    fun `an item already held by id is neither requested nor pulled from gallery`() {
        val mine = TimelineCache(gigMedia = mapOf("a" to listOf(photo("m1"))))
        val offer = HandoverManifest(media = listOf(offered("m1", "h1")))

        val plan = contactReconcilePlan(mine, offer, verified = true)

        assertEquals(listOf("m1"), plan.held)
        assertTrue(plan.fromGallery.isEmpty())
        assertTrue(plan.request.isEmpty())
    }

    @Test
    fun `a hash match in the gallery resolves without a request`() {
        val offer = HandoverManifest(media = listOf(offered("m1", "h1")))
        val gallery = listOf(GalleryItem(ref = "content://gallery/x", hash = "h1"))

        val plan = contactReconcilePlan(TimelineCache(), offer, verified = true, gallery = gallery)

        assertEquals(mapOf("m1" to "content://gallery/x"), plan.fromGallery)
        assertTrue(plan.request.isEmpty())
    }

    @Test
    fun `unmatched media is requested`() {
        val offer = HandoverManifest(media = listOf(offered("m1", "h1")))

        val plan = contactReconcilePlan(TimelineCache(), offer, verified = true)

        assertEquals(listOf("m1"), plan.request)
    }

    @Test
    fun `running the plan twice against the same manifests is idempotent`() {
        val mine = TimelineCache(gigMedia = mapOf("a" to listOf(photo("m1"))))
        val offer = HandoverManifest(
            media = listOf(offered("m1", "h1"), offered("m2", "h2"), offered("m3", "h3")),
        )
        val gallery = listOf(GalleryItem(ref = "content://gallery/x", hash = "h2"))

        val first = contactReconcilePlan(mine, offer, verified = true, gallery = gallery)
        val second = contactReconcilePlan(mine, offer, verified = true, gallery = gallery)

        assertEquals(first, second)
    }
}
