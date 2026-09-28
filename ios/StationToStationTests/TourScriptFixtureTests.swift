import XCTest

/// Loading contract for the platform-neutral Tour script cases in #587.
final class TourScriptFixtureTests: XCTestCase {

    private struct Fixture: Decodable {
        let version: Int
        let interactiveSteps: [String]
        let cases: [Case]
    }

    private struct Case: Decodable {
        let name: String
        let kind: String
        let events: [Event]?
        let event: Event?
        let atSteps: [String]?
        let expect: [String: JSONValue]
    }

    private struct Event: Decodable {
        let type: String
        let value: String?
    }

    private enum JSONValue: Decodable {
        case scalar, array, object

        init(from decoder: Decoder) throws {
            let container = try decoder.singleValueContainer()
            if try container.decodeNil()
                || (try? container.decode(Bool.self)) != nil
                || (try? container.decode(Double.self)) != nil
                || (try? container.decode(String.self)) != nil {
                self = .scalar
            } else if (try? container.decode([JSONValue].self)) != nil {
                self = .array
            } else {
                _ = try container.decode([String: JSONValue].self)
                self = .object
            }
        }
    }

    private var fixture: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("fixtures/tour-script/cases.json")
    }

    func testBothPlatformsReceiveEverySharedTourCase() throws {
        let document = try JSONDecoder().decode(Fixture.self, from: Data(contentsOf: fixture))

        XCTAssertEqual(document.version, 1)
        XCTAssertEqual(document.interactiveSteps, (1...19).map { "S\($0)" })
        XCTAssertEqual(
            Set(document.cases.map(\.name)),
            Set([
                "happy path",
                "offline start",
                "skip at every step",
                "resume at every step",
                "replay",
                "spotify declined",
                "spotify retry",
                "fill from setlist.fm",
                "fill from MusicBrainz fallback",
                "out-of-order event",
            ])
        )
        XCTAssertEqual(document.cases.count, Set(document.cases.map(\.name)).count)

        let skip = try XCTUnwrap(document.cases.first { $0.name == "skip at every step" })
        let resume = try XCTUnwrap(document.cases.first { $0.name == "resume at every step" })
        XCTAssertEqual(skip.atSteps, document.interactiveSteps)
        XCTAssertEqual(resume.atSteps, document.interactiveSteps)
        XCTAssertEqual(skip.event?.type, "skipped")
        XCTAssertEqual(resume.event?.type, "resumed")
        XCTAssertTrue(document.cases.allSatisfy { !$0.expect.isEmpty })
    }
}
