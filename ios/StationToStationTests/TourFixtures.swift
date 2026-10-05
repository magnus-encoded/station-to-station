import Foundation

struct TourFixtures: Decodable {
    let schemaVersion: Int
    let cases: [Case]

    struct Case: Decodable {
        let id: String
        let row: String
        let online: Bool?
        let initial: State?
        let input: FillInput?
        let checks: [Check]
        let expected: Result?
    }

    struct State: Decodable {
        let step: String?
        let finished: Bool?
        let pendingSpotifyRetry: Bool?
        let completedEffects: [String]?
    }

    struct FillInput: Decodable {
        let enteredSongs: [String]
        let setlistFmSongs: [String]
        let musicBrainzSongs: [String]
    }

    struct Check: Decodable {
        let event: String
        let expect: Observation
    }

    struct Observation: Decodable {
        let step: String?
        let commands: [String]
        let finished: Bool?
        let pendingSpotifyRetry: Bool?
        let unchanged: Bool?
        let freshDemoWorld: Bool?
    }

    struct Result: Decodable {
        let visitedSteps: [String]?
        let finished: Bool?
        let spotifyExported: Bool?
        let playlistExportedBySkip: Bool?
        let source: String?
        let addedSongs: [String]?
        let totalSongs: Int?
    }

    static func load() throws -> [Case] {
        let file = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("fixtures/tour/cases.json")
        let corpus = try JSONDecoder().decode(Self.self, from: Data(contentsOf: file))
        guard corpus.schemaVersion == 1 else {
            throw CocoaError(.coderReadCorrupt)
        }
        return corpus.cases
    }
}
