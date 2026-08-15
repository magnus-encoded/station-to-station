import XCTest
@testable import StationToStation

/// The loop around the parse: paging, the cap, and what the request actually carries.
///
/// #211 shipped this path untested and said so. It is the half that cannot be checked
/// by handing a string to a pure function — an artist with a long career exceeds one
/// page, and a truncated catalogue silently lacks the one title someone is looking
/// for, which is a failure that looks exactly like "MusicBrainz doesn't have it".
///
/// Driven through a stubbed `URLProtocol` rather than a socket, so it is a unit test
/// that happens to exercise `URLSession` rather than a network test.
final class MusicBrainzPagingTests: XCTestCase {

    override func setUp() {
        super.setUp()
        StubProtocol.bodies = []
        StubProtocol.requests = []
        // Reset here rather than only at the end of the one test that changes it: a
        // failed assertion skips the cleanup, and a leaked 503 would fail every test
        // that ran after it with an unrelated message.
        StubProtocol.status = 200
    }

    private func client() -> MusicBrainzClient {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        return MusicBrainzClient(session: URLSession(configuration: config))
    }

    private func page(count: Int, _ titles: [String]) -> String {
        let recordings = titles.map { #"{"title":"\#($0)"}"# }.joined(separator: ",")
        return #"{"recording-count":\#(count),"recordings":[\#(recordings)]}"#
    }

    /// Two pages become one catalogue, in order, with no repetition at the seam.
    func testItFollowsThePagesUntilTheCountIsReached() async {
        StubProtocol.bodies = [
            page(count: 3, ["Toothpicks and Gum", "High and Apple Sweet"]),
            page(count: 3, ["Between Stations"]),
        ]

        let titles = await client().catalogue(mbid: "mb-1")

        XCTAssertEqual(["Toothpicks and Gum", "High and Apple Sweet", "Between Stations"], titles)
        XCTAssertEqual(2, StubProtocol.requests.count)
    }

    /// The second request asks for what the first did not return. Getting this wrong
    /// re-fetches page one forever, which dedupe would quietly hide.
    func testTheSecondRequestAsksFromWhereTheFirstStopped() async {
        StubProtocol.bodies = [
            page(count: 3, ["Toothpicks and Gum", "High and Apple Sweet"]),
            page(count: 3, ["Between Stations"]),
        ]

        _ = await client().catalogue(mbid: "mb-1")

        let offsets = StubProtocol.requests.compactMap { req -> String? in
            URLComponents(url: req.url!, resolvingAgainstBaseURL: false)?
                .queryItems?.first { $0.name == "offset" }?.value
        }
        XCTAssertEqual(["0", "2"], offsets)
    }

    /// MusicBrainz blocks a blank or generic User-Agent, so this is the difference
    /// between a catalogue and silence.
    func testEveryRequestNamesTheApplication() async {
        StubProtocol.bodies = [page(count: 1, ["Between Stations"])]

        _ = await client().catalogue(mbid: "mb-1")

        XCTAssertEqual(
            MusicBrainzClient.userAgent,
            StubProtocol.requests.first?.value(forHTTPHeaderField: "User-Agent")
        )
        XCTAssertEqual("mb-1", StubProtocol.requests.first.flatMap {
            URLComponents(url: $0.url!, resolvingAgainstBaseURL: false)?
                .queryItems?.first { $0.name == "artist" }?.value
        })
    }

    /// A page that says there is more but returns nothing stops the loop rather than
    /// spinning on it — the count is the source's claim, the rows are the fact.
    func testAnEmptyPageEndsTheWalkEvenIfTheCountDisagrees() async {
        StubProtocol.bodies = [
            page(count: 900, ["Between Stations"]),
            page(count: 900, []),
        ]

        let titles = await client().catalogue(mbid: "mb-1")

        XCTAssertEqual(["Between Stations"], titles)
        XCTAssertEqual(2, StubProtocol.requests.count)
    }

    /// The cap is honoured, and it is applied to songs rather than to recordings.
    func testTheCapLimitsWhatComesBack() async {
        StubProtocol.bodies = [page(count: 3, ["One", "Two", "Three"])]

        // Bound to a local first: XCTAssert's arguments are autoclosures, which cannot
        // carry an await.
        let titles = await client().catalogue(mbid: "mb-1", cap: 2)

        XCTAssertEqual(["One", "Two"], titles)
    }

    /// A blank mbid asks nothing at all. Nobody is served by a request we know is
    /// meaningless, least of all a source that rate-limits.
    func testABlankMbidNeverReachesTheNetwork() async {
        let titles = await client().catalogue(mbid: "  ")

        XCTAssertEqual([], titles)
        XCTAssertTrue(StubProtocol.requests.isEmpty)
    }

    /// A failure is an empty catalogue, never a throw: a source going quiet costs the
    /// enrichment and never the night (ADR-0004).
    func testAServerErrorIsAnEmptyCatalogue() async {
        StubProtocol.status = 503
        StubProtocol.bodies = [page(count: 1, ["Between Stations"])]

        let titles = await client().catalogue(mbid: "mb-1")

        XCTAssertEqual([], titles)
    }
}

/// Answers each request with the next queued body, and records what was asked.
final class StubProtocol: URLProtocol {
    static var bodies: [String] = []
    static var requests: [URLRequest] = []
    static var status = 200

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.requests.append(request)
        let body = Self.bodies.isEmpty ? "{}" : Self.bodies.removeFirst()
        let response = HTTPURLResponse(url: request.url!, statusCode: Self.status,
                                       httpVersion: nil, headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
