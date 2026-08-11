package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.GalleryItem
import io.github.magnusencoded.stationtostation.data.HandoverManifest
import io.github.magnusencoded.stationtostation.data.OfferedMedia
import io.github.magnusencoded.stationtostation.data.SealedManifest
import io.github.magnusencoded.stationtostation.data.StoredGig
import io.github.magnusencoded.stationtostation.data.StoredMedia
import io.github.magnusencoded.stationtostation.data.TimelineCache
import io.github.magnusencoded.stationtostation.data.countsAgree
import io.github.magnusencoded.stationtostation.data.handoverPlan
import io.github.magnusencoded.stationtostation.data.openManifest
import io.github.magnusencoded.stationtostation.data.sealManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The envelope (#142). Confidentiality belongs to the transport and is verified with a
 * packet capture, not here; this is the half that is decidable without a radio.
 */
class WireTest {

    private val key = "the key the QR carried".toByteArray()
    private val other = "a key from somebody else's screen".toByteArray()

    private fun photo(id: String, personal: Boolean = false) =
        StoredMedia(id = id, kind = StoredMedia.Kind.PHOTO, ref = "content://theirs/$id", personal = personal)

    private fun offered(id: String, personal: Boolean = false) = OfferedMedia(
        id = id,
        gigId = "g1",
        kind = StoredMedia.Kind.PHOTO,
        hash = "h-$id",
        personal = personal,
    )

    private val gig = StoredGig(id = "g1", date = "12-06-2026", artist = "Paper Cranes", setlistId = "s1")

    private fun manifest() = HandoverManifest(
        timeline = TimelineCache(
            gigs = mapOf("g1" to gig),
            gigMedia = mapOf("g1" to listOf(photo("m1"), photo("m2", personal = true))),
        ),
        media = listOf(offered("m1"), offered("m2", personal = true)),
    )

    @Test
    fun `a sealed manifest opens with the key that sealed it`() {
        val opened = openManifest(key, sealManifest(key, manifest()))
        assertNotNull(opened)
        assertEquals(listOf("m1", "m2"), opened?.media?.map { it.id })
    }

    @Test
    fun `a key from somebody else's screen opens nothing`() {
        assertNull(openManifest(other, sealManifest(key, manifest())))
    }

    /**
     * The highest-stakes single bit in the payload: flipping it exposes something
     * withheld with nothing in the UI to say so. Which is why the manifest is verified
     * as one unit, before anything is written.
     */
    @Test
    fun `flipping the personal bit in transit fails the whole manifest`() {
        val sealed = sealManifest(key, manifest())
        val tampered = sealed.copy(payload = sealed.payload.replace("\"personal\":true", "\"personal\":false"))

        assertTrue("the fixture must actually contain the bit", tampered.payload != sealed.payload)
        assertNull(openManifest(key, tampered))
    }

    @Test
    fun `a forged tag opens nothing, and neither does a missing or malformed one`() {
        val sealed = sealManifest(key, manifest())
        assertNull(openManifest(key, sealed.copy(mac = "AAAA")))
        assertNull(openManifest(key, sealed.copy(mac = "")))
        assertNull(openManifest(key, sealed.copy(mac = "not base64 at all !!")))
    }

    /** An algorithm we do not implement is a manifest we cannot verify, so it is refused. */
    @Test
    fun `an unknown algorithm is refused rather than assumed`() {
        val sealed = sealManifest(key, manifest())
        assertNull(openManifest(key, sealed.copy(alg = "trust-me")))
    }

    /** A payload that will not parse is the same outcome as one that will not verify. */
    @Test
    fun `a payload that is not a manifest opens nothing`() {
        assertNull(openManifest(key, SealedManifest(payload = "{", mac = "")))
    }

    /**
     * Counts are computed at seal time, so they are *inside* the tag. Expected and
     * received cannot drift together, which is the only way "48 offered, 48 received"
     * means anything.
     */
    @Test
    fun `per-category counts are sealed with the payload`() {
        val opened = openManifest(key, sealManifest(key, manifest()))!!
        assertEquals(mapOf("photo" to 1, "personal_photo" to 1), opened.counts)

        assertTrue(countsAgree(opened, opened.media))
        // A truncated transfer otherwise looks exactly like a smaller library.
        assertFalse(countsAgree(opened, opened.media.drop(1)))
    }

    /**
     * The whole contract in one line: a manifest that fails verification writes nothing,
     * because the plan takes the verdict rather than deciding it.
     */
    @Test
    fun `a manifest that does not verify produces an empty plan`() {
        val sealed = sealManifest(key, manifest())
        val opened = openManifest(other, sealed)

        val plan = handoverPlan(
            mine = TimelineCache(gigs = mapOf("g1" to gig)),
            offer = opened ?: HandoverManifest(),
            allow = setOf("setlists", "photo", "personal_photo"),
            verified = opened != null,
            gallery = listOf(GalleryItem("content://mine/1", "h-m1")),
        )

        assertEquals(TimelineCache(), plan.merged)
        assertTrue(plan.request.isEmpty())
        assertTrue(plan.fromGallery.isEmpty())
    }
}
