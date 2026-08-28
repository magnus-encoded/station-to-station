import XCTest
@testable import StationToStation

/// The shared weave fixtures (`fixtures/weave/`, issue #36) are the cross-platform
/// contract: the same documents the Kotlin suite runs, outside `android/` and
/// `ios/` on purpose. Adding a case needs no code change here — this iterates the
/// directory.
///
/// Skips rather than fails when the directory is absent, because the fixtures land
/// on their own branch: this suite starts enforcing them the moment they merge,
/// without a second edit here.
final class WeaveFixtureTests: XCTestCase {

    /// The repo root, found from this file rather than a bundle — the fixtures are
    /// deliberately not iOS resources.
    private var fixturesDir: URL {
        URL(fileURLWithPath: #filePath)             // …/ios/StationToStationTests/WeaveFixtureTests.swift
            .deletingLastPathComponent()            // …/ios/StationToStationTests
            .deletingLastPathComponent()            // …/ios
            .deletingLastPathComponent()            // repo root
            .appendingPathComponent("fixtures/weave")
    }

    /// The extra keys a fixture carries that the store itself never holds: which
    /// line is mine, and the friends in Lane order (nearest the Spine first).
    private struct FixtureInput: Decodable {
        var me: String
        var friends: [Friend]?
        /// Which **Festival**s are open, by row key. A **Resolution**, not a fact about
        /// the night — carried because the grammar this corpus pins is claimed *at
        /// every Resolution* (ADR-0006), and a corpus that only ever weaves collapsed
        /// can only ever check one of them. Absent means every Festival is closed,
        /// which is what every fixture written before this said implicitly.
        var expanded: [String]?
    }

    /// Optionals rather than defaults, so the synthesized decoder does the work:
    /// a fixture omits `with`/`together`/`theirs` on a row with no company.
    private struct ExpectedRow: Decodable {
        var key: String
        var date: String?
        var node: String
        var title: String
        var ownership: String
        var with: [String]?
        var together: Int?
        var theirs: Int?
        var hosts: [String: String]?
    }

    private struct Expected: Decodable { var rows: [ExpectedRow] }

    private let isoDay: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(secondsFromGMT: 0)
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    func testEveryFixtureWeavesToItsExpectedRows() async throws {
        let dir = fixturesDir
        guard FileManager.default.fileExists(atPath: dir.path) else {
            throw XCTSkip("fixtures/weave not present in this checkout (see #36)")
        }
        let cases = try FileManager.default
            .contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)
            .filter { $0.hasDirectoryPath }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
        XCTAssertFalse(cases.isEmpty, "fixtures/weave is empty")

        for caseDir in cases {
            let name = caseDir.lastPathComponent
            let storeData = try Data(contentsOf: caseDir.appendingPathComponent("timelines.json"))
            let expectedData = try Data(contentsOf: caseDir.appendingPathComponent("expected.json"))
            let input = try JSONDecoder().decode(FixtureInput.self, from: storeData)
            let expected = try JSONDecoder().decode(Expected.self, from: expectedData)

            // The fixture's own file must load as a plain TimelineCache — its two
            // extra keys are simply unknown to the store, which is the point.
            let file = FileManager.default.temporaryDirectory
                .appendingPathComponent("fixture-\(name)-\(UUID().uuidString).json")
            try storeData.write(to: file)
            defer { try? FileManager.default.removeItem(at: file) }
            let cache = await TimelineStore(file: file).load()

            let mine = cache.shows[input.me] ?? []
            let lanes = input.friends ?? []
            let rows = weaveTimelines(
                mine: mine,
                festivals: cache.festivalIdentities(),
                friends: lanes,
                theirs: cache.shows.filter { $0.key != input.me },
                expanded: Set(input.expanded ?? [])
            )

            XCTAssertEqual(expected.rows.count, rows.count, "\(name): row count")
            for (want, got) in zip(expected.rows, rows) {
                XCTAssertEqual(want.key, got.key, "\(name): key")
                XCTAssertEqual(want.node, nodeKind(got.node), "\(name) \(want.key): node")
                XCTAssertEqual(want.title, got.node.label, "\(name) \(want.key): title")
                XCTAssertEqual(want.ownership, got.ownership.rawValue, "\(name) \(want.key): ownership")
                XCTAssertEqual(want.with ?? [], got.others.map(\.setlistfm), "\(name) \(want.key): with")
                XCTAssertEqual(want.together ?? 0, got.sharedCount, "\(name) \(want.key): together")
                XCTAssertEqual(want.theirs ?? 0, got.showsHereByFriends.count, "\(name) \(want.key): theirs")
                if let date = want.date {
                    XCTAssertEqual(date, got.date.map { isoDay.string(from: $0) }, "\(name) \(want.key): date")
                }
                // Lane 1 is nearest the spine; the indices are 0-based.
                for (who, wantHost) in want.hosts ?? [:] {
                    guard let friend = lanes.first(where: { $0.setlistfm == who }) else {
                        XCTFail("\(name): fixture names \(who), who is not in friends")
                        continue
                    }
                    let lane = hostLane(got, friend, lanes)
                    XCTAssertEqual(wantHost, lane == Spine ? "spine" : "lane\(lane + 1)",
                                   "\(name) \(want.key): \(who)")
                }
            }
        }
    }

    /// The three kinds the corpus names. **A Section is not a nameless Festival** —
    /// it is a Node holding several shows that nothing has identified — so the fixture
    /// distinguishes them and this suite must too.
    private func nodeKind(_ node: TimelineNode) -> String {
        switch node {
        case .festival: return "festival"
        case .section: return "section"
        case .concert: return "gig"
        }
    }
}
