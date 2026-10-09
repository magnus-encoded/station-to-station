import Foundation
import XCTest
@testable import StationToStation

final class TourScriptFixtureTests: XCTestCase {
    func testSharedScriptCases() throws {
        let rows: Set<String> = ["Happy path", "Offline start", "Skip at Sn", "Resume", "Replay",
                                 "Out-of-order event", "Spotify declined", "Spotify retry"]
        for fixture in try TourFixtures.load() {
            switch fixture.row {
            case "Fill: setlist.fm hit", "Fill: fallback": continue
            default:
                guard rows.contains(fixture.row) else {
                    XCTFail("Unhandled Tour row: \(fixture.row)")
                    continue
                }
            }
            var state = TourState()
            if let step = fixture.initial?.step {
                state.currentStep = try XCTUnwrap(TourStep(rawValue: step), fixture.id)
            }
            state.finished = fixture.initial?.finished ?? false
            state.spotifyRetryPending = fixture.initial?.pendingSpotifyRetry ?? false
            let completed = fixture.initial?.completedEffects ?? []
            state.bandLookedUp = completed.contains("lookUpBand")
            state.demoTicketImported = completed.contains("importDemoTicket")
            var visitedSteps: [String] = []
            var spotifyExported = false
            var playlistExportedBySkip = false
            for (index, check) in fixture.checks.enumerated() {
                let context = "\(fixture.id), check \(index): \(check.event)"
                let before = state
                let commands: [TourCommand]
                let freshDemoWorld: Bool
                if check.event == "restart" {
                    state = try JSONDecoder().decode(TourState.self, from: JSONEncoder().encode(state))
                    commands = []
                    freshDemoWorld = false
                } else {
                    let result = TourScript.reduce(state, try event(check.event, online: fixture.online ?? true))
                    state = result.state
                    commands = result.commands
                    freshDemoWorld = result.freshDemoWorld
                }
                if let step = state.currentStep, step != before.currentStep {
                    visitedSteps.append(step.rawValue)
                }
                let completedExport = before.currentStep == .s19 && state.currentStep == .s20
                    && state.finished && !state.spotifyRetryPending
                let retriedExport = before.spotifyRetryPending && !state.spotifyRetryPending
                    && state.finished && state.currentStep == before.currentStep
                let exported = completedExport || retriedExport
                spotifyExported = spotifyExported || exported
                if check.event == "skipped" { playlistExportedBySkip = playlistExportedBySkip || exported }
                XCTAssertEqual(state.currentStep?.rawValue, check.expect.step, context)
                XCTAssertEqual(commands.map(commandName), check.expect.commands, context)
                if let value = check.expect.finished { XCTAssertEqual(state.finished, value, context) }
                if let value = check.expect.pendingSpotifyRetry {
                    XCTAssertEqual(state.spotifyRetryPending, value, context)
                }
                if let value = check.expect.unchanged { XCTAssertEqual(state == before, value, context) }
                if let value = check.expect.freshDemoWorld { XCTAssertEqual(freshDemoWorld, value, context) }
            }
            if let expected = fixture.expected {
                if let value = expected.visitedSteps { XCTAssertEqual(visitedSteps, value, fixture.id) }
                if let value = expected.finished { XCTAssertEqual(state.finished, value, fixture.id) }
                if let value = expected.spotifyExported { XCTAssertEqual(spotifyExported, value, fixture.id) }
                if let value = expected.playlistExportedBySkip {
                    XCTAssertEqual(playlistExportedBySkip, value, fixture.id)
                }
            }
        }
    }

    private func event(_ type: String, online: Bool) throws -> TourEvent {
        switch type {
        case "started": return .started(online: online)
        case "contactExchanged":
            return .contactExchanged(location: TourLocation(latitude: 59.9139, longitude: 10.7522))
        case "mediaAdded": return .mediaAdded(visibility: .private)
        case "acknowledged": return .acknowledged
        case "curtainPulled": return .curtainPulled
        case "bandPicked": return .bandPicked
        case "gigAdded": return .gigAdded
        case "roomOpened": return .roomOpened
        case "swipedBack": return .swipedBack
        case "pinchedOut": return .pinchedOut
        case "ticketImported": return .ticketImported
        case "calendarAdded": return .calendarAdded
        case "mapsOpened": return .mapsOpened
        case "ticketShown": return .ticketShown
        case "checkedIn": return .checkedIn
        case "logEntryWritten": return .logEntryWritten
        case "gapRecorded": return .gapRecorded
        case "gossipSent": return .gossipSent
        case "setlistFilled": return .setlistFilled
        case "setCompleted": return .setCompleted
        case "returnedFromPhotos": return .returnedFromPhotos
        case "spotifyExported": return .spotifyExported
        case "spotifyDeclined": return .spotifyDeclined
        case "skipped": return .skipped
        case "resumed": return .resumed
        case "replayRequested": return .replayRequested
        case "connectivityLost": return .connectivityLost
        default: throw NSError(domain: "Unknown Tour event: \(type)", code: 1)
        }
    }

    private func commandName(_ command: TourCommand) -> String {
        switch command {
        case .showCoachMark(let mark): return "showCoachMark(\(mark.rawValue))"
        case .advanceDemoClock(let mark): return "advanceDemoClock(\(mark.rawValue))"
        case .lookUpBand: return "lookUpBand"
        case .importDemoTicket: return "importDemoTicket(at: venue)"
        case .deliverGossip: return "deliverGossip(gapFill)"
        case .fillSetlist: return "fillSetlist"
        case .deliverFriendSelfie: return "deliverFriendSelfie"
        case .purgeDemoWorld: return "purgeDemoWorld"
        case .markTourFinished: return "markTourFinished"
        }
    }

    func testSelfieRequiresReturningFromPhotosBeforeEitherVisibility() throws {
        for visibility in [TourMediaVisibility.private, .shared] {
            let state = TourState(currentStep: .s18)
            let premature = TourScript.reduce(state, .mediaAdded(visibility: visibility))
            XCTAssertEqual(premature.0, state)
            XCTAssertTrue(premature.1.isEmpty)
            let returned = TourScript.reduce(state, .returnedFromPhotos).0
            let restored = try JSONDecoder().decode(TourState.self, from: JSONEncoder().encode(returned))
            let added = TourScript.reduce(restored, .mediaAdded(visibility: visibility))
            XCTAssertEqual(added.0.currentStep, .s19)
            XCTAssertEqual(added.1, [.deliverFriendSelfie, .advanceDemoClock(.after), .showCoachMark(.spotify)])
        }
    }

    func testConnectivityLossLeavesTheActiveStepUntouched() {
        let state = TourState(currentStep: .s4)
        let result = TourScript.reduce(state, .connectivityLost)
        XCTAssertEqual(result.0, state)
        XCTAssertTrue(result.1.isEmpty)
    }
}
