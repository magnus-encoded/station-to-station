package io.github.magnusencoded.stationtostation.features.tickets

import android.app.Application
import io.github.magnusencoded.stationtostation.PendingTicket
import io.github.magnusencoded.stationtostation.UiState
import io.github.magnusencoded.stationtostation.data.Admission
import io.github.magnusencoded.stationtostation.data.ParsedTicket
import io.github.magnusencoded.stationtostation.data.StoredAttendance
import io.github.magnusencoded.stationtostation.data.TicketOriginals
import io.github.magnusencoded.stationtostation.data.TimelineStore
import io.github.magnusencoded.stationtostation.data.musicbrainz.MusicBrainzClient
import io.github.magnusencoded.stationtostation.data.setlistfm.FmArtist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmVenue
import io.github.magnusencoded.stationtostation.data.setlistfm.SetlistFmClient
import io.github.magnusencoded.stationtostation.features.FakeState
import io.github.magnusencoded.stationtostation.features.planning.PlanningController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class TicketsControllerTest {

    private val store = TimelineStore(File.createTempFile("timelines", ".json").also { it.delete() })
    private val originals = TicketOriginals(Files.createTempDirectory("originals").toFile())
    private val job = Job()
    private val failures = mutableListOf<Exception>()

    // The paths under test never reach these; a Context is unavailable off-device.
    private inline fun <reified T> unbuilt(): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").also { it.isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, T::class.java) as T
    }

    private fun controller(fake: FakeState): TicketsController {
        val scope = CoroutineScope(job + Dispatchers.Default)
        val setlistFm = SetlistFmClient(keySource = { null })
        val planning = PlanningController(
            fake.state, fake.update, store, setlistFm, unbuilt<MusicBrainzClient>(), scope, { failures += it },
        )
        return TicketsController(
            fake.state, fake.update, store, setlistFm, originals, unbuilt<Application>(), scope, planning,
            adoptSetlist = { _, _, _, _ -> true }, lookUpLocalGig = { _, _ -> true },
        )
    }

    private fun settle() = runBlocking { job.children.toList().forEach { it.join() } }

    private val admission = Admission("ticket-payload".toByteArray(), "qr")

    private fun pending(admissions: List<Admission> = listOf(admission)) =
        PendingTicket(ParsedTicket(admissions = admissions, artist = "Wilco"), possibleMatch = null)

    @Test
    fun `discarding a ticket takes it off the queue and forgets its kept file`() {
        val kept = originals.keep(ByteArrayInputStream("%PDF".toByteArray()), "pdf")
        val ticket = pending(listOf(admission.withOriginal(kept)))
        val fake = FakeState(UiState(pendingTickets = listOf(ticket)))

        controller(fake).dismissPendingTicket(ticket.id)

        assertTrue(fake.current.pendingTickets.isEmpty())
        assertNull(originals.file(kept))
    }

    @Test
    fun `confirming without a date keeps the ticket waiting and says why`() {
        val ticket = pending()
        val fake = FakeState(UiState(pendingTickets = listOf(ticket)))

        controller(fake).confirmPendingTicket(ticket.id, "Wilco", "", "next week")
        settle()

        assertEquals(listOf(ticket), fake.current.pendingTickets)
        assertNotNull(fake.current.error)
    }

    @Test
    fun `confirming a night nobody holds mints a planned Gig carrying the Admission`() {
        val ticket = pending()
        val fake = FakeState(UiState(pendingTickets = listOf(ticket)))

        controller(fake).confirmPendingTicket(ticket.id, "Wilco", "Sentrum Scene", "14-09-2099")
        settle()

        val gig = fake.current.plannedGigs.single()
        assertEquals("Wilco", gig.artist?.name)
        assertEquals(StoredAttendance.Provenance.PLANNED, fake.current.attendanceByGig[gig.id]?.provenance)
        assertEquals(1, fake.current.attendanceByGig[gig.id]?.admissions?.size)
        assertTrue(fake.current.pendingTickets.isEmpty())
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `confirming a night already on the Line attaches to it rather than minting`() {
        val known = FmSetlist(
            id = "g1", eventDate = "14-09-2099", artist = FmArtist(name = "Wilco"), venue = FmVenue(name = "Sentrum Scene"),
        )
        val ticket = pending()
        val fake = FakeState(UiState(plannedGigs = listOf(known), pendingTickets = listOf(ticket)))

        controller(fake).confirmPendingTicket(ticket.id, "Wilco", "Sentrum Scene", "14-09-2099")
        settle()

        assertEquals(listOf(known), fake.current.plannedGigs)
        assertEquals(1, fake.current.attendanceByGig["g1"]?.admissions?.size)
        assertTrue(fake.current.pendingTickets.isEmpty())
    }
}
