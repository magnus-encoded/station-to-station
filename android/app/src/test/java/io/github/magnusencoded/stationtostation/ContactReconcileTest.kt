package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.GalleryItem
import io.github.magnusencoded.stationtostation.data.HandoverManifest
import io.github.magnusencoded.stationtostation.data.OfferedMedia
import io.github.magnusencoded.stationtostation.data.StoredGig
import io.github.magnusencoded.stationtostation.data.StoredMedia
import io.github.magnusencoded.stationtostation.data.TimelineCache
import io.github.magnusencoded.stationtostation.data.contactLanding
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

    @Test
    fun `a resolved item lands on the gig sharing its setlistId, not its sender-side gigId`() {
        val mine = TimelineCache(gigs = mapOf("mine-gig" to StoredGig(id = "mine-gig", setlistId = "sl-1")))
        val offer = HandoverManifest(
            timeline = TimelineCache(
                gigs = mapOf("their-gig" to StoredGig(id = "their-gig", setlistId = "sl-1")),
                gigMedia = mapOf("their-gig" to listOf(photo("m1"))),
            ),
            media = listOf(offered("m1", "h1")),
        )

        val landing = contactLanding(mine, offer, resolved = mapOf("m1" to "content://gallery/x"))

        assertEquals(1, landing.size)
        val item = landing.getValue("mine-gig").single()
        assertEquals("m1", item.id)
        assertEquals("content://gallery/x", item.ref)
        assertEquals("their-key", item.from)
    }

    @Test
    fun `a night I have no record of attending lands nothing`() {
        val offer = HandoverManifest(
            timeline = TimelineCache(
                gigs = mapOf("their-gig" to StoredGig(id = "their-gig", setlistId = "sl-1")),
                gigMedia = mapOf("their-gig" to listOf(photo("m1"))),
            ),
            media = listOf(offered("m1", "h1")),
        )

        val landing = contactLanding(TimelineCache(), offer, resolved = mapOf("m1" to "content://gallery/x"))

        assertTrue(landing.isEmpty())
    }

    @Test
    fun `an item with no resolved ref yet does not land`() {
        val mine = TimelineCache(gigs = mapOf("mine-gig" to StoredGig(id = "mine-gig", setlistId = "sl-1")))
        val offer = HandoverManifest(
            timeline = TimelineCache(
                gigs = mapOf("their-gig" to StoredGig(id = "their-gig", setlistId = "sl-1")),
                gigMedia = mapOf("their-gig" to listOf(photo("m1"))),
            ),
            media = listOf(offered("m1", "h1")),
        )

        val landing = contactLanding(mine, offer, resolved = emptyMap())

        assertTrue(landing.isEmpty())
    }
}
