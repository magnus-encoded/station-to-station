import XCTest
@testable import StationToStation

/// The least trusted string in the app, checked at the door.
///
/// The twin of Android's `SetlistFmUserTest`, and it must stay a twin: a card that
/// crosses platforms should be accepted by both ends or dropped by both. Every name
/// here is invented — this repository is public.
final class SetlistFmUserTests: XCTestCase {

    func testAnOrdinaryUsernameIsAccepted() {
        for ok in ["ozzy", "Lemmy", "dizzi90", "a.b-c_d", "9"] {
            XCTAssertTrue(isPlausibleSetlistFmUser(ok), ok)
        }
    }

    /// The rule excludes URL syntax, not people.
    func testANameInAnotherScriptIsStillAName() {
        for ok in ["Ø", "Ægir", "Пётр", "さくら"] {
            XCTAssertTrue(isPlausibleSetlistFmUser(ok), ok)
        }
    }

    /// The #187 payload, at the door rather than at the socket. Doubly encoded is how
    /// it survives a URL parser, so that is the form to reject.
    func testThePercentThatCarriedTheCrlfIsRefused() {
        XCTAssertFalse(isPlausibleSetlistFmUser("alice%0d%0aX-Evil:%20yes"))
        XCTAssertFalse(isPlausibleSetlistFmUser("alice%0d%0a"))
    }

    func testNothingThatMeansSomethingToAUrlGetsThrough() {
        for bad in ["a/b", "a?b", "a#b", "a&b", "a=b", "a:b", "a@b", "a b", "a\tb", "a\nb", "..%2f"] {
            XCTAssertFalse(isPlausibleSetlistFmUser(bad), bad)
        }
    }

    func testEmptyAndAbsurdLengthsAreRefused() {
        XCTAssertFalse(isPlausibleSetlistFmUser(""))
        XCTAssertTrue(isPlausibleSetlistFmUser(String(repeating: "a", count: 64)))
        XCTAssertFalse(isPlausibleSetlistFmUser(String(repeating: "a", count: 65)))
    }

    /// Not a sanitised contact — none. A card we cannot read is not a meeting.
    func testALinkCarryingAHostileUsernameYieldsNoContactAtAll() {
        let hostile = URL(string: "station-to-station://friend?u=alice%250d%250aX-Evil&name=Alice")!
        XCTAssertNil(friendFromURL(hostile))
    }

    func testAnOrdinaryLinkStillBuildsTheContactItAlwaysDid() {
        let url = URL(string: "station-to-station://friend?u=ozzy&name=Ozzy&sid=sid")!
        let f = friendFromURL(url)
        XCTAssertEqual(f?.setlistfm, "ozzy")
        XCTAssertEqual(f?.name, "Ozzy")
        XCTAssertEqual(f?.spotifyId, "sid")
    }
}
