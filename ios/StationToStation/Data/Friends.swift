import CryptoKit
import Foundation

/// A person on my timeline: a **Followed line**, a **Contact**, or both.
///
/// A **Followed line** is a setlist.fm username and nothing more — the address its
/// attended list is fetched from — added by a link, a QR code or typing it. A **Contact**
/// is whoever I ran an **Exchange** with, and the key they handed over is the identity
/// (#405): `setlistfm` is an attribute of theirs, blank for someone with no account. Only
/// the radio can make a Friend with a blank username, because only the radio carries a key
/// (see `friendFromURL`). `laneKey` is what their **Lane** is held under either way.
struct Friend: Codable, Identifiable, Hashable {
    /// Their setlist.fm username, or blank for a **Contact** with no account (#405).
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
    /// Present only for the disposable local Contact made by the first-run Tour.
    /// Optional keeps Friends written before the Tour decodable without a migration.
    var demo: Bool?

    init(setlistfm: String, name: String? = nil, spotifyId: String? = nil,
         publicKey: String? = nil, demo: Bool = false) {
        self.setlistfm = setlistfm
        self.name = name?.nilIfBlank ?? setlistfm
        self.spotifyId = spotifyId
        self.publicKey = publicKey
        self.demo = demo ? true : nil
    }

    /// By Lane, not by username: two Contacts without an account share a blank one.
    var id: String { laneKey }

    /// What their **Lane** is held under, in `TimelineCache.shows` and every map keyed by
    /// a Line (#405). The setlist.fm username exactly as it always was, so every Contact
    /// and Lane held before this is found where it was left; `key:` and `keyFingerprint`
    /// for a Contact with no account. The colon keeps the two apart —
    /// `isPlausibleSetlistFmUser` refuses it. Android's `laneKey`, term for term.
    var laneKey: String {
        if !setlistfm.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return setlistfm }
        if let key = publicKey?.nilIfBlank { return "key:" + keyFingerprint(key) }
        return ""
    }

    /// " (@username)", or nothing for a **Contact** with no account — for a line of copy.
    var atUser: String { setlistfm.nilIfBlank.map { " (@\($0))" } ?? "" }

    /// How copy refers to them: "@username", or their name when there is no username.
    var handle: String { setlistfm.nilIfBlank.map { "@\($0)" } ?? name }

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

func withoutDemoFriends(_ friends: [Friend]) -> [Friend] { friends.filter { $0.demo != true } }

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

    var id: String { existing.laneKey }

    /// A changed key is a changed phone, and that is how the question is asked: someone
    /// who bought a handset recognises it immediately, and someone who did not has just
    /// been shown an attack. A *first* key never reaches here — that is a promotion.
    /// What the alert says. A changed key is asked about as a changed phone; anything else
    /// shows both cards, and says the Line is untouched only when it is (#405).
    var message: String {
        if keyChanged {
            return "\(existing.name)\(existing.atUser) seems to be on a different phone than "
                + "last time you saw them. Confirm you still want to share."
        }
        let now: String = "Now: \(existing.name)\(existing.atUser)"
        let card: String = "Card: \(incoming.name)\(incoming.atUser)"
        let head: String = "A card for \(existing.handle) says something different from what you have."
        let tail: String = usernameChanged
            ? ""
            : "\n\nTheir timeline does not change either way — only the name you see against it."
        return head + "\n\n" + now + "\n" + card + tail
    }

    /// A card carrying a different setlist.fm username — or a first one — changes where
    /// their **Line** is read from (#405). One silent about it changes nothing there.
    var usernameChanged: Bool {
        guard let user = incoming.setlistfm.nilIfBlank else { return false }
        return user.lowercased() != existing.setlistfm.lowercased()
    }

    var keyChanged: Bool {
        existing.publicKey?.nilIfBlank != nil && incoming.publicKey?.nilIfBlank != nil
            && existing.publicKey != incoming.publicKey
    }
}

/// A short, stable name for a **Card** key: SHA-256 over the base64 text as it came off
/// the wire, the first 16 hex digits. Over the text rather than the decoded bytes so it
/// cannot fail, and so both twins and both of my own devices (Handover) derive the same
/// name from the same card. Android's `keyFingerprint`, and both suites pin one digest.
func keyFingerprint(_ publicKey: String) -> String {
    let digest = SHA256.hash(data: Data(publicKey.trimmingCharacters(in: .whitespacesAndNewlines).utf8))
    return String(digest.map { String(format: "%02x", $0) }.joined().prefix(16))
}

/// The Friend I already hold who `incoming` is, or nil (#405).
///
/// **The key first**, because it is the identity: a card carrying a key I hold is that
/// person whatever username it carries, or none. Then the setlist.fm username,
/// case-insensitively, which is how a **Followed line** — keyless by construction — is
/// recognised, and how a new phone (a new key) is still asked about rather than added as
/// a stranger. A blank username never matches anything: two people without an account
/// are not one person.
func heldFriend(_ incoming: Friend, known: [Friend]) -> Friend? {
    func trimmed(_ s: String?) -> String? {
        s?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfBlank
    }
    if let key = trimmed(incoming.publicKey),
       let held = known.first(where: { trimmed($0.publicKey) == key }) {
        return held
    }
    guard incoming.setlistfm.nilIfBlank != nil else { return nil }
    return known.first { $0.setlistfm.lowercased() == incoming.setlistfm.lowercased() }
}

/// The list once `incoming` is written: whoever `heldFriend` says it is comes out, and
/// `incoming` goes on the end, where the most recently added person's **Lane** is drawn.
///
/// A key already held is never dropped by a later, thinner way of meeting the same person
/// (#188) — only the radio carries a key, and there is no second moment to collect it. The
/// same goes for a username (#405): a card silent about it keeps the one held, so the Lane
/// stays where it is filed. Android's `withFriend`, term for term.
func withFriend(_ known: [Friend], _ incoming: Friend) -> [Friend] {
    let held = heldFriend(incoming, known: known)
    let written = Friend(
        setlistfm: incoming.setlistfm.nilIfBlank ?? held?.setlistfm ?? "",
        name: incoming.name,
        spotifyId: incoming.spotifyId,
        publicKey: incoming.publicKey?.nilIfBlank ?? held?.publicKey
    )
    return known.filter { $0 != held } + [written]
}

extension Array where Element == Friend {
    /// The name of whoever `from` is — a **Card** key, as media and notes are attributed,
    /// or a `laneKey`. Nil for a blank `from`: a blank is nobody, and matching it would
    /// hand an unattributed item to the first Contact with no account.
    func nameOf(_ from: String) -> String? {
        guard let from = from.nilIfBlank else { return nil }
        let trimmed = from.trimmingCharacters(in: .whitespacesAndNewlines)
        return first {
            $0.publicKey?.trimmingCharacters(in: .whitespacesAndNewlines) == trimmed || $0.laneKey == from
        }?.name
    }
}

/// Matched by `heldFriend`: the key when the card carries one, the username otherwise.
func friendArrival(_ incoming: Friend, known: [Friend]) -> FriendArrival {
    guard let existing = heldFriend(incoming, known: known) else { return .new(incoming) }
    // A first key is a promotion, not a change: nothing is being overwritten, because a
    // **Followed line** held no key to overwrite. Checked before anything else, so a name
    // or Spotify id arriving alongside that first key rides in with it unasked.
    let incomingKey = incoming.publicKey?.nilIfBlank
    if existing.publicKey?.nilIfBlank == nil, incomingKey != nil { return .promotion(incoming) }
    // Only what the card *says* about the person can differ here. A card carrying no
    // Spotify id is not a claim that they have none, so it does not count as a change on
    // its own — and the same goes for a card carrying no key, which must never unmake a
    // **Contact**, and a card carrying no username (#405): matched by key, it is the same
    // person saying less. A username that differs, or one arriving for a Contact held
    // without one, changes where their Lane comes from, and asks.
    let sameName = existing.name == incoming.name
    let sameSpotify = incoming.spotifyId == nil || existing.spotifyId == incoming.spotifyId
    let sameUser = incoming.setlistfm.nilIfBlank == nil
        || existing.setlistfm.lowercased() == incoming.setlistfm.lowercased()
    // A differing key is the change that matters most: it is what a LAN beacon is
    // verified against (#265), so a card silently swapping it is exactly the
    // impersonation case this whole arrival check exists to catch.
    let sameKey = incomingKey == nil || existing.publicKey == incomingKey
    return sameName && sameSpotify && sameKey && sameUser
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
///
/// Untouched by #405: a **Card** can now go without a username, but only over the radio,
/// where it carries a key. A link carries no key, so a link with no username is nobody.
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
