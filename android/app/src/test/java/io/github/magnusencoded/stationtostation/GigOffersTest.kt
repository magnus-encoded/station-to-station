package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.StoredAttendance
import io.github.magnusencoded.stationtostation.data.StoredLog
import io.github.magnusencoded.stationtostation.ui.Alcove
import io.github.magnusencoded.stationtostation.ui.Curtain
import io.github.magnusencoded.stationtostation.ui.CurtainAction
import io.github.magnusencoded.stationtostation.ui.GigAsKnown
import io.github.magnusencoded.stationtostation.ui.GigLeaf
import io.github.magnusencoded.stationtostation.ui.curtainAction
import io.github.magnusencoded.stationtostation.ui.gigOffers
import io.github.magnusencoded.stationtostation.ui.nightWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The table in #129, executable. Each test asserts what a **Gig** offers — never how it
 * decided — so the derived axes and the phase stay free to change.
 *
 * Extends `GigTimeStateTest`'s style: plain JVM, no Robolectric, no device.
 */
class GigOffersTest {

    private val today = LocalDate.of(2026, 8, 11)
    private val now = today.atTime(21, 30)

    // One line per row of the table. Test-local on purpose: it buys the readability a
    // named axes type would have bought, without spending domain vocabulary on it.
    private fun night(
        date: LocalDate = today,
        provenance: String? = null,
        log: StoredLog? = null,
        setlistId: String? = null,
        songs: Int = 0,
        calendarEvent: String? = null,
        ticketQrBytes: ByteArray? = null,
    ) = GigAsKnown(
        window = nightWindow(date),
        provenance = provenance,
        log = log,
        setlistId = setlistId,
        songCount = songs,
        calendarEvent = calendarEvent,
        ticketQrBytes = ticketQrBytes,
    )

    private val openLog = StoredLog(songs = listOf("Hollowmoor", ""), closed = false)
    private val closedLog = StoredLog(songs = listOf("Hollowmoor", "", "Vardhavn"), closed = true)
    private val checkedIn = StoredAttendance.Provenance.CHECKED_IN
    private val attended = StoredAttendance.Provenance.ATTENDED
    private val planned = StoredAttendance.Provenance.PLANNED

    private fun offers(gig: GigAsKnown) = gigOffers(gig, now)

    // --- The table, row by row ------------------------------------------------

    @Test
    fun `a night three weeks out offers the calendar and asks whether the event moved`() {
        val o = offers(night(date = today.plusDays(21), provenance = planned))
        assertEquals(Alcove.ADD_TO_CALENDAR, o.alcove)
        assertEquals(Curtain.CHECK_EVENT, o.curtain)
        assertEquals(GigLeaf.PLAN, o.phase)
    }

    @Test
    fun `a calendar entry already made is the thing to open`() {
        val o = offers(night(date = today.plusDays(21), provenance = planned, calendarEvent = "content://calendar/events/7"))
        assertEquals(Alcove.OPEN_CALENDAR, o.alcove)
    }

    @Test
    fun `nothing is offered to record before the set`() {
        val o = offers(night(date = today.plusDays(21), provenance = planned))
        assertFalse(o.room.capture)
    }

    @Test
    fun `on the night, before checking in, the alcove is empty and the window is the event`() {
        val o = offers(night(provenance = planned))
        assertEquals(Alcove.NONE, o.alcove)
        assertEquals(Curtain.CHECK_EVENT, o.curtain)
        assertTrue(o.room.checkIn)
    }

    @Test
    fun `a ticket's QR shows before check-in`() {
        val o = offers(night(provenance = planned, ticketQrBytes = byteArrayOf(1, 2, 3)))
        assertTrue(o.room.showQr)
    }

    @Test
    fun `no ticket, no QR — never a placeholder`() {
        val o = offers(night(provenance = planned))
        assertFalse(o.room.showQr)
    }

    @Test
    fun `checking in retires the QR — the claim is already made`() {
        val o = offers(night(provenance = checkedIn, ticketQrBytes = byteArrayOf(1, 2, 3)))
        assertFalse(o.room.showQr)
    }

    @Test
    fun `the gate is checked-in specifically, not any claim at all`() {
        // `attended` is not `checkedIn` — an imported night with a ticket byte still
        // shows the QR, because nothing here has claimed I stood in this room.
        val o = offers(night(provenance = attended, ticketQrBytes = byteArrayOf(1, 2, 3)))
        assertTrue(o.room.showQr)
    }

    @Test
    fun `checked in with nothing posted pulls the artist's catalogue`() {
        val o = offers(night(provenance = checkedIn, log = openLog))
        assertEquals(Alcove.NONE, o.alcove)
        assertEquals(Curtain.CATALOGUE, o.curtain)
        assertTrue(o.room.capture)
    }

    @Test
    fun `checked in with a record linked pulls that setlist`() {
        val o = offers(night(provenance = checkedIn, log = openLog, setlistId = "s1"))
        assertEquals(Curtain.FETCH_SETLIST, o.curtain)
    }

    @Test
    fun `a closed Log nobody has posted offers setlist fm`() {
        val o = offers(night(date = today.minusDays(3), provenance = checkedIn, log = closedLog))
        assertEquals(Alcove.SETLIST_FM, o.alcove)
        assertEquals(Curtain.FETCH_SETLIST, o.curtain)
    }

    @Test
    fun `a record linked but empty is the same unfinished thing wearing an id`() {
        val o = offers(night(date = today.minusDays(3), provenance = checkedIn, log = closedLog, setlistId = "s1", songs = 0))
        assertEquals(Alcove.SETLIST_FM, o.alcove)
        assertEquals(Curtain.FETCH_SETLIST, o.curtain)
    }

    @Test
    fun `once the night is recorded the playlist is what remains`() {
        val o = offers(night(date = today.minusDays(3), provenance = checkedIn, log = closedLog, setlistId = "s1", songs = 15))
        assertEquals(Alcove.SPOTIFY, o.alcove)
        assertEquals(Curtain.CHECK_EDITS, o.curtain)
    }

    @Test
    fun `a past night with an open Log and no record still pulls the catalogue`() {
        val o = offers(night(date = today.minusDays(3), provenance = checkedIn, log = openLog))
        assertEquals(Alcove.NONE, o.alcove)
        assertEquals(Curtain.CATALOGUE, o.curtain)
    }

    // --- The three records that defeated a linear model -----------------------

    @Test
    fun `Valkyrien - checked in, Log closed, record with fifteen songs`() {
        val o = offers(night(date = today.minusDays(4), provenance = checkedIn, log = closedLog, setlistId = "637062c7", songs = 15))
        assertEquals(Alcove.SPOTIFY, o.alcove)
        assertEquals(Curtain.CHECK_EDITS, o.curtain)
    }

    @Test
    fun `Oyvind Holm - checked in, Log closed, record linked and empty`() {
        // Before or after "recorded to setlist.fm"? Both answers are wrong, which is
        // why this is a fold and not a sequence: the record is linked and holds nothing,
        // so the unfinished thing is still posting it.
        val o = offers(night(date = today.minusDays(4), provenance = checkedIn, log = closedLog, setlistId = "53705b8d", songs = 0))
        assertEquals(Alcove.SETLIST_FM, o.alcove)
        assertEquals(Curtain.FETCH_SETLIST, o.curtain)
    }

    @Test
    fun `Nirvana 1992 - attended, no Log, no check-in, record with songs`() {
        val o = offers(
            GigAsKnown(
                window = nightWindow(LocalDate.of(1992, 6, 28)),
                provenance = attended,
                log = null,
                setlistId = "old-one",
                songCount = 12,
            ),
        )
        // An imported night is not unfinished. It leads somewhere.
        assertEquals(Alcove.SPOTIFY, o.alcove)
        assertEquals(Curtain.CHECK_EDITS, o.curtain)
        assertFalse(o.room.checkIn)
        assertTrue(o.room.capture)
    }

    // --- The rules that are easy to break ------------------------------------

    @Test
    fun `reopening a closed Log takes the offers back with it`() {
        val gig = night(date = today.minusDays(3), provenance = checkedIn, log = closedLog, setlistId = "s1", songs = 15)
        assertEquals(Alcove.SPOTIFY, offers(gig).alcove)
        // Remembering a song three years later must cost nothing.
        val reopened = gig.copy(log = closedLog.copy(closed = false))
        assertEquals(Alcove.NONE, offers(reopened).alcove)
        assertEquals(Curtain.CHECK_EDITS, offers(reopened).curtain)
    }

    @Test
    fun `checking in before the listed start opens capture anyway`() {
        // Standing there is the strongest evidence the thing has begun.
        val early = LocalDate.of(2026, 8, 12)
        val o = gigOffers(night(date = early, provenance = checkedIn), now)
        assertTrue(o.room.capture)
        assertEquals(GigLeaf.CAPTURE, o.phase)
    }

    @Test
    fun `a night whose venue never geocoded can still be checked into`() {
        // 17 of the 18 checked-in nights on the device have no coordinates at all. The
        // decision takes no location, so there is nothing here that a missing fix
        // could gate.
        assertTrue(offers(night(provenance = planned)).room.checkIn)
    }

    @Test
    fun `an undated night keeps the plan-ahead offers`() {
        val o = gigOffers(GigAsKnown(window = null, provenance = planned), now)
        assertEquals(Alcove.ADD_TO_CALENDAR, o.alcove)
        assertEquals(GigLeaf.PLAN, o.phase)
        assertFalse(o.room.checkIn)
    }

    @Test
    fun `Gaps gate nothing`() {
        // A closed Log that is mostly "one I couldn't name" is still a set.
        val mostlyGaps = StoredLog(songs = listOf("", "", "Vardhavn", ""), closed = true)
        val o = offers(night(date = today.minusDays(3), provenance = checkedIn, log = mostlyGaps))
        assertEquals(Alcove.SETLIST_FM, o.alcove)
    }

    // --- The lattice ----------------------------------------------------------

    /**
     * Exhaustive rather than random: the state space is small enough to walk, and a
     * fixed walk cannot pass by not generating the awkward case.
     *
     * Time advances, evidence never downgrades, and a record only gains songs. Under
     * every one of those the phase must not move backwards and the calendar must not
     * come back — a future axis introducing a cycle is exactly what a table of examples
     * would miss.
     */
    @Test
    fun `no forward transition moves the offers backwards`() {
        val dates = listOf(today.plusDays(21), today, today.minusDays(3))
        val claims = listOf(null, planned, attended, checkedIn)
        val logs = listOf(null, openLog, closedLog)
        val records = listOf(null to 0, "s1" to 0, "s1" to 15)
        val rank = mapOf(GigLeaf.PLAN to 0, GigLeaf.CAPTURE to 1, GigLeaf.PUBLISH to 2)

        for (di in dates.indices) for (ci in claims.indices) for (log in logs) for (ri in records.indices) {
            val here = gigOffers(
                night(dates[di], claims[ci], log, records[ri].first, records[ri].second),
                now,
            )
            // Every legal step forward on each axis except the Log, which is free.
            val forward = buildList {
                if (di + 1 < dates.size) add(night(dates[di + 1], claims[ci], log, records[ri].first, records[ri].second))
                if (ci + 1 < claims.size) add(night(dates[di], claims[ci + 1], log, records[ri].first, records[ri].second))
                if (ri + 1 < records.size) add(night(dates[di], claims[ci], log, records[ri + 1].first, records[ri + 1].second))
            }
            for (next in forward) {
                val then = gigOffers(next, now)
                assertTrue(
                    "phase went backwards: $here -> $then",
                    rank.getValue(then.phase) >= rank.getValue(here.phase),
                )
                if (here.alcove != Alcove.ADD_TO_CALENDAR && here.alcove != Alcove.OPEN_CALENDAR) {
                    assertTrue(
                        "the calendar came back: $here -> $then",
                        then.alcove != Alcove.ADD_TO_CALENDAR && then.alcove != Alcove.OPEN_CALENDAR,
                    )
                }
                assertTrue("capture was taken away: $here -> $then", !here.room.capture || then.room.capture)
            }
        }
    }

    // --- curtainAction: what pulling the curtain actually asks for (#129) -----

    @Test
    fun `CATALOGUE dispatches to fetching the catalogue`() {
        assertEquals(CurtainAction.FETCH_CATALOGUE, curtainAction(Curtain.CATALOGUE))
    }

    @Test
    fun `FETCH_SETLIST dispatches to refreshing the setlist`() {
        assertEquals(CurtainAction.FETCH_SETLIST, curtainAction(Curtain.FETCH_SETLIST))
    }

    @Test
    fun `CHECK_EDITS also dispatches to refreshing the setlist, same call as FETCH_SETLIST`() {
        // A linked record with songs already on it and one still being typed both
        // resolve to "ask setlist.fm again" — the difference is only in what the
        // fold expects to learn, not in which endpoint answers.
        assertEquals(CurtainAction.FETCH_SETLIST, curtainAction(Curtain.CHECK_EDITS))
    }

    @Test
    fun `CHECK_EVENT dispatches to nothing — no event-moved endpoint exists yet`() {
        assertEquals(CurtainAction.NONE, curtainAction(Curtain.CHECK_EVENT))
    }
}
