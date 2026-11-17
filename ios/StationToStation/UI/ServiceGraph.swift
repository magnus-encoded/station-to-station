import Foundation

/// The services this app reads from and writes to, as known right now — and from them,
/// which are **Lit** on the Settings **Field** (#563).
///
/// Settings used to be a form of keys and buttons, grouped by vendor. It said nothing
/// about what any of it was *for*. The **Field** draws the same facts as the picture of
/// what feeds **My timeline** and where it goes: inputs on the left, the **Alcoves** on
/// the right, every line amber where that service can do its job now. This is the fold
/// behind the picture. Everything the screen asks — lit or not, what to say, what
/// lighting it would take — is decided here, once, with no device.
///
/// Term for term with Android's `ServiceGraph.kt`, every string word for word, and
/// `fixtures/service-graph/cases.json` holds both platforms to it. No SwiftUI in here.
///
/// The defaults are a fresh install: nothing granted, nobody known, no key of your own.
struct ServicesAsKnown: Equatable {
    /// A setlist.fm key is there to use, bundled or your own. `UiState.setlistFmReady`.
    var setlistFmKeyAvailable = false
    /// The user pasted a key of their own.
    var setlistFmOwnKey = false
    /// The bundled key's shared daily quota ran out. Only means anything while the user
    /// has no key of their own: their own key has its own quota.
    var setlistFmSharedQuotaSpent = false
    var clashfinderUser = ""
    /// A clashfinder private key is saved.
    var clashfinderKey = false
    var spotifyConnected = false
    /// The scopes Spotify granted at login, space-separated. Nil where unknown.
    var spotifyScope: String? = nil
    /// How many **Contacts**' timelines this phone holds.
    var knownTimelines = 0
    var photos: PhotoAccess = .none
    var calendar = false
    var location = false
    /// Checked in at a gig, so **Gossip** is running.
    var gigActive = false
}

/// iOS's "Selected Photos" and Android 14's both let someone share *some* photos, which
/// is still lit. The raw values are the fixture's spelling.
enum PhotoAccess: String {
    case none, partial, full
}

/// Which side of **My timeline** a service stands on: feeding it, or fed by it.
enum ServiceRole {
    case input, alcove
}

/// The strip a tile sits in on the **Field**, by where the service lives.
enum ServiceStrip {
    case databases, thisPhone, otherPhones, services

    var title: String {
        switch self {
        case .databases: return "Databases"
        case .thisPhone: return "This phone"
        case .otherPhones: return "Other phones"
        case .services: return "Services"
        }
    }
}

struct ServiceNode: Equatable, Identifiable {
    /// Stable, shared with Android and the fixtures.
    let id: String
    let name: String
    let role: ServiceRole
    let strip: ServiceStrip
    var lit: Bool
    /// One line on where it stands, said the same way whether it is lit or not.
    let status: String
    /// What it gives you, each phrased as something you can have.
    let unlocks: [String]
    /// The single step that would light it. Nil when it is lit, or nothing would.
    var nextStep: String? = nil
    var experimental = false

    /// What a screen reader says for the tile: the whole state in one element, since the
    /// ring colour that says it on screen is not something VoiceOver or TalkBack can see.
    var spoken: String {
        let said = status.prefix(1).lowercased() + String(status.dropFirst())
        return "\(name), \(lit ? "lit" : "not lit"), \(said)"
    }
}

/// The **Field** as facts: the nodes in drawing order, and which lines are lit.
///
/// Built from a node list rather than only from `ServicesAsKnown` so the line rules can
/// be checked on their own. With today's services MusicBrainz and Ticket PDFs are always
/// lit, so **My timeline** always is too and no `ServicesAsKnown` can show an alcove
/// whose line is dark — but that is a fact about today's services, not the rule, and the
/// rule is what the drawing follows.
struct ServiceGraph: Equatable {
    let nodes: [ServiceNode]

    init(nodes: [ServiceNode]) {
        self.nodes = nodes
    }

    /// **My timeline** is lit when anything at all feeds it.
    var timelineLit: Bool { nodes.contains { $0.role == .input && $0.lit } }

    func node(_ id: String) -> ServiceNode? { nodes.first { $0.id == id } }

    /// The line out to an **Alcove** is lit only when both ends are: a Spotify login
    /// with nothing on the timeline has nothing to make a playlist of.
    func alcoveLineLit(_ id: String) -> Bool {
        guard let node = node(id) else { return false }
        return node.role == .alcove && node.lit && timelineLit
    }
}

func serviceGraph(_ known: ServicesAsKnown) -> ServiceGraph {
    // The shared quota is the bundled key's problem. Someone with their own key never
    // hears about it, even if the flag was set before they pasted one.
    let quotaSpent = known.setlistFmSharedQuotaSpent && !known.setlistFmOwnKey
    let setlistFm = known.setlistFmKeyAvailable && !quotaSpent
    let clashUser = !known.clashfinderUser.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    let clashfinder = clashUser && known.clashfinderKey
    let photos = known.photos != .none
    let contacts = known.knownTimelines >= 1
    let scope = known.spotifyScope ?? ""
    // Logged in is not enough: a login from before playlists were asked for can read
    // but not write, and a playlist is the whole of what Spotify is here for.
    let playlists = known.spotifyConnected && scope.contains("playlist-modify")

    let setlistFmStatus: String
    if quotaSpent {
        setlistFmStatus = "The shared key is spent for today"
    } else if !known.setlistFmKeyAvailable {
        setlistFmStatus = "Needs an API key"
    } else if known.setlistFmOwnKey {
        setlistFmStatus = "Your own key"
    } else {
        setlistFmStatus = "Shared key, bundled with the app"
    }

    let clashfinderStatus: String
    if clashfinder {
        clashfinderStatus = "Signed in as \(known.clashfinderUser)"
    } else if clashUser {
        clashfinderStatus = "Needs your private key as well"
    } else if known.clashfinderKey {
        clashfinderStatus = "Needs your username as well"
    } else {
        clashfinderStatus = "Needs a free account"
    }

    let photosStatus: String
    switch known.photos {
    case .full: photosStatus = "Allowed"
    case .partial: photosStatus = "Allowed for the photos you picked"
    case .none: photosStatus = "Not allowed yet"
    }

    let contactsStatus: String
    switch known.knownTimelines {
    case 0: contactsStatus = "Nobody yet"
    case 1: contactsStatus = "1 known timeline"
    default: contactsStatus = "\(known.knownTimelines) known timelines"
    }

    let spotifyStatus: String
    let spotifyNext: String?
    if !known.spotifyConnected {
        spotifyStatus = "Not logged in. The shared app admits five people"
        spotifyNext = "Log in with Spotify"
    } else if !playlists {
        spotifyStatus = "Logged in, but playlist permission is missing"
        spotifyNext = "Log out and in again"
    } else if !scope.contains("ugc-image-upload") {
        // Logins made before photo covers existed carry every playlist permission but
        // not the one covers need. Still lit: the playlist is the job, the cover is a
        // nicety.
        spotifyStatus = "Playlists yes, photo covers need a fresh login"
        spotifyNext = nil
    } else {
        spotifyStatus = "Logged in"
        spotifyNext = nil
    }

    return ServiceGraph(nodes: [
        ServiceNode(
            id: "setlistfm", name: "setlist.fm",
            role: .input, strip: .databases,
            lit: setlistFm,
            status: setlistFmStatus,
            unlocks: [
                "Your attended concerts",
                "The setlist of any gig",
                "A friend's line, by username",
            ],
            nextStep: setlistFm ? nil : "Paste a free key of your own"
        ),
        ServiceNode(
            id: "musicbrainz", name: "MusicBrainz",
            role: .input, strip: .databases,
            lit: true,
            status: "No account needed",
            unlocks: ["Song titles when a setlist is empty", "Artist names as you type"]
        ),
        ServiceNode(
            id: "clashfinder", name: "clashfinder",
            role: .input, strip: .databases,
            lit: clashfinder,
            status: clashfinderStatus,
            unlocks: ["Festival timetables: stages, set times, clashes"],
            nextStep: clashfinder ? nil : "Register, then paste your private key"
        ),
        ServiceNode(
            id: "photos", name: "Photos",
            role: .input, strip: .thisPhone,
            lit: photos,
            status: photosStatus,
            unlocks: ["Photos from the night on the gig", "A cover for the playlist"],
            nextStep: photos ? nil : "Allow photo access"
        ),
        ServiceNode(
            id: "tickets", name: "Ticket PDFs",
            role: .input, strip: .thisPhone,
            lit: true,
            status: "Share a ticket from your mail or files",
            unlocks: ["A gig added from its ticket", "The ticket's code, on the day"]
        ),
        ServiceNode(
            id: "location", name: "Location",
            role: .input, strip: .thisPhone,
            lit: known.location,
            status: known.location ? "Allowed" : "Not allowed yet",
            unlocks: ["An offer to check in when you're at tonight's gig"],
            nextStep: known.location ? nil : "Allow location access"
        ),
        ServiceNode(
            id: "contacts", name: "Contacts",
            role: .input, strip: .otherPhones,
            lit: contacts,
            status: contactsStatus,
            unlocks: ["Their lines beside yours", "Their photos from nights you shared"],
            nextStep: contacts ? nil : "Swipe left from your timeline to swap cards"
        ),
        // Experimental (#462): lit only for as long as a check-in keeps it running, so
        // most of the time it is dark, and that is its normal state.
        ServiceNode(
            id: "gossip", name: "Gossip",
            role: .input, strip: .otherPhones,
            lit: known.gigActive,
            status: known.gigActive
                ? "Checked in, and gossip is active"
                : "Lights only while you're checked in at a gig",
            unlocks: ["Log lines from phones nearby"],
            nextStep: known.gigActive ? nil : "Check in at tonight's gig",
            experimental: true
        ),
        ServiceNode(
            id: "spotify", name: "Spotify",
            role: .alcove, strip: .services,
            lit: playlists,
            status: spotifyStatus,
            unlocks: ["A playlist of the night", "Your photo as its cover"],
            nextStep: spotifyNext
        ),
        ServiceNode(
            id: "calendar", name: "Calendar",
            role: .alcove, strip: .thisPhone,
            lit: known.calendar,
            status: known.calendar ? "Allowed" : "Not allowed yet",
            unlocks: ["An upcoming gig in your calendar"],
            nextStep: known.calendar ? nil : "Allow calendar access"
        ),
    ])
}
