import XCTest
@testable import StationToStation

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

    // Ids fixed rather than random so an assertion can name one.
    private func photo(_ ref: String) -> StoredMedia {
        StoredMedia(id: "m-\(ref)", kind: StoredMedia.Kind.photo, ref: ref)
    }

    private func video(_ ref: String) -> StoredMedia {
        StoredMedia(id: "m-\(ref)", kind: StoredMedia.Kind.video, ref: ref)
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
        await s.save(shows: ["Ozzy": [show("b")]])
        let shows = await s.load().shows
        XCTAssertEqual(["a"], shows["magnus"]?.map(\.id))
        XCTAssertEqual(["b"], shows["Ozzy"]?.map(\.id))
    }

    func testAFailedFetchDoesNotWipeTheLastGoodLane() async {
        let s = store()
        await s.save(shows: ["Ozzy": [show("a")]])
        // A failed fetch puts an empty list in the map.
        await s.save(shows: ["Ozzy": []])
        let loaded = await s.load()
        XCTAssertEqual(["a"], loaded.shows["Ozzy"]?.map(\.id))
    }

    /// Asked and answered are different facts: an evening with no festival behind it
    /// must be remembered as asked, or every launch re-asks the whole timeline.
    func testFestivalsAskedAccumulatesAcrossSaves() async {
        let s = store()
        await s.save(festivalsAsked: ["a"])
        await s.save(festivalsAsked: ["b"])
        let loaded = await s.load()
        XCTAssertEqual(Set(["a", "b"]), loaded.festivalsAsked)
    }

    func testAnAuthoredIdentitySurvivesAScrapedOneArrivingLater() async {
        let s = store()
        let mine = StoredFestival(
            id: "f1", name: "Piknik i Parken", source: StoredFestival.FestivalSource.authored
        )
        await s.save(festivals: ["f1": mine])
        await s.save(festivals: ["f1": StoredFestival(
            id: "f1", name: "PiP", source: StoredFestival.FestivalSource.scraped
        )])
        let loaded = await s.load()
        XCTAssertEqual(mine, loaded.festivals["f1"])
    }

    func testFestivalsAndFestivalIdByShowRoundTripThroughASave() async {
        let s = store()
        let identity = StoredFestival(
            id: "f1", name: "Piknik i Parken", source: StoredFestival.FestivalSource.authored
        )
        await s.save(festivals: ["f1": identity], festivalIdByShow: ["a": "f1"])
        let loaded = await s.load()
        XCTAssertEqual(identity, loaded.festivals["f1"])
        XCTAssertEqual("f1", loaded.festivalIdByShow["a"])
    }

    func testANightRemembersThePlaylistItBecame() async {
        let s = store()
        await s.save(shows: ["magnus": [show("a")]])
        await s.save(playlists: ["a": playlist("p1")])
        // A later save of the shows must not drop it: the two write independently.
        await s.save(shows: ["magnus": [show("a"), show("b")]])
        let loaded = await s.load()
        XCTAssertEqual(19, loaded.playlists()["a"]?.first?.trackCount)
        XCTAssertEqual(["a", "b"], loaded.shows["magnus"]?.map(\.id))
    }

    func testConvertingANightAgainKeepsTheLinkAlreadySentToSomeone() async {
        let s = store()
        await s.save(playlists: ["a": playlist("p1")])
        await s.save(playlists: ["a": playlist("p2")])
        let loaded = await s.load()
        XCTAssertEqual(
            ["https://open.spotify.com/playlist/p1", "https://open.spotify.com/playlist/p2"],
            loaded.playlists()["a"]?.map(\.url)
        )
    }

    func testRecordingTheSamePlaylistTwiceDoesNotDuplicateIt() async {
        let s = store()
        await s.save(playlists: ["a": playlist("p1")])
        await s.save(playlists: ["a": playlist("p1")])
        let loaded = await s.load()
        XCTAssertEqual(1, loaded.playlists()["a"]?.count)
    }

    func testRemovingAPlaylistDropsOnlyThatLink() async {
        let s = store()
        await s.save(playlists: ["a": playlist("p1")])
        await s.save(playlists: ["a": playlist("p2")])
        await s.removePlaylist(setlistId: "a", url: "https://open.spotify.com/playlist/p1")
        let loaded = await s.load()
        XCTAssertEqual(["https://open.spotify.com/playlist/p2"], loaded.playlists()["a"]?.map(\.url))
    }

    func testACacheWrittenBeforePlaylistsWereAListStillLoadsItsTimelines() async {
        // The shape a previous build wrote: playlists as one entry per night.
        let file = tempFile(contents: """
        {"shows":{"magnus":[{"id":"a","eventDate":"25-06-2026"}]},\
        "festivalNames":{},"playlists":{"a":{"url":"u","name":"n","trackCount":3}}}
        """)
        let loaded = await TimelineStore(file: file).load()
        XCTAssertEqual(["a"], loaded.shows["magnus"]?.map(\.id))
        XCTAssertTrue(loaded.playlists().isEmpty)
    }

    func testAnUnreadableCacheDegradesToEmptyInsteadOfCrashingTheLaunch() async {
        let loaded = await TimelineStore(file: tempFile(contents: "{ not json")).load()
        XCTAssertTrue(loaded.shows.isEmpty)
    }

    func testSongOffsetsSurviveASaveOfTheShowsAroundThem() async {
        let s = store()
        await s.saveMedia(setlistId: "a", media: [video("content://rec.mp4")])
        await s.saveSongOffsets(mediaId: "m-content://rec.mp4", offsets: [0, 214_000, -1])
        await s.save(shows: ["magnus": [show("a")]])
        let loaded = await s.load()
        XCTAssertEqual([0, 214_000, -1], loaded.media()["a"]?.first?.songOffsets)
    }

    func testRestampingARecordingReplacesItsOffsets() async {
        let s = store()
        await s.saveMedia(setlistId: "a", media: [video("content://rec.mp4")])
        await s.saveSongOffsets(mediaId: "m-content://rec.mp4", offsets: [0, 100])
        await s.saveSongOffsets(mediaId: "m-content://rec.mp4", offsets: [0, 250])
        let loaded = await s.load()
        XCTAssertEqual([0, 250], loaded.media()["a"]?.first?.songOffsets)
    }

    /// The shape #27 needed and a night-keyed map could not express.
    func testTwoRecordingsOfOneNightEachCarryTheirOwnStamps() async {
        let s = store()
        await s.saveMedia(setlistId: "a", media: [video("content://one.mp4"), video("content://two.mp4")])
        await s.saveSongOffsets(mediaId: "m-content://one.mp4", offsets: [0, 100])
        await s.saveSongOffsets(mediaId: "m-content://two.mp4", offsets: [0, 250])
        let media = await s.load().media()["a"] ?? []
        XCTAssertEqual([0, 100], media[0].songOffsets)
        XCTAssertEqual([0, 250], media[1].songOffsets)
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
        "festivalNames":{"a1":"Tons of Rock 2026"},"festivalsMigrated":true,\
        "festivals":{"tor":{"id":"tor","name":"Tons of Rock 2026","rangeFrom":"24-06-2026",\
        "rangeTo":"27-06-2026","setlistFmSlug":"tons-of-rock-2026-6bd52ece","mbid":null,\
        "source":"scraped","dayMembership":null,"setTimes":null}},\
        "festivalIdByShow":{"a1":"tor","a2":"tor"},"festivalsAsked":["a1","a2"],\
        "playlistsMade":{},"attendedTotals":{"dizzi90":169},\
        "photosBySetlist":{"a1":["content://media/external/images/media/42"]},\
        "songOffsetsBySetlist":{"a1":[0,214000,-1]}}
        """)
        let cache = await TimelineStore(file: file).load()

        XCTAssertEqual(["a1", "a2"], cache.shows["dizzi90"]?.map(\.id))
        XCTAssertEqual(169, cache.attendedTotals["dizzi90"])
        // Dead since #166 but still written by Android, so still carried here.
        XCTAssertEqual("Tons of Rock 2026", cache.festivalNames["a1"])
        XCTAssertEqual("Tons of Rock 2026", cache.festivals["tor"]?.name)
        // No video among that night's media, so the stamps stay in the dead key
        // rather than being guessed onto a photo. See the one-video rule below.
        XCTAssertEqual([0, 214_000, -1], cache.songOffsetsBySetlist["a1"])

        // The same two shows, grouped by this platform's rules: one **Festival**,
        // because the identity Android stored says both nights are that festival.
        // Without it they would be two nights at one address (#166) — which is the
        // whole reason the identity, and not the name, crosses between the builds.
        let rows = weaveTimelines(
            mine: cache.shows["dizzi90"] ?? [], festivals: cache.festivalIdentities()
        )
        XCTAssertEqual(1, rows.count)
        guard case .festival(let identity, let shows) = rows[0].node else {
            return XCTFail("expected one festival node")
        }
        XCTAssertEqual("Tons of Rock 2026", identity.name)
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
        XCTAssertEqual(["content://x/1"], loaded.media()["a1"]?.map(\.ref))
        XCTAssertEqual([0, 5000], loaded.songOffsetsBySetlist["a1"])
        // #29's check-in is the newest Android-only field, and the one a Reliver
        // would most notice losing: it is evidence they were there.
        XCTAssertEqual("checked_in", loaded.attendance()["a1"]?.provenance)
        XCTAssertEqual(1_750_000_000_000, loaded.attendance()["a1"]?.checkedInAt)
        XCTAssertEqual(59.9, loaded.attendance()["a1"]?.venueLat)
        XCTAssertEqual(["b1"], loaded.shows["dizzi90"]?.map(\.id))
    }

    /// The keys iOS has never heard of at all — the **Bills** on the wall, my
    /// **Logs** of the nights, the catalogue, the two tier-upgrade fields — plus
    /// `text` and `verdict` inside a **Note**. `Codable` drops what it never
    /// decoded, so before #168 the first save from this side erased every one of
    /// them, and a **Bill** and a **Log** have no upstream: nothing can put them
    /// back.
    func testAWriteFromHereKeepsTheKeysThisPlatformHasNeverHeardOf() async throws {
        let file = tempFile(contents: """
        {"shows":{},"festivalNames":{},"gigs":{"g1":{"id":"g1","date":"25-06-2026",\
        "artist":"Gojira","venue":"Ekebergsletta","setlistId":null,"createdAt":1}},\
        "gigMedia":{"g1":[{"id":"n1","kind":"note","ref":"","capturedAt":null,"from":null,\
        "personal":true,"pointer":null,"songOffsets":[],\
        "text":"Best encore I have stood through.","verdict":"double_up"}]},\
        "bills":{"b1":{"id":"b1","name":"Tons of Rock 2026","city":"Oslo",\
        "from":"25-06-2026","to":"27-06-2026",\
        "acts":[{"name":"Gojira","maybe":false,"candidates":[],"gigId":null}]}},\
        "gigLogs":{"g1":{"songs":["Flying Whales",""],"closed":false}},\
        "sharedNights":["g1"],"mediaTierMigrated":true,\
        "catalogueByArtist":{"mb-1":["Flying Whales","Stranded"]}}
        """)
        let s = TimelineStore(file: file)
        await s.save(shows: ["dizzi90": [show("b1")]])

        let json = try JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [String: Any]
        // Asserted against the raw file rather than a decoded cache, because the
        // whole point is that this platform models almost none of these — carrying
        // them is what the file says, not what a Swift type remembered. (gigLogs
        // became the exception in #169; see below.)
        XCTAssertEqual(["b1"], (json?["bills"] as? [String: Any])?.keys.sorted())
        // gigLogs is the exception since #169: it is modelled now, not carried, so
        // this asserts it survives *decoding and re-encoding* rather than surviving
        // untouched. The Gap in the middle is the part a lossy round-trip would eat.
        XCTAssertEqual(["g1"], (json?["gigLogs"] as? [String: Any])?.keys.sorted())
        let log = (json?["gigLogs"] as? [String: Any])?["g1"] as? [String: Any]
        XCTAssertEqual(["Flying Whales", ""], log?["songs"] as? [String])
        XCTAssertEqual(false, log?["closed"] as? Bool)
        XCTAssertEqual(["g1"], json?["sharedNights"] as? [String])
        XCTAssertEqual(true, json?["mediaTierMigrated"] as? Bool)
        XCTAssertEqual(
            ["Flying Whales", "Stranded"],
            (json?["catalogueByArtist"] as? [String: [String]])?["mb-1"]
        )
        // A Bill's own shape has to survive too, not just its key: nothing here
        // reads an Act, so nothing here may flatten one.
        let bill = (json?["bills"] as? [String: [String: Any]])?["b1"]
        XCTAssertEqual("Tons of Rock 2026", bill?["name"] as? String)
        XCTAssertEqual(["Gojira"], (bill?["acts"] as? [[String: Any]])?.compactMap { $0["name"] as? String })

        // The two inside StoredMedia are modelled rather than carried — a note
        // round-trips through the same decode every photo does, so raw carrying
        // could never have reached them. #170 needs them on this side anyway.
        let loaded = await s.load()
        let note = loaded.gigMedia["g1"]?.first
        XCTAssertEqual(StoredMedia.Kind.note, note?.kind)
        XCTAssertEqual("Best encore I have stood through.", note?.text)
        XCTAssertEqual("double_up", note?.verdict)
        XCTAssertEqual(true, note?.personal)
        // And the save that carried all of it still did its own job.
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
            // gigLogs joined the list in #169 and bills in #172: iOS now models both
            // rather than carrying them blind, so it writes the keys itself instead of
            // only echoing ones it found. Android reads an absent or empty map the same
            // way.
            ["attendanceByGig", "attendedTotals", "bills", "calendarEventByGig",
             "festivalIdByShow", "festivalNames", "festivals", "festivalsAsked",
             "gigAttendance", "gigCalendarEvent", "gigLogs", "gigMedia", "gigPhotos", "gigPlanned",
             "gigPlaylists", "gigSongOffsets", "gigs", "photosBySetlist", "plannedShows", "playlistsMade",
             "shows", "songOffsetsBySetlist"],
            json?.keys.sorted()
        )
    }

    /// Before #107 these two were not carried at all: a save from iOS silently
    /// dropped the gigs Android held tickets for and the calendar events it had
    /// made. They are dead keys now, but dead is not the same as gone — an older
    /// Android build reading this file still expects to find them.
    func testAWriteFromHereKeepsTheDeadKeysAnOlderBuildStillReads() async {
        let file = tempFile(contents: """
        {"plannedShows":[{"id":"oya","eventDate":"25-06-2026"}],\
        "calendarEventByGig":{"oya":"content://com.android.calendar/events/42"}}
        """)
        let s = TimelineStore(file: file)
        await s.save(shows: ["dizzi90": [show("b1")]])
        let loaded = await s.load()
        XCTAssertEqual(["oya"], loaded.plannedShows.map(\.id))
        XCTAssertEqual("content://com.android.calendar/events/42", loaded.calendarEventByGig["oya"])
    }

    // MARK: - #107: a Gig gets an identity the app owns

    /// The migration has to produce the *same* ids here as on Android, or a user
    /// with two phones ends up with two histories of the same nights. Asserted
    /// against fixed values rather than against Android, so neither platform can
    /// drift by agreeing with itself.
    func testAnOldCacheMigratesEveryMapOntoOneGigPerNight() async {
        let file = tempFile(contents: """
        {"shows":{"magnus":[{"id":"a1","eventDate":"25-06-2026",\
        "artist":{"name":"Gojira"},"venue":{"name":"Ekebergsletta"}}]},\
        "playlistsMade":{"a1":[{"url":"u","name":"n","trackCount":3}]},\
        "photosBySetlist":{"a1":["content://photo1","content://rec.mp4"]},\
        "songOffsetsBySetlist":{"a1":[0,214000]},\
        "attendanceByGig":{"a1":{"provenance":"checked_in","checkedInAt":42}},\
        "calendarEventByGig":{"a1":"content://cal/7"}}
        """)
        let cache = await TimelineStore(file: file).load()

        XCTAssertEqual(1, cache.gigs.count)
        let gig = cache.gigs.values.first!
        XCTAssertEqual("a1", gig.setlistId)
        XCTAssertEqual("6033fd8a-ff1e-5334-854f-5e2edfd5a255", gig.id)
        // The facts of the night are filled in from the show the cache already held.
        XCTAssertEqual("25-06-2026", gig.date)
        XCTAssertEqual("Gojira", gig.artist)
        XCTAssertEqual("Ekebergsletta", gig.venue)
        // Everything still resolves, under the id the screens use.
        XCTAssertEqual(["content://photo1", "content://rec.mp4"], cache.media()["a1"]?.map(\.ref))
        // The night's one video takes the stamps that used to belong to the night.
        XCTAssertEqual([0, 214_000], cache.media()["a1"]?.last?.songOffsets)
        XCTAssertEqual(42, cache.attendance()["a1"]?.checkedInAt)
        XCTAssertEqual("content://cal/7", cache.calendarEvents()["a1"])
        XCTAssertEqual(1, cache.playlists()["a1"]?.count)
    }

    func testTheOldKeysSurviveTheMigrationSoAnOlderBuildIsUnharmed() async {
        let file = tempFile(contents: #"{"photosBySetlist":{"a1":["content://photo1"]}}"#)
        let s = TimelineStore(file: file)
        await s.save(shows: ["magnus": [show("b")]])
        let loaded = await s.load()
        XCTAssertEqual(["content://photo1"], loaded.photosBySetlist["a1"])
        XCTAssertEqual(["content://photo1"], loaded.media()["a1"]?.map(\.ref))
    }

    func testMigratingTwiceChangesNothing() async {
        let file = tempFile(contents: #"{"photosBySetlist":{"a1":["content://photo1"]}}"#)
        let s = TimelineStore(file: file)
        let first = Set(await s.load().gigs.keys)
        await s.saveMedia(setlistId: "a1", media: [photo("content://photo1"), photo("content://photo2")])
        let loaded = await s.load()
        XCTAssertEqual(first, Set(loaded.gigs.keys))
        XCTAssertEqual(2, loaded.media()["a1"]?.count)
    }

    /// The assertion #107 exists for: a night carrying everything a night can
    /// carry appears on setlist.fm, and nothing is orphaned by the good news.
    func testAdoptingASetlistIdPreservesEveryAssociation() async {
        let s = store()
        let gigId = await s.createLocalGig(date: "25-06-2026", artist: "The Warning", venue: "Vaterland")
        await s.saveMedia(setlistId: gigId, media: [photo("content://photo1"), video("content://rec.mp4")])
        await s.saveSongOffsets(mediaId: "m-content://rec.mp4", offsets: [0, 214_000])
        await s.save(playlists: [gigId: playlist("p1")])

        let adopted = await s.adoptSetlistId(gigId: gigId, setlistId: "63de6d5b")
        XCTAssertTrue(adopted)

        let after = await s.load()
        XCTAssertEqual("63de6d5b", after.setlistIdFor(gigId))
        XCTAssertEqual(
            ["content://photo1", "content://rec.mp4"],
            after.media()["63de6d5b"]?.map(\.ref)
        )
        XCTAssertEqual([0, 214_000], after.media()["63de6d5b"]?.last?.songOffsets)
        XCTAssertEqual(1, after.playlists()["63de6d5b"]?.count)
        XCTAssertEqual(1, after.gigs.count)
    }

    func testAdoptingASecondSetlistIdIsRefused() async {
        let s = store()
        let gigId = await s.createLocalGig(date: "25-06-2026", artist: "The Warning", venue: "Vaterland")
        let first = await s.adoptSetlistId(gigId: gigId, setlistId: "63de6d5b")
        let second = await s.adoptSetlistId(gigId: gigId, setlistId: "other")
        XCTAssertTrue(first)
        XCTAssertFalse(second)
        let after = await s.load()
        XCTAssertEqual("63de6d5b", after.setlistIdFor(gigId))
    }

    func testANightIsFoundFromEitherEnd() async {
        let s = store()
        await s.saveMedia(setlistId: "63de6d5b", media: [photo("content://photo1")])
        let cache = await s.load()
        let gig = cache.gigForSetlist("63de6d5b")
        XCTAssertEqual("63de6d5b", gig?.setlistId)
        XCTAssertEqual("63de6d5b", cache.setlistIdFor(gig!.id))
        XCTAssertEqual(1, cache.gigs.count)
    }

    /// A local-only Gig cannot be a **Crossing** — the weave keys on setlist.fm
    /// ids, and this night has none. #34 accepts that consequence; pinned here as
    /// behaviour rather than prose.
    func testALocalOnlyGigHasNoSetlistId() async {
        let s = store()
        let gigId = await s.createLocalGig(date: "25-06-2026", artist: "Local Band", venue: "A basement")
        let cache = await s.load()
        XCTAssertNil(cache.setlistIdFor(gigId))
        XCTAssertEqual(gigId, cache.keyOf(gigId))
        XCTAssertTrue(cache.gigs.values.allSatisfy { $0.setlistId == nil })
    }

    // MARK: - #97: media becomes a record

    private func oldCache(_ photos: String, offsets: String = "[0,214000]") -> TimelineStore {
        TimelineStore(file: tempFile(contents:
            #"{"photosBySetlist":{"a1":\#(photos)},"songOffsetsBySetlist":{"a1":\#(offsets)}}"#))
    }

    func testANightWithExactlyOneVideoTakesTheStampsThatWereTheNights() async {
        let cache = await oldCache(#"["content://photo.jpg","content://rec.mp4"]"#).load()
        let media = cache.media()["a1"] ?? []
        XCTAssertEqual([StoredMedia.Kind.photo, StoredMedia.Kind.video], media.map(\.kind))
        XCTAssertEqual([], media[0].songOffsets)
        XCTAssertEqual([0, 214_000], media[1].songOffsets)
    }

    func testANightWithNoVideoLeavesItsStampsInTheDeadKey() async {
        let cache = await oldCache(#"["content://photo.jpg"]"#).load()
        XCTAssertEqual([], cache.media()["a1"]?.first?.songOffsets)
        // Nothing is lost — and a guess would have been worse than declining:
        // there is no recording to be right about.
        XCTAssertEqual([0, 214_000], cache.songOffsetsBySetlist["a1"])
    }

    func testANightWithTwoVideosLeavesItsStampsPutRatherThanGuessing() async {
        let cache = await oldCache(#"["content://one.mp4","content://two.mp4"]"#).load()
        XCTAssertTrue((cache.media()["a1"] ?? []).allSatisfy { $0.songOffsets.isEmpty })
        XCTAssertEqual([0, 214_000], cache.songOffsetsBySetlist["a1"])
    }

    /// Fixed rather than recomputed here: Android asserts the same literal, so
    /// neither platform can drift by agreeing with its own arithmetic.
    func testMigratedMediaIdsAreDerived() async {
        let cache = await oldCache(#"["content://photo.jpg"]"#).load()
        XCTAssertEqual("70c08466-7711-5bc1-a64c-519669c9a42a", cache.media()["a1"]?.first?.id)
    }

    func testPersonalSurvivesTheMigrationAsFalseAndIsNeverInferred() async {
        let cache = await oldCache(#"["content://photo.jpg","content://rec.mp4"]"#).load()
        let media = cache.media()["a1"] ?? []
        XCTAssertTrue(media.allSatisfy { !$0.personal })
        // Nor is anything invented for the fields only a live attach can know.
        XCTAssertTrue(media.allSatisfy { $0.capturedAt == nil && $0.from == nil })
    }

    func testTheOldPhotoKeysSurviveTheMediaMigration() async {
        let s = oldCache(#"["content://photo.jpg"]"#)
        await s.saveMedia(setlistId: "a1", media: [photo("content://photo.jpg"), photo("content://new.jpg")])
        let cache = await s.load()
        XCTAssertEqual(["content://photo.jpg"], cache.photosBySetlist["a1"])
        XCTAssertEqual(2, cache.media()["a1"]?.count)
    }

    func testTwoRecordsOfOneNightMergeAndTheOlderIdWins() async {
        let s = store()
        let older = await s.createLocalGig(date: "25-06-2026", artist: "The Warning", venue: "Vaterland")
        await s.saveMedia(setlistId: older, media: [photo("content://photo1")])
        // The same night, found again by an import that didn't know it was here.
        await s.saveMedia(setlistId: "63de6d5b", media: [photo("content://photo2")])
        let newer = await s.load().gigForSetlist("63de6d5b")!.id

        let survivor = await s.mergeGigs(older, newer)
        XCTAssertEqual(older, survivor)
        let after = await s.load()
        XCTAssertEqual(1, after.gigs.count)
        XCTAssertEqual("63de6d5b", after.setlistIdFor(older))
        XCTAssertEqual(["content://photo1", "content://photo2"], after.media()["63de6d5b"]?.map(\.ref))
    }

    // MARK: - Deleting a night this app minted (#172)

    /// A **Gig** an **Act** became, taken back off — the mistap path. Two refusals, and
    /// both are the point: a night that has taken a setlist.fm id is not ours to delete,
    /// and a night with **Media** on it is not a mistap.
    func testANightThisAppMintedGoesEntirelyIncludingWhatWasKeyedByIt() async {
        let store = TimelineStore(file: tempFile(contents: "{}"))
        let id = await store.createLocalGig(date: "06-08-2026", artist: "Velvet Ditch", venue: "")
        await store.saveAttendance(setlistId: id, attendance: StoredAttendance(provenance: "checked_in"))
        await store.saveLog(setlistId: id, log: StoredLog(songs: ["Low Tide"]))

        let gone = await store.deleteGig(id)
        XCTAssertTrue(gone)
        let after = await store.load()
        XCTAssertNil(after.gigs[id])
        XCTAssertNil(after.gigPlanned[id])
        XCTAssertNil(after.gigAttendance[id])
        XCTAssertNil(after.gigLogs[id])
    }

    func testANightWithPhotographsOnItIsKeptBecauseThatIsNotAMistap() async {
        let store = TimelineStore(file: tempFile(contents: "{}"))
        let id = await store.createLocalGig(date: "06-08-2026", artist: "Velvet Ditch", venue: "")
        await store.saveMedia(setlistId: id, media: [
            StoredMedia(id: "m1", kind: StoredMedia.Kind.photo, ref: "file:///p.jpg"),
        ])

        let gone = await store.deleteGig(id)
        XCTAssertFalse(gone)
        let after = await store.load()
        XCTAssertNotNil(after.gigs[id])
        XCTAssertEqual(["m1"], after.gigMedia[id]?.map(\.id))
    }

    /// The vendor's record outlives our copy of it: a night that has adopted a
    /// setlist.fm id is no longer a local night to throw away.
    func testANightThatHasTakenASetlistFmIdIsNotOursToDelete() async {
        let store = TimelineStore(file: tempFile(contents: "{}"))
        let id = await store.createLocalGig(date: "06-08-2026", artist: "Velvet Ditch", venue: "")
        _ = await store.adoptSetlistId(gigId: id, setlistId: "fm-1")

        XCTAssertFalse(await store.deleteGig(id))
        let after = await store.load()
        XCTAssertNotNil(after.gigs[id])
    }
}
