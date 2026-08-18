import XCTest
@testable import StationToStation

/// The same assertions Android's `PreambleTest.kt` runs on the JVM (#50,
/// ported for iOS by #170).
///
/// Every name, venue and band here is invented — this repository is public
/// and no real concert history belongs in a fixture.
final class PreambleTests: XCTestCase {

    func testEveryClauseKnownReadsAsOneSentence() {
        XCTAssertEqual(
            "I was here with Ozzy and Lemmy at Verandaen, seeing this set.",
            preamble(people: ["Ozzy", "Lemmy"], venue: "Verandaen", songCount: 14)
        )
    }

    func testNoVenueDropsThePlaceAndNothingElse() {
        XCTAssertEqual(
            "I was here with Ozzy, seeing this set.",
            preamble(people: ["Ozzy"], venue: nil, songCount: 14)
        )
    }

    func testNobodyElseDropsTheCompanyAndNothingElse() {
        XCTAssertEqual(
            "I was here at Verandaen, seeing this set.",
            preamble(people: [], venue: "Verandaen", songCount: 14)
        )
    }

    func testNoSetlistDropsTheSetAndNothingElse() {
        XCTAssertEqual(
            "I was here with Ozzy at Verandaen.",
            preamble(people: ["Ozzy"], venue: "Verandaen", songCount: 0)
        )
    }

    /// The 1992 import: attended, undated beyond the year, no venue and no
    /// record. It gets no sentence rather than a sentence with holes in it —
    /// and it is exactly the night someone most wants to write about from
    /// memory.
    func testNothingKnownRendersNothingAtAll() {
        XCTAssertEqual("", preamble(people: [], venue: nil, songCount: 0))
        XCTAssertEqual("", preamble(people: ["  "], venue: "   ", songCount: 0))
    }

    func testOneNameReadsWithoutAConjunctionAndThreeReadAsAList() {
        XCTAssertEqual("I was here with Ida.", preamble(people: ["Ida"]))
        XCTAssertEqual(
            "I was here with Ida, Ozzy and Lemmy.",
            preamble(people: ["Ida", "Ozzy", "Lemmy"])
        )
    }

    /// Reconcile has no time bound, so the cast changes years later. The
    /// sentence is a function of the record precisely so that it improves
    /// instead of going stale — this is the property that makes storing it
    /// wrong.
    func testAContactDiscoveredLaterSimplyAppearsInTheSentence() {
        let then = preamble(people: ["Ida"], venue: "Verandaen")
        let now = preamble(people: ["Ida", "Ozzy"], venue: "Verandaen")
        XCTAssertEqual("I was here with Ida at Verandaen.", then)
        XCTAssertEqual("I was here with Ida and Ozzy at Verandaen.", now)
    }
}
