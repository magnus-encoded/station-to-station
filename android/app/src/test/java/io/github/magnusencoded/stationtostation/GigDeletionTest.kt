package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.GigDeletionOutcome
import io.github.magnusencoded.stationtostation.data.GigStorage
import io.github.magnusencoded.stationtostation.data.StoredAttendance
import io.github.magnusencoded.stationtostation.data.StoredLog
import io.github.magnusencoded.stationtostation.data.StoredMedia
import io.github.magnusencoded.stationtostation.data.StoredPlaylist
import io.github.magnusencoded.stationtostation.data.TimelineStore
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GigDeletionTest {
    @get:Rule val temporary = TemporaryFolder()

    private val me = "dizzi90"

    private fun held(id: String): UiState {
        fun <T> byGig(make: (String) -> T) = listOf(id, "other").associateWith(make)
        return UiState(
            plannedGigs = listOf(FmSetlist(id = id), FmSetlist(id = "other")),
            setlists = listOf(FmSetlist(id = id), FmSetlist(id = "other")),
            showsByFriend = mapOf(me to listOf(FmSetlist(id = id), FmSetlist(id = "other"))),
            attendanceByGig = byGig { StoredAttendance() },
            logsByGig = byGig { StoredLog() },
            mediaBySetlist = byGig { listOf(StoredMedia(id = "m-$it", ref = "asset/$it")) },
            playlistsBySetlist = byGig { listOf(StoredPlaylist(url = "https://open.spotify.com/playlist/$it")) },
            calendarEventByGig = byGig { "event-$it" },
        )
    }

    @Test fun `a deleted gig leaves every list the screen holds`() {
        val s = held("gone").deletingGig("gone", me)

        assertEquals(listOf("other"), s.plannedGigs.map { it.id })
        assertEquals(listOf("other"), s.setlists.map { it.id })
        assertEquals(listOf("other"), s.showsByFriend[me].orEmpty().map { it.id })
        assertEquals(setOf("other"), s.attendanceByGig.keys)
        assertEquals(setOf("other"), s.logsByGig.keys)
        assertEquals(setOf("other"), s.mediaBySetlist.keys)
        assertEquals(setOf("other"), s.playlistsBySetlist.keys)
        assertEquals(setOf("other"), s.calendarEventByGig.keys)
    }

    @Test fun `deleting the open gig closes its room`() {
        val s = held("gone").copy(selectedSetlist = FmSetlist(id = "gone")).deletingGig("gone", me)

        assertNull(s.selectedSetlist)
    }

    @Test fun `deleting another gig leaves the open room alone`() {
        val s = held("gone").copy(selectedSetlist = FmSetlist(id = "other")).deletingGig("gone", me)

        assertEquals("other", s.selectedSetlist?.id)
    }

    private fun storage(answer: GigDeletionOutcome) = object : GigStorage {
        override suspend fun delete(gigId: String) = answer
    }

    @Test fun `the screen is deleted from only after the storage deletes`() = runBlocking {
        var onScreen = false
        val outcome = deleteFromStorage("g", storage(GigDeletionOutcome.DELETED)) { onScreen = true }

        assertEquals(GigDeletionOutcome.DELETED, outcome)
        assertTrue(onScreen)
    }

    @Test fun `a gig the storage keeps stays on screen`() = runBlocking {
        var onScreen = false
        val outcome = deleteFromStorage("g", storage(GigDeletionOutcome.KEPT)) { onScreen = true }

        assertEquals(GigDeletionOutcome.KEPT, outcome)
        assertFalse(onScreen)
    }

    private fun store(copies: MutableList<String> = mutableListOf(), lane: String = me) = TimelineStore(
        File(temporary.newFolder(), "timeline.json"),
        myAttendedList = { lane },
        deleteLocalCopies = { copies += it.id },
    )

    @Test fun `deleting from the local store deletes the local copies of its media`() = runBlocking {
        val copies = mutableListOf<String>()
        val store = store(copies)
        val id = store.createLocalGig("25-09-2026", "Big Thief", "")
        store.saveMedia(id, listOf(StoredMedia(id = "m1", ref = "asset/1"), StoredMedia(id = "m2", ref = "asset/2")))

        assertEquals(GigDeletionOutcome.DELETED, store.delete(id))
        assertEquals(listOf("m1", "m2"), copies)
        assertNull(store.load().gigs[id])
    }

    @Test fun `deleting a gig the local store does not hold keeps everything`() = runBlocking {
        val copies = mutableListOf<String>()
        val store = store(copies)

        assertEquals(GigDeletionOutcome.KEPT, store.delete("nowhere"))
        assertTrue(copies.isEmpty())
    }

    @Test fun `deleting from the local store takes the gig out of my cached attended list`() = runBlocking {
        val store = store()
        store.save(shows = mapOf(me to listOf(FmSetlist(id = "a"), FmSetlist(id = "b"))))
        val id = store.createLocalGig("25-09-2026", "Big Thief", "")
        store.adoptSetlistId(id, "a")

        store.delete("a")

        assertEquals(listOf("b"), store.load().shows[me].orEmpty().map { it.id })
    }
}
