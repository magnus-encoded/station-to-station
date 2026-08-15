import XCTest
@testable import StationToStation

/// Getting a night you hold a ticket for into the app, and keeping it there (#175).
///
/// Two halves: reading the id out of whatever was pasted, and storing the gig with the
/// claim that goes with it. Ids below are invented but shaped like setlist.fm's.
final class PlannedGigTests: XCTestCase {

    // MARK: - Reading the id

    func testABareIdIsAnId() {
        XCTAssertEqual("53705b8d", parseSetlistId("53705b8d"))
        XCTAssertEqual("53705b8d", parseSetlistId("  53705b8d  "))
    }

    func testASetlistPageUrlYieldsItsId() {
        XCTAssertEqual(
            "53705b8d",
            parseSetlistId("https://www.setlist.fm/setlist/some-band/2026/a-venue-53705b8d.html")
        )
    }

    /// A gig weeks away lives under /upcoming/, which is the whole point — the search
    /// index stops about a day out, so this is the only route in.
    func testAnUpcomingPageUrlYieldsItsId() {
        XCTAssertEqual(
            "7ab3c1de",
            parseSetlistId("https://www.setlist.fm/upcoming/some-band/2026/a-venue-7ab3c1de.html")
        )
    }

    /// The case this function exists to get right. An artist page and a venue page end
    /// in *exactly* the same shape as a setlist page, so a looser rule would fetch a
    /// real gig that is not the one in front of the user — and nothing about it would
    /// look wrong afterwards.
    func testAnArtistOrVenuePageIsNotAGig() {
        XCTAssertNil(parseSetlistId("https://www.setlist.fm/setlists/some-band-23d6a877.html"))
        XCTAssertNil(parseSetlistId("https://www.setlist.fm/venue/a-venue-63d41af7.html"))
    }

    func testAnythingElseIsNotAnId() {
        XCTAssertNil(parseSetlistId(""))
        XCTAssertNil(parseSetlistId("not a link"))
        XCTAssertNil(parseSetlistId("https://example.com/setlist/x-53705b8d.html"))
        // Too short, too long, and not hex.
        XCTAssertNil(parseSetlistId("53f"))
        XCTAssertNil(parseSetlistId("53705b8d123"), "11 characters is past the cap")
        XCTAssertNil(parseSetlistId("53705z8d"))
    }

    /// The bounds themselves, since the range is what separates an id from any other
    /// hex-looking string someone might paste.
    func testTheIdLengthBoundsAreInclusive() {
        XCTAssertEqual("53705", parseSetlistId("53705"))
        XCTAssertEqual("53705b8d12", parseSetlistId("53705b8d12"))
    }

    // MARK: - Storing the plan

    private func tempFile() -> URL {
        URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("planned-\(UUID().uuidString).json")
    }

    private func show(_ id: String) -> FmSetlist {
        FmSetlist(id: id, eventDate: "13-08-2026", artist: FmArtist(name: "A Band"))
    }

    /// The record and the claim are useless apart: a planned gig whose provenance did
    /// not land would read as attended on the next launch.
    func testAPlannedGigStoresItsRecordAndItsClaimTogether() async {
        let store = TimelineStore(file: tempFile())

        await store.savePlanned(show("g1"))

        let cache = await store.load()
        XCTAssertEqual(["g1"], cache.planned().map(\.id))
        XCTAssertEqual("planned", cache.attendance()["g1"]?.provenance)
    }

    func testReAddingTheSameGigReplacesRatherThanDuplicates() async {
        let store = TimelineStore(file: tempFile())

        await store.savePlanned(show("g1"))
        await store.savePlanned(show("g1"))

        let cache = await store.load()
        XCTAssertEqual(1, cache.planned().count)
    }

    /// The sequence a night you planned and then went to actually produces: storing the
    /// record again when the setlist finally lands must not throw the check-in away.
    func testSavingAgainNeverDowngradesACheckIn() async {
        let store = TimelineStore(file: tempFile())
        await store.savePlanned(show("g1"))
        await store.saveAttendance(setlistId: "g1",
                                   attendance: StoredAttendance(provenance: "checked_in"))

        await store.savePlanned(show("g1"))

        let cache = await store.load()
        XCTAssertEqual("checked_in", cache.attendance()["g1"]?.provenance)
    }

    func testForgettingAPlanDropsTheClaimWithIt() async {
        let store = TimelineStore(file: tempFile())
        await store.savePlanned(show("g1"))

        await store.removePlanned(setlistId: "g1")

        let cache = await store.load()
        XCTAssertTrue(cache.planned().isEmpty)
        XCTAssertNil(cache.attendance()["g1"])
    }

    /// A gig since checked into is a night that happened. Taking it out of my plans
    /// must not quietly erase the evidence that I was there.
    func testForgettingAPlanKeepsTheEvidenceOfANightThatHappened() async {
        let store = TimelineStore(file: tempFile())
        await store.savePlanned(show("g1"))
        await store.saveAttendance(setlistId: "g1",
                                   attendance: StoredAttendance(provenance: "checked_in"))

        await store.removePlanned(setlistId: "g1")

        let cache = await store.load()
        XCTAssertTrue(cache.planned().isEmpty, "it is no longer a plan")
        XCTAssertEqual("checked_in", cache.attendance()["g1"]?.provenance, "but I was still there")
    }

    func testForgettingAGigTheStoreNeverHeardOfChangesNothing() async {
        let store = TimelineStore(file: tempFile())
        await store.savePlanned(show("g1"))

        await store.removePlanned(setlistId: "nope")

        let cache = await store.load()
        XCTAssertEqual(["g1"], cache.planned().map(\.id))
    }
}
