package io.github.magnusencoded.stationtostation.features

import io.github.magnusencoded.stationtostation.SetlistSource
import io.github.magnusencoded.stationtostation.UiState
import io.github.magnusencoded.stationtostation.data.TimelineStore
import io.github.magnusencoded.stationtostation.data.setlistfm.SetlistFmClient
import io.github.magnusencoded.stationtostation.data.setlistfm.SetlistFmKey
import io.github.magnusencoded.stationtostation.data.setlistfm.SetlistFmResponse
import io.github.magnusencoded.stationtostation.features.setlists.SetlistController
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SetlistControllerTest {

    @get:Rule val temporary = TemporaryFolder()

    private class Harness(
        val fake: FakeState,
        val controller: SetlistController,
        val job: Job,
        val failures: MutableList<Exception>,
    ) {
        fun settle() = runBlocking { job.children.toList().forEach { it.join() } }
    }

    private fun harness(status: Int, body: String, mine: String = ""): Harness {
        val fake = FakeState(UiState(mySetlistFmUser = mine))
        val job = Job()
        val failures = mutableListOf<Exception>()
        val client = SetlistFmClient(
            keySource = { SetlistFmKey("key", shared = false) },
            transport = { _, _ -> SetlistFmResponse(status, body) },
            sleep = {},
        )
        val controller = SetlistController(
            state = fake.state,
            update = fake.update,
            setlistFm = client,
            timelines = TimelineStore(File(temporary.newFolder(), "timeline.json")),
            setlistFmKey = { null },
            sharedQuotaSpentAtValue = { null },
            gossipStoppedAt = { 0L },
            scope = CoroutineScope(job + Dispatchers.Unconfined),
            fail = { failures += it },
            consumeError = {},
            saveSettingsNow = { _, _ -> },
            saveMySetlistFmUser = { name -> fake.update { it.copy(mySetlistFmUser = name) } },
            adoptSetlist = { _, _, _, _ -> false },
            lineArtists = { emptyList() },
        )
        return Harness(fake, controller, job, failures)
    }

    private val twoShows =
        """{"total":2,"setlist":[{"id":"1a2b3c01","eventDate":"12-10-2024"},{"id":"1a2b3c02"}]}"""

    @Test fun `typing a query only sets the query`() {
        val h = harness(200, twoShows)
        h.controller.setArtistQuery("Motorpsycho")
        h.controller.setUserQuery("magnus")
        assertEquals("Motorpsycho", h.fake.value.artistQuery)
        assertEquals("magnus", h.fake.value.userQuery)
    }

    @Test fun `opening a user's attended list loads their shows and adopts the name as mine when none is set`() {
        val h = harness(200, twoShows)
        h.controller.setUserQuery("magnus")
        h.controller.openUserAttended()
        h.settle()
        val s = h.fake.value
        assertEquals(SetlistSource.USER, s.source)
        assertEquals("Attended by magnus", s.setlistsTitle)
        assertEquals(listOf("1a2b3c01", "1a2b3c02"), s.setlists.map { it.id })
        assertEquals(2, s.setlistsTotal)
        assertFalse(s.setlistsLoading)
        assertEquals("magnus", s.mySetlistFmUser)
    }

    @Test fun `an explicit own username is not replaced by the one being looked at`() {
        val h = harness(200, twoShows, mine = "me")
        h.controller.setUserQuery("magnus")
        h.controller.openUserAttended()
        h.settle()
        assertEquals("me", h.fake.value.mySetlistFmUser)
    }

    @Test fun `a failed attended fetch is reported through fail`() {
        val h = harness(500, "")
        h.controller.setUserQuery("magnus")
        h.controller.openUserAttended()
        h.settle()
        assertEquals(1, h.failures.size)
    }

    @Test fun `loading more does nothing once every show is loaded`() {
        val h = harness(200, twoShows)
        h.controller.setUserQuery("magnus")
        h.controller.openUserAttended()
        h.settle()
        h.controller.loadMoreSetlists()
        h.settle()
        assertEquals(1, h.fake.value.setlistsPage)
        assertTrue(h.failures.isEmpty())
    }
}
