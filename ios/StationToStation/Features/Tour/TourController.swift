import Foundation

protocol TourConnectivity {
    func isOnline() -> Bool
}

protocol DemoWorld {
    func purge()
}

struct EmptyDemoWorld: DemoWorld {
    func purge() {}
}

@MainActor
final class TourController {
    private let host: StateHost
    private let settings: Settings
    private let connectivity: TourConnectivity
    private let demoWorld: DemoWorld
    private(set) var state: TourState
    private var launched = false

    init(host: StateHost, settings: Settings, connectivity: TourConnectivity, demoWorld: DemoWorld) {
        self.host = host
        self.settings = settings
        self.connectivity = connectivity
        self.demoWorld = demoWorld
        state = settings.tourState
        publish()
    }

    func start() {
        guard !launched else { return }
        launched = true
        if state.currentStep != nil && !state.finished {
            resume()
        } else if !settings.onboarded && connectivity.isOnline() {
            settings.setOnboarded()
            host.state.onboarded = true
            send(.started(online: true))
        } else if settings.onboarded && state == TourState() {
            // Only an install onboarded before the Tour existed has `onboarded` and no Tour state.
            host.state.tourUpgradePrompt = true
        }
    }

    /// Either answer closes the upgrade prompt for good.
    func acceptUpgradePrompt() {
        closeUpgradePrompt()
        replay()
    }

    func dismissUpgradePrompt() { closeUpgradePrompt() }

    private func closeUpgradePrompt() {
        state.upgradePromptDismissed = true
        settings.saveTourState(state)
        host.state.tourUpgradePrompt = false
    }

    func send(_ event: TourEvent) {
        let (next, commands, freshDemoWorld) = TourScript.reduce(state, event)
        guard next != state || !commands.isEmpty || freshDemoWorld else { return }
        if freshDemoWorld { demoWorld.purge() }
        let changedStep = next.currentStep != state.currentStep
        state = next
        settings.saveTourState(state)
        if changedStep || state.finished { host.state.tourCoachMark = nil }
        publish()
        for command in commands {
            switch command {
            case .purgeDemoWorld: demoWorld.purge()
            case .showCoachMark(let mark): host.state.tourCoachMark = mark
            case .markTourFinished: host.state.tourFinished = true
            default: break
            }
        }
    }

    func skip() { send(.skipped) }
    func resume() { send(.resumed) }
    func replay() {
        settings.setOnboarded()
        host.state.onboarded = true
        send(.replayRequested)
    }
    func dismissCoachMark() { host.state.tourCoachMark = nil }

    private func publish() {
        host.state.tourStep = state.currentStep
        host.state.tourFinished = state.finished
    }
}
