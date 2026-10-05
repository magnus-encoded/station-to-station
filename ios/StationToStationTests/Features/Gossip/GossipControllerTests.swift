import XCTest
@testable import StationToStation

@MainActor
final class GossipControllerTests: XCTestCase {

    private var store: TimelineStore!
    private var model: AppModel!

    override func setUp() async throws {
        // The AppModel reads the default timeline file, so the test writes there.
        try? FileManager.default.removeItem(at: TimelineStore.defaultFile)
        UserDefaults.standard.removeObject(forKey: "gossip.selectedGigId")
        GossipTransport.shared.resumeParticipation()
        store = TimelineStore()
        model = AppModel()
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: TimelineStore.defaultFile)
        UserDefaults.standard.removeObject(forKey: "gossip.selectedGigId")
        GossipTransport.shared.resumeParticipation()
    }

    private func checkedInTonight() async -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "dd-MM-yyyy"
        let gigId = await store.createLocalGig(date: formatter.string(from: Date()), artist: "Kvelertak", venue: "Rockefeller")
        await store.saveAttendance(setlistId: gigId, attendance: StoredAttendance(
            provenance: "checked_in", checkedInAt: Int64(Date().timeIntervalSince1970 * 1000)))
        return gigId
    }

    private func eventually(_ condition: () -> Bool, file: StaticString = #filePath, line: UInt = #line) async {
        for _ in 0..<200 where !condition() {
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTAssertTrue(condition(), file: file, line: line)
    }

    func testStoppingEndsTonightsParticipationAndResumingBringsItBack() async {
        let gigId = await checkedInTonight()

        model.resumeGossip()
        await eventually { self.model.state.gossipActiveUntil != nil }
        XCTAssertEqual(model.state.gossipActiveGig, gigId)
        XCTAssertEqual(model.state.gossipStoppedGigs, [])

        model.stopGossip()
        XCTAssertGreaterThan(GossipTransport.shared.stoppedAt, 0)
        await eventually { self.model.state.gossipActiveUntil == nil }
        XCTAssertNil(model.state.gossipActiveGig)
        XCTAssertEqual(model.state.gossipStoppedGigs, [gigId])

        model.resumeGossip()
        XCTAssertEqual(GossipTransport.shared.stoppedAt, 0)
        await eventually { self.model.state.gossipActiveUntil != nil }
        XCTAssertEqual(model.state.gossipActiveGig, gigId)
    }

    func testChoosingAGigStandsAtItByItsLocalIdAndClearsAStop() async {
        let gigId = await checkedInTonight()
        model.stopGossip()

        model.selectGossipGig(gigId)
        await eventually { GossipTransport.shared.selectedGigId == gigId }
        XCTAssertEqual(GossipTransport.shared.stoppedAt, 0)
        await eventually { self.model.state.gossipActiveGig == gigId }
        XCTAssertNotNil(model.state.gossipActiveUntil)
    }

    func testChoosingAGigThisPhoneDoesNotKnowChangesNothing() async {
        model.stopGossip()

        model.selectGossipGig("unknown")
        try? await Task.sleep(nanoseconds: 300_000_000)
        XCTAssertNil(GossipTransport.shared.selectedGigId)
        XCTAssertGreaterThan(GossipTransport.shared.stoppedAt, 0)
    }

    func testRefreshingPresenceRereadsWhatTheRowsDraw() async {
        let gigId = await checkedInTonight()

        model.refreshGossipPresence()
        await eventually { self.model.state.gossipActiveGig == gigId }
        XCTAssertNotNil(model.state.gossipEligibleUntil[gigId])
    }
}
