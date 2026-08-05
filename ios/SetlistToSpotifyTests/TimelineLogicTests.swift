import XCTest
@testable import SetlistToSpotify

/// The logic layer above the plumbing (ADR-0001). Case for case with Android's
/// `TimelineLogicTest`: these are the rules the two builds must agree about, so a
/// case added on one side belongs on the other.
///
/// Every one of them is reachable only because the plumbing is handed in. Before
/// the split, the collaborators were constructed in place, so the rules that had
/// actually broken in the field — a reopened app showing venue names, an empty CI
/// screenshot, playlist naming drifting between the platforms — were exactly the
/// rules nothing could assert.
final class TimelineLogicTests: XCTestCase {

    // MARK: - The fake device
    //
    // The whole test double. If it ever stops being trivial to write, the
    // plumbing interface is wrong.

    private final class FakePlumbing: TimelinePlumbing {
        var seeded: LoadedSpine?
        var stored: LoadedSpine?
        /// Festival name by setlist page url; a url that isn't here fails the
        /// scrape, which is the silent case.
        var festivalNames: [String: String] = [:]
        /// Pages of an Attended list by username, in page order.
        var pages: [String: [(shows: [FmSetlist], total: Int)]] = [:]

        /// What was asked of the device, in order. A call-order rule is exactly
        /// what a pure function could not express, so it is asserted directly.
        private(set) var calls: [String] = []
        private(set) var savedFestivalNames: [String: String] = [:]

        func seededSpine() async -> LoadedSpine? {
            calls.append("seededSpine")
            return seeded
        }

        func storedSpine(me: String) async -> LoadedSpine? {
            calls.append("storedSpine")
            return stored
        }

        func attendedPage(_ user: String, page: Int) async throws -> (shows: [FmSetlist], total: Int) {
            calls.append("attendedPage(\(user), \(page))")
            let all = pages[user] ?? []
            guard page <= all.count else { return ([], 0) }
            return all[page - 1]
        }

        func festivalName(setlistURL: String) async -> String? {
            calls.append("festivalName(\(setlistURL))")
            return festivalNames[setlistURL]
        }

        func saveFestivalNames(_ names: [String: String]) async {
            calls.append("saveFestivalNames")
            savedFestivalNames.merge(names) { _, new in new }
        }
    }

    private func show(
        _ id: String,
        _ date: String = "25-06-2026",
        venue: String = "Rockefeller",
        artist: String = "The Warning",
        url: String? = nil
    ) -> FmSetlist {
        FmSetlist(
            id: id,
            eventDate: date,
            artist: FmArtist(name: artist),
            venue: FmVenue(name: venue),
            url: url ?? "https://www.setlist.fm/setlist/\(id).html"
        )
    }

    /// Two nights at one venue: a **Festival**, as `groupIntoFestivals` sees it.
    private func festivalShows() -> [FmSetlist] {
        [show("a", "26-06-2026", venue: "Ekebergsletta"),
         show("b", "25-06-2026", venue: "Ekebergsletta")]
    }

    // MARK: - The playlist name
    //
    // The rule that shipped wrong output from correct sequencing, drifted between
    // the platforms and cost a commit to bring back in line. Asserted here and in
    // Android's TimelineLogicTest with the same inputs and the same expectations.

    func testALoneShowIsNamedYearArtistVenue() {
        let gig = show("a", "25-06-2026", venue: "Rockefeller")
        XCTAssertEqual(
            "2026 – The Warning – Rockefeller",
            TimelineLogic.playlistName(for: gig, mine: [gig], festivalNames: [:])
        )
    }

    func testAFestivalIsNamedByItsFestivalNameWithTheYearStripped() {
        // The year already leads, so "Tons of Rock 2026" must not repeat it.
        let mine = festivalShows()
        XCTAssertEqual(
            "2026 – The Warning – Tons of Rock",
            TimelineLogic.playlistName(
                for: mine[1], mine: mine, festivalNames: [mine[0].id: "Tons of Rock 2026"]
            )
        )
    }

    func testAFestivalWithNoResolvedNameFallsBackToItsVenue() {
        let mine = festivalShows()
        XCTAssertEqual(
            "2026 – The Warning – Ekebergsletta",
            TimelineLogic.playlistName(for: mine[0], mine: mine, festivalNames: [:])
        )
    }

    func testANameWithNothingKnownIsJustSetlist() {
        let gig = FmSetlist(id: "a")
        XCTAssertEqual("Setlist", TimelineLogic.playlistName(for: gig, mine: [gig], festivalNames: [:]))
    }

    // MARK: - The sequence
    //
    // Load, then retry the Festival names, then save them. A call-order rule, and
    // the reason this layer is allowed to call plumbing at all.

    func testUnresolvedFestivalNamesAreRetriedOnLoad() async {
        let mine = festivalShows()
        let fake = FakePlumbing()
        fake.stored = LoadedSpine(me: "magnus", mine: mine)
        fake.festivalNames = [mine[0].url!: "Tons of Rock 2026"]

        var emitted: [LoadedSpine] = []
        await TimelineLogic(plumbing: fake).loadSpine(me: "magnus") { emitted.append($0) }

        // Twice: the cached Spine has to be on screen before any network is, so
        // the names cannot be awaited before the first hand-over.
        XCTAssertEqual(2, emitted.count)
        XCTAssertTrue(emitted[0].festivalNames.isEmpty)
        XCTAssertEqual("Tons of Rock 2026", emitted[1].festivalNames[mine[0].id])
        // Paid once: a Festival name costs a fetch each.
        XCTAssertEqual(["Tons of Rock 2026"], Array(fake.savedFestivalNames.values))
    }

    func testAFestivalNameAlreadyKnownIsNotFetchedAgain() async {
        let mine = festivalShows()
        let fake = FakePlumbing()
        fake.stored = LoadedSpine(me: "magnus", mine: mine, festivalNames: [mine[0].id: "Tons of Rock 2026"])

        var emitted: [LoadedSpine] = []
        await TimelineLogic(plumbing: fake).loadSpine(me: "magnus") { emitted.append($0) }

        XCTAssertEqual(1, emitted.count)
        XCTAssertFalse(fake.calls.contains { $0.hasPrefix("festivalName") })
    }

    func testAFailedScrapeLeavesTheVenueStandingAndSavesNothing() async {
        let fake = FakePlumbing()
        fake.stored = LoadedSpine(me: "magnus", mine: festivalShows())
        // No entry in `festivalNames`: the scrape came back with nothing.

        var emitted: [LoadedSpine] = []
        await TimelineLogic(plumbing: fake).loadSpine(me: "magnus") { emitted.append($0) }

        XCTAssertEqual(1, emitted.count)
        XCTAssertTrue(fake.savedFestivalNames.isEmpty)
    }

    func testASeededFixtureIsTheSpineAndTheStoreIsNeverRead() async {
        let fake = FakePlumbing()
        fake.seeded = LoadedSpine(me: "dizzi90", mine: [show("fixture")])
        // In CI the stored cache is empty, which is how a screenshot came back
        // blank; here it holds something else entirely, so a read would show.
        fake.stored = LoadedSpine(me: "magnus", mine: [show("cached")])

        var emitted: [LoadedSpine] = []
        await TimelineLogic(plumbing: fake).loadSpine(me: "magnus") { emitted.append($0) }

        XCTAssertEqual(1, emitted.count)
        XCTAssertEqual(["fixture"], emitted[0].mine.map(\.id))
        XCTAssertFalse(fake.calls.contains("storedSpine"))
    }

    func testNothingStoredYetLeavesTheScreenAlone() async {
        let fake = FakePlumbing()

        var emitted: [LoadedSpine] = []
        await TimelineLogic(plumbing: fake).loadSpine(me: "magnus") { emitted.append($0) }

        XCTAssertTrue(emitted.isEmpty)
    }

    // MARK: - Shared concerts

    func testSharedConcertsAreTheIntersectionOfTwoAttendedLists() async throws {
        let fake = FakePlumbing()
        fake.pages = [
            "magnus": [([show("a"), show("b"), show("c")], 3)],
            "Ozzy": [([show("b"), show("c"), show("d")], 3)],
        ]
        let shared = try await TimelineLogic(plumbing: fake).sharedConcerts(me: "magnus", friend: "Ozzy")
        // Mine keeps its order, so the result is newest first like every list.
        XCTAssertEqual(["b", "c"], shared.map(\.id))
    }

    func testAttendedPagingStopsAtTheNamedCap() async throws {
        let fake = FakePlumbing()
        // Ten pages available and a total nobody will reach: only the guard stops it.
        let deep = (1...10).map { page in (shows: [show("p\(page)")], total: 999) }
        fake.pages = ["magnus": deep, "Ozzy": deep]
        _ = try await TimelineLogic(plumbing: fake).sharedConcerts(me: "magnus", friend: "Ozzy")

        let asked = fake.calls.filter { $0.hasPrefix("attendedPage(magnus") }
        XCTAssertEqual(TimelineLogic.attendedPageCap, asked.count)
    }

    func testAttendedPagingStopsEarlyOnceTheTotalIsInHand() async throws {
        let fake = FakePlumbing()
        fake.pages = [
            "magnus": [([show("a")], 1), ([show("never")], 1)],
            "Ozzy": [([show("a")], 1)],
        ]
        let shared = try await TimelineLogic(plumbing: fake).sharedConcerts(me: "magnus", friend: "Ozzy")
        XCTAssertEqual(["a"], shared.map(\.id))
        XCTAssertEqual(1, fake.calls.filter { $0.hasPrefix("attendedPage(magnus") }.count)
    }
}
