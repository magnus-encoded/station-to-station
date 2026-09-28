import XCTest
@testable import StationToStation

/// Whether a **Contact**'s **Lane** needs a setlist.fm fetch (#405).
///
/// `fixtures/lane-freshness/cases.json` is shared with Android's `LaneFreshnessTest`, which
/// asserts it case for case; so are the two-pass cases below, which no single call to
/// `laneNeedsFetch` can reach. Everything here is synthetic.
final class LaneFreshnessTests: XCTestCase {

    /// The repo root, found from this file rather than a bundle: the fixtures are
    /// deliberately not iOS resources.
    private func fixture(_ path: String) -> URL {
        URL(fileURLWithPath: #filePath)             // …/ios/StationToStationTests/LaneFreshnessTests.swift
            .deletingLastPathComponent()            // …/ios/StationToStationTests
            .deletingLastPathComponent()            // …/ios
            .deletingLastPathComponent()            // repo root
            .appendingPathComponent("fixtures/\(path)")
    }

    private struct Case: Decodable {
        let name: String
        let username: String
        let held: [String]?
        let myOldest: String?
        let fetch: Bool
    }

    private struct Cases: Decodable {
        let cases: [Case]
    }

    /// A Night as setlist.fm serves it, page and all. A hand-logged one has no url.
    private func night(_ date: String, id: String? = nil) -> FmSetlist {
        let id = id ?? "n-\(date)"
        return FmSetlist(id: id, eventDate: date, artist: FmArtist(name: "The Warning"),
                         url: "https://www.setlist.fm/setlist/the-warning/\(id).html")
    }

    func testEveryCaseSaysWhatTheFixtureSays() throws {
        let data = try Data(contentsOf: fixture("lane-freshness/cases.json"))
        let cases = try JSONDecoder().decode(Cases.self, from: data).cases
        XCTAssertFalse(cases.isEmpty, "fixtures/lane-freshness/cases.json is empty")
        for c in cases {
            let mine: Date?
            if let text = c.myOldest {
                mine = try XCTUnwrap(parseFmDate(text), "\(c.name): bad myOldest")
            } else {
                mine = nil
            }
            let got = laneNeedsFetch(
                Friend(setlistfm: c.username),
                held: c.held?.map { night($0) },
                myOldest: mine
            )
            XCTAssertEqual(got, c.fetch, c.name)
        }
        print("LaneFreshnessTests: \(cases.count) fixture cases")
    }

    private let ozzy = Friend(setlistfm: "ozzy")
    private let myOldest = parseFmDate("25-06-2019")

    func testAContactWithNoNightsIsNotFetchedOnASecondPass() {
        var held: [String: [FmSetlist]] = [:]
        XCTAssertTrue(laneNeedsFetch(ozzy, held: held[ozzy.setlistfm], myOldest: myOldest), "first pass")
        // setlist.fm answers: a real user with no attended shows.
        held = holdLanes(held, [ozzy.setlistfm: []])
        XCTAssertFalse(laneNeedsFetch(ozzy, held: held[ozzy.setlistfm], myOldest: myOldest), "second pass")
    }

    func testAFailedFetchIsAskedAgainOnTheNextPass() {
        // A failure is left out of what landed, so nothing is held for them yet.
        let held = holdLanes([:], [:])
        XCTAssertTrue(laneNeedsFetch(ozzy, held: held[ozzy.setlistfm], myOldest: myOldest))
    }

    func testAnEmptyAnswerKeepsALaneThatHadNights() {
        let had = [ozzy.setlistfm: [night("20-06-2019")]]
        let held = holdLanes(had, [ozzy.setlistfm: []])
        XCTAssertEqual(held[ozzy.setlistfm]?.map(\.id), ["n-20-06-2019"])
    }

    func testAFetchedLaneReplacesTheOneHeldAndLeavesTheOthersAlone() {
        let had = [
            ozzy.setlistfm: [night("10-01-2020")],
            "magnus": [night("01-01-2026")],
        ]
        let held = holdLanes(had, [ozzy.setlistfm: [night("20-06-2019")]])
        XCTAssertEqual(held[ozzy.setlistfm]?.map(\.id), ["n-20-06-2019"])
        XCTAssertEqual(held["magnus"]?.map(\.id), ["n-01-01-2026"])
        XCTAssertFalse(laneNeedsFetch(ozzy, held: held[ozzy.setlistfm], myOldest: myOldest))
    }

    /// A Night they logged by hand reached me on the Reconcile (#405). setlist.fm has
    /// never heard of it, so a fetched Lane — however complete — says nothing about it.
    func testAFetchedLaneKeepsTheHandLoggedNightsOnlyTheReconcileCarried() {
        var handLogged = night("14-08-2026", id: "local-1")
        handLogged.url = nil
        let had = [ozzy.setlistfm: [handLogged, night("10-01-2020")]]

        let held = holdLanes(had, [ozzy.setlistfm: [night("20-06-2019")]])

        XCTAssertEqual(["local-1", "n-20-06-2019"], held[ozzy.setlistfm]?.map(\.id))
    }

    /// A Contact with no account is never fetched: what the Reconcile brought is all.
    func testAnAccountlessContactsLaneIsHeldUnderItsKeyAndNeverFetched() {
        let dio = Friend(setlistfm: "", name: "Dio", publicKey: "k-dio")
        var logged = night("01-01-2026", id: "local-2")
        logged.url = nil
        let held = [dio.laneKey: landNights(nil, [logged])]

        XCTAssertFalse(laneNeedsFetch(dio, held: held[dio.laneKey], myOldest: myOldest))
        XCTAssertEqual(["local-2"], held[dio.laneKey]?.map(\.id))
    }
}
