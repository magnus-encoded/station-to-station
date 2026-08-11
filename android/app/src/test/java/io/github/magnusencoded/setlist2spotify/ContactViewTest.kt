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
import io.github.magnusencoded.setlist2spotify.data.Band
import io.github.magnusencoded.setlist2spotify.data.moveMedia
import io.github.magnusencoded.setlist2spotify.data.toBands
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
        // The band, not a night-level grant: an unshared night is one whose media is
        // all in the vault (#162).
        gigMedia = media.mapValues { (gigId, items) -> toBands(items, gigId in shared) },
        mediaTierMigrated = true,
    )

    // --- The tier ------------------------------------------------------------------

    /**
     * Nothing is shared until sharing is an act. The grant is prospective — it reaches
     * everyone who will ever become a **Contact** — so a default of "shared" would be a
     * grant nobody made. Since #162 the act is per photograph, at the moment it is
     * attached, and the vault is where the un-acted-on ones sit.
     */
    @Test
    fun `a photograph nobody put in the commons shows a contact nothing`() {
        val vaulted = listOf(mine("a", personal = true), mine("b", personal = true))
        assertTrue(visibleToContacts(vaulted).isEmpty())
        assertEquals(listOf("a", "b"), visibleToContacts(listOf(mine("a"), mine("b"))).map { it.id })
    }

    /** The bands are exactly the two halves: what is exposed, and what is held back. */
    @Test
    fun `Personal media is withheld and the two halves account for all of mine`() {
        val held = listOf(mine("a"), mine("b", personal = true), mine("c"))
        assertEquals(listOf("a", "c"), visibleToContacts(held).map { it.id })
        assertEquals(listOf("b"), withheldFromContacts(held).map { it.id })
    }

    /** A night with everything vaulted withholds all of it, and the view says so. */
    @Test
    fun `an all-vault night withholds all of it`() {
        val held = listOf(mine("a", personal = true), mine("b", personal = true))
        assertEquals(listOf("a", "b"), withheldFromContacts(held).map { it.id })
    }

    /**
     * Passing a contact's photograph on to my other contacts would be publishing on
     * their behalf — a second path for their picture they never agreed to and cannot
     * see. It is not mine to expose, and not mine to withhold either.
     */
    @Test
    fun `received media is never re-shared`() {
        val held = listOf(mine("a"), theirs("t"))
        assertEquals(listOf("a"), visibleToContacts(held).map { it.id })
        assertTrue(withheldFromContacts(held).none { it.id == "t" })
    }

    /** One photograph at a time: placing one in the commons says nothing about the rest. */
    @Test
    fun `sharing one photograph leaves every other night alone`() {
        val seen = contactMedia(
            mapOf("g1" to listOf(mine("a")), "g2" to listOf(mine("b", personal = true))),
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

    /**
     * Forward only, and now one photograph at a time: dragging it into the vault is
     * what closes the door. Nothing is deleted and the photograph stays on the night —
     * what changes is whether it is offered from here on.
     */
    @Test
    fun `moving a photograph into the vault takes it out of later manifests`() {
        val c = cache(shared = setOf("g1"), media = mapOf("g1" to listOf(mine("a"))))
        assertEquals(1, contactManifest(c, me).media.size)

        val after = c.copy(
            gigMedia = c.gigMedia + ("g1" to moveMedia(c.gigMedia.getValue("g1"), "a", Band.VAULT, 0)),
        )
        assertTrue(contactManifest(after, me).media.isEmpty())
        assertEquals(listOf("a"), after.gigMedia["g1"]?.map { it.id })
        assertTrue(after.gigMedia.getValue("g1").first().personal)
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
        val shown = contactMedia(c.gigMedia).values.flatten().map { it.id }.toSet()
        val offered = contactManifest(c, me).media.map { it.id }.toSet()

        assertEquals(shown, offered)
        assertEquals(setOf("a"), offered)
    }
}
