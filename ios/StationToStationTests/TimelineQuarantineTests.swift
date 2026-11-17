import XCTest
@testable import StationToStation

/// `fixtures/timeline/unreadable/` is shared with Android's `TimelineQuarantineTest`, which
/// asserts the same four things of it (see `fixtures/timeline/README.md`).
final class TimelineQuarantineTests: XCTestCase {

    private var dirs: [URL] = []

    override func tearDown() {
        dirs.forEach { try? FileManager.default.removeItem(at: $0) }
        dirs = []
        super.tearDown()
    }

    private func emptyDir() throws -> URL {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("quarantine-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        dirs.append(dir)
        return dir
    }

    private func fixture(_ name: String) throws -> Data {
        let url = URL(fileURLWithPath: #filePath)   // …/ios/StationToStationTests/TimelineQuarantineTests.swift
            .deletingLastPathComponent()            // …/ios/StationToStationTests
            .deletingLastPathComponent()            // …/ios
            .deletingLastPathComponent()            // repo root
            .appendingPathComponent("fixtures/timeline/unreadable/\(name)")
        return try Data(contentsOf: url)
    }

    private func quarantined(_ dir: URL) throws -> [URL] {
        try FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)
            .filter { $0.lastPathComponent != "timelines.json" }
    }

    private func assertQuarantinesAndKeeps(_ name: String, line: UInt = #line) async throws {
        let original = try fixture(name)
        let dir = try emptyDir()
        let file = dir.appendingPathComponent("timelines.json")
        try original.write(to: file)
        let store = TimelineStore(file: file)

        let loaded = await store.load()
        XCTAssertTrue(loaded.gigs.isEmpty, line: line)
        let aside = try quarantined(dir)
        XCTAssertEqual(1, aside.count, line: line)
        let kept = try XCTUnwrap(aside.first, line: line)
        let pattern = try NSRegularExpression(pattern: #"^timelines\.corrupt-\d+\.json$"#)
        let keptName = kept.lastPathComponent
        XCTAssertNotNil(pattern.firstMatch(in: keptName, range: NSRange(keptName.startIndex..., in: keptName)),
                        keptName, line: line)
        XCTAssertEqual(original, try Data(contentsOf: kept), line: line)

        await store.save(attendedTotals: ["dizzi90": 3])
        XCTAssertEqual([keptName], try quarantined(dir).map(\.lastPathComponent), line: line)
        XCTAssertEqual(original, try Data(contentsOf: kept), line: line)
        let reloaded = await store.load()
        XCTAssertEqual(["dizzi90": 3], reloaded.attendedTotals, line: line)
        XCTAssertTrue(reloaded.gigs.isEmpty, line: line)
    }

    func testATruncatedTimelineFileIsSetAsideUntouchedNotOverwrittenByTheNextSave() async throws {
        try await assertQuarantinesAndKeeps("truncated.json")
    }

    func testATimelineFileThatIsNotAnObjectIsSetAsideUntouchedNotOverwrittenByTheNextSave() async throws {
        try await assertQuarantinesAndKeeps("not-an-object.json")
    }

    func testAMissingTimelineFileOnFirstRunSetsNothingAside() async throws {
        let dir = try emptyDir()
        let store = TimelineStore(file: dir.appendingPathComponent("timelines.json"))
        let loaded = await store.load()
        XCTAssertTrue(loaded.gigs.isEmpty)
        await store.save(attendedTotals: ["dizzi90": 3])
        XCTAssertEqual([], try quarantined(dir))
    }
}
