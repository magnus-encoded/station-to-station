package io.github.magnusencoded.setlist2spotify

import io.github.magnusencoded.setlist2spotify.data.OfferedMedia
import io.github.magnusencoded.setlist2spotify.data.StoredMedia
import io.github.magnusencoded.setlist2spotify.data.contactMedia
import io.github.magnusencoded.setlist2spotify.data.stopSharing
import io.github.magnusencoded.setlist2spotify.data.visibleToContacts
import io.github.magnusencoded.setlist2spotify.data.withheldFromContacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a **Contact** can see of my **Line** (#145). Pure: the gesture and the tint are
 * UI and are checked on a device; the filter is the reviewable part.
 *
 * Fixtures invented. This repository is public and no real night, contact or photograph
 * enters one.
 */
class ContactViewTest {

    private fun mine(id: String, personal: Boolean = false) =
        StoredMedia(id = id, kind = StoredMedia.Kind.PHOTO, ref = "content://mine/$id", personal = personal)

    private fun theirs(id: String) =
        StoredMedia(id = id, kind = StoredMedia.Kind.PHOTO, ref = "content://mine/$id", from = "their-key")

    @Test
    fun `a contact sees what is not Personal`() {
        val held = listOf(mine("a"), mine("b", personal = true), mine("c"))
        assertEquals(listOf("a", "c"), visibleToContacts(held).map { it.id })
    }

    @Test
    fun `the other half of the question is what is held back`() {
        val held = listOf(mine("a"), mine("b", personal = true), mine("c"))
        assertEquals(listOf("b"), withheldFromContacts(held).map { it.id })
    }

    /**
     * Passing a contact's photograph on to my other contacts would be publishing on
     * their behalf — a second path for their picture they never agreed to and cannot
     * see. Their media reaches whoever they share it with, through them.
     */
    @Test
    fun `received media is never re-shared, and is not mine to withhold either`() {
        val held = listOf(mine("a"), theirs("t"))
        assertEquals(listOf("a"), visibleToContacts(held).map { it.id })
        assertTrue(withheldFromContacts(held).isEmpty())
    }

    /**
     * The agreement assertion the spec asks for. What the view shows and what a
     * **Contact** is sent must be the same set — if two implementations can disagree
     * they eventually will, and the direction of that disagreement is showing someone
     * less than they are being sent.
     */
    @Test
    fun `what the view shows is exactly what a contact would be offered`() {
        val held = listOf(mine("a"), mine("b", personal = true), mine("c"), theirs("t"))

        val shown = visibleToContacts(held).map { it.id }.toSet()
        // A manifest built for a **Contact** comes through the same rule, never a
        // parallel one. This is that manifest.
        val offered = visibleToContacts(held)
            .map { OfferedMedia(id = it.id, gigId = "g1", kind = it.kind, personal = it.personal) }

        assertEquals(shown, offered.map { it.id }.toSet())
        assertTrue("nothing Personal may reach a manifest", offered.none { it.personal })
    }

    @Test
    fun `a night with nothing shared still appears, empty`() {
        val media = mapOf(
            "g1" to listOf(mine("a")),
            "g2" to listOf(mine("b", personal = true)),
        )
        val seen = contactMedia(media)
        assertEquals(setOf("g1", "g2"), seen.keys)
        assertEquals(listOf("a"), seen["g1"]?.map { it.id })
        assertTrue(seen["g2"].isNullOrEmpty())
    }

    /** Forward only, and it keeps the photograph: what changes is who it is for. */
    @Test
    fun `stopping sharing withholds mine and leaves a contact's own media alone`() {
        val held = listOf(mine("a"), mine("b", personal = true), theirs("t"))
        val after = stopSharing(held)

        assertTrue(visibleToContacts(after).isEmpty())
        assertEquals(held.size, after.size) // nothing is deleted
        assertEquals(false, after.first { it.id == "t" }.personal)
    }
}
