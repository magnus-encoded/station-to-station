package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.GalleryItem
import io.github.magnusencoded.stationtostation.data.HandoverManifest
import io.github.magnusencoded.stationtostation.data.OfferedMedia
import io.github.magnusencoded.stationtostation.data.StoredGig
import io.github.magnusencoded.stationtostation.data.StoredMedia
import io.github.magnusencoded.stationtostation.data.TimelineCache
import io.github.magnusencoded.stationtostation.data.acceptingOffer
import io.github.magnusencoded.stationtostation.data.contactLanding
import io.github.magnusencoded.stationtostation.data.contactOffers
import io.github.magnusencoded.stationtostation.data.decliningOffer
import io.github.magnusencoded.stationtostation.data.holdingOffers
import io.github.magnusencoded.stationtostation.data.joinedNights
import io.github.magnusencoded.stationtostation.data.joiningNight
import io.github.magnusencoded.stationtostation.data.dismissingMaybe
import io.github.magnusencoded.stationtostation.data.undismissingMaybe
import io.github.magnusencoded.stationtostation.data.unjoiningNight
import io.github.magnusencoded.stationtostation.data.spineDismissals
import io.github.magnusencoded.stationtostation.data.spineJoins
import io.github.magnusencoded.stationtostation.data.waitingOn
import io.github.magnusencoded.stationtostation.data.contactManifest
import io.github.magnusencoded.stationtostation.data.contactReconcilePlan
import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.data.StoredAttendance
import io.github.magnusencoded.stationtostation.data.landNights
import io.github.magnusencoded.stationtostation.data.laneNeedsFetch
import io.github.magnusencoded.stationtostation.data.localGigSetlist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmArtist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSet
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSets
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

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

    // --- Nights ride the Reconcile offer (#405) ---
    //
    // A Contact with no setlist.fm account has no address to fetch a Lane from, so the
    // Lane is what they hand over. Hand-logged and imported Nights go on the same terms.

    private val imported = FmSetlist(
        id = "sl-imported", eventDate = "13-08-2026", artist = FmArtist(name = "Wilco"),
        url = "https://www.setlist.fm/setlist/wilco/2026/x.html",
    )
    private val handLogged = localGigSetlist(
        gigId = "local-1", artist = "Nick Cave", date = LocalDate.of(2026, 8, 14),
        venue = "Tøyenparken", city = "Oslo",
    )

    private fun theirCache() = TimelineCache(
        shows = mapOf("theirs" to listOf(imported)),
        gigPlanned = mapOf("local-1" to handLogged),
        gigAttendance = mapOf("local-1" to StoredAttendance(provenance = StoredAttendance.Provenance.ATTENDED)),
    )

    @Test
    fun `an unverified peer's nights are not taken`() {
        val offer = HandoverManifest(nights = listOf(imported, handLogged))

        assertTrue(contactReconcilePlan(TimelineCache(), offer, verified = false).nights.isEmpty())
    }

    @Test
    fun `hand-logged nights are offered on the same terms as imported ones`() {
        val offer = contactManifest(theirCache(), me = "their-key", setlistfm = "theirs")

        assertEquals(setOf("sl-imported", "local-1"), offer.nights.map { it.id }.toSet())
        val plan = contactReconcilePlan(TimelineCache(), offer, verified = true)
        assertEquals(setOf("sl-imported", "local-1"), plan.nights.map { it.id }.toSet())
    }

    /** No account is no reason to offer less: what I logged by hand is my whole Line. */
    @Test
    fun `a contact with no account still offers every night they logged`() {
        val offer = contactManifest(theirCache(), me = "their-key", setlistfm = "")

        assertEquals(listOf("local-1"), offer.nights.map { it.id })
    }

    @Test
    fun `nights the far end already holds are not taken again`() {
        val offer = HandoverManifest(nights = listOf(imported, handLogged))

        val plan = contactReconcilePlan(TimelineCache(), offer, verified = true, heldLane = listOf(imported))

        assertEquals(listOf("local-1"), plan.nights.map { it.id })
    }

    @Test
    fun `a lane already whole takes nothing, and running it twice is running it once`() {
        val offer = HandoverManifest(nights = listOf(imported, handLogged, handLogged))

        val first = contactReconcilePlan(TimelineCache(), offer, verified = true)
        val lane = landNights(null, first.nights)
        val second = contactReconcilePlan(TimelineCache(), offer, verified = true, heldLane = lane)

        assertEquals(listOf("local-1", "sl-imported"), lane.map { it.id })
        assertTrue(second.nights.isEmpty())
    }

    @Test
    fun `a night with no id is no night`() {
        val offer = HandoverManifest(nights = listOf(imported.copy(id = "")))

        assertTrue(contactReconcilePlan(TimelineCache(), offer, verified = true).nights.isEmpty())
    }

    @Test
    fun `the offer carries no songs`() {
        val withSongs = imported.copy(sets = FmSets(set = listOf(FmSet(song = listOf(FmSong(name = "Jesus, Etc."))))))
        val cache = theirCache().copy(shows = mapOf("theirs" to listOf(withSongs)))

        assertTrue(contactManifest(cache, me = "their-key", setlistfm = "theirs").nights.all { it.sets == null })
    }

    /** A received Lane is held, so an account-less Contact is never sent to setlist.fm. */
    @Test
    fun `an account-less contact's received lane draws without a fetch`() {
        val dio = Friend(setlistfm = "", name = "Dio", publicKey = "k-dio")
        val offer = contactManifest(theirCache(), me = "k-dio", setlistfm = "")
        val lane = landNights(null, contactReconcilePlan(TimelineCache(), offer, verified = true).nights)

        assertEquals(listOf("local-1"), lane.map { it.id })
        assertFalse(laneNeedsFetch(dio, lane, LocalDate.of(2019, 6, 25)))
    }

    // --- Media a Contact sends is offered, never filed (#405) ---
    //
    // Another person's belief that we shared a Night must never write onto my record. What
    // they send for a Night I hold under the same catalogue id merges as it always did;
    // what they send for a Night I have not joined waits as an offer until I answer it.

    /** My own hand-logged Night on 14-08-2026, and the gig record it lives under. */
    private val myNight = localGigSetlist(
        gigId = "my-local", artist = "Nick Cave", date = LocalDate.of(2026, 8, 14),
        venue = "Tøyenparken", city = "Oslo",
    )
    private val myGig = StoredGig(id = "my-local", date = "14-08-2026", artist = "Nick Cave", venue = "Tøyenparken")

    /** Their hand-logged record of the same evening: another id, so nothing links the two. */
    private fun theirOffer(vararg items: StoredMedia) = HandoverManifest(
        timeline = TimelineCache(
            gigs = mapOf("their-local" to StoredGig(
                id = "their-local", date = "14-08-2026", artist = "Nick Cave", venue = "Tøyenparken",
            )),
            gigMedia = mapOf("their-local" to items.toList()),
        ),
        media = items.map { offered(it.id, "h-${it.id}") },
    )

    @Test
    fun `media for a night I hold under the same catalogue id merges and is not offered`() {
        val mine = TimelineCache(gigs = mapOf("mine-gig" to StoredGig(id = "mine-gig", date = "13-08-2026", setlistId = "sl-1")))
        val offer = HandoverManifest(
            timeline = TimelineCache(
                gigs = mapOf("their-gig" to StoredGig(id = "their-gig", date = "13-08-2026", setlistId = "sl-1")),
                gigMedia = mapOf("their-gig" to listOf(photo("m1"))),
            ),
            media = listOf(offered("m1", "h1")),
        )
        val resolved = mapOf("m1" to "content://received/m1")
        val spine = listOf(FmSetlist(id = "sl-1", eventDate = "13-08-2026"))

        assertEquals(listOf("m1"), contactLanding(mine, offer, resolved).getValue("mine-gig").map { it.id })
        assertTrue(contactOffers(mine, offer, resolved, spine).isEmpty())
    }

    @Test
    fun `media for a night I have not joined is offered and my timeline is untouched`() {
        val mine = TimelineCache(gigs = mapOf("my-local" to myGig))
        val offer = theirOffer(photo("m1"), photo("m2"))
        val resolved = mapOf("m1" to "content://received/m1", "m2" to "content://received/m2")

        val landing = contactLanding(mine, offer, resolved)
        val offers = contactOffers(mine, offer, resolved, listOf(myNight))
        val held = mine.holdingOffers(offers)

        assertTrue(landing.isEmpty())
        val waiting = offers.getValue("their-local")
        assertEquals("14-08-2026", waiting.date)
        assertEquals(listOf("m1", "m2"), waiting.media.map { it.id })
        assertEquals("content://received/m1", waiting.media.first().ref)
        assertEquals("their-key", waiting.media.first().from)
        // Held apart: the Night's media and the joins are exactly what they were.
        assertEquals(mine.gigMedia, held.gigMedia)
        assertEquals(mine.gigs, held.gigs)
        assertTrue(joinedNights(held).isEmpty())
        assertEquals(listOf("their-local"), held.mediaOffers.waitingOn("14-08-2026").map { it.first })
    }

    /** Their manifest carries everything they share, not only what we might have in common. */
    @Test
    fun `media for a night on a date I was not out is neither filed nor offered`() {
        val offer = theirOffer(photo("m1"))
        val resolved = mapOf("m1" to "content://received/m1")
        val elsewhere = myNight.copy(eventDate = "01-01-2026")

        assertTrue(contactLanding(TimelineCache(), offer, resolved).isEmpty())
        assertTrue(contactOffers(TimelineCache(), offer, resolved, listOf(elsewhere)).isEmpty())
    }

    @Test
    fun `declining changes nothing on my timeline and is not asked again`() {
        val mine = TimelineCache(gigs = mapOf("my-local" to myGig), gigMedia = mapOf("my-local" to listOf(photo("mine"))))
        val offer = theirOffer(photo("m1"))
        val resolved = mapOf("m1" to "content://received/m1")
        val held = mine.holdingOffers(contactOffers(mine, offer, resolved, listOf(myNight)))

        val declined = held.decliningOffer("their-local")

        assertEquals(mine.gigMedia, declined.gigMedia)
        assertEquals(mine.gigs, declined.gigs)
        assertTrue(joinedNights(declined).isEmpty())
        assertTrue(declined.mediaOffers.waitingOn("14-08-2026").isEmpty())
        // The next Reconcile brings the same photo again: it is not offered a second time.
        val again = declined.holdingOffers(contactOffers(declined, offer, resolved, listOf(myNight)))
        assertTrue(again.mediaOffers.waitingOn("14-08-2026").isEmpty())
    }

    @Test
    fun `accepting files the media on my night and joins it`() {
        val mine = TimelineCache(gigs = mapOf("my-local" to myGig), gigMedia = mapOf("my-local" to listOf(photo("mine"))))
        val resolved = mapOf("m1" to "content://received/m1", "m2" to "content://received/m2")
        val held = mine.holdingOffers(contactOffers(mine, theirOffer(photo("m1")), resolved, listOf(myNight)))

        val accepted = held.acceptingOffer("their-local", "my-local")

        assertEquals(listOf("mine", "m1"), accepted.gigMedia.getValue("my-local").map { it.id })
        assertEquals("their-key", accepted.gigMedia.getValue("my-local").last().from)
        assertEquals(mapOf("their-local" to "my-local"), joinedNights(accepted).filterKeys { it == "their-local" })
        assertTrue(accepted.mediaOffers.isEmpty())
        val later = theirOffer(photo("m1"), photo("m2"))
        assertEquals(listOf("m1", "m2"), contactLanding(accepted, later, resolved).getValue("my-local").map { it.id })
        assertTrue(contactOffers(accepted, later, resolved, listOf(myNight)).isEmpty())
    }

    @Test
    fun `an unsafe id is never offered`() {
        val mine = TimelineCache(gigs = mapOf("my-local" to myGig))
        val offer = theirOffer(photo("../evil"), photo("m1"))
        val resolved = mapOf("../evil" to "content://received/evil", "m1" to "content://received/m1")

        val offers = contactOffers(mine, offer, resolved, listOf(myNight))

        assertEquals(listOf("m1"), offers.getValue("their-local").media.map { it.id })
    }

    @Test
    fun `same night joins it, so what they send lands directly and nothing is offered`() {
        val mine = TimelineCache(gigs = mapOf("my-local" to myGig))
        val resolved = mapOf("m1" to "content://received/m1")

        val joined = mine.joiningNight("their-local", "my-local")

        assertEquals("my-local", joinedNights(joined)["their-local"])
        assertEquals(mapOf("their-local" to "my-local"), joined.spineJoins())
        assertEquals(listOf("m1"), contactLanding(joined, theirOffer(photo("m1")), resolved).getValue("my-local").map { it.id })
        assertTrue(contactOffers(joined, theirOffer(photo("m1")), resolved, listOf(myNight)).isEmpty())
        assertEquals(mine.gigMedia, joined.gigMedia)
        assertEquals(mine.gigs, joined.gigs)
    }

    @Test
    fun `not the same is remembered per pair, once, and joins nothing`() {
        val mine = TimelineCache(gigs = mapOf("my-local" to myGig))

        val apart = mine.dismissingMaybe("their-local", "my-local").dismissingMaybe("their-local", "my-local")

        assertEquals(mapOf("their-local" to listOf("my-local")), apart.nightDismissals)
        assertEquals(mapOf("their-local" to setOf("my-local")), apart.spineDismissals())
        assertTrue(joinedNights(apart).isEmpty())
        assertEquals(mine.gigMedia, apart.gigMedia)
    }

    /** The Spine knows a Night by its setlist.fm id once it has one; the answer follows it. */
    @Test
    fun `an answer is read back under the id the spine uses`() {
        val adopted = StoredGig(id = "my-local", setlistId = "sfm-1", date = "14-08-2026")
        val mine = TimelineCache(gigs = mapOf("my-local" to adopted))
            .joiningNight("their-a", "my-local")
            .dismissingMaybe("their-b", "my-local")

        assertEquals(mapOf("their-a" to "sfm-1"), mine.spineJoins())
        assertEquals(mapOf("their-b" to setOf("sfm-1")), mine.spineDismissals())
    }

    // --- Undo (#580): an answer taken back is the maybe asked again. ---

    @Test
    fun `undoing same night takes the join away and nothing else`() {
        val mine = TimelineCache(gigs = mapOf("my-local" to myGig))
            .joiningNight("their-b", "my-local")

        val undone = mine.joiningNight("their-local", "my-local").unjoiningNight("their-local", "my-local")

        assertEquals(mine, undone)
        assertEquals(mapOf("their-b" to "my-local"), undone.nightJoins)
    }

    @Test
    fun `undo leaves a join to another night of mine alone`() {
        val mine = TimelineCache(gigs = mapOf("my-local" to myGig))
            .joiningNight("their-local", "my-other")

        assertEquals(mine, mine.unjoiningNight("their-local", "my-local"))
    }

    @Test
    fun `undoing not the same marks that pair again and keeps the others apart`() {
        val mine = TimelineCache(gigs = mapOf("my-local" to myGig))
        val once = mine.dismissingMaybe("their-local", "my-local")

        assertEquals(mine, once.undismissingMaybe("their-local", "my-local"))
        assertTrue(once.undismissingMaybe("their-local", "my-local").nightDismissals.isEmpty())

        val two = once.dismissingMaybe("their-local", "my-other")
        assertEquals(
            mapOf("their-local" to listOf("my-other")),
            two.undismissingMaybe("their-local", "my-local").nightDismissals,
        )
        assertEquals(two, two.undismissingMaybe("their-local", "nobody"))
    }
}
