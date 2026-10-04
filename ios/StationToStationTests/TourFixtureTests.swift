import Foundation
import XCTest

final class TourFixtureTests: XCTestCase {
    private func cases() throws -> [[String: Any]] {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("fixtures/tour/cases.json")
        let data = try Data(contentsOf: url)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [[String: Any]])
    }

    func testSharedTourCasesLoad() throws {
        let cases = try cases()
        let rows = Set(cases.compactMap { $0["row"] as? String })
        XCTAssertEqual(rows, expectedRows)
        XCTAssertEqual(cases.filter { $0["row"] as? String == "Skip at Sn" }.count, 19)
        XCTAssertTrue(cases.allSatisfy { !(($0["events"] as? [[String: Any]]) ?? []).isEmpty })
    }

    private let expectedRows: Set<String> = [
        "Happy path", "Offline start", "Skip at Sn", "Resume", "Replay",
        "Spotify declined", "Spotify retry", "Fill: setlist.fm hit",
        "Fill: fallback", "Out-of-order event"
    ]
}
