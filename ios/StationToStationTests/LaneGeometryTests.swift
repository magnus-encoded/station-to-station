import XCTest
@testable import StationToStation

/// Which Line each person is drawn on at a given row. The merge rule lives here:
/// Lines that share a Node become one, and my Spine is only special in that it
/// never moves to meet anyone. Ported from the Android LaneGeometryTest.
///
/// These assertions now cover the code that *draws*: the Canvas asks `linesAt` and
/// `nodeHost` directly rather than keeping its own private copy of the rule (#69).
final class LaneGeometryTests: XCTestCase {

    private let ozzy = Friend(setlistfm: "Ozzy", name: "Ozzy")
    private let lemmy = Friend(setlistfm: "Lemmy", name: "Lemmy")

    /// Lane 0 is nearest my Spine and belongs to the most recently added friend.
    private var lanes: [Friend] { [ozzy, lemmy] }

    private func row(mine: Bool, _ present: Friend...) -> WovenRow {
        WovenRow(
            node: .concert(FmSetlist(id: "n", artist: FmArtist(name: "A"))),
            mine: mine,
            others: present
        )
    }

    func testAFriendWhoWasntThereStaysInTheirOwnLane() {
        XCTAssertEqual(0, hostLane(row(mine: true), ozzy, lanes))
        XCTAssertEqual(1, hostLane(row(mine: true), lemmy, lanes))
    }

    func testANightIWasAtPullsTheirLineOntoMySpine() {
        let night = row(mine: true, ozzy)
        XCTAssertEqual(Spine, hostLane(night, ozzy, lanes))
        XCTAssertEqual(1, hostLane(night, lemmy, lanes)) // not there, own lane
    }

    func testTwoFriendsAtANightIMissedMergeOntoTheLaneNearestMySpine() {
        let night = row(mine: false, ozzy, lemmy)
        XCTAssertEqual(0, nodeHost(night, lanes))
        XCTAssertEqual(0, hostLane(night, ozzy, lanes))
        XCTAssertEqual(0, hostLane(night, lemmy, lanes)) // came to meet the inner lane
    }

    func testOneFriendAloneAtANightIMissedKeepsTheirOwnLane() {
        let night = row(mine: false, lemmy)
        XCTAssertEqual(1, nodeHost(night, lanes))
        XCTAssertEqual(1, hostLane(night, lemmy, lanes))
        XCTAssertFalse(linesAt(night, lanes).count > 1) // alone is not company
    }

    /// Meeting green comes from the *count* of Lines on a stretch, not from a boolean
    /// about me: a Joined run between two friends is green without me being one of
    /// them. This is the expression the Canvas paints with.
    func testCompanyIsGreenWhoeverItIsWith() {
        XCTAssertTrue(linesAt(row(mine: true, ozzy), lanes).count > 1)
        XCTAssertTrue(linesAt(row(mine: false, ozzy, lemmy), lanes).count > 1)
        XCTAssertFalse(linesAt(row(mine: true), lanes).count > 1)
    }

    func testOnePartingOnTheRowTheOtherJoinsIsTwoIndependentAnswers() {
        // Above: I was out with Lemmy. Here: with Ozzy instead.
        let above = row(mine: true, lemmy)
        let here = row(mine: true, ozzy)

        // Ozzy comes in from their lane to my spine.
        XCTAssertEqual(0, hostLane(above, ozzy, lanes))
        XCTAssertEqual(Spine, hostLane(here, ozzy, lanes))
        // Lemmy leaves my spine for theirs, on the same row. Neither
        // answer depends on the other, which is what a shared Boolean got wrong.
        XCTAssertEqual(Spine, hostLane(above, lemmy, lanes))
        XCTAssertEqual(1, hostLane(here, lemmy, lanes))
    }

    func testTheStripStopsWideningOnceThereAreEnoughFriends() {
        // Few friends: full spacing, strip grows with each one.
        XCTAssertLessThan(stripWidth(2), stripWidth(4))
        XCTAssertEqual(laneStep(2), laneStep(4), accuracy: 0.01)
        // Many: lanes tighten instead of pushing the timeline off the phone.
        XCTAssertEqual(stripWidth(8), stripWidth(20), accuracy: 0.01)
        XCTAssertLessThan(laneStep(20), laneStep(8))
    }

    /// The Int→points conversion the whole grammar rests on, and the only step in it
    /// the collapse does not cover by construction: which Line is a whole number, but
    /// where that Line sits depends on how far the strip has slid open.
    func testTheNodeSitsOnItsHostLaneWhenOpenAndOnMySpineWhenShut() {
        let night = row(mine: false, lemmy) // hosted by Lane 1
        XCTAssertEqual(
            laneXf(nodeHost(night, lanes), laneStep(lanes.count)),
            crossingX(night, lanes, stripWidth(lanes.count)),
            accuracy: 0.01
        )
        XCTAssertEqual(SpineX, crossingX(night, lanes, 0))
    }

    /// Nobody is drawn in a lane they don't have. Kotlin's indexOfFirst returns
    /// -1 (the Spine) for a stranger; lane 0 belongs to a real friend.
    func testAFriendWithNoLaneIsNotGivenLaneZero() {
        let stranger = Friend(setlistfm: "nobody")
        XCTAssertEqual(Spine, hostLane(row(mine: true), stranger, lanes))
    }

    // MARK: - The drawn row
    //
    // Everything below asserts `rowGeometry`, the one value the Canvas strokes, the dump
    // prints and these tests read. It is in points, so there is no density here and
    // nothing renders: the numbers *are* the drawn geometry (#116). The twin of the
    // Kotlin suite's assertions, case for case and number for number — which is what
    // makes "the same rule on both platforms" a passing test rather than a claim.

    /// Both Lanes fully out.
    private var fullyOpen: CGFloat { stripWidth(lanes.count) }

    /// A row with a line of text on it. Only the tail bend depends on this.
    private let ordinary: CGFloat = 96

    private func drawn(
        _ r: WovenRow,
        next: WovenRow? = nil,
        laneWidth: CGFloat? = nil,
        height: CGFloat = 96,
        over: [Friend]? = nil
    ) -> [DrawnLine] {
        let over = over ?? lanes
        return rowGeometry(r, next, over, laneWidth ?? stripWidth(over.count), height)
    }

    private func festivalRow(mine: Bool, _ present: Friend...) -> WovenRow {
        WovenRow(
            // Geometry asks only whether a node holds several nights, never which kind.
            node: .section([
                FmSetlist(id: "f1", artist: FmArtist(name: "A")),
                FmSetlist(id: "f2", artist: FmArtist(name: "B")),
            ]),
            mine: mine,
            others: present
        )
    }

    private func line(_ d: [DrawnLine], _ i: Int) -> DrawnLine? { d.first { $0.line == i } }

    private func at(_ d: [DrawnLine], _ i: Int, file: StaticString = #filePath, line ln: UInt = #line) throws -> DrawnLine {
        try XCTUnwrap(line(d, i), "line \(i) was not drawn", file: file, line: ln)
    }

    /// My Line never moves. The strip opening beside it is the whole gesture, so this is
    /// the rule most exposed to a refactor of the slide.
    func testTheSpinesXIsUntouchedByHowFarTheStripHasOpened() throws {
        for w: CGFloat in [0, 5, 10, 20, 30, fullyOpen] {
            XCTAssertEqual(SpineX, try at(drawn(row(mine: true), laneWidth: w), Spine).x, accuracy: 0.01)
            XCTAssertEqual(SpineX, try at(drawn(row(mine: false, ozzy), laneWidth: w), Spine).x, accuracy: 0.01)
        }
    }

    /// A Lane still behind the Spine is not stroked: nobody could see it.
    func testALaneThatHasNotSlidIntoViewIsAbsentFromTheDrawnSet() {
        XCTAssertEqual([Spine], drawn(row(mine: true), laneWidth: 0).map(\.line))
    }

    /// The strip's openness is scaled by the Lane count, so Lanes arrive one after
    /// another rather than together. Partial openness is its own case, not a point
    /// between two endpoints.
    func testLanesSlideOutOneAfterAnotherAsTheStripOpens() throws {
        let step = laneStep(lanes.count)
        let lane0 = laneXf(0, step) // 45
        let lane1 = laneXf(1, step) // 65

        // A quarter open: the first Lane is half way out, the second has not started.
        let quarter = drawn(row(mine: true), laneWidth: fullyOpen * 0.25)
        XCTAssertEqual(35.5, try at(quarter, 0).x, accuracy: 0.01)
        XCTAssertNil(line(quarter, 1))

        // Half: the first Lane has arrived, the second is only now leaving.
        let half = drawn(row(mine: true), laneWidth: fullyOpen * 0.5)
        XCTAssertEqual(lane0, try at(half, 0).x, accuracy: 0.01)
        XCTAssertNil(line(half, 1))

        // Three quarters: the second is half way.
        let most = drawn(row(mine: true), laneWidth: fullyOpen * 0.75)
        XCTAssertEqual(lane0, try at(most, 0).x, accuracy: 0.01)
        XCTAssertEqual(45.5, try at(most, 1).x, accuracy: 0.01)

        // Fully open: both on their own Lanes.
        let all = drawn(row(mine: true))
        XCTAssertEqual(lane0, try at(all, 0).x, accuracy: 0.01)
        XCTAssertEqual(lane1, try at(all, 1).x, accuracy: 0.01)
    }

    /// A Node is a ring you see through, and a Line drawn inside one fills it in. So a
    /// Line that was there stops at the rim and picks up on the far side.
    func testALineStopsAtItsNodesRimAndResumesPastIt() throws {
        let d = try at(drawn(row(mine: true)), Spine)
        XCTAssertTrue(d.present)
        XCTAssertGreaterThan(d.nodeR, 0)
        XCTAssertGreaterThan(d.nodeY - d.nodeR, 0, "the approach must end above the rim")
        XCTAssertLessThan(d.nodeY + d.nodeR, ordinary, "the trunk must start below the rim")
    }

    /// Three radii, three kinds of night — a member gig's smaller ring keeps its
    /// proportion by having its own radius, not by scaling the gig's.
    func testTheRimGapDiffersByNodeKind() throws {
        var member = row(mine: true)
        member.depth = 1

        XCTAssertEqual(7, try at(drawn(row(mine: true)), Spine).nodeR, accuracy: 0.01)
        XCTAssertEqual(5, try at(drawn(member), Spine).nodeR, accuracy: 0.01)
        XCTAssertEqual(11, try at(drawn(festivalRow(mine: true)), Spine).nodeR, accuracy: 0.01)

        // And the Node itself sits lower on a festival, which is a bigger ring.
        XCTAssertEqual(13, try at(drawn(row(mine: true)), Spine).nodeY, accuracy: 0.01)
        XCTAssertEqual(15, try at(drawn(festivalRow(mine: true)), Spine).nodeY, accuracy: 0.01)
    }

    /// A stranger's Lane is not notched by a night they missed.
    func testALineNobodyPresentIsOnRunsPastTheNodeWithNoRimGap() throws {
        let d = try at(drawn(row(mine: true)), 0) // Ozzy was not there
        XCTAssertFalse(d.present)
        XCTAssertEqual(0, d.nodeR, accuracy: 0.01)
        XCTAssertEqual(LineColour.absent, d.colour)
    }

    /// Merged Lines are one Line by definition, so without the weight two of them stroke
    /// the same path twice and look exactly like one. The per-person increment is a
    /// stated rule, not a constant that happens to look right at two.
    func testStrokeWeightSaysHowManyWalkTheStretchTogether() throws {
        let dio = Friend(setlistfm: "Dio", name: "Dio")
        let three = [ozzy, lemmy, dio]
        func spineWidth(_ with: Friend...) throws -> CGFloat {
            let r = WovenRow(node: .concert(FmSetlist(id: "n", artist: FmArtist(name: "A"))), mine: true, others: with)
            return try at(drawn(r, over: three), Spine).width
        }

        XCTAssertEqual(2.0, try spineWidth(), accuracy: 0.01)
        XCTAssertEqual(3.2, try spineWidth(ozzy), accuracy: 0.01)
        XCTAssertEqual(4.4, try spineWidth(ozzy, lemmy), accuracy: 0.01)
        XCTAssertEqual(5.6, try spineWidth(ozzy, lemmy, dio), accuracy: 0.01)

        XCTAssertEqual(1, try at(drawn(row(mine: true), over: three), Spine).people)
        let all = WovenRow(node: .concert(FmSetlist(id: "n", artist: FmArtist(name: "A"))), mine: true, others: three)
        XCTAssertEqual(4, try at(drawn(all, over: three), Spine).people)
    }

    /// Colour is a role, so "more than one Line here is meeting green" is testable with
    /// nothing rendered.
    func testColourIsARoleThatFollowsTheGeometry() throws {
        XCTAssertEqual(LineColour.mine(present: true), try at(drawn(row(mine: true)), Spine).colour)
        XCTAssertEqual(LineColour.mine(present: false), try at(drawn(row(mine: false, lemmy)), Spine).colour)
        // A friend alone on their own Lane takes their own light.
        XCTAssertEqual(LineColour.rail(colourIndex: 1), try at(drawn(row(mine: false, lemmy)), 1).colour)
        // Company, whoever it is with.
        XCTAssertEqual(LineColour.meeting, try at(drawn(row(mine: true, ozzy)), Spine).colour)
    }

    /// Meeting green follows the geometry, not my own presence.
    func testACrossingBetweenTwoFriendsIMissedIsGreen() throws {
        let d = drawn(row(mine: false, ozzy, lemmy))
        // They lie on top of each other, so they are one Line and must read as one.
        XCTAssertEqual(try at(d, 0).x, try at(d, 1).x, accuracy: 0.01)
        XCTAssertEqual(LineColour.meeting, try at(d, 0).colour)
        XCTAssertEqual(LineColour.meeting, try at(d, 1).colour)
        XCTAssertEqual(2, try at(d, 0).people)
        // I am not on it, and my own Line says so rather than borrowing their green.
        XCTAssertEqual(LineColour.mine(present: false), try at(d, Spine).colour)
    }

    /// The last stretch of a row belongs to the edge ahead and *every* Line gets it, not
    /// only the ones that bend — or a Spine stays green after its company has left,
    /// claiming a Crossing that ended.
    func testAPartingReturnsEachLineToItsOwnColourOnTheEdgeAhead() throws {
        let together = row(mine: true, ozzy)
        let alone = row(mine: true)

        let spine = try at(drawn(together, next: alone), Spine)
        XCTAssertEqual(LineColour.meeting, spine.colour)              // green through the row
        XCTAssertEqual(LineColour.mine(present: true), spine.colourAhead) // and alone below it
        XCTAssertEqual(1, spine.peopleAhead)
        XCTAssertEqual(2.0, spine.widthAhead, accuracy: 0.01)

        let leaving = try at(drawn(together, next: alone), 0)
        XCTAssertEqual(LineColour.meeting, leaving.colour)
        XCTAssertEqual(LineColour.rail(colourIndex: 0), leaving.colourAhead)  // takes its colour back
        XCTAssertEqual(laneXf(0, laneStep(lanes.count)), leaving.toX, accuracy: 0.01) // swings out

        // The row after the parting: nothing green is left of it.
        for d in drawn(alone) { XCTAssertNotEqual(LineColour.meeting, d.colour) }
    }

    /// A Line may not jump between rows the way the spine jog once did.
    func testARowsOutgoingXIsTheNextRowsIncomingX() throws {
        let a = row(mine: true, ozzy)
        let b = row(mine: false, lemmy)
        let c = row(mine: true)

        for l in [Spine, 0, 1] {
            XCTAssertEqual(try at(drawn(b, next: c), l).x, try at(drawn(a, next: b), l).toX, accuracy: 0.01)
            XCTAssertEqual(try at(drawn(c), l).x, try at(drawn(b, next: c), l).toX, accuracy: 0.01)
        }
    }

    /// The end of the Spine is defined, not whatever the loop happened to leave.
    func testTheLastRowLeavesWhereItEntered() {
        for d in drawn(row(mine: true, ozzy), next: nil) {
            XCTAssertEqual(d.x, d.toX, accuracy: 0.01)
        }
    }

    /// The bend is never longer than the room below the Node, or a short row draws its
    /// straight stretch backwards before turning.
    func testTheTailBendNeverExceedsTheRoomBelowTheNode() throws {
        // An ordinary row has room to spare, so the bend is the full edge bend.
        XCTAssertEqual(56, try at(drawn(row(mine: true)), Spine).bendLen, accuracy: 0.01)

        // A short one gives up most of what is left rather than all of it.
        let short = try at(drawn(row(mine: true), height: 30), Spine)
        XCTAssertEqual(8, short.bendLen, accuracy: 0.01) // (30 - 13 - 7) * 0.8
        XCTAssertLessThanOrEqual(short.bendLen, 30 - short.nodeY - short.nodeR)

        // Shorter than the Node itself: clamped to nothing, never negative.
        XCTAssertEqual(0, try at(drawn(row(mine: true), height: 15), Spine).bendLen, accuracy: 0.01)

        for h: CGFloat in [0, 15, 24, 30, 60, ordinary] {
            let d = try at(drawn(row(mine: true), height: h), Spine)
            XCTAssertGreaterThanOrEqual(d.bendLen, 0, "bend ran backwards at \(h)")
            XCTAssertLessThanOrEqual(d.bendLen, max(h - d.nodeY - d.nodeR, 0), "bend overran the row at \(h)")
        }
    }

    // MARK: - The shared weave fixtures, through the drawn geometry

    /// The nights both platforms already agree on, asserted as points rather than as
    /// rows. `two-lines-crossing` is a Crossing and the Parting after it.
    func testTheFixtureCrossingDrawsBothLinesOnMySpineAndPartsBelowIt() async throws {
        let (rows, friends) = try await WeaveFixture.load("two-lines-crossing")
        let strip = stripWidth(friends.count)
        func geom(_ i: Int) -> [DrawnLine] {
            rowGeometry(rows[i], i + 1 < rows.count ? rows[i + 1] : nil, friends, strip, ordinary)
        }

        // Row 1 is Sløtface, together. One Node, two Lines on it, one green stroke twice
        // as heavy as a person walking alone.
        let together = geom(1)
        XCTAssertEqual(SpineX, try at(together, Spine).x, accuracy: 0.01)
        XCTAssertEqual(SpineX, try at(together, 0).x, accuracy: 0.01)
        XCTAssertEqual(LineColour.meeting, try at(together, Spine).colour)
        XCTAssertEqual(3.2, try at(together, Spine).width, accuracy: 0.01)
        XCTAssertEqual(7, try at(together, 0).nodeR, accuracy: 0.01)
        // And below it Ozzy leaves for Turnstile, so the edge ahead is nobody's green.
        XCTAssertEqual(LineColour.mine(present: true), try at(together, Spine).colourAhead)
        XCTAssertEqual(LineColour.rail(colourIndex: 0), try at(together, 0).colourAhead)

        // Row 2 is Turnstile, theirs. Their Node sits on their Lane and mine runs past it
        // without a notch, dimmed because I was not there.
        let theirs = geom(2)
        XCTAssertEqual(laneXf(0, laneStep(friends.count)), try at(theirs, 0).x, accuracy: 0.01)
        XCTAssertEqual(LineColour.rail(colourIndex: 0), try at(theirs, 0).colour)
        XCTAssertEqual(7, try at(theirs, 0).nodeR, accuracy: 0.01)
        XCTAssertEqual(SpineX, try at(theirs, Spine).x, accuracy: 0.01)
        XCTAssertEqual(0, try at(theirs, Spine).nodeR, accuracy: 0.01)
        XCTAssertEqual(LineColour.mine(present: false), try at(theirs, Spine).colour)
    }

    /// `three-lines-tons-of-rock`: a festival all three of us were at, and above it a gig
    /// only one friend was — the case a screenshot once misread as a merge the data said
    /// never happened. Here it is a disagreement between x values, which a test can state.
    func testTheFixtureFestivalMergesThreeLinesAndTheRowBelowMergesNone() async throws {
        let (rows, friends) = try await WeaveFixture.load("three-lines-tons-of-rock")
        let strip = stripWidth(friends.count)
        let step = laneStep(friends.count)
        func geom(_ i: Int) -> [DrawnLine] {
            rowGeometry(rows[i], i + 1 < rows.count ? rows[i + 1] : nil, friends, strip, ordinary)
        }

        let festival = geom(1)
        for l in [Spine, 0, 1] {
            XCTAssertEqual(SpineX, try at(festival, l).x, accuracy: 0.01)
            XCTAssertEqual(LineColour.meeting, try at(festival, l).colour)
            XCTAssertEqual(3, try at(festival, l).people)
            XCTAssertEqual(4.4, try at(festival, l).width, accuracy: 0.01)
            XCTAssertEqual(15, try at(festival, l).nodeY, accuracy: 0.01) // a festival sits lower
            XCTAssertEqual(11, try at(festival, l).nodeR, accuracy: 0.01) // and its ring is biggest
        }

        // The row below: Kvelertak, which only Lemmy was at. Three Lines, three x's,
        // nothing merged — and each one carrying exactly one person.
        let kvelertak = geom(2)
        XCTAssertEqual(SpineX, try at(kvelertak, Spine).x, accuracy: 0.01)
        XCTAssertEqual(laneXf(0, step), try at(kvelertak, 0).x, accuracy: 0.01)
        XCTAssertEqual(laneXf(1, step), try at(kvelertak, 1).x, accuracy: 0.01)
        XCTAssertEqual(3, Set(kvelertak.map(\.x)).count)
        for d in kvelertak { XCTAssertEqual(1, d.people) }
        XCTAssertNotNil(line(kvelertak, 1))
        XCTAssertEqual(LineColour.rail(colourIndex: 0), try at(kvelertak, 0).colour) // Lemmy, alone
    }

    // MARK: - Hiding a Line (#266)
    //
    // Tapping a name in the legend is nothing more than a shorter lane list, so every
    // assertion below is the same `rowGeometry` call with fewer people in it.

    private var three: [Friend] { [ozzy, lemmy, Friend(setlistfm: "Dio", name: "Dio")] }

    private func drawnHiding(_ r: WovenRow, next: WovenRow? = nil,
                             hidden: Set<String> = []) -> [DrawnLine] {
        let shown = visibleLanes(three, hidden)
        return rowGeometry(r, next, shown, stripWidth(shown.count), ordinary,
                           laneColours(three, hidden))
    }

    /// The seam itself: who is left, and which colour each of them keeps.
    func testTheFilterIsOneShorterListPlusTheColoursItLeftBehind() {
        XCTAssertEqual(three.map(\.setlistfm), visibleLanes(three, []).map(\.setlistfm))
        XCTAssertEqual([0, 1, 2], laneColours(three, []))

        XCTAssertEqual(["Ozzy", "Dio"], visibleLanes(three, ["Lemmy"]).map(\.setlistfm))
        XCTAssertEqual([0, 2], laneColours(three, ["Lemmy"]))

        // Someone who is not a Followed line at all cannot hide anyone.
        XCTAssertEqual(three.map(\.setlistfm), visibleLanes(three, ["Nobody"]).map(\.setlistfm))
    }

    /// Gone, not dimmed: the strip has to actually get quieter.
    func testAHiddenLineIsNotDrawnAtAll() {
        let r = row(mine: false, lemmy)
        XCTAssertNotNil(drawnHiding(r).first { $0.line == 1 })
        // Lemmy is lane 1 of three; hide him and no Line is left carrying him.
        let quieter = drawnHiding(r, hidden: ["Lemmy"])
        XCTAssertTrue(quieter.allSatisfy { !$0.present || $0.line == Spine })
        XCTAssertEqual(2, quieter.filter { $0.line != Spine }.count)
    }

    // MARK: - The legend's recency order (#396)

    /// One sort key: active Lanes first (whatever order they came in), then hidden
    /// ones by how recently they were toggled off, most recent first.
    func testLegendOrdersActiveFirstThenMostRecentlyHidden() {
        let dio = Friend(setlistfm: "Dio", name: "Dio")
        let ordered = legendOrder(
            [ozzy, lemmy, dio],
            hiddenAt: ["Ozzy": 100, "Dio": 200]
        )
        // Lemmy is active, so first regardless of the others' timestamps. Dio was
        // hidden more recently than Ozzy, so Dio comes next.
        XCTAssertEqual(["Lemmy", "Dio", "Ozzy"], ordered.map(\.setlistfm))
    }

    /// The head never drops an active Lane, even past `headSize` — losing the group
    /// you are actually comparing is the bug this exists to fix.
    func testLegendSplitNeverCutsAnActiveLaneFromTheHead() {
        let dio = Friend(setlistfm: "Dio", name: "Dio")
        let motorhead = Friend(setlistfm: "Motorhead", name: "Motorhead")
        let (head, rest) = legendSplit(
            [ozzy, lemmy, dio, motorhead],
            hiddenAt: ["Dio": 1],
            headSize: 2
        )
        // Three are active (Ozzy, Lemmy, Motorhead); the floor of 2 is exceeded to
        // fit all three, and only the one hidden Lane falls into the disclosure.
        XCTAssertEqual(3, head.count)
        XCTAssertTrue(head.allSatisfy { $0.setlistfm != "Dio" })
        XCTAssertEqual(["Dio"], rest.map(\.setlistfm))
    }

    /// A short list gets no disclosure at all: everyone fits under `headSize`.
    func testLegendSplitLeavesNothingForTheDisclosureWhenTheListIsShort() {
        let (head, rest) = legendSplit([ozzy, lemmy], hiddenAt: [:], headSize: 6)
        XCTAssertEqual(2, head.count)
        XCTAssertTrue(rest.isEmpty)
    }

    /// The rest is a disclosure, not a truncation (#266): every hidden name past the
    /// head is still there, in the same recency order, just not drawn yet.
    func testLegendSplitsRestContinuesTheSameOrder() {
        let dio = Friend(setlistfm: "Dio", name: "Dio")
        let motorhead = Friend(setlistfm: "Motorhead", name: "Motorhead")
        let hiddenAt: [String: Int64] = ["Ozzy": 100, "Lemmy": 300, "Dio": 200, "Motorhead": 50]
        let (head, rest) = legendSplit(
            [ozzy, lemmy, dio, motorhead], hiddenAt: hiddenAt, headSize: 2
        )
        XCTAssertEqual(["Lemmy", "Dio"], head.map(\.setlistfm))
        XCTAssertEqual(["Ozzy", "Motorhead"], rest.map(\.setlistfm))
    }

    /// The half the seam does not give for free: a colour you have learned to read
    /// goes on meaning the same person after someone inside them is hidden.
    func testHidingSomeoneRepaintsNobody() throws {
        // Dio is lane 2, colour 2. Hide Lemmy and Dio is drawn on lane 1 — with his
        // own colour still, not the one lane 1 used to wear.
        let dio = three[2]
        let r = row(mine: false, dio)
        XCTAssertEqual(LineColour.rail(colourIndex: 2), try at(drawnHiding(r), 2).colour)
        XCTAssertEqual(LineColour.rail(colourIndex: 2),
                       try at(drawnHiding(r, hidden: ["Lemmy"]), 1).colour)
    }
}
