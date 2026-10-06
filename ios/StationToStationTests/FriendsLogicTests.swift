import XCTest
@testable import StationToStation

final class FriendsLogicTests: XCTestCase {

    func testStampRoundTrips() {
        let desc = "Setlist at Oslo on 2024-06-01. Created from setlist.fm: https://x \(sfmStamp("magnus90"))"
        XCTAssertEqual("magnus90", sfmUserFromDescription(desc))
    }

    func testFromSetlistFmTextDoesNotFalseMatch() {
        // The human-readable "from setlist.fm" must not be mistaken for the stamp.
        XCTAssertNil(sfmUserFromDescription("Created from setlist.fm: https://www.setlist.fm/x"))
        XCTAssertNil(sfmUserFromDescription("no stamp here"))
        XCTAssertNil(sfmUserFromDescription(nil))
    }

    func testPlaylistIdFromLinkAndUri() {
        XCTAssertEqual("37i9dQZF1DX", spotifyPlaylistId("https://open.spotify.com/playlist/37i9dQZF1DX?si=abc"))
        XCTAssertEqual("37i9dQZF1DX", spotifyPlaylistId("spotify:playlist:37i9dQZF1DX"))
        XCTAssertEqual("AbC0123", spotifyPlaylistId("  https://open.spotify.com/playlist/AbC0123  "))
        XCTAssertNil(spotifyPlaylistId("not a link"))
    }

    func testFriendsEncodeDecodeRoundTrip() {
        let friends = [
            Friend(setlistfm: "magnus90", name: "Magnus", spotifyId: "dizziness"),
            Friend(setlistfm: "alice"),
        ]
        XCTAssertEqual(friends, decodeFriends(encodeFriends(friends)))
        XCTAssertEqual([], decodeFriends(nil))
        XCTAssertEqual([], decodeFriends("garbage-not-json"))
    }

    func testFriendLinkRoundTrips() {
        let friend = Friend(setlistfm: "magnus90", name: "Magnus", spotifyId: "dizzi")
        let parsed = friendFromURL(friend.shareURL)
        XCTAssertEqual(friend, parsed)
        XCTAssertNil(friendFromURL(URL(string: "setlist2spotify://callback?code=x")!))
    }

    @MainActor
    func testZoomedOutRefusesToOpenWithNoFriends() {
        let model = AppModel()
        model.navigation.setZoomedOut(true)
        XCTAssertFalse(model.state.zoomedOut)

        model.addFriend(Friend(setlistfm: "alice"))
        model.navigation.setZoomedOut(true)
        XCTAssertTrue(model.state.zoomedOut)
    }

    // #79: the setlist.fm username must survive exactly, including dots.
    func testFriendLinkSurvivesADottedUsername() {
        let friend = Friend(setlistfm: "magnus.vikan.90", name: "Magnus V.", spotifyId: "dizzi.ness")
        XCTAssertEqual(friend, friendFromURL(friend.shareURL))
    }

    // #79: no display name / Spotify id on the card degrades to the username, not a crash.
    func testFriendLinkDegradesCleanlyWithNoNameOrSpotifyId() {
        let friend = Friend(setlistfm: "alice")
        let parsed = friendFromURL(friend.shareURL)
        XCTAssertEqual(friend, parsed)
        XCTAssertEqual("alice", parsed?.name)
        XCTAssertNil(parsed?.spotifyId)
    }

    /// #271: a link cannot make a **Contact**. Holding a key is what makes one, and a
    /// **Contact** is not addable remotely — so a crafted `k` on the link is dropped and
    /// the **Card** arrives keyless, a **Followed line** and nothing more. iOS already
    /// behaved this way and nothing pinned it, which is how Android drifted; this is the
    /// regression lock, mirroring `FriendLinkTest.aLinkNeverCarriesAKey`.
    func testFriendLinkNeverCarriesAKey() {
        let url = URL(string: "station-to-station://friend?u=dizzi90&name=Magnus&sid=dizziness&k=base64-key")!
        let parsed = friendFromURL(url)
        // Narrow refusal: the key is gone, the rest of the link still arrives.
        XCTAssertEqual(Friend(setlistfm: "dizzi90", name: "Magnus", spotifyId: "dizziness"), parsed)
        XCTAssertNil(parsed?.publicKey)
    }

    // #79: links shared before the station-to-station rename must still resolve.
    func testFriendLinkResolvesOnTheLegacyScheme() {
        let url = URL(string: "setlist2spotify://friend?u=magnus.vikan&name=Magnus&sid=dizzi")!
        XCTAssertEqual(Friend(setlistfm: "magnus.vikan", name: "Magnus", spotifyId: "dizzi"), friendFromURL(url))
    }

    // --- A Contact needs no setlist.fm account (#405) ---

    /// #405 lets a **Card** go without a username — over the radio, where it carries a
    /// key. A link carries no key, so a link with no username is still nobody.
    func testALinkWithNeitherKeyNorUsernameIsStillNobody() {
        XCTAssertNil(friendFromURL(URL(string: "station-to-station://friend?name=Magnus&sid=dizziness")!))
        XCTAssertNil(friendFromURL(URL(string: "station-to-station://friend?u=&name=Magnus")!))
        XCTAssertNil(friendFromURL(URL(string: "station-to-station://friend?name=Magnus&k=base64-key")!))
    }

    /// An account-less Contact's Lane is filed under `key:…`, and no link can name it.
    func testALinkCannotAddressAnAccountlessContactsLane() {
        XCTAssertNil(friendFromURL(URL(string: "station-to-station://friend?u=key:52da85770864f909")!))
    }

    private let dio = Friend(setlistfm: "", name: "Dio", publicKey: "k-dio")

    /// User story 34: everything held before this is found where it was left.
    func testAContactWithAUsernameIsStillFiledUnderIt() {
        XCTAssertEqual("magnus90", Friend(setlistfm: "magnus90", publicKey: "k").laneKey)
        XCTAssertEqual("alice", Friend(setlistfm: "alice").laneKey)
        XCTAssertEqual("alice", Friend(setlistfm: "alice").id)
    }

    func testAnAccountlessContactIsFiledUnderItsKeyAndStoresAndReadsBack() {
        XCTAssertEqual("key:" + keyFingerprint("k-dio"), dio.laneKey)
        XCTAssertEqual([dio], decodeFriends(encodeFriends([dio])))
    }

    func testTwoContactsWithoutAnAccountAreTwoLanes() {
        let other = Friend(setlistfm: "", name: "Dio", publicKey: "k-other")
        let both = withFriend(withFriend([], dio), other)
        XCTAssertEqual(2, both.count)
        XCTAssertEqual(2, Set(both.map(\.laneKey)).count)
    }

    func testWritingAThinnerCardKeepsTheKeyAndTheUsernameHeld() {
        let held = [Friend(setlistfm: "ozzy", name: "Ozzy", publicKey: "k-ozzy")]
        // A link: no key.
        XCTAssertEqual("k-ozzy", withFriend(held, Friend(setlistfm: "ozzy", name: "Oz")).first?.publicKey)
        // The radio, from a phone that stopped saying its username: same key, no username.
        let written = withFriend(held, Friend(setlistfm: "", name: "Ozzy O", publicKey: "k-ozzy"))
        XCTAssertEqual(1, written.count)
        XCTAssertEqual("ozzy", written.first?.setlistfm)
        XCTAssertEqual("Ozzy O", written.first?.name)
    }

    /// The most recently added person's Lane is Lane 1, so a write moves them there.
    func testAWrittenContactGoesLast() {
        let held = [dio, Friend(setlistfm: "alice")]
        XCTAssertEqual(["alice", "Dio"], withFriend(held, dio).map(\.name))
    }

    func testMediaIsAttributedByKeyAndABlankSenderIsNobody() {
        let friends = [dio, Friend(setlistfm: "alice", name: "Alice")]
        XCTAssertEqual("Dio", friends.nameOf("k-dio"))
        XCTAssertEqual("Alice", friends.nameOf("alice"))
        XCTAssertNil(friends.nameOf(""))
    }
}
