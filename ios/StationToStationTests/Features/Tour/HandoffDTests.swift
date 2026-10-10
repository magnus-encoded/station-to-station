import Foundation
import XCTest
@testable import StationToStation

func handoffData() throws -> Data {
    var url = URL(fileURLWithPath: #filePath)
    for _ in 0..<5 { url.deleteLastPathComponent() }
    return try Data(contentsOf: url.appendingPathComponent("fixtures/tour/cases.json"))
}
final class HandoffDTests: XCTestCase {
    func testUnknownBandAndRetry() throws {
        let line = TourCharacter.bundled.lines["S3"]!
        let nohit = try XCTUnwrap(line.nohit), failed = try XCTUnwrap(line.failed)
        XCTAssertEqual(tourBandReason(picked: false, lookup: "nohit", nohit: nohit, failed: failed), nohit)
        XCTAssertFalse(tourPlanReady(picked: false, venue: "Venue", future: true))
        XCTAssertEqual(tourBandReason(picked: false, lookup: "failed", nohit: nohit, failed: failed), failed)
        XCTAssertNil(tourBandReason(picked: true, lookup: "hits", nohit: nohit, failed: failed))
        XCTAssertTrue(tourPlanReady(picked: true, venue: "Venue", future: true))
        XCTAssertFalse(tourPlanReady(picked: true, venue: "", future: true))
        XCTAssertFalse(tourPlanReady(picked: true, venue: "Venue", future: false))
    }
    func testSharedGoals() throws {
        struct Row: Decodable { let initial: TourStep?; let event: String; let goals: [TourStep]; let step: TourStep; let satisfied: Bool }
        struct Corpus: Decodable { let goalCases: [Row] }
        for row in try JSONDecoder().decode(Corpus.self, from: handoffData()).goalCases {
            var state = TourState(); state.currentStep = row.initial
            let event: TourEvent
            switch row.event {
            case "started": event = .started(online: true)
            case "resumed": event = .resumed
            case "contactExchanged": event = .contactExchanged(location: TourLocation(latitude: 0, longitude: 0))
            default: event = .acknowledged
            }
            let next = TourScript.reduce(state, event, goals: Set(row.goals)).state
            XCTAssertEqual(next.currentStep, row.step)
            XCTAssertEqual(next.goalSatisfied == true, row.satisfied)
            let mark = TourCoachMark.allCases.first { $0.step == row.step }!
            let line = TourCharacter.bundled.line(mark, screen: "timelines", satisfied: row.satisfied)
            if row.satisfied {
                XCTAssertEqual(line.instruction, "Tap OK.")
                XCTAssertEqual(line.why, TourCharacter.bundled.lines[row.step.rawValue]?.already)
                XCTAssertEqual(TourScript.reduce(next, .acknowledged, goals: Set(row.goals)).state.currentStep, .s9)
            } else { XCTAssertTrue(line.instruction.contains(row.step == .s1 ? "OK" : "Spread two fingers")) }
        }
        for step in [TourStep.s5, .s6, .s8, .s10, .s13] {
            var state = TourState(); state.currentStep = step
            XCTAssertEqual(TourScript.reduce(state, .resumed, goals: [step]).state.goalSatisfied, true)
            XCTAssertNotNil(TourCharacter.bundled.lines[step.rawValue]?.already)
        }
    }
    func testArrivalPhasesAndOrder() {
        XCTAssertEqual(logArrivalOrder(before: ["1", "2"], after: (1...6).map(String.init)), ["3", "4", "5", "6"])
        XCTAssertTrue(logArrivalOrder(before: ["1", "2"], after: ["1", "2"]).isEmpty)
        XCTAssertEqual(logArrivalOrder(before: ["log:1"], after: ["published:1", "published:2"], beforeTitles: ["First"], afterTitles: ["First", "Second"]), ["published:2"])
        let row = LogArrival(index: 0, count: 4, started: Date())
        XCTAssertEqual(row.frame(elapsed: 0), LogArrivalFrame(space: 0, text: 0, node: false))
        XCTAssertGreaterThan(row.frame(elapsed: 0.045).space, 0)
        XCTAssertEqual(row.frame(elapsed: 0.045).text, 0)
        XCTAssertEqual(row.frame(elapsed: 0.18).space, 1)
        XCTAssertGreaterThan(row.frame(elapsed: 0.18).text, 0)
        XCTAssertFalse(row.frame(elapsed: 0.18).node)
        XCTAssertTrue(row.frame(elapsed: 0.36).node)
        XCTAssertEqual(LogArrival(index: 1, count: 4, started: Date()).frame(elapsed: 0.36), LogArrivalFrame(space: 0, text: 0, node: false))
        XCTAssertTrue(LogArrival(index: 24, count: 25, started: Date()).frame(elapsed: 3).node)
        XCTAssertEqual(row.frame(elapsed: 0, reduceMotion: true), LogArrivalFrame())
    }
}
