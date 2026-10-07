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
    private let meetFriend: TourMeetFriendEffects?
    private let night: TourNightArrivesEffects?
    private(set) var state: TourState
    private var launched = false

    init(host: StateHost, settings: Settings, connectivity: TourConnectivity, demoWorld: DemoWorld,
         addGig: TourAddGigEffects? = nil, meetFriend: TourMeetFriendEffects? = nil,
         night: TourNightArrivesEffects? = nil) {
        self.host = host
        self.settings = settings
        self.connectivity = connectivity
        self.demoWorld = demoWorld
        self.addGig = addGig
        self.meetFriend = meetFriend
        self.night = night
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
            case .importDemoTicket: importDemoTicket()
            case .advanceDemoClock(let mark): night?.advance(to: mark)
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

    /// The Virtual friend's row on the Exchange screen while S7 waits for it; nil otherwise.
    var exchangeFriendName: String? {
        guard host.state.tourStep == .s7, !host.state.tourFinished, let meetFriend else { return nil }
        return meetFriend.name
    }

    /// S7's tap on the friend's row: a local Exchange, no radio.
    func exchangeWithFriend() async {
        guard state.currentStep == .s7, !state.finished, let meetFriend else { return }
        let fix = await meetFriend.exchange()
        // No fix: the script still needs a location, and the venue simply has none.
        send(.contactExchanged(location: fix ?? TourLocation(latitude: 0, longitude: 0)))
    }

    /// The clock a **Gig**'s rules read: the **Demo clock** for the demo **Gig** while the
    /// Tour runs, the real one for every other **Gig** and once the Tour is over.
    func now(for gigId: String) -> Date {
        guard running, isDemoGig(gigId), let demoNow = night?.demoNow else { return Date() }
        return demoNow
    }

    /// S10: the calendar event made for the demo **Gig**, kept so the purge can delete it.
    func calendarAdded(_ gigId: String, eventId: String) {
        guard running, isDemoGig(gigId) else { return }
        night?.recordCalendarEvent(eventId)
        send(.calendarAdded)
    }

    /// Where Maps should open for `gig`: the demo venue's point for the demo **Gig**, nil
    /// (the plain venue query) for every other.
    func mapsURL(for gig: FmSetlist) -> URL? {
        guard running, isDemoGig(gig.id) else { return nil }
        return TourNightArrivesEffects.mapsURL(for: gig)
    }

    func mapsOpened(_ gigId: String) { sendForDemoGig(gigId, .mapsOpened) }
    func ticketShown(_ gigId: String) { sendForDemoGig(gigId, .ticketShown) }
    func checkedIn(_ gigId: String) { sendForDemoGig(gigId, .checkedIn) }

    private func sendForDemoGig(_ gigId: String, _ event: TourEvent) {
        guard running, isDemoGig(gigId) else { return }
        send(event)
    }

    private func isDemoGig(_ gigId: String) -> Bool { addGig?.demoGigIds.contains(gigId) == true }

    private func importDemoTicket() {
        guard let meetFriend else { return }
        Task {
            await meetFriend.importDemoTicket()
            send(.ticketImported)
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
