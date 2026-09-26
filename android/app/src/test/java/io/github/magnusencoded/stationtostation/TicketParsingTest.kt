package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.ParsedTicket
import io.github.magnusencoded.stationtostation.data.TicketBarcode
import io.github.magnusencoded.stationtostation.data.TicketEvidence
import io.github.magnusencoded.stationtostation.data.TicketReading
import io.github.magnusencoded.stationtostation.data.TicketRouting
import io.github.magnusencoded.stationtostation.data.TicketSupport
import io.github.magnusencoded.stationtostation.data.matchKnownNight
import io.github.magnusencoded.stationtostation.data.parseTicketFields
import io.github.magnusencoded.stationtostation.data.routeTicket
import io.github.magnusencoded.stationtostation.data.setlistfm.FmArtist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmVenue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * [io.github.magnusencoded.stationtostation.data.parseTicketFields] and
 * [io.github.magnusencoded.stationtostation.data.routeTicket] against synthetic
 * inputs, per #408/#411's own testing decision: the pure seam is exercised directly,
 * with no PDF, no bitmap, and no zxing/ML Kit call anywhere near these cases.
 *
 * The rules themselves are pinned by the shared corpus (TicketFixturesTest, which runs
 * `fixtures/ticket/` exactly as iOS does); these are the Android-side cases around it.
 * Real tickets' personal data here is made up.
 */
class TicketParsingTest {

    private fun qr(payload: String = "ticket-payload") = payload.toByteArray()

    private fun barcode(symbology: String, payload: String) =
        TicketBarcode(image = ByteArray(0), payload = payload.toByteArray(), symbology = symbology)

    /** One OCR reading, as a scan or a phone below API 35 gives. */
    private fun ocrOnly(lines: List<String>, barcodes: List<TicketBarcode> = emptyList()) =
        parseTicketFields(TicketEvidence(listOf(TicketReading(TicketReading.Origin.OCR, lines)), barcodes))

    private fun known(id: String, date: String, artist: String, venue: String = "Rockefeller") = FmSetlist(
        id = id,
        eventDate = date, // dd-MM-yyyy
        artist = FmArtist(name = artist),
        venue = FmVenue(name = venue),
    )

    // --- parseTicketFields: reporting only, never a decision ---

    @Test
    fun aCleanParseFindsAllFourFields() {
        val parsed = ocrOnly(
            listOf("Kaizers Orchestra", "Sentrum Scene, Oslo", "24-06-2027"),
            barcodes = listOf(barcode("qr", "ticket-payload")),
        )

        assertEquals("Kaizers Orchestra", parsed.artist)
        assertEquals("Sentrum Scene, Oslo", parsed.venue)
        assertEquals("24-06-2027", parsed.date)
        assertEquals(TicketSupport.OCR, parsed.artistSupport)
        assertEquals(1, parsed.readingCount)
        assertTrue(parsed.isComplete)
        assertTrue("one reading passes on completeness alone", parsed.canSkipPrompt)
    }

    @Test
    fun qrOnlyWithNoUsableTextIsIncompleteNotEmpty() {
        val parsed = parseTicketFields(
            TicketEvidence(readings = emptyList(), barcodes = listOf(barcode("qr", "ticket-payload"))),
        )

        assertEquals(qr().toList(), parsed.qrBytes!!.toList())
        assertNull(parsed.artist)
        assertNull(parsed.date)
        assertTrue(!parsed.isComplete)
        assertTrue(!parsed.isEmpty)
    }

    @Test
    fun textOnlyWithNoQrIsIncomplete() {
        val parsed = ocrOnly(listOf("Kaizers Orchestra", "Sentrum Scene", "24-06-2027"))

        assertNull(parsed.qrBytes)
        assertEquals("Kaizers Orchestra", parsed.artist)
        assertEquals("24-06-2027", parsed.date)
        assertTrue(!parsed.isComplete)
    }

    @Test
    fun nothingUsableAtAllIsReportedAsEmpty() {
        val parsed = parseTicketFields(TicketEvidence(readings = emptyList()))

        assertTrue(parsed.isEmpty)
        assertTrue(!parsed.isComplete)
    }

    @Test
    fun aCompleteReadSkipsThePromptOnlyWhenBothReadingsBackEveryField() {
        // #526: the text layer and OCR agree on every field — or one field came out of
        // OCR alone (here the date, printed as a picture), and a person looks first.
        val qrs = listOf(barcode("qr", "ticket-payload"))
        val agreed = parseTicketFields(
            TicketEvidence(
                listOf(
                    TicketReading(TicketReading.Origin.TEXT_LAYER, listOf("Kaizers Orchestra", "24-06-2027", "Sentrum Scene")),
                    TicketReading(TicketReading.Origin.OCR, listOf("KAIZERS ORCHESTRA", "24.06.2027", "Sentrum Scene")),
                ),
                qrs,
            ),
        )
        val disputed = parseTicketFields(
            TicketEvidence(
                listOf(
                    TicketReading(TicketReading.Origin.TEXT_LAYER, listOf("Kaizers Orchestra", "Sentrum Scene")),
                    TicketReading(TicketReading.Origin.OCR, listOf("Kaizers Orchestra", "Sentrum Scene", "24-06-2027")),
                ),
                qrs,
            ),
        )

        assertEquals("Kaizers Orchestra", agreed.artist)
        assertEquals(TicketSupport.BOTH, agreed.artistSupport)
        assertTrue(agreed.canSkipPrompt)
        assertEquals(TicketSupport.OCR, disputed.dateSupport)
        assertTrue(disputed.isComplete)
        assertFalse(disputed.canSkipPrompt)
    }

    @Test
    fun anUnsupportedBarcodeIsCarriedThroughAndChangesNothingElse() {
        // #441 interim: a Code 128 is reported for the confirm prompt, not stored as a
        // QR. The text parse is exactly what it would have been without it.
        val lines = listOf("Kaizers Orchestra", "Sentrum Scene, Oslo", "24-06-2027")

        val flagged = ocrOnly(lines, barcodes = listOf(barcode("code128", "SYNTHETIC-CODE128-0001")))
        val plain = ocrOnly(lines)

        assertEquals("code128", flagged.unsupportedBarcodeFormat)
        assertNull(flagged.qrBytes)
        assertEquals(plain.copy(unsupportedBarcodeFormat = "code128"), flagged)
        assertEquals(plain.isEmpty, flagged.isEmpty)
        assertEquals(plain.isComplete, flagged.isComplete)
    }

    @Test
    fun aNonQrFoundBeforeTheQrIsStillReportedAndTheFirstQrIsKept() {
        // That ticket may well be the Code 128 with an unrelated QR printed after it —
        // the prompt saying "bring the PDF" costs nothing there.
        val parsed = parseTicketFields(
            TicketEvidence(
                readings = emptyList(),
                barcodes = listOf(
                    barcode("code128", "SYNTHETIC-CODE128-0001"),
                    barcode("qr", "SYNTHETIC-QR-1"),
                    barcode("qr", "SYNTHETIC-QR-2"),
                ),
            ),
        )

        assertEquals("SYNTHETIC-QR-1", parsed.qrBytes?.toString(Charsets.UTF_8))
        assertEquals("code128", parsed.unsupportedBarcodeFormat)
    }

    @Test
    fun aTicketWhoseOnlyBarcodeIsUnsupportedAlwaysReachesThePrompt() {
        // No QR means never complete, so the prompt that says "bring the PDF" is
        // always shown — even when every text field parsed and the night is future.
        val parsed = ocrOnly(
            listOf("Kaizers Orchestra", "Sentrum Scene, Oslo", "24-06-2027"),
            barcodes = listOf(barcode("code128", "SYNTHETIC-CODE128-0001")),
        )

        val routing = routeTicket(parsed, emptyList(), today = LocalDate.of(2027, 1, 1))

        assertTrue(routing is TicketRouting.NeedsConfirmation)
        assertEquals("code128", (routing as TicketRouting.NeedsConfirmation).parsed.unsupportedBarcodeFormat)
    }

    @Test
    fun theFlagDoesNotChangeRoutingWhenAQrWasAlsoFound() {
        val gigs = listOf(known("g1", "24-06-2027", "Kaizers Orchestra"))
        val base = ParsedTicket(qrBytes = qr(), artist = "Kaizers Orchestra", venue = "Sentrum Scene", date = "24-06-2027")
        val today = LocalDate.of(2027, 1, 1)

        for (nights in listOf(gigs, emptyList())) {
            val plain = routeTicket(base, nights, today)
            val flagged = routeTicket(base.copy(unsupportedBarcodeFormat = "code128"), nights, today)
            assertEquals(plain::class, flagged::class)
        }
    }

    @Test
    fun aBannerLineAboveTheEventDetailsDoesNotWinOverTheStyledEventLine() {
        // Real bug, real ticket (a Norwegian Billettservice/Ticketmaster e-ticket):
        // OCR reads an instructional banner ahead of the actual event details, and
        // "first two non-date lines" confidently handed the banner to the confirm
        // dialog instead of the artist/venue. Both banner lines are ordinary
        // sentence-case Norwegian; the real event/venue line is vendor-styled caps.
        val parsed = ocrOnly(
            listOf(
                "Dette er din billett",
                "Ta med hele siden til arrangementet",
                "SKAMBANKT",
                "PARKTEATRET SCENE",
                "TORSDAG 29.01.2015",
            ),
        )

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
        // for this decision; the buyer and the numbers are made up.
        // `skambankt-billettservice-ocr-only` in fixtures/ticket is the same ticket as
        // ML Kit lines.
        val parsed = ocrOnly(
            listOf(
                "Dette er din billett",
                "Ta med hele siden til arrangementet",
                "Kari Nordmann",
                "Kundenummer:",
                "1000001",
                "Arrangementskode:",
                "OPT2901",
                "Kjøpsdato:",
                "Ordrenummer:",
                "10000001",
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

        assertEquals("SKAMBANKT", parsed.artist)
        assertEquals("PARKTEATRET SCENE", parsed.venue)
        assertEquals("29-01-2015", parsed.date)
    }

    @Test
    fun anUnstyledEventimTicketFallsBackToTheLinesBesideTheDate() {
        // A third real ticket (Eventim), reported alongside the Billettservice one:
        // no line on this layout is vendor-styled caps at all, so there is no caps
        // line to prefer and "first two non-date lines" would hand the confirm
        // dialog the banner ("Dette er din billett") again. The layout does carry a
        // different, still-generic signal: the artist prints immediately before the
        // date and the venue immediately after it. "presenterer:" is the vendor's own
        // label line and must not win instead. Personal data and numbers are made up.
        //
        // Only the venue and the date are asserted. The line above the date is
        // "Dumdumboys – XL [romertallførti]", and the artist is "Dumdumboys": no rule
        // says where the band's name ends, which `dumdumboys-eventim-ocr-only` in
        // fixtures/ticket carries as a known failure on both platforms.
        val parsed = ocrOnly(
            listOf(
                "Dette er din billett",
                "Vis billetten på din telefon eller print den ut",
                "Booking details",
                "Kari Nordmann",
                "Order number: 1000000001",
                "E-ticket code: ABCD1E2",
                "Terms and conditions",
                "Please check the ticket for event, date and time.",
                "000000000000000000000001",
                "Stageway, ATL & Ramalama presenterer:",
                "Dumdumboys – XL [romertallførti]",
                "28. nov. 2026 kl. 20.00",
                "Trondheim Spektrum",
                "Klostergata 90, 7030 Trondheim",
                "Kunde: Kari Nordmann",
                "NOK 935,00 - fees included",
                "Inngang 2/Inngang 4",
                "STÅPLASS/STANDING",
                "Dørene åpner 18:00",
                "OrdreID: 0000000001",
            ),
        )

        assertEquals("Trondheim Spektrum", parsed.venue)
        assertEquals("28-11-2026", parsed.date)
    }

    @Test
    fun aLongFormDateIsRecognisedToo() {
        // Generic date shapes, not any one vendor's — see routeTicket's own doc.
        val parsed = ocrOnly(listOf("Doors 19:00, 24th June 2027"))

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
    fun aCompleteParseOnlyOneReadingBackedIsAskedAboutNotMinted() {
        // #526: two readings, and the artist came out of OCR alone — the vendor logo
        // case. A ticket built from a link has no readings at all and still mints.
        val single = ParsedTicket(
            qrBytes = qr(),
            artist = "TICKETLINE",
            venue = "Sentrum Scene",
            date = "24-06-2027",
            artistSupport = TicketSupport.OCR,
            venueSupport = TicketSupport.BOTH,
            dateSupport = TicketSupport.BOTH,
            readingCount = 2,
        )
        val backed = single.copy(artist = "Kaizers Orchestra", artistSupport = TicketSupport.BOTH)
        val linked = ParsedTicket(qrBytes = qr(), artist = "Kaizers Orchestra", venue = "Sentrum Scene", date = "24-06-2027")
        val today = LocalDate.of(2027, 1, 1)

        assertTrue(routeTicket(single, emptyList(), today) is TicketRouting.NeedsConfirmation)
        assertTrue(routeTicket(backed, emptyList(), today) is TicketRouting.NewPlannedGig)
        assertTrue(routeTicket(linked, emptyList(), today) is TicketRouting.NewPlannedGig)
    }

    @Test
    fun aCompleteParseMatchingAKnownNightIsAMatchWhateverItsSupport() {
        // As on iOS: a match adds nothing new to the line, so completeness is enough.
        val gigs = listOf(known("g1", "24-06-2027", "Kaizers Orchestra"))
        val parsed = ParsedTicket(
            qrBytes = qr(),
            artist = "Kaizers Orchestra",
            venue = "Sentrum Scene",
            date = "24-06-2027",
            artistSupport = TicketSupport.OCR,
            venueSupport = TicketSupport.TEXT_LAYER,
            dateSupport = TicketSupport.BOTH,
            readingCount = 2,
        )

        assertTrue(routeTicket(parsed, gigs, today = LocalDate.of(2027, 1, 1)) is TicketRouting.AlreadyKnown)
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
    fun aTicketForTonightIsAddedAndOnlyYesterdaysIsPast() {
        // Tonight is a night you are going to — the day of the gig is when a ticket is
        // most often shared. Same three days, same answers as iOS's
        // testTheDayBeforeTheDayOfAndTheDayAfter.
        fun dated(date: String) = ParsedTicket(
            qrBytes = qr(),
            artist = "Kaizers Orchestra",
            venue = "Sentrum Scene",
            date = date,
        )
        val today = LocalDate.of(2027, 6, 24)

        val yesterday = routeTicket(dated("23-06-2027"), emptyList(), today = today)
        val tonight = routeTicket(dated("24-06-2027"), emptyList(), today = today)
        val tomorrow = routeTicket(dated("25-06-2027"), emptyList(), today = today)

        assertTrue("yesterday's ticket is asked about", yesterday is TicketRouting.NeedsConfirmation)
        assertTrue("tonight's ticket is added", tonight is TicketRouting.NewPlannedGig)
        assertTrue("tomorrow's ticket is added", tomorrow is TicketRouting.NewPlannedGig)
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
