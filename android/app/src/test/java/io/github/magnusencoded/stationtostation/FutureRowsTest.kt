package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.Festivals
import io.github.magnusencoded.stationtostation.data.StoredAttendance
import io.github.magnusencoded.stationtostation.data.StoredFestival
import io.github.magnusencoded.stationtostation.data.FutureRow
import io.github.magnusencoded.stationtostation.data.futureRows
import io.github.magnusencoded.stationtostation.data.localGigSetlist
import io.github.magnusencoded.stationtostation.ui.TimelineNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The future lane answers both questions the rest of the line does (#134). */
class FutureRowsTest {

    /** Every named gig is still only a plan — the claim, never map membership (#127). */
    private fun planned(vararg ids: String): Map<String, StoredAttendance> =
        ids.associateWith { StoredAttendance(StoredAttendance.Provenance.PLANNED) }

    private fun concert(gig: io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist) =
        FutureRow.Ticket(TimelineNode.Concert(gig))

    // Invented, on purpose: this repo is public and the device's own timeline is not
    // going in it. Same shape as the real case — two acts, one park, one night.
    private val marbleQuiet =
        localGigSetlist("mq", "Marble Quiet", LocalDate.of(2026, 8, 13), "Hollowmoor Park", "Vardhavn")
    private val tinFuneral =
        localGigSetlist("tf", "Tin Funeral", LocalDate.of(2026, 8, 13), "Hollowmoor Park", "Vardhavn")

    @Test
    fun `two planned gigs at one venue on one night are one Section, not two nodes`() {
        val rows = futureRows(listOf(marbleQuiet, tinFuneral), planned("mq", "tf"))
        assertEquals(1, rows.size)
        val node = (rows.single() as FutureRow.Ticket).node
        assertTrue(node is TimelineNode.Section)
        assertEquals(setOf("mq", "tf"), node.shows.map { it.id }.toSet())
    }

    @Test
    fun `a planned evening takes an identity where there is one, and its acts otherwise`() {
        // Unnamed, the evening is billed by who is playing it. It is not called
        // "Hollowmoor Park": a room is not the name of an event (#166), and the
        // venue fallback that used to sit here is the claim that bug was about.
        val plain = futureRows(listOf(marbleQuiet, tinFuneral), planned("mq", "tf"))
        assertEquals("Marble Quiet (Tin Funeral)", festivalNameOf(plain.single()))
        assertTrue((plain.single() as FutureRow.Ticket).node is TimelineNode.Section)

        // An identity is the whole difference: nothing about the two shows changed,
        // only what is known about the evening they belong to.
        val named = futureRows(
            listOf(marbleQuiet, tinFuneral),
            planned("mq", "tf"),
            festivals = Festivals(
                byId = mapOf("hm26" to StoredFestival(id = "hm26", name = "Hollowmoor Sound 2026")),
                idByShow = mapOf("mq" to "hm26", "tf" to "hm26"),
            ),
        )
        assertEquals("Hollowmoor Sound 2026", festivalNameOf(named.single()))
        assertTrue((named.single() as FutureRow.Ticket).node is TimelineNode.Festival)
    }

    @Test
    fun `a lone planned gig stays a plain node`() {
        val rows = futureRows(listOf(marbleQuiet), planned("mq"))
        assertEquals(concert(marbleQuiet), rows.single())
    }

    @Test
    fun `a night I checked into is not in the future lane, and never leaves gigPlanned`() {
        // The device case: fourteen of sixteen gigPlanned entries had been and gone.
        // Nothing is deleted — the map is the only home of these gigs' facts — the
        // lane just stops reading membership as a plan.
        val gigPlanned = listOf(marbleQuiet, tinFuneral)
        val rows = futureRows(
            gigPlanned,
            mapOf(
                "mq" to StoredAttendance(StoredAttendance.Provenance.CHECKED_IN),
                "tf" to StoredAttendance(StoredAttendance.Provenance.PLANNED),
            ),
        )
        assertEquals(listOf<Any>(concert(tinFuneral)), rows)
        assertEquals(2, gigPlanned.size)
    }

    @Test
    fun `an imported night with no claim at all is not a plan`() {
        assertTrue(futureRows(listOf(marbleQuiet), emptyMap()).isEmpty())
    }

    private fun festivalNameOf(row: FutureRow): String =
        ((row as FutureRow.Ticket).node as TimelineNode.Several).label
}
