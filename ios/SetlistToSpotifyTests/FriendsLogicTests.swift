import XCTest
@testable import SetlistToSpotify

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
}
