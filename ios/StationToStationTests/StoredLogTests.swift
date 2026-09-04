import XCTest
@testable import StationToStation

/// The **Log**'s rules, ported from Android's `BillTest` alongside `StoredLog` itself.
///
/// These are twins on purpose: the two platforms share one cache file, so a rule that
/// holds on one side and not the other is a corruption with extra steps. Every song
/// title here is invented — this repository is public.
final class StoredLogTests: XCTestCase {

    // MARK: - Gaps

    func testABlankEntryIsAGapAndNotATitle() {
        let log = StoredLog(songs: ["Vardhavn", "", "Paper Cranes"])

        XCTAssertEqual(["Vardhavn", "Paper Cranes"], log.named())
        XCTAssertEqual(1, log.gaps)
        XCTAssertEqual(3, log.songs.count, "a gap is in the record, it is just not a title")
    }

    func testAWhitespaceEntryIsAGapToo() {
        XCTAssertEqual(1, StoredLog(songs: ["   "]).gaps)
    }

    // MARK: - Open by default

    func testALogStartsOpen() {
        XCTAssertFalse(StoredLog().closed)
    }

    func testAddingAndRemovingKeepTheClosedBitAlone() {
        let closed = StoredLog(songs: ["Vardhavn"], closed: true)

        XCTAssertTrue(closed.adding("Paper Cranes").closed)
        XCTAssertTrue(closed.removingAt(0).closed)
    }

    // MARK: - Adding and removing

    func testASongIsAddedAtTheEndInTheOrderItWasTapped() {
        let log = StoredLog().adding("Vardhavn").adding("Paper Cranes")

        XCTAssertEqual(["Vardhavn", "Paper Cranes"], log.songs)
        XCTAssertEqual(log.songs.count, log.remembered.count, "the two lists stay parallel")
    }

    func testRemovingTakesTheWordsBehindItToo() {
        let log = StoredLog(songs: ["Vardhavn", "Paper Cranes"])
            .correctingAt(0, title: "Hollowmoor")
            .removingAt(0)

        XCTAssertEqual(["Paper Cranes"], log.songs)
        XCTAssertEqual([""], log.remembered)
        XCTAssertNil(log.rememberedAt(0))
    }

    func testRemovingOutsideTheLogChangesNothing() {
        let log = StoredLog(songs: ["Vardhavn"])

        XCTAssertEqual(log, log.removingAt(4))
        XCTAssertEqual(log, log.removingAt(-1))
    }

    // MARK: - Correcting

    func testCorrectingKeepsTheWordsThatWereThere() {
        let log = StoredLog(songs: ["All held together by toothpicks and gum"])
            .correctingAt(0, title: "Toothpicks and Gum")

        XCTAssertEqual(["Toothpicks and Gum"], log.songs)
        XCTAssertEqual("All held together by toothpicks and gum", log.rememberedAt(0))
    }

    func testASecondCorrectionKeepsTheFirstWords() {
        // The ones written in the dark. A title I already chose is not a memory.
        let log = StoredLog(songs: ["all held together by toothpicks"])
            .correctingAt(0, title: "Wrong Song")
            .correctingAt(0, title: "Toothpicks and Gum")

        XCTAssertEqual(["Toothpicks and Gum"], log.songs)
        XCTAssertEqual("all held together by toothpicks", log.rememberedAt(0))
    }

    func testAGapIsNotCorrected() {
        // "One I couldn't name" is an acknowledged fact, not an invitation to guess.
        let log = StoredLog(songs: ["", "Vardhavn"])

        XCTAssertEqual(log, log.correctingAt(0, title: "Paper Cranes"))
    }

    func testCorrectingToNothingIsRefused() {
        let log = StoredLog(songs: ["Vardhavn"])

        XCTAssertEqual(log, log.correctingAt(0, title: "   "))
    }

    // MARK: - Restoring

    func testTheWordsComeBackAsTheEntry() {
        let log = StoredLog(songs: ["all held together by toothpicks"])
            .correctingAt(0, title: "Toothpicks and Gum")
            .restoringAt(0)

        XCTAssertEqual(["all held together by toothpicks"], log.songs)
        XCTAssertNil(log.rememberedAt(0), "and there is nothing left underneath")
    }

    func testRestoringAnUncorrectedEntryChangesNothing() {
        let log = StoredLog(songs: ["Vardhavn"])

        XCTAssertEqual(log, log.restoringAt(0))
    }

    // MARK: - An older cache

    func testALogWrittenBeforeRememberedExistedAlignsOnFirstUse() {
        // No `remembered` at all reads as "nothing was ever replaced" — exactly true.
        let old = StoredLog(songs: ["Vardhavn", "Paper Cranes"], closed: false, remembered: [])

        XCTAssertNil(old.rememberedAt(0))
        XCTAssertNil(old.rememberedAt(1))

        let corrected = old.correctingAt(1, title: "Hollowmoor")
        XCTAssertEqual(["Vardhavn", "Hollowmoor"], corrected.songs)
        XCTAssertEqual(["", "Paper Cranes"], corrected.remembered)
    }

    func testAMissingKeyDecodesToTheDefaultRatherThanThrowing() throws {
        let json = Data(#"{"songs":["Vardhavn"]}"#.utf8)
        let log = try JSONDecoder().decode(StoredLog.self, from: json)

        XCTAssertEqual(["Vardhavn"], log.songs)
        XCTAssertFalse(log.closed)
        XCTAssertEqual([], log.remembered)
    }

    func testItRoundTripsThroughJsonUnchanged() throws {
        // The shared cache is the contract: what Android writes, this must read back
        // and write again identically.
        let log = StoredLog(songs: ["Toothpicks and Gum", ""], closed: true,
                            remembered: ["all held together by toothpicks", ""])
        let data = try JSONEncoder().encode(log)

        XCTAssertEqual(log, try JSONDecoder().decode(StoredLog.self, from: data))
    }

    // MARK: - Entry timestamps (#409)

    func testAddingStampsTheNewEntryWithTheGivenTime() {
        let log = StoredLog().adding("Vardhavn", now: 1_000)

        XCTAssertEqual(1_000, log.enteredAtOrNull(0))
    }

    func testCorrectingLeavesAnEntrysTimestampUntouched() {
        let log = StoredLog(songs: ["all held together by toothpicks"], enteredAt: [1_000])
            .correctingAt(0, title: "Toothpicks and Gum")

        XCTAssertEqual(1_000, log.enteredAtOrNull(0))
    }

    func testRestoringLeavesAnEntrysTimestampUntouched() {
        let log = StoredLog(songs: ["all held together by toothpicks"], enteredAt: [1_000])
            .correctingAt(0, title: "Toothpicks and Gum")
            .restoringAt(0)

        XCTAssertEqual(1_000, log.enteredAtOrNull(0))
    }

    func testALogWrittenBeforeEnteredAtExistedReadsAsUnknownNeverFabricated() {
        // No `enteredAt` at all decodes as "unknown" per entry — never backfilled.
        let old = StoredLog(songs: ["Vardhavn", "Paper Cranes"], closed: false, enteredAt: [])

        XCTAssertNil(old.enteredAtOrNull(0))
        XCTAssertNil(old.enteredAtOrNull(1))

        let added = old.adding("Hollowmoor", now: 2_000)
        XCTAssertNil(added.enteredAtOrNull(0))
        XCTAssertNil(added.enteredAtOrNull(1))
        XCTAssertEqual(2_000, added.enteredAtOrNull(2))
    }

    func testAMissingEnteredAtKeyDecodesToTheDefaultRatherThanThrowing() throws {
        let json = Data(#"{"songs":["Vardhavn"]}"#.utf8)
        let log = try JSONDecoder().decode(StoredLog.self, from: json)

        XCTAssertEqual([], log.enteredAt)
        XCTAssertNil(log.enteredAtOrNull(0))
    }
}
