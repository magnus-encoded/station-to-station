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
    fun aBookingCodeAndAnAdsTaglineDoNotOutrankTheRealEventLineEither() {
        // The previous test's fix (prefer caps) was verified against a guess at what
        // OCR would produce. Run against a real device, this same ticket's actual PDF
        // also bundles a gift-card ad on the same page, and both an internal booking
        // code ("OPT2901") and the ad's own tagline ("DEL EN OPPLEVELSE!") are
        // themselves caps-styled — a caps preference alone hands the confirm dialog
        // "OPT2901" / "DEL EN OPPLEVELSE!" instead of "SKAMBANKT" / "PARKTEATRET
        // SCENE". This is that real ML Kit output, trimmed to the blocks that matter
        // for this decision.
        val extract = TicketExtract(
            textBlocks = listOf(
                "Dette er din billett",
                "Ta med hele siden til arrangementet",
                "Magnus Hustveit",
                "Kundenummer:",
                "1813111",
                "Arrangementskode:",
                "OPT2901",
                "Kjøpsdato:",
                "Ordrenummer:",
                "17424705",
                "billettservice",
                "I Gaver",
                "Gi levende",
                "underholdningi gave",
                "DEL EN OPPLEVELSE!",
                "YNGLING & ØYA UNDER 18:",
                "SKAMBANKT",
                "Vi har to forskjellige typer gavekort:",
                "PARKTEATRET SCENE",
                "OLAF RYES PLASS 11",
                "DØRENE ÅPNER KL.18.00",
                "Send gavekort per post",
                "TORSDAG 29.01.2015",
                "FRI ALDERSGRENSE",
                "KJØPTE BILL. REFUNDERES IKKE",
            ),
        )

        val parsed = parseTicket(extract)

        assertEquals("SKAMBANKT", parsed.artist)
        assertEquals("PARKTEATRET SCENE", parsed.venue)
        assertEquals("29-01-2015", parsed.date)
    }

    @Test
    fun anUnstyledEventimTicketFallsBackToTheLinesBesideTheDate() {
        // A third real ticket (Eventim), reported alongside the Billettservice one:
        // no line on this layout is vendor-styled caps at all, so isShoutyLabel finds
        // nothing to prefer and "first two non-date lines" would hand the confirm
        // dialog the banner ("Dette er din billett") again. The layout does carry a
        // different, still-generic signal: the artist prints immediately before the
        // date and the venue immediately after it. Trimmed to the blocks that matter;
        // "presenterer:" is the vendor's own label line and must not win instead.
        val extract = TicketExtract(
            textBlocks = listOf(
                "Dette er din billett",
                "Vis billetten på din telefon eller print den ut",
                "Booking details",
                "Magnus Meyer Europa",
                "Order number: 1102933078",
                "E-ticket code: NUSA7D2",
                "Terms and conditions",
                "Please check the ticket for event, date and time.",
                "000310038500200020010000",
                "Stageway, ATL & Ramalama presenterer:",
                "Dumdumboys – XL [romertallførti]",
                "28. nov. 2026 kl. 20.00",
                "Trondheim Spektrum",
                "Klostergata 90, 7030 Trondheim",
                "Kunde: Magnus Meyer Europa",
                "NOK 935,00 - fees included",
                "Inngang 2/Inngang 4",
                "STÅPLASS/STANDING",
                "Dørene åpner 18:00",
                "OrdreID: 0003081683",
            ),
        )

        val parsed = parseTicket(extract)

        assertEquals("Dumdumboys – XL [romertallførti]", parsed.artist)
        assertEquals("Trondheim Spektrum", parsed.venue)
        assertEquals("28-11-2026", parsed.date)
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
