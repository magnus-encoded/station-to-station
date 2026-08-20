import Foundation

/// A friend is just a setlist.fm username (the only thing needed to fetch their
/// attended concerts) plus a display name and, when available, the Spotify id
/// that username maps to. No server: friends are exchanged peer-to-peer as a
/// shareable deep link (see `shareURL`) and stored on-device.
struct Friend: Codable, Identifiable, Hashable {
    let setlistfm: String
    var name: String
    var spotifyId: String?
    /// Their **Contact** identity: base64 X.509 SubjectPublicKeyInfo over an ECDSA
    /// P-256 key, as `ProbeCard.publicKey` carried it at Exchange time (#28).
    ///
    /// Nil is a normal state, not a broken record: a Friend added from a deep link
    /// or added before this field existed has no key, and simply never matches a
    /// LAN peer's challenge (#265). Removing the Friend takes the key with it —
    /// that is the whole of revocation.
    var publicKey: String?

    init(setlistfm: String, name: String? = nil, spotifyId: String? = nil,
         publicKey: String? = nil) {
        self.setlistfm = setlistfm
        self.name = name?.nilIfBlank ?? setlistfm
        self.spotifyId = spotifyId
        self.publicKey = publicKey
    }

    var id: String { setlistfm }

    /// The link a user shares so a friend's app can add them with one tap.
    ///
    /// Never carries the key — see `friendFromURL`. A link can only make a
    /// **Followed line**; the key rides the radio (#271).
    var shareURL: URL {
        var c = URLComponents()
        c.scheme = "station-to-station"
        c.host = "friend"
        c.queryItems = [URLQueryItem(name: "u", value: setlistfm),
                        URLQueryItem(name: "name", value: name)]
        if let spotifyId { c.queryItems?.append(URLQueryItem(name: "sid", value: spotifyId)) }
        return c.url!
    }
}

/// What arriving at my **Contact** list means for a card I have just been handed (#188).
///
/// A card enters this app from four doors: a deep link any page can open, a QR scan, a
/// BLE write from any radio in range, and — between two Androids — a Nearby swap. The
/// write each one performed was a **replace**, so knowing a real contact's username was
/// enough to silently rewrite the name shown against their **Line**.
///
/// The line is drawn where the risk is. Writing into an empty space costs nothing, and a
/// swap that stopped to ask on every first meeting would be ceremony at exactly the
/// moment two people are standing in front of each other. Changing what is already there
/// is the only case a card can make my record say something about someone I already know,
/// so that one asks.
///
/// The Android twin is `FriendArrival` in `data/Friends.kt`, and the test suites are the
/// same list written twice on purpose — a divergence should show up as a missing test
/// rather than as a field report.
enum FriendArrival: Equatable {
    /// Nobody by that username yet. Write it, say nothing.
    case new(Friend)

    /// Already held **without a key**, and the card brings one. **This is the Exchange.**
    ///
    /// The moment a **Followed line** becomes a **Contact**: the person was already on
    /// screen — from a link, a QR scan, a typed username — and standing next to them is
    /// what adds the key. Nothing is overwritten, because a **Followed line** grants
    /// nothing and there was no trust there to overwrite. A distinct outcome rather than
    /// a special case of `new`, because holding a key is what *makes* a **Contact**: it
    /// is a change of kind, not a change of field.
    ///
    /// The card is taken **whole**, not merged: presented in person it outranks anything
    /// a link guessed. So a promotion never asks about the name — which is the false
    /// positive this case exists to remove.
    case promotion(Friend)

    /// Already held, and the card says the same thing. **Not a write and not a prompt.**
    ///
    /// Meeting the same person twice is the ordinary case for people who go to gigs
    /// together, and a prompt that routinely means nothing is a prompt nobody reads.
    case unchanged

    /// Already held, and the card differs. The one case that asks.
    case conflict(existing: Friend, incoming: Friend)
}

/// The standing question, held in view state and never persisted. `Identifiable` so an
/// alert can be driven straight off it.
struct FriendConflict: Identifiable, Equatable {
    let existing: Friend
    let incoming: Friend

    var id: String { existing.setlistfm }

    /// A changed key is a changed phone, and that is how the question is asked: someone
    /// who bought a handset recognises it immediately, and someone who did not has just
    /// been shown an attack. A *first* key never reaches here — that is a promotion.
    var keyChanged: Bool {
        existing.publicKey?.nilIfBlank != nil && incoming.publicKey?.nilIfBlank != nil
            && existing.publicKey != incoming.publicKey
    }
}

/// Matched on the setlist.fm username, case-insensitively — the same key the list has
/// always de-duplicated on, because it is the identity setlist.fm itself uses.
func friendArrival(_ incoming: Friend, known: [Friend]) -> FriendArrival {
    guard let existing = known.first(where: {
        $0.setlistfm.lowercased() == incoming.setlistfm.lowercased()
    }) else { return .new(incoming) }
    // A first key is a promotion, not a change: nothing is being overwritten, because a
    // **Followed line** held no key to overwrite. Checked before anything else, so a name
    // or Spotify id arriving alongside that first key rides in with it unasked.
    let incomingKey = incoming.publicKey?.nilIfBlank
    if existing.publicKey?.nilIfBlank == nil, incomingKey != nil { return .promotion(incoming) }
    // The username is the identity and cannot differ here; only what the card *says*
    // about that identity can. A card carrying no Spotify id is not a claim that they
    // have none, so it does not count as a change on its own — and the same goes for a
    // card carrying no key, which must never unmake a **Contact**.
    let sameName = existing.name == incoming.name
    let sameSpotify = incoming.spotifyId == nil || existing.spotifyId == incoming.spotifyId
    // A differing key is the change that matters most: it is what a LAN beacon is
    // verified against (#265), so a card silently swapping it is exactly the
    // impersonation case this whole arrival check exists to catch.
    let sameKey = incomingKey == nil || existing.publicKey == incomingKey
    return sameName && sameSpotify && sameKey
        ? .unchanged
        : .conflict(existing: existing, incoming: incoming)
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

/// The link that invites someone to a gig I'm going to.
///
/// Same deep-link mechanism as the friend card, a different authority: the setlist.fm
/// id is all a second device needs, because it fetches the rest. The Android twin is
/// `gigInviteUri` in `data/Friends.kt`, and the two have to agree exactly — an invite
/// is the one thing in this app that is *made* on one platform and *read* on the other.
func gigInviteURL(setlistId: String) -> URL {
    var c = URLComponents()
    c.scheme = "station-to-station"
    c.host = "gig"
    c.queryItems = [URLQueryItem(name: "id", value: setlistId)]
    return c.url!
}

/// The setlist.fm id out of a `station-to-station://gig?id=…` invite, or nil.
func gigIdFromInvite(_ url: URL) -> String? {
    guard let c = URLComponents(url: url, resolvingAgainstBaseURL: false), c.host == "gig"
    else { return nil }
    return c.queryItems?.first { $0.name == "id" }?
        .value?.trimmingCharacters(in: .whitespaces).nilIfBlank
}

/// Parses a `station-to-station://friend?...` link. Nil if it isn't one / has no username.
func friendFromURL(_ url: URL) -> Friend? {
    guard let c = URLComponents(url: url, resolvingAgainstBaseURL: false), c.host == "friend"
    else { return nil }
    func param(_ n: String) -> String? {
        c.queryItems?.first { $0.name == n }?.value?.trimmingCharacters(in: .whitespaces).nilIfBlank
    }
    guard let user = param("u"), isPlausibleSetlistFmUser(user) else { return nil }
    // No key is read, deliberately (#271): holding one is what makes a **Contact**, and a
    // **Contact** is not addable remotely — the authentication is that two people stood
    // together. A link arrives from any web page, chat message or installed app, so a `k`
    // parameter would mint a **Contact** at a distance and let it **Reconcile** over LAN
    // (#257) for media of mine. A link makes a **Followed line**; promotion is #188's
    // arrival case, over the radio, in person. Do not add it back as a convenience.
    return Friend(setlistfm: user, name: param("name"), spotifyId: param("sid"))
}

/// Letters, digits, dot, hyphen, underscore — nothing that means something to a URL.
///
/// A username is the least trusted string this app holds: it arrives from a link any
/// app can open, or from any radio in range, and it ends up in a **path segment**
/// against setlist.fm carrying our API key. #187 is what that costs when it is not
/// checked — a percent-encoded CRLF rode the path into the request line and split one
/// request into two. That fix encodes the path, which is the right root fix; this is
/// the other half, refusing the value at the door so it never travels at all.
///
/// An allow-list, because the interesting characters here are the ones nobody thought
/// of. Unicode letters and digits rather than ASCII, so a name in a non-Latin script
/// is still a name — the point is to exclude URL and protocol syntax, not foreigners.
///
/// Deliberately conservative, and it is worth saying what that costs: setlist.fm's own
/// rule is not published anywhere we can read, so this is a guess at the shape of a
/// username rather than a copy of their policy. If a real account is ever rejected,
/// widen this — but widen it to a character, not to "anything non-blank".
///
/// The Android twin is `isPlausibleSetlistFmUser` in `Friends.kt`; the two must agree,
/// or a card that crosses platforms is accepted by one end and dropped by the other.
func isPlausibleSetlistFmUser(_ user: String) -> Bool {
    guard !user.isEmpty, user.count <= 64 else { return false }
    return user.unicodeScalars.allSatisfy {
        CharacterSet.letters.contains($0) || CharacterSet.decimalDigits.contains($0)
            || $0 == "." || $0 == "-" || $0 == "_"
    }
}

extension String {
    /// Nil when blank/whitespace, so `?? fallback` mirrors Kotlin's `ifBlank { null }`.
    var nilIfBlank: String? {
        trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : self
    }
}
