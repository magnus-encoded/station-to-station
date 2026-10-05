import XCTest
@testable import StationToStation

@MainActor
final class GigMediaControllerTests: XCTestCase {

    private let night = FmSetlist(id: "gigmedia-night", eventDate: "13-08-2026", artist: FmArtist(name: "A Band"))

    private func media(_ id: String, personal: Bool = false, offsets: [Int64] = []) -> StoredMedia {
        var m = StoredMedia(id: id, kind: StoredMedia.Kind.photo, ref: "ref-\(id)", personal: personal)
        m.songOffsets = offsets
        return m
    }

    private func subject(_ nightMedia: [StoredMedia] = []) -> AppModel {
        let model = AppModel()
        model.state.selectedSetlist = night
        model.state.mediaBySetlist = [night.id: nightMedia]
        return model
    }

    func testSongOffsetsArePaddedAndTruncatedToTheSetlistAsItIsNow() {
        let model = subject([media("a", offsets: [10, 20, 30])])

        XCTAssertEqual(model.songOffsets(mediaId: "a", songCount: 2), [10, 20])
        XCTAssertEqual(model.songOffsets(mediaId: "a", songCount: 4), [10, 20, 30, notStamped])
        XCTAssertEqual(model.songOffsets(mediaId: nil, songCount: 2), [notStamped, notStamped])
    }

    func testStampingASongMovesOnlyThatSong() {
        let model = subject([media("a", offsets: [10, 20, 30])])

        model.stampSong(mediaId: "a", index: 1, atMs: 25, songCount: 3)

        XCTAssertEqual(model.state.mediaBySetlist[night.id]?.first?.songOffsets, [10, 25, 30])
    }

    func testAStampPastTheEndOfTheSetlistIsIgnored() {
        let model = subject([media("a", offsets: [10])])

        model.stampSong(mediaId: "a", index: 5, atMs: 25, songCount: 1)

        XCTAssertEqual(model.state.mediaBySetlist[night.id]?.first?.songOffsets, [10])
    }

    func testMovingIntoTheVaultLandsAtTheEndOfItsRun() {
        let model = subject([media("a"), media("b"), media("v", personal: true)])

        model.moveMedia("a", to: .vault)

        let moved = model.state.mediaBySetlist[night.id] ?? []
        XCTAssertEqual(moved.map(\.id), ["b", "v", "a"])
        XCTAssertEqual(moved.map(\.personal), [false, true, true])
    }

    func testRemovingDropsTheItemAtOnce() {
        let model = subject([media("a"), media("b")])

        model.removeMedia(media("a"))

        XCTAssertEqual(model.state.mediaBySetlist[night.id]?.map(\.id), ["b"])
    }

    func testNothingChangesWithoutAnOpenNight() {
        let model = subject([media("a")])
        model.state.selectedSetlist = nil

        model.moveMedia("a", to: .vault)
        model.removeMedia(media("a"))
        model.attachMedia(assetIds: ["x"], to: .shared)

        XCTAssertEqual(model.state.mediaBySetlist[night.id]?.map(\.id), ["a"])
        XCTAssertNil(model.state.error)
    }

    func testAttachingOnlyWhatIsAlreadyAttachedDoesNothing() async throws {
        let model = subject([media("a")])

        model.attachMedia(assetIds: ["ref-a"], to: .shared)
        try await Task.sleep(nanoseconds: 200_000_000)

        XCTAssertEqual(model.state.mediaBySetlist[night.id]?.map(\.id), ["a"])
        XCTAssertNil(model.state.error)
    }

    func testOpeningANightClearsItsSuggestionsAndAttendanceThenReadsTheStore() async throws {
        let model = subject()
        model.state.selectedSetlist = nil
        model.state.gigMediaSuggestions = ["stale"]
        model.state.selectedAttendance = StoredAttendance()

        model.selectSetlist(night)

        XCTAssertEqual(model.state.gigMediaSuggestions, [])
        XCTAssertNil(model.state.selectedAttendance)
        let stored = await TimelineStore().load().media()
        for _ in 0..<50 where model.state.mediaBySetlist != stored {
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        XCTAssertEqual(model.state.mediaBySetlist, stored)
    }
}
