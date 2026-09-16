import XCTest
@testable import StationToStation

/// What the client returns and throws, over a fake transport (#457).
///
/// Nothing here knows how the retry loop is written — only how many requests reached
/// setlist.fm and what came back out of the client, which is what the rest of the app
/// and the user actually see. Invented data throughout; the repository is public. The
/// mirror of Android's `SetlistFmClientTest`.
final class SetlistFmClientTests: XCTestCase {

    private let shared = SetlistFmKey(key: "bundled-key", shared: true)
    private let own = SetlistFmKey(key: "my-own-key", shared: false)

    private let oneShow = #"{"total":1,"setlist":[{"id":"abc1234"}]}"#

    /// Answers the given statuses in order, and records every request it was given.
    private final class FakeTransport: @unchecked Sendable {
        private let statuses: [Int]
        private let body: String
        private(set) var keysSent: [String] = []
        var requests: Int { keysSent.count }

        init(_ statuses: [Int], body: String) {
            self.statuses = statuses
            self.body = body
        }

        func send(_ url: URL, _ apiKey: String) async throws -> SetlistFmResponse {
            keysSent.append(apiKey)
            let status = requests - 1 < statuses.count ? statuses[requests - 1] : statuses.last!
            let payload = (200...299).contains(status) ? Data(body.utf8) : Data()
            return SetlistFmResponse(status: status, body: payload)
        }
    }

    /// Recorded shared-quota instants, so a test can say what the client wrote down.
    private final class Recorder: @unchecked Sendable {
        private(set) var instants: [TimeInterval] = []
        func record(_ t: TimeInterval) { instants.append(t) }
    }

    /// A client with no clock and no sleeping, so the tests run at full speed.
    private func client(
        _ key: SetlistFmKey?,
        _ transport: FakeTransport,
        spentAt: TimeInterval? = nil,
        now: TimeInterval = 1_000_000,
        recorder: Recorder = Recorder()
    ) -> SetlistFmClient {
        SetlistFmClient(
            keySource: { key },
            sharedQuotaSpentAt: { spentAt },
            recordSharedQuotaSpent: { recorder.record($0) },
            now: { now },
            transport: { try await transport.send($0, $1) },
            sleep: { _ in }
        )
    }

    func testA200IsDecodedAndCostsOneRequest() async throws {
        let transport = FakeTransport([200], body: oneShow)
        let resp = try await client(shared, transport).userAttended("someone")
        XCTAssertEqual(resp.total, 1)
        XCTAssertEqual(transport.requests, 1)
    }

    func testABurstIsRetriedOnceAndCarriesOn() async throws {
        let transport = FakeTransport([429, 200], body: oneShow)
        let resp = try await client(shared, transport).userAttended("someone")
        XCTAssertEqual(resp.total, 1)
        XCTAssertEqual(transport.requests, 2)
    }

    func testTwo429sOnTheSharedKeyIsTheSharedKeyErrorAfterTwoRequests() async {
        let transport = FakeTransport([429, 429], body: oneShow)
        let recorder = Recorder()
        let error = await failing {
            _ = try await self.client(self.shared, transport, now: 555, recorder: recorder)
                .userAttended("someone")
        }
        XCTAssertEqual(error as? SetlistFmRateLimited, SetlistFmRateLimited(sharedKey: true))
        XCTAssertEqual(transport.requests, 2)
        XCTAssertEqual(recorder.instants, [555])
    }

    func testTwo429sOnMyOwnKeyIsTheOwnKeyErrorAndRecordsNothing() async {
        let transport = FakeTransport([429, 429], body: oneShow)
        let recorder = Recorder()
        let error = await failing {
            _ = try await self.client(self.own, transport, recorder: recorder)
                .searchArtists("Whoever")
        }
        XCTAssertEqual(error as? SetlistFmRateLimited, SetlistFmRateLimited(sharedKey: false))
        XCTAssertTrue(recorder.instants.isEmpty)
    }

    func testTheSharedKeyFailsWithoutARequestWhileTheQuotaIsSpent() async {
        let transport = FakeTransport([200], body: oneShow)
        let error = await failing {
            _ = try await self.client(self.shared, transport, spentAt: 1_000_000, now: 1_000_001)
                .userAttended("someone")
        }
        XCTAssertEqual(error as? SetlistFmRateLimited, SetlistFmRateLimited(sharedKey: true))
        XCTAssertEqual(transport.requests, 0)
    }

    func testMyOwnKeyIsSentNormallyWhileTheSharedQuotaIsSpent() async throws {
        let transport = FakeTransport([200], body: oneShow)
        let resp = try await client(own, transport, spentAt: 1_000_000, now: 1_000_001)
            .userAttended("me")
        XCTAssertEqual(resp.total, 1)
        XCTAssertEqual(transport.keysSent, ["my-own-key"])
    }

    func testAfterTheHourTheSharedKeyProbesAndA200LeavesTheMemoryExpired() async throws {
        let transport = FakeTransport([200], body: oneShow)
        let recorder = Recorder()
        let resp = try await client(
            shared,
            transport,
            spentAt: 1_000_000,
            now: 1_000_000 + sharedQuotaMemorySeconds,
            recorder: recorder
        ).userAttended("someone")
        XCTAssertEqual(resp.total, 1)
        XCTAssertEqual(transport.requests, 1)
        XCTAssertTrue(recorder.instants.isEmpty)
    }

    func testAfterTheHourARefusedProbeRecordsANewInstant() async {
        let transport = FakeTransport([429, 429], body: oneShow)
        let recorder = Recorder()
        let later = 1_000_000 + sharedQuotaMemorySeconds
        _ = await failing {
            _ = try await self.client(
                self.shared, transport, spentAt: 1_000_000, now: later, recorder: recorder
            ).userAttended("someone")
        }
        XCTAssertEqual(transport.requests, 2)
        XCTAssertEqual(recorder.instants, [later])
    }

    func test5xxIsStillRiddenOutWithBackoff() async throws {
        let transport = FakeTransport([503, 503, 200], body: oneShow)
        let resp = try await client(shared, transport).userAttended("someone")
        XCTAssertEqual(resp.total, 1)
        XCTAssertEqual(transport.requests, 3)
    }

    func test5xxAllTheWayIsUnavailableNotRateLimited() async {
        let transport = FakeTransport([503], body: oneShow)
        let error = await failing {
            _ = try await self.client(self.shared, transport).userAttended("someone")
        }
        XCTAssertNil(error as? SetlistFmRateLimited)
        XCTAssertEqual(transport.requests, 3)
    }

    func test403StillSaysTheKeyWasRejected() async {
        let transport = FakeTransport([403], body: oneShow)
        let error = await failing {
            _ = try await self.client(self.shared, transport).userAttended("someone")
        }
        XCTAssertNil(error as? SetlistFmRateLimited)
        XCTAssertTrue(userMessage(error!).contains("403"))
        XCTAssertEqual(transport.requests, 1)
    }

    /// An attended list with no shows in it is still an answer, not an error.
    func test404OnAnAttendedListIsStillEmptyNotMissing() async throws {
        let transport = FakeTransport([404], body: oneShow)
        let resp = try await client(shared, transport).userAttended("brand-new")
        XCTAssertEqual(resp.total, 0)
    }

    func test404ElsewhereIsStillNotFound() async {
        let transport = FakeTransport([404], body: oneShow)
        let error = await failing {
            _ = try await self.client(self.shared, transport).searchArtists("Nobody")
        }
        XCTAssertTrue(userMessage(error!).contains("404"))
    }

    func testNoKeyAtAllStillSaysToConfigureOne() async {
        let transport = FakeTransport([200], body: oneShow)
        let error = await failing {
            _ = try await self.client(nil, transport).userAttended("someone")
        }
        XCTAssertNil(error as? SetlistFmRateLimited)
        XCTAssertTrue(userMessage(error!).contains("Settings"))
        XCTAssertEqual(transport.requests, 0)
    }

    func testTheMessagesSayTheRightThingAboutWhoseQuotaItIs() {
        XCTAssertTrue(userMessage(SetlistFmRateLimited(sharedKey: true)).contains("your own"))
        XCTAssertTrue(userMessage(SetlistFmRateLimited(sharedKey: false)).contains("your API key"))
        // Never a time: setlist.fm does not define its day, so any hour named is a guess.
        let clock = try! Regex(#"(?i)\b\d{1,2}(:\d\d)?\s?(am|pm)\b"#)
        XCTAssertNil(userMessage(SetlistFmRateLimited(sharedKey: true)).firstMatch(of: clock))
    }

    /// The thrown thing, or nil if nothing was thrown.
    private func failing(_ body: () async throws -> Void) async -> Error? {
        do {
            try await body()
            return nil
        } catch {
            return error
        }
    }
}
