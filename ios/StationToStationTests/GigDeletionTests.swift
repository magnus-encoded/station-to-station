import XCTest
@testable import StationToStation

final class GigDeletionTests: XCTestCase {
    private let me = "dizzi90"

    private func held(_ id: String) -> UiState {
        var s = UiState()
        s.plannedGigs = [FmSetlist(id: id), FmSetlist(id: "other")]
        s.timelineShows = [FmSetlist(id: id), FmSetlist(id: "other")]
        s.showsByFriend[me] = [FmSetlist(id: id), FmSetlist(id: "other")]
        for key in [id, "other"] {
            s.attendanceByGig[key] = StoredAttendance()
            s.mediaBySetlist[key] = [StoredMedia(id: "m-\(key)", ref: "asset/\(key)")]
            s.playlistsBySetlist[key] = [StoredPlaylist(url: "https://open.spotify.com/playlist/\(key)")]
            s.calendarEventByGig[key] = "event-\(key)"
        }
        return s
    }

    func testADeletedGigLeavesEveryListTheScreenHolds() {
        var s = held("gone")
        s.deleteGig("gone", mine: me)

        XCTAssertEqual(["other"], s.plannedGigs.map(\.id))
        XCTAssertEqual(["other"], s.timelineShows.map(\.id))
        XCTAssertEqual(["other"], s.showsByFriend[me]?.map(\.id))
        XCTAssertEqual(["other"], Array(s.attendanceByGig.keys))
        XCTAssertEqual(["other"], Array(s.mediaBySetlist.keys))
        XCTAssertEqual(["other"], Array(s.playlistsBySetlist.keys))
        XCTAssertEqual(["other"], Array(s.calendarEventByGig.keys))
    }

    func testDeletingTheOpenGigClosesItsRoomAndLog() {
        var s = held("gone")
        s.selectedSetlist = FmSetlist(id: "gone")
        s.gigLog.songs = ["Intro"]
        s.deleteGig("gone", mine: me)

        XCTAssertNil(s.selectedSetlist)
        XCTAssertEqual(StoredLog(), s.gigLog)
    }

    func testDeletingAnotherGigLeavesTheOpenRoomAlone() {
        var s = held("gone")
        s.selectedSetlist = FmSetlist(id: "other")
        s.deleteGig("gone", mine: me)

        XCTAssertEqual("other", s.selectedSetlist?.id)
    }

    private struct FakeStorage: GigStorage {
        let answer: GigDeletionOutcome
        func delete(_ gigId: String) async -> GigDeletionOutcome { answer }
    }

    @MainActor
    func testTheScreenIsDeletedFromOnlyAfterTheStorageDeletes() async {
        var onScreen = false
        let outcome = await deleteFromStorage("g", storage: FakeStorage(answer: .deleted)) { onScreen = true }

        XCTAssertEqual(.deleted, outcome)
        XCTAssertTrue(onScreen)
    }

    @MainActor
    func testAGigTheStorageKeepsStaysOnScreen() async {
        var onScreen = false
        let outcome = await deleteFromStorage("g", storage: FakeStorage(answer: .kept)) { onScreen = true }

        XCTAssertEqual(.kept, outcome)
        XCTAssertFalse(onScreen)
    }

    private func store(lane: String = "dizzi90", copies: CopyLog = CopyLog()) -> TimelineStore {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("timelines-\(UUID().uuidString).json")
        addTeardownBlock { try? FileManager.default.removeItem(at: file) }
        return TimelineStore(file: file, myAttendedList: { lane }, deleteLocalCopies: { copies.add($0.id) })
    }

    private final class CopyLog: @unchecked Sendable {
        private(set) var ids: [String] = []
        func add(_ id: String) { ids.append(id) }
    }

    func testDeletingFromTheLocalStoreDeletesTheLocalCopiesOfItsMedia() async {
        let copies = CopyLog()
        let store = store(copies: copies)
        let id = await store.createLocalGig(date: "25-09-2026", artist: "Big Thief", venue: "")
        await store.saveMedia(setlistId: id, media: [StoredMedia(id: "m1", ref: "asset/1"), StoredMedia(id: "m2", ref: "asset/2")])

        let outcome = await store.delete(id)

        XCTAssertEqual(.deleted, outcome)
        XCTAssertEqual(["m1", "m2"], copies.ids)
        let held = await store.load().gigs[id]
        XCTAssertNil(held)
    }

    func testDeletingAGigTheLocalStoreDoesNotHoldKeepsEverything() async {
        let copies = CopyLog()
        let outcome = await store(copies: copies).delete("nowhere")

        XCTAssertEqual(.kept, outcome)
        XCTAssertTrue(copies.ids.isEmpty)
    }

    func testDeletingFromTheLocalStoreTakesTheGigOutOfMyCachedAttendedList() async {
        let store = store()
        await store.save(shows: [me: [FmSetlist(id: "a"), FmSetlist(id: "b")]])
        let id = await store.createLocalGig(date: "25-09-2026", artist: "Big Thief", venue: "")
        _ = await store.adoptSetlistId(gigId: id, setlistId: "a")

        _ = await store.delete("a")

        let shows = await store.load().shows[me]
        XCTAssertEqual(["b"], shows?.map(\.id))
    }
}
