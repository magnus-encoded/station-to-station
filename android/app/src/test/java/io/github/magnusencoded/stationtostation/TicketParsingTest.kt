package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.Admission
import io.github.magnusencoded.stationtostation.data.ParsedTicket
import io.github.magnusencoded.stationtostation.data.TicketBarcode
import io.github.magnusencoded.stationtostation.data.TicketEvidence
import io.github.magnusencoded.stationtostation.data.TicketReading
import io.github.magnusencoded.stationtostation.data.TicketRouting
import io.github.magnusencoded.stationtostation.data.TicketSupport
import io.github.magnusencoded.stationtostation.data.checkedForRedraw
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

    private fun qr(payload: String = "ticket-payload") = listOf(Admission(payload.toByteArray(), "qr"))

    /** The same QR once the app has redrawn it and read it back (story 29): what every ticket routed past the prompt carries. */
    private fun drawnQr(payload: String = "ticket-payload") = qr(payload).map { it.withRedrawable(true) }

    private fun barcode(symbology: String, payload: String, page: Int? = null) =
        TicketBarcode(image = ByteArray(0), payload = payload.toByteArray(), symbology = symbology, page = page)

    private fun List<Admission>.payloads() = map { it.payload.toString(Charsets.UTF_8) }

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

        assertEquals(qr(), parsed.admissions)
        assertNull(parsed.artist)
        assertNull(parsed.date)
        assertTrue(!parsed.isComplete)
        assertTrue(!parsed.isEmpty)
    }

    @Test
    fun textOnlyWithNoQrIsIncomplete() {
        val parsed = ocrOnly(listOf("Kaizers Orchestra", "Sentrum Scene", "24-06-2027"))

        assertTrue(parsed.admissions.isEmpty())
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

    // --- Admissions (#441) ---

    @Test
    fun aCode128IsAnAdmissionAndChangesNothingElse() {
        // #441: a Code 128 is kept as an Admission in its own symbology, where the
        // interim #534 only flagged it. The text parse is exactly what it would have
        // been without it.
        val lines = listOf("Kaizers Orchestra", "Sentrum Scene, Oslo", "24-06-2027")

        val withCode = ocrOnly(lines, barcodes = listOf(barcode("code128", "SYNTHETIC-CODE128-0001")))
        val plain = ocrOnly(lines)

        assertEquals(listOf("code128"), withCode.admissions.map { it.symbology })
        assertEquals(plain, withCode.copy(admissions = emptyList()))
    }

    @Test
    fun everyAdmissionIsKeptInPageOrderWhateverItsSymbology() {
        val parsed = parseTicketFields(
            TicketEvidence(
                readings = emptyList(),
                barcodes = listOf(
                    barcode("qr", "SYNTHETIC-QR-2", page = 1),
                    barcode("aztec", "SYNTHETIC-AZTEC-0001", page = 0),
                    barcode("qr", "SYNTHETIC-QR-1", page = 0),
                ),
            ),
        )

        assertEquals(listOf("SYNTHETIC-AZTEC-0001", "SYNTHETIC-QR-1", "SYNTHETIC-QR-2"), parsed.admissions.payloads())
        assertEquals(listOf(0, 0, 1), parsed.admissions.map { it.page })
    }

    @Test
    fun oneCodeOnThreePagesIsOneAdmissionAndTheFirstSymbologyWins() {
        val parsed = parseTicketFields(
            TicketEvidence(
                readings = emptyList(),
                barcodes = listOf(
                    barcode("qr", "SYNTHETIC-SAME", page = 0),
                    barcode("aztec", "SYNTHETIC-SAME", page = 1),
                    barcode("qr", "SYNTHETIC-SAME", page = 2),
                ),
            ),
        )

        assertEquals(listOf(Admission("SYNTHETIC-SAME".toByteArray(), "qr", page = 0)), parsed.admissions)
    }

    @Test
    fun retailCodesAreDroppedBesideAnythingElseAndKeptAlone() {
        val beside = parseTicketFields(
            TicketEvidence(emptyList(), listOf(barcode("ean13", "4006381333931"), barcode("qr", "SYNTHETIC-QR-1"))),
        )
        val alone = parseTicketFields(TicketEvidence(emptyList(), listOf(barcode("upca", "036000291452"))))

        assertEquals(listOf("SYNTHETIC-QR-1"), beside.admissions.payloads())
        assertEquals(listOf("upca"), alone.admissions.map { it.symbology })
    }

    @Test
    fun anAdmissionIsCorroboratedWhenTheTicketPrintsItAndKeptWhenItDoesNot() {
        val parsed = parseTicketFields(
            TicketEvidence(
                readings = listOf(TicketReading(TicketReading.Origin.TEXT_LAYER, listOf("Order", "*SYNTH46G7*"))),
                barcodes = listOf(barcode("qr", "SYNTH46G7"), barcode("qr", "SYNTH-UNPRINTED")),
            ),
        )

        assertEquals(listOf(true, false), parsed.admissions.map { it.corroborated })
    }

    @Test
    fun aPayloadThatIsNotUtf8IsNeverCorroborated() {
        val binary = TicketBarcode(image = ByteArray(0), payload = byteArrayOf(0x00, 0xFF.toByte()), symbology = "qr")
        val parsed = parseTicketFields(
            TicketEvidence(listOf(TicketReading(TicketReading.Origin.OCR, listOf("\u0000\uFFFD"))), listOf(binary)),
        )

        assertEquals(1, parsed.admissions.size)
        assertFalse(parsed.admissions.single().corroborated)
    }

    @Test
    fun aTicketWhoseOnlyAdmissionIsACode128CanBeComplete() {
        // #441: "first QR" is gone. An Eventim ticket read in full is as complete as a
        // QR one, and once its Code 128 has been redrawn and read back it is added
        // like one. Straight off the parse (unchecked) it is asked about.
        val parsed = ocrOnly(
            listOf("Kaizers Orchestra", "Sentrum Scene, Oslo", "24-06-2027"),
            barcodes = listOf(barcode("code128", "SYNTHETIC-CODE128-0001")),
        )
        val today = LocalDate.of(2027, 1, 1)

        assertTrue(parsed.isComplete)
        assertTrue(routeTicket(parsed, emptyList(), today) is TicketRouting.NeedsConfirmation)
        val drawn = parsed.checkedForRedraw()
        val routing = routeTicket(drawn, emptyList(), today)
        assertTrue(routing is TicketRouting.NewPlannedGig)
        assertEquals(drawn.admissions, (routing as TicketRouting.NewPlannedGig).admissions)
    }

    // --- #441, story 29: redrawn and read back at import, or asked about ---

    @Test
    fun aCompleteTicketWhoseBarcodeCannotBeRedrawnIsAskedAbout() {
        // Closes #542's open question 1: a complete Eventim read whose Code 128 did not
        // read back as itself is not added without asking.
        val eventim = ParsedTicket(
            admissions = listOf(Admission("123456789012345678901234".toByteArray(), "code128", redrawable = false)),
            artist = "Kaizers Orchestra",
            venue = "Sentrum Scene",
            date = "24-06-2027",
        )

        assertTrue("the read itself is complete", eventim.canSkipPrompt)
        assertTrue(routeTicket(eventim, emptyList(), LocalDate.of(2027, 1, 1)) is TicketRouting.NeedsConfirmation)
    }

    @Test
    fun aMatchWhoseBarcodeCannotBeRedrawnIsAskedAboutToo() {
        val gigs = listOf(known("g1", "24-06-2027", "Kaizers Orchestra"))
        val parsed = ParsedTicket(
            admissions = drawnQr() + Admission("SYNTH".toByteArray(), "maxicode", redrawable = false),
            artist = "Kaizers Orchestra",
            venue = "Sentrum Scene",
            date = "24-06-2027",
        )

        val routing = routeTicket(parsed, gigs, LocalDate.of(2027, 1, 1))

        assertTrue(routing is TicketRouting.NeedsConfirmation)
        assertEquals("g1", (routing as TicketRouting.NeedsConfirmation).possibleMatch?.id)
    }

    @Test
    fun anUncheckedAdmissionCountsAsNotRedrawable() {
        val gigs = listOf(known("g1", "24-06-2027", "Kaizers Orchestra"))
        val unchecked = ParsedTicket(admissions = qr(), artist = "Kaizers Orchestra", venue = "Sentrum Scene", date = "24-06-2027")
        val today = LocalDate.of(2027, 1, 1)

        assertFalse(unchecked.redrawsEveryAdmission)
        assertTrue(routeTicket(unchecked, emptyList(), today) is TicketRouting.NeedsConfirmation)
        assertTrue(routeTicket(unchecked, gigs, today) is TicketRouting.NeedsConfirmation)
    }

    @Test
    fun aCode128OnlyTicketIsCheckedWithTheRealRedraw() {
        // Through the real zxing redraw, not a stub: an Eventim-shaped 24-digit Code 128
        // reads back, so the complete ticket is added; a payload Code 128 cannot carry
        // does not, so it is asked about.
        val today = LocalDate.of(2027, 1, 1)
        fun eventim(payload: String) = ParsedTicket(
            admissions = listOf(Admission(payload.toByteArray(), "code128")),
            artist = "Kaizers Orchestra",
            venue = "Sentrum Scene",
            date = "24-06-2027",
        ).checkedForRedraw()

        assertTrue(routeTicket(eventim("123456789012345678901234"), emptyList(), today) is TicketRouting.NewPlannedGig)
        assertTrue(routeTicket(eventim("Kjøpt – ÆØÅ"), emptyList(), today) is TicketRouting.NeedsConfirmation)
    }

    // --- the #441 review: a complete read naming no known act, on a known night's date ---

    @Test
    fun aCompleteReadForAKnownNightsDateUnderAnotherNameIsAskedAboutNotMinted() {
        // Eventim's `Dumdumboys – XL [romertallførti]`: both readings agree on it, the
        // Code 128s read back, and the night was already planned by hand as `Dumdumboys`.
        // No artist match, but a night that day: asked about, with that night as the hint.
        val today = LocalDate.of(2027, 1, 1)
        val parsed = ParsedTicket(
            admissions = drawnQr(),
            artist = "Dumdumboys – XL [romertallførti]",
            venue = "Rockefeller",
            date = "24-06-2027",
        )
        assertTrue("minted when nothing is known that day", routeTicket(parsed, emptyList(), today) is TicketRouting.NewPlannedGig)

        val routing = routeTicket(parsed, listOf(known("g1", "24-06-2027", "Dumdumboys")), today)

        assertEquals("g1", (routing as TicketRouting.NeedsConfirmation).possibleMatch?.id)
    }

    @Test
    fun aKnownNightOnAnotherDateDoesNotStopTheMint() {
        val parsed = ParsedTicket(admissions = drawnQr(), artist = "Kaizers Orchestra", venue = "Sentrum Scene", date = "24-06-2027")

        val routing = routeTicket(parsed, listOf(known("g1", "25-06-2027", "Dumdumboys")), LocalDate.of(2027, 1, 1))

        assertTrue(routing is TicketRouting.NewPlannedGig)
    }

    @Test
    fun ofSeveralNightsThatDateTheHintIsTheOneAtTheTicketsVenue() {
        // A festival day mints many nights on one date. The hint is the one at the room the
        // ticket names; failing that, the first known that day. Provisional.
        val gigs = listOf(
            known("g1", "24-06-2027", "Big Thief", venue = "Sentrum Scene"),
            known("g2", "24-06-2027", "Dumdumboys", venue = "Rockefeller"),
        )
        val today = LocalDate.of(2027, 1, 1)
        fun at(venue: String) = ParsedTicket(admissions = drawnQr(), artist = "Dumdumboys – XL", venue = venue, date = "24-06-2027")

        assertEquals("g2", (routeTicket(at("Rockefeller"), gigs, today) as TicketRouting.NeedsConfirmation).possibleMatch?.id)
        assertEquals("g1", (routeTicket(at("Somewhere Else"), gigs, today) as TicketRouting.NeedsConfirmation).possibleMatch?.id)
    }

    @Test
    fun aPartialReadOnAKnownNightsDateCarriesTheSameHint() {
        // Asked about anyway; the hint is the same one a complete read gets.
        val parsed = ParsedTicket(admissions = qr(), artist = "Dumdumboys – XL", date = "24-06-2027")

        val routing = routeTicket(parsed, listOf(known("g1", "24-06-2027", "Dumdumboys")), LocalDate.of(2027, 1, 1))

        assertEquals("g1", (routing as TicketRouting.NeedsConfirmation).possibleMatch?.id)
    }

    @Test
    fun routingCarriesEveryAdmissionNotTheFirst() {
        val three = listOf("A", "B", "C").map { Admission("SYNTHETIC-$it".toByteArray(), "qr", redrawable = true) }
        val parsed = ParsedTicket(admissions = three, artist = "Kaizers Orchestra", venue = "Sentrum Scene", date = "24-06-2027")

        val routing = routeTicket(parsed, emptyList(), today = LocalDate.of(2027, 1, 1))

        assertEquals(three, (routing as TicketRouting.NewPlannedGig).admissions)
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
            admissions = drawnQr(),
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
            admissions = drawnQr(),
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
            admissions = drawnQr(),
            artist = "TICKETLINE",
            venue = "Sentrum Scene",
            date = "24-06-2027",
            artistSupport = TicketSupport.OCR,
            venueSupport = TicketSupport.BOTH,
            dateSupport = TicketSupport.BOTH,
            readingCount = 2,
        )
        val backed = single.copy(artist = "Kaizers Orchestra", artistSupport = TicketSupport.BOTH)
        val linked = ParsedTicket(admissions = drawnQr(), artist = "Kaizers Orchestra", venue = "Sentrum Scene", date = "24-06-2027")
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
            admissions = drawnQr(),
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
            admissions = drawnQr(),
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
            admissions = drawnQr(),
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
            ParsedTicket(admissions = drawnQr()),
            ParsedTicket(artist = "Kaizers Orchestra", venue = "Sentrum Scene", date = "24-06-2027"),
            ParsedTicket(admissions = drawnQr(), artist = "Kaizers Orchestra"),
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

    @Test
    fun theArtistNameIsFoldedBeforeItIsMatched() {
        // The country tag clashfinder and setlist.fm disagree about, folded through
        // nameKey the way every other match in this app folds it — and iOS's knownNight.
        val gigs = listOf(known("g1", "14-09-2027", "Wilco"))
        val parsed = ParsedTicket(admissions = drawnQr(), artist = "Wilco (US)", venue = "Sentrum Scene", date = "14-09-2027")

        val routing = routeTicket(parsed, gigs, today = LocalDate.of(2027, 1, 1))

        assertEquals("g1", (routing as TicketRouting.AlreadyKnown).gig.id)
    }

    @Test
    fun anArtistThatFoldsToNothingMatchesNoNight() {
        // nameKey("") is "", so without a guard a ticket artist of pure punctuation
        // would match any night that day whose artist is missing.
        val gigs = listOf(FmSetlist(id = "g1", eventDate = "14-09-2027", artist = null))

        assertNull(matchKnownNight(ParsedTicket(artist = "—", date = "14-09-2027"), gigs))
    }

    // --- the confirm step: the match is checked again on what was confirmed ---

    @Test
    fun editingTheArtistAtConfirmDropsTheRoutingTimeMatch() {
        // What routing matched is a hint for the prompt, not the answer: the person
        // said it is a different act, so the night routing found is not this one.
        val gigs = listOf(known("g1", "14-09-2027", "Wilco"))
        val routing = routeTicket(ParsedTicket(artist = "Wilco", date = "14-09-2027"), gigs, today = LocalDate.of(2027, 1, 1))
        assertEquals("g1", (routing as TicketRouting.NeedsConfirmation).possibleMatch?.id)

        assertNull(matchKnownNight(ParsedTicket(artist = "Big Thief", venue = "Sentrum Scene", date = "14-09-2027"), gigs))
    }

    @Test
    fun editingTheDateAtConfirmFindsTheNightItNowNames() {
        val gigs = listOf(known("g1", "14-09-2027", "Wilco"), known("g2", "15-09-2027", "Wilco"))
        val routing = routeTicket(ParsedTicket(artist = "Wilco", date = "14-09-2027"), gigs, today = LocalDate.of(2027, 1, 1))
        assertEquals("g1", (routing as TicketRouting.NeedsConfirmation).possibleMatch?.id)

        assertEquals("g2", matchKnownNight(ParsedTicket(artist = "Wilco (US)", date = "15-09-2027"), gigs)?.id)
    }
}
