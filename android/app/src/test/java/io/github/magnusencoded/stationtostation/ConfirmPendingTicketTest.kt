package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.Admission
import io.github.magnusencoded.stationtostation.data.ParsedTicket
import io.github.magnusencoded.stationtostation.data.TicketRouting
import io.github.magnusencoded.stationtostation.data.routeTicket
import io.github.magnusencoded.stationtostation.data.setlistfm.FmArtist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmVenue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * The confirm dialog's Save, as `AppViewModel.confirmPendingTicket` acts on it (#526):
 * where the ticket lands is decided on the values the person confirmed, never on the
 * [PendingTicket.possibleMatch] routing found for what the parse read — as iOS's
 * `confirmTicket` does.
 */
class ConfirmPendingTicketTest {

    private val admissions = listOf(Admission("ticket-payload".toByteArray(), "qr"))

    private fun known(id: String, date: String, artist: String, venue: String = "Rockefeller") =
        FmSetlist(id = id, eventDate = date, artist = FmArtist(name = artist), venue = FmVenue(name = venue))

    /** What `routeParsedTicket` queues for the prompt, from a real routing decision. */
    private fun pendingFor(parsed: ParsedTicket, gigs: List<FmSetlist>): PendingTicket {
        val routing = routeTicket(parsed, gigs, today = LocalDate.of(2027, 1, 1))
        val confirm = routing as TicketRouting.NeedsConfirmation
        return PendingTicket(confirm.parsed, confirm.possibleMatch)
    }

    @Test
    fun editingTheArtistAtConfirmMintsANightRatherThanTheStaleMatch() {
        val gigs = listOf(known("g1", "14-09-2027", "Wilco"))
        val pending = pendingFor(ParsedTicket(admissions = admissions, artist = "Wilco", date = "14-09-2027"), gigs)
        assertEquals("g1", pending.possibleMatch?.id)

        val confirmed = pending.confirmedAs("Big Thief", "Sentrum Scene", LocalDate.of(2027, 9, 14), gigs)

        assertEquals(
            ConfirmedTicket.Mint("Big Thief", "Sentrum Scene", LocalDate.of(2027, 9, 14), admissions),
            confirmed,
        )
    }

    @Test
    fun editingTheDateAtConfirmAttachesToTheNightItNowNames() {
        val gigs = listOf(known("g1", "14-09-2027", "Wilco"), known("g2", "15-09-2027", "Wilco"))
        val pending = pendingFor(ParsedTicket(admissions = admissions, artist = "Wilco", date = "14-09-2027"), gigs)
        assertEquals("g1", pending.possibleMatch?.id)

        val confirmed = pending.confirmedAs("Wilco (US)", "", LocalDate.of(2027, 9, 15), gigs)

        assertEquals(ConfirmedTicket.Attach("g2", admissions), confirmed)
    }

    @Test
    fun aSameDayPossibleMatchIsNotAttachedToWithoutTheAct() {
        // The #441 review's same-date guard sends this to the prompt with Dumdumboys'
        // night as the possible match. Saved as read, the act is not that night's act:
        // a night of its own, as on iOS. The prompt says so; the person edits the artist
        // to `Dumdumboys` to attach instead.
        val gigs = listOf(known("g1", "14-09-2027", "Dumdumboys", venue = "Sentrum Scene"))
        val parsed = ParsedTicket(
            admissions = admissions,
            artist = "Dumdumboys – XL [romertallførti]",
            venue = "Sentrum Scene",
            date = "14-09-2027",
        )
        val pending = pendingFor(parsed, gigs)
        assertEquals("g1", pending.possibleMatch?.id)
        val night = LocalDate.of(2027, 9, 14)

        assertEquals(
            ConfirmedTicket.Mint(parsed.artist!!, "Sentrum Scene", night, admissions),
            pending.confirmedAs(parsed.artist!!, "Sentrum Scene", night, gigs),
        )
        assertEquals(
            ConfirmedTicket.Attach("g1", admissions),
            pending.confirmedAs("Dumdumboys", "Sentrum Scene", night, gigs),
        )
    }

    @Test
    fun aPartialParseFilledInAtConfirmFindsTheNightAlreadyThere() {
        // Routing had no date, so no match; what the person confirmed names a known night.
        val gigs = listOf(known("g1", "14-09-2027", "Wilco"))
        val pending = pendingFor(ParsedTicket(admissions = admissions, artist = "Wilco"), gigs)
        assertEquals(null, pending.possibleMatch)

        val confirmed = pending.confirmedAs(" Wilco ", "", LocalDate.of(2027, 9, 14), gigs)

        assertEquals(ConfirmedTicket.Attach("g1", admissions), confirmed)
    }
}
