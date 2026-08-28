import XCTest
@testable import StationToStation

/// The zoomed-out Spine: my Nodes, other people's, and where the two are the same
/// night. Ported from the Android WeaveTimelinesTest, including the three-line
/// cases — the fixtures both platforms have to agree on.
final class WeaveTimelinesTests: XCTestCase {

    private func show(_ id: String, _ date: String, _ venue: String) -> FmSetlist {
        FmSetlist(id: id, eventDate: date, artist: FmArtist(name: "Artist \(id)"), venue: FmVenue(name: venue))
    }

    private let lemmy = Friend(setlistfm: "Lemmy", name: "Lemmy")
    private let ozzy = Friend(setlistfm: "Ozzy", name: "Ozzy")

    /// A **Festival** identity carried by `shows` — mine and theirs alike, since that
    /// is what makes their nights and my nights the same festival rather than two
    /// things at one address (#166).
    private func festival(_ shows: String...) -> Festivals {
        Festivals(
            byId: ["hm26": StoredFestival(id: "hm26", name: "Hollowmoor Sound 2026")],
            idByShow: Dictionary(uniqueKeysWithValues: shows.map { ($0, "hm26") })
        )
    }

    func testWithNobodyConnectedTheRowsAreJustMyOwn() {
        let rows = weaveTimelines(mine: [show("1", "21-11-2025", "Blå")])
        XCTAssertEqual(1, rows.count)
        XCTAssertTrue(rows[0].mine)
        XCTAssertTrue(rows[0].others.isEmpty)
    }

    /// Their days at a festival land on my node rather than beside it — because the
    /// identity says both are that festival. Without one they would be four separate
    /// nights at one address, which is the true, smaller thing (#166).
    func testTheirDaysAtMyFestivalFoldIntoMyNode() {
        let rows = weaveTimelines(
            mine: [show("a1", "25-06-2026", "Ekebergsletta"), show("a2", "24-06-2026", "Ekebergsletta")],
            festivals: festival("a1", "a2", "b1", "b2"),
            friends: [lemmy],
            theirs: ["Lemmy": [show("b1", "27-06-2026", "Ekebergsletta"),
                                       show("b2", "26-06-2026", "Ekebergsletta")]]
        )
        XCTAssertEqual(1, rows.count)
        XCTAssertTrue(rows[0].node.isIdentified)
        // Company, but not Together: their 26–27 June run and my 24–25 June one
        // share no night. Absorb folds their cluster in; it does not make the
        // nights shared.
        XCTAssertTrue(rows[0].hasCompany)
        XCTAssertEqual(0, rows[0].sharedCount)
        XCTAssertEqual(.mine, rows[0].ownership)
        XCTAssertEqual(2, rows[0].showsHereByFriends.count)
        XCTAssertEqual([lemmy], rows[0].others)
    }

    /// And with no identity, they do not fold: nothing knows those are one thing.
    func testTheirRunAtMyVenueWithNoIdentityStaysBesideMyNights() {
        let rows = weaveTimelines(
            mine: [show("a1", "25-06-2026", "Ekebergsletta"), show("a2", "24-06-2026", "Ekebergsletta")],
            friends: [lemmy],
            theirs: ["Lemmy": [show("b1", "27-06-2026", "Ekebergsletta"),
                                       show("b2", "26-06-2026", "Ekebergsletta")]]
        )
        XCTAssertEqual(4, rows.count)
        XCTAssertEqual(2, rows.filter { $0.mine }.count)
        XCTAssertTrue(rows.allSatisfy { $0.sharedCount == 0 })
    }

    func testANightOnlyTheyWereAtGetsItsOwnRow() {
        let rows = weaveTimelines(
            mine: [show("a1", "21-11-2025", "Blå")],
            friends: [lemmy],
            theirs: ["Lemmy": [show("b1", "12-06-2025", "3Arena")]]
        )
        XCTAssertEqual(2, rows.count)
        // Newest first, and the one that isn't mine carries no node of my own.
        XCTAssertTrue(rows[0].mine)
        XCTAssertFalse(rows[1].mine)
        XCTAssertEqual([lemmy], rows[1].others)
    }

    func testOpeningAFestivalINeverAttendedKeepsEveryGigTheirs() {
        let theirs = ["Lemmy": [show("b1", "16-05-2026", "Stora Scenen"),
                                        show("b2", "15-05-2026", "Stora Scenen")]]
        let mine = [show("a1", "21-11-2025", "Blå")]
        let theirFestival = festival("b1", "b2")
        let collapsed = weaveTimelines(mine: mine, festivals: theirFestival,
                                       friends: [lemmy], theirs: theirs)
        guard let fest = collapsed.first(where: { $0.node.isIdentified }) else {
            return XCTFail("expected a festival row")
        }
        let rows = weaveTimelines(mine: mine, festivals: theirFestival, friends: [lemmy],
                                  theirs: theirs, expanded: [fest.key])

        let inner = rows.filter { $0.depth == 1 }
        XCTAssertEqual(2, inner.count)
        XCTAssertTrue(inner.allSatisfy { !$0.mine })                // I was at neither
        XCTAssertTrue(inner.allSatisfy { $0.ownership == .theirs }) // so none is together
    }

    func testOpeningASharedFestivalListsBothSidesGigsUnderneath() {
        let mine = [show("a1", "25-06-2026", "Ekebergsletta"), show("a2", "24-06-2026", "Ekebergsletta")]
        let theirs = ["Lemmy": [show("b1", "26-06-2026", "Ekebergsletta")]]
        let ours = festival("a1", "a2", "b1")
        let collapsed = weaveTimelines(mine: mine, festivals: ours,
                                       friends: [lemmy], theirs: theirs)
        let rows = weaveTimelines(mine: mine, festivals: ours, friends: [lemmy],
                                  theirs: theirs, expanded: [collapsed[0].key])

        XCTAssertEqual(4, rows.count) // the festival, then its three gigs
        XCTAssertTrue(rows[0].node.isIdentified)
        let inner = Array(rows.dropFirst())
        XCTAssertTrue(inner.allSatisfy { $0.depth == 1 })
        // 26th theirs, 25th + 24th mine
        XCTAssertEqual([false, true, true], inner.map(\.mine))
    }

    /// A night we were **both** at, listed inside an open Festival, is a Crossing —
    /// at the Resolution the Festival is open, not only at the one it is closed.
    ///
    /// The regression: `showsHereByFriends` was left off the member rows, and
    /// `sharedCount` is an intersection with it, so it was structurally zero at depth
    /// 1. The Festival counted "1 together" and the night it counted drew amber.
    func testASharedNightInsideAnOpenFestivalIsACrossing() {
        let mine = [show("a1", "25-06-2026", "Ekebergsletta"),
                    show("a2", "24-06-2026", "Ekebergsletta")]
        // a1 is on both lists; a2 is mine alone.
        let theirs = ["Lemmy": [show("a1", "25-06-2026", "Ekebergsletta")]]
        let ours = festival("a1", "a2")
        let collapsed = weaveTimelines(mine: mine, festivals: ours,
                                       friends: [lemmy], theirs: theirs)
        XCTAssertEqual(1, collapsed[0].sharedCount) // the closed Festival already knew

        let rows = weaveTimelines(mine: mine, festivals: ours, friends: [lemmy],
                                  theirs: theirs, expanded: [collapsed[0].key])
        let inner = rows.filter { $0.depth == 1 }

        let together = inner.first { $0.shows.first?.id == "a1" }
        XCTAssertEqual(1, together?.sharedCount)
        XCTAssertEqual(.together, together?.ownership)

        // And the night nobody else was at is still mine alone — the fix must not
        // hand a Crossing to every member row of a shared Festival.
        let alone = inner.first { $0.shows.first?.id == "a2" }
        XCTAssertEqual(0, alone?.sharedCount)
        XCTAssertEqual(.mine, alone?.ownership)
    }

    /// The Absorb case, which the fix must leave exactly as it was: their cluster
    /// sits in my node without our having shared a night, so no member row is a
    /// Crossing however many Lines run through the row.
    func testAnAbsorbedFestivalHasCompanyButNoCrossingInside() {
        let mine = [show("a1", "25-06-2026", "Ekebergsletta"),
                    show("a2", "24-06-2026", "Ekebergsletta")]
        let theirs = ["Lemmy": [show("b1", "27-06-2026", "Ekebergsletta"),
                                show("b2", "26-06-2026", "Ekebergsletta")]]
        let ours = festival("a1", "a2", "b1", "b2")
        let collapsed = weaveTimelines(mine: mine, festivals: ours,
                                       friends: [lemmy], theirs: theirs)
        XCTAssertTrue(collapsed[0].hasCompany)
        XCTAssertEqual(0, collapsed[0].sharedCount)

        let rows = weaveTimelines(mine: mine, festivals: ours, friends: [lemmy],
                                  theirs: theirs, expanded: [collapsed[0].key])
        XCTAssertTrue(rows.filter { $0.depth == 1 }.allSatisfy { $0.sharedCount == 0 })
    }

    // --- Three lines. Everything above holds with one friend and hides the rest. ---

    func testANightAllThreeOfUsWereAtIsOneNodeCarryingBoth() {
        let tons = show("w1", "25-06-2026", "Ekebergsletta")
        let rows = weaveTimelines(
            mine: [tons, show("a2", "24-06-2026", "Ekebergsletta")],
            festivals: festival("w1", "a2", "b2"),
            friends: [ozzy, lemmy],
            theirs: ["Lemmy": [tons, show("b2", "26-06-2026", "Ekebergsletta")],
                     "Ozzy": [tons]]
        )
        XCTAssertEqual(1, rows.count)
        XCTAssertEqual(Set([ozzy, lemmy]), Set(rows[0].others))
        XCTAssertTrue(rows[0].hasCompany)
    }

    func testAGigTwoFriendsBothWentToIsCountedOnceNotOnceEach() {
        let tons = show("w1", "25-06-2026", "Ekebergsletta")
        let rows = weaveTimelines(
            mine: [tons, show("a2", "24-06-2026", "Ekebergsletta")],
            friends: [ozzy, lemmy],
            theirs: ["Lemmy": [tons], "Ozzy": [tons]]
        )
        // Both were at the same one gig: one show here, and it is the one we shared.
        XCTAssertEqual(1, rows[0].showsHereByFriends.count)
        XCTAssertEqual(1, rows[0].sharedCount)
    }

    func testANightIMissedThatTwoFriendsSharedIsOneRow() {
        let theirNight = show("b1", "12-06-2025", "3Arena")
        let rows = weaveTimelines(
            mine: [show("a1", "21-11-2025", "Blå")],
            friends: [ozzy, lemmy],
            theirs: ["Lemmy": [theirNight], "Ozzy": [theirNight]]
        )
        XCTAssertEqual(2, rows.count) // my night, and the one they shared without me
        guard let without = rows.first(where: { !$0.mine }) else { return XCTFail("no row of theirs") }
        XCTAssertEqual(Set([ozzy, lemmy]), Set(without.others))
    }

    func testANightWithOneOfThemSaysSo() {
        let withOzzy = show("a1", "21-11-2025", "Blå")
        let rows = weaveTimelines(
            mine: [withOzzy],
            friends: [ozzy, lemmy],
            theirs: ["Ozzy": [withOzzy], "Lemmy": [show("b9", "01-01-2020", "Somewhere else")]]
        )
        guard let mine = rows.first(where: { $0.mine }) else { return XCTFail("no row of mine") }
        XCTAssertEqual([ozzy], mine.others)
        XCTAssertEqual(1, mine.sharedCount)
    }

    func testAFestivalOnlyTheyWentToIsNeverTogether() {
        let rows = weaveTimelines(
            mine: [show("a1", "21-11-2025", "Blå")],
            festivals: festival("b1", "b2"),
            friends: [ozzy, lemmy],
            theirs: ["Ozzy": [show("b1", "16-05-2026", "Stora Scenen"),
                              show("b2", "15-05-2026", "Stora Scenen")]]
        )
        // Their node's own shows are theirs, so intersecting them with "what
        // friends attended" used to match every one and light the node green.
        XCTAssertEqual(0, rows.first { !$0.mine }?.sharedCount)
    }

    func testTheSameSingleGigOnBothListsIsOneNode() {
        let night = show("x1", "21-11-2025", "Blå")
        let rows = weaveTimelines(
            mine: [night],
            friends: [lemmy],
            theirs: ["Lemmy": [night]]
        )
        // A lone gig used to fail to Absorb, so a shared night drew two rows.
        XCTAssertEqual(1, rows.count)
        XCTAssertTrue(rows[0].hasCompany)
        XCTAssertEqual(1, rows[0].sharedCount)
    }

    /// Rows come back newest first whether they are mine or theirs — the one
    /// ordering rule the whole spine rests on.
    func testRowsAreNewestFirstAcrossBothLines() {
        let rows = weaveTimelines(
            mine: [show("a1", "21-11-2025", "Blå"), show("a2", "01-01-2019", "Blå")],
            friends: [lemmy],
            theirs: ["Lemmy": [show("b1", "12-06-2026", "3Arena")]]
        )
        XCTAssertEqual(["b1", "a1", "a2"], rows.map { $0.shows[0].id })
    }
}
