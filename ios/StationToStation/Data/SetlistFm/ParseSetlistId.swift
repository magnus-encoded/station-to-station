import Foundation

/// A gig id from whatever the user pasted — the setlist.fm page url, or the bare id.
///
/// The Swift twin of Android's `parseSetlistId` (`data/setlistfm/SetlistFmClient.kt`).
///
/// This is how a gig that has not happened gets into the app at all: the API's search
/// index stops about a day out (#29), so a show weeks away cannot be found by artist,
/// venue or date. It can only be asked for by the id sitting in the url of the page the
/// user was on when they decided to go.
///
/// **Only `/setlist/` and `/upcoming/` count.** An artist page
/// (`/setlists/…-23d6a877.html`) and a venue page (`/venue/…-63d41af7.html`) end in
/// *exactly* the same shape, and taking their id would fetch a real gig that is not the
/// one in front of the user — a wrong show is worse than "that link isn't a gig",
/// because nothing about it looks wrong afterwards.
func parseSetlistId(_ input: String) -> String? {
    let s = input.trimmingCharacters(in: .whitespacesAndNewlines)

    // Two steps because `range(of:options:.regularExpression)` gives no capture
    // groups: the first establishes the id sits under /setlist/ or /upcoming/, the
    // second lifts the id back out of what matched.
    if let url = s.range(of: setlistPageURL, options: .regularExpression) {
        let matched = String(s[url])
        if let id = matched.range(of: trailingHexId, options: .regularExpression) {
            return String(matched[id])
        }
    }
    return s.range(of: bareId, options: .regularExpression) != nil ? s : nil
}

private let setlistPageURL = #"setlist\.fm/(?:setlist|upcoming)/\S*?-[0-9a-f]{5,10}\.html"#
private let trailingHexId = #"[0-9a-f]{5,10}(?=\.html)"#
private let bareId = #"^[0-9a-f]{5,10}$"#
