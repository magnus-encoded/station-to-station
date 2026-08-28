package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.CATEGORY_SETLISTS
import io.github.magnusencoded.stationtostation.data.GalleryItem
import io.github.magnusencoded.stationtostation.data.StoredGig
import io.github.magnusencoded.stationtostation.data.StoredMedia
import io.github.magnusencoded.stationtostation.data.TimelineCache
import io.github.magnusencoded.stationtostation.data.categoriesFor
import io.github.magnusencoded.stationtostation.data.contactManifest
import io.github.magnusencoded.stationtostation.data.contactMedia
import io.github.magnusencoded.stationtostation.data.StoredAttendance
import io.github.magnusencoded.stationtostation.data.isMyNight
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.data.handoverPlan
import io.github.magnusencoded.stationtostation.data.Band
import io.github.magnusencoded.stationtostation.data.moveMedia
import io.github.magnusencoded.stationtostation.data.toBands
import io.github.magnusencoded.stationtostation.data.visibleToContacts
import io.github.magnusencoded.stationtostation.data.withheldFromContacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
     * The timeline's lit subset, which is the expression StationScreen hands the strip.
     *
     * The tint is a device concern, but *which* thumbnails are lit is decidable here,
     * and it is the half that was wrong: the strip dimmed as one row, so a night with an
     * empty vault drew exactly as dark as a night that held everything back. That is not
     * a weak signal, it is a false one — the same defect the doc on
     * [withheldFromContacts] names about absence, arrived at through uniformity instead.
     *
     * Pinned to [visibleToContacts] rather than to `!personal`, because ContactView.kt
     * is explicit that a second implementation of this rule eventually disagrees with
     * the first, in the direction of showing someone less than they are being sent.
     */
    @Test
    fun `the lit thumbnails are exactly the ones a contact is sent`() {
        fun lit(media: List<StoredMedia>) = visibleToContacts(media).map { it.ref }.toSet()

        // The night that used to lie: nothing held back, yet drawn as if it were.
        val nothingHeldBack = listOf(mine("a"), mine("b"))
        assertEquals(nothingHeldBack.map { it.ref }.toSet(), lit(nothingHeldBack))

        // The night it was indistinguishable from.
        val allHeldBack = listOf(mine("a", personal = true), mine("b", personal = true))
        assertTrue(lit(allHeldBack).isEmpty())

        // And the two must not agree, which is the whole point of the switch.
        assertNotEquals(lit(nothingHeldBack), lit(allHeldBack))

        // Mixed: the shared ones light, the vaulted one does not, and the strip still
        // draws all three — so the count is unchanged and no row can change height.
        val mixed = listOf(mine("a"), mine("b", personal = true), mine("c"))
        assertEquals(setOf("content://mine/a", "content://mine/c"), lit(mixed))
        assertEquals(3, mixed.size)

        // A contact's own photograph is never lit: it is not mine to pass on, and a
        // thumbnail drawn at full strength would claim I am sending it.
        assertTrue(lit(listOf(theirs("t"))).isEmpty())
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

    /**
     * A **Contact** is offered media from a shared night, and a [TimelineCache] carries far
     * more than that — the Log, attendance and how it was decided, tickets held, playlists
     * made, every band's shows, my totals. `cache.copy(gigMedia = …)`, which is what this
     * built, sent all of it because it happened to be in the same class (#267).
     */
    @Test
    fun `a contact's manifest carries only the media and the nights it is offered on`() {
        val c = cache(
            shared = setOf("g1"),
            media = mapOf("g1" to listOf(mine("a")), "g2" to listOf(mine("b", personal = true))),
        ).copy(
            shows = mapOf(me to emptyList()),
            attendedTotals = mapOf(me to 412),
            playlistsMade = mapOf("sl-1" to emptyList()),
        )

        val timeline = contactManifest(c, me).timeline

        // The two fields contactLanding reads, and nothing else.
        assertEquals(setOf("g1"), timeline.gigMedia.keys)
        // Narrowed too: the full gigs map is the complete list of every night I have ever
        // attended, which is a different disclosure than the one being made.
        assertEquals(setOf("g1"), timeline.gigs.keys)
        assertEquals(TimelineCache().attendedTotals, timeline.attendedTotals)
        assertEquals(TimelineCache().shows, timeline.shows)
        assertEquals(TimelineCache().playlistsMade, timeline.playlistsMade)
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
        // that lands still says whose camera it came from. The match is by hash and
        // only by hash: two items that were never hashed are not the same picture.
        val plan = handoverPlan(
            mine = TimelineCache(gigs = manifest.timeline.gigs),
            offer = manifest.copy(media = manifest.media.map { it.copy(hash = "same-bytes") }),
            allow = categoriesFor(contact = true),
            verified = true,
            gallery = listOf(GalleryItem(ref = "content://mine/copy", hash = "same-bytes")),
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

    // ---- text obeys the same tier line as everything else (#50) --------------

    private fun myNote(id: String, text: String, personal: Boolean, verdict: String? = null) =
        StoredMedia(
            id = id,
            kind = StoredMedia.Kind.NOTE,
            ref = "",
            text = text,
            personal = personal,
            verdict = verdict,
        )

    @Test
    fun `a note in the vault reaches nobody`() {
        val night = listOf(myNote("draft", "not sure about this one", personal = true))
        assertTrue(visibleToContacts(night).isEmpty())
        assertEquals(listOf("draft"), withheldFromContacts(night).map { it.id })
    }

    /**
     * A note has no bytes, so there is no second phase to fetch it in: it either
     * rides the manifest or it never arrives. That makes the text and the verdict
     * part of the envelope rather than part of a payload.
     */
    @Test
    fun `a shared note travels with its words and its verdict intact`() {
        val c = cache(
            shared = setOf("g1"),
            media = mapOf(
                "g1" to listOf(
                    myNote("said", "they ruled", personal = false, verdict = StoredMedia.Verdict.DOUBLE_UP),
                ),
            ),
        )
        val offered = contactManifest(c, me).media.single()
        assertEquals("said", offered.id)
        assertEquals("they ruled", offered.text)
        assertEquals(StoredMedia.Verdict.DOUBLE_UP, offered.verdict)
        assertEquals("", offered.hash)
        assertEquals(0L, offered.bytes)
    }

    @Test
    fun `a vault note never enters a contact manifest`() {
        val c = cache(
            shared = setOf("g1"),
            media = mapOf("g1" to listOf(myNote("draft", "for me", personal = true), mine("a"))),
        )
        assertEquals(setOf("a"), contactManifest(c, me).media.map { it.id }.toSet())
    }

    /**
     * Passing on someone's words would be publishing on their behalf — a second path
     * for their sentence that they never agreed to and cannot see. Same rule their
     * photograph already got.
     */
    @Test
    fun `a received note is not passed on to my other contacts`() {
        val given = myNote("t", "loved it", personal = false).copy(from = them)
        assertTrue(visibleToContacts(listOf(given)).isEmpty())
    }

    @Test
    fun `the note categories exist for my own device and the personal one never for a contact`() {
        assertTrue(categoriesFor(contact = true).contains(StoredMedia.Kind.NOTE))
        assertFalse(categoriesFor(contact = true).contains("personal_note"))
        // My own other phone: a draft has to travel, or keeping it back costs me the
        // material I write from.
        assertTrue(categoriesFor(contact = false).contains("personal_note"))
    }

    // --- Whose night is this (#327) ------------------------------------------------

    private fun night(id: String) = FmSetlist(id = id, eventDate = "25-06-2026")

    /**
     * The defect this rule exists to stop: a **Contact**'s night is reachable from the
     * timeline exactly like one of mine, and every editing affordance on it was offered.
     * Attaching acquired the night.
     */
    @Test
    fun `a contact's night is not mine`() {
        assertFalse(
            isMyNight("theirs", null, listOf(night("a"), night("b")), listOf(night("c"))),
        )
    }

    @Test
    fun `a night on my own line is mine`() {
        assertTrue(isMyNight("b", null, listOf(night("a"), night("b")), emptyList()))
    }

    /**
     * A ticket I hold is my night before it has happened — there is nothing on my
     * attended line yet, and it is still mine to put a note on.
     */
    @Test
    fun `a night I am going to is mine`() {
        assertTrue(isMyNight("c", null, emptyList(), listOf(night("c"))))
    }

    /**
     * The most direct answer, and the reason it is asked first: an attendance record is
     * the app saying I was there. A night minted by standing in front of the stage is on
     * neither list.
     */
    @Test
    fun `an attendance claim is enough on its own`() {
        assertTrue(isMyNight("x", StoredAttendance(), emptyList(), emptyList()))
    }
}
