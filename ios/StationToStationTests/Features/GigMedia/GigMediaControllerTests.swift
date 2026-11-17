import XCTest
@testable import StationToStation

@MainActor
final class GigMediaControllerTests: XCTestCase {

    private let night = FmSetlist(id: "gigmedia-night", eventDate: "13-08-2026", artist: FmArtist(name: "A Band"))

    private var storeFile: URL!

    override func setUp() {
        super.setUp()
        storeFile = FileManager.default.temporaryDirectory
            .appendingPathComponent("gigmedia-\(UUID().uuidString).json")
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: storeFile)
        super.tearDown()
    }

    private func media(_ id: String, personal: Bool = false, offsets: [Int64] = []) -> StoredMedia {
        var m = StoredMedia(id: id, kind: StoredMedia.Kind.photo, ref: "ref-\(id)", personal: personal)
        m.songOffsets = offsets
        return m
    }

    private func subject(
        _ nightMedia: [StoredMedia] = [],
        markSelectedOwnership: @escaping (FmSetlist, StoredAttendance?) -> Void = { _, _ in }
    ) -> (GigMediaController, FakeState) {
        let fake = FakeState()
        fake.state.selectedSetlist = night
        fake.state.mediaBySetlist = [night.id: nightMedia]
        let controller = GigMediaController(host: fake, timelines: TimelineStore(file: storeFile),
                                            markSelectedOwnership: markSelectedOwnership)
        return (controller, fake)
    }

    func testSongOffsetsArePaddedAndTruncatedToTheSetlistAsItIsNow() {
        let (gigMedia, _) = subject([media("a", offsets: [10, 20, 30])])

        XCTAssertEqual(gigMedia.songOffsets(mediaId: "a", songCount: 2), [10, 20])
        XCTAssertEqual(gigMedia.songOffsets(mediaId: "a", songCount: 4), [10, 20, 30, notStamped])
        XCTAssertEqual(gigMedia.songOffsets(mediaId: nil, songCount: 2), [notStamped, notStamped])
    }

    func testStampingASongMovesOnlyThatSong() {
        let (gigMedia, fake) = subject([media("a", offsets: [10, 20, 30])])

        gigMedia.stampSong(mediaId: "a", index: 1, atMs: 25, songCount: 3)

        XCTAssertEqual(fake.state.mediaBySetlist[night.id]?.first?.songOffsets, [10, 25, 30])
    }

    func testAStampPastTheEndOfTheSetlistIsIgnored() {
        let (gigMedia, fake) = subject([media("a", offsets: [10])])

        gigMedia.stampSong(mediaId: "a", index: 5, atMs: 25, songCount: 1)

        XCTAssertEqual(fake.state.mediaBySetlist[night.id]?.first?.songOffsets, [10])
    }

    func testMovingIntoTheVaultLandsAtTheEndOfItsRun() {
        let (gigMedia, fake) = subject([media("a"), media("b"), media("v", personal: true)])

        gigMedia.moveMedia("a", to: .vault)

        let moved = fake.state.mediaBySetlist[night.id] ?? []
        XCTAssertEqual(moved.map(\.id), ["b", "v", "a"])
        XCTAssertEqual(moved.map(\.personal), [false, true, true])
    }

    func testRemovingDropsTheItemAtOnce() {
        let (gigMedia, fake) = subject([media("a"), media("b")])

        gigMedia.removeMedia(media("a"))

        XCTAssertEqual(fake.state.mediaBySetlist[night.id]?.map(\.id), ["b"])
    }

    func testNothingChangesWithoutAnOpenNight() {
        let (gigMedia, fake) = subject([media("a")])
        fake.state.selectedSetlist = nil

        gigMedia.moveMedia("a", to: .vault)
        gigMedia.removeMedia(media("a"))
        gigMedia.attachMedia(assetIds: ["x"], to: .shared)

        XCTAssertEqual(fake.state.mediaBySetlist[night.id]?.map(\.id), ["a"])
        XCTAssertNil(fake.state.error)
    }

    func testAttachingOnlyWhatIsAlreadyAttachedDoesNothing() async throws {
        let (gigMedia, fake) = subject([media("a")])

        gigMedia.attachMedia(assetIds: ["ref-a"], to: .shared)
        try await Task.sleep(nanoseconds: 200_000_000)

        XCTAssertEqual(fake.state.mediaBySetlist[night.id]?.map(\.id), ["a"])
        XCTAssertNil(fake.state.error)
    }

    func testOpeningANightClearsItsSuggestionsAndAttendanceThenReadsTheStore() async throws {
        var owned: [String] = []
        let (gigMedia, fake) = subject(markSelectedOwnership: { setlist, _ in owned.append(setlist.id) })
        await TimelineStore(file: storeFile).saveMedia(setlistId: night.id, media: [media("a")])
        fake.state.gigMediaSuggestions = ["stale"]
        fake.state.selectedAttendance = StoredAttendance()

        gigMedia.loadGigMedia(night)

        XCTAssertEqual(fake.state.gigMediaSuggestions, [])
        XCTAssertNil(fake.state.selectedAttendance)
        for _ in 0..<50 where owned.isEmpty {
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        XCTAssertEqual(fake.state.mediaBySetlist[night.id]?.map(\.id), ["a"])
        XCTAssertEqual(owned, [night.id])
    }

    func testANightClosedBeforeTheStoreAnswersIsLeftAlone() async throws {
        var owned: [String] = []
        let (gigMedia, fake) = subject(markSelectedOwnership: { setlist, _ in owned.append(setlist.id) })
        await TimelineStore(file: storeFile).saveMedia(setlistId: night.id, media: [media("a")])

        gigMedia.loadGigMedia(night)
        fake.state.selectedSetlist = nil
        try await Task.sleep(nanoseconds: 300_000_000)

        XCTAssertEqual(fake.state.mediaBySetlist[night.id]?.map(\.id), [])
        XCTAssertEqual(owned, [])
    }
}
