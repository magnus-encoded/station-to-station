package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.TimelineStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TourAddGigTest {
    @Test fun `tour gig is visible until purge while an ordinary gig remains`() = runBlocking {
        val store = TimelineStore(File.createTempFile("tour-gigs", ".json").also { it.delete() })
        val demo = store.createLocalGig("01-10-2026", "Demo band", "Demo room", demo = true)
        val real = store.createLocalGig("02-10-2026", "Real band", "Real room")

        assertTrue(demo in store.load().gigs)
        assertTrue(store.load().gigs.getValue(demo).demo)

        store.purgeDemoWorld()

        assertFalse(demo in store.load().gigs)
        assertTrue(real in store.load().gigs)
    }
}
