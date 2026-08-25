import XCTest
@testable import StationToStation

/// What becomes one **Node**, and what it is called — the one seam, asserted the way
/// the timeline reads it rather than by how the grouping got there. Ported from the
/// Android FestivalGroupingTest, case for case.
///
/// The names here are invented. This repository is public and no real concert history
/// belongs in a fixture.
final class FestivalGroupingTests: XCTestCase {

    private func show(
        _ id: String,
        _ date: String,
        _ venue: String,
        artist: String? = nil,
        songs: Int = 1,
        info: String? = nil
    ) -> FmSetlist {
        FmSetlist(
            id: id,
            eventDate: date, // dd-MM-yyyy
            artist: FmArtist(name: artist ?? "Artist \(id)"),
            venue: FmVenue(name: venue),
            sets: FmSets(set: [FmSet(song: (0..<songs).map { FmSong(name: "Song \($0)") })]),
            info: info
        )
    }

    /// An identity for `shows`, the way the store hands one over.
    private func identity(
        _ shows: String...,
        name: String = "Hollowmoor Sound 2026",
        dayMembership: [String: [String]]? = nil,
        setTimes: [String: String]? = nil
    ) -> Festivals {
        Festivals(
            byId: ["hm26": StoredFestival(
                id: "hm26", name: name, dayMembership: dayMembership, setTimes: setTimes
            )],
            idByShow: Dictionary(uniqueKeysWithValues: shows.map { ($0, "hm26") })
        )
    }

    // MARK: - The false festival

    /// #166's own case. Two acts, one room, one night, and nothing that knows what the
    /// evening was: a headline show with support, which the app used to draw as a
    /// **Festival** named after the venue.
    func testTwoActsAtOneVenueOnOneNightAreOneSectionNamedFromItsActs() {
        let nodes = groupIntoFestivals([
            show("1", "24-11-2019", "Hollowmoor Hall", artist: "Marrowfield", songs: 18),
            show("2", "24-11-2019", "Hollowmoor Hall", artist: "Pale Ledger", songs: 6),
        ])
        guard nodes.count == 1, case .section = nodes[0] else {
            return XCTFail("expected one Section, got \(nodes.count) nodes")
        }
        XCTAssertEqual("Marrowfield (Pale Ledger)", nodes[0].label)
        XCTAssertFalse(nodes[0].label.contains("Hollowmoor Hall"))
    }

    /// The four-day window is gone: a run of nights is a run of nights.
    func testTwoNightsAtOneVenueWithNoIdentityAreTwoNodes() {
        let nodes = groupIntoFestivals([
            show("1", "25-06-2026", "Hollowmoor Hall"),
            show("2", "24-06-2026", "Hollowmoor Hall"),
        ])
        XCTAssertEqual(2, nodes.count)
        XCTAssertTrue(nodes.allSatisfy { !$0.isSeveral })
    }

    func testALoneShowStaysAConcert() {
        let nodes = groupIntoFestivals([show("1", "10-05-2026", "Hollowmoor Hall")])
        XCTAssertEqual(1, nodes.count)
        XCTAssertFalse(nodes[0].isSeveral)
    }

    func testTwoActsOnOneNightAtDifferentVenuesStayTwoNodes() {
        let nodes = groupIntoFestivals([
            show("1", "25-06-2026", "Hollowmoor Hall"),
            show("2", "25-06-2026", "Pale Ledger Club"),
        ])
        XCTAssertEqual(2, nodes.count)
    }

    /// setlist.fm's `info` is free text, not a name. It must never reach the label.
    func testFreeTextInfoNeverLeaksIntoTheLabel() {
        let nodes = groupIntoFestivals([
            show("1", "08-08-2025", "Hollowmoor Field", info: "a long editorial note"),
            show("2", "08-08-2025", "Hollowmoor Field", info: "First show in Norway"),
        ])
        guard nodes.count == 1 else {
            return XCTFail("expected one Section, got \(nodes.count) nodes")
        }
        XCTAssertFalse(nodes[0].label.contains("First show in Norway"))
        XCTAssertFalse(nodes[0].label.contains("editorial"))
    }

    // MARK: - The identity

    func testTheSameTwoActsWithAnIdentityAreOneFestivalNamedFromIt() {
        let shows = [
            show("1", "24-11-2019", "Hollowmoor Hall"),
            show("2", "24-11-2019", "Hollowmoor Hall"),
        ]
        let nodes = groupIntoFestivals(shows, identity("1", "2"))
        guard nodes.count == 1, case .festival = nodes[0] else {
            return XCTFail("expected one Festival, got \(nodes.count) nodes")
        }
        XCTAssertEqual("Hollowmoor Sound 2026", nodes[0].label)
        XCTAssertEqual(2, nodes[0].shows.count)
    }

    /// Membership follows the identity's own day grouping, so a festival's two days are
    /// one **Node** — while two nights at that venue *without* an identity are two. The
    /// difference is evidence, which is the whole issue.
    func testActsAcrossTwoDaysUnderOneIdentityAreOneFestival() {
        let shows = [
            show("1", "25-06-2026", "Hollowmoor Field"),
            show("2", "25-06-2026", "Hollowmoor Field"),
            show("3", "24-06-2026", "Hollowmoor Field"),
        ]
        let nodes = groupIntoFestivals(shows, identity(
            dayMembership: ["25-06-2026": ["1", "2"], "24-06-2026": ["3"]]
        ))
        guard nodes.count == 1, case .festival = nodes[0] else {
            return XCTFail("expected one Festival, got \(nodes.count) nodes")
        }
        XCTAssertEqual(["1", "2", "3"], nodes[0].shows.map(\.id))
    }

    /// One day of a four-day festival is still that festival.
    func testASingleNightCarryingAnIdentityIsAFestivalNotAConcert() {
        let nodes = groupIntoFestivals([show("1", "25-06-2026", "Hollowmoor Field")], identity("1"))
        XCTAssertEqual(1, nodes.count)
        XCTAssertTrue(nodes[0].isIdentified)
    }

    // MARK: - The headliner ladder
    //
    // Three rungs, each a weaker answer to "who played last" — asserted one at a time,
    // so a fallback firing early is visible rather than absorbed.

    func testTheHeadlinerIsTheLatestScheduledSetTime() {
        let shows = [
            // Longest set and first in source order, so only the times can decide it.
            show("1", "25-06-2026", "Hollowmoor Field", artist: "Pale Ledger", songs: 20),
            show("2", "25-06-2026", "Hollowmoor Field", artist: "Marrowfield", songs: 4),
        ]
        XCTAssertEqual(
            "Marrowfield (Pale Ledger)",
            billedAs(shows, setTimes: ["1": "16:00", "2": "22:00"])
        )
    }

    /// A set that ran past midnight closed the evening; it did not open it.
    func testASetAfterMidnightIsTheLastOfTheNightNotTheFirst() {
        let shows = [
            show("1", "25-06-2026", "Hollowmoor Field", artist: "Pale Ledger", songs: 20),
            show("2", "25-06-2026", "Hollowmoor Field", artist: "Marrowfield", songs: 4),
        ]
        XCTAssertEqual(
            "Marrowfield (Pale Ledger)",
            billedAs(shows, setTimes: ["1": "22:00", "2": "00:30"])
        )
    }

    /// The evening as it went, where the source published the running order.
    func testAFestivalListsItsActsInRunningOrder() {
        let shows = [
            show("1", "25-06-2026", "Hollowmoor Field"),
            show("2", "25-06-2026", "Hollowmoor Field"),
        ]
        let nodes = groupIntoFestivals(
            shows, identity("1", "2", setTimes: ["1": "22:00", "2": "16:00"])
        )
        XCTAssertEqual(["2", "1"], nodes[0].runningOrder().map(\.id))
    }

    func testWithNoSetTimesTheHeadlinerIsTheLongestSet() {
        let nodes = groupIntoFestivals([
            show("1", "25-06-2026", "Hollowmoor Hall", artist: "Pale Ledger", songs: 5),
            show("2", "25-06-2026", "Hollowmoor Hall", artist: "Marrowfield", songs: 17),
        ])
        XCTAssertEqual("Marrowfield (Pale Ledger)", nodes[0].label)
    }

    // The two weaker rungs on their own — song count, then source order — are
    // BilledAsTests', along with how many supports a long evening names.
}
