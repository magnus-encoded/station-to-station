import XCTest
@testable import StationToStation

/// The shared cases in `fixtures/setlistfm-match/` (#531), the same files Android's
/// `SetlistFmMatchTest` reads. A case is required, never skipped: an empty directory
/// fails, because a matcher that agrees with Android on nothing agrees on nothing.
final class SetlistFmMatchTests: XCTestCase {

    private struct Case: Decodable {
        struct Ticket: Decodable {
            var artist: String
            var venue: String?
            var date: String
        }
        struct Levels: Decodable {
            var artist: String
            var date: String
            var venue: String?
        }
        struct Expected: Decodable {
            var outcome: String
            var ids: [String]
        }
        var note: String
        var ticket: Ticket
        var lineArtists: [FmArtist]
        var response: SetlistsResponse
        var levels: [String: Levels]
        var expected: Expected

        var lookup: TicketLookup { TicketLookup(artist: ticket.artist, venue: ticket.venue, date: ticket.date) }
    }

    private func cases() throws -> [(name: String, value: Case)] {
        let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("fixtures/setlistfm-match")
        let files = try FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "json" }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
        return try files.map {
            ($0.deletingPathExtension().lastPathComponent,
             try JSONDecoder().decode(Case.self, from: Data(contentsOf: $0)))
        }
    }

    func testEverySharedCaseGivesItsLevels() throws {
        let all = try cases()
        XCTAssertFalse(all.isEmpty, "no cases in fixtures/setlistfm-match")
        for (name, c) in all {
            XCTAssertEqual(Set(c.response.setlist.map(\.id)), Set(c.levels.keys), "\(name): a level for every hit")
            for hit in c.response.setlist {
                let got = fieldLevels(c.lookup, hit: hit, lineArtists: c.lineArtists)
                let want = try XCTUnwrap(c.levels[hit.id])
                XCTAssertEqual(got.artist.rawValue, want.artist, "\(name)/\(hit.id) artist")
                XCTAssertEqual(got.date.rawValue, want.date, "\(name)/\(hit.id) date")
                XCTAssertEqual(got.venue?.rawValue, want.venue, "\(name)/\(hit.id) venue")
            }
        }
    }

    func testEverySharedCaseGivesItsOutcome() throws {
        for (name, c) in try cases() {
            let outcome: String
            let ids: [String]
            switch matchSetlistFm(c.lookup, hits: c.response.setlist, lineArtists: c.lineArtists) {
            case .linked(let candidate): (outcome, ids) = ("linked", [candidate.setlist.id])
            case .ask(let candidates): (outcome, ids) = ("ask", candidates.map(\.setlist.id))
            case .noMatch: (outcome, ids) = ("noMatch", [])
            }
            XCTAssertEqual(outcome, c.expected.outcome, "\(name): \(c.note)")
            XCTAssertEqual(ids, c.expected.ids, "\(name): \(c.note)")
        }
    }

    // MARK: - The fold, which is Android's `foldName` term for term

    func testTheFoldTakesOffCaseAccentsAndTheNordicLettersNFDLeavesAlone() {
        XCTAssertEqual(foldName("RØYKSOPP"), "royksopp")
        XCTAssertEqual(foldName("Röyksopp"), "royksopp")
        XCTAssertEqual(foldName("Bærum"), "baerum")
        XCTAssertEqual(foldName("Sigur Rós"), "sigurros")
        XCTAssertEqual(foldName("Guns N' Roses"), "gunsnroses")
    }
}
