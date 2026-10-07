import Foundation
import XCTest
@testable import StationToStation

@MainActor
final class TourAddGigEffectsTests: XCTestCase {
    private struct Online: TourConnectivity {
        func isOnline() -> Bool { true }
    }

    private var suite = ""
    private var store: UserDefaults!
    private var deleted: [String] = []

    override func setUp() async throws {
        suite = "TourAddGigEffectsTests.\(UUID().uuidString)"
        store = UserDefaults(suiteName: suite)!
        deleted = []
    }

    override func tearDown() async throws {
        store.removePersistentDomain(forName: suite)
    }

    private func makeTour(_ host: FakeState) -> (TourController, TourAddGigEffects) {
        let effects = TourAddGigEffects(store: store) { [unowned self] in self.deleted.append($0) }
        let controller = TourController(host: host, settings: Settings(store: store),
                                        connectivity: Online(),
                                        demoWorld: DemoWorldRegistry(parts: [effects]),
                                        addGig: effects)
        controller.start()
        controller.send(.acknowledged)
        return (controller, effects)
    }

    func testEachStepWaitsForItsOwnGestureFromCurtainToSwipeBack() {
        let host = FakeState()
        let (tour, effects) = makeTour(host)
        XCTAssertEqual(host.state.tourCoachMark, .curtain)
        tour.send(.roomOpened)
        XCTAssertEqual(host.state.tourStep, .s2)
        tour.send(.curtainPulled)
        XCTAssertEqual(host.state.tourCoachMark, .band)
        tour.gigAdded("too-early")
        XCTAssertEqual(host.state.tourStep, .s3)
        tour.send(.bandPicked)
        XCTAssertEqual(host.state.tourCoachMark, .addGig)
        tour.gigAdded("demo")
        XCTAssertEqual(host.state.tourCoachMark, .openRoom)
        tour.send(.swipedBack)
        XCTAssertEqual(host.state.tourStep, .s5)
        tour.send(.roomOpened)
        XCTAssertEqual(host.state.tourCoachMark, .swipeBack)
        tour.send(.swipedBack)
        XCTAssertEqual(host.state.tourStep, .s7)
        XCTAssertEqual(effects.demoGigIds, ["demo"])
    }

    func testAGigAddedOutsideTheAddStepIsThePersonsOwn() {
        let host = FakeState()
        let (tour, effects) = makeTour(host)
        tour.gigAdded("mine")
        tour.send(.curtainPulled)
        tour.send(.bandPicked)
        tour.gigAdded("demo")
        tour.gigAdded("mine-too")
        XCTAssertEqual(effects.demoGigIds, ["demo"])
    }

    func testSkipDeletesOnlyTheDemoGigAndForgetsIt() {
        let host = FakeState()
        let (tour, effects) = makeTour(host)
        tour.send(.curtainPulled)
        tour.send(.bandPicked)
        tour.gigAdded("demo")
        tour.skip()
        XCTAssertEqual(deleted, ["demo"])
        XCTAssertEqual(effects.demoGigIds, [])
    }

    func testTheDemoGigIsRememberedAcrossLaunches() {
        let first = TourAddGigEffects(store: store) { _ in }
        first.record("demo")
        let relaunched = TourAddGigEffects(store: store) { [unowned self] in self.deleted.append($0) }
        relaunched.purge()
        XCTAssertEqual(deleted, ["demo"])
    }
}
