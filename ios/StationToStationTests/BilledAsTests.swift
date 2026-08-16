import XCTest
@testable import StationToStation

/// Ported from the Android BilledAsTest, case for case: the label half of #166, which
/// is what an evening of several acts is called when nothing knows it was a festival.
/// The case that motivated it is real — 24 November 2019, Devin Townsend at Sentrum
/// Scene with Haken supporting, which the Line rendered as a Festival called
/// "Sentrum Scene".
final class BilledAsTests: XCTestCase {

    private func show(
        _ id: String,
        _ artist: String,
        songs: Int,
        venue: String = "Sentrum Scene"
    ) -> FmSetlist {
        FmSetlist(
            id: id,
            eventDate: "24-11-2019",
            artist: FmArtist(name: artist),
            venue: FmVenue(name: venue),
            sets: FmSets(set: [FmSet(song: (0..<songs).map { FmSong(name: "song \($0)") })])
        )
    }

    func testTheHeadlinerIsNamedFirstAndTheSupportInParentheses() {
        // Source order deliberately puts the support first: the label must come from
        // the evening, not from however setlist.fm happened to return it.
        let evening = [show("2", "Haken", songs: 7), show("1", "Devin Townsend", songs: 18)]

        XCTAssertEqual("Devin Townsend (Haken)", billedAs(evening))
    }

    func testARoomNeverBecomesTheNameOfAnEvent() {
        let evening = [show("1", "Devin Townsend", songs: 18), show("2", "Haken", songs: 7)]

        XCTAssertFalse(billedAs(evening).contains("Sentrum Scene"))
    }

    /// Song count is a weaker answer to "who played last", not a different question —
    /// with nothing to separate them it must not shuffle the evening around. The first
    /// the source gave wins, so the label is stable between two renders.
    func testATieKeepsTheOrderTheSourceGave() {
        let evening = [show("1", "First", songs: 10), show("2", "Second", songs: 10)]

        XCTAssertEqual("First (Second)", billedAs(evening))
    }

    func testTapeTracksDoNotDecideTheHeadliner() {
        // performed() already excludes walk-on tape; a support with a long interval
        // recording must not outrank the band everyone came for.
        let support = FmSetlist(
            id: "2",
            artist: FmArtist(name: "Haken"),
            sets: FmSets(set: [FmSet(song: (0..<30).map { FmSong(name: "tape \($0)", tape: true) })])
        )

        XCTAssertEqual(
            "Devin Townsend (Haken)",
            billedAs([support, show("1", "Devin Townsend", songs: 18)])
        )
    }

    /// A Node is not a list. One Resolution in is where the whole lineup lives.
    func testAFestivalDayNamesTwoSupportsAndCountsTheRest() {
        let day = [show("1", "QOTSA", songs: 20)]
            + (1...8).map { show("s\($0)", "Act \($0)", songs: 5) }

        XCTAssertEqual("QOTSA (Act 1, Act 2 +6)", billedAs(day))
    }

    func testOneNamedActIsJustThatAct() {
        XCTAssertEqual("Solo", billedAs([show("1", "Solo", songs: 12)]))
    }

    /// The venue comes back only here, where there is nothing else to say — and it is
    /// the last resort rather than the default it used to be.
    func testAnEveningWithNoNamedActsFallsBackToWhatLittleIsKnown() {
        let nameless = FmSetlist(id: "1", venue: FmVenue(name: "Sentrum Scene"))

        XCTAssertEqual("Sentrum Scene", billedAs([nameless]))
        XCTAssertEqual("Several acts", billedAs([FmSetlist(id: "1")]))
    }

    /// The eyebrow above an unidentified label: a smaller claim than FESTIVAL, and
    /// one the data actually supports.
    func testTheKickerCountsNightsRatherThanClaimingAFestival() {
        XCTAssertEqual("ONE NIGHT", eveningKicker([
            show("1", "Devin Townsend", songs: 18),
            show("2", "Haken", songs: 7),
        ]))
        let run = [
            FmSetlist(id: "1", eventDate: "24-11-2019", artist: FmArtist(name: "A")),
            FmSetlist(id: "2", eventDate: "25-11-2019", artist: FmArtist(name: "B")),
        ]
        XCTAssertEqual("2 NIGHTS", eveningKicker(run))
    }
}
