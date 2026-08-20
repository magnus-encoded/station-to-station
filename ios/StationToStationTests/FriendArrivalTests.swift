import XCTest
@testable import StationToStation

/// What a handed-over card is allowed to do to a **Contact** list (#188).
///
/// A card arrives from a link any page can open, a QR scan, or a write by any radio in
/// range. The write it used to perform was a replace, so knowing a real contact's
/// username was enough to rewrite the name I see against their **Line**.
///
/// **This is Android's `FriendArrivalTest` ported case for case, in the same order and
/// with the same names.** That list is the specification; a divergence between the two
/// platforms should show up here as a missing test rather than as a field report. Same
/// arrangement as the paired username-validation and contact-challenge suites. Every
/// name is invented.
final class FriendArrivalTests: XCTestCase {

    private let known = [
        Friend(setlistfm: "ozzy", name: "Ozzy", spotifyId: "s-ozzy"),
        Friend(setlistfm: "lemmy", name: "Lemmy"),
    ]

    func testAUsernameNobodyHoldsIsSimplyNew() {
        let card = Friend(setlistfm: "dio", name: "Dio")

        XCTAssertEqual(FriendArrival.new(card), friendArrival(card, known: known))
    }

    func testTheFirstContactOfAllIsNew() {
        let card = Friend(setlistfm: "dio", name: "Dio")

        XCTAssertEqual(FriendArrival.new(card), friendArrival(card, known: []))
    }

    /// Meeting the same person twice is the ordinary case for people who go to gigs
    /// together. A prompt that routinely means nothing is a prompt nobody reads.
    func testTheSameCardAgainIsNeitherAWriteNorAQuestion() {
        let same = Friend(setlistfm: "ozzy", name: "Ozzy", spotifyId: "s-ozzy")

        XCTAssertEqual(FriendArrival.unchanged, friendArrival(same, known: known))
    }

    func testADifferentDisplayNameForSomeoneIHoldAsksFirst() {
        let card = Friend(setlistfm: "ozzy", name: "Ozzy (verified)", spotifyId: "s-ozzy")

        XCTAssertEqual(FriendArrival.conflict(existing: known[0], incoming: card), friendArrival(card, known: known))
    }

    func testADifferentSpotifyIdForSomeoneIHoldAsksFirst() {
        let card = Friend(setlistfm: "ozzy", name: "Ozzy", spotifyId: "s-someone-else")

        XCTAssertEqual(FriendArrival.conflict(existing: known[0], incoming: card), friendArrival(card, known: known))
    }

    /// A card carrying no Spotify id is not a claim that they have none — an add by
    /// username alone would otherwise ask to erase an id it never mentioned.
    func testACardThatIsSilentAboutSpotifyIsNotProposingToClearIt() {
        let card = Friend(setlistfm: "ozzy", name: "Ozzy")

        XCTAssertEqual(FriendArrival.unchanged, friendArrival(card, known: known))
    }

    func testLearningASpotifyIdWeDidNotHaveStillAsks() {
        // It is new information about someone I hold, which is the case that asks.
        let card = Friend(setlistfm: "lemmy", name: "Lemmy", spotifyId: "s-lemmy")

        XCTAssertEqual(FriendArrival.conflict(existing: known[1], incoming: card), friendArrival(card, known: known))
    }

    /// The username is setlist.fm's identity and it is not case sensitive, so matching
    /// has to agree with the list's own de-duplication — otherwise "Ozzy" would arrive
    /// as new and sit beside "ozzy" as a second contact.
    func testTheUsernameMatchesRegardlessOfCase() {
        let card = Friend(setlistfm: "OZZY", name: "Ozzy (verified)")

        XCTAssertEqual(FriendArrival.conflict(existing: known[0], incoming: card), friendArrival(card, known: known))
    }

    func testACaseDifferentCardSayingTheSameThingIsStillUnchanged() {
        let card = Friend(setlistfm: "OZZY", name: "Ozzy", spotifyId: "s-ozzy")

        XCTAssertEqual(FriendArrival.unchanged, friendArrival(card, known: known))
    }

    // --- The first key: a **Followed line** becoming a **Contact** (#188) ---

    func testAFirstKeyForSomeoneIFollowIsAPromotionAndNotAQuestion() {
        let card = Friend(setlistfm: "ozzy", name: "Ozzy", spotifyId: "s-ozzy", publicKey: "k-ozzy")

        XCTAssertEqual(FriendArrival.promotion(card), friendArrival(card, known: known))
    }

    /// The card handed over in person outranks the name a link guessed.
    func testAPromotionCarriesTheCardsDisplayNameWithoutAsking() {
        let card = Friend(setlistfm: "ozzy", name: "Ozzy Osbourne", spotifyId: "s-real", publicKey: "k-ozzy")

        XCTAssertEqual(FriendArrival.promotion(card), friendArrival(card, known: known))
    }

    func testADifferentKeyForAContactIHoldAsksFirst() {
        let keyed = [Friend(setlistfm: "ozzy", name: "Ozzy", publicKey: "k-ozzy")]
        let card = Friend(setlistfm: "ozzy", name: "Ozzy", publicKey: "k-someone-else")

        XCTAssertEqual(FriendArrival.conflict(existing: keyed[0], incoming: card), friendArrival(card, known: keyed))
    }

    /// The key is collected in one moment and there is no second chance to collect it —
    /// only a second **Exchange**. A link, a typed username or a playlist collaborator
    /// must never propose unmaking a **Contact**.
    func testACardWithNoKeyDoesNotProposeClearingAKeyIHold() {
        let keyed = [Friend(setlistfm: "ozzy", name: "Ozzy", publicKey: "k-ozzy")]
        let card = Friend(setlistfm: "ozzy", name: "Ozzy")

        XCTAssertEqual(FriendArrival.unchanged, friendArrival(card, known: keyed))
    }

    func testTheSameKeyAndNothingElseNewIsUnchanged() {
        let keyed = [Friend(setlistfm: "ozzy", name: "Ozzy", spotifyId: "s-ozzy", publicKey: "k-ozzy")]
        let card = Friend(setlistfm: "ozzy", name: "Ozzy", spotifyId: "s-ozzy", publicKey: "k-ozzy")

        XCTAssertEqual(FriendArrival.unchanged, friendArrival(card, known: keyed))
    }

    /// Meeting one person I follow says nothing about the others.
    func testAPromotionTouchesOnlyThePersonItNames() {
        let ozzy = Friend(setlistfm: "ozzy", name: "Ozzy", spotifyId: "s-ozzy", publicKey: "k-ozzy")
        let lemmy = Friend(setlistfm: "lemmy", name: "Lemmy", publicKey: "k-lemmy")

        XCTAssertEqual(FriendArrival.promotion(ozzy), friendArrival(ozzy, known: known))
        XCTAssertEqual(FriendArrival.promotion(lemmy), friendArrival(lemmy, known: known))
    }

    // --- What the model does with each outcome, which is where the doors meet it ---

    /// `AppModel` loads the stored list at init and saves every write, so a model test
    /// has to both start clean and leave clean — otherwise it reads, or hands on, what
    /// another test in the same run left in UserDefaults.
    @MainActor
    private func emptyModel() -> AppModel {
        let model = AppModel()
        clearFriends(model)
        return model
    }

    @MainActor
    private func clearFriends(_ model: AppModel) {
        for friend in model.state.friends { model.removeFriend(friend) }
    }

    /// The four doors all route through `addFriend`, so this is what each of them gets.
    @MainActor
    func testTheModelWritesAPromotionAndAsksAboutAChangedKey() {
        let model = emptyModel()
        model.addFriend(Friend(setlistfm: "ozzy", name: "Ozzy"))
        XCTAssertNil(model.state.friends.first?.publicKey)
        XCTAssertNil(model.state.friendConflict)

        // Met in person: the key arrives, silently, and the card's name comes with it.
        model.addFriend(Friend(setlistfm: "ozzy", name: "Ozzy Osbourne", publicKey: "k-ozzy"))
        XCTAssertNil(model.state.friendConflict)
        XCTAssertEqual("k-ozzy", model.state.friends.first?.publicKey)
        XCTAssertEqual("Ozzy Osbourne", model.state.friends.first?.name)

        // A second key for the same person is the one question, and refusing it is safe.
        model.addFriend(Friend(setlistfm: "ozzy", name: "Ozzy Osbourne", publicKey: "k-someone-else"))
        XCTAssertNotNil(model.state.friendConflict)
        XCTAssertEqual(true, model.state.friendConflict?.keyChanged)
        XCTAssertEqual("k-ozzy", model.state.friends.first?.publicKey)
        model.dismissFriendOverwrite()
        XCTAssertNil(model.state.friendConflict)
        XCTAssertEqual("k-ozzy", model.state.friends.first?.publicKey)

        // Saying yes is the only thing that writes it.
        model.addFriend(Friend(setlistfm: "ozzy", name: "Ozzy Osbourne", publicKey: "k-someone-else"))
        model.confirmFriendOverwrite()
        XCTAssertEqual("k-someone-else", model.state.friends.first?.publicKey)
        clearFriends(model)
    }

    /// A keyless card confirmed over a **Contact** must not take the key with it.
    @MainActor
    func testAConfirmedOverwriteFromAKeylessCardKeepsTheKey() {
        let model = emptyModel()
        model.addFriend(Friend(setlistfm: "ozzy", name: "Ozzy", publicKey: "k-ozzy"))

        model.addFriend(Friend(setlistfm: "ozzy", name: "Ozzy (from a link)"))
        model.confirmFriendOverwrite()

        XCTAssertEqual("Ozzy (from a link)", model.state.friends.first?.name)
        XCTAssertEqual("k-ozzy", model.state.friends.first?.publicKey)
        clearFriends(model)
    }
}
