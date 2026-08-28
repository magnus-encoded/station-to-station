import XCTest
@testable import StationToStation

/// **Festival resolution** on iOS (#176): a Festival is uncollapsed **in place**, so
/// the rung exists with no `Route` case and nothing on the navigation stack. That is
/// exactly why **Back out** needs a check of its own — there is no popped screen to
/// prove it happened, and "swipe right does nothing here" was indistinguishable from
/// a working gesture until this asserted otherwise.
final class FestivalRungTests: XCTestCase {

    private func show(_ id: String, _ date: String, _ venue: String) -> FmSetlist {
        FmSetlist(id: id, eventDate: date, artist: FmArtist(name: "Artist \(id)"), venue: FmVenue(name: venue))
    }

    /// One step Inner reaches the Gigs inside; Back out returns one rung Outer.
    @MainActor
    func testOneStepInnerReachesTheGigsAndBackOutReturnsOneRungOuter() {
        let mine = [show("a1", "25-06-2026", "Ekebergsletta"),
                    show("a2", "24-06-2026", "Ekebergsletta")]
        let model = AppModel()

        let collapsed = weaveTimelines(mine: mine, expanded: model.state.expandedFestivals)
        XCTAssertEqual(1, collapsed.count)
        XCTAssertTrue(collapsed[0].node.isFestival)

        model.toggleFestival(collapsed[0].key)
        let open = weaveTimelines(mine: mine, expanded: model.state.expandedFestivals)
        XCTAssertEqual(2, open.filter { $0.depth == 1 }.count) // its two gigs, listed under it

        XCTAssertTrue(model.backOutOfFestivals())
        XCTAssertEqual(collapsed.count,
                       weaveTimelines(mine: mine, expanded: model.state.expandedFestivals).count)
    }

    /// Every uncollapsed Festival sits at the same rung, so Back out leaves that rung
    /// once — not once per festival, which would take several swipes to get Outer.
    @MainActor
    func testBackOutLeavesTheRungWhateverIsOpenOnIt() {
        let model = AppModel()
        model.toggleFestival("one")
        model.toggleFestival("two")

        XCTAssertTrue(model.backOutOfFestivals())
        XCTAssertTrue(model.state.expandedFestivals.isEmpty)
    }

    /// Nothing open is the outermost rung, where **Pinch** replaces **Back out** —
    /// so the gesture reports it did nothing rather than silently swallowing itself.
    @MainActor
    func testWithNoFestivalOpenThereIsNothingToBackOutOf() {
        XCTAssertFalse(AppModel().backOutOfFestivals())
    }
}
