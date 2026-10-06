import XCTest
@testable import StationToStation

@MainActor
final class TicketsControllerTests: XCTestCase {

    private func draft(artist: String? = nil) -> TicketDraft {
        TicketDraft(ticket: Ticket(artist: artist))
    }

    private func eventually(_ condition: () -> Bool) async {
        for _ in 0..<250 where !condition() {
            try? await Task.sleep(nanoseconds: 20_000_000)
        }
    }

    func testDismissingAPromptDropsThatTicketAndNoOther() {
        let model = AppModel()
        let kept = draft(artist: "Kept")
        let dropped = draft(artist: "Dropped")
        model.state.ticketDrafts = [kept, dropped]

        model.dismissTicket(dropped.id)

        XCTAssertEqual(model.state.ticketDrafts, [kept])
    }

    func testAPromptAnsweredWithoutAnArtistStaysOpenAndSaysWhy() {
        let model = AppModel()
        let open = draft()
        model.state.ticketDrafts = [open]

        model.confirmTicket(open.id, artist: "  ", venue: "", date: "14-09-2031")

        XCTAssertEqual(model.state.ticketDrafts, [open])
        XCTAssertEqual(model.state.error, "A night needs who is playing and a date as dd-MM-yyyy.")
    }

    func testAnAnswerForATicketNoLongerOnThePromptDoesNothing() {
        let model = AppModel()
        model.state.ticketDrafts = []

        model.confirmTicket(UUID(), artist: "Dumdumboys", venue: "", date: "14-09-2031")

        XCTAssertNil(model.state.error)
        XCTAssertTrue(model.state.ticketDrafts.isEmpty)
    }

    func testAConfirmedTicketForANightAlreadyOnTheLineMintsNothing() async {
        let model = AppModel()
        model.state.timelineShows = [FmSetlist(id: "known-night", eventDate: "14-09-2031",
                                               artist: FmArtist(name: "Dumdumboys"))]
        let planned = model.state.plannedGigs.count
        let open = draft()
        model.state.ticketDrafts = [open]

        model.confirmTicket(open.id, artist: "Dumdumboys", venue: "", date: "14-09-2031")

        XCTAssertTrue(model.state.ticketDrafts.isEmpty)
        await eventually { model.state.notice != nil }
        XCTAssertEqual(model.state.notice, "That night is already on your line.")
        XCTAssertEqual(model.state.plannedGigs.count, planned)
    }

    func testAConfirmedTicketForANewNightIsPlanned() async {
        let model = AppModel()
        let artist = "Ticket \(UUID().uuidString.prefix(8))"
        let open = draft()
        model.state.ticketDrafts = [open]

        model.confirmTicket(open.id, artist: artist, venue: "Rockefeller", date: "14-09-2031")

        await eventually { model.state.plannedGigs.contains { $0.artist?.name == artist } }
        let gig = model.state.plannedGigs.first { $0.artist?.name == artist }
        XCTAssertEqual(gig?.eventDate, "14-09-2031")
        XCTAssertEqual(gig?.venue?.name, "Rockefeller")
        XCTAssertNotNil(gig.flatMap { model.state.attendanceByGig[$0.id] })
        XCTAssertNil(model.state.notice)
    }
}
