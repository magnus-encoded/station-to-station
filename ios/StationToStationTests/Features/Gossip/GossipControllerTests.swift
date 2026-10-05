import XCTest
@testable import StationToStation

@MainActor
final class GossipControllerTests: XCTestCase {

    private var file: URL!
    private var store: TimelineStore!
    private var host: FakeState!
    private var gossip: GossipController!

    override func setUp() async throws {
        file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        UserDefaults.standard.removeObject(forKey: "gossip.selectedGigId")
        GossipTransport.shared.resumeParticipation()
        store = TimelineStore(file: file)
        host = FakeState()
        gossip = GossipController(host: host, timelines: store)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: file)
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

        gossip.resumeGossip()
        await eventually { self.host.state.gossipActiveUntil != nil }
        XCTAssertEqual(host.state.gossipActiveGig, gigId)
        XCTAssertEqual(host.state.gossipStoppedGigs, [])

        gossip.stopGossip()
        XCTAssertGreaterThan(GossipTransport.shared.stoppedAt, 0)
        await eventually { self.host.state.gossipActiveUntil == nil }
        XCTAssertNil(host.state.gossipActiveGig)
        XCTAssertEqual(host.state.gossipStoppedGigs, [gigId])

        gossip.resumeGossip()
        XCTAssertEqual(GossipTransport.shared.stoppedAt, 0)
        await eventually { self.host.state.gossipActiveUntil != nil }
        XCTAssertEqual(host.state.gossipActiveGig, gigId)
    }

    func testChoosingAGigStandsAtItByItsLocalIdAndClearsAStop() async {
        let gigId = await checkedInTonight()
        gossip.stopGossip()

        gossip.selectGossipGig(gigId)
        await eventually { GossipTransport.shared.selectedGigId == gigId }
        XCTAssertEqual(GossipTransport.shared.stoppedAt, 0)
        await eventually { self.host.state.gossipActiveGig == gigId }
        XCTAssertNotNil(host.state.gossipActiveUntil)
    }

    func testChoosingAGigThisPhoneDoesNotKnowChangesNothing() async {
        gossip.stopGossip()

        gossip.selectGossipGig("unknown")
        try? await Task.sleep(nanoseconds: 300_000_000)
        XCTAssertNil(GossipTransport.shared.selectedGigId)
        XCTAssertGreaterThan(GossipTransport.shared.stoppedAt, 0)
    }

    func testRefreshingPresenceRereadsWhatTheRowsDraw() async {
        let gigId = await checkedInTonight()

        gossip.refreshGossipPresence()
        await eventually { self.host.state.gossipActiveGig == gigId }
        XCTAssertNotNil(host.state.gossipEligibleUntil[gigId])
    }
}
