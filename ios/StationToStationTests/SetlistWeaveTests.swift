import XCTest
@testable import StationToStation

/// The alignment behind the interwoven set (#268). What it decides is which lines are
/// the *same* line, which is the whole of what stops a night being printed twice.
///
/// The same cases Android's `SetlistWeaveTest.kt` runs on the JVM.
final class SetlistWeaveTests: XCTestCase {

    private func sides(_ rows: [WovenSong]) -> [String] {
        rows.map { row in
            let side = row.both ? "=" : (row.published != nil ? "p" : "l")
            return side + "\(row.published ?? row.logged!)"
        }
    }

    func testTwoRecordsOfTheSameSetAreOneLineEach() {
        XCTAssertEqual(["=0", "=1"],
                       sides(weaveSetlist(published: ["Tupelo", "Joy"], logged: ["Tupelo", "Joy"])))
    }

    func testASongIMissedKeepsEveryLaterSongAligned() {
        // The reason this is a diff and not an index walk: one dropped entry used to
        // put every song after it out by one, and the whole tail read as disagreement.
        XCTAssertEqual(
            ["=0", "p1", "=2", "=3"],
            sides(weaveSetlist(published: ["Tupelo", "Joy", "Carnage", "Henry Lee"],
                               logged: ["Tupelo", "Carnage", "Henry Lee"]))
        )
    }

    func testASongOnlyIHaveSitsBetweenThePublishedOnes() {
        XCTAssertEqual(
            ["=0", "l1", "=1"],
            sides(weaveSetlist(published: ["Tupelo", "Joy"],
                               logged: ["Tupelo", "Wild God", "Joy"]))
        )
    }

    func testMatchingIsLooseTheWayRecognitionIsEverywhereElse() {
        // sameSong's terms: typed in the dark, without the apostrophe.
        XCTAssertEqual(["=0"],
                       sides(weaveSetlist(published: ["Don't Look Back"], logged: ["dont look back"])))
    }

    func testAGapMatchesNothingHoweverWellItWouldFit() {
        // "One I couldn't name" is a statement that no title was captured. Pairing it
        // with a published title would invent the claim it exists to avoid making.
        XCTAssertEqual(["p0", "l0"], sides(weaveSetlist(published: ["Tupelo"], logged: [""])))
    }

    func testARowThatIsNotASongIsCarriedThroughAndNeverMatches() {
        // Encore markers arrive as nils, and have to keep their place in the set.
        XCTAssertEqual(
            ["=0", "p1", "=2"],
            sides(weaveSetlist(published: ["Tupelo", nil, "Joy"], logged: ["Tupelo", "Joy"]))
        )
    }

    func testOneSideEmptyIsTheOtherSideInOrder() {
        XCTAssertEqual(["p0", "p1"], sides(weaveSetlist(published: ["A", "B"], logged: [])))
        XCTAssertEqual(["l0", "l1"], sides(weaveSetlist(published: [], logged: ["A", "B"])))
        XCTAssertEqual([], sides(weaveSetlist(published: [], logged: [])))
    }

    func testASongInADifferentPlaceIsInBothPlacesAndNothingElseMoves() {
        // The two records disagree about where B and C sat. Neither is edited to agree
        // with the other, so one of them is printed twice — once where setlist.fm puts
        // it, once where I do. It is B rather than C because a tie goes to the
        // published side.
        //
        // The property worth pinning is that the disagreement stays *local*: D, E and F
        // are still one line each. The index walk this replaced turned a single swap
        // into a tail of false disagreements all the way to the end of the set.
        XCTAssertEqual(
            ["=0", "p1", "=2", "l2", "=3", "=4", "=5"],
            sides(weaveSetlist(published: ["A", "B", "C", "D", "E", "F"],
                               logged: ["A", "C", "B", "D", "E", "F"]))
        )
    }

    func testASongPlayedTwiceStaysTwoLines() {
        // Position is the only thing telling two performances of one song apart, and
        // an LCS that collapsed them would lose the second.
        XCTAssertEqual(
            ["=0", "p1", "=2"],
            sides(weaveSetlist(published: ["Tupelo", "Joy", "Tupelo"], logged: ["Tupelo", "Tupelo"]))
        )
    }
}
