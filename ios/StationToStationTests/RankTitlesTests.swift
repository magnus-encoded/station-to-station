import XCTest
@testable import StationToStation

/// Ranking a remembered line against a catalogue (#126), ported from Android's
/// `BillTest` with its fixtures.
///
/// These are twins on purpose. The two implementations answering differently would
/// mean a **Log** corrected on one phone and re-opened on the other offered a
/// different first candidate — the same file, two answers.
///
/// The band and titles in the first case are the real one #126 was written from:
/// published song names, which is catalogue data rather than anyone's concert
/// history. Everything else is invented.
final class RankTitlesTests: XCTestCase {

    private let catalogue = ["High and Apple Sweet", "Vardhavn", "Toothpicks and Gum", "Paper Cranes"]

    /// The case that motivated this, with the real numbers behind it: the contained
    /// title scores 1.00 where the next candidate scores 0.25, on the word "and".
    func testTheContainedTitleRanksFirstByAWideMargin() {
        let ranked = rankTitles("All held together by toothpicks and gum", catalogue)

        XCTAssertEqual("Toothpicks and Gum", ranked.first)
        XCTAssertEqual("High and Apple Sweet", ranked[1], "one word shared, and only one")
        XCTAssertEqual(catalogue.count, ranked.count, "the whole pool stays reachable")
    }

    /// Punctuation is thrown away exactly as it is everywhere recognition happens.
    func testRankingIgnoresPunctuationAndCase() {
        XCTAssertEqual(
            "Don't Look Back",
            rankTitles("i think it was dont look back", ["Vardhavn", "Don't Look Back"]).first
        )
    }

    /// Degrades to "nothing confident" rather than promoting a bad match: with no
    /// words in common the pool comes back in the order it came in.
    func testALineSharingNoWordsWithAnyTitleLeavesTheOrderAlone() {
        let pool = ["Vardhavn", "Paper Cranes", "Hollowmoor"]

        XCTAssertEqual(pool, rankTitles("something else entirely", pool))
        XCTAssertEqual(pool, rankTitles("", pool))
    }

    /// A title is not "contained" across a word boundary: on a spacing-stripped key
    /// "Sand" sits inside "toothpick*s and* gum", and containment is worth a whole
    /// point, so the coincidence would outrank the title the line actually names.
    func testATitleSpanningTwoWordsIsNotAContainedMatch() {
        let ranked = rankTitles("All held together by toothpicks and gum", ["Sand", "Toothpicks and Gum"])

        XCTAssertEqual("Toothpicks and Gum", ranked.first)
    }

    /// The same, with nothing to outrank it: a coincidence must not lead on its own.
    func testAWordBoundaryCoincidenceDoesNotBeatARealWordMatch() {
        let ranked = rankTitles("All held together by toothpicks and gum", ["Sand", "Gum"])

        XCTAssertEqual("Gum", ranked.first)
    }

    /// Stable, so the same sheet does not reshuffle between openings. Swift's sort is
    /// not stable on its own, which is why the implementation carries an index.
    func testEqualCandidatesKeepTheOrderTheCatalogueGaveThem() {
        let tied = ["One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight",
                    "Nine", "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen",
                    "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen", "Twenty",
                    "Twenty One", "Twenty Two", "Twenty Three", "Twenty Four"]

        XCTAssertEqual(tied, rankTitles("nothing in common with any of them", tied))
    }

    func testAnEmptyCatalogueRanksNothingRatherThanFailing() {
        XCTAssertEqual([], rankTitles("anything at all", []))
    }
}
