import XCTest
@testable import StationToStation

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
        /// What the scrape of a setlist page finds, by url. A url that isn't here is
        /// a night at no festival — the common answer, and the one that must be
        /// remembered rather than asked again.
        var festivals: [String: ScrapedFestival] = [:]
        /// Pages of an Attended list by username, in page order.
        var pages: [String: [(shows: [FmSetlist], total: Int)]] = [:]

        /// What was asked of the device, in order. A call-order rule is exactly
        /// what a pure function could not express, so it is asserted directly.
        var calls: [String] = []
        private(set) var savedFestivals: [String: StoredFestival] = [:]
        private(set) var savedMembership: [String: String] = [:]
        private(set) var savedAsked: Set<String> = []

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

        func festivalAt(setlistURL: String) async -> ScrapedFestival? {
            calls.append("festivalAt(\(setlistURL))")
            return festivals[setlistURL]
        }

        func saveFestivals(
            _ festivals: [String: StoredFestival],
            idByShow: [String: String],
            asked: Set<String>
        ) async {
            calls.append("saveFestivals")
            savedFestivals.merge(festivals) { _, new in new }
            savedMembership.merge(idByShow) { _, new in new }
            savedAsked.formUnion(asked)
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

    /// Two acts at one venue on one night: a **Section**, and the one shape the
    /// resolver asks setlist.fm about. Two *nights* would be two **Nodes** and would
    /// never be asked about at all (#166).
    private func oneEvening() -> [FmSetlist] {
        [show("a", "25-06-2026", venue: "Ekebergsletta"),
         show("b", "25-06-2026", venue: "Ekebergsletta", artist: "Gojira")]
    }

    /// The identity that evening turns out to have, as the scrape hands it over.
    private func scraped() -> ScrapedFestival {
        ScrapedFestival(
            name: "Tons of Rock 2026",
            slug: "tons-of-rock-2026-6bd52ece",
            rangeFrom: "24-06-2026",
            rangeTo: "27-06-2026"
        )
    }

    /// The same identity, as everything downstream of the resolver holds it. The id is
    /// the app's own and any stable string will do — that it is minted from the slug is
    /// the store's business, not this layer's.
    private func identified(_ shows: [FmSetlist], asked: Set<String> = []) -> Festivals {
        Festivals(
            byId: ["tor": StoredFestival(id: "tor", name: "Tons of Rock 2026")],
            idByShow: Dictionary(uniqueKeysWithValues: shows.map { ($0.id, "tor") }),
            asked: asked
        )
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
            TimelineLogic.playlistName(for: gig, mine: [gig], festivals: Festivals())
        )
    }

    func testAFestivalIsNamedByItsFestivalNameWithTheYearStripped() {
        // The year already leads, so "Tons of Rock 2026" must not repeat it.
        let mine = oneEvening()
        XCTAssertEqual(
            "2026 – The Warning – Tons of Rock",
            TimelineLogic.playlistName(for: mine[0], mine: mine, festivals: identified(mine))
        )
    }

    func testAnEveningWithNoIdentityIsNamedByItsRoom() {
        // Which is right *here* and nowhere else: a playlist title says where the
        // night was, and a room is a true answer to that. It is the **Node**'s label
        // that must never be a venue — see billedAs.
        let mine = oneEvening()
        XCTAssertEqual(
            "2026 – The Warning – Ekebergsletta",
            TimelineLogic.playlistName(for: mine[0], mine: mine, festivals: Festivals())
        )
    }

    func testANameWithNothingKnownIsJustSetlist() {
        let gig = FmSetlist(id: "a")
        XCTAssertEqual(
            "Setlist",
            TimelineLogic.playlistName(for: gig, mine: [gig], festivals: Festivals())
        )
    }

    // MARK: - The sequence
    //
    // Load, then ask about the evenings nothing has identified, then save the answers.
    // A call-order rule, and the reason this layer is allowed to call plumbing at all.

    func testAnUnidentifiedEveningIsAskedAboutOnLoad() async {
        let mine = oneEvening()
        let fake = FakePlumbing()
        fake.stored = LoadedSpine(me: "magnus", mine: mine)
        fake.festivals = [mine[0].url!: scraped()]

        var emitted: [LoadedSpine] = []
        await TimelineLogic(plumbing: fake).loadSpine(me: "magnus") { emitted.append($0) }

        // Twice: the cached Spine has to be on screen before any network is, so the
        // identities cannot be awaited before the first hand-over.
        XCTAssertEqual(2, emitted.count)
        XCTAssertTrue(emitted[0].festivals.isEmpty)
        XCTAssertEqual("Tons of Rock 2026", emitted[1].festivals.of("a")?.name)
        // The range is the festival's own, not the nights I happened to attend.
        XCTAssertEqual("24-06-2026", emitted[1].festivals.of("a")?.rangeFrom)
        // Paid once: an identity costs a fetch, so it is saved the moment it lands.
        XCTAssertEqual(["Tons of Rock 2026"], fake.savedFestivals.values.map(\.name))
    }

    func testAnEveningAlreadyIdentifiedIsNotAskedAboutAgain() async {
        let mine = oneEvening()
        let fake = FakePlumbing()
        fake.stored = LoadedSpine(me: "magnus", mine: mine, festivals: identified(mine))

        var emitted: [LoadedSpine] = []
        await TimelineLogic(plumbing: fake).loadSpine(me: "magnus") { emitted.append($0) }

        XCTAssertEqual(1, emitted.count)
        XCTAssertFalse(fake.calls.contains { $0.hasPrefix("festivalAt") })
    }

    func testANightAtNoFestivalIsAskedAboutOnceEver() async {
        // The Sentrum Scene case: a headline show with support looks exactly like a
        // festival day in the data, so it has to be asked about — and "no festival"
        // is a real answer worth keeping. 44 nights on the line are shaped like that
        // one, and re-asking on every launch is 44 fetches for nothing.
        let mine = oneEvening()
        let fake = FakePlumbing()
        let logic = TimelineLogic(plumbing: fake)

        let first = await logic.resolveFestivals(mine: mine, known: Festivals())
        XCTAssertTrue(first.isEmpty)
        // The evening, not the act: one page answers for the whole night.
        XCTAssertEqual(Set(["a", "b"]), fake.savedAsked)

        fake.calls.removeAll()
        _ = await logic.resolveFestivals(mine: mine, known: first)
        XCTAssertFalse(fake.calls.contains { $0.hasPrefix("festivalAt") })
    }

    func testTwoNightsAtOneVenueAreNeverAskedAboutAtAll() async {
        // They are two Nodes now. Nothing on the Line claims they are one thing, so
        // there is nothing to look up: the four-day window is gone from the seam and
        // from what it costs.
        let mine = [
            show("a", "26-06-2026", venue: "Ekebergsletta"),
            show("b", "25-06-2026", venue: "Ekebergsletta"),
        ]
        let fake = FakePlumbing()
        _ = await TimelineLogic(plumbing: fake).resolveFestivals(mine: mine, known: Festivals())
        XCTAssertTrue(fake.calls.isEmpty)
    }

    func testTheSourcesOwnDayGroupingDecidesMembershipNotMyAttendance() async {
        // I went for one day; the festival did not become one day long, and the acts
        // I did not see still belong to it.
        let mine = oneEvening()
        let fake = FakePlumbing()
        var page = scraped()
        page.dayMembership = ["26-06-2026": ["elsewhere"]]
        fake.festivals = [mine[0].url!: page]

        let found = await TimelineLogic(plumbing: fake)
            .resolveFestivals(mine: mine, known: Festivals())
        XCTAssertEqual("Tons of Rock 2026", found.of("elsewhere")?.name)
        XCTAssertEqual(found.of("a")?.id, found.of("elsewhere")?.id)
    }

    func testAMultiDayFestivalCostsOneFetchNotOnePerDay() async {
        // Three days at one venue arrive here as three Sections, because the candidates
        // are decided before anything has been asked. The first day's page names the
        // other two, so asking about them again would buy the same bytes twice more —
        // and each ask is two fetches, the setlist page and the festival page behind it.
        let mine = [
            show("thu1", "26-06-2026", venue: "Ekebergsletta"),
            show("thu2", "26-06-2026", venue: "Ekebergsletta", artist: "Gojira"),
            show("wed1", "25-06-2026", venue: "Ekebergsletta"),
            show("wed2", "25-06-2026", venue: "Ekebergsletta", artist: "Gojira"),
            show("tue1", "24-06-2026", venue: "Ekebergsletta"),
            show("tue2", "24-06-2026", venue: "Ekebergsletta", artist: "Gojira"),
        ]
        let fake = FakePlumbing()
        var page = scraped()
        page.dayMembership = [
            "26-06-2026": ["thu1", "thu2"],
            "25-06-2026": ["wed1", "wed2"],
            "24-06-2026": ["tue1", "tue2"],
        ]
        fake.festivals = [mine[0].url!: page]

        let found = await TimelineLogic(plumbing: fake)
            .resolveFestivals(mine: mine, known: Festivals())

        XCTAssertEqual(1, fake.calls.filter { $0.hasPrefix("festivalAt") }.count)
        // And it answered for the whole run, not only the night it was asked about.
        XCTAssertEqual(
            Set(["Tons of Rock 2026"]),
            Set(mine.compactMap { found.of($0.id)?.name })
        )
        XCTAssertEqual(6, mine.filter { found.of($0.id) != nil }.count)
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
