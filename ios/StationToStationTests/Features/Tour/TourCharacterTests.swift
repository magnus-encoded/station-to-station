import XCTest
@testable import StationToStation

final class TourCharacterTests: XCTestCase {
    func testBundledCharacterAndOverrides() throws {
        let character = TourCharacter.bundled
        XCTAssertEqual(Set(character.lines.keys), TourCharacter.cardSteps)
        let line = TourCharacter.Line(do: "Base", why: "Reason", ios: "Apple", android: "Android")
        XCTAssertEqual(line.instruction, "Apple")
        XCTAssertEqual(line.why, "Reason")
    }

    func testOpenerAndGenericFallback() {
        let character = TourCharacter.bundled
        XCTAssertEqual(character.line(.log, opener: "Paranoid Android").instruction,
                       "They’re playing “Paranoid Android” as the opener. Type it into the Log, then add it.")
        XCTAssertEqual(character.line(.log).instruction, "Type the first song they played into the Log, then add it.")
    }

    func testMissingStepAndLongInstructionFail() throws {
        let data = try JSONEncoder().encode(TourCharacter.bundled)
        var json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        var lines = try XCTUnwrap(json["lines"] as? [String: Any])
        lines["S16"] = nil
        json["lines"] = lines
        XCTAssertThrowsError(try TourCharacter.decode(JSONSerialization.data(withJSONObject: json)))
        lines = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])["lines"] as! [String: Any]
        lines["S1"] = ["do": Array(repeating: "word", count: 21).joined(separator: " ")]
        json["lines"] = lines
        XCTAssertThrowsError(try TourCharacter.decode(JSONSerialization.data(withJSONObject: json)))
    }

    func testSwappedIdentityComesFromData() throws {
        let data = try JSONEncoder().encode(TourCharacter.bundled)
        var json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        json["name"] = "Ada"
        json["avatar"] = "ada_avatar"
        let swapped = try TourCharacter.decode(JSONSerialization.data(withJSONObject: json))
        XCTAssertEqual(swapped.name, "Ada")
        XCTAssertEqual(swapped.avatar, "ada_avatar")
    }
}
