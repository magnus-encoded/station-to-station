import XCTest
@testable import StationToStation

/// #531's lookup flow, decided without the network: what one lookup comes to, what the
/// loop looks up next, what a shared ticket becomes, the question a chip row asks, and
/// the stored state all of that leaves behind.
///
/// `fixtures/setlistfm-outcome/`, `fixtures/setlistfm-lookup/plan.json` and
/// `fixtures/setlistfm-question/cases.json` are shared with Android's
/// `SetlistFmLookupFlowTest`, which asserts them case for case; so is the ticketImport
/// table's every case name. Everything here is synthetic.
final class SetlistFmLookupFlowTests: XCTestCase {

    private var files: [URL] = []

    override func tearDown() {
        files.forEach { try? FileManager.default.removeItem(at: $0) }
        files = []
        super.tearDown()
    }

    private func tempFile() -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("timelines-\(UUID().uuidString).json")
        files.append(url)
        return url
    }

    /// The repo root, found from this file rather than a bundle: the fixtures are
    /// deliberately not iOS resources.
    private func fixture(_ path: String) -> URL {
        URL(fileURLWithPath: #filePath)             // …/ios/StationToStationTests/SetlistFmLookupFlowTests.swift
            .deletingLastPathComponent()            // …/ios/StationToStationTests
            .deletingLastPathComponent()            // …/ios
            .deletingLastPathComponent()            // repo root
            .appendingPathComponent("fixtures/\(path)")
    }

    /// UTC, the zone `parseFmDate` reads in and the fixtures are written in.
    private let calendar: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "UTC")!
        return c
    }()

    private func instant(_ iso: String) -> Date {
        ISO8601DateFormatter().date(from: iso)!
    }

    // MARK: - fixtures/setlistfm-outcome

    private struct TicketIn: Decodable {
        var artist: String?
        var venue: String?
        var date: String?
    }

    private struct Before: Decodable {
        var now: Int64
        var lookup: StoredSetlistFmLookup?
    }

    private struct Expected: Decodable {
        var outcome: String
        var adopt: String?
        var ask: [String]?
        var after: StoredSetlistFmLookup
    }

    func testEveryOutcomeCaseComesToItsAnswerAndItsStoredState() throws {
        let dir = fixture("setlistfm-outcome")
        let cases = try FileManager.default
            .contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)
            .filter { $0.hasDirectoryPath }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
        XCTAssertFalse(cases.isEmpty, "fixtures/setlistfm-outcome is empty")

        let decoder = JSONDecoder()
        for caseDir in cases {
            let name = caseDir.lastPathComponent
            let input = try decoder.decode(
                TicketIn.self, from: Data(contentsOf: caseDir.appendingPathComponent("ticket.json")))
            let hits = try decoder.decode(
                SetlistsResponse.self, from: Data(contentsOf: caseDir.appendingPathComponent("search-setlists.json")))
            let before = try decoder.decode(
                Before.self, from: Data(contentsOf: caseDir.appendingPathComponent("before.json")))
            let expected = try decoder.decode(
                Expected.self, from: Data(contentsOf: caseDir.appendingPathComponent("expected.json")))
            let ticket = Ticket(artist: input.artist, venue: input.venue, date: input.date.flatMap(parseFmDate))

            let outcome = setlistFmLookupOutcome(ticket, hits: hits.setlist, lineArtists: [],
                                                 lookup: before.lookup, nowMillis: before.now,
                                                 calendar: calendar)
            switch outcome {
            case .adopt(let hit, _):
                XCTAssertEqual(expected.outcome, "adopt", "\(name): outcome")
                XCTAssertEqual(hit.id, expected.adopt, "\(name): adopted id")
            case .ask(let candidates, _):
                XCTAssertEqual(expected.outcome, "ask", "\(name): outcome")
                XCTAssertEqual(candidates.map { $0.setlist.id }, expected.ask ?? [], "\(name): candidates, best first")
            case .nothing:
                XCTAssertEqual(expected.outcome, "nothing", "\(name): outcome")
            }
            XCTAssertEqual(outcome.next, expected.after, "\(name): stored lookup after")
        }
    }

    // MARK: - fixtures/setlistfm-lookup/plan.json

    private struct PlanGig: Decodable {
        var id: String
        var date: String
        var local: Bool
        var lookup: StoredSetlistFmLookup?
        var participationUntil: String?
    }

    private struct PlanCase: Decodable {
        var name: String
        var now: String
        var sharedKey: Bool?
        var sharedQuotaSpentAt: String?
        var gigs: [PlanGig]
        var dueNow: [String]
        var nextWakeAt: String?
    }

    private struct PlanFile: Decodable {
        var cases: [PlanCase]
    }

    func testEveryPlanCaseLooksUpTheRightGigsInOrderAndWakesWhenItShould() throws {
        let file = try JSONDecoder().decode(
            PlanFile.self, from: Data(contentsOf: fixture("setlistfm-lookup/plan.json")))
        XCTAssertFalse(file.cases.isEmpty, "plan.json has no cases")

        for c in file.cases {
            let gigs = c.gigs.map { g in
                LookupGig(id: g.id, date: g.date, local: g.local, lookup: g.lookup,
                          participationUntil: g.participationUntil.map { instant($0) })
            }
            let plan = setlistFmLookupPlan(
                gigs, now: instant(c.now), calendar: calendar,
                sharedKey: c.sharedKey ?? true,
                sharedQuotaSpentAt: c.sharedQuotaSpentAt.map { instant($0).timeIntervalSince1970 })
            XCTAssertEqual(plan.dueNow, c.dueNow, "\(c.name): due now")
            XCTAssertEqual(plan.nextWakeAt, c.nextWakeAt.map { instant($0) }, "\(c.name): next wake")
        }
    }

    // MARK: - fixtures/setlistfm-question/cases.json

    private struct QuestionCase: Decodable {
        var name: String
        var yourVenue: String?
        var fromTicket: Bool
        var hit: StoredSetlistFmHit
        var expect: String?
    }

    private struct QuestionFile: Decodable {
        var cases: [QuestionCase]
    }

    func testEveryQuestionCaseAsksExactlyItsQuestionOrNone() throws {
        let file = try JSONDecoder().decode(
            QuestionFile.self, from: Data(contentsOf: fixture("setlistfm-question/cases.json")))
        XCTAssertFalse(file.cases.isEmpty, "cases.json has no cases")

        for c in file.cases {
            XCTAssertEqual(setlistFmQuestion(yourVenue: c.yourVenue, fromTicket: c.fromTicket, hit: c.hit),
                           c.expect, c.name)
        }
    }

    func testACandidateAsksWhatItsStoredHitWould() {
        let candidate = SetlistFmCandidate(setlist: hit("t1000002", venue: "Driftshallen"),
                                           artist: .strong, date: .strong, venue: .weak)
        XCTAssertEqual(
            setlistFmQuestion(yourVenue: "Kjøkkenhagen Scene", fromTicket: true, candidate: candidate),
            "Your ticket says Kjøkkenhagen Scene. setlist.fm lists it at Driftshallen. Same gig?")
        XCTAssertEqual(
            StoredSetlistFmHit(candidate),
            StoredSetlistFmHit(id: "t1000002", artist: "Ferrous Owls", venue: "Driftshallen",
                               city: "Tromsø", date: "12-03-2027", venueLevel: "weak"))
    }

    // MARK: - ticketImport: the #531 table
    // Case names and summaries are word for word with Android's `SetlistFmLookupFlowTest`.

    private func hit(_ id: String, venue: String = "Kjøkkenhagen Scene") -> FmSetlist {
        FmSetlist(id: id, eventDate: "12-03-2027", artist: FmArtist(name: "Ferrous Owls"),
                  venue: FmVenue(name: venue, city: FmCity(name: "Tromsø")),
                  url: "https://www.setlist.fm/setlist/ferrous-owls/2027/\(id).html")
    }

    private var sure: SetlistFmCandidate {
        SetlistFmCandidate(setlist: hit("t1000001"), artist: .strong, date: .strong, venue: .strong)
    }

    private var question: [SetlistFmCandidate] {
        [SetlistFmCandidate(setlist: hit("t1000002", venue: "Driftshallen"), artist: .strong, date: .strong, venue: .weak),
         SetlistFmCandidate(setlist: hit("t1000003", venue: "Kaikanten"), artist: .strong, date: .weak, venue: .noMatch)]
    }

    private var newNight: TicketRoute {
        .add(Ticket(artist: "Ferrous Owls", venue: "Kjøkkenhagen Scene", date: parseFmDate("12-03-2027")))
    }

    private var unsure: TicketRoute { .confirm(Ticket(artist: "Ferrous Owls")) }

    private struct ImportCase {
        let name: String
        let route: TicketRoute
        let match: SetlistFmMatch?
        let knownIds: Set<String>
        let expect: String
    }

    /// `g-local` is a local Gig on the Line, `t9000001` a setlist.fm one.
    private let localIds: Set<String> = ["g-local"]

    private var importCases: [ImportCase] {
        [ImportCase(name: "known local Gig: attach, then look it up", route: .match("g-local"), match: nil,
                    knownIds: ["t9000001"], expect: "attachThenLookUp g-local"),
         ImportCase(name: "known setlist.fm Gig: attach", route: .match("t9000001"), match: nil,
                    knownIds: ["t9000001"], expect: "attach t9000001"),
         ImportCase(name: "new night, sure hit already on the Line: attach to it", route: newNight, match: .linked(sure),
                    knownIds: ["t9000001", "t1000001"], expect: "attach t1000001"),
         ImportCase(name: "new night, sure hit: mint from setlist.fm", route: newNight, match: .linked(sure),
                    knownIds: ["t9000001"], expect: "mintFromSetlistFm t1000001"),
         ImportCase(name: "new night, a question: prompt", route: newNight, match: .ask(question),
                    knownIds: ["t9000001"], expect: "prompt [t1000002,t1000003] preselected none"),
         ImportCase(name: "new night, no match: mint local", route: newNight, match: .noMatch,
                    knownIds: ["t9000001"], expect: "mintLocal"),
         ImportCase(name: "new night, not looked up: mint local", route: newNight, match: nil,
                    knownIds: ["t9000001"], expect: "mintLocal"),
         ImportCase(name: "unsure parse, sure hit: prompt with it ticked", route: unsure, match: .linked(sure),
                    knownIds: ["t9000001"], expect: "prompt [t1000001] preselected t1000001"),
         ImportCase(name: "unsure parse, a question: prompt", route: unsure, match: .ask(question),
                    knownIds: ["t9000001"], expect: "prompt [t1000002,t1000003] preselected none"),
         ImportCase(name: "unsure parse, no match: prompt with none", route: unsure, match: .noMatch,
                    knownIds: ["t9000001"], expect: "prompt [] preselected none"),
         ImportCase(name: "unsure parse, not looked up: prompt with none", route: unsure, match: nil,
                    knownIds: ["t9000001"], expect: "prompt [] preselected none")]
    }

    /// One line per result, spelt the same on Android, so the two tables compare by eye.
    private func summary(_ result: TicketImport) -> String {
        switch result {
        case .attach(let gigId): return "attach \(gigId)"
        case .attachThenLookUp(let gigId): return "attachThenLookUp \(gigId)"
        case .mintFromSetlistFm(let hit): return "mintFromSetlistFm \(hit.id)"
        case .mintLocal: return "mintLocal"
        case .prompt(let candidates, let preselectedId):
            let ids = candidates.map { $0.setlist.id }.joined(separator: ",")
            return "prompt [\(ids)] preselected \(preselectedId ?? "none")"
        }
    }

    func testEveryRoutingAndLookupComesToTheImportTheTableSays() {
        for c in importCases {
            XCTAssertEqual(summary(ticketImport(c.route, match: c.match, knownIds: c.knownIds, localIds: localIds)),
                           c.expect, c.name)
        }
    }

    /// iOS only: Android has no unreadable route, its empty parse is `NeedsConfirmation`.
    func testAnUnreadableTicketPromptsLikeAnUnsureOne() {
        XCTAssertEqual(summary(ticketImport(.unreadable, match: nil, knownIds: [], localIds: localIds)),
                       "prompt [] preselected none")
    }

    // MARK: - The stored state

    private let chip = [
        StoredSetlistFmHit(id: "t1000002", artist: "Ferrous Owls", venue: "Driftshallen", city: "Tromsø",
                           date: "12-03-2027", venueLevel: "weak"),
        StoredSetlistFmHit(id: "t1000003", artist: "Ferrous Owls", venue: "Kaikanten", city: "Tromsø",
                           date: "13-03-2027", venueLevel: "noMatch"),
    ]

    func testAskingSetsTheChipAndNoneOfTheseRejectsItAndClearsTheSnapshot() {
        let asked = StoredSetlistFmLookup(lastLookupAt: 5, rejectedIds: ["t0000001"]).asking(chip)
        XCTAssertEqual(asked.pendingHitIds, ["t1000002", "t1000003"])
        XCTAssertEqual(asked.pendingHits, chip)
        XCTAssertTrue(asked.possibleMatchPending)

        XCTAssertEqual(asked.rejectingPending(),
                       StoredSetlistFmLookup(lastLookupAt: 5, rejectedIds: ["t0000001", "t1000002", "t1000003"]))
    }

    /// What Android writes (`encodeDefaults`), read here field for field.
    func testALookupAndroidWroteReadsWhole() throws {
        let claim = try JSONDecoder().decode(StoredAttendance.self, from: Data("""
        {"provenance":"planned","checkedInAt":null,"venueLat":null,"venueLon":null,"admissions":[],\
        "setlistFmLookup":{"lastLookupAt":1804881600000,"rejectedIds":["t0000001"],\
        "pendingHitIds":["t1000002","t1000003"],"pendingHits":[\
        {"id":"t1000002","artist":"Ferrous Owls","venue":"Driftshallen","city":"Tromsø","date":"12-03-2027","venueLevel":"weak"},\
        {"id":"t1000003","artist":"Ferrous Owls","venue":"Kaikanten","city":"Tromsø","date":"13-03-2027","venueLevel":"noMatch"}]}}
        """.utf8))

        XCTAssertEqual(claim.setlistFmLookup,
                       StoredSetlistFmLookup(lastLookupAt: 1804881600000, rejectedIds: ["t0000001"]).asking(chip))
    }

    func testPendingHitsRoundTripThroughTheStore() async throws {
        let file = tempFile()
        let store = TimelineStore(file: file)
        let lookup = StoredSetlistFmLookup(lastLookupAt: 1804881600000, rejectedIds: ["t0000001"]).asking(chip)
        await store.saveAttendance(setlistId: "t9000001", attendance: StoredAttendance(setlistFmLookup: lookup))

        let reloaded = await TimelineStore(file: file).load().attendance()["t9000001"]
        XCTAssertEqual(reloaded?.setlistFmLookup, lookup)
        let written = try String(contentsOf: file, encoding: .utf8)
        XCTAssertTrue(written.contains("\"pendingHits\""))
    }

    func testAMalformedLookupCostsOnlyWhatIsMalformedNeverTheNight() throws {
        let claim = try JSONDecoder().decode(StoredAttendance.self, from: Data("""
        {"provenance":"planned","setlistFmLookup":{"lastLookupAt":"soon",\
        "rejectedIds":["t0000001",7],"pendingHitIds":["t1000002","t1000003"],"pendingHits":[\
        {"id":"t1000002","artist":"Ferrous Owls","venue":"Driftshallen","city":"Tromsø","date":"12-03-2027","venueLevel":"weak"},\
        "not an object",null,7,{"id":5,"venue":"Kaikanten","venueLevel":3}],"future":true}}
        """.utf8))

        XCTAssertEqual(claim, StoredAttendance(
            provenance: "planned",
            setlistFmLookup: StoredSetlistFmLookup(
                pendingHitIds: ["t1000002", "t1000003"],
                pendingHits: [chip[0], StoredSetlistFmHit(venue: "Kaikanten")])))
    }

    func testALookupThatIsNotAnObjectReadsAsNeverLookedUpAndTheNightSurvives() throws {
        let claim = try JSONDecoder().decode(StoredAttendance.self, from: Data("""
        {"provenance":"attended","setlistFmLookup":"oops"}
        """.utf8))

        XCTAssertEqual(claim, StoredAttendance(provenance: "attended"))
    }

    func testALookupMissingFieldsReadsThemAsEmpty() throws {
        let claim = try JSONDecoder().decode(StoredAttendance.self, from: Data("""
        {"provenance":"planned","setlistFmLookup":{"lastLookupAt":7}}
        """.utf8))

        XCTAssertEqual(claim.setlistFmLookup, StoredSetlistFmLookup(lastLookupAt: 7))
    }

    // MARK: - unionAttendance

    func testTheLookupSurvivesAMergeWhicheverRecordWins() {
        let lookup = StoredSetlistFmLookup(lastLookupAt: 5, rejectedIds: ["t0000001"]).asking(chip)
        let planned = StoredAttendance(setlistFmLookup: lookup)
        let attended = StoredAttendance(provenance: "attended")

        XCTAssertEqual(unionAttendance(planned, attended).setlistFmLookup, lookup)
        XCTAssertEqual(unionAttendance(attended, planned).setlistFmLookup, lookup)
        XCTAssertEqual(unionAttendance(planned, attended).provenance, "attended")
    }

    func testTwoLookupsMergeTheirRejectionsKeepTheLaterStampAndDropAChipHitEitherSideRejected() {
        let winner = StoredSetlistFmLookup(lastLookupAt: 5, rejectedIds: ["t0000001"]).asking(chip)
        let other = StoredSetlistFmLookup(lastLookupAt: 9, rejectedIds: ["t1000002", "t0000001"])

        XCTAssertEqual(unionSetlistFmLookup(winner, other), StoredSetlistFmLookup(
            lastLookupAt: 9,
            rejectedIds: ["t0000001", "t1000002"],
            pendingHitIds: ["t1000003"],
            pendingHits: [chip[1]]))
    }

    func testTheChipComesFromTheOtherSideWhenTheWinnerHasNone() {
        let winner = StoredSetlistFmLookup(lastLookupAt: 9)
        let other = StoredSetlistFmLookup(lastLookupAt: 5).asking(chip)

        XCTAssertEqual(unionSetlistFmLookup(winner, other), StoredSetlistFmLookup(lastLookupAt: 9).asking(chip))
        XCTAssertEqual(unionSetlistFmLookup(nil, other), other)
        XCTAssertEqual(unionSetlistFmLookup(winner, nil), winner)
        XCTAssertNil(unionSetlistFmLookup(nil, nil))
    }

    // MARK: - editSetlistFmLookup

    func testEditingTheLookupOfANightNobodyClaimedMintsNothing() async {
        let file = tempFile()
        let store = TimelineStore(file: file)
        let gigId = await store.createLocalGig(date: "12-03-2027", artist: "Ferrous Owls", venue: "Kjøkkenhagen Scene")

        let edited = await store.editSetlistFmLookup(gigId: gigId) { $0.lookedUp(at: 5) }
        XCTAssertNil(edited)
        let nowhere = await store.editSetlistFmLookup(gigId: "nowhere") { $0.lookedUp(at: 5) }
        XCTAssertNil(nowhere)

        let loaded = await TimelineStore(file: file).load()
        XCTAssertTrue(loaded.gigAttendance.isEmpty)
        XCTAssertEqual(Set(loaded.gigs.keys), [gigId])
    }

    func testEditingTheLookupChangesOnlyTheLookupStartingFromNeverLookedUp() async {
        let file = tempFile()
        let store = TimelineStore(file: file)
        let gigId = await store.createLocalGig(date: "12-03-2027", artist: "Ferrous Owls", venue: "Kjøkkenhagen Scene")
        await store.saveAttendance(setlistId: gigId, attendance: StoredAttendance(provenance: "planned", venueLat: 1.5))

        let chip = self.chip
        let settled = await store.editSetlistFmLookup(gigId: gigId) { before in
            XCTAssertEqual(before, StoredSetlistFmLookup())
            return before.lookedUp(at: 5).asking(chip)
        }

        let expected = StoredAttendance(provenance: "planned", venueLat: 1.5,
                                        setlistFmLookup: StoredSetlistFmLookup(lastLookupAt: 5).asking(chip))
        XCTAssertEqual(settled, expected)
        let reloaded = await TimelineStore(file: file).load().gigAttendance[gigId]
        XCTAssertEqual(reloaded, expected)
    }

    func testTheLookupTravelsWithTheNightWhenItAdoptsASetlistFmId() async {
        let file = tempFile()
        let store = TimelineStore(file: file)
        let gigId = await store.createLocalGig(date: "12-03-2027", artist: "Ferrous Owls", venue: "Kjøkkenhagen Scene")
        await store.saveAttendance(setlistId: gigId, attendance: StoredAttendance())
        let chip = self.chip
        await store.editSetlistFmLookup(gigId: gigId) { $0.lookedUp(at: 5).asking(chip).rejectingPending() }

        let adopted = await store.adoptSetlistId(gigId: gigId, setlistId: "t1000001")
        XCTAssertTrue(adopted)

        let lookup = await TimelineStore(file: file).load().attendance()["t1000001"]?.setlistFmLookup
        XCTAssertEqual(lookup, StoredSetlistFmLookup(lastLookupAt: 5, rejectedIds: ["t1000002", "t1000003"]))
        // And the new id names the same record to edit.
        let again = await store.editSetlistFmLookup(gigId: "t1000001") { $0.lookedUp(at: 9) }
        XCTAssertEqual(again?.setlistFmLookup, lookup?.lookedUp(at: 9))
    }

    func testTheLookupSurvivesAdoptingAnIdAnotherRecordAlreadyHolds() async {
        let file = tempFile()
        let store = TimelineStore(file: file)
        let gigId = await store.createLocalGig(date: "12-03-2027", artist: "Ferrous Owls", venue: "Kjøkkenhagen Scene")
        await store.saveAttendance(setlistId: gigId, attendance: StoredAttendance())
        let chip = self.chip
        await store.editSetlistFmLookup(gigId: gigId) { $0.lookedUp(at: 5).asking(chip) }
        await store.saveAttendance(setlistId: "t1000001", attendance: StoredAttendance(provenance: "attended"))

        let adopted = await store.adoptSetlistId(gigId: gigId, setlistId: "t1000001")
        XCTAssertTrue(adopted)

        let record = await TimelineStore(file: file).load().attendance()["t1000001"]
        XCTAssertEqual(record?.provenance, "attended")
        XCTAssertEqual(record?.setlistFmLookup, StoredSetlistFmLookup(lastLookupAt: 5).asking(chip))
    }
}
