import XCTest
@testable import SetlistToSpotify

/// `timelines.json` is the cross-platform contract, so these are the Android
/// TimelineStoreTest cases plus the ones only a second implementation can check:
/// that a cache written by Android loads here, and that writing from here does
/// not drop the fields only Android uses.
final class TimelineStoreTests: XCTestCase {

    private var files: [URL] = []

    override func tearDown() {
        files.forEach { try? FileManager.default.removeItem(at: $0) }
        files = []
        super.tearDown()
    }

    private func tempFile(contents: String? = nil) -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("timelines-\(UUID().uuidString).json")
        files.append(url)
        if let contents { try? contents.write(to: url, atomically: true, encoding: .utf8) }
        return url
    }

    private func store(_ file: URL? = nil) -> TimelineStore { TimelineStore(file: file ?? tempFile()) }

    private func show(_ id: String) -> FmSetlist {
        FmSetlist(id: id, eventDate: "25-06-2026", artist: FmArtist(name: "The Warning"))
    }

    private func playlist(_ id: String) -> StoredPlaylist {
        StoredPlaylist(url: "https://open.spotify.com/playlist/\(id)", name: "2026 – The Warning", trackCount: 19)
    }

    func testMissingFileLoadsEmptyRatherThanThrowing() async {
        let cache = await store().load()
        XCTAssertTrue(cache.shows.isEmpty)
        XCTAssertTrue(cache.festivalNames.isEmpty)
    }

    func testASavedTimelineRoundTrips() async {
        let s = store()
        await s.save(shows: ["magnus": [show("a"), show("b")]])
        let loaded = await s.load()
        XCTAssertEqual(["a", "b"], loaded.shows["magnus"]?.map(\.id))
    }

    func testSavingOneLaneLeavesTheOthersAlone() async {
        let s = store()
        await s.save(shows: ["magnus": [show("a")]])
        await s.save(shows: ["Egil": [show("b")]])
        let shows = await s.load().shows
        XCTAssertEqual(["a"], shows["magnus"]?.map(\.id))
        XCTAssertEqual(["b"], shows["Egil"]?.map(\.id))
    }

    func testAFailedFetchDoesNotWipeTheLastGoodLane() async {
        let s = store()
        await s.save(shows: ["Egil": [show("a")]])
        // A failed fetch puts an empty list in the map.
        await s.save(shows: ["Egil": []])
        let loaded = await s.load()
        XCTAssertEqual(["a"], loaded.shows["Egil"]?.map(\.id))
    }

    func testFestivalNamesAccumulateAcrossSaves() async {
        let s = store()
        await s.save(festivalNames: ["a": "Tons of Rock"])
        await s.save(shows: ["magnus": [show("a")]])
        let loaded = await s.load()
        XCTAssertEqual(["a": "Tons of Rock"], loaded.festivalNames)
    }

    func testANightRemembersThePlaylistItBecame() async {
        let s = store()
        await s.save(shows: ["magnus": [show("a")]])
        await s.save(playlists: ["a": playlist("p1")])
        // A later save of the shows must not drop it: the two write independently.
        await s.save(shows: ["magnus": [show("a"), show("b")]])
        let loaded = await s.load()
        XCTAssertEqual(19, loaded.playlistsMade["a"]?.first?.trackCount)
        XCTAssertEqual(["a", "b"], loaded.shows["magnus"]?.map(\.id))
    }

    func testConvertingANightAgainKeepsTheLinkAlreadySentToSomeone() async {
        let s = store()
        await s.save(playlists: ["a": playlist("p1")])
        await s.save(playlists: ["a": playlist("p2")])
        let loaded = await s.load()
        XCTAssertEqual(
            ["https://open.spotify.com/playlist/p1", "https://open.spotify.com/playlist/p2"],
            loaded.playlistsMade["a"]?.map(\.url)
        )
    }

    func testRecordingTheSamePlaylistTwiceDoesNotDuplicateIt() async {
        let s = store()
        await s.save(playlists: ["a": playlist("p1")])
        await s.save(playlists: ["a": playlist("p1")])
        let loaded = await s.load()
        XCTAssertEqual(1, loaded.playlistsMade["a"]?.count)
    }

    func testRemovingAPlaylistDropsOnlyThatLink() async {
        let s = store()
        await s.save(playlists: ["a": playlist("p1")])
        await s.save(playlists: ["a": playlist("p2")])
        await s.removePlaylist(setlistId: "a", url: "https://open.spotify.com/playlist/p1")
        let loaded = await s.load()
        XCTAssertEqual(["https://open.spotify.com/playlist/p2"], loaded.playlistsMade["a"]?.map(\.url))
    }

    func testACacheWrittenBeforePlaylistsWereAListStillLoadsItsTimelines() async {
        // The shape a previous build wrote: playlists as one entry per night.
        let file = tempFile(contents: """
        {"shows":{"magnus":[{"id":"a","eventDate":"25-06-2026"}]},\
        "festivalNames":{},"playlists":{"a":{"url":"u","name":"n","trackCount":3}}}
        """)
        let loaded = await TimelineStore(file: file).load()
        XCTAssertEqual(["a"], loaded.shows["magnus"]?.map(\.id))
        XCTAssertTrue(loaded.playlistsMade.isEmpty)
    }

    func testAnUnreadableCacheDegradesToEmptyInsteadOfCrashingTheLaunch() async {
        let loaded = await TimelineStore(file: tempFile(contents: "{ not json")).load()
        XCTAssertTrue(loaded.shows.isEmpty)
    }

    func testSongOffsetsSurviveASaveOfTheShowsAroundThem() async {
        let s = store()
        await s.saveSongOffsets(setlistId: "a", offsets: [0, 214_000, -1])
        await s.save(shows: ["magnus": [show("a")]])
        let loaded = await s.load()
        XCTAssertEqual([0, 214_000, -1], loaded.songOffsetsBySetlist["a"])
    }

    func testRestampingANightReplacesItsOffsets() async {
        let s = store()
        await s.saveSongOffsets(setlistId: "a", offsets: [0, 100])
        await s.saveSongOffsets(setlistId: "a", offsets: [0, 250])
        let loaded = await s.load()
        XCTAssertEqual([0, 250], loaded.songOffsetsBySetlist["a"])
    }

    func testTheReportedTotalSurvivesAReloadSoPagingCanResume() async {
        let s = store()
        await s.save(shows: ["dizzi90": [show("a")]], attendedTotals: ["dizzi90": 169])
        // A later save of more shows must not drop the total already learned.
        await s.save(shows: ["dizzi90": [show("a"), show("b")]])
        let loaded = await s.load()
        XCTAssertEqual(169, loaded.attendedTotals["dizzi90"])
    }

    // MARK: - The cross-platform contract

    /// A cache exactly as the Android build writes it (kotlinx with
    /// encodeDefaults: every key present, nulls explicit) must load here and
    /// produce the same spine. This is the strongest single check available
    /// without two devices in the same room.
    func testACacheWrittenByAndroidLoadsAndYieldsTheSameSpine() async {
        let file = tempFile(contents: """
        {"shows":{"dizzi90":[\
        {"id":"a1","eventDate":"25-06-2026","artist":{"mbid":"m1","name":"Gojira","sortName":"Gojira","disambiguation":null},\
        "venue":{"name":"Ekebergsletta","city":{"name":"Oslo","country":{"name":"Norway"}}},"tour":null,\
        "sets":{"set":[{"name":null,"encore":null,"song":[{"name":"Flying Whales","info":null,"tape":false,"cover":null}]}]},\
        "url":"https://www.setlist.fm/setlist/a1.html","info":null},\
        {"id":"a2","eventDate":"24-06-2026","artist":{"mbid":"m2","name":"Ghost"},\
        "venue":{"name":"Ekebergsletta","city":{"name":"Oslo","country":{"name":"Norway"}}},\
        "sets":{"set":[]},"url":null,"info":"First show in Norway"}]},\
        "festivalNames":{"a1":"Tons of Rock 2026"},"playlistsMade":{},"attendedTotals":{"dizzi90":169},\
        "photosBySetlist":{"a1":["content://media/external/images/media/42"]},\
        "songOffsetsBySetlist":{"a1":[0,214000,-1]}}
        """)
        let cache = await TimelineStore(file: file).load()

        XCTAssertEqual(["a1", "a2"], cache.shows["dizzi90"]?.map(\.id))
        XCTAssertEqual(169, cache.attendedTotals["dizzi90"])
        XCTAssertEqual("Tons of Rock 2026", cache.festivalNames["a1"])
        XCTAssertEqual([0, 214_000, -1], cache.songOffsetsBySetlist["a1"])

        // The same two shows, grouped by this platform's rules: one festival,
        // named by the resolved festival name rather than the venue.
        let rows = weaveTimelines(mine: cache.shows["dizzi90"] ?? [], festivalNames: cache.festivalNames)
        XCTAssertEqual(1, rows.count)
        guard case .festival(let name, let shows) = rows[0].node else {
            return XCTFail("expected one festival node")
        }
        XCTAssertEqual("Tons of Rock 2026", name)
        XCTAssertEqual(2, shows.count)
        XCTAssertEqual("Flying Whales", shows[0].performed().first?.name)
        XCTAssertEqual("Ekebergsletta, Oslo, Norway", shows[0].venueLine())
    }

    /// Writing from iOS must not silently drop the fields only Android fills in.
    /// A round of saves from this side is otherwise a data-loss event the user
    /// only discovers on the other phone.
    func testAWriteFromHereKeepsTheFieldsOnlyAndroidUses() async {
        let file = tempFile(contents: """
        {"shows":{},"festivalNames":{},"playlistsMade":{},"attendedTotals":{},\
        "photosBySetlist":{"a1":["content://x/1"]},"songOffsetsBySetlist":{"a1":[0,5000]},\
        "attendanceByGig":{"a1":{"provenance":"checked_in","checkedInAt":1750000000000,\
        "venueLat":59.9,"venueLon":10.7}}}
        """)
        let s = TimelineStore(file: file)
        await s.save(shows: ["dizzi90": [show("b1")]])
        let loaded = await s.load()
        XCTAssertEqual(["content://x/1"], loaded.photosBySetlist["a1"])
        XCTAssertEqual([0, 5000], loaded.songOffsetsBySetlist["a1"])
        // #29's check-in is the newest Android-only field, and the one a Reliver
        // would most notice losing: it is evidence they were there.
        XCTAssertEqual("checked_in", loaded.attendanceByGig["a1"]?.provenance)
        XCTAssertEqual(1_750_000_000_000, loaded.attendanceByGig["a1"]?.checkedInAt)
        XCTAssertEqual(59.9, loaded.attendanceByGig["a1"]?.venueLat)
        XCTAssertEqual(["b1"], loaded.shows["dizzi90"]?.map(\.id))
    }

    /// Every key kotlinx expects is present in what we write, so the file we
    /// hand back is one an Android build can read without its own tolerance
    /// rules doing the work.
    func testWhatWeWriteCarriesEveryKeyAndroidExpects() async throws {
        let file = tempFile()
        await TimelineStore(file: file).save(shows: ["dizzi90": [show("a")]])
        let json = try JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [String: Any]
        XCTAssertEqual(
            ["attendanceByGig", "attendedTotals", "festivalNames", "photosBySetlist",
             "playlistsMade", "shows", "songOffsetsBySetlist"],
            json?.keys.sorted()
        )
    }
}
