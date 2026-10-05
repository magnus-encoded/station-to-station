import XCTest
@testable import StationToStation

@MainActor
final class ContactsControllerTests: XCTestCase {

    private var model: AppModel!

    override func setUp() async throws {
        model = AppModel()
        for friend in model.state.friends { model.removeFriend(friend) }
    }

    override func tearDown() async throws {
        for friend in model.state.friends { model.removeFriend(friend) }
        model = nil
    }

    // Ids unique per run: the store is shared with whatever ran before.
    private func id(_ name: String) -> String { "\(name)-\(UUID().uuidString)" }

    private func night(_ id: String) -> FmSetlist {
        FmSetlist(id: id, eventDate: "25-06-2026", artist: FmArtist(name: "Kvelertak"))
    }

    private func maybe(mine: String, theirs: String) -> MaybeNight {
        MaybeNight(friend: Friend(setlistfm: "ozzy"), mine: night(mine), theirs: night(theirs))
    }

    private func settled(_ done: () -> Bool) async {
        for _ in 0..<1000 where !done() { await Task.yield() }
    }

    func testJoiningANightShowsTheJoinOnceWritten() async {
        let mine = id("mine"), theirs = id("theirs")
        await model.joinNight(theirs, key: mine)
        XCTAssertEqual(mine, model.state.nightJoins[theirs])
    }

    func testDismissingAMaybeKeepsTheNightsApart() async {
        let mine = id("mine"), theirs = id("theirs")
        await model.dismissMaybe(theirs, key: mine)
        XCTAssertEqual([mine], model.state.nightsApart[theirs])
    }

    func testAnsweringAMaybeOffersUndoForThatAnswer() async {
        let m = maybe(mine: id("mine"), theirs: id("theirs"))
        await model.answerMaybe(m, same: true)
        XCTAssertEqual(m.mine.id, model.state.nightJoins[m.theirs.id])
        XCTAssertEqual(m, model.maybeUndo?.maybe)
        XCTAssertEqual(true, model.maybeUndo?.same)
    }

    func testUndoingAJoinBringsTheMaybeBack() async {
        let m = maybe(mine: id("mine"), theirs: id("theirs"))
        await model.answerMaybe(m, same: true)
        await model.undoMaybe(model.maybeUndo!)
        XCTAssertNil(model.state.nightJoins[m.theirs.id])
        XCTAssertNil(model.maybeUndo)
    }

    func testUndoingADismissalBringsTheMaybeBack() async {
        let m = maybe(mine: id("mine"), theirs: id("theirs"))
        await model.answerMaybe(m, same: false)
        await model.undoMaybe(model.maybeUndo!)
        XCTAssertTrue(model.state.nightsApart[m.theirs.id]?.isEmpty ?? true)
        XCTAssertNil(model.maybeUndo)
    }

    func testAnUndoForAnEarlierAnswerDoesNothing() async {
        let m = maybe(mine: id("mine"), theirs: id("theirs"))
        await model.answerMaybe(m, same: true)
        let earlier = model.maybeUndo!
        await model.answerMaybe(maybe(mine: id("mine"), theirs: id("theirs")), same: true)
        await model.undoMaybe(earlier)
        XCTAssertEqual(m.mine.id, model.state.nightJoins[m.theirs.id])
        XCTAssertNotNil(model.maybeUndo)
    }

    func testAMaybeThatCannotBeAdoptedIsJoinedInstead() async {
        let m = maybe(mine: id("mine"), theirs: id("theirs"))
        await model.adoptMaybe(m)
        XCTAssertEqual(m.mine.id, model.state.nightJoins[m.theirs.id])
        XCTAssertEqual(true, model.maybeUndo?.same)
    }

    func testHeldOffersReachTheScreen() async {
        let theirs = id("theirs")
        await model.holdMediaOffers([theirs: MediaOffer(date: "2026-06-25", artist: "Kvelertak")])
        XCTAssertEqual("Kvelertak", model.state.mediaOffers[theirs]?.artist)
    }

    func testDecliningAnOfferIsWrittenThenShown() async {
        let theirs = id("theirs")
        await model.holdMediaOffers([theirs: MediaOffer(date: "2026-06-25", artist: "Kvelertak")])
        let before = model.state.mediaOffers[theirs]
        model.declineMediaOffer(theirs)
        await settled { model.state.mediaOffers[theirs] != before }
        XCTAssertNotEqual(before, model.state.mediaOffers[theirs])
    }

    func testAContactsNightsLandUnderTheirLane() async {
        let key = id("k")
        model.addFriend(Friend(setlistfm: "", name: "Lemmy", publicKey: key))
        let lane = model.state.friends.first!.laneKey
        let theirs = id("theirs")
        await model.landContactNights(" \(key) ", [night(theirs)])
        XCTAssertEqual([theirs], model.state.showsByFriend[lane]?.map(\.id))
    }

    func testNightsFromAKeyNoContactHoldsLandNothing() async {
        let before = model.state.showsByFriend
        await model.landContactNights(id("stranger"), [night(id("theirs"))])
        XCTAssertEqual(before.mapValues { $0.map(\.id) }, model.state.showsByFriend.mapValues { $0.map(\.id) })
    }

    func testAChangedKeyAsksBeforeOverwriting() {
        model.addFriend(Friend(setlistfm: "ozzy", publicKey: "k1"))
        model.addFriend(Friend(setlistfm: "ozzy", publicKey: "k2"))
        XCTAssertEqual("k2", model.state.friendConflict?.incoming.publicKey)
        model.confirmFriendOverwrite()
        XCTAssertNil(model.state.friendConflict)
        XCTAssertEqual("k2", model.state.friends.first?.publicKey)
    }

    func testRemovingAContactKeepsTheOthers() {
        model.addFriend(Friend(setlistfm: "ozzy"))
        model.addFriend(Friend(setlistfm: "lemmy"))
        model.removeFriend(Friend(setlistfm: "ozzy"))
        XCTAssertEqual(["lemmy"], model.state.friends.map(\.setlistfm))
    }

    func testSharedConcertsWithAnAccountlessContactAreTheNightsThisPhoneHolds() async {
        let friend = Friend(setlistfm: "", name: "Lemmy", publicKey: id("k"))
        model.openSharedConcerts(friend)
        XCTAssertEqual("You & Lemmy", model.state.setlistsTitle)
        XCTAssertTrue(model.state.setlistsLoading)
        await settled { !model.state.setlistsLoading }
        XCTAssertFalse(model.state.setlistsLoading)
        XCTAssertEqual([], model.state.setlists.map(\.id))
        XCTAssertEqual(friend.laneKey, model.state.sharedWith?.laneKey)
    }
}
