@testable import StationToStation

/// The `StateHost` a controller test hands in: plain state to arrange and assert on, and
/// every reported error kept instead of turned into words.
@MainActor
final class FakeState: StateHost {
    var state: UiState
    private(set) var failures: [Error] = []

    init(_ state: UiState = UiState()) {
        self.state = state
    }

    func fail(_ error: Error) {
        failures.append(error)
    }
}
