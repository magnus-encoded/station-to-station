import Foundation

enum TourStep: Int, Codable, CaseIterable, Equatable {
    case line = 1, curtain, band, addGig, room, swipeBack, exchange, timelines, ticket
    case calendar, maps, atTheDoor, checkIn, firstSong, gap, gossipBack, setlistFill, selfie, spotify, end

    var fixtureName: String { "S\(rawValue)" }
}

enum TourCoachMark: String, Codable, Equatable {
    case line, curtain, band, addGig, openRoom, swipeBack, exchange, pinchOut
    case calendar, maps, ticket, checkIn, log, gap, gossip, selfie, spotify
}

enum DemoClock: String, Codable, Equatable { case approaching, doors, showStarted, after }
enum MediaVisibility: String, Codable, Equatable { case personal, shared }

enum TourCommand: Equatable {
    case showCoachMark(TourCoachMark)
    case lookUpBand
    case importDemoTicket(at: String)
    case advanceDemoClock(DemoClock)
    case deliverGossipGapFill
    case fillSetlist
    case deliverFriendSelfie
    case purgeDemoWorld
    case markTourFinished
}

enum TourEvent: Equatable {
    case started(online: Bool)
    case acknowledged, curtainPulled, gigAdded, roomOpened, swipedBack
    case bandPicked(String)
    case contactExchanged(location: String)
    case pinchedOut, ticketImported, calendarAdded, mapsOpened
    case ticketShown, checkedIn, gapRecorded, gossipSent, setlistFilled
    case logEntryWritten(String)
    case returnedFromPhotos, mediaAdded(MediaVisibility), spotifyExported, spotifyDeclined
    case skipped, resumed, replayRequested, connectivityLost
}

struct TourState: Codable, Equatable {
    var step: TourStep?
    var finished = false
    var upgradePromptDismissed = false
    var pendingSpotifyRetry = false
    var seenContextHints: Set<String> = []
    var demoWorldID: UUID?
    var bandLookupStarted = false
    var ticketImportStarted = false
    var demoLocation: String?
    var returnedFromPhotos = false

    static let unstarted = TourState()
    var isRunning: Bool { step != nil && !finished }
}

struct TourTransition: Equatable {
    var state: TourState
    var commands: [TourCommand]
}

/// The pure Tour script. UI and device work consume commands; ordering lives here.
func runTour(_ old: TourState, _ event: TourEvent) -> TourTransition {
    var state = old

    if event == .spotifyExported, state.pendingSpotifyRetry, !state.isRunning {
        state.pendingSpotifyRetry = false
        return TourTransition(state: state, commands: [])
    }

    switch event {
    case .started(let online):
        guard !state.finished, state.step == nil, online else { return .unchanged(old) }
        state.demoWorldID = UUID()
        return enter(.line, state)
    case .replayRequested:
        state = TourState(demoWorldID: UUID())
        return enter(.line, state)
    case .resumed:
        guard let step = state.step, !state.finished else { return .unchanged(old) }
        return enter(step, state)
    case .skipped:
        guard state.isRunning else { return .unchanged(old) }
        state.step = .end
        state.finished = true
        return TourTransition(state: state, commands: [.purgeDemoWorld, .markTourFinished])
    case .connectivityLost:
        return .unchanged(old)
    default:
        break
    }

    guard let step = state.step, !state.finished else { return .unchanged(old) }
    if step == .selfie, event == .returnedFromPhotos {
        state.returnedFromPhotos = true
        return TourTransition(state: state, commands: [])
    }
    guard awaits(step, event, returnedFromPhotos: state.returnedFromPhotos) else {
        return .unchanged(old)
    }
    if case .contactExchanged(let location) = event { state.demoLocation = location }
    if step == .spotify, event == .spotifyDeclined { state.pendingSpotifyRetry = true }
    guard let next = TourStep(rawValue: step.rawValue + 1) else { return .unchanged(old) }
    if next == .end {
        state.step = .end
        state.finished = true
        return TourTransition(state: state, commands: [.purgeDemoWorld, .markTourFinished])
    }
    var entered = enter(next, state)
    if step == .selfie { entered.commands.insert(.deliverFriendSelfie, at: 0) }
    return entered
}

private func awaits(_ step: TourStep, _ event: TourEvent, returnedFromPhotos: Bool) -> Bool {
    switch (step, event) {
    case (.line, .acknowledged), (.curtain, .curtainPulled), (.band, .bandPicked),
         (.addGig, .gigAdded), (.room, .roomOpened), (.swipeBack, .swipedBack),
         (.exchange, .contactExchanged), (.timelines, .pinchedOut), (.ticket, .ticketImported),
         (.calendar, .calendarAdded), (.maps, .mapsOpened), (.atTheDoor, .ticketShown),
         (.checkIn, .checkedIn), (.firstSong, .logEntryWritten), (.gap, .gapRecorded),
         (.gossipBack, .gossipSent), (.setlistFill, .setlistFilled),
         (.spotify, .spotifyExported), (.spotify, .spotifyDeclined): return true
    case (.selfie, .mediaAdded): return returnedFromPhotos
    default: return false
    }
}

private func enter(_ step: TourStep, _ input: TourState) -> TourTransition {
    var state = input
    state.step = step
    var commands = entryCommands(step)
    if step == .band {
        if state.bandLookupStarted { commands.removeAll { $0 == .lookUpBand } }
        else { state.bandLookupStarted = true }
    }
    if step == .ticket {
        if state.ticketImportStarted {
            commands.removeAll { if case .importDemoTicket = $0 { return true }; return false }
        } else if let location = state.demoLocation {
            commands.append(.importDemoTicket(at: location))
            state.ticketImportStarted = true
        }
    }
    return TourTransition(state: state, commands: commands)
}

private func entryCommands(_ step: TourStep) -> [TourCommand] {
    switch step {
    case .line: return [.showCoachMark(.line)]
    case .curtain: return [.showCoachMark(.curtain)]
    case .band: return [.showCoachMark(.band), .lookUpBand]
    case .addGig: return [.showCoachMark(.addGig)]
    case .room: return [.showCoachMark(.openRoom)]
    case .swipeBack: return [.showCoachMark(.swipeBack)]
    case .exchange: return [.showCoachMark(.exchange)]
    case .timelines: return [.showCoachMark(.pinchOut)]
    case .ticket: return [] // the location-bearing command is added by `enter`
    case .calendar: return [.advanceDemoClock(.approaching), .showCoachMark(.calendar)]
    case .maps: return [.showCoachMark(.maps)]
    case .atTheDoor: return [.advanceDemoClock(.doors), .showCoachMark(.ticket)]
    case .checkIn: return [.showCoachMark(.checkIn)]
    case .firstSong: return [.advanceDemoClock(.showStarted), .showCoachMark(.log)]
    case .gap: return [.showCoachMark(.gap)]
    case .gossipBack: return [.deliverGossipGapFill, .showCoachMark(.gossip)]
    case .setlistFill: return [.fillSetlist]
    case .selfie: return [.showCoachMark(.selfie)]
    case .spotify: return [.advanceDemoClock(.after), .showCoachMark(.spotify)]
    case .end: return [.purgeDemoWorld, .markTourFinished]
    }
}

private extension TourTransition {
    static func unchanged(_ state: TourState) -> TourTransition { TourTransition(state: state, commands: []) }
}

/// A durable marker carried by every record made inside a Demo world.
struct DemoTag: Codable, Hashable { let worldID: UUID }

struct DemoRecord: Codable, Equatable, Identifiable {
    let id: String
    var demoTag: DemoTag?
}

func purgeDemoWorld(_ records: [DemoRecord], worldID: UUID) -> [DemoRecord] {
    records.filter { $0.demoTag?.worldID != worldID }
}
