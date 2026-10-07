import Foundation
import XCTest
@testable import StationToStation

@MainActor
final class TourControllerTests: XCTestCase {
    private struct Connectivity: TourConnectivity {
        let online: Bool
        func isOnline() -> Bool { online }
    }

    private final class World: DemoWorld {
        var purges = 0
        var onPurge: (() -> Void)?
        func purge() {
            purges += 1
            onPurge?()
        }
    }

    private func withSettings(_ body: (Settings) throws -> Void) rethrows {
        let suite = "TourControllerTests.\(UUID().uuidString)"
        let store = UserDefaults(suiteName: suite)!
        defer { store.removePersistentDomain(forName: suite) }
        try body(Settings(store: store))
    }

    func testOfflineLaunchLeavesTheTourUnofferedForAnOnlineLaunch() {
        withSettings { settings in
            let host = FakeState()
            let world = World()
            TourController(host: host, settings: settings,
                           connectivity: Connectivity(online: false), demoWorld: world).start()
            XCTAssertFalse(settings.onboarded)
            XCTAssertFalse(host.state.onboarded)
            XCTAssertNil(host.state.tourStep)
            XCTAssertEqual(settings.tourState, TourState())
            XCTAssertEqual(world.purges, 0)

            let online = TourController(host: host, settings: settings,
                                        connectivity: Connectivity(online: true), demoWorld: world)
            online.start()
            XCTAssertTrue(settings.onboarded)
            XCTAssertTrue(host.state.onboarded)
            XCTAssertEqual(host.state.tourStep, .s1)
            XCTAssertEqual(host.state.tourCoachMark, .line)
            XCTAssertEqual(settings.tourState, online.state)
            online.start()
            XCTAssertEqual(host.state.tourStep, .s1)
        }
    }

    func testKilledTourRestoresTheStepAndCoachMarkEvenOffline() {
        withSettings { settings in
            let first = TourController(host: FakeState(), settings: settings,
                                       connectivity: Connectivity(online: true), demoWorld: World())
            first.start()
            first.send(.acknowledged)
            first.send(.curtainPulled)
            first.send(.bandPicked)
            let host = FakeState()
            host.state.onboarded = settings.onboarded
            let restored = TourController(host: host, settings: settings,
                                          connectivity: Connectivity(online: false), demoWorld: World())
            restored.start()
            XCTAssertEqual(restored.state, first.state)
            XCTAssertEqual(host.state.tourStep, .s4)
            XCTAssertEqual(host.state.tourCoachMark, .addGig)
            restored.dismissCoachMark()
            XCTAssertNil(host.state.tourCoachMark)
            restored.resume()
            XCTAssertEqual(host.state.tourCoachMark, .addGig)
        }
    }

    func testSkipPurgesTheDemoWorldAndPersistsFinishedState() {
        withSettings { settings in
            let host = FakeState()
            let world = World()
            let controller = TourController(host: host, settings: settings,
                                            connectivity: Connectivity(online: true), demoWorld: world)
            controller.start()
            controller.skip()
            XCTAssertEqual(world.purges, 1)
            XCTAssertNil(host.state.tourStep)
            XCTAssertNil(host.state.tourCoachMark)
            XCTAssertTrue(host.state.tourFinished)
            XCTAssertTrue(settings.tourState.finished)
            controller.skip()
            controller.resume()
            XCTAssertEqual(world.purges, 1)
        }
    }

    func testReplayPurgesBeforeShowingAFreshTourAndKeepsHintMemory() {
        withSettings { settings in
            var finished = TourState(currentStep: .s20, finished: true)
            finished.seenHints = ["rotate"]
            finished.bandLookedUp = true
            finished.demoTicketImported = true
            settings.saveTourState(finished)
            settings.setOnboarded()
            let host = FakeState()
            let world = World()
            let controller = TourController(host: host, settings: settings,
                                            connectivity: Connectivity(online: true), demoWorld: world)
            world.onPurge = {
                XCTAssertEqual(host.state.tourStep, .s20)
                XCTAssertNil(host.state.tourCoachMark)
            }
            controller.replay()
            XCTAssertEqual(world.purges, 1)
            XCTAssertEqual(host.state.tourStep, .s1)
            XCTAssertEqual(host.state.tourCoachMark, .line)
            XCTAssertFalse(host.state.tourFinished)
            XCTAssertFalse(controller.state.bandLookedUp)
            XCTAssertFalse(controller.state.demoTicketImported)
            XCTAssertEqual(controller.state.seenHints, ["rotate"])
            XCTAssertEqual(settings.tourState, controller.state)
        }
    }

    func testSkipAtTheTerminalStepPurgesAndClearsTheStep() {
        withSettings { settings in
            settings.saveTourState(TourState(currentStep: .s20, finished: true))
            let host = FakeState()
            let world = World()
            let controller = TourController(host: host, settings: settings,
                                            connectivity: Connectivity(online: true), demoWorld: world)
            controller.skip()
            XCTAssertEqual(world.purges, 1)
            XCTAssertNil(host.state.tourStep)
            XCTAssertTrue(host.state.tourFinished)
            XCTAssertEqual(settings.tourState, controller.state)
            controller.skip()
            XCTAssertEqual(world.purges, 1)
        }
    }

    func testAnInstallAlreadyOfferedTheTourDoesNotStartAnother() {
        withSettings { settings in
            settings.setOnboarded()
            let host = FakeState()
            let controller = TourController(host: host, settings: settings,
                                            connectivity: Connectivity(online: true), demoWorld: World())
            controller.start()
            XCTAssertNil(host.state.tourStep)
            XCTAssertNil(host.state.tourCoachMark)
        }
    }

    func testIgnoredEventsLeavePersistenceAndCoachMarkUntouched() {
        withSettings { settings in
            let host = FakeState()
            let controller = TourController(host: host, settings: settings,
                                            connectivity: Connectivity(online: true), demoWorld: World())
            controller.start()
            let before = settings.tourState
            controller.send(.gigAdded)
            XCTAssertEqual(settings.tourState, before)
            XCTAssertEqual(host.state.tourCoachMark, .line)
            XCTAssertTrue(host.failures.isEmpty)
        }
    }

    func testFinishingTheTourPurgesAndClearsTheCoachMark() {
        withSettings { settings in
            settings.saveTourState(TourState(currentStep: .s19))
            let host = FakeState()
            let world = World()
            let controller = TourController(host: host, settings: settings,
                                            connectivity: Connectivity(online: true), demoWorld: world)
            controller.start()
            controller.send(.spotifyDeclined)
            XCTAssertEqual(world.purges, 1)
            XCTAssertEqual(host.state.tourStep, .s20)
            XCTAssertNil(host.state.tourCoachMark)
            XCTAssertTrue(host.state.tourFinished)
            XCTAssertTrue(settings.tourState.spotifyRetryPending)
            controller.send(.spotifyExported)
            XCTAssertFalse(settings.tourState.spotifyRetryPending)
            XCTAssertEqual(world.purges, 1)
        }
    }

    func testAnUpgraderIsOfferedTheTourOnceAndDismissingHidesItForGood() {
        withSettings { settings in
            settings.setOnboarded()
            let host = FakeState()
            let controller = TourController(host: host, settings: settings,
                                            connectivity: Connectivity(online: true), demoWorld: World())
            controller.start()
            XCTAssertTrue(host.state.tourUpgradePrompt)
            XCTAssertNil(host.state.tourStep)
            controller.dismissUpgradePrompt()
            XCTAssertFalse(host.state.tourUpgradePrompt)
            XCTAssertNil(host.state.tourStep)

            let relaunch = FakeState()
            TourController(host: relaunch, settings: settings,
                           connectivity: Connectivity(online: true), demoWorld: World()).start()
            XCTAssertFalse(relaunch.state.tourUpgradePrompt)
            XCTAssertNil(relaunch.state.tourStep)
        }
    }

    func testAcceptingTheUpgradePromptStartsTheTourAndNeverOffersItAgain() {
        withSettings { settings in
            settings.setOnboarded()
            let host = FakeState()
            let controller = TourController(host: host, settings: settings,
                                            connectivity: Connectivity(online: true), demoWorld: World())
            controller.start()
            controller.acceptUpgradePrompt()
            XCTAssertFalse(host.state.tourUpgradePrompt)
            XCTAssertEqual(host.state.tourStep, .s1)
            XCTAssertEqual(host.state.tourCoachMark, .line)
            controller.skip()

            let relaunch = FakeState()
            TourController(host: relaunch, settings: settings,
                           connectivity: Connectivity(online: true), demoWorld: World()).start()
            XCTAssertFalse(relaunch.state.tourUpgradePrompt)
            XCTAssertTrue(settings.tourState.upgradePromptDismissed)
        }
    }

    func testANewInstallNeverSeesTheUpgradePrompt() {
        withSettings { settings in
            for online in [false, true, true] {
                let host = FakeState()
                let controller = TourController(host: host, settings: settings,
                                                connectivity: Connectivity(online: online), demoWorld: World())
                controller.start()
                XCTAssertFalse(host.state.tourUpgradePrompt)
                controller.skip()
            }
        }
    }
}
