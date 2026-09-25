import XCTest
@testable import StationToStation

/// `search/setlists`, the lookup a **Ticket** makes (#531), over a fake transport: what
/// reaches setlist.fm and what comes back. Invented data; the mirror of Android's
/// `SetlistFmSearchTest`.
final class SetlistFmSearchTests: XCTestCase {

    private final class Recording: @unchecked Sendable {
        private let status: Int
        private let body: String
        private(set) var urls: [URL] = []

        init(_ status: Int, body: String) {
            self.status = status
            self.body = body
        }

        func send(_ url: URL, _ apiKey: String) async throws -> SetlistFmResponse {
            urls.append(url)
            return SetlistFmResponse(status: status, body: Data(body.utf8))
        }
    }

    private func client(_ transport: Recording) -> SetlistFmClient {
        SetlistFmClient(
            keySource: { SetlistFmKey(key: "my-own-key", shared: false) },
            transport: { try await transport.send($0, $1) },
            sleep: { _ in }
        )
    }

    private let twoHits =
        #"{"total":2,"setlist":[{"id":"1a2b3c01","eventDate":"12-10-2024"},{"id":"1a2b3c02"}]}"#

    private func query(_ url: URL?) -> [String: String] {
        let items = url.flatMap { URLComponents(url: $0, resolvingAgainstBaseURL: false) }?.queryItems ?? []
        return Dictionary(items.map { ($0.name, $0.value ?? "") }, uniquingKeysWith: { a, _ in a })
    }

    func testArtistAndDateAreSentAsSetlistFmNamesThemWithNoVenueUnlessAsked() async throws {
        let transport = Recording(200, body: twoHits)
        let resp = try await client(transport).searchSetlists(artistName: "Guns N' Roses", date: "07-07-2023")
        XCTAssertEqual(resp.setlist.map(\.id), ["1a2b3c01", "1a2b3c02"])
        XCTAssertEqual(transport.urls.count, 1)
        XCTAssertEqual(transport.urls.first?.path, "/rest/1.0/search/setlists")
        let q = query(transport.urls.first)
        XCTAssertEqual(q["artistName"], "Guns N' Roses")
        XCTAssertEqual(q["date"], "07-07-2023")
        XCTAssertEqual(q["p"], "1")
        XCTAssertNil(q["venueName"])
    }

    func testAVenueNarrowsTheSearchWhenGiven() async throws {
        let transport = Recording(200, body: twoHits)
        _ = try await client(transport).searchSetlists(
            artistName: "Motorpsycho", date: "12-10-2024", venueName: "Rockefeller Music Hall")
        XCTAssertEqual(query(transport.urls.first)["venueName"], "Rockefeller Music Hall")
    }

    func testASearchThatFindsNothingIsNoHitsNotAnError() async throws {
        let resp = try await client(Recording(404, body: "")).searchSetlists(
            artistName: "Motorpsycho", date: "12-10-2030")
        XCTAssertEqual(resp.total, 0)
        XCTAssertTrue(resp.setlist.isEmpty)
    }
}
