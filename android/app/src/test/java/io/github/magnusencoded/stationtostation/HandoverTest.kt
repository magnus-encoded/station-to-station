package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.CATEGORY_SETLISTS
import io.github.magnusencoded.stationtostation.data.GalleryItem
import io.github.magnusencoded.stationtostation.data.HandoverManifest
import io.github.magnusencoded.stationtostation.data.OfferedMedia
import io.github.magnusencoded.stationtostation.data.StoredAttendance
import io.github.magnusencoded.stationtostation.data.StoredGig
import io.github.magnusencoded.stationtostation.data.StoredLog
import io.github.magnusencoded.stationtostation.data.StoredMedia
import io.github.magnusencoded.stationtostation.data.TimelineCache
import io.github.magnusencoded.stationtostation.data.handoverPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device handover decision (#141). No radio, no socket, no device — which is the
 * whole reason it is a pure function.
 *
 * Names are invented. This repository is public and real timeline data never enters a
 * fixture; the *shapes* here come from real failures, the nights do not.
 */
class HandoverTest {

    private fun gig(id: String, setlistId: String? = null, createdAt: Long = 0L, venue: String = "") =
        StoredGig(id = id, date = "12-06-2026", artist = "Paper Cranes", venue = venue, setlistId = setlistId, createdAt = createdAt)

    private fun photo(id: String, ref: String = "content://theirs/$id", personal: Boolean = false) =
        StoredMedia(id = id, kind = StoredMedia.Kind.PHOTO, ref = ref, personal = personal)

    private fun offered(id: String, gigId: String, hash: String, personal: Boolean = false) =
        OfferedMedia(id = id, gigId = gigId, kind = StoredMedia.Kind.PHOTO, hash = hash, personal = personal)

    private val all = setOf(CATEGORY_SETLISTS, StoredMedia.Kind.PHOTO, StoredMedia.Kind.VIDEO, "personal_photo")

    @Test
    fun `the union keeps what only one side has`() {
        val mine = TimelineCache(gigs = mapOf("a" to gig("a", setlistId = "s1")))
        val theirs = TimelineCache(gigs = mapOf("b" to gig("b", setlistId = "s2")))

        val plan = handoverPlan(mine, HandoverManifest(timeline = theirs), all, verified = true)

        assertEquals(setOf("a", "b"), plan.merged.gigs.keys)
    }

    @Test
    fun `the newer device's night survives the older device's larger library`() {
        val mine = TimelineCache(
            gigs = mapOf("new" to gig("new", setlistId = "s-last-night", createdAt = 900L)),
            gigLogs = mapOf("new" to StoredLog(songs = listOf("Hollowmoor", "", "Vardhavn"))),
        )
        val theirs = TimelineCache(gigs = (1..40).associate { "old$it" to gig("old$it", setlistId = "s$it") })

        val plan = handoverPlan(mine, HandoverManifest(timeline = theirs), all, verified = true)

        assertEquals(41, plan.merged.gigs.size)
        assertEquals(listOf("Hollowmoor", "", "Vardhavn"), plan.merged.gigLogs["new"]?.songs)
    }

    @Test
    fun `two records of one night collapse and the older id wins`() {
        // One night adopted onto its setlist.fm record on both devices, at different
        // times, so the two ids differ and only the setlistId ties them together.
        val mine = TimelineCache(
            gigs = mapOf("younger" to gig("younger", setlistId = "s1", createdAt = 500L)),
            gigMedia = mapOf("younger" to listOf(photo("m-mine", ref = "content://mine/1"))),
        )
        val theirs = TimelineCache(
            gigs = mapOf("older" to gig("older", setlistId = "s1", createdAt = 100L, venue = "Hollowmoor Park")),
        )

        val plan = handoverPlan(mine, HandoverManifest(timeline = theirs), all, verified = true)

        assertEquals(setOf("older"), plan.merged.gigs.keys)
        assertEquals("Hollowmoor Park", plan.merged.gigs["older"]?.venue)
        // My media moved onto the surviving id rather than being stranded on mine.
        assertEquals(listOf("m-mine"), plan.merged.gigMedia["older"]?.map { it.id })
    }

    @Test
    fun `a Log on both sides is not dropped, and Gaps survive`() {
        val mine = TimelineCache(
            gigs = mapOf("a" to gig("a", setlistId = "s1")),
            gigLogs = mapOf("a" to StoredLog(songs = listOf("Hollowmoor", ""), closed = false)),
        )
        val theirs = TimelineCache(
            gigs = mapOf("a" to gig("a", setlistId = "s1")),
            gigLogs = mapOf("a" to StoredLog(songs = listOf("Hollowmoor", "", "Vardhavn"), closed = true)),
        )

        val plan = handoverPlan(mine, HandoverManifest(timeline = theirs), all, verified = true)

        val log = plan.merged.gigLogs["a"]!!
        assertEquals(listOf("Hollowmoor", "", "Vardhavn"), log.songs)
        assertEquals(1, log.gaps)
        // Open on one side stays open: a handover must not upgrade a claim nobody made.
        assertFalse(log.closed)
    }

    @Test
    fun `the stronger attendance claim survives`() {
        val mine = TimelineCache(
            gigs = mapOf("a" to gig("a", setlistId = "s1")),
            gigAttendance = mapOf("a" to StoredAttendance(provenance = StoredAttendance.Provenance.PLANNED)),
        )
        val theirs = TimelineCache(
            gigs = mapOf("a" to gig("a", setlistId = "s1")),
            gigAttendance = mapOf(
                "a" to StoredAttendance(provenance = StoredAttendance.Provenance.CHECKED_IN, checkedInAt = 1_700L),
            ),
        )

        val plan = handoverPlan(mine, HandoverManifest(timeline = theirs), all, verified = true)

        assertEquals(StoredAttendance.Provenance.CHECKED_IN, plan.merged.gigAttendance["a"]?.provenance)
        assertEquals(1_700L, plan.merged.gigAttendance["a"]?.checkedInAt)
    }

    @Test
    fun `a photo already in my own gallery is attached rather than transferred`() {
        val plan = handoverPlan(
            mine = TimelineCache(gigs = mapOf("a" to gig("a", setlistId = "s1"))),
            offer = HandoverManifest(
                timeline = TimelineCache(
                    gigs = mapOf("a" to gig("a", setlistId = "s1")),
                    gigMedia = mapOf("a" to listOf(photo("m1"))),
                ),
                media = listOf(offered("m1", "a", hash = "h1")),
            ),
            allow = all,
            verified = true,
            gallery = listOf(GalleryItem(ref = "content://mine/99", hash = "h1")),
        )

        assertEquals(mapOf("m1" to "content://mine/99"), plan.fromGallery)
        assertTrue(plan.request.isEmpty())
        // The app owns the bytes either way (#97): the record points at my own copy.
        assertEquals("content://mine/99", plan.merged.gigMedia["a"]?.single()?.ref)
    }

    @Test
    fun `a photo my gallery does not have is requested`() {
        val plan = handoverPlan(
            mine = TimelineCache(gigs = mapOf("a" to gig("a", setlistId = "s1"))),
            offer = HandoverManifest(
                timeline = TimelineCache(
                    gigs = mapOf("a" to gig("a", setlistId = "s1")),
                    gigMedia = mapOf("a" to listOf(photo("m1"))),
                ),
                media = listOf(offered("m1", "a", hash = "h1")),
            ),
            allow = all,
            verified = true,
        )

        assertEquals(listOf("m1"), plan.request)
        // Not in the merged cache: the bytes have not arrived, and a record pointing
        // at nothing is the dead reference #97 exists to prevent.
        assertTrue(plan.merged.gigMedia["a"].isNullOrEmpty())
    }

    @Test
    fun `a neighbouring frame from the same minute does not resolve`() {
        val plan = handoverPlan(
            mine = TimelineCache(gigs = mapOf("a" to gig("a", setlistId = "s1"))),
            offer = HandoverManifest(
                timeline = TimelineCache(
                    gigs = mapOf("a" to gig("a", setlistId = "s1")),
                    gigMedia = mapOf("a" to listOf(photo("m1"))),
                ),
                media = listOf(offered("m1", "a", hash = "h1")),
            ),
            allow = all,
            verified = true,
            // Same night, same minute, different bytes.
            gallery = listOf(GalleryItem(ref = "content://mine/98", hash = "h-other")),
        )

        assertTrue(plan.fromGallery.isEmpty())
        assertEquals(listOf("m1"), plan.request)
    }

    @Test
    fun `a refused hash never transfers`() {
        val plan = handoverPlan(
            mine = TimelineCache(gigs = mapOf("a" to gig("a", setlistId = "s1"))),
            offer = HandoverManifest(
                timeline = TimelineCache(
                    gigs = mapOf("a" to gig("a", setlistId = "s1")),
                    gigMedia = mapOf("a" to listOf(photo("m1"))),
                ),
                media = listOf(offered("m1", "a", hash = "h-blocked")),
            ),
            allow = all,
            verified = true,
            refusedHashes = setOf("h-blocked"),
        )

        assertEquals(listOf("m1"), plan.refused)
        assertTrue(plan.request.isEmpty())
        assertTrue(plan.withheld.isEmpty())
        assertTrue(plan.merged.gigMedia["a"].isNullOrEmpty())
    }

    @Test
    fun `a category the source did not tick is withheld, not refused`() {
        val plan = handoverPlan(
            mine = TimelineCache(gigs = mapOf("a" to gig("a", setlistId = "s1"))),
            offer = HandoverManifest(
                timeline = TimelineCache(
                    gigs = mapOf("a" to gig("a", setlistId = "s1")),
                    gigMedia = mapOf("a" to listOf(photo("m1"), photo("m2", personal = true))),
                ),
                media = listOf(
                    offered("m1", "a", hash = "h1"),
                    offered("m2", "a", hash = "h2", personal = true),
                ),
            ),
            // Personal media is its own checkbox, and this source left it unticked.
            allow = setOf(CATEGORY_SETLISTS, StoredMedia.Kind.PHOTO),
            verified = true,
            gallery = listOf(GalleryItem("content://mine/1", "h1"), GalleryItem("content://mine/2", "h2")),
        )

        assertEquals(listOf("m2"), plan.withheld)
        assertTrue(plan.refused.isEmpty())
        assertEquals(setOf("m1"), plan.fromGallery.keys)
    }

    @Test
    fun `counts are carried per category, and a truncated item list shows`() {
        val offer = HandoverManifest(
            timeline = TimelineCache(
                gigs = mapOf("a" to gig("a", setlistId = "s1")),
                gigMedia = mapOf("a" to listOf(photo("m1"), photo("m2", personal = true))),
            ),
            media = listOf(
                offered("m1", "a", hash = "h1"),
                offered("m2", "a", hash = "h2", personal = true),
            ),
            counts = mapOf("photo" to 1, "personal_photo" to 2),
        )

        val plan = handoverPlan(TimelineCache(gigs = mapOf("a" to gig("a", setlistId = "s1"))), offer, all, verified = true)

        assertEquals(mapOf("photo" to 1, "personal_photo" to 1), plan.expected)
        assertTrue(plan.countMismatch)
    }

    @Test
    fun `an item I already hold is neither requested nor counted twice`() {
        val mine = TimelineCache(
            gigs = mapOf("a" to gig("a", setlistId = "s1")),
            gigMedia = mapOf("a" to listOf(photo("m1", ref = "content://mine/1"))),
        )
        val plan = handoverPlan(
            mine = mine,
            offer = HandoverManifest(
                timeline = TimelineCache(
                    gigs = mapOf("a" to gig("a", setlistId = "s1")),
                    gigMedia = mapOf("a" to listOf(photo("m1"))),
                ),
                media = listOf(offered("m1", "a", hash = "h1")),
            ),
            allow = all,
            verified = true,
        )

        assertEquals(listOf("m1"), plan.held)
        assertTrue(plan.request.isEmpty())
        assertEquals(listOf("content://mine/1"), plan.merged.gigMedia["a"]?.map { it.ref })
    }

    @Test
    fun `a manifest that does not verify plans nothing at all`() {
        val plan = handoverPlan(
            mine = TimelineCache(gigs = mapOf("a" to gig("a", setlistId = "s1"))),
            offer = HandoverManifest(
                timeline = TimelineCache(gigs = mapOf("b" to gig("b", setlistId = "s2"))),
                media = listOf(offered("m1", "b", hash = "h1")),
            ),
            allow = all,
            verified = false,
        )

        assertEquals(TimelineCache(), plan.merged)
        assertTrue(plan.request.isEmpty())
        assertTrue(plan.fromGallery.isEmpty())
        assertTrue(plan.merged.gigs.isEmpty())
    }

    @Test
    fun `a Bill-minted night with no correspondence key is surfaced as unkeyed`() {
        val mine = TimelineCache(gigs = mapOf("mine-local" to gig("mine-local", createdAt = 10L)))
        val theirs = TimelineCache(gigs = mapOf("their-local" to gig("their-local", createdAt = 11L)))

        val plan = handoverPlan(mine, HandoverManifest(timeline = theirs), all, verified = true)

        // It duplicates — ADR-0002's artist|venue|day key is not implemented — and the
        // plan says so rather than letting it look like two nights.
        assertEquals(listOf("their-local"), plan.unkeyed)
        assertEquals(setOf("mine-local", "their-local"), plan.merged.gigs.keys)
    }

    /**
     * Resumption is not a mechanism, it is a consequence (#142). **Media** ids are
     * assigned at **Attach** and carried forever, so an item that arrived is an item I
     * hold — and re-running the plan after a dropped connection asks for exactly the
     * remainder. There is nothing to checkpoint and nothing to get out of step.
     */
    @Test
    fun `a dropped transfer resumes without asking twice for what arrived`() {
        val theirs = TimelineCache(
            gigs = mapOf("a" to gig("a", setlistId = "s1")),
            gigMedia = mapOf("a" to listOf(photo("m1"), photo("m2"), photo("m3"))),
        )
        val offer = HandoverManifest(
            timeline = theirs,
            media = listOf(offered("m1", "a", "h1"), offered("m2", "a", "h2"), offered("m3", "a", "h3")),
        )
        val mine = TimelineCache(gigs = mapOf("a" to gig("a", setlistId = "s1")))

        assertEquals(listOf("m1", "m2", "m3"), handoverPlan(mine, offer, all, verified = true).request)

        // The first photograph landed, and then the wifi went.
        val afterDrop = mine.copy(gigMedia = mapOf("a" to listOf(photo("m1", ref = "content://mine/m1"))))
        val resumed = handoverPlan(afterDrop, offer, all, verified = true)

        assertEquals(listOf("m1"), resumed.held)
        assertEquals(listOf("m2", "m3"), resumed.request)
        // And what already arrived is coherent on its own: a cancelled transfer leaves a
        // smaller library, never a corrupt one.
        assertEquals(listOf("m1"), resumed.merged.gigMedia["a"]?.map { it.id })
    }
}
