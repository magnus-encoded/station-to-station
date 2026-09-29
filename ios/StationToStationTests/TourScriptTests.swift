import XCTest
@testable import StationToStation

final class TourScriptTests: XCTestCase {
    private let progress: [[TourEvent]] = [
        [.acknowledged], [.curtainPulled], [.bandPicked("demo-band")], [.gigAdded], [.roomOpened],
        [.swipedBack], [.contactExchanged(location: "demo-location")], [.pinchedOut], [.ticketImported], [.calendarAdded],
        [.mapsOpened], [.ticketShown], [.checkedIn], [.logEntryWritten("song-1")], [.gapRecorded],
        [.gossipSent], [.setlistFilled], [.returnedFromPhotos, .mediaAdded(.shared)], [.spotifyExported],
    ]

    func testScriptHasExactlyTheSharedInteractiveSteps() {
        XCTAssertEqual(TourStep.allCases.dropLast().map(\.fixtureName), (1...19).map { "S\($0)" })
    }

    func testOfflineStartDoesNothing() {
        let result = runTour(.unstarted, .started(online: false))
        XCTAssertEqual(result.state, .unstarted)
        XCTAssertEqual(result.commands, [])
    }

    func testOnlineStartOffersLineAndCreatesFreshDemoWorld() {
        let result = runTour(.unstarted, .started(online: true))
        XCTAssertEqual(result.state.step, .line)
        XCTAssertNotNil(result.state.demoWorldID)
        XCTAssertEqual(result.commands, [.showCoachMark(.line)])
    }

    func testSkipAtEveryInteractiveStepPurgesAndFinishes() {
        var state = runTour(.unstarted, .started(online: true)).state
        for i in 0..<19 {
            let skipped = runTour(state, .skipped)
            XCTAssertTrue(skipped.state.finished, "S\(i + 1)")
            XCTAssertEqual(skipped.commands, [.purgeDemoWorld, .markTourFinished], "S\(i + 1)")
            if i < 18 { state = progress[i].reduce(state) { runTour($0, $1).state } }
        }
    }

    func testResumeAtEveryInteractiveStepReEmitsEntryWithoutRepeatingOneShotWork() throws {
        var state = runTour(.unstarted, .started(online: true)).state
        for i in 0..<19 {
            let restored = try JSONDecoder().decode(TourState.self, from: JSONEncoder().encode(state))
            let resumed = runTour(restored, .resumed)
            XCTAssertEqual(resumed.state.step, TourStep(rawValue: i + 1), "S\(i + 1)")
            if i != 8 { XCTAssertFalse(resumed.commands.isEmpty, "S\(i + 1)") }
            XCTAssertFalse(resumed.commands.contains(.lookUpBand), "S\(i + 1)")
            XCTAssertFalse(resumed.commands.contains { if case .importDemoTicket = $0 { return true }; return false }, "S\(i + 1)")
            if i < 18 { state = progress[i].reduce(state) { runTour($0, $1).state } }
        }
    }

    func testHappyPathVisitsEndAndFinishes() {
        var state = runTour(.unstarted, .started(online: true)).state
        var finalCommands: [TourCommand] = []
        for events in progress {
            for event in events {
                let result = runTour(state, event)
                state = result.state
                finalCommands = result.commands
            }
        }
        XCTAssertEqual(state.step, .end)
        XCTAssertTrue(state.finished)
        XCTAssertEqual(finalCommands, [.purgeDemoWorld, .markTourFinished])
    }

    func testReplayFinishedTourStartsFreshWorldAtLine() {
        let oldWorld = UUID()
        let finished = TourState(step: .end, finished: true, demoWorldID: oldWorld)
        let replay = runTour(finished, .replayRequested)
        XCTAssertEqual(replay.state.step, .line)
        XCTAssertFalse(replay.state.finished)
        XCTAssertNotEqual(replay.state.demoWorldID, oldWorld)
        XCTAssertEqual(replay.commands, [.showCoachMark(.line)])
    }

    func testOutOfOrderEventChangesNothing() {
        let started = runTour(.unstarted, .started(online: true)).state
        XCTAssertEqual(runTour(started, .gigAdded), TourTransition(state: started, commands: []))
    }

    func testAddTheGigStepsWaitForTheirActualGesturesInOrder() {
        var state = runTour(.unstarted, .started(online: true)).state
        state = runTour(state, .acknowledged).state
        XCTAssertEqual(state.step, .curtain)

        for early in [TourEvent.bandPicked("Low"), .gigAdded, .roomOpened, .swipedBack] {
            XCTAssertEqual(runTour(state, early).state, state)
        }
        state = runTour(state, .curtainPulled).state
        XCTAssertEqual(state.step, .band)
        state = runTour(state, .bandPicked("Low")).state
        XCTAssertEqual(state.step, .addGig)
        XCTAssertEqual(state.demoBandName, "Low")
        state = runTour(state, .gigAdded).state
        XCTAssertEqual(state.step, .room)
        state = runTour(state, .roomOpened).state
        XCTAssertEqual(state.step, .swipeBack)
        state = runTour(state, .swipedBack).state
        XCTAssertEqual(state.step, .exchange)
    }

    func testDemoGigUsesThePickedMusicBrainzArtistAndDemoWorldIdentity() {
        let world = UUID(uuidString: "00000000-0000-0000-0000-000000000592")!
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let date = Date(timeIntervalSince1970: 1_800_000_000)

        let gig = tourDemoGig(
            worldID: world,
            artist: MbArtist(name: "Low", mbid: "mb-low"),
            date: date,
            calendar: calendar
        )

        XCTAssertEqual(gig.id, "tour-00000000-0000-0000-0000-000000000592")
        XCTAssertEqual(gig.artist?.name, "Low")
        XCTAssertEqual(gig.artist?.mbid, "mb-low")
        XCTAssertNil(gig.url)
    }

    func testSkipPurgesOnlyThisDemoWorld() {
        let thisWorld = UUID(), anotherWorld = UUID()
        let records = [
            DemoRecord(id: "real", demoTag: nil),
            DemoRecord(id: "mine", demoTag: DemoTag(worldID: thisWorld)),
            DemoRecord(id: "other", demoTag: DemoTag(worldID: anotherWorld)),
        ]
        XCTAssertEqual(purgeDemoWorld(records, worldID: thisWorld).map(\.id), ["real", "other"])
    }

    func testPurgeRemovesTheDemoGigFromTheLineAndLeavesRealGigs() {
        let world = UUID()
        let real = localGigSetlist(gigId: "real", artist: "Real", date: "01-10-2026", venue: "", city: "")
        let demo = localGigSetlist(gigId: "demo", artist: "Demo", date: "02-10-2026", venue: "", city: "")
        let records = [DemoRecord(id: demo.id, demoTag: DemoTag(worldID: world))]

        XCTAssertEqual(purgeDemoGigs([real, demo], records: records, worldID: world).map(\.id), ["real"])
    }
}
