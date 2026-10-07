import Foundation
import XCTest
@testable import StationToStation

final class TourScriptFixtureTests: XCTestCase {
    private func cases() throws -> [[String: Any]] {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("fixtures/tour/cases.json")
        return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [[String: Any]])
    }

    func testSharedScriptCases() throws {
        let rows: Set<String> = ["Offline start", "Skip at Sn", "Resume", "Replay",
                                 "Out-of-order event", "Happy path", "Spotify declined", "Spotify retry"]
        let selected = try cases().filter { rows.contains($0["row"] as? String ?? "") }
        XCTAssertEqual(selected.filter { $0["row"] as? String == "Skip at Sn" }.count, 19)
        for item in selected {
            let id = try XCTUnwrap(item["id"] as? String)
            var state = TourState()
            let precondition = item["precondition"] as? [String: Any] ?? [:]
            state.currentStep = (precondition["currentStep"] as? String).flatMap(TourStep.init(rawValue:))
            state.finished = precondition["finished"] as? Bool ?? false
            state.spotifyRetryPending = precondition["pendingSpotifyRetry"] as? Bool ?? false
            let initial = state
            var visited: [String] = []
            var commands: [TourCommand] = []
            var lastCommands: [TourCommand] = []
            var reenteredStep: String?
            var reenteredCommands: [TourCommand] = []
            var spotify: String?
            for raw in try XCTUnwrap(item["events"] as? [[String: Any]]) {
                let type = try XCTUnwrap(raw["type"] as? String)
                if type == "restart" {
                    state = try JSONDecoder().decode(TourState.self, from: JSONEncoder().encode(state))
                    continue
                }
                let before = state
                let result = TourScript.reduce(state, try event(raw))
                state = result.0
                lastCommands = result.1
                commands += lastCommands
                if let step = state.currentStep, step != before.currentStep { visited.append(step.rawValue) }
                if type == "resumed" {
                    reenteredStep = state.currentStep?.rawValue
                    reenteredCommands = lastCommands
                }
                if type == "spotifyExported", state != before { spotify = "exported" }
            }
            for (key, expected) in try XCTUnwrap(item["expect"] as? [String: Any]) {
                switch key {
                case "currentStep":
                    XCTAssertEqual(state.currentStep?.rawValue, expected as? String, id)
                case "finished": XCTAssertEqual(state.finished, expected as? Bool, id)
                case "visitedSteps": XCTAssertEqual(visited, expected as? [String], id)
                case "commands": XCTAssertEqual(commands.map(commandName), expected as? [String], id)
                case "commandsEnd":
                    let names = try XCTUnwrap(expected as? [String])
                    XCTAssertEqual(Array(commands.suffix(names.count)).map(commandName), names, id)
                case "spotify": XCTAssertEqual(spotify, expected as? String, id)
                case "playlistCreated": XCTAssertEqual(spotify == "exported", expected as? Bool, id)
                case "stateChanged": XCTAssertEqual(state != initial, expected as? Bool, id)
                case "reenteredStep": XCTAssertEqual(reenteredStep, expected as? String, id)
                case "reemitEntryCommands":
                    XCTAssertEqual(!reenteredCommands.isEmpty, expected as? Bool, id)
                    XCTAssertEqual(reenteredCommands, [.showCoachMark(.addGig)], id)
                case "doNotRepeat":
                    for name in try XCTUnwrap(expected as? [String]) {
                        XCTAssertFalse(reenteredCommands.map(commandName).contains(name), id)
                    }
                case "freshDemoWorld":
                    XCTAssertEqual(lastCommands.first == .purgeDemoWorld, expected as? Bool, id)
                    XCTAssertEqual(lastCommands, [.purgeDemoWorld, .showCoachMark(.line)], id)
                    XCTAssertFalse(state.finished, id)
                case "pendingSpotifyRetry": XCTAssertEqual(state.spotifyRetryPending, expected as? Bool, id)
                default: XCTFail("Unchecked expectation: \(key) in \(id)")
                }
            }
        }
    }

    private func event(_ raw: [String: Any]) throws -> TourEvent {
        let type = try XCTUnwrap(raw["type"] as? String)
        switch type {
        case "started": return .started(online: try XCTUnwrap(raw["online"] as? Bool))
        case "contactExchanged":
            let location = try XCTUnwrap(raw["location"] as? [String: Double])
            return .contactExchanged(location: TourLocation(
                latitude: try XCTUnwrap(location["latitude"]), longitude: try XCTUnwrap(location["longitude"])))
        case "mediaAdded":
            return .mediaAdded(visibility: try XCTUnwrap(TourMediaVisibility(rawValue: try XCTUnwrap(raw["visibility"] as? String))))
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
        case .importDemoTicket: return "importDemoTicket"
        case .deliverGossip: return "deliverGossip"
        case .fillSetlist: return "fillSetlist"
        case .deliverFriendSelfie: return "deliverFriendSelfie"
        case .purgeDemoWorld: return "purgeDemoWorld"
        case .markTourFinished: return "markTourFinished"
        }
    }

    func testEveryStepEmitsItsScriptedEntryCommands() throws {
        let expected: [[TourCommand]] = [
            [.showCoachMark(.line)],
            [.showCoachMark(.curtain)],
            [.showCoachMark(.band), .lookUpBand],
            [.showCoachMark(.addGig)],
            [.showCoachMark(.openRoom)],
            [.showCoachMark(.swipeBack)],
            [.showCoachMark(.exchange)],
            [.showCoachMark(.pinchOut)],
            [.importDemoTicket],
            [.advanceDemoClock(.approaching), .showCoachMark(.calendar)],
            [.showCoachMark(.maps)],
            [.advanceDemoClock(.doors), .showCoachMark(.ticket)],
            [.showCoachMark(.checkIn)],
            [.advanceDemoClock(.showStarted), .showCoachMark(.log)],
            [.showCoachMark(.gap)],
            [.showCoachMark(.gossip)],
            [.fillSetlist],
            [.showCoachMark(.selfie)],
            [.advanceDemoClock(.after), .showCoachMark(.spotify)],
            [.purgeDemoWorld, .markTourFinished]
        ]
        let happy = try XCTUnwrap(cases().first { $0["row"] as? String == "Happy path" })
        var state = TourState()
        var index = 0
        for raw in try XCTUnwrap(happy["events"] as? [[String: Any]]) {
            let result = TourScript.reduce(state, try event(raw))
            if result.0.currentStep != state.currentStep {
                var commands = expected[index]
                if raw["type"] as? String == "gapRecorded" { commands.insert(.deliverGossip, at: 0) }
                if raw["type"] as? String == "mediaAdded" { commands.insert(.deliverFriendSelfie, at: 0) }
                XCTAssertEqual(result.1, commands, "S\(index + 1)")
                index += 1
            } else {
                XCTAssertTrue(result.1.isEmpty)
            }
            state = result.0
        }
        XCTAssertEqual(index, 20)
        XCTAssertTrue(state.finished)
        XCTAssertEqual(state.location, TourLocation(latitude: 59.9139, longitude: 10.7522))
    }

    func testResumeDoesNotRepeatBandLookupOrTicketImport() throws {
        for (step, event, command) in [(TourStep.s2, TourEvent.curtainPulled, TourCommand.lookUpBand),
                                      (.s8, .pinchedOut, .importDemoTicket)] {
            let entered = TourScript.reduce(TourState(currentStep: step), event)
            XCTAssertTrue(entered.1.contains(command))
            let restored = try JSONDecoder().decode(TourState.self, from: JSONEncoder().encode(entered.0))
            let resumed = TourScript.reduce(restored, .resumed)
            XCTAssertEqual(resumed.0, restored)
            XCTAssertFalse(resumed.1.contains(command))
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

    func testGapDeliversGossipBeforeTheNextCoachMark() {
        let result = TourScript.reduce(TourState(currentStep: .s15), .gapRecorded)
        XCTAssertEqual(result.0.currentStep, .s16)
        XCTAssertEqual(result.1, [.deliverGossip, .showCoachMark(.gossip)])
    }

    func testConnectivityLossLeavesTheActiveStepUntouched() {
        let state = TourState(currentStep: .s4)
        let result = TourScript.reduce(state, .connectivityLost)
        XCTAssertEqual(result.0, state)
        XCTAssertTrue(result.1.isEmpty)
    }
}
