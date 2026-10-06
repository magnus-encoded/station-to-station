import XCTest
@testable import StationToStation

@MainActor
final class TicketsControllerTests: XCTestCase {

    private var fake: FakeState!
    private var minted: [(artist: String, venue: String, night: Date)] = []
    // Held here: the Tickets controller keeps these unowned, as AppModel's are.
    private var gossip: GossipController!
    private var gig: GigController!
    private var setlists: SetlistsController!

    private func controller() -> TicketsController {
        fake = FakeState()
        minted = []
        let store = TimelineStore(file: FileManager.default.temporaryDirectory
            .appendingPathComponent("tickets-\(UUID().uuidString).json"))
        let client = SetlistFmClient(keySource: { nil })
        gossip = GossipController(host: fake, timelines: store)
        gig = GigController(host: fake, timelines: store, setlistFm: client, location: DeviceLocation(),
                            sortedPlanned: { $0 }, gossip: gossip)
        setlists = SetlistsController(host: fake, setlistFm: client, musicBrainz: MusicBrainzClient(),
                                      timelines: store, settings: Settings(),
                                      saveMySetlistFmUser: { _ in },
                                      adoptSetlist: { _, _, _, _ in true },
                                      storeAttendance: { _, _ in },
                                      lineArtists: { [] })
        return TicketsController(
            host: fake,
            timelines: store,
            setlistFm: client,
            mintPlannedGig: { [unowned self] artist, venue, night in
                minted.append((artist, venue, night))
                return "minted"
            },
            planFmGig: { _ in },
            lineArtists: { [] },
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
        XCTAssertTrue(minted.isEmpty)
    }

    func testAConfirmedTicketForANewNightIsPlannedThroughPlanning() async {
        let tickets = controller()
        let open = draft()
        fake.state.ticketDrafts = [open]

        tickets.confirmTicket(open.id, artist: "Dumdumboys", venue: "Rockefeller", date: "14-09-2031")

        await eventually { !minted.isEmpty }
        XCTAssertEqual(minted.count, 1)
        XCTAssertEqual(minted.first?.artist, "Dumdumboys")
        XCTAssertEqual(minted.first?.venue, "Rockefeller")
        XCTAssertEqual(minted.first.map { fmDate($0.night) }, "14-09-2031")
        XCTAssertNil(fake.state.notice)
    }
}
