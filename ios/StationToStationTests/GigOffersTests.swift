import XCTest
@testable import StationToStation

/// The table in #129, executable, ported from Android's `GigOffersTest` (#177). Each
/// test asserts what a **Gig** offers — never how it decided — so the derived axes and
/// the phase stay free to change.
///
/// A fixed UTC calendar throughout, `GigTimeStateTests`'s convention: the Android suite
/// gets that for free from `LocalDateTime` and here it has to be said, or the 06:00 edge
/// moves with the machine.
final class GigOffersTests: XCTestCase {

    private let cal: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(secondsFromGMT: 0)!
        return c
    }()

    /// `dd-MM-yyyy` at an hour, the shape a **Gig** carries.
    private func at(_ ymd: String, _ hour: Int, _ minute: Int = 0) -> Date {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "dd-MM-yyyy"
        f.timeZone = cal.timeZone
        return cal.date(bySettingHour: hour, minute: minute, second: 0, of: f.date(from: ymd)!)!
    }

    private let today = "11-08-2026"
    private let inThreeWeeks = "01-09-2026"
    private let threeDaysAgo = "08-08-2026"
    private let fourDaysAgo = "07-08-2026"
    private let tomorrow = "12-08-2026"
    private var now: Date { at("11-08-2026", 21, 30) }

    // One line per row of the table. Test-local on purpose: it buys the readability a
    // named axes type would have bought, without spending domain vocabulary on it.
    private func night(_ date: String? = nil, _ provenance: String? = nil,
                       _ log: StoredLog? = nil, _ setlistId: String? = nil,
                       _ songs: Int = 0, calendarEvent: String? = nil) -> GigAsKnown {
        GigAsKnown(
            window: nightWindow(gigDate: date ?? today, calendar: cal),
            provenance: provenance,
            log: log,
            setlistId: setlistId,
            songCount: songs,
            calendarEvent: calendarEvent
        )
    }

    /// The same night, with a ticket parsed for it. Synthetic bytes: #412 is what puts
    /// real ones in the store, and nothing here depends on what a ticket actually says.
    private func ticketed(_ date: String? = nil, _ provenance: String? = nil,
                          _ setlistId: String? = nil, _ songs: Int = 0) -> GigAsKnown {
        var gig = night(date, provenance, nil, setlistId, songs)
        gig.admissionCount = 1
        return gig
    }

    private let ticketBytes = Data("STS-TICKET-0001".utf8)

    private let openLog = StoredLog(songs: ["Hollowmoor", ""], closed: false)
    private let closedLog = StoredLog(songs: ["Hollowmoor", "", "Vardhavn"], closed: true)
    private let checkedIn = "checked_in"
    private let attended = "attended"
    private let planned = "planned"

    private func offers(_ gig: GigAsKnown) -> GigOffers { gigOffers(gig, now: now) }

    // --- The table, row by row ------------------------------------------------

    func testANightThreeWeeksOutOffersTheCalendarAndAsksWhetherTheEventMoved() {
        let o = offers(night(inThreeWeeks, planned))
        XCTAssertEqual(.addToCalendar, o.alcove)
        XCTAssertEqual(.checkEvent, o.curtain)
        XCTAssertEqual(.plan, o.phase)
    }

    func testACalendarEntryAlreadyMadeIsTheThingToOpen() {
        let o = offers(night(inThreeWeeks, planned, calendarEvent: "x-apple-calevent://7"))
        XCTAssertEqual(.openCalendar, o.alcove)
    }

    func testNothingIsOfferedToRecordBeforeTheSet() {
        XCTAssertFalse(offers(night(inThreeWeeks, planned)).room.capture)
    }

    func testOnTheNightBeforeCheckingInTheAlcoveIsEmptyAndTheWindowIsTheEvent() {
        let o = offers(night(today, planned))
        XCTAssertEqual(.empty, o.alcove)
        XCTAssertEqual(.checkEvent, o.curtain)
        XCTAssertTrue(o.room.checkIn)
    }

    func testCheckedInWithNothingPostedPullsTheArtistsCatalogue() {
        let o = offers(night(today, checkedIn, openLog))
        XCTAssertEqual(.empty, o.alcove)
        XCTAssertEqual(.catalogue, o.curtain)
        XCTAssertTrue(o.room.capture)
    }

    func testCheckedInWithARecordLinkedPullsThatSetlist() {
        XCTAssertEqual(.fetchSetlist, offers(night(today, checkedIn, openLog, "s1")).curtain)
    }

    func testAClosedLogNobodyHasPostedOffersSetlistFm() {
        let o = offers(night(threeDaysAgo, checkedIn, closedLog))
        XCTAssertEqual(.setlistFm, o.alcove)
        XCTAssertEqual(.fetchSetlist, o.curtain)
    }

    func testARecordLinkedButEmptyIsTheSameUnfinishedThingWearingAnId() {
        let o = offers(night(threeDaysAgo, checkedIn, closedLog, "s1", 0))
        XCTAssertEqual(.setlistFm, o.alcove)
        XCTAssertEqual(.fetchSetlist, o.curtain)
    }

    func testOnceTheNightIsRecordedThePlaylistIsWhatRemains() {
        let o = offers(night(threeDaysAgo, checkedIn, closedLog, "s1", 15))
        XCTAssertEqual(.spotify, o.alcove)
        XCTAssertEqual(.checkEdits, o.curtain)
    }

    func testAPastNightWithAnOpenLogAndNoRecordStillPullsTheCatalogue() {
        let o = offers(night(threeDaysAgo, checkedIn, openLog))
        XCTAssertEqual(.empty, o.alcove)
        XCTAssertEqual(.catalogue, o.curtain)
    }

    // --- The three records that defeated a linear model -----------------------

    func testValkyrienCheckedInLogClosedRecordWithFifteenSongs() {
        let o = offers(night(fourDaysAgo, checkedIn, closedLog, "637062c7", 15))
        XCTAssertEqual(.spotify, o.alcove)
        XCTAssertEqual(.checkEdits, o.curtain)
    }

    func testOyvindHolmCheckedInLogClosedRecordLinkedAndEmpty() {
        // Before or after "recorded to setlist.fm"? Both answers are wrong, which is
        // why this is a fold and not a sequence: the record is linked and holds nothing,
        // so the unfinished thing is still posting it.
        let o = offers(night(fourDaysAgo, checkedIn, closedLog, "53705b8d", 0))
        XCTAssertEqual(.setlistFm, o.alcove)
        XCTAssertEqual(.fetchSetlist, o.curtain)
    }

    func testNirvana1992AttendedNoLogNoCheckInRecordWithSongs() {
        let o = offers(night("28-06-1992", attended, nil, "old-one", 12))
        // An imported night is not unfinished. It leads somewhere.
        XCTAssertEqual(.spotify, o.alcove)
        XCTAssertEqual(.checkEdits, o.curtain)
        XCTAssertFalse(o.room.checkIn)
        XCTAssertTrue(o.room.capture)
    }

    // --- The rules that are easy to break ------------------------------------

    func testReopeningAClosedLogTakesTheOffersBackWithIt() {
        let gig = night(threeDaysAgo, checkedIn, closedLog, "s1", 15)
        XCTAssertEqual(.spotify, offers(gig).alcove)
        // Remembering a song three years later must cost nothing.
        var reopened = gig
        reopened.log = StoredLog(songs: closedLog.songs, closed: false)
        XCTAssertEqual(.empty, offers(reopened).alcove)
        XCTAssertEqual(.checkEdits, offers(reopened).curtain)
    }

    func testCheckingInBeforeTheListedStartOpensCaptureAnyway() {
        // Standing there is the strongest evidence the thing has begun.
        let o = offers(night(tomorrow, checkedIn))
        XCTAssertTrue(o.room.capture)
        XCTAssertEqual(.capture, o.phase)
    }

    func testANightWhoseVenueNeverGeocodedCanStillBeCheckedInto() {
        // The decision takes no location, so there is nothing here that a missing fix
        // could gate.
        XCTAssertTrue(offers(night(today, planned)).room.checkIn)
    }

    func testAnUndatedNightKeepsThePlanAheadOffers() {
        let o = offers(GigAsKnown(window: nil, provenance: planned))
        XCTAssertEqual(.addToCalendar, o.alcove)
        XCTAssertEqual(.plan, o.phase)
        XCTAssertFalse(o.room.checkIn)
    }

    func testGapsGateNothing() {
        // A closed Log that is mostly "one I couldn't name" is still a set.
        let mostlyGaps = StoredLog(songs: ["", "", "Vardhavn", ""], closed: true)
        XCTAssertEqual(.setlistFm, offers(night(threeDaysAgo, checkedIn, mostlyGaps)).alcove)
    }

    // --- The ticket (#414) ----------------------------------------------------

    func testANightWithNoTicketHoldsNothingUp() {
        XCTAssertFalse(offers(night(today, planned)).room.showTicket)
    }

    func testATicketIsHeldUpUntilTheCheckIn() {
        XCTAssertTrue(offers(ticketed(today, planned)).room.showTicket)
    }

    func testCheckingInRetiresTheTicket() {
        // The swap the Room renders: one branch, never both at once.
        let o = offers(ticketed(today, checkedIn))
        XCTAssertFalse(o.room.showTicket)
        XCTAssertFalse(o.room.checkIn)
    }

    func testATicketIsHeldUpBeforeTheNightsWindowOpens() {
        // The door opens before the set does. Unlike the check-in beside it, this is
        // not gated on the window — a QR withheld until the music starts is a QR you
        // cannot get in with.
        let o = offers(ticketed(inThreeWeeks, planned))
        XCTAssertTrue(o.room.showTicket)
        XCTAssertFalse(o.room.checkIn)
    }

    func testATicketDecidesNothingButItself() {
        // It hangs off a night the way media does. Holding one must not move the
        // Alcove, the Curtain or the phase.
        let plain = offers(night(threeDaysAgo, attended, nil, "s1", 15))
        let withTicket = offers(ticketed(threeDaysAgo, attended, "s1", 15))
        XCTAssertEqual(plain.alcove, withTicket.alcove)
        XCTAssertEqual(plain.curtain, withTicket.curtain)
        XCTAssertEqual(plain.phase, withTicket.phase)
    }

    func testAnEmptyPayloadIsNotAQRToDraw() {
        // A barcode that scans as nothing is worse at a door than no barcode, so the
        // Exchange's generator — widened to bytes for this — declines it.
        XCTAssertNil(qrImage(Data()))
        XCTAssertNotNil(qrImage(ticketBytes, correction: "H"))
    }

    func testATicketsBytesSurviveNotBeingText() {
        // The reason the generator takes Data: a venue's barcode is whatever it
        // encoded, and rounding it through a String would mangle this.
        XCTAssertNotNil(qrImage(Data([0x00, 0xFF, 0xFE, 0x80, 0x01])))
    }

    /// The seam to the store, asserted rather than assumed: what the fold carries is how
    /// many Admissions the stored claim holds (#441) — the same fact under the same name
    /// as Android's twin — and the bytes are only produced at the render edge.
    func testTheFoldCarriesTheStoredCountAndDecodesOnlyToDraw() {
        let claim = StoredAttendance(provenance: planned, admissions: [
            StoredAdmission(payload: ticketBytes.base64EncodedString(), symbology: "qr"),
        ])
        var gig = night(today, claim.provenance)
        gig.admissionCount = claim.admissions.count

        XCTAssertTrue(offers(gig).room.showTicket)
        XCTAssertEqual(ticketBytes, claim.admissions.first?.payloadBytes)
    }

    /// Presence decides, content does not. A stored payload that will not decode — or a
    /// Code 128 the app cannot redraw yet — still says "this night had a ticket"; what
    /// it cannot do is draw as a QR, and the render edge is where that is caught.
    func testTheFoldAsksOnlyWhetherThereIsATicketAtAll() {
        var gig = night(today, planned)
        gig.admissionCount = 1

        XCTAssertTrue(offers(gig).room.showTicket)
        XCTAssertNil(StoredAdmission(payload: "not base64 at all!", symbology: "qr").payloadBytes)
    }

    /// Two people on one PDF, or a second ticket for the night (#441): still one ticket
    /// held up, and the check-in retires every Admission at once.
    func testSeveralAdmissionsAreOneTicketToShowUntilTheCheckIn() {
        var gig = night(today, planned)
        gig.admissionCount = 3
        XCTAssertTrue(offers(gig).room.showTicket)

        gig.provenance = checkedIn
        XCTAssertFalse(offers(gig).room.showTicket)
    }

    // --- The lattice ----------------------------------------------------------

    /// Exhaustive rather than random: the state space is small enough to walk, and a
    /// fixed walk cannot pass by not generating the awkward case.
    ///
    /// Time advances, evidence never downgrades, and a record only gains songs. Under
    /// every one of those the phase must not move backwards and the calendar must not
    /// come back — a future axis introducing a cycle is exactly what a table of examples
    /// would miss.
    func testNoForwardTransitionMovesTheOffersBackwards() {
        let dates = [inThreeWeeks, today, threeDaysAgo]
        let claims: [String?] = [nil, planned, attended, checkedIn]
        let logs: [StoredLog?] = [nil, openLog, closedLog]
        let records: [(String?, Int)] = [(nil, 0), ("s1", 0), ("s1", 15)]
        let rank: [GigLeaf: Int] = [.plan: 0, .capture: 1, .publish: 2]
        let calendarish: (Alcove) -> Bool = { $0 == .addToCalendar || $0 == .openCalendar }

        for di in dates.indices {
            for ci in claims.indices {
                for log in logs {
                    for ri in records.indices {
                        let here = offers(night(dates[di], claims[ci], log, records[ri].0, records[ri].1))
                        // Every legal step forward on each axis except the Log, which is free.
                        var forward: [GigAsKnown] = []
                        if di + 1 < dates.count {
                            forward.append(night(dates[di + 1], claims[ci], log, records[ri].0, records[ri].1))
                        }
                        if ci + 1 < claims.count {
                            forward.append(night(dates[di], claims[ci + 1], log, records[ri].0, records[ri].1))
                        }
                        if ri + 1 < records.count {
                            forward.append(night(dates[di], claims[ci], log, records[ri + 1].0, records[ri + 1].1))
                        }
                        for next in forward {
                            let then = offers(next)
                            XCTAssertGreaterThanOrEqual(
                                rank[then.phase]!, rank[here.phase]!,
                                "phase went backwards: \(here) -> \(then)"
                            )
                            if !calendarish(here.alcove) {
                                XCTAssertFalse(
                                    calendarish(then.alcove),
                                    "the calendar came back: \(here) -> \(then)"
                                )
                            }
                            XCTAssertTrue(
                                !here.room.capture || then.room.capture,
                                "capture was taken away: \(here) -> \(then)"
                            )
                        }
                    }
                }
            }
        }
    }

    // --- curtainAction: what pulling the Curtain down actually asks for (#129) -----

    func testCatalogueDispatchesToFetchingTheCatalogue() {
        XCTAssertEqual(.fetchCatalogue, curtainAction(.catalogue))
    }

    func testFetchSetlistDispatchesToRefreshingTheSetlist() {
        XCTAssertEqual(.fetchSetlist, curtainAction(.fetchSetlist))
    }

    func testCheckEditsAlsoDispatchesToRefreshingTheSetlistSameCallAsFetchSetlist() {
        // A linked record with songs already on it and one still being typed both
        // resolve to "ask setlist.fm again" — the difference is only in what the fold
        // expects to learn, not in which endpoint answers.
        XCTAssertEqual(.fetchSetlist, curtainAction(.checkEdits))
    }

    func testCheckEventDispatchesToNothingNoEventMovedEndpointExists() {
        XCTAssertEqual(.nothing, curtainAction(.checkEvent))
    }

    // MARK: - Stepping between Admissions (#441, story 5)

    func testThreeAdmissionsAreStepped1Of3To3Of3AndStopAtEachEnd() {
        let first = AdmissionPage(index: 0, count: 3)
        XCTAssertEqual("1 of 3", first.label)
        XCTAssertFalse(first.hasPrevious)
        XCTAssertEqual(first, first.previous())

        let last = first.next().next()
        XCTAssertEqual("3 of 3", last.label)
        XCTAssertFalse(last.hasNext)
        // No wrapping round to the first: that is the same barcode shown twice.
        XCTAssertEqual(last, last.next())
        XCTAssertEqual("2 of 3", last.previous().label)
    }

    func testOneAdmissionHasNothingToStepTo() {
        let only = AdmissionPage(index: 0, count: 1)
        XCTAssertNil(only.label)
        XCTAssertFalse(only.hasNext)
        XCTAssertFalse(only.hasPrevious)
    }

    func testAPageIsHeldInsideTheAdmissionsThereAre() {
        XCTAssertEqual(1, AdmissionPage(index: 5, count: 2).index)
        XCTAssertEqual(0, AdmissionPage(index: -1, count: 2).index)
        XCTAssertEqual(0, AdmissionPage(index: 3, count: 0).index)
    }

    /// Stepping from 1 of 2 to 2 of 2 while the first is still shown: the new page is
    /// checking, never the previous page's drawing under the new label.
    func testAVerdictIsOnlyReadBackForTheAdmissionItWasReachedFor() {
        let first = StoredAdmission(payload: "QQ==", symbology: "qr")
        let second = StoredAdmission(payload: "Qg==", symbology: "code128", page: 1)
        let reached = DoorVerdict(admission: first, verdict: "drawing of the first")

        XCTAssertEqual("drawing of the first", reached.forAdmission(first))
        XCTAssertNil(reached.forAdmission(second))
    }
}
