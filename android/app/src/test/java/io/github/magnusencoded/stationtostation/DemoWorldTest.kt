package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.data.StoredAttendance
import io.github.magnusencoded.stationtostation.data.StoredAdmission
import io.github.magnusencoded.stationtostation.data.StoredGig
import io.github.magnusencoded.stationtostation.data.StoredLog
import io.github.magnusencoded.stationtostation.data.StoredMedia
import io.github.magnusencoded.stationtostation.data.StoredPlaylist
import io.github.magnusencoded.stationtostation.data.TimelineCache
import io.github.magnusencoded.stationtostation.data.TimelineStore
import io.github.magnusencoded.stationtostation.data.laneKey
import io.github.magnusencoded.stationtostation.data.localGigSetlist
import io.github.magnusencoded.stationtostation.data.withoutDemoWorld
import io.github.magnusencoded.stationtostation.data.withoutDemoFriends
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class DemoWorldTest {
    @Test fun `purge removes demo records and keeps real records and playlist`() {
        val cache = TimelineCache(
            gigs = mapOf(
                "demo" to StoredGig(id = "demo", demo = true),
                "real" to StoredGig(id = "real"),
            ),
            gigAttendance = mapOf("demo" to StoredAttendance(), "real" to StoredAttendance()),
            gigMedia = mapOf("demo" to listOf(StoredMedia(id = "photo"))),
            gigLogs = mapOf("demo" to StoredLog(), "real" to StoredLog()),
            gigPlaylists = mapOf("demo" to listOf(StoredPlaylist("https://playlist"))),
        )

        val purged = cache.withoutDemoWorld()

        assertFalse("demo" in purged.gigs)
        assertFalse("demo" in purged.gigAttendance)
        assertFalse("demo" in purged.gigMedia)
        assertFalse("demo" in purged.gigLogs)
        assertTrue("real" in purged.gigs)
        assertTrue("real" in purged.gigAttendance)
        assertTrue("real" in purged.gigLogs)
        assertEquals(cache.gigPlaylists, purged.gigPlaylists)
    }

    @Test fun `meet friend data uses the exchange fix real admission store and purge`() = runBlocking {
        val file = File.createTempFile("tour-meet-friend", ".json").also { it.delete() }
        val store = TimelineStore(file)
        val gigId = store.createLocalGig("01-10-2026", "Demo band", "Demo room", demo = true)
        val gig = localGigSetlist(gigId, "Demo band", LocalDate.of(2026, 10, 1), "Demo room", "")
        store.savePlanned(gig)
        val friend = Friend("tour-virtual-friend", "Virtual friend", demo = true)

        store.setDemoVenue(gigId, 59.9139, 10.7522)
        store.save(shows = mapOf(friend.laneKey to listOf(gig)))
        store.attachAdmissions(gigId, listOf(StoredAdmission(payload = "dGlja2V0", symbology = "qr")))

        val before = store.load()
        assertEquals(59.9139, before.gigAttendance.getValue(gigId).venueLat)
        assertEquals(10.7522, before.gigAttendance.getValue(gigId).venueLon)
        assertEquals(1, before.gigAttendance.getValue(gigId).admissions.size)
        assertEquals(listOf(gigId), before.shows.getValue(friend.laneKey).map { it.id })

        store.purgeDemoWorld()

        val purged = store.load()
        assertFalse(gigId in purged.gigs)
        assertFalse(gigId in purged.gigAttendance)
        assertTrue(purged.shows[friend.laneKey].orEmpty().isEmpty())
        assertEquals(
            listOf(Friend("real", "Real friend")),
            listOf(Friend("real", "Real friend"), friend).withoutDemoFriends(),
        )
    }
}
