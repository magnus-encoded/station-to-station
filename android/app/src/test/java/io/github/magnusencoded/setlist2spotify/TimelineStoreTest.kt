package io.github.magnusencoded.setlist2spotify

import io.github.magnusencoded.setlist2spotify.data.StoredAttendance
import io.github.magnusencoded.setlist2spotify.data.StoredPlaylist
import io.github.magnusencoded.setlist2spotify.data.TimelineStore
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmArtist
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmSetlist
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TimelineStoreTest {

    private fun store() = TimelineStore(File.createTempFile("timelines", ".json").also { it.delete() })

    private fun show(id: String) = FmSetlist(id = id, eventDate = "25-06-2026", artist = FmArtist(name = "The Warning"))

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
        store.save(shows = mapOf("Egil" to listOf(show("b"))))
        val shows = store.load().shows
        assertEquals(listOf("a"), shows["magnus"]?.map { it.id })
        assertEquals(listOf("b"), shows["Egil"]?.map { it.id })
    }

    @Test
    fun `a failed fetch does not wipe the last good lane`() = runBlocking {
        val store = store()
        store.save(shows = mapOf("Egil" to listOf(show("a"))))
        // loadFriendTimelines() puts an empty list in the map when a fetch throws.
        store.save(shows = mapOf("Egil" to emptyList()))
        assertEquals(listOf("a"), store.load().shows["Egil"]?.map { it.id })
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
        assertEquals(19, cached.playlistsMade["a"]?.single()?.trackCount)
        assertEquals(listOf("a", "b"), cached.shows["magnus"]?.map { it.id })
    }

    @Test
    fun `converting a night again keeps the link already sent to someone`() = runBlocking {
        val store = store()
        store.save(playlists = mapOf("a" to playlist("p1")))
        store.save(playlists = mapOf("a" to playlist("p2")))
        assertEquals(
            listOf("https://open.spotify.com/playlist/p1", "https://open.spotify.com/playlist/p2"),
            store.load().playlistsMade["a"]?.map { it.url },
        )
    }

    @Test
    fun `recording the same playlist twice does not duplicate it`() = runBlocking {
        val store = store()
        store.save(playlists = mapOf("a" to playlist("p1")))
        store.save(playlists = mapOf("a" to playlist("p1")))
        assertEquals(1, store.load().playlistsMade["a"]?.size)
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
        assertTrue(cached.playlistsMade.isEmpty())
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
        store.saveSongOffsets("a", listOf(0L, 214_000L, -1L))
        store.save(shows = mapOf("magnus" to listOf(show("a"))))
        assertEquals(listOf(0L, 214_000L, -1L), store.load().songOffsetsBySetlist["a"])
    }

    @Test
    fun `restamping a night replaces its offsets rather than appending`() = runBlocking {
        val store = store()
        store.saveSongOffsets("a", listOf(0L, 100L))
        store.saveSongOffsets("a", listOf(0L, 250L))
        assertEquals(listOf(0L, 250L), store.load().songOffsetsBySetlist["a"])
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
            store.load().attendanceByGig["a"]?.provenance,
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
        val loaded = store.load().attendanceByGig["a"]
        assertEquals(StoredAttendance.Provenance.CHECKED_IN, loaded?.provenance)
        assertEquals(1_700_000_000_000L, loaded?.checkedInAt)
    }

    @Test
    fun `a gig with no setlist id is a distinct attendance record from one with an id`() = runBlocking {
        val store = store()
        // "local-1" is what #34's local-id fallback looks like: no setlist.fm id yet.
        store.saveAttendance("local-1", StoredAttendance(provenance = StoredAttendance.Provenance.PLANNED))
        store.saveAttendance("a", StoredAttendance(provenance = StoredAttendance.Provenance.ATTENDED))
        val attendance = store.load().attendanceByGig
        assertEquals(2, attendance.size)
        assertEquals(StoredAttendance.Provenance.PLANNED, attendance["local-1"]?.provenance)
        assertEquals(StoredAttendance.Provenance.ATTENDED, attendance["a"]?.provenance)
    }

    @Test
    fun `adopting a real setlist id carries the attendance record to the new key`() = runBlocking {
        val store = store()
        store.saveAttendance(
            "local-1",
            StoredAttendance(provenance = StoredAttendance.Provenance.CHECKED_IN, checkedInAt = 42L),
        )
        val stub = store.load().attendanceByGig.getValue("local-1")
        // #34's job, not this store's: whoever notices the real id showed up re-saves
        // under it. The store itself never rewrites a key on the caller's behalf.
        store.saveAttendance("a", stub)
        val attendance = store.load().attendanceByGig
        assertEquals(StoredAttendance.Provenance.CHECKED_IN, attendance["a"]?.provenance)
        assertEquals(42L, attendance["a"]?.checkedInAt)
        // The old stub key is still there until whoever adopts it decides to drop it —
        // this store doesn't delete entries, only [saveAttendance]/[save]-style upserts.
        assertTrue(attendance.containsKey("local-1"))
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
        val loaded = store.load().attendanceByGig["a"]
        assertEquals(59.9139, loaded?.venueLat)
        assertEquals(10.7522, loaded?.venueLon)
    }

    @Test
    fun `attendance coexists with a full timeline of shows, photos, playlists and offsets`() = runBlocking {
        val store = store()
        store.save(shows = mapOf("magnus" to listOf(show("a"), show("b"))))
        store.save(playlists = mapOf("a" to playlist("p1")))
        store.savePhotos("a", listOf("content://photo1"))
        store.saveSongOffsets("a", listOf(0L, 200_000L))
        store.saveAttendance("a", StoredAttendance(provenance = StoredAttendance.Provenance.CHECKED_IN, checkedInAt = 99L))
        store.saveAttendance("local-1", StoredAttendance(provenance = StoredAttendance.Provenance.PLANNED))

        val cached = store.load()
        assertEquals(listOf("a", "b"), cached.shows["magnus"]?.map { it.id })
        assertEquals(1, cached.playlistsMade["a"]?.size)
        assertEquals(listOf("content://photo1"), cached.photosBySetlist["a"])
        assertEquals(listOf(0L, 200_000L), cached.songOffsetsBySetlist["a"])
        assertEquals(StoredAttendance.Provenance.CHECKED_IN, cached.attendanceByGig["a"]?.provenance)
        assertEquals(StoredAttendance.Provenance.PLANNED, cached.attendanceByGig["local-1"]?.provenance)
    }

    @Test
    fun `a gig I'm going to round-trips with provenance planned`() = runBlocking {
        val store = store()
        store.savePlanned(show("oya"))
        val cached = store.load()
        assertEquals(listOf("oya"), cached.plannedShows.map { it.id })
        assertEquals(StoredAttendance.Provenance.PLANNED, cached.attendanceByGig["oya"]?.provenance)
    }

    @Test
    fun `adding the same gig twice keeps one record`() = runBlocking {
        val store = store()
        store.savePlanned(show("oya"))
        store.savePlanned(show("oya"))
        assertEquals(1, store.load().plannedShows.size)
    }

    @Test
    fun `deciding not to go drops both the record and the claim`() = runBlocking {
        val store = store()
        store.savePlanned(show("oya"))
        store.removePlanned("oya")
        val cached = store.load()
        assertTrue(cached.plannedShows.isEmpty())
        assertTrue(cached.attendanceByGig.isEmpty())
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
        assertTrue(cached.plannedShows.isEmpty())
        assertEquals(StoredAttendance.Provenance.CHECKED_IN, cached.attendanceByGig["oya"]?.provenance)
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
        assertEquals(StoredAttendance.Provenance.CHECKED_IN, store.load().attendanceByGig["oya"]?.provenance)
    }

    @Test
    fun `a planned gig coexists with a full timeline and disturbs none of it`() = runBlocking {
        val store = store()
        store.save(shows = mapOf("magnus" to listOf(show("a"), show("b"))))
        store.save(playlists = mapOf("a" to playlist("p1")))
        store.savePhotos("a", listOf("content://photo1"))
        store.saveSongOffsets("a", listOf(0L, 200_000L))
        store.savePlanned(show("oya"))

        val cached = store.load()
        assertEquals(listOf("a", "b"), cached.shows["magnus"]?.map { it.id })
        assertEquals(1, cached.playlistsMade["a"]?.size)
        assertEquals(listOf("content://photo1"), cached.photosBySetlist["a"])
        assertEquals(listOf(0L, 200_000L), cached.songOffsetsBySetlist["a"])
        assertEquals(listOf("oya"), cached.plannedShows.map { it.id })
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
        assertTrue(cached.plannedShows.isEmpty())
    }

    @Test
    fun `the calendar event's URI survives a cold start, keyed by gig`() = runBlocking {
        val store = store()
        val uri = "content://com.android.calendar/events/42"
        store.markCalendarAdded("oya", uri)
        // The URI is both the "added" flag and what the link opens; its own field, not
        // a provenance value — the attendance claim is untouched.
        val cached = store.load()
        assertEquals(uri, cached.calendarEventByGig["oya"])
        assertTrue(cached.attendanceByGig.isEmpty())
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
        assertTrue(cached.calendarEventByGig.isEmpty())
    }

    @Test
    fun `an older cache with no attendance field still loads its timelines`() = runBlocking {
        val file = File.createTempFile("timelines", ".json")
        file.writeText(
            """{"shows":{"magnus":[{"id":"a","eventDate":"25-06-2026"}]},"festivalNames":{}}"""
        )
        val cached = TimelineStore(file).load()
        assertEquals(listOf("a"), cached.shows["magnus"]?.map { it.id })
        assertTrue(cached.attendanceByGig.isEmpty())
    }
}
