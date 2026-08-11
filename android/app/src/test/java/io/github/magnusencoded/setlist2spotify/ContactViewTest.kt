package io.github.magnusencoded.setlist2spotify

import io.github.magnusencoded.setlist2spotify.data.CATEGORY_SETLISTS
import io.github.magnusencoded.setlist2spotify.data.GalleryItem
import io.github.magnusencoded.setlist2spotify.data.StoredGig
import io.github.magnusencoded.setlist2spotify.data.StoredMedia
import io.github.magnusencoded.setlist2spotify.data.TimelineCache
import io.github.magnusencoded.setlist2spotify.data.categoriesFor
import io.github.magnusencoded.setlist2spotify.data.contactManifest
import io.github.magnusencoded.setlist2spotify.data.contactMedia
import io.github.magnusencoded.setlist2spotify.data.handoverPlan
import io.github.magnusencoded.setlist2spotify.data.stopSharing
import io.github.magnusencoded.setlist2spotify.data.visibleToContacts
import io.github.magnusencoded.setlist2spotify.data.withheldFromContacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two tiers (#144) and the view that shows them (#145). Pure: the gesture and the
 * tint are UI and are checked on a device; what can and cannot enter a manifest is the
 * part that matters, and it is decidable here.
 *
 * Fixtures invented. This repository is public and no real night, contact or photograph
 * enters one.
 */
class ContactViewTest {

    private val me = "my-public-key"
    private val them = "their-public-key"

    private fun mine(id: String, personal: Boolean = false) =
        StoredMedia(id = id, kind = StoredMedia.Kind.PHOTO, ref = "content://mine/$id", personal = personal)

    private fun theirs(id: String) =
        StoredMedia(id = id, kind = StoredMedia.Kind.PHOTO, ref = "content://mine/$id", from = them)

    private fun cache(shared: Set<String>, media: Map<String, List<StoredMedia>>) = TimelineCache(
        gigs = media.keys.associateWith { StoredGig(id = it, date = "12-06-2026", artist = "Paper Cranes") },
        gigMedia = media,
        sharedNights = shared,
    )

    // --- The tiers ---------------------------------------------------------------

    /**
     * Nothing is shared until sharing is an act. The grant is prospective — it reaches
     * everyone who will ever become a **Contact** — so a default of "shared" would be a
     * grant nobody made.
     */
    @Test
    fun `a night nobody shared shows a contact nothing`() {
        assertTrue(visibleToContacts(listOf(mine("a"), mine("b")), shared = false).isEmpty())
        assertEquals(
            listOf("a", "b"),
            visibleToContacts(listOf(mine("a"), mine("b")), shared = true).map { it.id },
        )
    }

    /** The stricter tier: **Personal** never leaves for anyone, shared night or not. */
    @Test
    fun `Personal media is withheld even on a night that is shared`() {
        val held = listOf(mine("a"), mine("b", personal = true), mine("c"))
        assertEquals(listOf("a", "c"), visibleToContacts(held, shared = true).map { it.id })
        assertEquals(listOf("b"), withheldFromContacts(held, shared = true).map { it.id })
    }

    /** On an unshared night everything of mine is withheld, and the view says so. */
    @Test
    fun `an unshared night withholds all of it`() {
        val held = listOf(mine("a"), mine("b", personal = true))
        assertEquals(listOf("a", "b"), withheldFromContacts(held, shared = false).map { it.id })
    }

    /**
     * Passing a contact's photograph on to my other contacts would be publishing on
     * their behalf — a second path for their picture they never agreed to and cannot
     * see. It is not mine to expose, and not mine to withhold either.
     */
    @Test
    fun `received media is never re-shared`() {
        val held = listOf(mine("a"), theirs("t"))
        assertEquals(listOf("a"), visibleToContacts(held, shared = true).map { it.id })
        assertTrue(withheldFromContacts(held, shared = true).none { it.id == "t" })
    }

    /** One night at a time: the act is the **Room**, and it does not reach the next one. */
    @Test
    fun `sharing one night leaves every other night alone`() {
        val seen = contactMedia(
            mapOf("g1" to listOf(mine("a")), "g2" to listOf(mine("b"))),
            sharedNights = setOf("g1"),
        )
        assertEquals(listOf("a"), seen["g1"]?.map { it.id })
        assertTrue(seen["g2"].isNullOrEmpty())
    }

    // --- What can enter a manifest -----------------------------------------------

    /**
     * The assertion that matters most, because the failure is silent and irreversible:
     * **no combination of ticked categories puts a Personal item in a contact's
     * manifest.** Exclusion is at construction, so there is no box to mis-tap.
     */
    @Test
    fun `no ticked category can put a Personal item in a contact's manifest`() {
        val c = cache(
            shared = setOf("g1", "g2"),
            media = mapOf(
                "g1" to listOf(mine("a"), mine("secret", personal = true)),
                "g2" to listOf(mine("b", personal = true)),
            ),
        )
        val manifest = contactManifest(c, me)

        assertTrue(manifest.media.none { it.personal })
        assertEquals(setOf("a"), manifest.media.map { it.id }.toSet())
        assertTrue(manifest.timeline.gigMedia.values.flatten().none { it.personal })

        // Even with every category ticked, including the ones only my own device has,
        // it is not there to send: the manifest is the whole of what exists.
        val plan = handoverPlan(
            mine = TimelineCache(),
            offer = manifest,
            allow = categoriesFor(contact = false),
            verified = true,
        )
        assertTrue(plan.request.none { it == "secret" })
    }

    /** Absent, not unticked: there is no **Personal** category for a contact at all. */
    @Test
    fun `the Personal categories do not exist for a contact`() {
        val forContact = categoriesFor(contact = true)
        assertFalse(forContact.any { it.startsWith("personal_") })
        assertTrue(forContact.contains(CATEGORY_SETLISTS))
        // My own other phone is the case where they do exist — privacy must not cost me
        // my own record.
        assertTrue(categoriesFor(contact = false).contains("personal_photo"))
    }

    /** A picture that arrives unattributed silently becomes the receiver's. */
    @Test
    fun `attribution is in the envelope and survives the transfer`() {
        val c = cache(shared = setOf("g1"), media = mapOf("g1" to listOf(mine("a"))))
        val manifest = contactManifest(c, me)
        assertEquals(listOf(me), manifest.media.map { it.from })

        // Resolved from the receiver's own gallery rather than sent — and the record
        // that lands still says whose camera it came from.
        val plan = handoverPlan(
            mine = TimelineCache(gigs = manifest.timeline.gigs),
            offer = manifest,
            allow = categoriesFor(contact = true),
            verified = true,
            gallery = listOf(GalleryItem(ref = "content://mine/copy", hash = "")),
        )
        assertEquals(mapOf("a" to "content://mine/copy"), plan.fromGallery)
        assertEquals(me, plan.merged.gigMedia["g1"]?.single()?.from)
    }

    /** Forward only. Nothing is deleted, and no item's own **Personal** bit changes. */
    @Test
    fun `stopping sharing closes the door and takes the night out of later manifests`() {
        val c = cache(shared = setOf("g1"), media = mapOf("g1" to listOf(mine("a"))))
        assertEquals(1, contactManifest(c, me).media.size)

        val after = c.copy(sharedNights = stopSharing(c.sharedNights, "g1"))
        assertTrue(contactManifest(after, me).media.isEmpty())
        // The photograph is still mine and still on the night.
        assertEquals(listOf("a"), after.gigMedia["g1"]?.map { it.id })
        assertFalse(after.gigMedia.getValue("g1").first().personal)
    }

    /**
     * The agreement assertion: what the contact's-eye view shows and what a contact is
     * actually offered are the same set. If two implementations can disagree they
     * eventually will, and the direction of that disagreement is showing someone less
     * than they are being sent.
     */
    @Test
    fun `what the view shows is exactly what a contact is offered`() {
        val c = cache(
            shared = setOf("g1"),
            media = mapOf(
                "g1" to listOf(mine("a"), mine("b", personal = true), theirs("t")),
                "g2" to listOf(mine("c")),
            ),
        )
        val shown = contactMedia(c.gigMedia, c.sharedNights).values.flatten().map { it.id }.toSet()
        val offered = contactManifest(c, me).media.map { it.id }.toSet()

        assertEquals(shown, offered)
        assertEquals(setOf("a"), offered)
    }
}
