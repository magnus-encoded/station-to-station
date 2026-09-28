import XCTest
@testable import StationToStation

/// The zoomed-out Spine: my Nodes, other people's, and where the two are the same
/// night. Ported from the Android WeaveTimelinesTest, including the three-line
/// cases — the fixtures both platforms have to agree on.
final class WeaveTimelinesTests: XCTestCase {

    // A real setlist.fm show by default — `url` is what `isLocal` reads, and most
    // shows in this file stand in for genuine setlist.fm records on both sides. The
    // #433 test below builds a local (no-account) show explicitly with `url: nil`.
    private func show(_ id: String, _ date: String, _ venue: String) -> FmSetlist {
        FmSetlist(
            id: id,
            eventDate: date,
            artist: FmArtist(name: "Artist \(id)"),
            venue: FmVenue(name: venue),
            url: "https://www.setlist.fm/setlist/\(id).html"
        )
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

    /// A local Gig — no setlist.fm account behind it, so no real setlist.fm id, and
    /// a venue string typed by hand or guessed from a ticket rather than pulled from
    /// setlist.fm's own listing (#433). A friend's night at the same room on the
    /// same date used to draw its own row entirely — "theirs" and nothing else —
    /// because the only fold path besides a shared id, `sameEvening`, required the
    /// venue names to match character for character, and "Parkteatret" never will
    /// against "Parkteatret Scene, Oslo, Norway".
    func testALocalGigWithNoSetlistFmIdStillFoldsIntoAFriendsNightAtTheSameRoom() {
        var mine = show("local-1", "29-01-2027", "Parkteatret")
        mine.url = nil
        let theirs = show("sfm-9", "29-01-2027", "Parkteatret Scene, Oslo, Norway")
        let rows = weaveTimelines(
            mine: [mine],
            friends: [lemmy],
            theirs: ["Lemmy": [theirs]]
        )

        XCTAssertEqual(1, rows.count)
        XCTAssertTrue(rows[0].hasCompany)
        XCTAssertEqual(1, rows[0].sharedCount)
        XCTAssertEqual(0, rows[0].theirsCount)
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

    // MARK: The maybe-shared marker (#405). Twinned in WeaveTimelinesTest.kt.

    /// A Night typed by hand: no setlist.fm id behind it.
    private func local(_ id: String, _ date: String, _ venue: String) -> FmSetlist {
        var night = show(id, date, venue)
        night.url = nil
        return night
    }

    func testTheSameDateAndTheSameIdIsJoinedNotAMaybe() {
        let night = local("x1", "21-11-2025", "Blå")
        let rows = weaveTimelines(mine: [night], friends: [lemmy], theirs: ["Lemmy": [night]])
        XCTAssertEqual(1, rows.count)
        XCTAssertEqual(1, rows[0].sharedCount)
        XCTAssertTrue(rows[0].maybe.isEmpty)
    }

    func testTheSameDateUnderDifferentIdsIsAMaybeAndNeverACrossing() {
        let rows = weaveTimelines(
            mine: [local("m1", "21-11-2025", "Blå")],
            friends: [lemmy],
            theirs: ["Lemmy": [local("n1", "21-11-2025", "Blå")]]
        )
        // Two rows: the same room on the same date does not fold two hand-logged Nights,
        // because folding them would be the app answering its own question.
        XCTAssertEqual(2, rows.count)
        let mine = rows.first { $0.mine }
        XCTAssertEqual([lemmy], mine?.maybe)
        XCTAssertEqual(true, mine?.others.isEmpty)
        XCTAssertEqual(0, mine?.sharedCount)
        XCTAssertEqual(true, rows.first { !$0.mine }?.maybe.isEmpty)
    }

    func testDifferentDatesAreNeither() {
        let rows = weaveTimelines(
            mine: [local("m1", "21-11-2025", "Blå")],
            friends: [lemmy],
            theirs: ["Lemmy": [local("n1", "22-11-2025", "Blå")]]
        )
        XCTAssertTrue(rows.allSatisfy { $0.maybe.isEmpty && $0.sharedCount == 0 })
    }

    func testAMaybeISaidIsNotTheSameDoesNotComeBack() {
        let rows = weaveTimelines(
            mine: [local("m1", "21-11-2025", "Blå")],
            friends: [lemmy],
            theirs: ["Lemmy": [local("n1", "21-11-2025", "Blå")]],
            apart: ["n1": ["m1"]]
        )
        XCTAssertEqual(2, rows.count)
        XCTAssertTrue(rows.allSatisfy { $0.maybe.isEmpty })
    }

    func testAMaybeIJoinedIsDrawnJoined() {
        let rows = weaveTimelines(
            mine: [local("m1", "21-11-2025", "Blå")],
            friends: [lemmy],
            theirs: ["Lemmy": [local("n1", "21-11-2025", "Somewhere else")]],
            joins: ["n1": "m1"]
        )
        // Folded onto my node even across two room names, because I said so.
        XCTAssertEqual(1, rows.count)
        XCTAssertEqual([lemmy], rows[0].others)
        XCTAssertEqual(1, rows[0].sharedCount)
        XCTAssertEqual(0, rows[0].theirsCount)
        XCTAssertTrue(rows[0].maybe.isEmpty)
    }

    /// Story 24: a Festival day against a single Act is a difference of granularity.
    func testTheirFestivalDayAgainstMySingleActIsNotAssertedToBeTheSameRecord() {
        let rows = weaveTimelines(
            mine: [show("g1", "25-06-2026", "Ekebergsletta")],
            festivals: festival("f1"),
            friends: [lemmy],
            theirs: ["Lemmy": [local("f1", "25-06-2026", "Ekebergsletta")]]
        )
        let mine = rows.first { $0.mine }
        XCTAssertEqual(0, mine?.sharedCount)
        XCTAssertEqual(true, mine?.others.isEmpty)
        XCTAssertEqual([lemmy], mine?.maybe)
    }

    /// Two catalogued records are a fact, not a question: two rooms, two Nights.
    func testTwoSetlistFmIdsOnOneDateAreNeverAMaybe() {
        let rows = weaveTimelines(
            mine: [show("a1", "21-11-2025", "Blå")],
            friends: [lemmy],
            theirs: ["Lemmy": [show("b1", "21-11-2025", "Rockefeller")]]
        )
        XCTAssertTrue(rows.allSatisfy { $0.maybe.isEmpty })
    }

    /// A Night of mine we already cross is no question, whatever else they logged that date.
    func testANightAlreadyCrossedAsksNothingMore() {
        let night = local("x1", "21-11-2025", "Blå")
        let rows = weaveTimelines(
            mine: [night],
            friends: [lemmy],
            theirs: ["Lemmy": [night, local("n2", "21-11-2025", "Rockefeller")]]
        )
        XCTAssertTrue(rows.allSatisfy { $0.maybe.isEmpty })
    }

    func testMaybeNightsNamesThePairItIsAskingAbout() {
        let m1 = local("m1", "21-11-2025", "Blå")
        let n1 = local("n1", "21-11-2025", "Blå")
        let asked = maybeNights(mine: [m1], friends: [lemmy, ozzy], theirs: ["Lemmy": [n1]])
        XCTAssertEqual(1, asked.count)
        XCTAssertEqual(lemmy, asked.first?.friend)
        XCTAssertEqual("m1", asked.first?.mine.id)
        XCTAssertEqual("n1", asked.first?.theirs.id)
    }

    // MARK: - Answering a maybe from the Spine (#580), twinned with Android.

    private func ids(_ rows: [WovenRow]) -> [String] { rows.compactMap { $0.shows.first?.id } }

    func testAMaybePairBecomesNeighboursPastAnotherNightThatDate() {
        let m1 = show("m1", "21-11-2025", "Blå")
        let n1 = local("n1", "21-11-2025", "Brenneriveien 9")
        let rows = weaveTimelines(mine: [m1], friends: [ozzy, lemmy], theirs: [
            "Ozzy": [show("b1", "21-11-2025", "Rockefeller")], "Lemmy": [n1],
        ])
        XCTAssertEqual(["m1", "n1", "b1"], ids(rows))
        XCTAssertEqual([MaybeNight(friend: lemmy, mine: m1, theirs: n1)], rows[1].maybeAbove)
        XCTAssertTrue(rows[0].maybeAbove.isEmpty && rows[2].maybeAbove.isEmpty)
        XCTAssertEqual([lemmy], rows[0].maybe)
        XCTAssertTrue(rows[0].maybeInWords.isEmpty)
    }

    func testSeveralMaybesStackBelowMyNightInLaneOrder() {
        let tom = Friend(setlistfm: "Tom", name: "Tom")
        let m1 = show("m1", "21-11-2025", "Blå")
        let n1 = local("n1", "21-11-2025", "Brenneriveien 9")
        let t1 = local("t1", "21-11-2025", "Rockefeller")
        let rows = weaveTimelines(
            mine: [show("m0", "22-11-2025", "Sentrum"), m1, show("m2", "20-11-2025", "Parkteatret")],
            friends: [tom, ozzy, lemmy], theirs: [
                "Tom": [t1], "Ozzy": [show("b1", "21-11-2025", "Victoria")], "Lemmy": [n1],
            ])
        XCTAssertEqual(["m0", "m1", "t1", "n1", "b1", "m2"], ids(rows))
        XCTAssertEqual([MaybeNight(friend: tom, mine: m1, theirs: t1)], rows[2].maybeAbove)
        XCTAssertEqual([MaybeNight(friend: lemmy, mine: m1, theirs: n1)], rows[3].maybeAbove)
        XCTAssertEqual([tom, lemmy], rows[1].maybe)
        XCTAssertTrue(rows[1].maybeInWords.isEmpty)
    }

    func testTheirNightSitsBelowTheFirstNightOfMineThatAsks() {
        let m1 = local("m1", "21-11-2025", "Blå")
        let m2 = local("m2", "21-11-2025", "Rockefeller")
        let n1 = local("n1", "21-11-2025", "Brenneriveien 9")
        let rows = weaveTimelines(mine: [m1, m2], friends: [lemmy], theirs: ["Lemmy": [n1]])
        XCTAssertEqual(["m1", "n1", "m2"], ids(rows))
        XCTAssertEqual([MaybeNight(friend: lemmy, mine: m1, theirs: n1)], rows[1].maybeAbove)
        XCTAssertTrue(rows[0].maybeInWords.isEmpty)
        XCTAssertEqual([lemmy], rows[2].maybeInWords)
    }

    func testSameNightDrawsMyNodeJoinedAndUndoAsksAgain() {
        let m1 = show("m1", "21-11-2025", "Blå")
        let n1 = local("n1", "21-11-2025", "Brenneriveien 9")
        let cache = TimelineCache()
        func weave(_ c: TimelineCache) -> [WovenRow] {
            weaveTimelines(mine: [m1], friends: [lemmy], theirs: ["Lemmy": [n1]],
                           joins: c.nightJoins, apart: c.nightDismissals.mapValues { Set($0) })
        }
        let before = weave(cache)
        let joined = weave(cache.joiningNight("n1", gigId: "m1"))
        XCTAssertEqual(1, joined.count)
        XCTAssertEqual([lemmy], joined[0].joinedWith)
        XCTAssertEqual(1, joined[0].sharedCount)
        XCTAssertTrue(joined.allSatisfy { $0.maybeAbove.isEmpty && $0.maybe.isEmpty })
        let apart = weave(cache.dismissingMaybe("n1", gigId: "m1"))
        XCTAssertEqual(2, apart.count)
        XCTAssertTrue(apart.allSatisfy { $0.maybeAbove.isEmpty && $0.maybe.isEmpty })
        for undone in [cache.joiningNight("n1", gigId: "m1").unjoiningNight("n1", gigId: "m1"),
                       cache.dismissingMaybe("n1", gigId: "m1").undismissingMaybe("n1", gigId: "m1")] {
            let restored = weave(undone)
            XCTAssertEqual(ids(before), ids(restored))
            XCTAssertEqual(before.map(\.maybeAbove), restored.map(\.maybeAbove))
            XCTAssertEqual(before.map(\.maybe), restored.map(\.maybe))
        }
        XCTAssertEqual([MaybeNight(friend: lemmy, mine: m1, theirs: n1)], before[1].maybeAbove)
    }

    func testAMultiDayFestivalIsNotDraggedAcrossDatesToPlaceAMaybe() {
        let rows = weaveTimelines(
            mine: [show("m1", "21-11-2025", "Blå")], festivals: festival("f1", "f2"),
            friends: [lemmy], theirs: ["Lemmy": [local("f1", "21-11-2025", "Festival"),
                                                 local("f2", "23-11-2025", "Festival")]])
        XCTAssertEqual([parseFmDate("23-11-2025"), parseFmDate("21-11-2025")], rows.map(\.date))
        XCTAssertTrue(rows.allSatisfy { $0.maybeAbove.isEmpty })
        XCTAssertEqual([lemmy], rows.first { $0.mine }?.maybeInWords)
    }

    func testComparisonHighlightsDifferingFieldsAndReadsThemRowByRow() {
        var mine = show("m1", "21-11-2025", "Blå")
        mine.artist = FmArtist(name: "Isak Benjamin")
        mine.venue = FmVenue(name: "Blå", city: FmCity(name: "Oslo"))
        var theirs = local("n1", "21-11-2025", "Brenneriveien 9")
        theirs.artist = FmArtist(name: "isak benjamin ")
        theirs.venue = FmVenue(name: "Brenneriveien 9", city: FmCity(name: "Oslo"))
        let maybe = MaybeNight(friend: Friend(setlistfm: "", name: "Mia"), mine: mine, theirs: theirs)
        let fields = compareMaybe(maybe)
        XCTAssertEqual(["Artist", "Date", "Venue", "City", "From"], fields.map(\.label))
        XCTAssertEqual(["Venue"], fields.filter(\.differs).map(\.label))
        XCTAssertEqual("21 Nov 2025", fields[1].yours)
        XCTAssertEqual("setlist.fm", fields[4].yours)
        XCTAssertEqual("typed by hand", fields[4].theirs)
        XCTAssertEqual("Venue: yours Blå, Mia's Brenneriveien 9, differs", maybeFieldSpoken(maybe, fields[2]))
        XCTAssertEqual("City: yours Oslo, Mia's Oslo", maybeFieldSpoken(maybe, fields[3]))
        XCTAssertEqual("Same night as Mia's?", maybePill(maybe))
        XCTAssertEqual("Maybe the same night: yours above, Mia's below. Compare them.", maybeMergeLabel(maybe))
        XCTAssertEqual("Joined with Mia's night", maybeAnswered(maybe, same: true))
        XCTAssertEqual("Kept apart from Mia's night", maybeAnswered(maybe, same: false))
    }

    func testComparisonNamesWhichRecordIsKept() {
        let mia = Friend(setlistfm: "", name: "Mia")
        let fm = show("m1", "21-11-2025", "Blå")
        let hand = local("h1", "21-11-2025", "Blå")
        XCTAssertEqual("Same night keeps your setlist.fm entry. Mia's joins it.",
                       sameNightLine(MaybeNight(friend: mia, mine: fm, theirs: hand)))
        XCTAssertEqual("Mia's is on setlist.fm. After Same night you can take it as yours.",
                       sameNightLine(MaybeNight(friend: mia, mine: hand, theirs: fm)))
        XCTAssertEqual("Same night keeps your entry. Mia's joins it.",
                       sameNightLine(MaybeNight(friend: mia, mine: hand,
                                                theirs: local("n1", "21-11-2025", "Blå"))))
    }

    /// Only a Night of mine typed by hand, against theirs from setlist.fm, is asked to adopt.
    func testOnlyMineByHandAgainstTheirsFromSetlistFmAsksToTakeTheirs() {
        let mia = Friend(setlistfm: "", name: "Mia")
        let fm = show("m1", "21-11-2025", "Blå")
        let hand = local("h1", "21-11-2025", "Blå")
        XCTAssertTrue(maybeAdoptable(MaybeNight(friend: mia, mine: hand, theirs: fm)))
        XCTAssertFalse(maybeAdoptable(MaybeNight(friend: mia, mine: fm, theirs: hand)))
        XCTAssertFalse(maybeAdoptable(MaybeNight(friend: mia, mine: hand, theirs: local("n1", "21-11-2025", "Blå"))))
        XCTAssertEqual("Take Mia's setlist.fm entry?", maybeAdoptQuestion(MaybeNight(friend: mia, mine: hand, theirs: fm)))
    }
}
