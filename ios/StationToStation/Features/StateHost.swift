import Foundation

/// What a feature controller holds instead of `AppModel`: the app's one `UiState`, and the
/// one way a thrown thing reaches the screen.
///
/// Held strongly. `AppModel` lives as long as the app, so the cycle it makes with its
/// controllers costs nothing, and a Task still running after a test's host is gone keeps
/// that host alive instead of crashing on it.
@MainActor
protocol StateHost: AnyObject {
    var state: UiState { get set }
    func fail(_ error: Error)
}
