import Foundation

/// A user-facing error message (the iOS analog of Android's IOException(message)).
struct AppError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

/// Spotify returned 403. Kept distinct so playlist writes can attach diagnostics,
/// matching the Android SpotifyForbiddenException.
struct SpotifyForbidden: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

/// The message to surface for any thrown error.
func userMessage(_ error: Error) -> String {
    (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
}
