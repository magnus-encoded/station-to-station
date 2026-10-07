package io.github.magnusencoded.stationtostation.data

import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoWorldTest {

    private val real = StoredGig(id = "real", artist = "Real Band", setlistId = "fm-real")
    private val demo = StoredGig(id = "demo", artist = "Demo Band", demo = true)
    private val playlist = StoredPlaylist(url = "https://open.spotify.com/playlist/x", name = "Went to a gig with a friend")

    private val seeded = TimelineCache(
        shows = mapOf("me" to listOf(FmSetlist(id = "fm-real"), FmSetlist(id = "demo"))),
        gigs = mapOf(real.id to real, demo.id to demo),
        gigPlanned = mapOf(demo.id to FmSetlist(id = "demo")),
        gigAttendance = mapOf(real.id to StoredAttendance(), demo.id to StoredAttendance()),
        gigCalendarEvent = mapOf(real.id to "1", demo.id to "2"),
        gigPlaylists = mapOf(demo.id to listOf(playlist)),
    )

    @Test
    fun the_purge_leaves_no_demo_tagged_record() {
        val purged = seeded.withoutDemoWorld()
        assertTrue(purged.gigs.values.none { it.demo })
        assertTrue(demo.id !in purged.gigPlanned)
        assertTrue(demo.id !in purged.gigAttendance)
        assertTrue(demo.id !in purged.gigCalendarEvent)
        assertEquals(listOf("fm-real"), purged.shows.getValue("me").map { it.id })
    }

    @Test
    fun the_purge_keeps_real_records_and_the_playlist() {
        val purged = seeded.withoutDemoWorld()
        assertEquals(real, purged.gigs[real.id])
        assertEquals("1", purged.gigCalendarEvent[real.id])
        assertEquals(listOf(playlist), purged.gigPlaylists[demo.id])
    }

    @Test
    fun a_timeline_without_a_demo_world_is_unchanged() {
        val plain = seeded.copy(gigs = mapOf(real.id to real))
        assertEquals(plain, plain.withoutDemoWorld())
    }

    @Test
    fun a_gig_tagged_demo_is_taken_by_the_purge() = kotlinx.coroutines.runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("demo").toFile()
        val store = TimelineStore(java.io.File(dir, "timeline.json"))
        val id = store.createLocalGig("01-01-2030", "Band", "")
        store.markDemo(id)
        assertEquals(setOf(id), store.purgeDemoWorld())
        assertEquals(emptySet<String>(), store.purgeDemoWorld())
    }
}
