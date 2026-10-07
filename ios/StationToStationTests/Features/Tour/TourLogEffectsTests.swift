import Foundation
import XCTest
@testable import StationToStation

@MainActor
final class TourLogEffectsTests: XCTestCase {
    private struct Online: TourConnectivity {
        func isOnline() -> Bool { true }
    }

    private var suite = ""
    private var defaults: UserDefaults!
    private var file: URL!
    private var timelines: TimelineStore!
    private var host: FakeState!
    private var published: [String] = []
    private var fmRequests: [URL] = []
    private var session: URLSession!
    private let songs = ["Don't Look Back", "Second Song"] + (3...14).map { "Song \($0)" }
    private var demo: FmSetlist {
        FmSetlist(id: "demo", eventDate: "01-01-2030", artist: FmArtist(mbid: "band-id", name: "Band"))
    }

    override func setUp() async throws {
        suite = "TourLogEffectsTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)!
        file = FileManager.default.temporaryDirectory.appendingPathComponent("\(suite).json")
        timelines = TimelineStore(file: file)
        host = FakeState()
        host.state.selectedSetlist = demo
        host.state.plannedGigs = [demo]
        published = []
        fmRequests = []
        TourRecordingsProtocol.body = "{}"
        TourRecordingsProtocol.requests = []
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [TourRecordingsProtocol.self]
        session = URLSession(configuration: configuration)
    }

    override func tearDown() async throws {
        session.invalidateAndCancel()
        defaults.removePersistentDomain(forName: suite)
        try? FileManager.default.removeItem(at: file)
    }

    private func response(_ songs: [String], id: String = "recent", date: String = "01-09-2026") -> [String: Any] {
        ["id": id, "eventDate": date, "sets": ["set": [["song": songs.map { ["name": $0] }]]]]
    }

    private func client(_ setlists: [[String: Any]], status: Int = 200) throws -> SetlistFmClient {
        let body = try JSONSerialization.data(withJSONObject: ["total": setlists.count, "setlist": setlists])
        return SetlistFmClient(keySource: { SetlistFmKey(key: "test-key", shared: false) },
                               now: { 1_000_000 }, transport: { [unowned self] url, _ in
            self.fmRequests.append(url)
            return SetlistFmResponse(status: status, body: body)
        }, sleep: { _ in })
    }

    private func effects(_ fm: SetlistFmClient) -> TourLogEffects {
        TourLogEffects(host: host, store: defaults, setlistFm: fm,
                       musicBrainz: MusicBrainzClient(session: session), friendKey: { "virtual-contact-key" },
                       characterLine: { "character.lines.\($0.rawValue)" })
    }

    private func makeTour(_ effects: TourLogEffects, fm: SetlistFmClient) -> (TourController, GigController) {
        let addGig = TourAddGigEffects(store: defaults) { _ in }
        let night = TourNightArrivesEffects(store: defaults, demoGig: { [unowned self] in self.demo },
                                           deleteCalendarEvent: { _ in })
        let tour = TourController(host: host, settings: Settings(store: defaults), connectivity: Online(),
                                  demoWorld: DemoWorldRegistry(parts: [effects, night, addGig]),
                                  addGig: addGig, night: night, log: effects)
        let gig = GigController(host: host, timelines: timelines, setlistFm: fm, location: DeviceLocation(),
                                gossip: GossipController(host: host, timelines: timelines),
                                publishLog: { [unowned self] gigId, _, _, _ in self.published.append(gigId) })
        gig.logNow = { [unowned tour] in tour.now(for: $0) }
        gig.onLogWritten = { [unowned tour] in tour.logWritten($0, before: $1, after: $2, now: $3) }
        return (tour, gig)
    }

    private func walkToS14(_ tour: TourController) {
        tour.start()
        tour.send(.acknowledged)
        tour.send(.curtainPulled)
        tour.send(.bandPicked)
        tour.gigAdded("demo")
        tour.send(.roomOpened)
        tour.send(.swipedBack)
        tour.send(.contactExchanged(location: TourLocation(latitude: 0, longitude: 0)))
        tour.send(.pinchedOut)
        tour.send(.ticketImported)
        tour.calendarAdded("demo", eventId: "demo-calendar")
        tour.mapsOpened("demo")
        tour.ticketShown("demo")
        tour.checkedIn("demo")
    }

    private func eventually(_ condition: () -> Bool) async {
        for _ in 0..<200 {
            if condition() { return }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTFail("The Tour effect did not settle")
    }

    func testFillSetlistFmHitUsesTheMostRecentPlayedSetAndKeepsTheUsersLog() async throws {
        let fm = try client([response(["Old Song"], id: "old", date: "01-01-2020"),
                             response([], id: "planned", date: "01-01-2030"), response(songs)])
        let log = effects(fm)
        let original = StoredLog(songs: ["Dont Look Back", "My own song"], closed: true,
                                 remembered: ["chorus", ""], enteredAt: [41, 42], completedAt: 43,
                                 lineNumbers: [7, 9], nextLineNumber: 10)
        log.writeLog("demo", log: original, reply: false, now: 42)
        let filled = await log.fillSetlist(for: demo, now: 100)
        XCTAssertTrue(filled)
        XCTAssertEqual(host.state.gigLog.songs, original.songs + Array(songs.dropFirst().prefix(8)))
        XCTAssertEqual(host.state.gigLog.songs.count, 10)
        XCTAssertEqual(Array(host.state.gigLog.remembered.prefix(2)), original.remembered)
        XCTAssertEqual(Array(host.state.gigLog.enteredAt.prefix(2)), original.enteredAt)
        XCTAssertEqual(Array(host.state.gigLog.lineNumbers.prefix(2)), original.lineNumbers)
        XCTAssertEqual(host.state.gigLog.completedAt, original.completedAt)
        XCTAssertTrue(host.state.gigLog.closed)
        XCTAssertEqual(TourRecordingsProtocol.requests.count, 0)
        XCTAssertEqual(log.fillLine("demo"), "character.lines.S17")
    }

    func testFillFallbackUsesMusicBrainzRecordingsMinusTheUsersSongs() async throws {
        let body = try JSONSerialization.data(withJSONObject: ["recording-count": songs.count,
                                                               "recordings": songs.map { ["title": $0] }])
        TourRecordingsProtocol.body = String(decoding: body, as: UTF8.self)
        let fm = try client([])
        let log = effects(fm)
        log.writeLog("demo", log: StoredLog().adding("Dont Look Back", now: 10), reply: false, now: 10)
        let filled = await log.fillSetlist(for: demo, now: 20)
        XCTAssertTrue(filled)
        XCTAssertEqual(host.state.gigLog.songs, ["Dont Look Back"] + Array(songs.dropFirst().prefix(9)))
        XCTAssertEqual(TourRecordingsProtocol.requests.count, 1)
        XCTAssertTrue(TourRecordingsProtocol.requests.first?.url?.query?.contains("artist=band-id") == true)
    }

    func testThePureFillNormalisesDuplicatesAndDoesNotInventSongs() {
        XCTAssertEqual(tourSetlistFill(userSongs: ["P.I.M.P.", "Dont Look Back"],
                                      setlistFmSongs: ["pimp", "Don't Look Back", "New", "NEW", "  "],
                                      musicBrainzSongs: ["Fallback"]), ["New"])
        XCTAssertEqual(tourSetlistFill(userSongs: ["P.I.M.P."], setlistFmSongs: ["", "  "],
                                      musicBrainzSongs: ["pimp", "Other", "OTHER"]), ["Other"])
        XCTAssertEqual(tourSetlistFill(userSongs: songs, setlistFmSongs: ["Extra"], musicBrainzSongs: []), [])
        XCTAssertEqual(tourSetlistFill(userSongs: ["Mine"], setlistFmSongs: [], musicBrainzSongs: []), [])
    }

    func testTheSharedS17FillCasesResumeAndAdvanceToS18() async throws {
        let fixtures = try TourFixtures.load().filter { ["Fill: setlist.fm hit", "Fill: fallback"].contains($0.row) }
        XCTAssertEqual(fixtures.count, 2)
        for fixture in fixtures {
            let input = try XCTUnwrap(fixture.input, fixture.id)
            let expected = try XCTUnwrap(fixture.expected, fixture.id)
            let body = try JSONSerialization.data(withJSONObject: ["recording-count": input.musicBrainzSongs.count,
                                                                   "recordings": input.musicBrainzSongs.map { ["title": $0] }])
            TourRecordingsProtocol.body = String(decoding: body, as: UTF8.self)
            TourRecordingsProtocol.requests = []
            fmRequests = []
            let rawStep = try XCTUnwrap(fixture.initial?.step, fixture.id)
            let step = try XCTUnwrap(TourStep(rawValue: rawStep), fixture.id)
            Settings(store: defaults).saveTourState(TourState(currentStep: step))
            TourAddGigEffects(store: defaults) { _ in }.record("demo")
            let fm = try client(input.setlistFmSongs.isEmpty ? [] : [response(input.setlistFmSongs)])
            let log = effects(fm)
            let entered = input.enteredSongs.reduce(StoredLog()) { $0.adding($1, now: 7) }
            log.writeLog("demo", log: entered, reply: false, now: 7)
            let (tour, _) = makeTour(log, fm: fm)
            tour.start()
            await eventually { self.host.state.tourStep == .s18 }
            XCTAssertEqual(host.state.gigLog.songs, input.enteredSongs + (expected.addedSongs ?? []), fixture.id)
            XCTAssertEqual(host.state.gigLog.songs.count, try XCTUnwrap(expected.totalSongs), fixture.id)
            XCTAssertEqual(TourRecordingsProtocol.requests.count, expected.source == "musicBrainz" ? 1 : 0, fixture.id)
            XCTAssertEqual(fmRequests.count, 1, fixture.id)
            let check = try XCTUnwrap(fixture.checks.first, fixture.id)
            XCTAssertEqual(host.state.tourStep?.rawValue, check.expect.step, fixture.id)
            let mark = try XCTUnwrap(host.state.tourCoachMark, fixture.id)
            XCTAssertEqual(["showCoachMark(\(mark.rawValue))"], check.expect.commands, fixture.id)
            log.purge()
        }
    }

    func testARecordedGapIsFilledByTheVirtualFriendBeforeTheS16CoachMark() async throws {
        let fm = try client([response(songs)])
        let log = effects(fm)
        let (tour, gig) = makeTour(log, fm: fm)
        walkToS14(tour)
        XCTAssertEqual(host.state.tourStep, .s14)
        gig.addToLog("Dont Look Back")
        XCTAssertEqual(host.state.tourStep, .s15)
        gig.addToLog("")
        XCTAssertEqual(host.state.gigLog.gaps, 1)
        XCTAssertNil(host.state.tourCoachMark)
        let recorded = host.state.gigLog
        await eventually { self.host.state.tourCoachMark == .gossip }
        XCTAssertEqual(host.state.tourStep, .s16)
        XCTAssertEqual(host.state.gigLog.songs, ["Dont Look Back", "Second Song"])
        XCTAssertEqual(host.state.gigLog.enteredAt, recorded.enteredAt)
        XCTAssertEqual(host.state.gigLog.lineNumbers, recorded.lineNumbers)
        XCTAssertEqual(host.state.gigLog.enteredAt, Array(repeating: epochMs(tour.now(for: "demo")), count: 2))
        let received = try XCTUnwrap(tour.demoGossip(for: "demo").first)
        XCTAssertEqual(received.source, .virtualFriend)
        XCTAssertEqual(received.contactKey, "virtual-contact-key")
        XCTAssertEqual(received.song, "Second Song")
        XCTAssertEqual(received.characterLine, "character.lines.S16")
        XCTAssertTrue(received.demo)
        XCTAssertTrue(try XCTUnwrap(log.records["demo"]).demo)
        XCTAssertTrue(host.state.publicGossip.facts.isEmpty)
    }

    func testReplyGossipIsShownAndNeverPublishedAndTheFillAdvancesS17() async throws {
        let fm = try client([response(songs)])
        let log = effects(fm)
        let (tour, gig) = makeTour(log, fm: fm)
        _ = await timelines.savePlanned(demo)
        await timelines.saveAttendance(setlistId: "demo", attendance: StoredAttendance(
            provenance: "checked_in", checkedInAt: epochMs(Date(timeIntervalSince1970: 1_893_456_000))))
        walkToS14(tour)
        gig.addToLog("Dont Look Back")
        gig.addToLog("")
        await eventually { self.host.state.tourCoachMark == .gossip }
        gig.addToLog("Song 3")
        XCTAssertEqual(host.state.tourStep, .s17)
        XCTAssertEqual(tour.demoGossip(for: "demo").last?.source, .user)
        XCTAssertEqual(tour.demoGossip(for: "demo").last?.song, "Song 3")
        await eventually { self.host.state.tourStep == .s18 }
        XCTAssertEqual(host.state.gigLog.songs.count, 10)
        XCTAssertEqual(Array(host.state.gigLog.songs.prefix(3)), ["Dont Look Back", "Second Song", "Song 3"])
        XCTAssertEqual(published, [])
        let cache = await timelines.load()
        XCTAssertTrue(cache.gigLogs.isEmpty)
        XCTAssertTrue(host.state.publicGossip.facts.isEmpty)
        XCTAssertTrue(log.records["demo"]?.gossip.allSatisfy(\.demo) == true)
    }

    func testOtherGigsDoNotAdvanceTheTourAndFinishedToursDoNotInterceptWrites() throws {
        let fm = try client([])
        let log = effects(fm)
        let (tour, _) = makeTour(log, fm: fm)
        walkToS14(tour)
        XCTAssertFalse(tour.logWritten("real", before: StoredLog(), after: StoredLog(songs: ["Mine"]), now: 1))
        XCTAssertNil(tour.demoLog(for: "real"))
        XCTAssertEqual(host.state.tourStep, .s14)
        XCTAssertTrue(log.records.isEmpty)
        tour.skip()
        XCTAssertFalse(tour.logWritten("demo", before: StoredLog(), after: StoredLog(songs: ["Mine"]), now: 1))
    }

    func testWriteToLogLinksAndCompletionUseTheDemoClockAndStayTagged() throws {
        let fm = try client([])
        let log = effects(fm)
        let (tour, gig) = makeTour(log, fm: fm)
        walkToS14(tour)
        let now = epochMs(tour.now(for: "demo"))
        gig.writeToLog(appends: ["First", "Second"], replacements: [:])
        gig.writeToLog(appends: [], replacements: [1: "Corrected first", 3: "Third"])
        gig.setLogClosed(true)
        XCTAssertEqual(host.state.gigLog.songs, ["Corrected first", "Second", "Third"])
        XCTAssertEqual(host.state.gigLog.enteredAt, [now, now, now])
        XCTAssertEqual(host.state.gigLog.completedAt, now)
        XCTAssertTrue(log.records["demo"]?.demo == true)
        XCTAssertEqual(published, [])
    }

    func testResumingS16FinishesAnInterruptedGapDeliveryBeforeShowingItsCoachMark() async throws {
        Settings(store: defaults).saveTourState(TourState(currentStep: .s16))
        TourAddGigEffects(store: defaults) { _ in }.record("demo")
        let fm = try client([response(songs)])
        let log = effects(fm)
        log.writeLog("demo", log: StoredLog().adding("Dont Look Back", now: 1).adding("", now: 2),
                     reply: false, now: 2)
        let (tour, _) = makeTour(log, fm: fm)
        tour.start()
        XCTAssertNil(host.state.tourCoachMark)
        await eventually { self.host.state.tourCoachMark == .gossip }
        XCTAssertEqual(host.state.gigLog.songs, ["Dont Look Back", "Second Song"])
        XCTAssertEqual(tour.demoGossip(for: "demo").count, 1)
        tour.resume()
        XCTAssertEqual(tour.demoGossip(for: "demo").count, 1)
    }

    func testUnavailableSongSourcesKeepTheGapHonestUntilResumeCanDeliverGossip() async throws {
        let fm = try client([], status: 429)
        let log = effects(fm)
        let (tour, gig) = makeTour(log, fm: fm)
        walkToS14(tour)
        gig.addToLog("Dont Look Back")
        gig.addToLog("")
        await eventually { self.fmRequests.count == 2 && TourRecordingsProtocol.requests.count == 1 }
        XCTAssertEqual(host.state.tourStep, .s16)
        XCTAssertNil(host.state.tourCoachMark)
        XCTAssertEqual(host.state.gigLog.gaps, 1)
        XCTAssertTrue(tour.demoGossip(for: "demo").isEmpty)
        gig.addToLog("Song 3")
        XCTAssertEqual(host.state.tourStep, .s16)
        let body = try JSONSerialization.data(withJSONObject: ["recording-count": songs.count,
                                                               "recordings": songs.map { ["title": $0] }])
        TourRecordingsProtocol.body = String(decoding: body, as: UTF8.self)
        tour.resume()
        await eventually { self.host.state.tourCoachMark == .gossip }
        XCTAssertEqual(host.state.gigLog.songs, ["Dont Look Back", "Second Song", "Song 3"])
        XCTAssertEqual(tour.demoGossip(for: "demo").first?.source, .virtualFriend)
        XCTAssertEqual(published, [])
    }

    func testPurgeAfterRelaunchRemovesOnlyTaggedLogAndGossip() async throws {
        let fm = try client([response(songs)])
        let log = effects(fm)
        let (tour, gig) = makeTour(log, fm: fm)
        walkToS14(tour)
        gig.addToLog("Dont Look Back")
        gig.addToLog("")
        await eventually { self.host.state.tourCoachMark == .gossip }
        let real = FmSetlist(id: "real", eventDate: "01-01-2020", artist: FmArtist(name: "Real Band"))
        _ = await timelines.savePlanned(real)
        let own = StoredLog(songs: ["My memory"], enteredAt: [7])
        await timelines.saveLog(setlistId: real.id, log: own)
        let realGossip = GossipEnvelope(id: "real-gossip", gigId: real.id, scope: "real-scope", author: "real-author",
                                        createdAt: 1, expiresAt: 2, kind: "log", line: 0, text: "Real news")
        host.state.publicGossip.facts[realGossip.id] = realGossip
        host.state.plannedGigs.append(real)
        let restored = effects(fm)
        let (resumed, _) = makeTour(restored, fm: fm)
        resumed.start()
        XCTAssertEqual(resumed.demoLog(for: "demo"), log.loadLog("demo"))
        XCTAssertEqual(resumed.demoGossip(for: "demo").count, 1)
        resumed.skip()
        XCTAssertTrue(restored.records.isEmpty)
        XCTAssertTrue(host.state.gigLog.songs.isEmpty)
        XCTAssertNil(resumed.demoLog(for: "demo"))
        XCTAssertTrue(resumed.demoGossip(for: "demo").isEmpty)
        let cache = await timelines.load()
        let realId = try XCTUnwrap(cache.gigForSetlist(real.id)?.id)
        XCTAssertEqual(cache.gigLogs[realId], own)
        XCTAssertEqual(host.state.publicGossip.facts, [realGossip.id: realGossip])
        XCTAssertTrue(cache.planned().contains { $0.id == "real" })
        XCTAssertTrue(host.state.plannedGigs.contains { $0.id == "real" })
        XCTAssertEqual(published, [])
    }

    func testPurgeDoesNotClearTheUsersOpenLog() throws {
        let fm = try client([])
        let log = effects(fm)
        log.writeLog("demo", log: StoredLog(songs: ["Demo"]), reply: false, now: 1)
        host.state.selectedSetlist = FmSetlist(id: "real")
        let own = StoredLog(songs: ["My memory"])
        host.state.gigLog = own
        log.purge()
        XCTAssertEqual(host.state.gigLog, own)
        XCTAssertTrue(log.records.isEmpty)
    }

    func testAPendingFillCannotRestoreRecordsAfterPurge() async throws {
        var release: CheckedContinuation<SetlistFmResponse, Never>?
        let body = try JSONSerialization.data(withJSONObject: ["total": 1, "setlist": [response(songs)]])
        let fm = SetlistFmClient(keySource: { SetlistFmKey(key: "test", shared: false) }, transport: { _, _ in
            await withCheckedContinuation { release = $0 }
        }, sleep: { _ in })
        let log = effects(fm)
        log.writeLog("demo", log: StoredLog(songs: ["Mine"]), reply: false, now: 1)
        let task = Task { await log.fillSetlist(for: self.demo, now: 2) }
        await eventually { release != nil }
        log.purge()
        release?.resume(returning: SetlistFmResponse(status: 200, body: body))
        let filled = await task.value
        XCTAssertFalse(filled)
        XCTAssertTrue(log.records.isEmpty)
        XCTAssertTrue(host.state.gigLog.songs.isEmpty)
    }
}

private final class TourRecordingsProtocol: URLProtocol {
    static var body = "{}"
    static var requests: [URLRequest] = []

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let body = Self.body
        Self.requests.append(request)
        let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
