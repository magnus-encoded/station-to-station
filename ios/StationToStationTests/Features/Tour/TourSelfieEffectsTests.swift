import UIKit
import XCTest
@testable import StationToStation

@MainActor
final class TourSelfieEffectsTests: XCTestCase {
    private struct Online: TourConnectivity {
        func isOnline() -> Bool { true }
    }

    private var suite = ""
    private var defaults: UserDefaults!
    private var file: URL!
    private var timelines: TimelineStore!
    private var host: FakeState!
    private var deleted: [String] = []
    private let demo = localGigSetlist(gigId: "demo", artist: "Band", date: "01-01-2030",
                                      venue: "Room", city: "")

    override func setUp() async throws {
        suite = "TourSelfieEffectsTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)!
        file = FileManager.default.temporaryDirectory.appendingPathComponent("\(suite).json")
        timelines = TimelineStore(file: file)
        host = FakeState()
        host.state.selectedSetlist = demo
        host.state.plannedGigs = [demo]
        deleted = []
        await timelines.savePlanned(demo)
    }

    override func tearDown() async throws {
        let cache = await timelines.load()
        for item in cache.gigMedia.values.flatMap({ $0 }) { PhotoLibrary.deleteThumbnails(item.id) }
        defaults.removePersistentDomain(forName: suite)
        try? FileManager.default.removeItem(at: file)
    }

    private func makeFriend() -> TourMeetFriendEffects {
        TourMeetFriendEffects(store: defaults, friendName: { "Name from data" },
                              demoGig: { [unowned self] in self.demo }, locate: { nil },
                              placeVenue: { _ in }, addContact: { _ in }, landNights: { _, _, _ in },
                              removeContact: { _ in }, importTicket: { _ in })
    }

    private func imageData() -> Data? {
        let size = CGSize(width: 4, height: 4)
        return UIGraphicsImageRenderer(size: size).image { context in
            UIColor.red.setFill()
            context.fill(CGRect(origin: .zero, size: size))
        }.pngData()
    }

    private func makeEffects(_ addGig: TourAddGigEffects, friend: TourMeetFriendEffects,
                             hasSelfie: Bool = true) -> TourSelfieEffects {
        TourSelfieEffects(host: host, store: defaults, timelines: timelines,
                          demoGigIds: { addGig.demoGigIds }, friendKey: { friend.contactKey },
                          selfie: { [unowned self] in hasSelfie ? self.imageData() : nil },
                          characterLine: { "Line from data" }, deleteThumbnails: { [unowned self] id in
            self.deleted.append(id)
            PhotoLibrary.deleteThumbnails(id)
        })
    }

    private func makeTour(hasSelfie: Bool = true) async -> (TourController, TourSelfieEffects, TourAddGigEffects, TourMeetFriendEffects) {
        Settings(store: defaults).saveTourState(TourState(currentStep: .s18))
        let addGig = TourAddGigEffects(store: defaults) { _ in }
        addGig.record(demo.id)
        let friend = makeFriend()
        _ = await friend.exchange()
        let effects = makeEffects(addGig, friend: friend, hasSelfie: hasSelfie)
        let tour = TourController(host: host, settings: Settings(store: defaults), connectivity: Online(),
                                  demoWorld: DemoWorldRegistry(parts: [effects]), addGig: addGig,
                                  meetFriend: friend, selfie: effects)
        tour.start()
        return (tour, effects, addGig, friend)
    }

    private func makeMedia(_ id: String, personal: Bool = false) -> StoredMedia {
        StoredMedia(id: "\(suite)-\(id)", ref: "asset-\(id)", personal: personal)
    }

    private func eventually(_ condition: () async -> Bool) async {
        for _ in 0..<200 {
            if await condition() { return }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTFail("condition not reached")
    }

    func testS18WaitsForTheReturnFromPhotosBeforeEitherVisibilityAndResumesAfterRelaunch() async {
        for band in [Band.vault, .shared] {
            let (tour, effects, addGig, friend) = await makeTour(hasSelfie: false)
            let visibility: TourMediaVisibility = band == .vault ? .private : .shared
            tour.send(.mediaAdded(visibility: visibility))
            XCTAssertEqual(tour.state.currentStep, .s18)
            XCTAssertFalse(tour.state.returnedFromPhotos)
            tour.returnedFromPhotos("another-gig")
            XCTAssertFalse(tour.state.returnedFromPhotos)
            tour.returnedFromPhotos(demo.id)
            XCTAssertEqual(tour.state.currentStep, .s18)
            XCTAssertTrue(tour.state.returnedFromPhotos)
            let relaunched = TourController(host: host, settings: Settings(store: defaults), connectivity: Online(),
                                            demoWorld: effects, addGig: addGig, meetFriend: friend, selfie: effects)
            relaunched.start()
            XCTAssertEqual(host.state.tourCoachMark, .selfie)
            relaunched.send(.mediaAdded(visibility: visibility))
            XCTAssertEqual(relaunched.state.currentStep, .s19)
            XCTAssertEqual(host.state.tourCoachMark, .spotify)
        }
    }

    func testARealAttachUsesTheChosenBandAndDeliversOneReceivedSelfieFromTheDemoContact() async throws {
        for band in [Band.vault, .shared] {
            let (tour, effects, _, friend) = await makeTour()
            let picked = makeMedia(band == .vault ? "vault" : "shared")
            let media = GigMediaController(host: host, timelines: timelines,
                                           attachAssets: { _ in ([picked], 0) }, markSelectedOwnership: { _, _ in })
            media.onMediaAttached = { tour.mediaAttachment(for: $0, to: $1) }
            tour.returnedFromPhotos(demo.id)
            media.attachMedia(assetIds: [picked.ref], to: band)
            await eventually { self.host.state.gigMedia.contains { $0.from == friend.contactKey } }
            XCTAssertEqual(tour.state.currentStep, .s19)
            let stored = await timelines.load().media()[demo.id] ?? []
            let own = try XCTUnwrap(stored.first { $0.id == picked.id })
            XCTAssertEqual(own.personal, band == .vault)
            XCTAssertNil(own.from)
            let received = try XCTUnwrap(stored.first { $0.from == friend.contactKey })
            XCTAssertFalse(received.personal)
            XCTAssertEqual(received.kind, StoredMedia.Kind.photo)
            XCTAssertEqual(received.text, "Line from data")
            XCTAssertTrue(FileManager.default.fileExists(atPath: Thumbnails.gridFile(received.id).path))
            XCTAssertTrue(FileManager.default.fileExists(atPath: Thumbnails.cacheFile(received.id).path))
            await effects.deliverFriendSelfie(for: demo, now: 2)
            let twice = await timelines.load().media()[demo.id] ?? []
            XCTAssertEqual(twice.filter { $0.from == friend.contactKey }.map(\.id), [received.id])
            effects.purge()
            await eventually { await self.timelines.load().media()[self.demo.id]?.isEmpty == true }
        }
    }

    func testEmptyPicksAndFailedReadsSendNoMediaAddedEvent() async {
        let (tour, _, _, _) = await makeTour(hasSelfie: false)
        var reads = 0
        var attached = 0
        let media = GigMediaController(host: host, timelines: timelines, attachAssets: { ids in
            reads += 1
            return ([], ids.count)
        }, markSelectedOwnership: { _, _ in })
        media.onMediaAttached = { gigId, band in
            let complete = tour.mediaAttachment(for: gigId, to: band)
            return { fresh in attached += 1; complete(fresh) }
        }
        tour.returnedFromPhotos(demo.id)
        media.attachMedia(assetIds: [], to: .vault)
        XCTAssertEqual(reads, 0)
        media.attachMedia(assetIds: ["unreadable"], to: .shared)
        await eventually { self.host.state.error != nil }
        XCTAssertEqual(reads, 1)
        XCTAssertEqual(attached, 0)
        XCTAssertEqual(tour.state.currentStep, .s18)
        XCTAssertTrue(tour.state.returnedFromPhotos)
        XCTAssertTrue(host.state.gigMedia.isEmpty)
        let cache = await timelines.load()
        XCTAssertTrue(cache.gigMedia.values.allSatisfy(\.isEmpty))
    }

    func testAnAttachBeforeReturningKeepsS18AndStillRecordsTheSelfieForPurge() async {
        let (tour, effects, _, _) = await makeTour(hasSelfie: false)
        let picked = makeMedia("early")
        let media = GigMediaController(host: host, timelines: timelines,
                                       attachAssets: { _ in ([picked], 0) }, markSelectedOwnership: { _, _ in })
        var attached = false
        media.onMediaAttached = { gigId, band in
            let complete = tour.mediaAttachment(for: gigId, to: band)
            return { fresh in complete(fresh); attached = true }
        }
        media.attachMedia(assetIds: [picked.ref], to: .shared)
        await eventually { attached }
        XCTAssertEqual(tour.state.currentStep, .s18)
        XCTAssertFalse(tour.state.returnedFromPhotos)
        effects.purge()
        await eventually { await self.timelines.load().media()[self.demo.id]?.isEmpty == true }
    }

    func testMissingCharacterSelfieOrContactKeyCreatesNoReceivedMedia() async {
        let (_, effects, addGig, friend) = await makeTour(hasSelfie: false)
        await effects.deliverFriendSelfie(for: demo, now: 1)
        friend.purge()
        let noContact = makeEffects(addGig, friend: friend)
        await noContact.deliverFriendSelfie(for: demo, now: 1)
        let cache = await timelines.load()
        XCTAssertTrue(cache.gigMedia.isEmpty)
        XCTAssertTrue(deleted.isEmpty)
    }

    func testAnAttachFinishingAfterSkipIsPurgedWithoutAdvancingTheTour() async {
        let (tour, _, _, _) = await makeTour(hasSelfie: false)
        let picked = makeMedia("late")
        var read: CheckedContinuation<(media: [StoredMedia], failed: Int), Never>?
        let media = GigMediaController(host: host, timelines: timelines, attachAssets: { _ in
            await withCheckedContinuation { read = $0 }
        }, markSelectedOwnership: { _, _ in })
        media.onMediaAttached = { tour.mediaAttachment(for: $0, to: $1) }
        tour.returnedFromPhotos(demo.id)
        media.attachMedia(assetIds: [picked.ref], to: .shared)
        await eventually { read != nil }
        tour.skip()
        read?.resume(returning: ([picked], 0))
        await eventually { self.deleted.contains(picked.id) }
        XCTAssertTrue(tour.state.finished)
        XCTAssertNil(host.state.tourCoachMark)
        XCTAssertTrue(host.state.gigMedia.isEmpty)
        let cache = await timelines.load()
        XCTAssertTrue(cache.gigMedia.values.allSatisfy(\.isEmpty))
    }

    func testAttachingOnARealGigLeavesTheTourWaitingAndTheMediaOutsideItsPurge() async {
        let (tour, effects, _, _) = await makeTour(hasSelfie: false)
        host.state.selectedSetlist = localGigSetlist(gigId: "real", artist: "Real band", date: "01-01-2030",
                                                     venue: "Room", city: "")
        let picked = makeMedia("real-attach")
        let media = GigMediaController(host: host, timelines: timelines,
                                       attachAssets: { _ in ([picked], 0) }, markSelectedOwnership: { _, _ in })
        media.onMediaAttached = { tour.mediaAttachment(for: $0, to: $1) }
        media.attachMedia(assetIds: [picked.ref], to: .shared)
        await eventually { await self.timelines.load().media()["real"] == [picked] }
        XCTAssertEqual(tour.state.currentStep, .s18)
        effects.purge()
        for _ in 0..<10 { await Task.yield() }
        let cache = await timelines.load()
        XCTAssertEqual(cache.media()["real"], [picked])
        XCTAssertEqual(host.state.gigMedia, [picked])
        XCTAssertTrue(deleted.isEmpty)
    }

    func testPurgeAfterRelaunchRemovesOnlyTourRecordsAndThumbnailsAndIsIdempotent() async throws {
        let (_, effects, addGig, friend) = await makeTour()
        let kept = makeMedia("kept")
        let elsewhere = makeMedia("elsewhere")
        let picked = makeMedia("selfie", personal: true)
        await timelines.saveMedia(setlistId: demo.id, media: [kept, picked])
        await timelines.saveMedia(setlistId: "real", media: [elsewhere])
        host.state.mediaBySetlist = [demo.id: [kept, picked], "real": [elsewhere]]
        effects.attachment(for: demo.id, completed: {})([picked])
        await effects.deliverFriendSelfie(for: demo, now: 1)
        let received = try XCTUnwrap(host.state.gigMedia.first { $0.from == friend.contactKey })
        let relaunched = makeEffects(addGig, friend: friend)
        await relaunched.deliverFriendSelfie(for: demo, now: 2)
        XCTAssertEqual(host.state.gigMedia.filter { $0.from == friend.contactKey }.map(\.id), [received.id])
        relaunched.purge()
        await eventually {
            let cache = await self.timelines.load()
            return cache.media()[self.demo.id] == [kept] && self.deleted.contains(received.id)
        }
        let cache = await timelines.load()
        XCTAssertEqual(cache.media()[demo.id], [kept])
        XCTAssertEqual(cache.media()["real"], [elsewhere])
        XCTAssertEqual(host.state.gigMedia, [kept])
        XCTAssertEqual(host.state.mediaBySetlist["real"], [elsewhere])
        XCTAssertEqual(Set(deleted), [picked.id, received.id])
        XCTAssertFalse(FileManager.default.fileExists(atPath: Thumbnails.gridFile(received.id).path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: Thumbnails.cacheFile(received.id).path))
        let firstPurge = deleted
        relaunched.purge()
        for _ in 0..<10 { await Task.yield() }
        XCTAssertEqual(deleted, firstPurge)
    }

    func testDemoMediaIsAbsentFromContactAndHandoverManifestsAndTheirByteSources() async {
        let (_, effects, _, _) = await makeTour()
        await timelines.saveMedia(setlistId: demo.id, media: [makeMedia("shared"), makeMedia("vault", personal: true)])
        let real = makeMedia("real")
        await timelines.saveMedia(setlistId: "real", media: [real])
        let cache = await timelines.load()
        XCTAssertEqual(contactManifest(cache, me: "me").media.count, 2)
        let safe = effects.mediaExchangeCache(cache)
        XCTAssertEqual(safe.gigMedia.values.flatMap({ $0 }).map(\.id), [real.id])
        XCTAssertEqual(contactManifest(safe, me: "me").media.map(\.id), [real.id])
        let device = deviceManifest(safe, allow: categoriesFor(contact: false), identities: Identities())
        XCTAssertEqual(device.media.map(\.id), [real.id])
    }
}
