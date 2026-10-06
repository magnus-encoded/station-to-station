import XCTest
@testable import StationToStation

@MainActor
final class SettingsControllerTests: XCTestCase {

    private struct Unreachable: Error {}

    private final class FakeLogin: SpotifyLogin {
        let failure: Error?
        init(failingWith failure: Error? = nil) { self.failure = failure }
        func login() async throws {
            if let failure { throw failure }
        }
    }

    func testALoginThatSucceedsConnectsSpotifyWithTheGrantedScope() async {
        let fake = FakeState()
        let settings = Settings()
        let controller = SettingsController(host: fake, settings: settings, spotify: FakeLogin())

        await controller.loginSpotify().value

        XCTAssertTrue(fake.state.spotifyConnected)
        XCTAssertEqual(fake.state.grantedScope, settings.grantedScope)
        XCTAssertTrue(fake.failures.isEmpty)
    }

    func testALoginThatFailsIsReportedAndLeavesSpotifyDisconnected() async {
        var state = UiState()
        state.grantedScope = "before"
        let fake = FakeState(state)
        let controller = SettingsController(
            host: fake, settings: Settings(), spotify: FakeLogin(failingWith: Unreachable()))

        await controller.loginSpotify().value

        XCTAssertFalse(fake.state.spotifyConnected)
        XCTAssertEqual(fake.state.grantedScope, "before")
        XCTAssertEqual(fake.failures.count, 1)
        XCTAssertTrue(fake.failures.first is Unreachable)
    }
}
