import SwiftUI

/// "Maybe with Mia" — the *maybe*'s tag on a Night of mine (#405). Android's `maybeTagLine`.
func maybeTagLine(_ maybe: MaybeNight) -> String {
    "Maybe with \(maybe.friend.name.isEmpty ? "a Contact" : maybe.friend.name)"
}

/// What their Night says it was: "Mia logged Kvelertak at Rockefeller". Android's
/// `maybeTheirNight`.
func maybeTheirNight(_ maybe: MaybeNight) -> String {
    let who = maybe.friend.name.isEmpty ? "A Contact" : maybe.friend.name
    let what = [maybe.theirs.artist?.name, maybe.theirs.venue?.name]
        .compactMap { $0?.isEmpty == false ? $0 : nil }
        .joined(separator: " at ")
    return what.isEmpty ? "\(who) logged a night on this date" : "\(who) logged \(what)"
}
