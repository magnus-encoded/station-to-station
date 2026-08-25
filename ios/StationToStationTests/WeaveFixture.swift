import Foundation
import XCTest
@testable import StationToStation

/// Loads one case from `fixtures/weave/` — the cross-platform contract described in that
/// directory's README — as the rows it weaves to, plus its Lane order.
///
/// `WeaveFixtureTests` iterates every case and asserts the whole model; this is for
/// suites that want *one* named night to ask a narrower question of, which is what the
/// geometry assertions need. The twin of Android's `WeaveFixture`.
enum WeaveFixture {

    /// The extra keys a fixture carries that the store itself never holds: which Line is
    /// mine, and the friends in Lane order (nearest the Spine first).
    private struct Input: Decodable {
        var me: String
        var friends: [Friend]?
    }

    /// The repo root, found from this file rather than a bundle — the fixtures are
    /// deliberately not iOS resources.
    private static var dir: URL {
        URL(fileURLWithPath: #filePath)     // …/ios/StationToStationTests/WeaveFixture.swift
            .deletingLastPathComponent()    // …/ios/StationToStationTests
            .deletingLastPathComponent()    // …/ios
            .deletingLastPathComponent()    // repo root
            .appendingPathComponent("fixtures/weave")
    }

    /// Skips rather than fails when the directory is absent, matching `WeaveFixtureTests`.
    static func load(_ name: String) async throws -> (rows: [WovenRow], lanes: [Friend]) {
        let caseDir = dir.appendingPathComponent(name)
        guard FileManager.default.fileExists(atPath: caseDir.path) else {
            throw XCTSkip("fixtures/weave/\(name) not present in this checkout (see #36)")
        }
        let data = try Data(contentsOf: caseDir.appendingPathComponent("timelines.json"))
        let input = try JSONDecoder().decode(Input.self, from: data)

        // Through the store, so a fixture is loaded exactly the way the app loads one.
        let file = FileManager.default.temporaryDirectory
            .appendingPathComponent("fixture-\(name)-\(UUID().uuidString).json")
        try data.write(to: file)
        defer { try? FileManager.default.removeItem(at: file) }
        let cache = await TimelineStore(file: file).load()

        let lanes = input.friends ?? []
        let rows = weaveTimelines(
            mine: cache.shows[input.me] ?? [],
            festivals: cache.festivalIdentities(),
            friends: lanes,
            theirs: cache.shows.filter { $0.key != input.me }
        )
        return (rows, lanes)
    }
}
