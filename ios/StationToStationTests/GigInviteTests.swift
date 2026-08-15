import XCTest
@testable import StationToStation

/// The gig invite, both ends (#179).
///
/// This link is the one thing in the app that is *made* on one platform and *read* on
/// the other, so the shape is a contract rather than an internal detail. The Android
/// twin is `GigLinkTest`. Every id here is invented.
final class GigInviteTests: XCTestCase {

    func testTheInviteIsTheShapeAndroidWrites() {
        XCTAssertEqual(
            "station-to-station://gig?id=abc123",
            gigInviteURL(setlistId: "abc123").absoluteString
        )
    }

    func testAnInviteRoundTripsThroughItsOwnLink() {
        XCTAssertEqual("abc123", gigIdFromInvite(gigInviteURL(setlistId: "abc123")))
    }

    /// The authority is what tells an invite from a friend card and from a timeline
    /// place, all of which ride the same scheme.
    func testOnlyTheGigAuthorityIsAnInvite() {
        XCTAssertNil(gigIdFromInvite(URL(string: "station-to-station://friend?u=ozzy&id=abc")!))
        XCTAssertNil(gigIdFromInvite(URL(string: "station-to-station://me")!))
        XCTAssertNil(gigIdFromInvite(URL(string: "station-to-station://gig")!))
    }

    func testABlankOrMissingIdIsNoInvite() {
        XCTAssertNil(gigIdFromInvite(URL(string: "station-to-station://gig?id=")!))
        XCTAssertNil(gigIdFromInvite(URL(string: "station-to-station://gig?id=%20%20")!))
        XCTAssertNil(gigIdFromInvite(URL(string: "station-to-station://gig?other=abc")!))
    }

    /// Trimmed the same way the Android side trims, so a link that survived a paste
    /// into a chat window still opens.
    func testSurroundingWhitespaceIsTrimmed() {
        XCTAssertEqual("abc123", gigIdFromInvite(URL(string: "station-to-station://gig?id=%20abc123%20")!))
    }

    /// An id needing encoding survives being written and read back — the builder and
    /// the parser have to agree about escaping, not only about the happy path. (Real
    /// setlist.fm ids are hex, so this is about the two halves agreeing rather than
    /// about a case anyone will meet.)
    func testAnIdNeedingEncodingSurvivesTheRoundTrip() {
        let awkward = "a b/c"

        XCTAssertEqual(awkward, gigIdFromInvite(gigInviteURL(setlistId: awkward)))
    }
}
