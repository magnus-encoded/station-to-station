import XCTest
@testable import StationToStation

@MainActor
final class TicketsControllerTests: XCTestCase {

    private var fake: FakeState!

    private func controller() -> TicketsController {
        fake = FakeState()
        let store = TimelineStore(file: FileManager.default.temporaryDirectory
            .appendingPathComponent("tickets-\(UUID().uuidString).json"))
        let client = SetlistFmClient(keySource: { nil })
        let settings = Settings()
        let gossip = GossipController(host: fake, timelines: store)
        let gig = GigController(host: fake, timelines: store, setlistFm: client, location: DeviceLocation(), gossip: gossip)
        let setlists = SetlistsController(host: fake, setlistFm: client, musicBrainz: MusicBrainzClient(),
                                          timelines: store, settings: settings,
                                          settingsController: SettingsController(host: fake, settings: settings,
                                                                                 spotify: SpotifyClient(settings)),
                                          gig: gig)
        let planning = PlanningController(host: fake, timelines: store, setlistFm: client,
                                          musicBrainz: MusicBrainzClient(), gig: gig, gossip: gossip,
                                          loadTimeline: {}, markSelectedOwnership: { _, _ in })
        return TicketsController(
            host: fake,
            timelines: store,
            setlistFm: client,
            planning: planning,
            setlists: setlists,
            gig: gig)
    }

    private func draft(artist: String? = nil) -> TicketDraft {
        TicketDraft(ticket: Ticket(artist: artist))
    }

    private func eventually(_ condition: () -> Bool) async {
        for _ in 0..<250 where !condition() {
            try? await Task.sleep(nanoseconds: 20_000_000)
        }
    }

    func testDismissingAPromptDropsThatTicketAndNoOther() {
        let tickets = controller()
        let kept = draft(artist: "Kept")
        let dropped = draft(artist: "Dropped")
        fake.state.ticketDrafts = [kept, dropped]

        tickets.dismissTicket(dropped.id)

        XCTAssertEqual(fake.state.ticketDrafts, [kept])
    }

    func testAPromptAnsweredWithoutAnArtistStaysOpenAndSaysWhy() {
        let tickets = controller()
        let open = draft()
        fake.state.ticketDrafts = [open]

        tickets.confirmTicket(open.id, artist: "  ", venue: "", date: "14-09-2031")

        XCTAssertEqual(fake.state.ticketDrafts, [open])
        XCTAssertEqual(fake.state.error, "A night needs who is playing and a date as dd-MM-yyyy.")
    }

    func testAnAnswerForATicketNoLongerOnThePromptDoesNothing() {
        let tickets = controller()

        tickets.confirmTicket(UUID(), artist: "Dumdumboys", venue: "", date: "14-09-2031")

        XCTAssertNil(fake.state.error)
        XCTAssertTrue(fake.state.ticketDrafts.isEmpty)
    }

    func testAConfirmedTicketForANightAlreadyOnTheLineMintsNothing() async {
        let tickets = controller()
        fake.state.timelineShows = [FmSetlist(id: "known-night", eventDate: "14-09-2031",
                                              artist: FmArtist(name: "Dumdumboys"))]
        let open = draft()
        fake.state.ticketDrafts = [open]

        tickets.confirmTicket(open.id, artist: "Dumdumboys", venue: "", date: "14-09-2031")

        XCTAssertTrue(fake.state.ticketDrafts.isEmpty)
        await eventually { fake.state.notice != nil }
        XCTAssertEqual(fake.state.notice, "That night is already on your line.")
        XCTAssertTrue(fake.state.plannedGigs.isEmpty)
    }

    func testAConfirmedTicketForANewNightIsPlannedThroughPlanning() async {
        let tickets = controller()
        let open = draft()
        fake.state.ticketDrafts = [open]

        tickets.confirmTicket(open.id, artist: "Dumdumboys", venue: "Rockefeller", date: "14-09-2031")

        await eventually { !fake.state.plannedGigs.isEmpty }
        XCTAssertEqual(fake.state.plannedGigs.count, 1)
        XCTAssertEqual(fake.state.plannedGigs.first?.artist?.name, "Dumdumboys")
        XCTAssertEqual(fake.state.plannedGigs.first?.venue?.name, "Rockefeller")
        XCTAssertEqual(fake.state.plannedGigs.first?.eventDate, "14-09-2031")
        XCTAssertNil(fake.state.notice)
    }

    func testAConfirmedTicketForANightAlreadyPastLandsAttendedNotPlanned() async {
        let tickets = controller()
        let open = draft()
        fake.state.ticketDrafts = [open]

        tickets.confirmTicket(open.id, artist: "Øystein Sunde", venue: "Folketeateret", date: "22-10-2024")

        await eventually { fake.state.plannedGigs.first.flatMap { fake.state.attendanceByGig[$0.id] }?.provenance == "attended" }
        let gigId = fake.state.plannedGigs.first?.id ?? ""
        XCTAssertEqual(fake.state.attendanceByGig[gigId]?.provenance, "attended")
    }
}
