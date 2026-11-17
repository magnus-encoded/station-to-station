import Foundation
import XCTest
@testable import StationToStation

/// The shared link corpus (`fixtures/deeplinks/`): the same link into `parseDeepLink` on
/// both platforms, the same intent out. `fixtures/deeplinks/README.md` is the schema and
/// the rules; Android's `DeepLinkFixturesTest` runs `cases.json` unchanged.
///
/// Required, never skipped, and every mismatch is reported together.
final class DeepLinkFixtureTests: XCTestCase {

    private func cases() throws -> [[String: Any]] {
        let url = URL(fileURLWithPath: #filePath)     // …/ios/StationToStationTests/DeepLinkFixtureTests.swift
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("fixtures/deeplinks/cases.json")
        let data = try Data(contentsOf: url)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [[String: Any]])
    }

    func testEveryLinkMeansWhatTheCorpusSays() throws {
        let cases = try cases()
        XCTAssertGreaterThanOrEqual(cases.count, 70, "fixtures/deeplinks lost cases")
        var failures: [String] = []
        for c in cases {
            let link = c["link"] as! String
            let expected = c["intent"] as AnyObject? ?? NSNull()
            let actual = parseDeepLink(link).map(render) ?? NSNull()
            if !(expected as AnyObject).isEqual(actual) {
                failures.append("\(link)\n    expected \(expected)\n    actual   \(actual)")
            }
        }
        XCTAssertTrue(failures.isEmpty, "\(failures.count) mismatches in fixtures/deeplinks:\n"
                      + failures.joined(separator: "\n"))
    }

    private func render(_ intent: LinkIntent) -> AnyObject {
        var d: [String: Any] = [:]
        switch intent {
        case .open(let screen, let date):
            d = ["type": "open", "screen": screen.rawValue]
            if let date { d["date"] = date }
        case .openGig(let id):
            d = ["type": "openGig", "id": id]
        case .addGig(let artist, let venue, let date):
            d = ["type": "addGig"]
            if let artist { d["artist"] = artist }
            if let venue { d["venue"] = venue }
            if let date { d["date"] = date }
        case .writeToLog(let gigId, let appends, let replacements):
            d = ["type": "writeToLog", "gigId": gigId, "appends": appends,
                 "replacements": Dictionary(uniqueKeysWithValues: replacements.map { (String($0.key), $0.value) })]
        case .legacyPlace(let gigId, let at):
            let word: String
            switch at {
            case .setlist: word = "setlist"
            case .singleLine: word = "singleLine"
            case .woven: word = "woven"
            }
            d = ["type": "legacyPlace", "gigId": gigId, "as": word]
        case .me:
            d = ["type": "me"]
        case .fixture(let name, let open):
            d = ["type": "fixture", "name": name, "open": open]
        case .passThrough(let kind, _):
            d = ["type": "passThrough", "kind": kind.rawValue]
        }
        return d as NSDictionary
    }
}
