import SwiftUI
import UIKit

// Spoken status for VoiceOver (#164): a lookup starting or finishing, an error
// appearing in place, an Exchange landing, a transfer reaching its next phase. The
// twin of Android's `spokenOnChange()` live regions. Nothing here changes what is drawn.
//
// Only for real status changes. Anything that changes on every keystroke or every item
// would talk over the person, which is worse than saying nothing.

/// Says `message` through VoiceOver without moving focus. Silent when VoiceOver is off.
@MainActor
func announce(_ message: String) {
    guard !message.isEmpty, UIAccessibility.isVoiceOverRunning else { return }
    UIAccessibility.post(notification: .announcement, argument: message)
}

extension View {
    /// Says `status` whenever it changes to something new; `nil` stays quiet.
    func spokenOnChange(_ status: String?) -> some View {
        onChange(of: status) { new in
            if let new { Task { @MainActor in announce(new) } }
        }
    }

    /// Says `message` once, when this view appears: a status that shows up in place of
    /// what was there, such as a spinner with its words.
    func spokenOnAppear(_ message: String) -> some View {
        onAppear { Task { @MainActor in announce(message) } }
    }
}
