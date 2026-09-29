import XCTest
@testable import StationToStation

final class TourLogTests: XCTestCase {
    func testSetlistFmWinsAndFillDeduplicatesAroundTheWrittenLog() {
        let result = tourSetlistFill(
            setlistFm: ["Already Here", "New One", "new-one", "Third", "Fourth", "Fifth", "Sixth", "Seventh", "Eighth", "Ninth", "Tenth"],
            musicBrainz: ["Wrong fallback"],
            entered: ["already here", "My opener"])

        XCTAssertTrue(result.usedSetlistFm)
        XCTAssertEqual(["New One", "Third", "Fourth", "Fifth", "Sixth", "Seventh", "Eighth", "Ninth"], result.titles)
        XCTAssertEqual(10, result.titles.count + 2)
    }

    func testMusicBrainzFillsWhenSetlistFmHasNoUsableSongs() {
        let result = tourSetlistFill(
            setlistFm: ["", "  "],
            musicBrainz: ["One", "ONE", "Two"],
            entered: [])

        XCTAssertFalse(result.usedSetlistFm)
        XCTAssertEqual(["One", "Two"], result.titles)
    }

    func testReceivedGossipFillsOnlyAGapAndPreservesItsIdentity() {
        let log = StoredLog(songs: ["Opening", ""], remembered: ["", ""], enteredAt: [10, 20],
            lineNumbers: [4, 7], nextLineNumber: 8)
        let filled = log.fillingGapAt(1, title: "Friend's answer")

        XCTAssertEqual(["Opening", "Friend's answer"], filled.songs)
        XCTAssertEqual([10, 20], filled.enteredAt)
        XCTAssertEqual([4, 7], filled.lineNumbers)
        XCTAssertEqual(log, log.fillingGapAt(0, title: "Must not replace"))
    }

    func testEveryTourLogStepStaysLocal() {
        XCTAssertTrue(tourKeepsLogLocal(.firstSong))
        XCTAssertTrue(tourKeepsLogLocal(.gap))
        XCTAssertTrue(tourKeepsLogLocal(.gossipBack))
        XCTAssertTrue(tourKeepsLogLocal(.setlistFill))
        XCTAssertFalse(tourKeepsLogLocal(.spotify))
        XCTAssertFalse(tourKeepsLogLocal(nil))
    }

    func testDemoPurgeRemovesOnlyItsLocalGossip() {
        var gossip = PublicGossipState()
        gossip.facts["demo-fact"] = GossipEnvelope(gigId: "demo", scope: "scope", author: "author",
            createdAt: 1, expiresAt: 2, kind: "log", line: 0, text: "Demo")
        gossip.facts["real-fact"] = GossipEnvelope(gigId: "real", scope: "scope", author: "author",
            createdAt: 1, expiresAt: 2, kind: "log", line: 0, text: "Real")

        let purged = gossip.withoutGigs(["demo"])

        XCTAssertNil(purged.facts["demo-fact"])
        XCTAssertEqual("Real", purged.facts["real-fact"]?.text)
    }
}
