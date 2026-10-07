import Foundation

protocol TourConnectivity {
    func isOnline() -> Bool
}

@MainActor
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
    private let addGig: TourAddGigEffects?
    private(set) var state: TourState
    private var launched = false

    init(host: StateHost, settings: Settings, connectivity: TourConnectivity, demoWorld: DemoWorld,
         addGig: TourAddGigEffects? = nil) {
        self.host = host
        self.settings = settings
        self.connectivity = connectivity
        self.demoWorld = demoWorld
        self.addGig = addGig
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
            case .lookUpBand: addGig?.lookUpBand()
            default: break
            }
        }
    }

    /// A **Gig** added while S4 waits for one is the **Demo world**'s; any other is the person's own.
    func gigAdded(_ gigId: String) {
        guard state.currentStep == .s4, !state.finished else { return }
        addGig?.record(gigId)
        send(.gigAdded)
    }

    func skip() { send(.skipped) }
    func resume() { send(.resumed) }
    func replay() {
        settings.setOnboarded()
        host.state.onboarded = true
        send(.replayRequested)
    }
    func dismissCoachMark() { host.state.tourCoachMark = nil }

    var running: Bool { state.currentStep != nil && !state.finished }

    func markHintSeen(_ key: String) {
        state.seenHints.insert(key)
        settings.saveTourState(state)
    }

    private func publish() {
        host.state.tourStep = state.currentStep
        host.state.tourFinished = state.finished
    }
}
