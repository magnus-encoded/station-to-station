import Foundation
import XCTest
@testable import StationToStation

/// The shared ticket corpus (`fixtures/ticket/`, #526): the same readings into
/// `parseTicketFields` on both platforms, and the same fields out, each with the same
/// support. `fixtures/ticket/README.md` is the schema and the rules; Android's fixture
/// test runs these files unchanged.
///
/// Required, never skipped: a missing corpus would make the parity this exists for
/// pass by saying nothing.
final class TicketFixtureTests: XCTestCase {

    /// What a case expects of one field: a value with its support, nothing found, or
    /// deliberately not asserted.
    private enum Expect<Value: Decodable & Equatable>: Decodable {
        case value(Value)
        case unchecked

        init(from decoder: Decoder) throws {
            let container = try decoder.singleValueContainer()
            if let word = try? container.decode(String.self), word == "unchecked" {
                self = .unchecked
            } else {
                self = .value(try container.decode(Value.self))
            }
        }
    }

    private struct Case: Decodable {
        struct Reading: Decodable {
            var origin: TicketReading.Origin
            var lines: [String]
        }
        struct Barcode: Decodable {
            var symbology: String
            /// The decoded payload, as text.
            var payload: String
            /// Zero-based; 0 when the case leaves it out.
            var page: Int?
        }
        /// One expected **Admission** (#441): the payload as text.
        struct Admission: Decodable, Equatable, CustomStringConvertible {
            var symbology: String
            var payload: String
            var corroborated: Bool
            var description: String { "\(symbology):\(payload)\(corroborated ? " (corroborated)" : "")" }
        }
        struct Field: Decodable, Equatable {
            var value: String
            var support: TicketSupport
        }
        struct Expected: Decodable {
            var artist: Expect<Field?>
            var venue: Expect<Field?>
            /// dd-MM-yyyy, the one shape both platforms write.
            var date: Expect<Field?>
            /// Every **Admission** the result carries, in order.
            var admissions: [Admission]
            var skipsPrompt: Expect<Bool>
        }
        var readings: [Reading]
        var barcodes: [Barcode]
        var expected: Expected
        /// Field name to the reason it is expected to fail, on both platforms.
        var knownFailure: [String: String]?
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
        XCTAssertGreaterThanOrEqual(files.count, 26, "fixtures/ticket lost cases")

        var ran = 0
        for file in files {
            let name = file.deletingPathExtension().lastPathComponent
            let fixture = try JSONDecoder().decode(Case.self, from: Data(contentsOf: file))
            XCTContext.runActivity(named: "fixture \(name)") { _ in
                check(fixture, name)
            }
            ran += 1
        }
        print("TicketFixtureTests: ran \(ran) fixture cases")
    }

    private func check(_ fixture: Case, _ name: String) {
        let known = fixture.knownFailure ?? [:]
        for field in known.keys {
            XCTAssertTrue(["artist", "venue", "date", "skipsPrompt"].contains(field),
                          "\(name): knownFailure names no field \(field)")
        }

        let evidence = TicketEvidence(
            readings: fixture.readings.map { TicketReading(origin: $0.origin, lines: $0.lines) },
            barcodes: fixture.barcodes.map {
                TicketBarcode(image: Data(), payload: Data($0.payload.utf8), symbology: $0.symbology,
                              page: $0.page ?? 0)
            })

        guard case .ticket(let found) = parseTicketFields(evidence, calendar: calendar) else {
            XCTFail("\(name): read nothing")
            return
        }

        /// A known failure is asserted inside `XCTExpectFailure`'s block, and strictly:
        /// if the field starts reading right, this fails until the flag is removed.
        func assertField(_ field: String, _ body: () -> Void) {
            if let reason = known[field] {
                XCTExpectFailure("\(name): \(field) — \(reason)", failingBlock: body)
            } else {
                body()
            }
        }

        let want = fixture.expected
        let date = found.date.map { fmDate($0, calendar: calendar) }
        let fields: [(String, Expect<Case.Field?>, String?, TicketSupport?)] = [
            ("artist", want.artist, found.artist, found.artistSupport),
            ("venue", want.venue, found.venue, found.venueSupport),
            ("date", want.date, date, found.dateSupport),
        ]
        for (field, expect, value, support) in fields {
            guard case .value(let expected) = expect else { continue }
            assertField(field) {
                XCTAssertEqual(expected?.value, value, "\(name): \(field)")
                XCTAssertEqual(expected?.support, support, "\(name): \(field) support")
            }
        }

        XCTAssertEqual(want.admissions, found.admissions.map {
            Case.Admission(symbology: $0.symbology, payload: String(decoding: $0.payload, as: UTF8.self),
                           corroborated: $0.corroborated)
        }, "\(name): admissions")

        if case .value(let skips) = want.skipsPrompt {
            assertField("skipsPrompt") {
                XCTAssertEqual(skips, found.canSkipPrompt, "\(name): skips prompt")
            }
        }
    }
}
