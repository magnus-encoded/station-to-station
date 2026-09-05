package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.ParsedTicket
import io.github.magnusencoded.stationtostation.data.TicketExtract
import io.github.magnusencoded.stationtostation.data.TicketRouting
import io.github.magnusencoded.stationtostation.data.matchKnownNight
import io.github.magnusencoded.stationtostation.data.parseTicket
import io.github.magnusencoded.stationtostation.data.routeTicket
import io.github.magnusencoded.stationtostation.data.setlistfm.FmArtist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmVenue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * [io.github.magnusencoded.stationtostation.data.parseTicket] and
 * [io.github.magnusencoded.stationtostation.data.routeTicket] against synthetic
 * inputs, per #408/#411's own testing decision: the pure seam is exercised directly,
 * with no PDF, no bitmap, and no zxing/ML Kit call anywhere near these cases.
 */
class TicketParsingTest {

    private fun qr(payload: String = "ticket-payload") = payload.toByteArray()

    private fun known(id: String, date: String, artist: String, venue: String = "Rockefeller") = FmSetlist(
        id = id,
        eventDate = date, // dd-MM-yyyy
        artist = FmArtist(name = artist),
        venue = FmVenue(name = venue),
    )

    // --- parseTicket: reporting only, never a decision ---

    @Test
    fun aCleanParseFindsAllFourFields() {
        val extract = TicketExtract(
            qrBytes = qr(),
            textBlocks = listOf("Kaizers Orchestra", "Sentrum Scene, Oslo", "24-06-2027"),
        )

        val parsed = parseTicket(extract)

        assertEquals("Kaizers Orchestra", parsed.artist)
        assertEquals("Sentrum Scene, Oslo", parsed.venue)
        assertEquals("24-06-2027", parsed.date)
        assertTrue(parsed.isComplete)
    }

    @Test
    fun qrOnlyWithNoUsableTextIsIncompleteNotEmpty() {
        val parsed = parseTicket(TicketExtract(qrBytes = qr(), textBlocks = emptyList()))

        assertEquals(qr().toList(), parsed.qrBytes!!.toList())
        assertNull(parsed.artist)
        assertNull(parsed.date)
        assertTrue(!parsed.isComplete)
        assertTrue(!parsed.isEmpty)
    }

    @Test
    fun textOnlyWithNoQrIsIncomplete() {
        val parsed = parseTicket(
            TicketExtract(qrBytes = null, textBlocks = listOf("Kaizers Orchestra", "Sentrum Scene", "24-06-2027")),
        )

        assertNull(parsed.qrBytes)
        assertEquals("Kaizers Orchestra", parsed.artist)
        assertEquals("24-06-2027", parsed.date)
        assertTrue(!parsed.isComplete)
    }

    @Test
    fun nothingUsableAtAllIsReportedAsEmpty() {
        val parsed = parseTicket(TicketExtract())

        assertTrue(parsed.isEmpty)
        assertTrue(!parsed.isComplete)
    }

    @Test
    fun aBannerLineAboveTheEventDetailsDoesNotWinOverTheStyledEventLine() {
        // Real bug, real ticket (a Norwegian Billettservice/Ticketmaster e-ticket):
        // OCR reads an instructional banner ahead of the actual event details, and
        // "first two non-date lines" confidently handed the banner to the confirm
        // dialog instead of the artist/venue. Both banner lines are ordinary
        // sentence-case Norwegian; the real event/venue line is vendor-styled caps.
        val extract = TicketExtract(
            textBlocks = listOf(
                "Dette er din billett",
                "Ta med hele siden til arrangementet",
                "SKAMBANKT",
                "PARKTEATRET SCENE",
                "TORSDAG 29.01.2015",
            ),
        )

        val parsed = parseTicket(extract)

        assertEquals("SKAMBANKT", parsed.artist)
        assertEquals("PARKTEATRET SCENE", parsed.venue)
        assertEquals("29-01-2015", parsed.date)
    }

    @Test
    fun aLongFormDateIsRecognisedToo() {
        // Generic date shapes, not any one vendor's — see routeTicket's own doc.
        val parsed = parseTicket(TicketExtract(textBlocks = listOf("Doors 19:00, 24th June 2027")))

        assertEquals("24-06-2027", parsed.date)
    }

    // --- routeTicket: only a complete, unambiguous parse skips confirmation ---

    @Test
    fun aCompleteParseMatchingAKnownNightIsAMatchNotADuplicate() {
        val gigs = listOf(known("g1", "24-06-2027", "Kaizers Orchestra"))
        val parsed = ParsedTicket(
            qrBytes = qr(),
            artist = "Kaizers Orchestra",
            venue = "Sentrum Scene",
            date = "24-06-2027",
        )

        val routing = routeTicket(parsed, gigs, today = LocalDate.of(2027, 1, 1))

        assertTrue(routing is TicketRouting.AlreadyKnown)
        assertEquals("g1", (routing as TicketRouting.AlreadyKnown).gig.id)
    }

    @Test
    fun aCompleteUnmatchedFutureParseIsANewPlannedGig() {
        val parsed = ParsedTicket(
            qrBytes = qr(),
            artist = "Kaizers Orchestra",
            venue = "Sentrum Scene",
            date = "24-06-2027",
        )

        val routing = routeTicket(parsed, emptyList(), today = LocalDate.of(2027, 1, 1))

        assertTrue(routing is TicketRouting.NewPlannedGig)
        val gig = routing as TicketRouting.NewPlannedGig
        assertEquals("Kaizers Orchestra", gig.artist)
        assertEquals("24-06-2027", gig.date)
    }

    @Test
    fun aCompleteUnmatchedPastParseStillNeedsConfirmation() {
        // Story 13: an old ticket found while cleaning out email must not become a
        // phantom future plan just because every field happened to parse.
        val parsed = ParsedTicket(
            qrBytes = qr(),
            artist = "Kaizers Orchestra",
            venue = "Sentrum Scene",
            date = "24-06-2020",
        )

        val routing = routeTicket(parsed, emptyList(), today = LocalDate.of(2027, 1, 1))

        assertTrue(routing is TicketRouting.NeedsConfirmation)
    }

    @Test
    fun everyPartialParseNeedsConfirmationAsTheNormNotAnEdgeCase() {
        // #411's clarifying comment: confirm-first applies whether nothing was
        // extracted, only the QR, only some text fields, or everything short of a
        // full match/no-match — never a silent add, never a silent drop.
        val cases = listOf(
            ParsedTicket(qrBytes = qr()),
            ParsedTicket(artist = "Kaizers Orchestra", venue = "Sentrum Scene", date = "24-06-2027"),
            ParsedTicket(qrBytes = qr(), artist = "Kaizers Orchestra"),
            ParsedTicket(),
        )
        for (parsed in cases) {
            val routing = routeTicket(parsed, emptyList(), today = LocalDate.of(2027, 1, 1))
            assertTrue("$parsed should need confirmation", routing is TicketRouting.NeedsConfirmation)
        }
    }

    @Test
    fun matchKnownNightIgnoresArtistCaseAndWhitespace() {
        val gigs = listOf(known("g1", "24-06-2027", "Kaizers Orchestra"))

        val match = matchKnownNight(ParsedTicket(artist = "  kaizers orchestra  ", date = "24-06-2027"), gigs)

        assertEquals("g1", match?.id)
    }

    @Test
    fun matchKnownNightRequiresBothDateAndArtist() {
        val gigs = listOf(known("g1", "24-06-2027", "Kaizers Orchestra"))

        assertNull(matchKnownNight(ParsedTicket(date = "24-06-2027"), gigs))
        assertNull(matchKnownNight(ParsedTicket(artist = "Kaizers Orchestra"), gigs))
    }
}
