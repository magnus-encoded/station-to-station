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

    /**
     * A **Note** is text and a **Verdict**, and both already rode the manifest. Asking for
     * it would be asking for zero bytes — and then dropping it when zero bytes arrived,
     * which is exactly what used to happen here and on iOS.
     */
    @Test
    fun `a note needs nothing fetched and is never requested`() {
        val note = OfferedMedia(id = "n1", gigId = "a", kind = StoredMedia.Kind.NOTE,
                                from = "their-key", text = "the encore was the point")
        val offer = HandoverManifest(media = listOf(note, offered("m1", "h1")))

        val plan = contactReconcilePlan(TimelineCache(), offer, verified = true)

        assertEquals(listOf("n1"), plan.noBytes)
        assertEquals(listOf("m1"), plan.request)
    }

    /**
     * The failure a note would otherwise hit first: it hashes to nothing, and so does
     * anything the hasher could not read. Matching on that empty string hands the note
     * whichever unhashable thing the gallery listed first — a note wearing a photo's ref.
     */
    @Test
    fun `an empty hash never matches the gallery`() {
        val offer = HandoverManifest(media = listOf(offered("m1", "")))
        val gallery = listOf(GalleryItem(ref = "content://gallery/x", hash = ""))

        val plan = contactReconcilePlan(TimelineCache(), offer, verified = true, gallery = gallery)

        assertTrue(plan.fromGallery.isEmpty())
        assertEquals(listOf("m1"), plan.request)
    }

    /** `noBytes` is "nothing to fetch", not "always take it again". */
    @Test
    fun `a note already held stays held`() {
        val note = photo("n1").copy(kind = StoredMedia.Kind.NOTE, ref = "")
        val mine = TimelineCache(gigMedia = mapOf("a" to listOf(note)))
        val offer = HandoverManifest(media = listOf(
            OfferedMedia(id = "n1", gigId = "a", kind = StoredMedia.Kind.NOTE, from = "their-key")
        ))

        val plan = contactReconcilePlan(mine, offer, verified = true)

        assertEquals(listOf("n1"), plan.held)
        assertTrue(plan.noBytes.isEmpty())
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

    // ---- peer-supplied ids are peer-supplied (#267) ---------------------------------
    //
    // A media id is a UUID this app minted at Attach — but an id arriving over the wire is
    // whatever the far end chose to send, and it reaches `receivedMediaFile` as a path
    // component. iOS has the same three checks, in the same three places.

    @Test
    fun `an id that would escape its directory never reaches a plan`() {
        val offer = HandoverManifest(media = listOf(
            offered("../../../databases/timeline", "h1"),
            offered("ok-1", "h2"),
        ))

        val plan = contactReconcilePlan(TimelineCache(), offer, verified = true)

        assertEquals(listOf("ok-1"), plan.request)
        assertTrue(plan.held.isEmpty())
        assertTrue(plan.noBytes.isEmpty())
    }

    @Test
    fun `an unsafe id is refused whichever bucket it would have fallen into`() {
        val evil = "a/b"
        val mine = TimelineCache(gigMedia = mapOf("a" to listOf(photo(evil))))
        val offer = HandoverManifest(media = listOf(
            offered(evil, "h1"),
            OfferedMedia(id = "..", gigId = "a", kind = StoredMedia.Kind.NOTE, from = "their-key"),
        ))
        val gallery = listOf(GalleryItem(ref = "content://gallery/x", hash = "h1"))

        val plan = contactReconcilePlan(mine, offer, verified = true, gallery = gallery)

        // Held, noBytes and fromGallery are all reachable without ever asking for bytes,
        // and an id that is never allowed to name a file must miss all of them too.
        assertTrue(plan.held.isEmpty())
        assertTrue(plan.noBytes.isEmpty())
        assertTrue(plan.fromGallery.isEmpty())
        assertTrue(plan.request.isEmpty())
    }

    /**
     * Re-checked at the landing rather than trusted from the plan: these items come from
     * `offer.timeline.gigMedia`, a different part of the peer's message than `offer.media`,
     * and a peer is free to make the two disagree.
     */
    @Test
    fun `an unsafe id is refused again at the landing`() {
        val mine = TimelineCache(gigs = mapOf("mine-gig" to StoredGig(id = "mine-gig", setlistId = "sl-1")))
        val offer = HandoverManifest(
            timeline = TimelineCache(
                gigs = mapOf("their-gig" to StoredGig(id = "their-gig", setlistId = "sl-1")),
                gigMedia = mapOf("their-gig" to listOf(photo("../evil"), photo("m1"))),
            ),
            media = listOf(offered("m1", "h1")),
        )

        val landing = contactLanding(
            mine, offer,
            resolved = mapOf("../evil" to "content://gallery/evil", "m1" to "content://gallery/x"),
        )

        assertEquals(listOf("m1"), landing.getValue("mine-gig").map { it.id })
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
