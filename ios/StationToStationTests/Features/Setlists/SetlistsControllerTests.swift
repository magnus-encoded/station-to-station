import XCTest
@testable import StationToStation

@MainActor
final class SetlistsControllerTests: XCTestCase {

    private let fmNight = FmSetlist(id: "fm1", eventDate: "01-06-2024",
                                    artist: FmArtist(mbid: "mb1", name: "Someone"),
                                    url: "https://www.setlist.fm/fm1")

    func testABlankArtistQuerySearchesNothing() {
        let model = AppModel()
        model.setArtistQuery("   ")
        model.searchArtists()
        XCTAssertFalse(model.state.searchLoading)
    }

    func testABlankUserQueryOpensNoList() {
        let model = AppModel()
        model.state.setlistsTitle = "before"
        model.setUserQuery("  ")
        model.openUserAttended()
        XCTAssertEqual("before", model.state.setlistsTitle)
        XCTAssertFalse(model.state.setlistsLoading)
    }

    func testNoMoreIsLoadedWhileAPageIsLoading() {
        let model = AppModel()
        model.state.setlists = [fmNight]
        model.state.setlistsTotal = 5
        model.state.setlistsLoading = true
        model.loadMoreSetlists()
        XCTAssertEqual(1, model.state.setlistsPage)
    }

    func testNoMoreIsLoadedOnceTheWholeListIsHere() {
        let model = AppModel()
        model.state.setlists = [fmNight]
        model.state.setlistsTotal = 1
        model.loadMoreSetlists()
        XCTAssertFalse(model.state.setlistsLoading)
        XCTAssertEqual(1, model.state.setlistsPage)
    }

    func testAPullWithNoGigOpenAsksNothing() async {
        let model = AppModel()
        await model.pullCurtain(.catalogue)
        await model.pullCurtain(.checkEvent)
        XCTAssertNil(model.state.catalogueFetching)
        XCTAssertNil(model.state.selectedSetlist)
    }

    func testACatalogueAlreadyHeldIsNotAskedForAgain() async {
        let model = AppModel()
        model.state.selectedSetlist = fmNight
        model.state.catalogueByArtist["mb1"] = ["Song"]
        await model.pullCurtain(.catalogue)
        XCTAssertEqual(["Song"], model.state.catalogueByArtist["mb1"])
        XCTAssertNil(model.state.catalogueFetching)
    }

    func testAnArtistWithoutAnMbidHasNoCatalogueToAskFor() async {
        let model = AppModel()
        model.state.selectedSetlist = FmSetlist(id: "fm2", artist: FmArtist(name: "Nobody"),
                                                url: "https://www.setlist.fm/fm2")
        await model.pullCurtain(.catalogue)
        XCTAssertTrue(model.state.catalogueByArtist.isEmpty)
        XCTAssertNil(model.state.catalogueFetching)
    }

    func testAGigWithNoPendingMatchHasNoChipHits() async {
        let model = AppModel()
        model.state.attendanceByGig["local1"] = StoredAttendance()
        let hits = await model.setlistFmChipHits(gigId: "local1")
        XCTAssertTrue(hits.isEmpty)
    }
}
