import CoreLocation
import EventKit
import Photos
import XCTest
@testable import StationToStation

@MainActor
final class TourFinalPurgeTests: XCTestCase {
    private struct Online: TourConnectivity {
        func isOnline() -> Bool { true }
    }
    private var suite = ""
    private var defaults: UserDefaults!
    private var file: URL!
    private var timelines: TimelineStore!
    private var thumbnails: [String] = []

    override func setUp() async throws {
        suite = "TourFinalPurgeTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)!
        file = FileManager.default.temporaryDirectory.appendingPathComponent("\(suite).json")
        timelines = TimelineStore(file: file)
        thumbnails = []
    }

    override func tearDown() async throws {
        thumbnails.forEach { PhotoLibrary.deleteThumbnails($0) }
        defaults.removePersistentDomain(forName: suite)
        try? FileManager.default.removeItem(at: file)
    }

    private func eventually(_ condition: () async -> Bool) async {
        for _ in 0..<200 {
            if await condition() { return }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTFail("The Demo world purge did not settle")
    }

    func testS20RemovesEveryDemoRecordAndKeepsRealDataPlaylistsAndPermissions() async throws {
        let host = FakeState()
        let settings = Settings(store: defaults)
        let spotify = TourSpotifyStub()
        let real = localGigSetlist(gigId: "real", artist: "Real band", date: "01-01-2020", venue: "Real room", city: "")
        var demo = localGigSetlist(gigId: "demo", artist: "Demo band", date: "01-01-2030", venue: "Demo room", city: "")
        demo.artist?.mbid = "demo-band-id"
        let realContact = Friend(setlistfm: "", name: "Real contact", publicKey: "real-contact-key")
        settings.saveFriends([realContact])
        host.state.friends = [realContact]
        host.state.mySetlistFmUser = "my-real-account"
        host.state.plannedGigs = [real, demo]
        host.state.timelineShows = [real, demo]
        host.state.selectedSetlist = demo
        await timelines.savePlanned(real)
        await timelines.savePlanned(demo)
        let realLog = StoredLog(songs: ["Real song"], closed: true, enteredAt: [7])
        await timelines.saveLog(setlistId: real.id, log: realLog)
        let realAdmission = StoredAdmission(payload: Data("real-ticket".utf8).base64EncodedString(), symbology: qrSymbology)
        host.state.attendanceByGig[real.id] = await timelines.attachAdmissions(setlistId: real.id, admissions: [realAdmission])
        let realFact = GossipEnvelope(id: "real-fact", gigId: real.id, scope: "real-scope", author: "real-author",
                                      createdAt: 1, expiresAt: 2, kind: "log", line: 0, text: "Real news")
        host.state.publicGossip.facts[realFact.id] = realFact
        await timelines.updatePublicGossip { $0.facts[realFact.id] = realFact }
        host.state.showsByFriend[realContact.laneKey] = [real]
        await timelines.save(shows: [realContact.laneKey: [real], host.state.mySetlistFmUser: [real, demo]])
        let realMedia = StoredMedia(id: "\(suite)-real-photo", ref: "real-library-asset", personal: true)
        let ownSelfie = StoredMedia(id: "\(suite)-demo-selfie", ref: "selfie-library-asset", personal: true)
        let friendSelfie = StoredMedia(id: "\(suite)-friend-selfie", from: "demo-contact", personal: false)
        let demoMedia = [ownSelfie, friendSelfie]
        await timelines.saveMedia(setlistId: real.id, media: [realMedia])
        await timelines.saveMedia(setlistId: demo.id, media: demoMedia)
        host.state.mediaBySetlist = [real.id: [realMedia], demo.id: demoMedia]
        for item in [realMedia] + demoMedia {
            thumbnails.append(item.id)
            try Data("thumbnail".utf8).write(to: Thumbnails.gridFile(item.id))
        }
        let realPlaylist = StoredPlaylist(url: "https://open.spotify.com/playlist/real", name: "Real playlist", trackCount: 3)
        let demoPlaylist = StoredPlaylist(url: "https://open.spotify.com/playlist/keepsake", name: "Kept playlist", trackCount: 2)
        await timelines.save(playlists: [real.id: realPlaylist, demo.id: demoPlaylist])
        host.state.playlistsBySetlist = [real.id: [realPlaylist], demo.id: [demoPlaylist]]
        await timelines.markCalendarAdded(gigId: real.id, eventId: "real-event")
        await timelines.markCalendarAdded(gigId: demo.id, eventId: "demo-event")
        host.state.calendarEventByGig = [real.id: "real-event", demo.id: "demo-event"]
        var calendarEvents: Set<String> = ["real-event", "demo-event"]
        var deletedThumbnails: [String] = []
        let fmBody = Data(#"{"total":1,"setlist":[{"id":"recent","sets":{"set":[{"song":[{"name":"Demo opener"},{"name":"Gap fill"}]}]}}]}"#.utf8)
        let fm = SetlistFmClient(keySource: { SetlistFmKey(key: "test", shared: false) },
                                 transport: { _, _ in SetlistFmResponse(status: 200, body: fmBody) }, sleep: { _ in })
        let gig = GigController(host: host, timelines: timelines, setlistFm: fm, location: DeviceLocation(),
                                gossip: GossipController(host: host, timelines: timelines))
        let addGig = TourAddGigEffects(store: defaults) { gig.deleteGig($0, keepingPlaylists: true) }
        addGig.record(demo.id)
        let friend = TourMeetFriendEffects(store: defaults, friendName: { "Character name from data" },
            demoGig: { demo }, locate: { nil }, placeVenue: { _ in },
            addContact: { contact in
                host.state.friends = withFriend(host.state.friends, contact)
                settings.saveFriends(host.state.friends)
            }, landNights: { key, nights, withdrawn in
                let lane = Friend(setlistfm: "", publicKey: key).laneKey
                let (held, _) = await self.timelines.mergeContactNights(lane, nights, withdrawn)
                if let held { host.state.showsByFriend[lane] = held }
            }, removeContact: { contact in
                host.state.friends.removeAll { $0.laneKey == contact.laneKey }
                settings.saveFriends(host.state.friends)
            }, importTicket: { ticket in
                host.state.attendanceByGig[demo.id] = await self.timelines.attachAdmissions(
                    setlistId: demo.id, admissions: ticket.admissions.map { StoredAdmission($0) })
            })
        _ = await friend.exchange()
        await friend.importDemoTicket()
        let contactKey = try XCTUnwrap(friend.contactKey)
        let night = TourNightArrivesEffects(store: defaults, demoGig: { demo },
                                            deleteCalendarEvent: { calendarEvents.remove($0) })
        night.recordCalendarEvent("demo-event")
        night.advance(to: .after)
        let log = TourLogEffects(host: host, store: defaults, setlistFm: fm, musicBrainz: MusicBrainzClient(),
                                  friendKey: { friend.contactKey }, characterLine: { _ in "Character gossip from data" })
        log.writeLog(demo.id, log: StoredLog(songs: ["Demo opener", ""]), reply: false, now: 10)
        let delivered = await log.deliverGossip(for: demo, now: 11)
        XCTAssertTrue(delivered)
        let selfie = TourSelfieEffects(host: host, store: defaults, timelines: timelines,
                                       demoGigIds: { addGig.demoGigIds }, friendKey: { friend.contactKey },
                                       selfie: { nil }, characterLine: { "Character selfie from data" },
                                       deleteThumbnails: { id in
                                           deletedThumbnails.append(id)
                                           PhotoLibrary.deleteThumbnails(id)
                                       })
        selfie.attachment(for: demo.id, completed: {})(demoMedia)
        let playlist = PlaylistController(host: host, spotify: spotify, timelines: timelines,
                                          loadGigMedia: { _ in }, loadGigLog: { _ in }, addFriend: { _ in })
        let ending = TourSpotifyEffects(host: host, store: defaults, settings: settings, playlist: playlist, login: spotify,
                                        characterLine: { $0 == "playlist.title" ? "Title from data" : "Line from data" })
        settings.saveTokens(access: "held-access", refresh: "held-refresh", expiresIn: 3600,
                            scope: "playlist-modify-public ugc-image-upload")
        host.state.spotifyConnected = true
        host.state.grantedScope = settings.grantedScope
        host.state.coverPermissionGranted = true
        let photoPermission = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        let calendarPermission = EKEventStore.authorizationStatus(for: .event)
        let locationPermission = CLLocationManager().authorizationStatus
        let scope = settings.grantedScope
        let before = await timelines.load()
        let realId = try XCTUnwrap(before.gigForSetlist(real.id)?.id ?? before.gigs[real.id]?.id)
        let demoId = try XCTUnwrap(before.gigForSetlist(demo.id)?.id ?? before.gigs[demo.id]?.id)
        XCTAssertFalse(before.gigAttendance[demoId]?.admissions.isEmpty ?? true)
        XCTAssertFalse(log.gossip(demo.id).isEmpty)
        settings.saveTourState(TourState(currentStep: .s19))
        let world = DemoWorldRegistry(parts: [selfie, log, night, friend, addGig])
        let tour = TourController(host: host, settings: settings, connectivity: Online(), demoWorld: world,
                                  addGig: addGig, meetFriend: friend, night: night, log: log, selfie: selfie, spotify: ending)
        tour.start()
        await tour.exportSpotify().value
        await eventually {
            let cache = await self.timelines.load()
            return cache.gigs[demoId] == nil && settings.friends == [realContact]
                && Set(deletedThumbnails) == Set(demoMedia.map(\.id))
        }
        let after = await timelines.load()
        XCTAssertEqual(tour.state.currentStep, .s20)
        XCTAssertTrue(tour.state.finished)
        XCTAssertTrue(settings.tourState.finished)
        XCTAssertNil(host.state.tourCoachMark)
        XCTAssertFalse(tour.spotifyRetryPending)
        XCTAssertNil(after.gigs[demoId])
        XCTAssertNil(after.gigPlanned[demoId])
        XCTAssertNil(after.gigAttendance[demoId])
        XCTAssertNil(after.gigLogs[demoId])
        XCTAssertNil(after.gigMedia[demoId])
        XCTAssertNil(after.gigCalendarEvent[demoId])
        XCTAssertTrue(after.shows.values.flatMap { $0 }.allSatisfy { $0.id != demo.id })
        XCTAssertTrue(addGig.demoGigIds.isEmpty)
        XCTAssertNil(friend.contactKey)
        XCTAssertTrue(friend.demoNights.isEmpty)
        XCTAssertTrue(log.records.isEmpty)
        XCTAssertNil(night.demoNow)
        XCTAssertTrue(night.calendarEventIds.isEmpty)
        XCTAssertEqual(after.gigs[realId], before.gigs[realId])
        XCTAssertEqual(after.gigPlanned[realId]?.id, before.gigPlanned[realId]?.id)
        XCTAssertEqual(after.gigAttendance[realId], before.gigAttendance[realId])
        XCTAssertEqual(after.gigLogs[realId], realLog)
        XCTAssertEqual(after.gigMedia[realId], [realMedia])
        XCTAssertEqual(after.publicGossip.facts, before.publicGossip.facts)
        XCTAssertEqual(host.state.publicGossip.facts, [realFact.id: realFact])
        XCTAssertEqual(after.gigCalendarEvent[realId], "real-event")
        XCTAssertEqual(calendarEvents, ["real-event"])
        XCTAssertEqual(settings.friends, [realContact])
        XCTAssertEqual(host.state.friends, [realContact])
        XCTAssertEqual(after.shows[realContact.laneKey]?.map(\.id), [real.id])
        XCTAssertTrue((after.shows["key:\(contactKey)"] ?? []).isEmpty)
        XCTAssertEqual(after.gigPlaylists, before.gigPlaylists)
        XCTAssertEqual(host.state.playlistsBySetlist, [real.id: [realPlaylist], demo.id: [demoPlaylist]])
        XCTAssertEqual(ending.playlists.count, 1)
        XCTAssertEqual(ending.playlists.first?.url, "https://open.spotify.com/playlist/playlist1")
        XCTAssertTrue(spotify.covers.isEmpty)
        XCTAssertTrue(FileManager.default.fileExists(atPath: Thumbnails.gridFile(realMedia.id).path))
        for item in demoMedia { XCTAssertFalse(FileManager.default.fileExists(atPath: Thumbnails.gridFile(item.id).path)) }
        XCTAssertTrue(host.state.coverPermissionGranted)
        XCTAssertEqual(settings.grantedScope, scope)
        XCTAssertEqual(host.state.grantedScope, scope)
        XCTAssertTrue(host.state.spotifyConnected)
        XCTAssertEqual(PHPhotoLibrary.authorizationStatus(for: .readWrite), photoPermission)
        XCTAssertEqual(EKEventStore.authorizationStatus(for: .event), calendarPermission)
        XCTAssertEqual(CLLocationManager().authorizationStatus, locationPermission)
    }
}
