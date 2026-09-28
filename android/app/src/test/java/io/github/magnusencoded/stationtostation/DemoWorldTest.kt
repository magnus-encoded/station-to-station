package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.StoredAttendance
import io.github.magnusencoded.stationtostation.data.StoredGig
import io.github.magnusencoded.stationtostation.data.StoredLog
import io.github.magnusencoded.stationtostation.data.StoredMedia
import io.github.magnusencoded.stationtostation.data.StoredPlaylist
import io.github.magnusencoded.stationtostation.data.TimelineCache
import io.github.magnusencoded.stationtostation.data.withoutDemoWorld
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
