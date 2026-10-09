import Foundation
import XCTest
@testable import StationToStation

@MainActor
final class ContextHintsTests: XCTestCase {
    private let now = ISO8601DateFormatter().date(from: "2026-10-07T20:00:00Z")!
    private var lastYear: Date { now.addingTimeInterval(-365 * 86_400) }

    private func due(_ hint: ContextHint, seen: Set<String> = [], tourRunning: Bool = false) -> Bool {
        contextHintDue(hint, seen: seen, tourRunning: tourRunning)
    }

    func testLongPress() {
        let cases: [(rows: Int, seen: Set<String>, running: Bool, expect: Bool)] = [
            (0, [], false, false),
            (1, [], false, false),
            (2, [], false, true),
            (5, [], false, true),
            (2, ["longPress"], false, false),
            (2, [], true, false),
        ]
        for c in cases {
            XCTAssertEqual(due(.longPress(editableRows: c.rows), seen: c.seen, tourRunning: c.running), c.expect, "\(c)")
        }
    }

    func testPullDownIsOneHintPerPlace() {
        let cases: [(place: HintPlace, seen: Set<String>, running: Bool, expect: Bool)] = [
            (.room, [], false, true),
            (.programme, [], false, true),
            (.room, ["pullDown.room"], false, false),
            (.programme, ["pullDown.room"], false, true),
            (.room, [], true, false),
        ]
        for c in cases {
            XCTAssertEqual(due(.pullDown(c.place), seen: c.seen, tourRunning: c.running), c.expect, "\(c)")
        }
    }

    func testLegendTap() {
        let cases: [(hiding: Bool, seen: Set<String>, running: Bool, expect: Bool)] = [
            (true, [], false, true),
            (false, [], false, false),
            (true, ["legendTap"], false, false),
            (true, [], true, false),
        ]
        for c in cases {
            XCTAssertEqual(due(.legendTap(hiding: c.hiding), seen: c.seen, tourRunning: c.running), c.expect, "\(c)")
        }
    }

    func testFlyoverOnlyWhenThereIsSomethingToSee() {
        let cases: [(night: Date?, songs: Int, photos: Int, seen: Set<String>, running: Bool, expect: Bool)] = [
            (lastYear, 12, 3, [], false, true),
            (lastYear, 0, 3, [], false, false),
            (lastYear, 12, 0, [], false, false),
            (lastYear, 0, 0, [], false, false),
            (now, 12, 3, [], false, false),
            (nil, 12, 3, [], false, false),
            (lastYear, 12, 3, ["flyover"], false, false),
            (lastYear, 12, 3, [], true, false),
        ]
        for c in cases {
            let hint = ContextHint.flyover(night: c.night, now: now, songs: c.songs, photos: c.photos)
            XCTAssertEqual(due(hint, seen: c.seen, tourRunning: c.running), c.expect, "\(c)")
        }
    }

    func testProgramme() {
        let cases: [(festivals: Int, seen: Set<String>, running: Bool, expect: Bool)] = [
            (0, [], false, false),
            (1, [], false, true),
            (3, [], false, true),
            (1, ["programme"], false, false),
            (1, [], true, false),
        ]
        for c in cases {
            XCTAssertEqual(due(.programme(festivals: c.festivals), seen: c.seen, tourRunning: c.running), c.expect, "\(c)")
        }
    }

    // MARK: Showing

    private struct Offline: TourConnectivity {
        func isOnline() -> Bool { false }
    }

    private struct Online: TourConnectivity {
        func isOnline() -> Bool { true }
    }

    private func makeHints(online: Bool) -> (FakeState, TourController, TourHintEffects, Settings, String) {
        let suite = "ContextHintsTests.\(UUID().uuidString)"
        let settings = Settings(store: UserDefaults(suiteName: suite)!)
        let host = FakeState()
        let tour = TourController(host: host, settings: settings,
                                  connectivity: online ? Online() as TourConnectivity : Offline(),
                                  demoWorld: EmptyDemoWorld())
        tour.start()
        return (host, tour, TourHintEffects(host: host, tour: tour), settings, suite)
    }

    func testHintsWaitWhileTheTourIsOffered() {
        let (host, _, hints, settings, suite) = makeHints(online: false)
        defer { UserDefaults().removePersistentDomain(forName: suite) }
        host.state.tourUpgradePrompt = true
        hints.offer(.pullDown(.room))
        XCTAssertNil(host.state.contextHint)
        XCTAssertTrue(settings.tourState.seenHints.isEmpty)
        host.state.tourUpgradePrompt = false
        hints.offer(.pullDown(.room))
        XCTAssertEqual(host.state.contextHint, .pullDown(.room))
    }

    func testAHintShowsOnceAndStaysSeenAcrossLaunches() {
        let (host, _, hints, settings, suite) = makeHints(online: false)
        defer { UserDefaults().removePersistentDomain(forName: suite) }
        hints.offer(.legendTap(hiding: true))
        XCTAssertEqual(host.state.contextHint, .legendTap(hiding: true))
        hints.dismiss()
        hints.offer(.legendTap(hiding: true))
        XCTAssertNil(host.state.contextHint)
        XCTAssertEqual(settings.tourState.seenHints, ["legendTap"])
    }

    func testOneHintAtATime() {
        let (host, _, hints, _, suite) = makeHints(online: false)
        defer { UserDefaults().removePersistentDomain(forName: suite) }
        hints.offer(.pullDown(.room))
        hints.offer(.legendTap(hiding: true))
        XCTAssertEqual(host.state.contextHint, .pullDown(.room))
        hints.dismiss()
        hints.offer(.legendTap(hiding: true))
        XCTAssertEqual(host.state.contextHint, .legendTap(hiding: true))
    }

    func testNoHintsWhileTheTourRunsAndHintsAfterSkipping() {
        let (host, tour, hints, _, suite) = makeHints(online: true)
        defer { UserDefaults().removePersistentDomain(forName: suite) }
        XCTAssertEqual(host.state.tourStep, .s1)
        hints.offer(.pullDown(.room))
        XCTAssertNil(host.state.contextHint)
        tour.skip()
        hints.offer(.pullDown(.room))
        XCTAssertEqual(host.state.contextHint, .pullDown(.room))
    }
}
