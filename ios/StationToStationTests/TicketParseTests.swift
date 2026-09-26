import XCTest
@testable import StationToStation

/// Reading a PDF ticket (#412, #408).
///
/// Fed evidence directly, never a real PDF — the seam is deliberately above PDFKit and
/// Vision so these assertions say something about the rules rather than about a
/// device's renderer. Most cases here are one OCR reading, which is what a scan gives;
/// the cross-checking between two readings is `TicketFixtureTests`, over the corpus in
/// `fixtures/ticket/` that the Android twin runs too.
final class TicketParseTests: XCTestCase {

    /// Fixed, because "day first unless a number says otherwise" is a rule about the
    /// parser and not about wherever the test happens to run.
    private let calendar: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "Europe/Oslo")!
        return c
    }()

    private func day(_ year: Int, _ month: Int, _ dayOfMonth: Int) -> Date {
        calendar.date(from: DateComponents(year: year, month: month, day: dayOfMonth))!
    }

    private func parse(qr: Data? = nil, _ lines: [String]) -> TicketParse {
        let evidence = TicketEvidence(
            readings: [TicketReading(origin: .ocr, lines: lines)],
            barcodes: qr.map { [TicketBarcode(image: Data(), payload: $0, symbology: "qr")] } ?? [])
        return parseTicketFields(evidence, calendar: calendar)
    }

    private func ticket(qr: Data? = nil, _ blocks: [String]) -> Ticket {
        guard case .ticket(let found) = parse(qr: qr, blocks) else {
            XCTFail("expected a ticket from \(blocks)")
            return Ticket()
        }
        return found
    }

    private let qrBytes = Data("TKT-9F31-0042".utf8)
    private var qrAdmission: Admission { Admission(payload: qrBytes, symbology: "qr") }
    /// The same QR once the app has redrawn it and read it back (story 29): what every
    /// ticket routed past the prompt must carry.
    private var drawnQr: Admission { Admission(payload: qrBytes, symbology: "qr", redrawable: true) }

    private func barcode(_ symbology: String, _ payload: String, page: Int = 0) -> TicketBarcode {
        TicketBarcode(image: Data(), payload: Data(payload.utf8), symbology: symbology, page: page)
    }

    private func admissions(_ barcodes: [TicketBarcode], lines: [String] = []) -> [Admission] {
        let evidence = TicketEvidence(
            readings: lines.isEmpty ? [] : [TicketReading(origin: .textLayer, lines: lines)],
            barcodes: barcodes)
        guard case .ticket(let found) = parseTicketFields(evidence, calendar: calendar) else { return [] }
        return found.admissions
    }

    // MARK: - The five cases the acceptance criteria name

    /// A clean parse: every fact present, which is the only shape allowed past the
    /// confirmation prompt.
    func testACleanTicketYieldsEveryFact() {
        let found = ticket(qr: qrBytes, [
            "E-TICKET",
            "Artist: Big Thief",
            "Venue: Sentrum Scene",
            "Date: 14 September 2026",
            "Doors 19:00",
            "Order #4471193",
        ])

        XCTAssertEqual("Big Thief", found.artist)
        XCTAssertEqual("Sentrum Scene", found.venue)
        XCTAssertEqual(day(2026, 9, 14), found.date)
        XCTAssertEqual([qrAdmission], found.admissions)
        XCTAssertTrue(found.isComplete)
    }

    /// A QR read perfectly off a ticket whose text is a scan, or a language, or a
    /// layout this parser cannot make sense of. The QR is kept — losing the one thing
    /// that read cleanly because the rest did not is the failure story 10 names.
    func testAQrWithNoUsableTextStillKeepsTheQr() {
        let found = ticket(qr: qrBytes, ["", "#4471193", "NOK 690,00", "|||| |||| ||"])

        XCTAssertEqual([qrAdmission], found.admissions)
        XCTAssertNil(found.artist)
        XCTAssertNil(found.venue)
        XCTAssertNil(found.date)
        XCTAssertFalse(found.isComplete, "a QR alone is never enough to skip the prompt")
    }

    /// Text with no QR at all — a print-at-home PDF whose code did not survive
    /// rasterizing, or a ticket that never had one.
    func testTextWithNoQrStillParses() {
        let found = ticket(["Big Thief at Sentrum Scene", "14.09.2026"])

        XCTAssertEqual("Big Thief", found.artist)
        XCTAssertEqual("Sentrum Scene", found.venue)
        XCTAssertEqual(day(2026, 9, 14), found.date)
        XCTAssertTrue(found.admissions.isEmpty)
        XCTAssertFalse(found.isComplete)
    }

    /// Nothing usable is a real answer, not an error. Saying so beats planting a
    /// wrong night on the **Line**.
    func testNothingUsableIsSaidPlainly() {
        XCTAssertEqual(.nothingUsable, parse(["", "   ", "#4471193", "NOK 690,00"]))
        XCTAssertEqual(.nothingUsable, parse([]))
    }

    /// An extractor that found no code hands back empty bytes as readily as nil;
    /// neither is a QR.
    func testAnEmptyQrIsNoQrAtAll() {
        XCTAssertEqual(.nothingUsable, parse(qr: Data(), []))
    }

    // MARK: - Match versus new gig

    private func night(_ id: String, _ date: String, _ artist: String,
                       venue: String? = nil) -> FmSetlist {
        FmSetlist(id: id, eventDate: date, artist: FmArtist(name: artist),
                  venue: venue.map { FmVenue(name: $0) })
    }

    private var complete: Ticket {
        Ticket(admissions: [drawnQr], artist: "Big Thief", venue: "Sentrum Scene",
               date: day(2026, 9, 14))
    }

    /// Sharing the ticket for a night already on the line is safe to do: it is a
    /// match, never a second copy of the same evening.
    func testATicketForANightAlreadyOnTheLineIsAMatch() {
        let route = routeTicket(.ticket(complete),
                                knownNights: [night("g1", "14-09-2026", "Big Thief")],
                                now: day(2026, 8, 1), calendar: calendar)

        XCTAssertEqual(.match("g1"), route)
    }

    /// The same night, on a line that does not hold it: an ordinary planned **Gig**,
    /// minted without asking, because there is nothing left to ask.
    func testACompleteFutureTicketIsAddedWithoutAsking() {
        let route = routeTicket(.ticket(complete),
                                knownNights: [night("g1", "02-02-2026", "Someone Else")],
                                now: day(2026, 8, 1), calendar: calendar)

        XCTAssertEqual(.add(complete), route)
    }

    /// Same artist, same day, different room. One person cannot be at both, and a
    /// venue that has since been renamed is far likelier than a genuine second gig —
    /// so the venue never vetoes an artist match.
    func testAVenueThatDisagreesDoesNotBreakAnArtistMatch() {
        let route = routeTicket(.ticket(complete),
                                knownNights: [night("g1", "14-09-2026", "Big Thief",
                                                    venue: "Rockefeller")],
                                now: day(2026, 8, 1), calendar: calendar)

        XCTAssertEqual(.match("g1"), route)
    }

    /// The country tag clashfinder and setlist.fm disagree about, folded the same way
    /// every other match in this app folds it.
    func testTheArtistNameIsFoldedBeforeItIsMatched() {
        let ticket = Ticket(admissions: [drawnQr], artist: "Wilco (US)", venue: "Sentrum Scene",
                            date: day(2026, 9, 14))

        let route = routeTicket(.ticket(ticket),
                                knownNights: [night("g1", "14-09-2026", "Wilco")],
                                now: day(2026, 8, 1), calendar: calendar)

        XCTAssertEqual(.match("g1"), route)
    }

    /// A ticket that named a room and no act still finds the night, because the room
    /// and the date together are an identity when nothing better is on offer.
    func testAVenueMatchesWhenTheTicketNamedNoArtist() {
        let ticket = Ticket(venue: "Sentrum Scene", date: day(2026, 9, 14))

        let found = knownNight(ticket,
                               among: [night("g1", "14-09-2026", "Big Thief",
                                             venue: "Sentrum Scene")],
                               calendar: calendar)

        XCTAssertEqual("g1", found?.id)
    }

    /// A date with nothing else is not a night. Two gigs on one day is ordinary.
    func testABareDateMatchesNothing() {
        XCTAssertNil(knownNight(Ticket(date: day(2026, 9, 14)),
                                among: [night("g1", "14-09-2026", "Big Thief")],
                                calendar: calendar))
    }

    // MARK: - Confirm-first

    /// The rule, not the exception: anything short of all four facts is put in front
    /// of the person before it becomes anything.
    func testAPartialParseIsAlwaysConfirmed() {
        var partial = complete
        partial.admissions = []

        let route = routeTicket(.ticket(partial), knownNights: [],
                                now: day(2026, 8, 1), calendar: calendar)

        XCTAssertEqual(.confirm(partial), route)
    }

    /// Even one that matches. What matched was a partial parse, and a partial parse is
    /// exactly what the person is there to correct. The night it matched goes with it as
    /// the prompt's hint, as Android's `possibleMatch` always has.
    func testAPartialParseThatMatchesIsStillConfirmed() {
        var partial = complete
        partial.venue = nil

        let route = routeTicket(.ticket(partial),
                                knownNights: [night("g1", "14-09-2026", "Big Thief")],
                                now: day(2026, 8, 1), calendar: calendar)

        XCTAssertEqual(.confirm(partial, possibleMatch: "g1"), route)
    }

    // MARK: - A known night's date, under another name (the #441 review)

    /// Eventim's `Dumdumboys – XL [romertallførti]`: both readings agree on it, the
    /// Code 128s read back, and the night was already planned by hand as `Dumdumboys`.
    /// No artist match, but a night that day: asked about, with that night as the hint.
    func testACompleteReadForAKnownNightsDateUnderAnotherNameIsAskedAboutNotMinted() {
        let eventim = Ticket(admissions: [drawnQr], artist: "Dumdumboys – XL [romertallførti]",
                             venue: "Rockefeller", date: day(2026, 9, 14))
        XCTAssertEqual(.add(eventim), routeTicket(.ticket(eventim), knownNights: [],
                                                  now: day(2026, 8, 1), calendar: calendar),
                       "minted when nothing is known that day")

        let route = routeTicket(.ticket(eventim),
                                knownNights: [night("g1", "14-09-2026", "Dumdumboys")],
                                now: day(2026, 8, 1), calendar: calendar)

        XCTAssertEqual(.confirm(eventim, possibleMatch: "g1"), route)
    }

    func testAKnownNightOnAnotherDateDoesNotStopTheMint() {
        let route = routeTicket(.ticket(complete),
                                knownNights: [night("g1", "15-09-2026", "Dumdumboys")],
                                now: day(2026, 8, 1), calendar: calendar)

        XCTAssertEqual(.add(complete), route)
    }

    /// A festival day mints many nights on one date. The hint is the one at the room the
    /// ticket names; failing that, the first known that day. Provisional.
    func testOfSeveralNightsThatDateTheHintIsTheOneAtTheTicketsVenue() {
        let nights = [night("g1", "14-09-2026", "Big Thief", venue: "Sentrum Scene"),
                      night("g2", "14-09-2026", "Dumdumboys", venue: "Rockefeller")]
        func at(_ venue: String) -> Ticket {
            Ticket(admissions: [drawnQr], artist: "Dumdumboys – XL", venue: venue, date: day(2026, 9, 14))
        }

        XCTAssertEqual(.confirm(at("Rockefeller"), possibleMatch: "g2"),
                       routeTicket(.ticket(at("Rockefeller")), knownNights: nights,
                                   now: day(2026, 8, 1), calendar: calendar))
        XCTAssertEqual(.confirm(at("Somewhere Else"), possibleMatch: "g1"),
                       routeTicket(.ticket(at("Somewhere Else")), knownNights: nights,
                                   now: day(2026, 8, 1), calendar: calendar))
    }

    /// Asked about anyway; the hint is the same one a complete read gets.
    func testAPartialReadOnAKnownNightsDateCarriesTheSameHint() {
        let partial = Ticket(admissions: [qrAdmission], artist: "Dumdumboys – XL", date: day(2026, 9, 14))

        XCTAssertEqual(.confirm(partial, possibleMatch: "g1"),
                       routeTicket(.ticket(partial),
                                   knownNights: [night("g1", "14-09-2026", "Dumdumboys")],
                                   now: day(2026, 8, 1), calendar: calendar))
    }

    /// An old ticket found while clearing out an inbox. Complete, unmatched, and in
    /// the past — minting it silently is the phantom plan story 13 asks us not to
    /// create, so it is asked about instead.
    func testACompletePastTicketIsNeverMintedSilently() {
        let route = routeTicket(.ticket(complete), knownNights: [],
                                now: day(2027, 1, 1), calendar: calendar)

        XCTAssertEqual(.confirm(complete), route)
    }

    /// Tonight still counts as a night you are going to.
    func testTheNightItselfIsStillAhead() {
        let route = routeTicket(.ticket(complete), knownNights: [],
                                now: day(2026, 9, 14), calendar: calendar)

        XCTAssertEqual(.add(complete), route)
    }

    /// The day either side of the night, held to the same answers Android's
    /// `aTicketForTonightIsAddedAndOnlyYesterdaysIsPast` asserts: seen from the day
    /// after, the ticket is past and asked about; from the day of or the day before,
    /// it is added.
    func testTheDayBeforeTheDayOfAndTheDayAfter() {
        func route(on now: Date) -> TicketRoute {
            routeTicket(.ticket(complete), knownNights: [], now: now, calendar: calendar)
        }

        XCTAssertEqual(.confirm(complete), route(on: day(2026, 9, 15)), "yesterday's ticket")
        XCTAssertEqual(.add(complete), route(on: day(2026, 9, 14)), "tonight's ticket")
        XCTAssertEqual(.add(complete), route(on: day(2026, 9, 13)), "tomorrow's ticket")
    }

    func testNothingUsableRoutesToAnHonestBlank() {
        XCTAssertEqual(.unreadable,
                       routeTicket(.nothingUsable, knownNights: [],
                                   now: day(2026, 8, 1), calendar: calendar))
    }

    // MARK: - Dates

    func testTheDateFormatsATicketActuallyUses() {
        XCTAssertEqual(day(2026, 9, 14), ticket(["2026-09-14"]).date)
        XCTAssertEqual(day(2026, 9, 14), ticket(["14/09/2026"]).date)
        XCTAssertEqual(day(2026, 9, 14), ticket(["14.09.2026"]).date)
        XCTAssertEqual(day(2026, 9, 14), ticket(["Mon 14 Sep 2026"]).date)
        XCTAssertEqual(day(2026, 9, 14), ticket(["14. september 2026"]).date)
        XCTAssertEqual(day(2026, 9, 14), ticket(["September 14, 2026"]).date)
        XCTAssertEqual(day(2026, 9, 14), ticket(["14th September 2026"]).date)
    }

    /// The one rule the two platforms are most likely to answer differently by
    /// accident, so it is written down and asserted: day first where nothing decides.
    func testAnAmbiguousNumericDateIsReadDayFirst() {
        XCTAssertEqual(day(2026, 4, 3), ticket(["03/04/2026"]).date)
    }

    /// And where a number decides it, it decides it in either direction.
    func testANumberOverTwelveSettlesTheOrderItself() {
        XCTAssertEqual(day(2026, 9, 14), ticket(["14/09/2026"]).date)
        XCTAssertEqual(day(2026, 9, 14), ticket(["09/14/2026"]).date)
    }

    /// `14/09/26` could be a year or a day of the month. The prompt is a cheaper place
    /// to settle that than a guess is.
    func testATwoDigitYearIsNotReadAtAll() {
        XCTAssertNil(ticket(qr: qrBytes, ["14/09/26"]).date)
    }

    func testAnImpossibleDateIsNotADate() {
        XCTAssertNil(ticket(qr: qrBytes, ["32/09/2026"]).date)
        XCTAssertNil(ticket(qr: qrBytes, ["2026-13-01"]).date)
        XCTAssertNil(ticket(qr: qrBytes, ["31/02/2026"]).date)
    }

    /// A time is not a date, whatever separator OCR left behind.
    func testATimeIsNotADate() {
        XCTAssertNil(ticket(qr: qrBytes, ["Doors 19:30", "Support 20:15"]).date)
    }

    /// The first date down the page wins. A ticket carries a purchase date and a
    /// printed-on date as often as not, and they are below the night it is for.
    func testTheFirstDateDownThePageWins() {
        let found = ticket(["Event: 14 September 2026", "Purchased: 02 March 2026"])
        XCTAssertEqual(day(2026, 9, 14), found.date)
    }

    /// But a purchase date above the night does not win for printing first: a date on a
    /// line with a purchase word, or under a purchase label of its own, is passed over.
    func testAPurchaseDateAboveTheNightIsPassedOver() {
        XCTAssertEqual(day(2026, 11, 28),
                       ticket(["Order date: 01.05.2025", "28. nov. 2026 kl. 20.00"]).date)
        XCTAssertEqual(day(2026, 11, 28),
                       ticket(["Kjøpsdato:", "01.05.2025", "28.11.2026"]).date)
    }

    /// Passed over, never thrown away: with no other date it is still the best known.
    func testAPurchaseDateAloneIsStillRead() {
        XCTAssertEqual(day(2025, 5, 1), ticket(["Kjøpt 01.05.2025"]).date)
    }

    // MARK: - Artist and venue

    /// OCR breaks a label off its value about as often as it keeps them together.
    func testALabelFindsItsValueOnTheNextLine() {
        let found = ticket(["Artist:", "Big Thief", "Venue:", "Sentrum Scene"])

        XCTAssertEqual("Big Thief", found.artist)
        XCTAssertEqual("Sentrum Scene", found.venue)
    }

    func testTheSeparatorFormTicketsActuallyUse() {
        XCTAssertEqual("Big Thief", ticket(["Big Thief at Sentrum Scene"]).artist)
        XCTAssertEqual("Sentrum Scene", ticket(["Big Thief at Sentrum Scene"]).venue)
        XCTAssertEqual("Sentrum Scene", ticket(["Big Thief live at Sentrum Scene"]).venue)
        XCTAssertEqual("Sentrum Scene", ticket(["Big Thief @ Sentrum Scene"]).venue)
    }

    /// The rule that is deliberately absent. A dash separates a great many things on a
    /// ticket ("Doors — 19:00"), so a dashed line is never cut into an artist and a
    /// venue. It can still be guessed whole, like any other line, for the person to fix.
    func testADashIsNotASeparator() {
        let found = ticket(qr: qrBytes, ["Big Thief — Sentrum Scene"])

        XCTAssertEqual("Big Thief — Sentrum Scene", found.artist)
        XCTAssertNil(found.venue)
    }

    // MARK: - Guessing (#526)

    /// No labels and no "at": the lines either side of the date. This is
    /// ticket_dayof2.pdf, which iOS used to leave for the person to type.
    func testAnUnlabelledTicketIsGuessedFromTheLinesAroundTheDate() {
        let found = ticket(["Your ticket", "Static Halo", "24-09-2026", "Rockefeller, Oslo"])

        XCTAssertEqual("Static Halo", found.artist)
        XCTAssertEqual("Rockefeller, Oslo", found.venue)
        XCTAssertEqual(day(2026, 9, 24), found.date)
        XCTAssertEqual(.ocr, found.artistSupport)
    }

    /// A label outranks a guess, and the line it used is not guessed again.
    func testALabelledArtistLeavesOnlyTheVenueToGuess() {
        let found = ticket(["Artist: Big Thief", "Sentrum Scene", "Order #4471193"])

        XCTAssertEqual("Big Thief", found.artist)
        XCTAssertEqual("Sentrum Scene", found.venue)
    }

    /// Caps lines go ahead of prose, but a caps line with a digit, a slash or an ad's
    /// exclamation mark is a code, a seat or a tagline, never a name.
    func testACapsEventLineOutranksABannerAndACode() {
        let found = ticket([
            "Dette er din billett", "OPT2901", "DEL EN OPPLEVELSE!", "STÅPLASS/STANDING",
            "SKAMBANKT", "PARKTEATRET SCENE", "TORSDAG 29.01.2015",
        ])

        XCTAssertEqual("SKAMBANKT", found.artist)
        XCTAssertEqual("PARKTEATRET SCENE", found.venue)
    }

    /// A caps banner naming the ticket is never the artist, even on a scan with no text
    /// layer to outvote it (the Pixel's "TICKETLINE" Gig). It is the word, not the vendor:
    /// no list of vendors is kept.
    func testACapsBannerNamingTheTicketIsNeverGuessed() {
        let found = ticket(["TICKETLINE", "MORK WATER", "28-09-2026", "Parkteatret, Oslo"])

        XCTAssertEqual("MORK WATER", found.artist)
        XCTAssertEqual("Parkteatret, Oslo", found.venue)
        XCTAssertEqual("SKAMBANKT", ticket(["E-BILLETT", "SKAMBANKT", "PARKTEATRET SCENE"]).artist)
    }

    /// Only a caps line is a banner. A band that merely has the word in its name, in
    /// ordinary case, is still a name.
    func testTheTicketWordInOrdinaryCaseIsStillAName() {
        XCTAssertEqual("The Ticketmen", ticket(["The Ticketmen", "Sentrum Scene"]).artist)
    }

    // MARK: - Admissions (#441)

    /// Every Admission, in page order, whatever its symbology. (A Code 128 beside a 2D
    /// code is not one: `admission-rule-linear-beside-a-qr` in the fixtures.)
    func testEveryAdmissionIsKeptInPageOrderWhateverItsSymbology() {
        let found = admissions([
            barcode("qr", "SYNTHETIC-QR-2", page: 1),
            barcode("aztec", "SYNTHETIC-AZTEC-0001", page: 0),
            barcode("qr", "SYNTHETIC-QR-1", page: 0),
        ])

        XCTAssertEqual(["SYNTHETIC-AZTEC-0001", "SYNTHETIC-QR-1", "SYNTHETIC-QR-2"],
                       found.map { String(decoding: $0.payload, as: UTF8.self) })
        XCTAssertEqual(["aztec", "qr", "qr"], found.map(\.symbology))
        XCTAssertEqual([0, 0, 1], found.map(\.page))
    }

    /// One code on three pages is one Admission, and the first symbology it was seen in
    /// is the one kept (story 6).
    func testOneCodeOnThreePagesIsOneAdmission() {
        let found = admissions([
            barcode("qr", "SYNTHETIC-SAME", page: 0),
            barcode("aztec", "SYNTHETIC-SAME", page: 1),
            barcode("qr", "SYNTHETIC-SAME", page: 2),
        ])

        XCTAssertEqual([Admission(payload: Data("SYNTHETIC-SAME".utf8), symbology: "qr")], found)
    }

    /// Retail codes beside a real one are dropped; alone, they are kept. Provisional.
    func testRetailCodesAreDroppedBesideAnythingElseAndKeptAlone() {
        XCTAssertEqual(["qr"], admissions([barcode("ean13", "4006381333931"),
                                           barcode("qr", "SYNTHETIC-QR-1")]).map(\.symbology))
        XCTAssertEqual(["upca"], admissions([barcode("upca", "036000291452")]).map(\.symbology))
    }

    /// Corroboration is evidence, never a filter: printed or not, the Admission stays.
    func testAnAdmissionIsCorroboratedWhenTheTicketPrintsIt() {
        let found = admissions([barcode("qr", "SYNTH46G7"), barcode("qr", "SYNTH-UNPRINTED")],
                               lines: ["Order", "*SYNTH46G7*"])

        XCTAssertEqual([true, false], found.map(\.corroborated))
    }

    /// Bytes that are not UTF-8 have no printed form to find, so they are never
    /// corroborated — and still kept.
    func testAPayloadThatIsNotUtf8IsNeverCorroborated() {
        let binary = TicketBarcode(image: Data(), payload: Data([0x00, 0xFF]), symbology: "qr")
        let found = admissions([binary], lines: ["\u{0}\u{FFFD}"])

        XCTAssertEqual(1, found.count)
        XCTAssertFalse(found[0].corroborated)
    }

    /// "First QR" is gone: an Eventim ticket read in full is as complete as a QR one,
    /// and routing carries every Admission, not the first.
    func testACode128OnlyTicketCanBeCompleteAndIsRoutedWhole() {
        let evidence = TicketEvidence(
            readings: [TicketReading(origin: .ocr, lines: ["Big Thief at Sentrum Scene", "14.09.2026"])],
            barcodes: [barcode("code128", "000000000000000000000001", page: 0),
                       barcode("code128", "000000000000000000000002", page: 1)])
        guard case .ticket(let found) = parseTicketFields(evidence, calendar: calendar) else {
            return XCTFail("read nothing")
        }

        XCTAssertTrue(found.isComplete)
        XCTAssertEqual(2, found.admissions.count)
        // Unchecked is not redrawable: straight from the parse, it is asked about.
        XCTAssertEqual(.confirm(found), routeTicket(.ticket(found), knownNights: [],
                                                    now: day(2026, 8, 1), calendar: calendar))
        let drawn = found.checkedForRedraw { _, _ in true }
        XCTAssertEqual(.add(drawn), routeTicket(.ticket(drawn), knownNights: [],
                                                now: day(2026, 8, 1), calendar: calendar))
    }

    // MARK: - Redrawn at import (#441, story 29)

    /// A complete Eventim read whose Code 128 did not read back as itself is not added
    /// without asking: the prompt says which barcode can't be shown and to bring the
    /// PDF. Closes #542's open question 1.
    func testACompleteTicketWhoseBarcodeCannotBeRedrawnIsAskedAbout() {
        var eventim = complete
        eventim.admissions = [Admission(payload: Data("000000000000000000000001".utf8),
                                        symbology: "code128", redrawable: false)]

        XCTAssertTrue(eventim.canSkipPrompt, "the read itself is complete and agreed")
        XCTAssertEqual(.confirm(eventim), routeTicket(.ticket(eventim), knownNights: [],
                                                      now: day(2026, 8, 1), calendar: calendar))
    }

    /// The match path is gated too: attaching an undrawable barcode to a night already
    /// on the line, silently, is the same surprise at the door.
    func testAMatchWhoseBarcodeCannotBeRedrawnIsAskedAbout() {
        var eventim = complete
        eventim.admissions = [drawnQr, Admission(payload: Data("CODE".utf8), symbology: "datamatrix",
                                                 redrawable: false)]

        XCTAssertEqual(.confirm(eventim, possibleMatch: "g1"),
                       routeTicket(.ticket(eventim),
                                   knownNights: [night("g1", "14-09-2026", "Big Thief")],
                                   now: day(2026, 8, 1), calendar: calendar))
    }

    /// Nothing checked is nothing known: a ticket straight off the parse never skips
    /// the prompt, whichever path it would take.
    func testAnUncheckedAdmissionCountsAsNotRedrawable() {
        var unchecked = complete
        unchecked.admissions = [qrAdmission]

        XCTAssertFalse(unchecked.redrawsEveryAdmission)
        XCTAssertEqual(.confirm(unchecked), routeTicket(.ticket(unchecked), knownNights: [],
                                                        now: day(2026, 8, 1), calendar: calendar))
        XCTAssertEqual(.confirm(unchecked, possibleMatch: "g1"),
                       routeTicket(.ticket(unchecked),
                                   knownNights: [night("g1", "14-09-2026", "Big Thief")],
                                   now: day(2026, 8, 1), calendar: calendar))
    }

    /// The verdict is the app's, never the extension's: it is not deposited.
    func testTheVerdictIsNeverDeposited() throws {
        let json = String(decoding: try JSONEncoder().encode(complete), as: UTF8.self)
        XCTAssertFalse(json.contains("redrawable"), json)
    }

    /// A deposit the extension wrote before #441 carried one `qr`; it still drains, as
    /// one uncorroborated QR Admission on page 0.
    func testADepositWithTheOldQrStillReads() throws {
        let old = #"{"qr":"\#(qrBytes.base64EncodedString())","artist":"Big Thief"}"#
        let decoded = try JSONDecoder().decode(Ticket.self, from: Data(old.utf8))

        XCTAssertEqual([qrAdmission], decoded.admissions)
        XCTAssertEqual("Big Thief", decoded.artist)
        let roundTripped = try JSONDecoder().decode(Ticket.self, from: JSONEncoder().encode(complete))
        XCTAssertEqual(complete.checkedForRedraw { _, _ in true },
                       roundTripped.checkedForRedraw { _, _ in true })
    }

    /// A line carrying the date is not a line carrying an artist, whatever else is on
    /// it — "Sat 14 Sep at Sentrum Scene" must not name an act called "Sat 14 Sep".
    func testADateLineNeverYieldsAnArtist() {
        let found = ticket(qr: qrBytes, ["Sat 14 Sep 2026 at Sentrum Scene"])

        XCTAssertNil(found.artist)
        XCTAssertEqual(day(2026, 9, 14), found.date)
    }

    /// An order line has no letters to speak of and is not a name.
    func testAReferenceNumberIsNotAName() {
        let found = ticket(qr: qrBytes, ["Artist: #4471193", "Venue: 12"])

        XCTAssertNil(found.artist)
        XCTAssertNil(found.venue)
    }

    /// A vendor's terms paragraph is not a venue, however it was labelled.
    func testASentenceIsTooLongToBeAName() {
        let terms = String(repeating: "no refunds or exchanges ", count: 6)
        XCTAssertNil(ticket(qr: qrBytes, ["Venue: \(terms)"]).venue)
    }

    /// A label that appears twice keeps the first answer, matching the date rule: one
    /// pass down the page, first match wins, on both platforms.
    func testTheFirstLabelledValueWins() {
        let found = ticket(["Artist: Big Thief", "Artist: Support Act"])
        XCTAssertEqual("Big Thief", found.artist)
    }

    // MARK: - Support (#526)

    /// Complete, but the text layer never backed the artist: asked, not minted. This is
    /// the shape a vendor logo read as an artist takes.
    func testACompleteParseOneReadingAloneBackedIsConfirmed() {
        var guessed = complete
        guessed.readingCount = 2
        guessed.artistSupport = .ocr
        guessed.venueSupport = .both
        guessed.dateSupport = .both

        let route = routeTicket(.ticket(guessed), knownNights: [],
                                now: day(2026, 8, 1), calendar: calendar)

        XCTAssertEqual(.confirm(guessed), route)
    }

    /// Both readings agreed on every field: nothing left to ask.
    func testACompleteParseBothReadingsBackedIsAdded() {
        var agreed = complete
        agreed.readingCount = 2
        agreed.artistSupport = .both
        agreed.venueSupport = .both
        agreed.dateSupport = .both

        let route = routeTicket(.ticket(agreed), knownNights: [],
                                now: day(2026, 8, 1), calendar: calendar)

        XCTAssertEqual(.add(agreed), route)
    }

    /// A scan only ever has one reading. Holding it to agreement it can never reach
    /// would make every scanned ticket a prompt.
    func testACompleteScanIsAddedOnItsOneReading() {
        var scan = complete
        scan.readingCount = 1
        scan.artistSupport = .ocr
        scan.venueSupport = .ocr
        scan.dateSupport = .ocr

        XCTAssertTrue(scan.canSkipPrompt)
        XCTAssertEqual(.add(scan), routeTicket(.ticket(scan), knownNights: [],
                                               now: day(2026, 8, 1), calendar: calendar))
    }

    // MARK: - The drop box

    /// A box of its own in the temporary directory: the App Group container is missing
    /// on CI, and these are about the files, not the entitlement.
    private func scratchBox() throws -> URL {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("ticket-inbox-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: dir) }
        return dir
    }

    /// The extension writes and the app reads, then deletes each deposit once what it
    /// became is on disk (the #441 review). Read but not yet removed, it is still there
    /// for a launch that follows a kill mid-routing; removed, it is gone for good.
    func testADepositStaysInTheBoxUntilItIsRemoved() throws {
        let box = try scratchBox()
        XCTAssertTrue(TicketInbox.deposit(complete, in: box))

        let first = TicketInbox.pending(in: box)
        XCTAssertEqual([complete], first.map(\.ticket))
        XCTAssertEqual(first, TicketInbox.pending(in: box), "reading does not take it out")

        TicketInbox.remove(first[0].id, in: box)
        XCTAssertTrue(TicketInbox.pending(in: box).isEmpty)
    }

    /// One bad file must not wedge every later ticket behind it.
    func testADepositThatWillNotDecodeIsDeletedWhenRead() throws {
        let box = try scratchBox()
        let bad = box.appendingPathComponent("garbage.json")
        try Data("{ not json".utf8).write(to: bad)
        XCTAssertTrue(TicketInbox.deposit(complete, in: box))

        XCTAssertEqual([complete], TicketInbox.pending(in: box).map(\.ticket))
        XCTAssertFalse(FileManager.default.fileExists(atPath: bad.path))
    }

    /// Oldest first, whatever order the directory lists them in.
    func testDepositsAreReadInTheOrderTheyWereShared() throws {
        let box = try scratchBox()
        for (at, artist) in [(3, "C"), (1, "A"), (2, "B")] {
            let deposit = TicketDeposit(depositedAt: Int64(at), ticket: Ticket(artist: artist))
            try JSONEncoder().encode(deposit).write(to: box.appendingPathComponent("\(deposit.id).json"))
        }

        XCTAssertEqual(["A", "B", "C"], TicketInbox.pending(in: box).map(\.ticket.artist))
    }

    // A sideloader renames the App Group the way it renames the bundle id, so the
    // declared group is not the one a sideloaded install holds.

    func testASignedBuildTriesOnlyTheDeclaredGroup() {
        XCTAssertEqual([TicketInbox.appGroup], TicketInbox.appGroupCandidates(
            bundleIdentifier: "io.github.magnusencoded.stationtostation", isExtension: false))
        XCTAssertEqual([TicketInbox.appGroup], TicketInbox.appGroupCandidates(
            bundleIdentifier: "io.github.magnusencoded.stationtostation.ticketshare", isExtension: true))
    }

    func testASideloadedAppAndItsExtensionDeriveTheSameRenamedGroup() {
        let renamed = "group.io.github.magnusencoded.stationtostation.VHZW7G33CV"
        XCTAssertEqual([TicketInbox.appGroup, renamed], TicketInbox.appGroupCandidates(
            bundleIdentifier: "io.github.magnusencoded.stationtostation.VHZW7G33CV", isExtension: false))
        XCTAssertEqual([TicketInbox.appGroup, renamed], TicketInbox.appGroupCandidates(
            bundleIdentifier: "io.github.magnusencoded.stationtostation.VHZW7G33CV.ticketshare", isExtension: true))
    }
}
