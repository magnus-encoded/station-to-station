import Foundation
import XCTest
@testable import StationToStation

@MainActor
final class TourNightArrivesEffectsTests: XCTestCase {
    private struct Online: TourConnectivity {
        func isOnline() -> Bool { true }
    }

    private var suite = ""
    private var store: UserDefaults!
    private var deletedEvents: [String] = []
    private let here = TourLocation(latitude: 59.91, longitude: 10.75)
    private var gig: FmSetlist {
        var gig = localGigSetlist(gigId: "demo", artist: "Band", date: "01-01-2030", venue: "Room", city: "")
        gig.venue?.city = FmCity(coords: FmCoords(lat: here.latitude, long: here.longitude))
        return gig
    }

    override func setUp() async throws {
        suite = "TourNightArrivesEffectsTests.\(UUID().uuidString)"
        store = UserDefaults(suiteName: suite)!
        deletedEvents = []
    }

    override func tearDown() async throws {
        store.removePersistentDomain(forName: suite)
    }

    private func makeTour() -> (TourController, TourNightArrivesEffects, FakeState) {
        let host = FakeState()
        let addGig = TourAddGigEffects(store: store) { _ in }
        let night = TourNightArrivesEffects(
            store: store,
            demoGig: { [unowned self] in self.gig },
            deleteCalendarEvent: { [unowned self] in self.deletedEvents.append($0) })
        let tour = TourController(host: host, settings: Settings(store: store), connectivity: Online(),
                                  demoWorld: DemoWorldRegistry(parts: [night, addGig]),
                                  addGig: addGig, night: night)
        return (tour, night, host)
    }

    private func walkToS10(_ tour: TourController) {
        tour.start()
        tour.send(.acknowledged)
        tour.send(.curtainPulled)
        tour.send(.bandPicked)
        tour.gigAdded("demo")
        tour.send(.roomOpened)
        tour.send(.swipedBack)
        tour.send(.contactExchanged(location: here))
        tour.send(.pinchedOut)
        tour.send(.ticketImported)
    }

    private func isRealClock(_ date: Date) -> Bool { abs(date.timeIntervalSinceNow) < 60 }

    func testTheNightArrivesUnderTheDemoClockFromS10ToS14() throws {
        let (tour, _, host) = makeTour()
        walkToS10(tour)
        XCTAssertEqual(host.state.tourStep, .s10)
        XCTAssertEqual(host.state.tourCoachMark, .calendar)

        let approaching = tour.now(for: "demo")
        XCTAssertEqual(approaching, TourNightArrivesEffects.instant(.approaching, gigDate: "01-01-2030"))
        XCTAssertEqual(gigTimeState(now: approaching, gigDate: "01-01-2030"), .approaching)
        XCTAssertFalse(canCheckInManually(gig: gig, now: approaching))
        XCTAssertTrue(isRealClock(tour.now(for: "a real gig")))

        tour.calendarAdded("a real gig", eventId: "real-event")
        XCTAssertEqual(host.state.tourStep, .s10)
        tour.calendarAdded("demo", eventId: "demo-event")
        XCTAssertEqual(host.state.tourStep, .s11)
        XCTAssertEqual(host.state.tourCoachMark, .maps)

        let maps = try XCTUnwrap(tour.mapsURL(for: gig))
        XCTAssertTrue(maps.absoluteString.contains("ll=59.91,10.75"), maps.absoluteString)
        XCTAssertNil(tour.mapsURL(for: localGigSetlist(gigId: "real", artist: "B", date: "01-01-2030",
                                                         venue: "V", city: "")))
        tour.mapsOpened("demo")
        XCTAssertEqual(host.state.tourStep, .s12)
        XCTAssertEqual(host.state.tourCoachMark, .ticket)

        let doors = tour.now(for: "demo")
        XCTAssertEqual(gigTimeState(now: doors, gigDate: "01-01-2030"), .dayOf)
        XCTAssertTrue(canCheckInManually(gig: gig, now: doors))
        XCTAssertEqual(checkInCandidate(gigs: [gig], now: doors, where: (here.latitude, here.longitude))?.id, "demo")
        XCTAssertTrue(atVenue(where: (here.latitude, here.longitude), venue: try XCTUnwrap(gig.cityCoords())))

        tour.ticketShown("demo")
        XCTAssertEqual(host.state.tourStep, .s13)
        XCTAssertEqual(host.state.tourCoachMark, .checkIn)
        tour.checkedIn("a real gig")
        XCTAssertEqual(host.state.tourStep, .s13)
        tour.checkedIn("demo")
        XCTAssertEqual(host.state.tourStep, .s14)
        XCTAssertEqual(tour.now(for: "demo"), TourNightArrivesEffects.instant(.showStarted, gigDate: "01-01-2030"))
    }

    func testSkipDeletesTheCalendarEventAndGivesTheRealClockBack() {
        let (tour, night, _) = makeTour()
        walkToS10(tour)
        tour.calendarAdded("demo", eventId: "demo-event")
        tour.skip()
        XCTAssertEqual(deletedEvents, ["demo-event"])
        XCTAssertNil(night.demoNow)
        XCTAssertEqual(night.calendarEventIds, [])
        XCTAssertTrue(isRealClock(tour.now(for: "demo")))
    }

    func testTheClockIsKeptAcrossALaunchAndPurgedAfterIt() {
        let (tour, _, _) = makeTour()
        walkToS10(tour)
        tour.calendarAdded("demo", eventId: "demo-event")
        let (relaunched, night, host) = makeTour()
        relaunched.start()
        XCTAssertEqual(host.state.tourStep, .s11)
        XCTAssertEqual(relaunched.now(for: "demo"), TourNightArrivesEffects.instant(.approaching, gigDate: "01-01-2030"))
        night.purge()
        XCTAssertEqual(deletedEvents, ["demo-event"])
        XCTAssertTrue(isRealClock(relaunched.now(for: "demo")))
    }

    func testTheDemoClockMarksLandOnTheGigsOwnNight() throws {
        let calendar = Calendar(identifier: .gregorian)
        let after = try XCTUnwrap(TourNightArrivesEffects.instant(.after, gigDate: "01-01-2030", calendar: calendar))
        XCTAssertEqual(gigTimeState(now: after, gigDate: "01-01-2030", calendar: calendar), .past)
        let started = try XCTUnwrap(TourNightArrivesEffects.instant(.showStarted, gigDate: "01-01-2030", calendar: calendar))
        XCTAssertEqual(calendar.component(.hour, from: started), 21)
        XCTAssertTrue(withinCheckInWindow(now: started, gigDate: "01-01-2030", calendar: calendar))
    }
}
