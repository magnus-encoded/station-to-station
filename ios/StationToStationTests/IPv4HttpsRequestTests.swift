import XCTest
@testable import StationToStation

/// The request line, and the username that tried to add a header to it.
///
/// A friend's setlist.fm username arrives from a QR link or from any radio in BLE
/// range and goes straight into a path — so the one thing worth pinning here is that
/// a hostile one cannot end the line it is written on.
final class IPv4HttpsRequestTests: XCTestCase {

    private func line(_ url: URL, headers: [String: String] = [:]) throws -> String {
        String(decoding: try IPv4Https.requestBytes(url: url, host: "api.setlist.fm", headers: headers), as: UTF8.self)
    }

    /// The bug this file exists for. `URL.path` hands back the *decoded* path, so a
    /// username of `alice%0d%0a…` — ordinary text everywhere upstream — became a real
    /// CRLF on the wire and split one request into two, on a connection carrying our
    /// API key.
    func testAnEncodedCrlfInAUsernameStaysEncoded() throws {
        let comps = URLComponents(string: "https://api.setlist.fm/rest/1.0/user/alice%0d%0aX-Evil:%20yes/attended")!
        let out = try line(comps.url!)

        XCTAssertTrue(out.hasPrefix("GET /rest/1.0/user/alice%0d%0aX-Evil:%20yes/attended HTTP/1.1\r\n"),
                      "the path must go on the wire encoded, not decoded: \(out)")
        XCTAssertFalse(out.contains("\r\nX-Evil:"), "a username must not be able to add a header")
    }

    /// One request, so exactly one blank line — the header block ends once.
    func testTheHeaderBlockIsTerminatedExactlyOnce() throws {
        let url = URL(string: "https://api.setlist.fm/rest/1.0/user/alice%0d%0a%0d%0aGET%20/x/attended")!
        let out = try line(url)

        XCTAssertEqual(out.components(separatedBy: "\r\n\r\n").count - 1, 1)
    }

    /// HTTP has no escape for a CRLF inside a header value, and every header this
    /// file sends is one we chose — so a control character in one is a bug, and the
    /// request does not go out.
    func testAHeaderCarryingAControlCharacterIsRefused() {
        let url = URL(string: "https://api.setlist.fm/rest/1.0/search/artists")!
        XCTAssertThrowsError(try line(url, headers: ["x-api-key": "abc\r\nX-Evil: yes"]))
    }

    /// The ordinary case still reads as it did, query and all.
    func testAnOrdinaryRequestIsUnchanged() throws {
        let comps = URLComponents(string: "https://api.setlist.fm/rest/1.0/user/alice/attended?p=1")!
        let out = try line(comps.url!, headers: ["x-api-key": "key"])

        XCTAssertTrue(out.hasPrefix("GET /rest/1.0/user/alice/attended?p=1 HTTP/1.1\r\n"), out)
        XCTAssertTrue(out.contains("Host: api.setlist.fm\r\n"))
        XCTAssertTrue(out.contains("x-api-key: key\r\n"))
        XCTAssertTrue(out.hasSuffix("Connection: close\r\n\r\n"))
    }
}
