package io.github.magnusencoded.setlist2spotify

import io.github.magnusencoded.setlist2spotify.data.StoredAttendance
import io.github.magnusencoded.setlist2spotify.data.StoredMedia
import io.github.magnusencoded.setlist2spotify.data.StoredPlaylist
import io.github.magnusencoded.setlist2spotify.data.TimelineStore
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmArtist
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmSetlist
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `Personal survives the migration as false and is never inferred`() = runBlocking {
        val cached = oldCache("""["content://photo.jpg","content://rec.mp4"]""").load()
        assertTrue(cached.media()["a1"].orEmpty().none { it.personal })
        // Nor is anything invented for the fields only a live attach can know.
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
}
