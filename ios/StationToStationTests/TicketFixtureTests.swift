import Foundation
import XCTest
@testable import StationToStation

/// The shared ticket corpus (`fixtures/ticket/`, #526): the same readings into
/// `parseTicketFields` on both platforms, and the same fields out, each with the same
/// support. Android's `TicketFixturesTest` runs these files unchanged.
///
/// Required, never skipped: a missing corpus would make the parity this exists for
/// pass by saying nothing.
final class TicketFixtureTests: XCTestCase {

    private struct Case: Decodable {
        struct Reading: Decodable {
            var origin: TicketReading.Origin
            var lines: [String]
        }
        struct Field: Decodable {
            var value: String
            var support: TicketSupport
        }
        struct Expected: Decodable {
            var artist: Field?
            var venue: Field?
            /// dd-MM-yyyy, the one shape both platforms write.
            var date: Field?
            var skipsPrompt: Bool
        }
        var readings: [Reading]
        /// The decoded payload, as UTF-8. Absent when the case has no barcode.
        var barcode: String?
        var expected: Expected
    }

    private let calendar: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "Europe/Oslo")!
        return c
    }()

    private var dir: URL {
        URL(fileURLWithPath: #filePath)     // …/ios/StationToStationTests/TicketFixtureTests.swift
            .deletingLastPathComponent()    // …/ios/StationToStationTests
            .deletingLastPathComponent()    // …/ios
            .deletingLastPathComponent()    // repo root
            .appendingPathComponent("fixtures/ticket")
    }

    func testEveryCaseReadsAsExpected() throws {
        let files = try FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "json" }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
        XCTAssertGreaterThanOrEqual(files.count, 10, "fixtures/ticket lost cases")

        for file in files {
            let name = file.deletingPathExtension().lastPathComponent
            let fixture = try JSONDecoder().decode(Case.self, from: Data(contentsOf: file))
            let evidence = TicketEvidence(
                readings: fixture.readings.map { TicketReading(origin: $0.origin, lines: $0.lines) },
                barcode: fixture.barcode.map {
                    TicketBarcode(image: Data(), payload: Data($0.utf8), symbology: "qr")
                })

            guard case .ticket(let found) = parseTicketFields(evidence, calendar: calendar) else {
                XCTFail("\(name): read nothing")
                continue
            }
            let want = fixture.expected
            XCTAssertEqual(want.artist?.value, found.artist, "\(name): artist")
            XCTAssertEqual(want.artist?.support, found.artistSupport, "\(name): artist support")
            XCTAssertEqual(want.venue?.value, found.venue, "\(name): venue")
            XCTAssertEqual(want.venue?.support, found.venueSupport, "\(name): venue support")
            XCTAssertEqual(want.date?.value, found.date.map { fmDate($0, calendar: calendar) },
                           "\(name): date")
            XCTAssertEqual(want.date?.support, found.dateSupport, "\(name): date support")
            XCTAssertEqual(want.skipsPrompt, found.canSkipPrompt, "\(name): skips prompt")
        }
    }
}
