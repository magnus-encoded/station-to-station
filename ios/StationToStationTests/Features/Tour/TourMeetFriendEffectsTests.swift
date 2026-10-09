import Foundation
import XCTest
@testable import StationToStation

@MainActor
final class TourMeetFriendEffectsTests: XCTestCase {
    private struct Online: TourConnectivity {
        func isOnline() -> Bool { true }
    }

    private var suite = ""
    private var store: UserDefaults!
    private var contacts: [Friend] = []
    private var lanes: [String: [String]] = [:]
    private var placedVenue: FmSetlist?
    private var imported: [Ticket] = []
    private let here = TourLocation(latitude: 59.91, longitude: 10.75)
    private let gig = localGigSetlist(gigId: "demo", artist: "Band", date: "01-01-2030",
                                      venue: "Room", city: "")

    override func setUp() async throws {
        suite = "TourMeetFriendEffectsTests.\(UUID().uuidString)"
        store = UserDefaults(suiteName: suite)!
        contacts = []
        lanes = [:]
        placedVenue = nil
        imported = []
    }

    override func tearDown() async throws {
        store.removePersistentDomain(forName: suite)
    }

    private func makeEffects(fix: TourLocation?) -> TourMeetFriendEffects {
        TourMeetFriendEffects(
            store: store,
            friendName: { "Friend from data" },
            demoGig: { [unowned self] in self.gig },
            locate: { fix },
            placeVenue: { [unowned self] in self.placedVenue = $0 },
            addContact: { [unowned self] in self.contacts.append($0) },
            landNights: { [unowned self] key, nights, withdrawn in
                let held = (self.lanes[key] ?? []) + nights.map(\.id)
                self.lanes[key] = held.filter { !withdrawn.contains($0) }
            },
            removeContact: { [unowned self] friend in
                self.contacts.removeAll { $0.publicKey == friend.publicKey }
            },
            importTicket: { [unowned self] in self.imported.append($0) })
    }

    func testExchangePlacesMyGigWithoutDuplicatingItOnTheFriendsLine() async {
        let effects = makeEffects(fix: here)
        let fix = await effects.exchange()
        XCTAssertEqual(fix, here)
        XCTAssertEqual(contacts.map(\.name), ["Friend from data"])
        XCTAssertNotNil(contacts.first?.publicKey)
        XCTAssertEqual(lanes[effects.contactKey!], [])
        XCTAssertEqual(placedVenue?.venue?.city?.coords?.lat, here.latitude)
        XCTAssertEqual(placedVenue?.venue?.city?.coords?.long, here.longitude)
        XCTAssertEqual(placedVenue?.venue?.name, "Room")
    }

    func testNoFixLeavesTheVenueUnplaced() async {
        let effects = makeEffects(fix: nil)
        let fix = await effects.exchange()
        XCTAssertNil(fix)
        XCTAssertNil(placedVenue)
        XCTAssertEqual(contacts.count, 1)
    }

    func testTheFriendsTicketMatchesTheDemoGigThroughTheRealRouting() throws {
        let ticket = try XCTUnwrap(TourMeetFriendEffects.ticket(for: gig)).checkedForRedraw()
        let route = routeTicket(.ticket(ticket), knownNights: [gig], now: Date())
        guard case .match(let id) = route else { return XCTFail("routed \(route)") }
        XCTAssertEqual(id, "demo")
    }

    func testPurgeTakesTheContactAndTheirLineBackAcrossALaunch() async {
        _ = await makeEffects(fix: here).exchange()
        let key = contacts.first?.publicKey
        let relaunched = makeEffects(fix: here)
        relaunched.purge()
        for _ in 0..<50 where !contacts.isEmpty { await Task.yield() }
        XCTAssertEqual(contacts, [])
        XCTAssertEqual(lanes[key ?? ""], [])
        XCTAssertNil(relaunched.contactKey)
        XCTAssertEqual(relaunched.demoNights, [])
    }

    func testExchangePinchAndTicketWalkS7ToS10() async {
        let host = FakeState()
        let addGig = TourAddGigEffects(store: store) { _ in }
        let effects = makeEffects(fix: here)
        let tour = TourController(host: host, settings: Settings(store: store),
                                  connectivity: Online(),
                                  demoWorld: DemoWorldRegistry(parts: [effects, addGig]),
                                  addGig: addGig, meetFriend: effects)
        tour.start()
        XCTAssertNil(tour.exchangeFriendName)
        tour.send(.acknowledged)
        tour.send(.curtainPulled)
        tour.send(.bandPicked)
        tour.gigAdded("demo")
        tour.send(.roomOpened)
        tour.send(.swipedBack)
        XCTAssertEqual(host.state.tourCoachMark, .exchange)
        XCTAssertEqual(tour.exchangeFriendName, "Friend from data")
        tour.send(.pinchedOut)
        XCTAssertEqual(host.state.tourStep, .s7)
        await tour.exchangeWithFriend()
        XCTAssertEqual(tour.state.location, here)
        XCTAssertEqual(host.state.tourCoachMark, .pinchOut)
        XCTAssertNil(tour.exchangeFriendName)
        XCTAssertTrue(imported.isEmpty)
        tour.send(.pinchedOut)
        for _ in 0..<50 where host.state.tourStep != .s10 { await Task.yield() }
        XCTAssertEqual(imported.count, 1)
        XCTAssertEqual(host.state.tourStep, .s10)
    }
}
