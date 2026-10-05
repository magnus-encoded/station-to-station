import XCTest
@testable import StationToStation

@MainActor
final class NavigationControllerTests: XCTestCase {

    private func show(_ id: String, _ date: String = "25-06-2025") -> FmSetlist {
        FmSetlist(id: id, eventDate: date, artist: FmArtist(name: "Artist \(id)"), venue: FmVenue(name: "Rockefeller"))
    }

    func testAGigOnMyLineOpensAtOnce() {
        let model = AppModel()
        model.state.timelineShows = [show("3bd6b4f0")]
        var opened = 0

        model.openGig("3bd6b4f0") { opened += 1 }

        XCTAssertEqual(opened, 1)
        XCTAssertEqual(model.state.selectedSetlist?.id, "3bd6b4f0")
    }

    func testAPlannedGigOpensAtOnce() {
        let model = AppModel()
        model.state.plannedGigs = [show("4bd6b4f1", "25-06-2099")]
        var opened = 0

        model.openGig("4bd6b4f1") { opened += 1 }

        XCTAssertEqual(opened, 1)
        XCTAssertEqual(model.state.selectedSetlist?.id, "4bd6b4f1")
    }

    func testAnIdThatIsNoSetlistIsRefusedOnScreen() {
        let model = AppModel()
        var opened = 0

        model.openGig("not a gig") { opened += 1 }

        XCTAssertEqual(opened, 0)
        XCTAssertNil(model.state.selectedSetlist)
        XCTAssertEqual(model.state.error, "That doesn't look like a setlist.fm gig link.")
        XCTAssertNil(model.state.errorKind)
    }

    func testAWovenPlaceLinkZoomsOutAndAsksTheTimelineToFindTheGig() {
        let model = AppModel()
        model.state.friends = [Friend(setlistfm: "alice")]
        var opened = 0

        model.openPlace("3bd6b4f0", as: .woven) { opened += 1 }

        XCTAssertEqual(opened, 0)
        XCTAssertTrue(model.state.zoomedOut)
        XCTAssertEqual(model.state.linkedGig, "3bd6b4f0")
        XCTAssertEqual(model.state.linkedGigAs, .woven)
    }

    func testASetlistPlaceLinkOpensTheGig() {
        let model = AppModel()
        model.state.timelineShows = [show("3bd6b4f0")]
        var opened = 0

        model.openPlace("3bd6b4f0", as: .setlist) { opened += 1 }

        XCTAssertEqual(opened, 1)
        XCTAssertNil(model.state.linkedGig)
    }

    func testHidingALineStampsItAndShowingItClearsIt() {
        let model = AppModel()

        model.toggleLineHidden("alice")
        XCTAssertNotNil(model.state.hiddenAt["alice"])

        model.toggleLineHidden("alice")
        XCTAssertNil(model.state.hiddenAt["alice"])
    }

    func testTheContactLightAlwaysComesOnFaithful() {
        let model = AppModel()
        model.setShowWithheld(true)

        model.toggleContactLight()

        XCTAssertTrue(model.state.contactLight)
        XCTAssertFalse(model.state.showWithheld)
    }

    func testAnAddGigLinkTurnsTheIsoDateRoundForTheForm() {
        let model = AppModel()

        model.openAddGig(artist: "Kvelertak", venue: nil, date: "2025-06-25")

        XCTAssertEqual(model.state.addGigLink, AddGigLink(artist: "Kvelertak", venue: "", date: "25-06-2025"))
    }
}
