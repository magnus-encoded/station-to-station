import Foundation

/// What a feature controller holds instead of `AppModel`: the app's one `UiState`, and the
/// one way a thrown thing reaches the screen.
///
/// Held `unowned`: `AppModel` owns every controller, so a controller never outlives its host.
@MainActor
protocol StateHost: AnyObject {
    var state: UiState { get set }
    func fail(_ error: Error)
}
