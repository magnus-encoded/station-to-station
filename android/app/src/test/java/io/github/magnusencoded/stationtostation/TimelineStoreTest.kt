package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.StoredAttendance
import io.github.magnusencoded.stationtostation.data.StoredFestival
import io.github.magnusencoded.stationtostation.data.StoredLog
import io.github.magnusencoded.stationtostation.data.StoredMedia
import io.github.magnusencoded.stationtostation.data.StoredPlaylist
import io.github.magnusencoded.stationtostation.data.TimelineStore
import io.github.magnusencoded.stationtostation.data.fmDate
import io.github.magnusencoded.stationtostation.data.isLocal
import io.github.magnusencoded.stationtostation.data.localGigSetlist
import io.github.magnusencoded.stationtostation.data.parseFmDate
import io.github.magnusencoded.stationtostation.data.plannedLane
import io.github.magnusencoded.stationtostation.data.setlistfm.FmArtist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmCity
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmVenue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TimelineStoreTest {

    private fun store() = TimelineStore(File.createTempFile("timelines", ".json").also { it.delete() })

    private fun show(id: String) = FmSetlist(id = id, eventDate = "25-06-2026", artist = FmArtist(name = "The Warning"))

    // Ids fixed rather than random so an assertion can name one.
    private fun photo(ref: String) = StoredMedia(id = "m-$ref", kind = StoredMedia.Kind.PHOTO, ref = ref)

    private fun video(ref: String) = StoredMedia(id = "m-$ref", kind = StoredMedia.Kind.VIDEO, ref = ref)

    @Test
    fun `missing file loads empty rather than throwing`() = runBlocking {
        val cache = store().load()
        assertTrue(cache.shows.isEmpty())
        assertTrue(cache.festivalNames.isEmpty())
    }

    @Test
    fun `a saved timeline round-trips`() = runBlocking {
        val store = store()
        store.save(shows = mapOf("magnus" to listOf(show("a"), show("b"))))
        assertEquals(listOf("a", "b"), store.load().shows["magnus"]?.map { it.id })
    }

    @Test
    fun `saving one lane leaves the others alone`() = runBlocking {
        val store = store()
        store.save(shows = mapOf("magnus" to listOf(show("a"))))
        store.save(shows = mapOf("Ozzy" to listOf(show("b"))))
        val shows = store.load().shows
        assertEquals(listOf("a"), shows["magnus"]?.map { it.id })
        assertEquals(listOf("b"), shows["Ozzy"]?.map { it.id })
    }

    @Test
    fun `a failed fetch does not wipe the last good lane`() = runBlocking {
        val store = store()
        store.save(shows = mapOf("Ozzy" to listOf(show("a"))))
        // loadFriendTimelines() puts an empty list in the map when a fetch throws.
        store.save(shows = mapOf("Ozzy" to emptyList()))
        assertEquals(listOf("a"), store.load().shows["Ozzy"]?.map { it.id })
    }

    @Test
    fun `festival names accumulate across saves`() = runBlocking {
        val store = store()
        store.save(festivalNames = mapOf("a" to "Tons of Rock"))
        store.save(shows = mapOf("magnus" to listOf(show("a"))))
        assertEquals(mapOf("a" to "Tons of Rock"), store.load().festivalNames)
    }

    // --- #166: a Festival gets an identity, and a name filed under the old venue+date
    // window becomes one, migrated rather than recomputed live -----------------------

    /**
     * A cache written before #166: a resolved name filed under its cluster's first
     * show id, next to the two nights that earned it (same venue, four days apart —
     * the window `groupIntoFestivals` used to draw and no longer does). The migration
     * replays that old window once, on read, purely to find who the name belongs to.
     */
    @Test
    fun `a name filed under the old venue-window cluster becomes a Festival identity`() = runBlocking {
        val file = File.createTempFile("timelines", ".json")
        file.writeText(
            """{"shows":{"magnus":[""" +
                """{"id":"a1","eventDate":"25-06-2026","artist":{"name":"Gojira"},"venue":{"name":"Ekebergsletta"}},""" +
                """{"id":"a2","eventDate":"27-06-2026","artist":{"name":"Ghost"},"venue":{"name":"Ekebergsletta"}}""" +
                """]},"festivalNames":{"a1":"Tons of Rock"}}"""
        )
        val cached = TimelineStore(file).load()

        assertTrue(cached.festivalsMigrated)
        // The old key survives untouched — an older build restored later still reads it.
        assertEquals(mapOf("a1" to "Tons of Rock"), cached.festivalNames)

        val identity = cached.festivals.values.single()
        assertEquals("Tons of Rock", identity.name)
        assertEquals(StoredFestival.FestivalSource.SCRAPED, identity.source)
        // Both nights the window would have clustered now carry the identity's id —
        // not just the one the name happened to be filed under.
        assertEquals(identity.id, cached.festivalIdByShow["a1"])
        assertEquals(identity.id, cached.festivalIdByShow["a2"])
    }

    /** Idempotent, and the flag rather than an empty map is what makes it so — an
     *  already-migrated cache with nothing left in [TimelineCache.festivalNames] must
     *  not be mistaken for one that has nothing to migrate. */
    @Test
    fun `the festival migration does not run a second time`() = runBlocking {
        val file = File.createTempFile("timelines", ".json")
        file.writeText(
            """{"shows":{"magnus":[{"id":"a1","eventDate":"25-06-2026"}]},""" +
                """"festivalNames":{"a1":"Tons of Rock"},"festivalsMigrated":true}"""
        )
        val cached = TimelineStore(file).load()
        assertTrue(cached.festivals.isEmpty())
        assertTrue(cached.festivalIdByShow.isEmpty())
    }

    /**
     * The shape a real device actually wrote. The lane grouped a newest-first list, so
     * the key is the *newest* show of the cluster — while the migration's replay sorts
     * ascending. Matching the cluster by its head would migrate nothing at all and lose
     * every resolved name in the store, silently.
     */
    @Test
    fun `a name keyed by the newest show of its cluster still migrates`() = runBlocking {
        val file = File.createTempFile("timelines", ".json")
        file.writeText(
            """{"shows":{"magnus":[""" +
                """{"id":"a2","eventDate":"27-06-2026","artist":{"name":"Ghost"},"venue":{"name":"Ekebergsletta"}},""" +
                """{"id":"a1","eventDate":"25-06-2026","artist":{"name":"Gojira"},"venue":{"name":"Ekebergsletta"}}""" +
                """]},"festivalNames":{"a2":"Tons of Rock"}}"""
        )
        val cached = TimelineStore(file).load()

        val identity = cached.festivals.values.single()
        assertEquals("Tons of Rock", identity.name)
        assertEquals(identity.id, cached.festivalIdByShow["a1"])
        assertEquals(identity.id, cached.festivalIdByShow["a2"])
    }

    /**
     * A name whose show is nowhere in the cache cannot say what it named — the venue
     * and the nights are the only evidence, and they are gone. Dropping it is honest;
     * filing it under a guessed identity would invent exactly the false Festival #166
     * is about.
     */
    @Test
    fun `a name whose show is not in the cache is dropped, not guessed at`() = runBlocking {
        val file = File.createTempFile("timelines", ".json")
        file.writeText(
            """{"shows":{"magnus":[{"id":"a1","eventDate":"25-06-2026"}]},""" +
                """"festivalNames":{"gone":"Tons of Rock"}}"""
        )
        val cached = TimelineStore(file).load()

        assertTrue(cached.festivalsMigrated)
        assertTrue(cached.festivals.isEmpty())
        assertTrue(cached.festivalIdByShow.isEmpty())
    }

    /** Asked and answered are different facts: an evening with no festival behind it
     *  must be remembered as asked, or every launch re-asks the whole timeline. */
    @Test
    fun `festivalsAsked accumulates across saves`() = runBlocking {
        val store = store()
        store.save(festivalsAsked = setOf("a"))
        store.save(festivalsAsked = setOf("b"))
        assertEquals(setOf("a", "b"), store.load().festivalsAsked)
    }

    @Test
    fun `an authored identity survives a scraped one arriving later`() = runBlocking {
        val store = store()
        val mine = StoredFestival(id = "f1", name = "Piknik i Parken", source = StoredFestival.FestivalSource.AUTHORED)
        store.save(festivals = mapOf("f1" to mine))
        store.save(
            festivals = mapOf(
                "f1" to StoredFestival(id = "f1", name = "PiP", source = StoredFestival.FestivalSource.SCRAPED),
            ),
        )
        assertEquals(mine, store.load().festivals["f1"])
    }

    @Test
    fun `festivals and festivalIdByShow round-trip through a save`() = runBlocking {
        val store = store()
        val identity = StoredFestival(id = "f1", name = "Piknik i Parken", source = StoredFestival.FestivalSource.AUTHORED)
        store.save(festivals = mapOf("f1" to identity), festivalIdByShow = mapOf("a" to "f1"))
        val cached = store.load()
        assertEquals(identity, cached.festivals["f1"])
        assertEquals("f1", cached.festivalIdByShow["a"])
    }

    private fun playlist(id: String) =
        StoredPlaylist("https://open.spotify.com/playlist/$id", "2026 – The Warning", 19)

    @Test
    fun `a night remembers the playlist it became`() = runBlocking {
        val store = store()
        store.save(shows = mapOf("magnus" to listOf(show("a"))))
        store.save(playlists = mapOf("a" to playlist("p1")))
        // A later save of the shows must not drop it: the two write independently.
        store.save(shows = mapOf("magnus" to listOf(show("a"), show("b"))))
        val cached = store.load()
        assertEquals(19, cached.playlists()["a"]?.single()?.trackCount)
        assertEquals(listOf("a", "b"), cached.shows["magnus"]?.map { it.id })
    }

    @Test
    fun `converting a night again keeps the link already sent to someone`() = runBlocking {
        val store = store()
        store.save(playlists = mapOf("a" to playlist("p1")))
        store.save(playlists = mapOf("a" to playlist("p2")))
        assertEquals(
            listOf("https://open.spotify.com/playlist/p1", "https://open.spotify.com/playlist/p2"),
            store.load().playlists()["a"]?.map { it.url },
        )
    }

    @Test
    fun `recording the same playlist twice does not duplicate it`() = runBlocking {
        val store = store()
        store.save(playlists = mapOf("a" to playlist("p1")))
        store.save(playlists = mapOf("a" to playlist("p1")))
        assertEquals(1, store.load().playlists()["a"]?.size)
    }

    @Test
    fun `a cache written before playlists were a list still loads its timelines`() = runBlocking {
        val file = File.createTempFile("timelines", ".json")
        // The shape the previous build wrote: playlists as one entry per night.
        file.writeText(
            """{"shows":{"magnus":[{"id":"a","eventDate":"25-06-2026"}]},""" +
                """"festivalNames":{},"playlists":{"a":{"url":"u","name":"n","trackCount":3}}}"""
        )
        val cached = TimelineStore(file).load()
        assertEquals(listOf("a"), cached.shows["magnus"]?.map { it.id })
        assertTrue(cached.playlists().isEmpty())
    }

    /**
     * The #162 upgrade, on the bytes the previous build actually wrote.
     *
     * This is the dangerous one: before it, `personal = false` needed a night-level
     * grant on top to mean anything, and afterwards it means shared on its own. Every
     * photograph on a night nobody shared has to reach the vault, or the update itself
     * publishes a collection.
     */
    @Test
    fun `media on a night nobody shared lands in the vault after the upgrade`() = runBlocking {
        val file = File.createTempFile("timelines", ".json")
        // Two nights, one of them actually shared, media with no `personal` written at
        // all — the shape that made everything shareable by default.
        file.writeText(
            """{"gigs":{"g1":{"id":"g1"},"g2":{"id":"g2"}},""" +
                """"gigMedia":{"g1":[{"id":"m1","ref":"content://a"}],""" +
                """"g2":[{"id":"m2","ref":"content://b"},{"id":"t","ref":"content://c","from":"someone"}]},""" +
                """"sharedNights":["g1"]}"""
        )
        val cached = TimelineStore(file).load()

        assertFalse(cached.gigMedia.getValue("g1").single().personal)
        assertTrue(cached.gigMedia.getValue("g2").first { it.id == "m2" }.personal)
        // Received media is not mine to vault: `personal` says nothing about it.
        assertFalse(cached.gigMedia.getValue("g2").first { it.id == "t" }.personal)
        assertTrue(cached.sharedNights.isEmpty())
        assertTrue(cached.mediaTierMigrated)
    }

    /** Idempotent, and it has to be: after one pass there is nothing left to read. */
    @Test
    fun `the upgrade does not run a second time and re-vault a shared photograph`() = runBlocking {
        val file = File.createTempFile("timelines", ".json")
        file.writeText(
            """{"gigs":{"g1":{"id":"g1"}},""" +
                """"gigMedia":{"g1":[{"id":"m1","ref":"content://a"}]},""" +
                """"sharedNights":["g1"],"mediaTierMigrated":true}"""
        )
        val cached = TimelineStore(file).load()
        assertFalse(cached.gigMedia.getValue("g1").single().personal)
    }

    @Test
    fun `an unreadable cache degrades to empty instead of crashing the launch`() = runBlocking {
        val file = File.createTempFile("timelines", ".json")
        file.writeText("{ not json")
        assertTrue(TimelineStore(file).load().shows.isEmpty())
    }

    @Test
    fun `song offsets survive a save of the shows around them`() = runBlocking {
        val store = store()
        store.saveMedia("a", listOf(video("content://rec.mp4")))
        store.saveSongOffsets("m-content://rec.mp4", listOf(0L, 214_000L, -1L))
        store.save(shows = mapOf("magnus" to listOf(show("a"))))
        assertEquals(listOf(0L, 214_000L, -1L), store.load().media()["a"]?.single()?.songOffsets)
    }

    @Test
    fun `restamping a recording replaces its offsets rather than appending`() = runBlocking {
        val store = store()
        store.saveMedia("a", listOf(video("content://rec.mp4")))
        store.saveSongOffsets("m-content://rec.mp4", listOf(0L, 100L))
        store.saveSongOffsets("m-content://rec.mp4", listOf(0L, 250L))
        assertEquals(listOf(0L, 250L), store.load().media()["a"]?.single()?.songOffsets)
    }

    @Test
    fun `two recordings of one night each carry their own stamps`() = runBlocking {
        // The shape #27 needed and a night-keyed map could not express.
        val store = store()
        store.saveMedia("a", listOf(video("content://one.mp4"), video("content://two.mp4")))
        store.saveSongOffsets("m-content://one.mp4", listOf(0L, 100L))
        store.saveSongOffsets("m-content://two.mp4", listOf(0L, 250L))
        val media = store.load().media()["a"].orEmpty()
        assertEquals(listOf(0L, 100L), media[0].songOffsets)
        assertEquals(listOf(0L, 250L), media[1].songOffsets)
    }

    @Test
    fun `removing one recording leaves the other one's stamps alone`() = runBlocking {
        val store = store()
        store.saveMedia("a", listOf(video("content://one.mp4"), video("content://two.mp4")))
        store.saveSongOffsets("m-content://one.mp4", listOf(0L, 100L))
        store.saveSongOffsets("m-content://two.mp4", listOf(0L, 250L))
        val kept = store.load().media()["a"].orEmpty().first { it.ref == "content://two.mp4" }
        store.saveMedia("a", listOf(kept))
        assertEquals(listOf(0L, 250L), store.load().media()["a"]?.single()?.songOffsets)
    }

    @Test
    fun `the reported total survives a reload, so paging can resume`() = runBlocking {
        val store = store()
        store.save(shows = mapOf("dizzi90" to listOf(show("a"))), attendedTotals = mapOf("dizzi90" to 169))
        // A later save of more shows must not drop the total already learned.
        store.save(shows = mapOf("dizzi90" to listOf(show("a"), show("b"))))
        assertEquals(169, store.load().attendedTotals["dizzi90"])
    }

    @Test
    fun `attendance round-trips`() = runBlocking {
        val store = store()
        store.saveAttendance("a", StoredAttendance(provenance = StoredAttendance.Provenance.PLANNED))
        assertEquals(
            StoredAttendance.Provenance.PLANNED,
            store.load().attendance()["a"]?.provenance,
        )
    }

    @Test
    fun `checking in moves provenance and stamps the time`() = runBlocking {
        val store = store()
        store.saveAttendance("a", StoredAttendance(provenance = StoredAttendance.Provenance.PLANNED))
        store.saveAttendance(
            "a",
            StoredAttendance(provenance = StoredAttendance.Provenance.CHECKED_IN, checkedInAt = 1_700_000_000_000L),
        )
        val loaded = store.load().attendance()["a"]
        assertEquals(StoredAttendance.Provenance.CHECKED_IN, loaded?.provenance)
        assertEquals(1_700_000_000_000L, loaded?.checkedInAt)
    }

    @Test
    fun `a gig with no setlist id is a distinct attendance record from one with an id`() = runBlocking {
        val store = store()
        // "local-1" is what #34's local-id fallback looks like: no setlist.fm id yet.
        store.saveAttendance("local-1", StoredAttendance(provenance = StoredAttendance.Provenance.PLANNED))
        store.saveAttendance("a", StoredAttendance(provenance = StoredAttendance.Provenance.ATTENDED))
        val attendance = store.load().attendance()
        assertEquals(2, attendance.size)
        assertEquals(StoredAttendance.Provenance.PLANNED, attendance["local-1"]?.provenance)
        assertEquals(StoredAttendance.Provenance.ATTENDED, attendance["a"]?.provenance)
    }

    // --- #107: a Gig gets an identity the app owns ---------------------------

    @Test
    fun `adopting a setlist id preserves every association on that night`() = runBlocking {
        // The test #107 exists for. A night carrying everything a night can carry
        // appears on setlist.fm; nothing may be orphaned by the good news.
        val store = store()
        val gigId = store.createLocalGig("25-06-2026", "The Warning", "Vaterland")
        store.saveMedia(gigId, listOf(photo("content://photo1"), video("content://rec.mp4")))
        store.saveSongOffsets("m-content://rec.mp4", listOf(0L, 214_000L))
        store.saveAttendance(
            gigId,
            StoredAttendance(provenance = StoredAttendance.Provenance.CHECKED_IN, checkedInAt = 42L),
        )
        store.markCalendarAdded(gigId, "content://com.android.calendar/events/7")
        store.save(playlists = mapOf(gigId to playlist("p1")))

        assertTrue(store.adoptSetlistId(gigId, "63de6d5b"))

        val after = store.load()
        // Adoption moved no data: the same records, now answering to the vendor id.
        assertEquals("63de6d5b", after.setlistIdFor(gigId))
        assertEquals(
            listOf("content://photo1", "content://rec.mp4"),
            after.media()["63de6d5b"]?.map { it.ref },
        )
        assertEquals(listOf(0L, 214_000L), after.media()["63de6d5b"]?.last()?.songOffsets)
        assertEquals(42L, after.attendance()["63de6d5b"]?.checkedInAt)
        assertEquals("content://com.android.calendar/events/7", after.calendarEvents()["63de6d5b"])
        assertEquals(1, after.playlists()["63de6d5b"]?.size)
        assertEquals(1, after.gigs.size)
    }

    @Test
    fun `adopting a second setlist id is refused rather than silently overwriting`() = runBlocking {
        val store = store()
        val gigId = store.createLocalGig("25-06-2026", "The Warning", "Vaterland")
        assertTrue(store.adoptSetlistId(gigId, "63de6d5b"))
        // Upstream bug, not a merge case — #34 must find out, not have it swallowed.
        assertFalse(store.adoptSetlistId(gigId, "other"))
        assertEquals("63de6d5b", store.load().setlistIdFor(gigId))
    }

    @Test
    fun `a night is found from either end`() = runBlocking {
        val store = store()
        store.saveMedia("63de6d5b", listOf(photo("content://photo1")))
        val cached = store.load()
        val gig = cached.gigForSetlist("63de6d5b")
        assertEquals("63de6d5b", gig?.setlistId)
        assertEquals("63de6d5b", cached.setlistIdFor(gig!!.id))
        // Two local ids, one setlist id, one night: the correspondence key between
        // people is the setlist.fm id, and it resolves to exactly one Gig here.
        assertEquals(1, cached.gigs.size)
    }

    @Test
    fun `a local-only gig has no setlist id, so it can never be a Crossing`() = runBlocking {
        val store = store()
        val gigId = store.createLocalGig("25-06-2026", "Local Band", "A basement")
        val cached = store.load()
        assertEquals(null, cached.setlistIdFor(gigId))
        // The weave keys on setlist.fm ids; with none, this night cannot meet
        // anyone's line. #34 accepts that consequence — pinned here as behaviour.
        assertEquals(gigId, cached.keyOf(gigId))
        assertTrue(cached.gigs.values.none { it.setlistId != null })
    }

    @Test
    fun `a night minted from a poster has no venue, and gains one when setlist_fm names the room`() = runBlocking {
        // #128. The poster says which festival, never which room, so the Gig starts
        // roomless rather than claiming the festival was the venue — the venue is one
        // third of ADR-0002's key, and a festival name there is what stopped this
        // night ever recognising its own setlist.fm record.
        val store = store()
        val gigId = store.createLocalGig("07-08-2026", "Paper Cranes", venue = "")
        assertEquals("", store.load().gigs[gigId]?.venue)

        // The night turns up on setlist.fm, which does know the room.
        store.save(
            shows = mapOf(
                "magnus" to listOf(
                    FmSetlist(
                        id = "637062c7",
                        eventDate = "07-08-2026",
                        artist = FmArtist(name = "Paper Cranes"),
                        venue = FmVenue(name = "Hollowmoor Park", city = FmCity(name = "Vardhavn")),
                    ),
                ),
            ),
        )
        assertTrue(store.adoptSetlistId(gigId, "637062c7"))

        // Blank was a state that resolves, not a hole: the date it already knew is
        // untouched, and the room it never knew is filled in.
        val gig = store.load().gigs[gigId]
        assertEquals("Hollowmoor Park", gig?.venue)
        assertEquals("07-08-2026", gig?.date)
        assertEquals("Paper Cranes", gig?.artist)
    }

    @Test
    fun `filling a blank venue does not overwrite a date already known`() = runBlocking {
        // The gate widened from "no date" to "missing anything" (#128); each field
        // still fills only if blank, so a night keeps every fact it already had.
        val store = store()
        val gigId = store.createLocalGig("07-08-2026", "Paper Cranes", venue = "")
        store.save(
            shows = mapOf(
                "magnus" to listOf(
                    FmSetlist(
                        id = "637062c7",
                        // setlist.fm disagreeing about the night must not silently
                        // relocate a date someone chose off the Bill's own range.
                        eventDate = "08-08-2026",
                        artist = FmArtist(name = "Paper Cranes"),
                        venue = FmVenue(name = "Hollowmoor Park"),
                    ),
                ),
            ),
        )
        assertTrue(store.adoptSetlistId(gigId, "637062c7"))

        val gig = store.load().gigs[gigId]
        assertEquals("07-08-2026", gig?.date)
        assertEquals("Hollowmoor Park", gig?.venue)
    }

    @Test
    fun `two records of one night merge, oldest id wins, nothing is lost`() = runBlocking {
        val store = store()
        val older = store.createLocalGig("25-06-2026", "The Warning", "Vaterland")
        store.saveMedia(older, listOf(photo("content://photo1")))
        store.saveAttendance(
            older,
            StoredAttendance(provenance = StoredAttendance.Provenance.CHECKED_IN, checkedInAt = 42L),
        )
        // The same night, found again by an import that didn't know it was already here.
        store.saveMedia("63de6d5b", listOf(photo("content://photo2")))
        val newer = store.load().gigForSetlist("63de6d5b")!!.id

        assertEquals(older, store.mergeGigs(older, newer))
        val after = store.load()
        assertEquals(1, after.gigs.size)
        // The survivor takes the union, and the vendor id the other one carried.
        assertEquals("63de6d5b", after.setlistIdFor(older))
        assertEquals(listOf("content://photo1", "content://photo2"), after.media()["63de6d5b"]?.map { it.ref })
        assertEquals(42L, after.attendance()["63de6d5b"]?.checkedInAt)
    }

    @Test
    fun `an old cache migrates every map onto one Gig per night`() = runBlocking {
        val file = File.createTempFile("timelines", ".json")
        // A cache from before #107: the same night in five different maps.
        file.writeText(
            """{"shows":{"magnus":[{"id":"a1","eventDate":"25-06-2026",""" +
                """"artist":{"name":"Gojira"},"venue":{"name":"Ekebergsletta"}}]},""" +
                """"playlistsMade":{"a1":[{"url":"u","name":"n","trackCount":3}]},""" +
                """"photosBySetlist":{"a1":["content://photo1","content://rec.mp4"]},""" +
                """"songOffsetsBySetlist":{"a1":[0,214000]},""" +
                """"attendanceByGig":{"a1":{"provenance":"checked_in","checkedInAt":42}},""" +
                """"calendarEventByGig":{"a1":"content://cal/7"}}"""
        )
        val cached = TimelineStore(file).load()

        assertEquals(1, cached.gigs.size)
        val gig = cached.gigs.values.single()
        assertEquals("a1", gig.setlistId)
        // Derived, not drawn: iOS must reach this exact id from the same cache.
        assertEquals("6033fd8a-ff1e-5334-854f-5e2edfd5a255", gig.id)
        // The facts of the night are filled in from the show the cache already held.
        assertEquals("25-06-2026", gig.date)
        assertEquals("Gojira", gig.artist)
        assertEquals("Ekebergsletta", gig.venue)
        // Everything still resolves, under the id the screens use.
        assertEquals(
            listOf("content://photo1", "content://rec.mp4"),
            cached.media()["a1"]?.map { it.ref },
        )
        // The night's one video takes the stamps that used to belong to the night.
        assertEquals(listOf(0L, 214_000L), cached.media()["a1"]?.last()?.songOffsets)
        assertEquals(42L, cached.attendance()["a1"]?.checkedInAt)
        assertEquals("content://cal/7", cached.calendarEvents()["a1"])
        assertEquals(1, cached.playlists()["a1"]?.size)
    }

    @Test
    fun `the old keys survive the migration, so an older build is unharmed`() = runBlocking {
        val file = File.createTempFile("timelines", ".json")
        file.writeText("""{"photosBySetlist":{"a1":["content://photo1"]}}""")
        val store = TimelineStore(file)
        // A write after migrating: the new keys carry the truth, the old ones stay
        // exactly as they were rather than being cleared out from under an old build.
        store.save(shows = mapOf("magnus" to listOf(show("b"))))
        val cached = store.load()
        assertEquals(listOf("content://photo1"), cached.photosBySetlist["a1"])
        assertEquals(listOf("content://photo1"), cached.media()["a1"]?.map { it.ref })
    }

    @Test
    fun `migrating twice changes nothing`() = runBlocking {
        val file = File.createTempFile("timelines", ".json")
        file.writeText("""{"photosBySetlist":{"a1":["content://photo1"]}}""")
        val store = TimelineStore(file)
        val first = store.load().gigs.keys
        store.saveMedia("a1", listOf(photo("content://photo1"), photo("content://photo2")))
        val cached = store.load()
        assertEquals(first, cached.gigs.keys)
        assertEquals(2, cached.media()["a1"]?.size)
    }

    @Test
    fun `venue coordinates round-trip alongside provenance`() = runBlocking {
        val store = store()
        store.saveAttendance(
            "a",
            StoredAttendance(
                provenance = StoredAttendance.Provenance.ATTENDED,
                venueLat = 59.9139,
                venueLon = 10.7522,
            ),
        )
        val loaded = store.load().attendance()["a"]
        assertEquals(59.9139, loaded?.venueLat)
        assertEquals(10.7522, loaded?.venueLon)
    }

    @Test
    fun `attendance coexists with a full timeline of shows, photos, playlists and offsets`() = runBlocking {
        val store = store()
        store.save(shows = mapOf("magnus" to listOf(show("a"), show("b"))))
        store.save(playlists = mapOf("a" to playlist("p1")))
        store.saveMedia("a", listOf(photo("content://photo1"), video("content://rec.mp4")))
        store.saveSongOffsets("m-content://rec.mp4", listOf(0L, 200_000L))
        store.saveAttendance("a", StoredAttendance(provenance = StoredAttendance.Provenance.CHECKED_IN, checkedInAt = 99L))
        store.saveAttendance("local-1", StoredAttendance(provenance = StoredAttendance.Provenance.PLANNED))

        val cached = store.load()
        assertEquals(listOf("a", "b"), cached.shows["magnus"]?.map { it.id })
        assertEquals(1, cached.playlists()["a"]?.size)
        assertEquals(
            listOf("content://photo1", "content://rec.mp4"),
            cached.media()["a"]?.map { it.ref },
        )
        assertEquals(listOf(0L, 200_000L), cached.media()["a"]?.last()?.songOffsets)
        assertEquals(StoredAttendance.Provenance.CHECKED_IN, cached.attendance()["a"]?.provenance)
        assertEquals(StoredAttendance.Provenance.PLANNED, cached.attendance()["local-1"]?.provenance)
    }

    @Test
    fun `a gig I'm going to round-trips with provenance planned`() = runBlocking {
        val store = store()
        store.savePlanned(show("oya"))
        val cached = store.load()
        assertEquals(listOf("oya"), cached.planned().map { it.id })
        assertEquals(StoredAttendance.Provenance.PLANNED, cached.attendance()["oya"]?.provenance)
    }

    @Test
    fun `adding the same gig twice keeps one record`() = runBlocking {
        val store = store()
        store.savePlanned(show("oya"))
        store.savePlanned(show("oya"))
        assertEquals(1, store.load().planned().size)
    }

    @Test
    fun `deciding not to go drops both the record and the claim`() = runBlocking {
        val store = store()
        store.savePlanned(show("oya"))
        store.removePlanned("oya")
        val cached = store.load()
        assertTrue(cached.planned().isEmpty())
        assertTrue(cached.attendance().isEmpty())
    }

    @Test
    fun `removing a gig I checked into leaves the evidence that I was there`() = runBlocking {
        val store = store()
        store.savePlanned(show("oya"))
        store.saveAttendance(
            "oya",
            StoredAttendance(provenance = StoredAttendance.Provenance.CHECKED_IN, checkedInAt = 99L),
        )
        store.removePlanned("oya")
        val cached = store.load()
        assertTrue(cached.planned().isEmpty())
        assertEquals(StoredAttendance.Provenance.CHECKED_IN, cached.attendance()["oya"]?.provenance)
    }

    @Test
    fun `re-storing a planned gig when its setlist lands never downgrades the claim`() = runBlocking {
        val store = store()
        store.savePlanned(show("oya"))
        store.saveAttendance(
            "oya",
            StoredAttendance(provenance = StoredAttendance.Provenance.CHECKED_IN, checkedInAt = 99L),
        )
        // refreshSelectedSetlist writes the filled-in record back.
        store.savePlanned(show("oya"))
        assertEquals(StoredAttendance.Provenance.CHECKED_IN, store.load().attendance()["oya"]?.provenance)
    }

    @Test
    fun `savePlanned hands back the claim the future lane filters on`() = runBlocking {
        val store = store()
        val gig = show("oya")

        // The bug: the caller saved this and put only the gig into its own state, so
        // plannedLane — which asks the attendance map, not the gig — drew nothing. The
        // night was on disk and off the screen until the next cold start, which is
        // indistinguishable from Add having silently failed.
        val claim = store.savePlanned(gig)
        assertEquals(StoredAttendance.Provenance.PLANNED, claim.provenance)
        assertEquals(listOf("oya"), plannedLane(listOf(gig), mapOf(gig.id to claim)).map { it.id })

        // Without it, the lane is empty — this is the state the two callers were in.
        assertTrue(plannedLane(listOf(gig), emptyMap()).isEmpty())
    }

    @Test
    fun `the claim handed back is the one kept, not the one offered`() = runBlocking {
        val store = store()
        store.savePlanned(show("oya"))
        store.saveAttendance(
            "oya",
            StoredAttendance(provenance = StoredAttendance.Provenance.CHECKED_IN, checkedInAt = 99L),
        )
        // A caller that wrote PLANNED into its own state here would show a night I am
        // standing at as one I merely intend to attend. The return value is the point:
        // the store settles this, callers do not get a second opinion.
        assertEquals(StoredAttendance.Provenance.CHECKED_IN, store.savePlanned(show("oya")).provenance)
    }

    @Test
    fun `a planned gig coexists with a full timeline and disturbs none of it`() = runBlocking {
        val store = store()
        store.save(shows = mapOf("magnus" to listOf(show("a"), show("b"))))
        store.save(playlists = mapOf("a" to playlist("p1")))
        store.saveMedia("a", listOf(photo("content://photo1"), video("content://rec.mp4")))
        store.saveSongOffsets("m-content://rec.mp4", listOf(0L, 200_000L))
        store.savePlanned(show("oya"))

        val cached = store.load()
        assertEquals(listOf("a", "b"), cached.shows["magnus"]?.map { it.id })
        assertEquals(1, cached.playlists()["a"]?.size)
        assertEquals(
            listOf("content://photo1", "content://rec.mp4"),
            cached.media()["a"]?.map { it.ref },
        )
        assertEquals(listOf(0L, 200_000L), cached.media()["a"]?.last()?.songOffsets)
        assertEquals(listOf("oya"), cached.planned().map { it.id })
        // The gig I'm going to is not among the nights I was at.
        assertTrue(cached.shows["magnus"].orEmpty().none { it.id == "oya" })
    }

    @Test
    fun `an older cache with no planned field still loads its timelines`() = runBlocking {
        val file = File.createTempFile("timelines", ".json")
        file.writeText(
            """{"shows":{"magnus":[{"id":"a","eventDate":"25-06-2026"}]},"festivalNames":{}}"""
        )
        val cached = TimelineStore(file).load()
        assertEquals(listOf("a"), cached.shows["magnus"]?.map { it.id })
        assertTrue(cached.planned().isEmpty())
    }

    @Test
    fun `the calendar event's URI survives a cold start, keyed by gig`() = runBlocking {
        val store = store()
        val uri = "content://com.android.calendar/events/42"
        store.markCalendarAdded("oya", uri)
        // The URI is both the "added" flag and what the link opens; its own field, not
        // a provenance value — the attendance claim is untouched.
        val cached = store.load()
        assertEquals(uri, cached.calendarEvents()["oya"])
        assertTrue(cached.attendance().isEmpty())
    }

    @Test
    fun `an older cache with the removed calendarAddedGigs key still loads`() = runBlocking {
        val file = File.createTempFile("timelines", ".json")
        // The field is gone; a cache that still carries it must load, not throw.
        file.writeText(
            """{"shows":{"magnus":[{"id":"a","eventDate":"25-06-2026"}]},"calendarAddedGigs":["oya"]}"""
        )
        val cached = TimelineStore(file).load()
        assertEquals(listOf("a"), cached.shows["magnus"]?.map { it.id })
        assertTrue(cached.calendarEvents().isEmpty())
    }

    @Test
    fun `an older cache with no attendance field still loads its timelines`() = runBlocking {
        val file = File.createTempFile("timelines", ".json")
        file.writeText(
            """{"shows":{"magnus":[{"id":"a","eventDate":"25-06-2026"}]},"festivalNames":{}}"""
        )
        val cached = TimelineStore(file).load()
        assertEquals(listOf("a"), cached.shows["magnus"]?.map { it.id })
        assertTrue(cached.attendance().isEmpty())
    }

    /**
     * #126 story 11, and the part of it the spec called the risky one: `remembered` is
     * **parallel to `songs`**, and a cache written before the field existed carries none
     * at all. Fifteen songs and zero remembered lines is not a corrupt record — it is
     * "nothing was ever replaced", which is exactly true — but it is the shape every
     * edit has to survive, and the real device is holding one right now.
     */
    @Test
    fun `a Log written before the remembered field loads with every entry intact`() =
        runBlocking {
            val file = File.createTempFile("timelines", ".json")
            file.writeText(
                """{"gigs":{"g1":{"id":"g1","date":"07-08-2026","artist":"Øyvind Holm",""" +
                    """"venue":"Verandaen, Skotbu","createdAt":1}},""" +
                    """"gigLogs":{"g1":{"songs":["","All held together by toothpicks and gum",""" +
                    """"","Between Stations"],"closed":true}}}"""
            )
            val log = TimelineStore(file).load().gigLogs["g1"]!!

            assertEquals(4, log.songs.size)
            assertEquals(2, log.gaps) // the two Gaps are still acknowledged Gaps
            assertEquals(
                listOf("All held together by toothpicks and gum", "Between Stations"),
                log.named(),
            )
            // No remembered line anywhere, rather than blanks that read as replaced.
            assertNull(log.rememberedAt(1))
            assertTrue(log.remembered.isEmpty())
        }

    /**
     * The same cache, then edited. `aligned()` is the only thing standing between an
     * empty `remembered` and a correction writing its words into the wrong row — so
     * correcting entry 1 of a log that has never carried the field must land on 1.
     */
    @Test
    fun `correcting a Log that predates the field keeps the two lists parallel`() =
        runBlocking {
            val file = File.createTempFile("timelines", ".json")
            // The Gig record matters: withGig() mints a night for a key it does not
            // already hold, so a Log with no Gig would be saved under a derived id.
            file.writeText(
                """{"gigs":{"g1":{"id":"g1","date":"07-08-2026","artist":"Øyvind Holm",""" +
                    """"venue":"Verandaen, Skotbu","createdAt":1}},""" +
                    """"gigLogs":{"g1":{"songs":["","All held together by toothpicks and gum",""" +
                    """"","Between Stations"],"closed":true}}}"""
            )
            val store = TimelineStore(file)
            val corrected = store.load().gigLogs["g1"]!!.correctingAt(1, "Toothpicks and Gum")
            store.saveLog("g1", corrected)

            val reloaded = store.load().gigLogs["g1"]!!
            assertEquals(
                listOf("", "Toothpicks and Gum", "", "Between Stations"),
                reloaded.songs,
            )
            assertEquals("All held together by toothpicks and gum", reloaded.rememberedAt(1))
            assertNull(reloaded.rememberedAt(3)) // the untouched entry stayed untouched
            assertEquals(2, reloaded.gaps)
        }

    // --- #97: media becomes a record ----------------------------------------

    private fun oldCache(photos: String, offsets: String = "[0,214000]"): TimelineStore {
        val file = File.createTempFile("timelines", ".json")
        file.writeText(
            """{"photosBySetlist":{"a1":$photos},"songOffsetsBySetlist":{"a1":$offsets}}"""
        )
        return TimelineStore(file)
    }

    @Test
    fun `a night with exactly one video takes the stamps that were the night's`() = runBlocking {
        val cached = oldCache("""["content://photo.jpg","content://rec.mp4"]""").load()
        val media = cached.media()["a1"].orEmpty()
        assertEquals(listOf(StoredMedia.Kind.PHOTO, StoredMedia.Kind.VIDEO), media.map { it.kind })
        assertEquals(emptyList<Long>(), media[0].songOffsets)
        assertEquals(listOf(0L, 214_000L), media[1].songOffsets)
    }

    @Test
    fun `a night with no video leaves its stamps in the dead key untouched`() = runBlocking {
        val cached = oldCache("""["content://photo.jpg"]""").load()
        assertEquals(emptyList<Long>(), cached.media()["a1"]?.single()?.songOffsets)
        // Nothing is lost — the old key still holds them, and a guess would have
        // been worse than declining: there is no recording to be right about.
        assertEquals(listOf(0L, 214_000L), cached.songOffsetsBySetlist["a1"])
    }

    @Test
    fun `a night with two videos leaves its stamps put rather than guessing`() = runBlocking {
        val cached = oldCache("""["content://one.mp4","content://two.mp4"]""").load()
        assertTrue(cached.media()["a1"].orEmpty().all { it.songOffsets.isEmpty() })
        assertEquals(listOf(0L, 214_000L), cached.songOffsetsBySetlist["a1"])
    }

    @Test
    fun `migrated media ids are derived, so two platforms reach one set of them`() = runBlocking {
        // Fixed rather than recomputed here: iOS asserts the same literal, so
        // neither platform can drift by agreeing with its own arithmetic.
        val cached = oldCache("""["content://photo.jpg"]""").load()
        assertEquals("70c08466-7711-5bc1-a64c-519669c9a42a", cached.media()["a1"]?.single()?.id)
    }

    /**
     * Personal is never *inferred* from the media — but a cache this old shared no
     * night, and since #162 that is the answer: it all lands in the vault, because
     * `personal = false` now means shared on its own and nobody ever said so.
     */
    @Test
    fun `a cache old enough to predate sharing migrates entirely into the vault`() = runBlocking {
        val cached = oldCache("""["content://photo.jpg","content://rec.mp4"]""").load()
        assertTrue(cached.media()["a1"].orEmpty().all { it.personal })
        // Nothing is invented for the fields only a live attach can know.
        assertTrue(cached.media()["a1"].orEmpty().all { it.capturedAt == null && it.from == null })
    }

    @Test
    fun `the old photo keys survive the media migration`() = runBlocking {
        val store = oldCache("""["content://photo.jpg"]""")
        store.saveMedia("a1", listOf(photo("content://photo.jpg"), photo("content://new.jpg")))
        val cached = store.load()
        assertEquals(listOf("content://photo.jpg"), cached.photosBySetlist["a1"])
        assertEquals(2, cached.media()["a1"]?.size)
    }

    // --- Deleting a local gig: fenced by what it refuses ------------------------

    @Test
    fun `a local gig deletes with everything hanging off it`() = runBlocking {
        val store = store()
        val id = store.createLocalGig("06-08-2026", "Villskudd", "Ringnes Festival 2026")
        store.saveAttendance(id, StoredAttendance(provenance = StoredAttendance.Provenance.CHECKED_IN))
        store.saveLog(id, StoredLog(songs = listOf("Ei vise")))
        assertTrue(store.deleteGig(id))
        val cached = store.load()
        assertTrue(cached.gigs.isEmpty())
        assertTrue(cached.gigPlanned.isEmpty())
        // The whole reason this exists: removePlanned rightly refuses to erase a
        // check-in, which used to strand a claim for a night nothing pointed at.
        assertTrue(cached.gigAttendance.isEmpty())
        assertTrue(cached.gigLogs.isEmpty())
    }

    @Test
    fun `a gig with a setlist-fm id is never deleted`() = runBlocking {
        val store = store()
        val id = store.createLocalGig("06-08-2026", "Silent Majority", "Ringnes")
        store.adoptSetlistId(id, "abc123")
        // It is no longer only ours — other people's lines can meet at it now.
        assertFalse(store.deleteGig(id))
        assertEquals(1, store.load().gigs.size)
    }

    @Test
    fun `a gig with media on it is kept, because a photographed night is not a mistap`() = runBlocking {
        val store = store()
        val id = store.createLocalGig("06-08-2026", "Du&Du", "Ringnes")
        store.saveMedia(id, listOf(photo("content://ringnes.jpg")))
        assertFalse(store.deleteGig(id))
        assertEquals(1, store.load().gigMedia[id]?.size)
    }

    @Test
    fun `deleting an unknown gig is a no-op, not a crash`() = runBlocking {
        assertFalse(store().deleteGig("nothing-here"))
    }

    @Test
    fun `the deliberate delete takes the media with it`() = runBlocking {
        val store = store()
        val id = store.createLocalGig("06-08-2026", "Truls Lorentzen", "Ringnes")
        store.saveMedia(id, listOf(photo("content://ringnes.jpg")))
        // The undo of a mistap still refuses; a person reading the night's own
        // screen can see what is on it and is allowed to mean it.
        assertFalse(store.deleteGig(id))
        assertTrue(store.deleteGig(id, withMedia = true))
        val cached = store.load()
        assertTrue(cached.gigs.isEmpty())
        assertTrue(cached.gigMedia.isEmpty())
    }

    @Test
    fun `adoption outranks the deliberate delete`() = runBlocking {
        val store = store()
        val id = store.createLocalGig("06-08-2026", "Silent Majority", "Ringnes")
        store.saveMedia(id, listOf(photo("content://ringnes.jpg")))
        store.adoptSetlistId(id, "abc123")
        // withMedia lifts the media guard and nothing else: a night other people's
        // lines can meet at is not deletable by any route.
        assertFalse(store.deleteGig(id, withMedia = true))
        assertEquals(1, store.load().gigs.size)
    }

    // --- #128: a setlistId names one Gig, and the read never drops one -----------

    /**
     * The Valkyrien pair, as it actually sat on the device on 2026-08-10: one night,
     * two **Gigs**, one `setlistId` — written straight into a cache file because
     * after this change nothing in the store's API can produce it any more.
     */
    private fun collidingPair(): File = File.createTempFile("timelines", ".json").apply {
        writeText(
            """{"gigs":{""" +
                // The newer record first, so the assertions below can only pass by
                // ordering on createdAt rather than on whatever the map hands back.
                """"c7d496ae":{"id":"c7d496ae","date":"07-08-2026","artist":"Valkyrien Allstars",""" +
                """"venue":"Ringnes Festival 2026","setlistId":"637062c7","createdAt":2},""" +
                """"f41586f4":{"id":"f41586f4","date":"07-08-2026","artist":"Valkyrien Allstars",""" +
                """"venue":"Verandaen, Skotbu","setlistId":"637062c7","createdAt":1}},""" +
                """"gigMedia":{"f41586f4":[{"id":"m1","kind":"photo","ref":"content://photo1"}],""" +
                """"c7d496ae":[{"id":"m2","kind":"photo","ref":"content://photo2"}]},""" +
                """"gigLogs":{"f41586f4":{"songs":["Ei Natt","Tomma Ord"],"closed":true},""" +
                """"c7d496ae":{"songs":["Ei Natt"],"closed":false}},""" +
                """"gigAttendance":{"f41586f4":{"provenance":"checked_in","checkedInAt":42},""" +
                """"c7d496ae":{"provenance":"planned"}},""" +
                """"gigPlaylists":{"f41586f4":[{"url":"p1","name":"n","trackCount":3}],""" +
                """"c7d496ae":[{"url":"p2","name":"n","trackCount":3}]},""" +
                """"gigCalendarEvent":{"f41586f4":"content://cal/7"}}"""
        )
    }

    @Test
    fun `two gigs sharing a setlist id combine in the read rather than one being dropped`() =
        runBlocking {
            val cached = TimelineStore(collidingPair()).load()
            // mapKeys was last-wins: one of each of these pairs vanished from every
            // screen with no error. Both records are still here.
            assertEquals(
                listOf("content://photo1", "content://photo2"),
                cached.media()["637062c7"]?.map { it.ref },
            )
            assertEquals(listOf("p1", "p2"), cached.playlists()["637062c7"]?.map { it.url })
            assertEquals("content://cal/7", cached.calendarEvents()["637062c7"])
            assertEquals(1, cached.media().size)
        }

    @Test
    fun `a Log survives a setlist id collision — the worst case named`() = runBlocking {
        val cached = TimelineStore(collidingPair()).load()
        // A set someone typed at a gig disappearing is the thing this is for. The
        // fuller Log is the one kept, and it does not stay Closed on the strength
        // of a record that was still Open.
        val log = cached.logs()["637062c7"]
        assertEquals(listOf("Ei Natt", "Tomma Ord"), log?.songs)
        assertFalse(log!!.closed)
    }

    @Test
    fun `a collision keeps the stronger attendance claim, once`() = runBlocking {
        val attendance = TimelineStore(collidingPair()).load().attendance()
        assertEquals(1, attendance.size)
        // Checked in by one route, merely planned by the other: one check-in on one
        // night, and it is not flattened back to planned by which key landed last.
        assertEquals(StoredAttendance.Provenance.CHECKED_IN, attendance["637062c7"]?.provenance)
        assertEquals(42L, attendance["637062c7"]?.checkedInAt)
    }

    @Test
    fun `adopting a setlist id another gig already holds merges into the older record`() =
        runBlocking {
            val store = store()
            // Marked played off a Bill first, so this is the older of the two.
            val fromBill = store.createLocalGig("07-08-2026", "Valkyrien Allstars", "Ringnes")
            store.saveLog(fromBill, StoredLog(songs = listOf("Ei Natt"), closed = true))
            store.saveAttendance(
                fromBill,
                StoredAttendance(provenance = StoredAttendance.Provenance.PLANNED),
            )
            // The same night arrives from setlist.fm and mints its own Gig.
            store.saveMedia("637062c7", listOf(photo("content://photo2")))
            store.saveAttendance(
                "637062c7",
                StoredAttendance(provenance = StoredAttendance.Provenance.CHECKED_IN, checkedInAt = 42L),
            )

            assertTrue(store.adoptSetlistId(fromBill, "637062c7"))

            val after = store.load()
            // One night, not two, and the older id is the one everything else points at.
            assertEquals(1, after.gigs.size)
            assertEquals(fromBill, after.gigForSetlist("637062c7")?.id)
            assertEquals("637062c7", after.setlistIdFor(fromBill))
            // Nothing was tie-broken away: both sides' side maps came along.
            assertEquals(listOf("Ei Natt"), after.logs()["637062c7"]?.songs)
            assertEquals(listOf("content://photo2"), after.media()["637062c7"]?.map { it.ref })
            assertEquals(1, after.attendance().size)
            assertEquals(42L, after.attendance()["637062c7"]?.checkedInAt)
        }

    @Test
    fun `adopting an id no other gig holds is still a plain attach`() = runBlocking {
        val store = store()
        val gigId = store.createLocalGig("07-08-2026", "Enok Monk", "Ringnes")
        assertTrue(store.adoptSetlistId(gigId, "637062c7"))
        val after = store.load()
        assertEquals(1, after.gigs.size)
        assertEquals(gigId, after.gigForSetlist("637062c7")?.id)
    }

    // ---- notes (#50) ---------------------------------------------------------

    private fun note(id: String, text: String, personal: Boolean, verdict: String? = null) =
        StoredMedia(
            id = id,
            kind = StoredMedia.Kind.NOTE,
            ref = "",
            text = text,
            verdict = verdict,
            personal = personal,
        )

    @Test
    fun `a note round-trips with its words and its verdict`() = runBlocking {
        val store = store()
        store.saveMedia(
            "a",
            listOf(note("n1", "First time seeing these ladies, and they ruled!", personal = true, verdict = StoredMedia.Verdict.DOUBLE_UP)),
        )
        val note = store.load().media()["a"]?.single()
        assertEquals(StoredMedia.Kind.NOTE, note?.kind)
        assertEquals("First time seeing these ladies, and they ruled!", note?.text)
        assertEquals(StoredMedia.Verdict.DOUBLE_UP, note?.verdict)
        assertEquals("", note?.ref)
    }

    /**
     * The vault holds. Asserted in this direction rather than the other because
     * **#162's upgrade has not necessarily run yet on a store this new**: it fires on
     * the first read of a cache whose `mediaTierMigrated` is false and sends every
     * unshared night's media *toward* the vault, so a freshly saved shared item can
     * legitimately come back personal. That is the migration working, not a bug — but
     * it does mean "saved shared, loaded shared" is only true of a store that has
     * already been through it, and a test asserting it unconditionally is asserting
     * the wrong thing.
     */
    @Test
    fun `a vault note stays in the vault across a save and load`() = runBlocking {
        val store = store()
        store.saveMedia("a", listOf(note("n1", "not sure about this one", personal = true)))
        assertTrue(store.load().media()["a"]?.single()?.personal ?: false)
    }

    /**
     * The fields are additive, so a cache written before #50 still parses and simply
     * has no notes in it. There is no migration here and that is the point — #162's
     * was the dangerous half precisely because it had to reinterpret a field that
     * already existed.
     */
    @Test
    fun `a cache written before notes existed loads unchanged`() = runBlocking {
        val store = store()
        store.saveMedia("a", listOf(photo("content://old.jpg")))
        val media = store.load().media()["a"].orEmpty()
        assertEquals(1, media.size)
        assertEquals("", media.single().text)
        assertEquals(null, media.single().verdict)
    }

    // --- #225: the zero-account floor's one door ---

    /**
     * The exact sequence `AppViewModel.addLocalGig` writes, asserted end to end,
     * because the door is new and the machinery behind it is not.
     *
     * The ordering is the part worth pinning: `savePlanned` writes a `PLANNED`
     * provenance when the gig has none, so the attendance write has to come after it
     * or a night the user typed in as one they *attended* reads as one they are going
     * to on the next launch.
     */
    @Test
    fun `a night typed in by hand round-trips as a local gig that was attended`() = runBlocking {
        val store = store()
        val night = parseFmDate("07-08-2026")!!
        val gigId = store.createLocalGig(fmDate(night), "Øyvind Holm", venue = "")
        store.savePlanned(localGigSetlist(gigId, "Øyvind Holm", night, venue = "", city = ""))
        store.saveAttendance(
            gigId,
            StoredAttendance(provenance = StoredAttendance.Provenance.ATTENDED),
        )

        val cached = store.load()
        val back = cached.planned().single { it.id == gigId }

        // No url, so nothing will try to fetch this night from setlist.fm.
        assertTrue(back.isLocal())
        assertNull(cached.setlistIdFor(gigId))
        // ATTENDED, never CHECKED_IN: typing a night in is a claim made now about the
        // past, and the provenance the Room shows must not present it as one the
        // phone corroborated at the venue.
        assertEquals(
            StoredAttendance.Provenance.ATTENDED,
            cached.attendance()[gigId]?.provenance,
        )
        assertNull(cached.attendance()[gigId]?.checkedInAt)
        // An unknown room stays unknown rather than becoming "" (#128).
        assertNull(back.venue?.name)
        assertEquals("07-08-2026", back.eventDate)
    }

    /** A blank artist or an unparseable date is refused before anything is written. */
    @Test
    fun `a date the app cannot read is not a night`() {
        assertNull(parseFmDate(""))
        assertNull(parseFmDate("7 August 2026"))
        assertNull(parseFmDate("2026-08-07")) // ISO, which is not what setlist.fm speaks
        assertEquals("07-08-2026", fmDate(parseFmDate(" 07-08-2026 ")!!))
    }
}
