import Foundation

/// A friend is just a setlist.fm username (the only thing needed to fetch their
/// attended concerts) plus a display name and, when available, the Spotify id
/// that username maps to. No server: friends are exchanged peer-to-peer as a
/// shareable deep link (see `shareURL`) and stored on-device.
struct Friend: Codable, Identifiable, Hashable {
    let setlistfm: String
    var name: String
    var spotifyId: String?

    init(setlistfm: String, name: String? = nil, spotifyId: String? = nil) {
        self.setlistfm = setlistfm
        self.name = name?.nilIfBlank ?? setlistfm
        self.spotifyId = spotifyId
    }

    var id: String { setlistfm }

    /// The link a user shares so a friend's app can add them with one tap.
    var shareURL: URL {
        var c = URLComponents()
        c.scheme = "setlist2spotify"
        c.host = "friend"
        c.queryItems = [URLQueryItem(name: "u", value: setlistfm),
                        URLQueryItem(name: "name", value: name)]
        if let spotifyId { c.queryItems?.append(URLQueryItem(name: "sid", value: spotifyId)) }
        return c.url!
    }
}

func encodeFriends(_ friends: [Friend]) -> String {
    guard let data = try? JSONEncoder().encode(friends),
          let json = String(data: data, encoding: .utf8) else { return "[]" }
    return json
}

func decodeFriends(_ stored: String?) -> [Friend] {
    guard let data = stored?.nilIfBlank?.data(using: .utf8) else { return [] }
    return (try? JSONDecoder().decode([Friend].self, from: data)) ?? []
}

// --- Playlist-as-card discovery ---
//
// A converted playlist's description carries the creator's setlist.fm username in
// a machine-parseable stamp. When a friend shares such a playlist, reading its
// description hands us their spotify->setlist.fm mapping with no server involved.

/// The stamp appended to a playlist description so a friend's app can find the creator.
func sfmStamp(_ username: String) -> String {
    "[sfm:\(username.trimmingCharacters(in: .whitespacesAndNewlines))]"
}

private let stampRegex = try! Regex(#"\[sfm:([^\]\s]+)\]"#)
private let playlistIdRegex = try! Regex(#"playlist[:/]([A-Za-z0-9]+)"#)

/// Extracts the creator's setlist.fm username from a playlist description, if stamped.
func sfmUserFromDescription(_ description: String?) -> String? {
    guard let description, let m = description.firstMatch(of: stampRegex),
          let group = m.output[1].substring else { return nil }
    return String(group).nilIfBlank
}

/// Pulls the playlist id out of a Spotify link or URI (open.spotify.com/... or spotify:playlist:...).
func spotifyPlaylistId(_ input: String) -> String? {
    guard let m = input.trimmingCharacters(in: .whitespacesAndNewlines).firstMatch(of: playlistIdRegex),
          let group = m.output[1].substring else { return nil }
    return String(group)
}

/// Parses a `setlist2spotify://friend?...` link. Nil if it isn't one / has no username.
func friendFromURL(_ url: URL) -> Friend? {
    guard let c = URLComponents(url: url, resolvingAgainstBaseURL: false), c.host == "friend"
    else { return nil }
    func param(_ n: String) -> String? {
        c.queryItems?.first { $0.name == n }?.value?.trimmingCharacters(in: .whitespaces).nilIfBlank
    }
    guard let user = param("u") else { return nil }
    return Friend(setlistfm: user, name: param("name"), spotifyId: param("sid"))
}

extension String {
    /// Nil when blank/whitespace, so `?? fallback` mirrors Kotlin's `ifBlank { null }`.
    var nilIfBlank: String? {
        trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : self
    }
}
