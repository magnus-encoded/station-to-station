import XCTest
@testable import StationToStation

/// The fixtures in `fixtures/setlistfm-match/` are the contract for #531's matcher: the
/// same **Ticket** and the same recorded `search/setlists` response must come to the
/// same answer, with the same level on every field, in Swift and in Kotlin
/// (`SetlistFmMatchFixturesTest`). Adding a case needs no code change here — this
/// iterates the directory.
final class SetlistFmMatchFixtureTests: XCTestCase {

    /// The repo root, found from this file rather than a bundle — the fixtures are
    /// deliberately not iOS resources.
    private var fixturesDir: URL {
        URL(fileURLWithPath: #filePath)             // …/ios/StationToStationTests/SetlistFmMatchFixtureTests.swift
            .deletingLastPathComponent()            // …/ios/StationToStationTests
            .deletingLastPathComponent()            // …/ios
            .deletingLastPathComponent()            // repo root
            .appendingPathComponent("fixtures/setlistfm-match")
    }

    /// GMT, the zone `parseFmDate` reads in, so a fixture's day survives the trip into
    /// a `Ticket`'s `Date` and back out through `fmDate` unchanged.
    private let calendar: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(secondsFromGMT: 0)!
        return c
    }()

    /// What the parser handed over, plus the artists already on the **Line**.
    private struct TicketInput: Decodable {
        var artist: String?
        var venue: String?
        var date: String?
        var line: [FmArtist]?
    }

    private struct Levels: Decodable, Equatable {
        var artist: String
        var date: String
        var venue: String?
    }

    private struct Expected: Decodable {
        var outcome: String
        var linked: String?
        var ask: [String]?
        /// Every hit that survives, by setlist id. A hit absent here must be dropped.
        var levels: [String: Levels]
    }

    /// The fixture's spelling, so a failure reads the same as the file it came from.
    private func word(_ level: MatchLevel?) -> String? {
        switch level {
        case .strong: return "strong"
        case .weak: return "weak"
        case .noMatch: return "noMatch"
        case nil: return nil
        }
    }

    private func levels(_ c: SetlistFmCandidate) -> Levels {
        Levels(artist: word(c.artist)!, date: word(c.date)!, venue: word(c.venue))
    }

    private func load(_ caseDir: URL) throws -> (Ticket, [FmSetlist], [FmArtist], Expected) {
        let decoder = JSONDecoder()
        let input = try decoder.decode(
            TicketInput.self, from: Data(contentsOf: caseDir.appendingPathComponent("ticket.json")))
        let hits = try decoder.decode(
            SetlistsResponse.self, from: Data(contentsOf: caseDir.appendingPathComponent("search-setlists.json")))
        let expected = try decoder.decode(
            Expected.self, from: Data(contentsOf: caseDir.appendingPathComponent("expected.json")))
        let ticket = Ticket(artist: input.artist, venue: input.venue, date: input.date.flatMap(parseFmDate))
        return (ticket, hits.setlist, input.line ?? [], expected)
    }

    func testEveryCaseComesToItsExpectedAnswer() throws {
        let dir = fixturesDir
        guard FileManager.default.fileExists(atPath: dir.path) else {
            throw XCTSkip("fixtures/setlistfm-match not present in this checkout")
        }
        let cases = try FileManager.default
            .contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)
            .filter { $0.hasDirectoryPath }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
        XCTAssertFalse(cases.isEmpty, "fixtures/setlistfm-match is empty")

        for caseDir in cases {
            let name = caseDir.lastPathComponent
            let (ticket, hits, line, expected) = try load(caseDir)

            let ranked = rankSetlistFmHits(ticket, hits: hits, lineArtists: line, calendar: calendar)
            var actual: [String: Levels] = [:]
            for candidate in ranked { actual[candidate.setlist.id] = levels(candidate) }
            XCTAssertEqual(actual, expected.levels, "\(name): levels of the surviving hits")

            switch matchSetlistFm(ticket, hits: hits, lineArtists: line, calendar: calendar) {
            case .linked(let candidate):
                XCTAssertEqual(expected.outcome, "linked", "\(name): outcome")
                XCTAssertEqual(candidate.setlist.id, expected.linked, "\(name): linked id")
            case .ask(let candidates):
                XCTAssertEqual(expected.outcome, "ask", "\(name): outcome")
                XCTAssertEqual(candidates.map(\.setlist.id), expected.ask ?? [], "\(name): candidates, best first")
            case .noMatch:
                XCTAssertEqual(expected.outcome, "noMatch", "\(name): outcome")
            }
        }
    }

    /// No artist or no day is nothing to look up with — the spec's own precondition.
    func testATicketWithoutArtistOrDateMatchesNothing() throws {
        let caseDir = fixturesDir.appendingPathComponent("link-strong-on-every-field")
        guard FileManager.default.fileExists(atPath: caseDir.path) else {
            throw XCTSkip("fixtures/setlistfm-match not present in this checkout")
        }
        let (_, hits, _, _) = try load(caseDir)
        let noArtist = Ticket(venue: "Kjøkkenhagen Scene", date: parseFmDate("12-03-2027"))
        let noDate = Ticket(artist: "Ferrous Owls", venue: "Kjøkkenhagen Scene")
        for ticket in [noArtist, noDate] {
            guard case .noMatch = matchSetlistFm(ticket, hits: hits, lineArtists: [], calendar: calendar) else {
                return XCTFail("expected no match for \(ticket)")
            }
        }
    }
}
