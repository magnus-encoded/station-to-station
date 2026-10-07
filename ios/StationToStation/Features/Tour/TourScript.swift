import Foundation

enum TourStep: String, Codable, CaseIterable {
    case s1 = "S1"
    case s2 = "S2"
    case s3 = "S3"
    case s4 = "S4"
    case s5 = "S5"
    case s6 = "S6"
    case s7 = "S7"
    case s8 = "S8"
    case s9 = "S9"
    case s10 = "S10"
    case s11 = "S11"
    case s12 = "S12"
    case s13 = "S13"
    case s14 = "S14"
    case s15 = "S15"
    case s16 = "S16"
    case s17 = "S17"
    case s18 = "S18"
    case s19 = "S19"
    case s20 = "S20"
}

enum TourCoachMark: String, Codable, Equatable {
    case line, curtain, band, addGig, openRoom, swipeBack, exchange, pinchOut
    case calendar, maps, ticket, checkIn, log, gap, gossip, selfie, spotify
}

enum DemoClockMark: String, Codable, Equatable { case approaching, doors, showStarted, after }
enum TourMediaVisibility: String, Codable, Equatable { case `private`, shared }
struct TourLocation: Codable, Equatable {
    var latitude: Double
    var longitude: Double
}

enum TourEvent: Equatable {
    case started(online: Bool)
    case acknowledged, curtainPulled, bandPicked, gigAdded, roomOpened, swipedBack
    case contactExchanged(location: TourLocation)
    case pinchedOut, ticketImported, calendarAdded, mapsOpened, ticketShown, checkedIn
    case logEntryWritten, gapRecorded, gossipSent, setlistFilled, returnedFromPhotos
    case mediaAdded(visibility: TourMediaVisibility)
    case spotifyExported, spotifyDeclined, skipped, resumed, replayRequested, connectivityLost
}

enum TourCommand: Equatable {
    case showCoachMark(TourCoachMark)
    case lookUpBand, importDemoTicket
    case advanceDemoClock(DemoClockMark)
    case deliverGossip, fillSetlist, deliverFriendSelfie, purgeDemoWorld, markTourFinished
}

struct TourState: Codable, Equatable {
    var currentStep: TourStep?
    var finished = false
    var upgradePromptDismissed = false
    var spotifyRetryPending = false
    var seenHints: Set<String> = []
    var bandLookedUp = false
    var demoTicketImported = false
    var returnedFromPhotos = false
    var location: TourLocation?
}

enum TourScript {
    private struct Step {
        var entry: [TourCommand]
        var awaits: [TourEvent]
        var exit: [TourCommand] = []
    }

    private static let steps: [TourStep: Step] = [
        .s1: Step(entry: [.showCoachMark(.line)], awaits: [.acknowledged]),
        .s2: Step(entry: [.showCoachMark(.curtain)], awaits: [.curtainPulled]),
        .s3: Step(entry: [.showCoachMark(.band), .lookUpBand], awaits: [.bandPicked]),
        .s4: Step(entry: [.showCoachMark(.addGig)], awaits: [.gigAdded]),
        .s5: Step(entry: [.showCoachMark(.openRoom)], awaits: [.roomOpened]),
        .s6: Step(entry: [.showCoachMark(.swipeBack)], awaits: [.swipedBack]),
        .s7: Step(entry: [.showCoachMark(.exchange)], awaits: [.contactExchanged(location: TourLocation(latitude: 0, longitude: 0))]),
        .s8: Step(entry: [.showCoachMark(.pinchOut)], awaits: [.pinchedOut]),
        .s9: Step(entry: [.importDemoTicket], awaits: [.ticketImported]),
        .s10: Step(entry: [.advanceDemoClock(.approaching), .showCoachMark(.calendar)], awaits: [.calendarAdded]),
        .s11: Step(entry: [.showCoachMark(.maps)], awaits: [.mapsOpened]),
        .s12: Step(entry: [.advanceDemoClock(.doors), .showCoachMark(.ticket)], awaits: [.ticketShown]),
        .s13: Step(entry: [.showCoachMark(.checkIn)], awaits: [.checkedIn]),
        .s14: Step(entry: [.advanceDemoClock(.showStarted), .showCoachMark(.log)], awaits: [.logEntryWritten]),
        .s15: Step(entry: [.showCoachMark(.gap)], awaits: [.gapRecorded], exit: [.deliverGossip]),
        .s16: Step(entry: [.showCoachMark(.gossip)], awaits: [.gossipSent]),
        .s17: Step(entry: [.fillSetlist], awaits: [.setlistFilled]),
        .s18: Step(entry: [.showCoachMark(.selfie)], awaits: [.returnedFromPhotos, .mediaAdded(visibility: .private)], exit: [.deliverFriendSelfie]),
        .s19: Step(entry: [.advanceDemoClock(.after), .showCoachMark(.spotify)], awaits: [.spotifyExported, .spotifyDeclined]),
        .s20: Step(entry: [.purgeDemoWorld, .markTourFinished], awaits: [])
    ]

    /// Advances only on the awaited event; entry commands describe the next Tour step.
    static func reduce(_ state: TourState, _ event: TourEvent) -> (TourState, [TourCommand]) {
        var next = state
        switch event {
        case .started(let online):
            guard online, state.currentStep == nil, !state.finished else { return (state, []) }
            return enter(.s1, next)
        case .replayRequested:
            next = TourState()
            next.seenHints = state.seenHints
            next.upgradePromptDismissed = state.upgradePromptDismissed
            let (replayed, commands) = enter(.s1, next)
            return (replayed, [.purgeDemoWorld] + commands)
        case .resumed:
            guard let step = state.currentStep, !state.finished else { return (state, []) }
            return enter(step, next)
        case .skipped:
            guard state.currentStep != nil, !state.finished else { return (state, []) }
            next.currentStep = nil
            next.finished = true
            return (next, [.purgeDemoWorld, .markTourFinished])
        case .spotifyExported where state.spotifyRetryPending && (state.finished || state.currentStep == nil):
            next.spotifyRetryPending = false
            return (next, [])
        default: break
        }
        guard let step = state.currentStep, !state.finished,
              let definition = steps[step],
              definition.awaits.contains(where: { matches($0, event) }) else { return (state, []) }
        if step == .s18 {
            if event == .returnedFromPhotos {
                next.returnedFromPhotos = true
                return (next, [])
            }
            guard next.returnedFromPhotos else { return (state, []) }
        }
        if case .contactExchanged(let location) = event { next.location = location }
        if event == .spotifyDeclined { next.spotifyRetryPending = true }
        if event == .spotifyExported { next.spotifyRetryPending = false }
        guard let index = TourStep.allCases.firstIndex(of: step),
              index + 1 < TourStep.allCases.count else { return (state, []) }
        let (advanced, commands) = enter(TourStep.allCases[index + 1], next)
        return (advanced, definition.exit + commands)
    }

    private static func matches(_ awaited: TourEvent, _ event: TourEvent) -> Bool {
        switch (awaited, event) {
        case (.contactExchanged, .contactExchanged), (.mediaAdded, .mediaAdded): return true
        default: return awaited == event
        }
    }

    private static func enter(_ step: TourStep, _ state: TourState) -> (TourState, [TourCommand]) {
        var next = state
        next.currentStep = step
        let commands = (steps[step]?.entry ?? []).filter {
            if $0 == .lookUpBand { return !state.bandLookedUp }
            if $0 == .importDemoTicket { return !state.demoTicketImported }
            return true
        }
        if commands.contains(.lookUpBand) { next.bandLookedUp = true }
        if commands.contains(.importDemoTicket) { next.demoTicketImported = true }
        if step == .s20 { next.finished = true }
        return (next, commands)
    }
}
