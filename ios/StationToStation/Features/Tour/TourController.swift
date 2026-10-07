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
    private let log: TourLogEffects?
    private var logTask: Task<Void, Never>?
    private(set) var state: TourState
    private var launched = false

    init(host: StateHost, settings: Settings, connectivity: TourConnectivity, demoWorld: DemoWorld,
         addGig: TourAddGigEffects? = nil, meetFriend: TourMeetFriendEffects? = nil,
         night: TourNightArrivesEffects? = nil, log: TourLogEffects? = nil) {
        self.host = host
        self.settings = settings
        self.connectivity = connectivity
        self.demoWorld = demoWorld
        self.addGig = addGig
        self.meetFriend = meetFriend
        self.night = night
        self.log = log
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
        if freshDemoWorld {
            logTask?.cancel()
            demoWorld.purge()
        }
        let changedStep = next.currentStep != state.currentStep
        state = next
        settings.saveTourState(state)
        if changedStep || state.finished { host.state.tourCoachMark = nil }
        publish()
        run(commands)
    }

    private func run(_ commands: [TourCommand]) {
        for (index, command) in commands.enumerated() {
            switch command {
            case .purgeDemoWorld:
                logTask?.cancel()
                demoWorld.purge()
            case .showCoachMark(let mark): host.state.tourCoachMark = mark
            case .markTourFinished: host.state.tourFinished = true
            case .lookUpBand: addGig?.lookUpBand()
            case .importDemoTicket: importDemoTicket()
            case .advanceDemoClock(let mark): night?.advance(to: mark)
            case .deliverGossip:
                guard let log, let gig = demoGig else { continue }
                let expected = state
                let now = epochMs(self.now(for: gig.id))
                logTask = Task {
                    guard await log.deliverGossip(for: gig, now: now),
                          !Task.isCancelled, state == expected else { return }
                    run(Array(commands.dropFirst(index + 1)))
                }
                return
            case .fillSetlist:
                guard let log, let gig = demoGig else { continue }
                let now = epochMs(self.now(for: gig.id))
                logTask = Task {
                    if await log.fillSetlist(for: gig, now: now), !Task.isCancelled {
                        sendForDemoGig(gig.id, .setlistFilled)
                    }
                }
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

    /// A **Log** edit on the demo **Gig** while the Tour runs: kept in the demo store, never saved to the
    /// timeline or broadcast, and the event the awaiting step needs. False for every other edit.
    func logWritten(_ gigId: String, before: StoredLog, after: StoredLog, now: Int64) -> Bool {
        guard running, isDemoGig(gigId), let log else { return false }
        let step = state.currentStep
        let changes = gossipLogChanges(before: before, after: after)
        let namedChange = changes.values.contains { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        let canReply = step == .s16 && log.gossip(gigId).contains { $0.source == .virtualFriend }
        log.writeLog(gigId, log: after, reply: canReply && namedChange, now: now)
        if step == .s14, after.songs.count > before.songs.count, namedChange { send(.logEntryWritten) }
        if step == .s15, after.gaps > before.gaps { send(.gapRecorded) }
        if canReply, namedChange { send(.gossipSent) }
        return true
    }

    /// The demo **Gig**'s **Log**, nil for every other **Gig** and once the Tour is over.
    func demoLog(for gigId: String) -> StoredLog? {
        guard running, isDemoGig(gigId), let log else { return nil }
        return log.loadLog(gigId)
    }

    /// The gossip shown in the demo **Gig**'s **Log**: the friend's, and the person's reply.
    func demoGossip(for gigId: String) -> [TourLogEffects.Gossip] {
        guard running, isDemoGig(gigId) else { return [] }
        return log?.gossip(gigId) ?? []
    }

    var virtualFriendName: String { meetFriend?.name ?? "" }

    func setlistFillLine(for gigId: String) -> String? {
        guard running, isDemoGig(gigId) else { return nil }
        return log?.fillLine(gigId)
    }

    private var demoGig: FmSetlist? {
        guard running else { return nil }
        return host.state.plannedGigs.first { isDemoGig($0.id) }
    }

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
    /// The saved step can outlive its local **Gossip** delivery when the app is killed.
    func resume() {
        guard state.currentStep == .s16, let log, let gig = demoGig,
              log.loadLog(gig.id).gaps > 0, !log.gossip(gig.id).contains(where: { $0.source == .virtualFriend })
        else { send(.resumed); return }
        let expected = state
        let now = epochMs(self.now(for: gig.id))
        logTask = Task {
            guard await log.deliverGossip(for: gig, now: now),
                  !Task.isCancelled, state == expected else { return }
            send(.resumed)
        }
    }
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
