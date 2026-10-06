import XCTest
@testable import StationToStation

@MainActor
final class ContactsControllerTests: XCTestCase {

    private var fake: FakeState!
    private var contacts: ContactsController!
    private var file: URL!
    private var timelines: TimelineStore!
    private var gossip: GossipController!
    private var gig: GigController!

    override func setUp() async throws {
        fake = FakeState()
        file = FileManager.default.temporaryDirectory
            .appendingPathComponent("timelines-\(UUID().uuidString).json")
        let settings = Settings()
        timelines = TimelineStore(file: file)
        let setlistFm = SetlistFmClient(keySource: { nil })
        gossip = GossipController(host: fake, timelines: timelines)
        gig = GigController(host: fake, timelines: timelines, setlistFm: setlistFm,
                            location: DeviceLocation(), gossip: gossip)
        contacts = ContactsController(
            host: fake, settings: settings, timelines: timelines,
            setlistFm: setlistFm, spotify: SpotifyClient(settings),
            logic: TimelineLogic(plumbing: DeviceTimelinePlumbing(store: timelines, client: setlistFm)),
            gossip: gossip, gig: gig)
        settings.saveFriends([])
    }

    override func tearDown() async throws {
        Settings().saveFriends([])
        try? FileManager.default.removeItem(at: file)
        contacts = nil
        gig = nil
        gossip = nil
        timelines = nil
        fake = nil
    }

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
        await contacts.joinNight(theirs, key: mine)
        XCTAssertEqual(mine, fake.state.nightJoins[theirs])
    }

    func testDismissingAMaybeKeepsTheNightsApart() async {
        let mine = id("mine"), theirs = id("theirs")
        await contacts.dismissMaybe(theirs, key: mine)
        XCTAssertEqual([mine], fake.state.nightsApart[theirs])
    }

    func testAnsweringAMaybeOffersUndoForThatAnswer() async {
        let m = maybe(mine: id("mine"), theirs: id("theirs"))
        await contacts.answerMaybe(m, same: true)
        XCTAssertEqual(m.mine.id, fake.state.nightJoins[m.theirs.id])
        XCTAssertEqual(m, contacts.maybeUndo?.maybe)
        XCTAssertEqual(true, contacts.maybeUndo?.same)
    }

    func testUndoingAJoinBringsTheMaybeBack() async {
        let m = maybe(mine: id("mine"), theirs: id("theirs"))
        await contacts.answerMaybe(m, same: true)
        await contacts.undoMaybe(contacts.maybeUndo!)
        XCTAssertNil(fake.state.nightJoins[m.theirs.id])
        XCTAssertNil(contacts.maybeUndo)
    }

    func testUndoingADismissalBringsTheMaybeBack() async {
        let m = maybe(mine: id("mine"), theirs: id("theirs"))
        await contacts.answerMaybe(m, same: false)
        await contacts.undoMaybe(contacts.maybeUndo!)
        XCTAssertTrue(fake.state.nightsApart[m.theirs.id]?.isEmpty ?? true)
        XCTAssertNil(contacts.maybeUndo)
    }

    func testAnUndoForAnEarlierAnswerDoesNothing() async {
        let m = maybe(mine: id("mine"), theirs: id("theirs"))
        await contacts.answerMaybe(m, same: true)
        let earlier = contacts.maybeUndo!
        await contacts.answerMaybe(maybe(mine: id("mine"), theirs: id("theirs")), same: true)
        await contacts.undoMaybe(earlier)
        XCTAssertEqual(m.mine.id, fake.state.nightJoins[m.theirs.id])
        XCTAssertNotNil(contacts.maybeUndo)
    }

    func testAMaybeThatCannotBeAdoptedIsJoinedInstead() async {
        let m = maybe(mine: id("mine"), theirs: id("theirs"))
        await contacts.adoptMaybe(m)
        XCTAssertEqual(m.mine.id, fake.state.nightJoins[m.theirs.id])
        XCTAssertEqual(true, contacts.maybeUndo?.same)
    }

    func testAnAdoptedMaybeIsNeitherJoinedNorUndoable() async {
        let mine = await timelines.createLocalGig(date: "25-06-2026", artist: "Kvelertak", venue: "Rockefeller")
        let m = maybe(mine: mine, theirs: id("theirs"))
        await contacts.answerMaybe(maybe(mine: id("mine"), theirs: id("theirs")), same: false)
        await contacts.adoptMaybe(m)
        let adopted = await timelines.load().gigs[mine]?.setlistId
        XCTAssertEqual(m.theirs.id, adopted)
        XCTAssertNil(fake.state.nightJoins[m.theirs.id])
        XCTAssertNil(contacts.maybeUndo)
    }

    func testHeldOffersReachTheScreen() async {
        let theirs = id("theirs")
        await contacts.holdMediaOffers([theirs: MediaOffer(date: "2026-06-25", artist: "Kvelertak")])
        XCTAssertEqual("Kvelertak", fake.state.mediaOffers[theirs]?.artist)
    }

    func testADeclinedOfferKeepsOnlyWhatWasDeclined() async {
        let theirs = id("theirs")
        let photo = StoredMedia(id: "m-1", kind: StoredMedia.Kind.photo, ref: "r-1")
        await contacts.holdMediaOffers([theirs: MediaOffer(date: "2026-06-25", media: [photo])])
        contacts.declineMediaOffer(theirs)
        await settled { fake.state.mediaOffers[theirs]?.media.isEmpty == true }
        XCTAssertEqual([], fake.state.mediaOffers[theirs]?.media.map(\.id))
        XCTAssertEqual(["m-1"], fake.state.mediaOffers[theirs]?.declined)
    }

    func testAContactsNightsLandUnderTheirLane() async {
        let key = id("k")
        contacts.addFriend(Friend(setlistfm: "", name: "Lemmy", publicKey: key))
        let lane = fake.state.friends.first!.laneKey
        let theirs = id("theirs")
        await contacts.landContactNights(" \(key) ", [night(theirs)])
        XCTAssertEqual([theirs], fake.state.showsByFriend[lane]?.map(\.id))
    }

    func testNightsFromAKeyNoContactHoldsLandNothing() async {
        let before = fake.state.showsByFriend
        await contacts.landContactNights(id("stranger"), [night(id("theirs"))])
        XCTAssertEqual(before.mapValues { $0.map(\.id) }, fake.state.showsByFriend.mapValues { $0.map(\.id) })
    }

    func testAChangedKeyAsksBeforeOverwriting() {
        contacts.addFriend(Friend(setlistfm: "ozzy", publicKey: "k1"))
        contacts.addFriend(Friend(setlistfm: "ozzy", publicKey: "k2"))
        XCTAssertEqual("k2", fake.state.friendConflict?.incoming.publicKey)
        contacts.confirmFriendOverwrite()
        XCTAssertNil(fake.state.friendConflict)
        XCTAssertEqual("k2", fake.state.friends.first?.publicKey)
    }

    func testRemovingAContactKeepsTheOthers() {
        contacts.addFriend(Friend(setlistfm: "ozzy"))
        contacts.addFriend(Friend(setlistfm: "lemmy"))
        contacts.removeFriend(Friend(setlistfm: "ozzy"))
        XCTAssertEqual(["lemmy"], fake.state.friends.map(\.setlistfm))
    }

    func testSharedConcertsWithAnAccountlessContactAreTheNightsThisPhoneHolds() async {
        let friend = Friend(setlistfm: "", name: "Lemmy", publicKey: id("k"))
        contacts.openSharedConcerts(friend)
        XCTAssertEqual("You & Lemmy", fake.state.setlistsTitle)
        XCTAssertTrue(fake.state.setlistsLoading)
        await settled { !fake.state.setlistsLoading }
        XCTAssertFalse(fake.state.setlistsLoading)
        XCTAssertEqual([], fake.state.setlists.map(\.id))
        XCTAssertEqual(friend.laneKey, fake.state.sharedWith?.laneKey)
    }
}
