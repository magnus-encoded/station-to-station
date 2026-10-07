import Foundation

/// The **Virtual friend**, read from `fixtures/tour/character/character.json`, which Android
/// reads too. Swapping the character changes that file and its two images, never this code.
struct TourCharacter: Decodable, Equatable {
    struct Playlist: Decodable, Equatable {
        let title: String
        let description: String
    }

    let name: String
    let avatar: String
    let selfie: String
    let lines: [String: String]
    let playlist: Playlist

    static let resource = "character"

    static func parse(_ data: Data) throws -> TourCharacter {
        try JSONDecoder().decode(TourCharacter.self, from: data)
    }

    /// The bundled character. A missing or broken file is a build mistake, so it traps.
    static let bundled: TourCharacter = {
        guard let url = Bundle.main.url(forResource: resource, withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let character = try? parse(data)
        else { fatalError("Tour character file is missing or malformed") }
        return character
    }()

    /// The friend's line for `step`, keyed by the script table's step id.
    func line(_ step: TourStep) -> String { lines[step.rawValue] ?? "" }
}
