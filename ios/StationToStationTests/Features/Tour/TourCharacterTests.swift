import Foundation
import XCTest
@testable import StationToStation

/// The shared character file has a line for every step, S1–S20, and its playlist text.
final class TourCharacterTests: XCTestCase {
    private func character() throws -> TourCharacter {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("fixtures/tour/character/character.json")
        return try TourCharacter.parse(Data(contentsOf: url))
    }

    func testEveryStepHasALine() throws {
        let character = try character()
        let missing = TourStep.allCases.map(\.rawValue).filter { (character.lines[$0] ?? "").isEmpty }
        XCTAssertEqual(missing, [], "missing lines")
    }

    func testNameAssetsAndPlaylistAreSet() throws {
        let character = try character()
        XCTAssertFalse(character.name.isEmpty)
        XCTAssertFalse(character.avatar.isEmpty || character.selfie.isEmpty)
        XCTAssertFalse(character.playlist.title.isEmpty || character.playlist.description.isEmpty)
    }

    func testTheAppBundlesTheSameFile() {
        XCTAssertEqual(TourCharacter.bundled, try character())
    }
}
