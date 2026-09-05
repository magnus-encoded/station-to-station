import XCTest
@testable import StationToStation

/// Reading a PDF ticket (#412, #408).
///
/// Fed pre-extracted QR bytes and OCR text blocks directly, never a real PDF — the
/// seam is deliberately above PDFKit and Vision so these assertions say something
/// about the rules rather than about a device's renderer. The Android twin (#411) is
/// held to the same cases on the same inputs.
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

    private func parse(qr: Data? = nil, _ blocks: [String]) -> TicketParse {
        parseTicket(qr: qr, blocks: blocks, calendar: calendar)
    }

    private func ticket(qr: Data? = nil, _ blocks: [String]) -> Ticket {
        guard case .ticket(let found) = parse(qr: qr, blocks) else {
            XCTFail("expected a ticket from \(blocks)")
            return Ticket()
        }
        return found
    }

    private let qrBytes = Data("TKT-9F31-0042".utf8)

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
        XCTAssertEqual(qrBytes, found.qr)
        XCTAssertTrue(found.isComplete)
    }

    /// A QR read perfectly off a ticket whose text is a scan, or a language, or a
    /// layout this parser cannot make sense of. The QR is kept — losing the one thing
    /// that read cleanly because the rest did not is the failure story 10 names.
    func testAQrWithNoUsableTextStillKeepsTheQr() {
        let found = ticket(qr: qrBytes, ["", "#4471193", "NOK 690,00", "|||| |||| ||"])

        XCTAssertEqual(qrBytes, found.qr)
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
        XCTAssertNil(found.qr)
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
        Ticket(qr: qrBytes, artist: "Big Thief", venue: "Sentrum Scene",
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
        let ticket = Ticket(qr: qrBytes, artist: "Wilco (US)", venue: "Sentrum Scene",
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
        partial.qr = nil

        let route = routeTicket(.ticket(partial), knownNights: [],
                                now: day(2026, 8, 1), calendar: calendar)

        XCTAssertEqual(.confirm(partial), route)
    }

    /// Even one that matches. What matched was a partial parse, and a partial parse is
    /// exactly what the person is there to correct.
    func testAPartialParseThatMatchesIsStillConfirmed() {
        var partial = complete
        partial.venue = nil

        let route = routeTicket(.ticket(partial),
                                knownNights: [night("g1", "14-09-2026", "Big Thief")],
                                now: day(2026, 8, 1), calendar: calendar)

        XCTAssertEqual(.confirm(partial), route)
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
    /// ticket, and it is the one rule that could hand a *complete-looking* parse a
    /// wrong artist — which is the parse that skips the prompt.
    func testADashIsNotASeparator() {
        let found = ticket(qr: qrBytes, ["Big Thief — Sentrum Scene"])

        XCTAssertNil(found.artist)
        XCTAssertNil(found.venue)
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

    // MARK: - The drop box

    /// The extension writes and the app reads-and-deletes; a drained box is empty, so
    /// the same ticket can never be routed onto the **Line** twice.
    func testDrainingTheInboxEmptiesIt() throws {
        try XCTSkipIf(TicketInbox.directory == nil,
                      "no App Group container — see ADR-0019 and the PR's signing note")

        XCTAssertTrue(TicketInbox.deposit(complete))
        let first = TicketInbox.drain()
        let second = TicketInbox.drain()

        XCTAssertEqual([complete], first.map(\.ticket))
        XCTAssertTrue(second.isEmpty)
    }
}
