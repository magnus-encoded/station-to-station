import XCTest
@testable import StationToStation

/// When a local **Gig** is next looked up on setlist.fm (#531), asserted case for case
/// against `fixtures/setlistfm-lookup/`, which Android's `SetlistFmLookupScheduleTest`
/// reads too. A fixed UTC calendar throughout, `GigTimeStateTests`'s convention, so the
/// 06:00 boundary is the same instant wherever this runs.
final class SetlistFmLookupScheduleTests: XCTestCase {

    private struct DueCase: Decodable {
        let name: String
        let night: String
        let now: String
        let lastLookupAt: String?
        let participationUntil: String?
        let chipPending: Bool?
        let local: Bool?
        let sharedKey: Bool?
        let sharedQuotaSpentAt: String?
        let expectDue: String?
    }

    private struct ManualCase: Decodable {
        let name: String
        let now: String
        let lastLookupAt: String?
        let expect: String
    }

    private struct Cases<T: Decodable>: Decodable { let cases: [T] }

    private let utc: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar
    }()

    private func fixture<T: Decodable>(_ name: String, as _: T.Type) throws -> [T] {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("fixtures/setlistfm-lookup/\(name)")
        return try JSONDecoder().decode(Cases<T>.self, from: Data(contentsOf: url)).cases
    }

    private func instant(_ text: String?) -> Date? {
        text.flatMap { ISO8601DateFormatter().date(from: $0) }
    }

    func testEverySharedDueCase() throws {
        let cases = try fixture("due.json", as: DueCase.self)
        XCTAssertGreaterThanOrEqual(cases.count, 20, "the fixture must not be empty")
        for c in cases {
            let now = try XCTUnwrap(instant(c.now), c.name)
            let due = setlistFmLookupDue(
                gigDate: c.night, now: now, calendar: utc,
                local: c.local ?? true,
                lastLookupAt: instant(c.lastLookupAt),
                participationUntil: instant(c.participationUntil),
                possibleMatchPending: c.chipPending ?? false,
                sharedKey: c.sharedKey ?? true,
                sharedQuotaSpentAt: instant(c.sharedQuotaSpentAt)?.timeIntervalSince1970)
            XCTAssertEqual(due, instant(c.expectDue), c.name)
        }
    }

    func testEverySharedManualCase() throws {
        let cases = try fixture("manual.json", as: ManualCase.self)
        XCTAssertFalse(cases.isEmpty, "the fixture must not be empty")
        for c in cases {
            let answer = manualSetlistFmLookup(lastLookupAt: instant(c.lastLookupAt),
                                               now: try XCTUnwrap(instant(c.now), c.name))
            let expected: ManualLookup
            switch c.expect {
            case "lookUpNow": expected = .lookUpNow
            case "friction": expected = .friction
            default: return XCTFail("unknown expectation \(c.expect) in \(c.name)")
            }
            XCTAssertEqual(answer, expected, c.name)
        }
    }

    /// The one Gig both halves are asked about: its automatic checks are over, a pull is not.
    func testAGigWhoseAutomaticChecksHaveStoppedCanStillBePulled() throws {
        let last = try XCTUnwrap(instant("2026-10-09T08:00:00Z"))
        let now = try XCTUnwrap(instant("2026-11-01T12:00:00Z"))
        XCTAssertNil(setlistFmLookupDue(gigDate: "25-09-2026", now: now, calendar: utc, local: true,
                                        lastLookupAt: last, participationUntil: nil,
                                        possibleMatchPending: false, sharedKey: true,
                                        sharedQuotaSpentAt: nil))
        XCTAssertEqual(manualSetlistFmLookup(lastLookupAt: last, now: now), .lookUpNow)
    }

    func testTheFrictionWordsAreTheSpecs() {
        XCTAssertEqual(lookupFrictionMessage, "We'll keep checking for you")
    }

    func testAPendingChipIsTheStoredPendingHits() {
        XCTAssertFalse(StoredSetlistFmLookup().possibleMatchPending)
        XCTAssertTrue(StoredSetlistFmLookup(pendingHitIds: ["63de6d5b"]).possibleMatchPending)
    }

    func testALookupIsStampedAndKeepsWhatWasRejected() {
        let before = StoredSetlistFmLookup(lastLookupAt: 1, rejectedIds: ["r1"])
        XCTAssertEqual(before.lookedUp(at: 42), StoredSetlistFmLookup(lastLookupAt: 42, rejectedIds: ["r1"]))
    }

    func testNoneOfTheseRejectsEveryPendingHitAndTheyAreNeverOfferedAgain() {
        let asked = StoredSetlistFmLookup(rejectedIds: ["r1"], pendingHitIds: ["h1", "h2"])
        let answered = asked.rejectingPending()
        XCTAssertEqual(answered.rejectedIds, ["r1", "h1", "h2"])
        XCTAssertEqual(answered.pendingHitIds, [])
        XCTAssertFalse(answered.possibleMatchPending)
        XCTAssertEqual(answered.unrejected(["h1", "h3", "r1", "h2"]), ["h3"])
    }

    func testRejectingTwiceRemembersAHitOnce() {
        let asked = StoredSetlistFmLookup(rejectedIds: ["h1"], pendingHitIds: ["h1"])
        XCTAssertEqual(asked.rejectingPending().rejectedIds, ["h1"])
    }

    /// Written before #531: no `setlistFmLookup` key at all. It must read as never looked up.
    func testAnAttendanceRecordWrittenBeforeTheLookupStateDecodesWithNone() throws {
        let old = Data(#"{"provenance":"checked_in","checkedInAt":42,"ticketQr":"VEtU"}"#.utf8)
        let decoded = try JSONDecoder().decode(StoredAttendance.self, from: old)
        XCTAssertEqual(decoded, StoredAttendance(provenance: "checked_in", checkedInAt: 42, ticketQr: "VEtU"))
        XCTAssertNil(decoded.setlistFmLookup)
    }

    func testTheLookupStateRoundTripsInsideTheAttendanceRecord() throws {
        let attendance = StoredAttendance(provenance: "planned", setlistFmLookup: StoredSetlistFmLookup(
            lastLookupAt: 1_790_000_000_000, rejectedIds: ["63de6d5b"], pendingHitIds: ["4bd6af3a", "13d6a5b9"]))
        let decoded = try JSONDecoder().decode(StoredAttendance.self, from: JSONEncoder().encode(attendance))
        XCTAssertEqual(decoded, attendance)
    }

    /// Android writes every default, iOS may not; either side must read a partial record.
    func testALookupStateWithFieldsMissingDecodesToTheirDefaults() throws {
        let partial = Data(#"{"provenance":"planned","setlistFmLookup":{"lastLookupAt":7}}"#.utf8)
        let decoded = try JSONDecoder().decode(StoredAttendance.self, from: partial)
        XCTAssertEqual(decoded.setlistFmLookup, StoredSetlistFmLookup(lastLookupAt: 7))
    }

    /// Android's `encodeDefaults` writes the empty lists and a null time; that reads too.
    func testALookupStateAndroidWroteDecodes() throws {
        let android = Data(("{\"provenance\":\"planned\",\"checkedInAt\":null,\"venueLat\":null,"
            + "\"venueLon\":null,\"ticketQr\":null,\"setlistFmLookup\":"
            + "{\"lastLookupAt\":null,\"rejectedIds\":[\"r1\"],\"pendingHitIds\":[]}}").utf8)
        let decoded = try JSONDecoder().decode(StoredAttendance.self, from: android)
        XCTAssertEqual(decoded.setlistFmLookup, StoredSetlistFmLookup(rejectedIds: ["r1"]))
    }

    /// Or "once a day" becomes "once a launch".
    func testTheLastLookupSurvivesARestart() async {
        let file = FileManager.default.temporaryDirectory
            .appendingPathComponent("timelines-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: file) }
        let lookup = StoredSetlistFmLookup(lastLookupAt: 42, rejectedIds: ["r1"], pendingHitIds: ["h1"])
        let id = await TimelineStore(file: file).createLocalGig(date: "25-09-2026", artist: "Big Thief", venue: "")
        await TimelineStore(file: file).saveAttendance(setlistId: id, attendance: StoredAttendance(setlistFmLookup: lookup))
        let reloaded = await TimelineStore(file: file).load().attendance()[id]?.setlistFmLookup
        XCTAssertEqual(reloaded, lookup)
    }
}
