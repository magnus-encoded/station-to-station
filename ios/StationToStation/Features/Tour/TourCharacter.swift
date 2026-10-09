import Foundation

struct TourCharacter: Codable {
    struct Line: Codable {
        let `do`: String
        let why: String?
        let ios: String?
        let android: String?
        var instruction: String { ios ?? `do` }
    }
    struct Notes: Codable { let gapFill: String; let setlistFill: String }
    struct Playlist: Codable { let title: String; let description: String }
    let name: String
    let username: String
    let avatar: String
    let selfie: String
    let cutout: String
    let lines: [String: Line]
    let notes: Notes
    struct History: Codable { let artist: String; let date: String; let venue: String; let city: String }
    let history: [History]?
    let playlist: Playlist

    static let bundled: TourCharacter = {
        guard let url = Bundle.main.url(forResource: "character", withExtension: "json") else {
            preconditionFailure("Missing Tour character.json")
        }
        do { return try decode(Data(contentsOf: url)) }
        catch { preconditionFailure("Invalid Tour character: \(error)") }
    }()

    static let cardSteps = Set((1...19).filter { $0 != 9 && $0 != 17 }.map { "S\($0)" })
    enum Invalid: Error { case missingValue, cardSteps, wordLimit(String) }
    static func decode(_ data: Data) throws -> TourCharacter {
        let character = try JSONDecoder().decode(Self.self, from: data)
        guard [character.name, character.username, character.avatar, character.selfie, character.cutout,
               character.notes.gapFill, character.notes.setlistFill, character.playlist.title,
               character.playlist.description].allSatisfy({ !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty })
        else { throw Invalid.missingValue }
        guard let history = character.history, !history.isEmpty, history.allSatisfy({ night in
            [night.artist, night.venue, night.city].allSatisfy { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty } &&
                isoDate(fromFm: night.date) != nil
        }) else { throw Invalid.missingValue }
        guard Set(character.lines.keys) == cardSteps else { throw Invalid.cardSteps }
        for (step, line) in character.lines {
            for (text, limit) in [(Optional(line.do), 20), (line.why, 30), (line.ios, 20), (line.android, 20)] {
                if let text {
                    let words = text.split(whereSeparator: { $0.isWhitespace })
                    guard !words.isEmpty, words.count <= limit else { throw Invalid.wordLimit(step) }
                }
            }
        }
        return character
    }

    func line(_ mark: TourCoachMark) -> Line { lines[mark.step.rawValue]! }
}

extension TourCoachMark {
    var step: TourStep {
        switch self {
        case .line: return .s1
        case .curtain: return .s2
        case .band: return .s3
        case .addGig: return .s4
        case .openRoom: return .s5
        case .swipeBack: return .s6
        case .exchange: return .s7
        case .pinchOut: return .s8
        case .calendar: return .s10
        case .maps: return .s11
        case .ticket: return .s12
        case .checkIn: return .s13
        case .log: return .s14
        case .gap: return .s15
        case .gossip: return .s16
        case .selfie: return .s18
        case .spotify: return .s19
        }
    }
}
