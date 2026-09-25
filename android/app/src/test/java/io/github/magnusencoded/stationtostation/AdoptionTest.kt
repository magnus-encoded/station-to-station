package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.StoredAttendance
import io.github.magnusencoded.stationtostation.data.StoredLog
import io.github.magnusencoded.stationtostation.data.StoredMedia
import io.github.magnusencoded.stationtostation.data.StoredPlaylist
import io.github.magnusencoded.stationtostation.data.TimelineStore
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A local **Gig** taking a setlist.fm id (#515).
 *
 * The store moves nothing on adoption — everything there is keyed by the local id and only
 * `keyOf` changes. The screens are keyed by that answer, so the state they read has to move
 * with it, and a **Log** read under the new id before it does is an empty one that the next
 * edit writes back over the real record.
 */
class AdoptionTest {

    @get:Rule val temporary = TemporaryFolder()
    private fun store() = TimelineStore(File(temporary.newFolder(), "timeline.json"))

    private val setlistId = "2b752c12"

    /** What `writeLog` does with the id the **Room** holds: read the state, edit, save. */
    private suspend fun UiState.addToLog(timeline: TimelineStore, gigId: String, song: String): UiState {
        val updated = (logsByGig[gigId] ?: StoredLog()).adding(song)
        timeline.saveLog(gigId, updated)
        return copy(logsByGig = logsByGig + (gigId to updated))
    }

    @Test
    fun `a line written after adoption keeps every line written before it`() = runBlocking {
        val timeline = store()
        val local = timeline.createLocalGig("24-09-2026", "The Warning", "Rockefeller")
        timeline.saveAttendance(local, StoredAttendance(StoredAttendance.Provenance.CHECKED_IN, checkedInAt = 1L))
        timeline.saveLog(local, StoredLog().adding("song1").adding("song2"))
        val before = timeline.load()
        val state = UiState(logsByGig = before.logs(), attendanceByGig = before.attendance())

        assertTrue(timeline.adoptSetlistId(local, setlistId))
        val after = state.adopting(local, setlistId).addToLog(timeline, setlistId, "song3")

        assertEquals(listOf("song1", "song2", "song3"), after.logsByGig[setlistId]?.named())
        assertEquals(listOf("song1", "song2", "song3"), timeline.load().logs()[setlistId]?.named())
        // And the **Room** still offers the **Log**: its **Check-in** came along.
        assertEquals(StoredAttendance.Provenance.CHECKED_IN, after.attendanceByGig[setlistId]?.provenance)
    }

    @Test
    fun `everything the Room reads by gig id moves to the adopted id together`() {
        val local = "756d178c"
        val stub = FmSetlist(id = local, eventDate = "24-09-2026")
        val state = UiState(
            logsByGig = mapOf(local to StoredLog().adding("song1")),
            attendanceByGig = mapOf(local to StoredAttendance(StoredAttendance.Provenance.CHECKED_IN)),
            calendarEventByGig = mapOf(local to "content://com.android.calendar/events/7"),
            mediaBySetlist = mapOf(local to listOf(StoredMedia(id = "m1"))),
            playlistsBySetlist = mapOf(local to listOf(StoredPlaylist(url = "https://open.spotify.com/p"))),
            plannedGigs = listOf(stub),
            setlists = listOf(stub),
            selectedSetlist = stub,
            checkInOffer = stub,
            linkedGig = local,
            witnessedGigs = setOf(local),
            gossipEligibleUntil = mapOf(local to 99L),
            gossipActiveGig = local,
            gossipStoppedGigs = setOf(local),
            gossipGigAliases = mapOf(local to setOf(local)),
        )

        val adopted = state.adopting(local, setlistId)

        assertEquals(listOf("song1"), adopted.logsByGig[setlistId]?.named())
        assertEquals(StoredAttendance.Provenance.CHECKED_IN, adopted.attendanceByGig[setlistId]?.provenance)
        assertEquals("content://com.android.calendar/events/7", adopted.calendarEventByGig[setlistId])
        assertEquals("m1", adopted.mediaBySetlist[setlistId]?.single()?.id)
        assertEquals("https://open.spotify.com/p", adopted.playlistsBySetlist[setlistId]?.single()?.url)
        listOf(adopted.logsByGig, adopted.attendanceByGig, adopted.calendarEventByGig,
            adopted.mediaBySetlist, adopted.playlistsBySetlist).forEach { assertFalse(local in it) }

        assertEquals(listOf(setlistId), adopted.plannedGigs.map { it.id })
        assertEquals(listOf(setlistId), adopted.setlists.map { it.id })
        assertEquals(setlistId, adopted.selectedSetlist?.id)
        assertEquals(setlistId, adopted.checkInOffer?.id)
        assertEquals(setlistId, adopted.linkedGig)

        // The gossip projections name both ids already; they gain the new one without losing
        // the old, because the record may still be filed under either.
        assertEquals(setOf(local, setlistId), adopted.witnessedGigs)
        assertEquals(99L, adopted.gossipEligibleUntil[setlistId])
        assertEquals(setlistId, adopted.gossipActiveGig)
        assertTrue(setlistId in adopted.gossipStoppedGigs)
        assertEquals(setOf(local, setlistId), adopted.gossipGigAliases[setlistId])
        assertEquals(setOf(local, setlistId), adopted.gossipGigAliases[local])
    }

    /**
     * Adoption onto an id another **Gig** already holds merges the two in the store (#128).
     * The state then holds an entry under each id, and neither may win by overwriting.
     */
    @Test
    fun `adopting onto an id already on screen combines the two rather than replacing one`() {
        val local = "756d178c"
        val state = UiState(
            logsByGig = mapOf(
                setlistId to StoredLog().adding("theirs"),
                local to StoredLog().adding("mine"),
            ),
            attendanceByGig = mapOf(
                setlistId to StoredAttendance(StoredAttendance.Provenance.PLANNED),
                local to StoredAttendance(StoredAttendance.Provenance.CHECKED_IN),
            ),
            mediaBySetlist = mapOf(
                setlistId to listOf(StoredMedia(id = "a")),
                local to listOf(StoredMedia(id = "b")),
            ),
        )

        val adopted = state.adopting(local, setlistId)

        assertEquals(setOf("theirs", "mine"), adopted.logsByGig[setlistId]?.named()?.toSet())
        assertEquals(StoredAttendance.Provenance.CHECKED_IN, adopted.attendanceByGig[setlistId]?.provenance)
        assertEquals(listOf("a", "b"), adopted.mediaBySetlist[setlistId]?.map { it.id })
        assertNull(adopted.logsByGig[local])
    }
}
