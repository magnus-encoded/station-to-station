import Foundation

/// The manifest envelope, ported from Android's `data/Handover.kt` (#142/#257).
///
/// The decision made *about* one of these — the union of one person's whole timeline
/// across their own devices — is `HandoverPlan.swift`, and the two must not be confused
/// with a **Contact** reconcile (`ContactReconcile.swift`): a handover is a union of
/// everything between two of my own devices, a reconcile is the shared band between two
/// people.
///
/// Field for field with Android's, including the ones a Contact path never sets, because
/// this is the format two phones agree on rather than a struct one of them finds
/// convenient.

/// A **Gig**'s facts, media, logs and attendance: the timeline itself.
let categorySetlists = "setlists"

/// What the source ticked. Media splits four ways rather than two, because **Personal**
/// and shared media are separate boxes on the source side: sending everything you ever
/// marked personal must be a distinct act from sending your photos.
func categoryOf(kind: String, personal: Bool) -> String {
    personal ? "personal_\(kind)" : kind
}

/// Which setlist.fm user and which Spotify account this is. **Records, not secrets**
/// (#143), so they travel with the records whether or not accounts are being moved — the
/// new phone knowing who it is costs nothing in blast radius.
///
/// There is deliberately no field here for a credential, and there must never be one.
struct Identities: Codable, Equatable {
    var setlistFmUser: String?
    var spotifyAccount: String?

    init(setlistFmUser: String? = nil, spotifyAccount: String? = nil) {
        self.setlistFmUser = setlistFmUser
        self.spotifyAccount = spotifyAccount
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        setlistFmUser = (try? c.decodeIfPresent(String.self, forKey: .setlistFmUser)) ?? nil
        spotifyAccount = (try? c.decodeIfPresent(String.self, forKey: .spotifyAccount)) ?? nil
    }
}

/// One photo, video or note the source is offering.
///
/// `id` is identity — a UUID assigned at **Attach** and carried forever (#97) — and
/// `hash` is resolution: whether the receiver already holds these exact bytes under
/// some other name. Two different jobs, deliberately not conflated.
struct OfferedMedia: Codable, Equatable {
    var id: String = ""
    /// The **Gig** it hangs off, in the *source's* own ids. Translating those to mine
    /// is the receiver's job (see `contactLanding`), never the sender's.
    var gigId: String = ""
    var kind: String = StoredMedia.Kind.photo
    var hash: String = ""
    var bytes: Int64 = 0
    var capturedAt: Int64?
    var personal: Bool = false
    /// Whose camera it came from — the sender's own public key on a Contact manifest,
    /// so attribution survives the transfer. Unattributed media silently becomes the
    /// receiver's, and which were whose is then unrecoverable.
    var from: String?
    /// A **Note**'s text, inline: the one kind that arrives complete with the manifest,
    /// because it has no bytes to fetch in a second phase.
    var text: String = ""
    var verdict: String?

    var category: String { categoryOf(kind: kind, personal: personal) }

    init(id: String = "", gigId: String = "", kind: String = StoredMedia.Kind.photo,
         hash: String = "", bytes: Int64 = 0, capturedAt: Int64? = nil,
         personal: Bool = false, from: String? = nil, text: String = "",
         verdict: String? = nil) {
        self.id = id
        self.gigId = gigId
        self.kind = kind
        self.hash = hash
        self.bytes = bytes
        self.capturedAt = capturedAt
        self.personal = personal
        self.from = from
        self.text = text
        self.verdict = verdict
    }

    /// Lenient in exactly the way `StoredMedia`'s is, and for a sharper reason: this
    /// arrives from another device, possibly another platform, possibly a newer build.
    /// A field this one has never heard of must cost nothing.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = (try? c.decodeIfPresent(String.self, forKey: .id)) ?? nil ?? ""
        gigId = (try? c.decodeIfPresent(String.self, forKey: .gigId)) ?? nil ?? ""
        kind = (try? c.decodeIfPresent(String.self, forKey: .kind)) ?? nil ?? StoredMedia.Kind.photo
        hash = (try? c.decodeIfPresent(String.self, forKey: .hash)) ?? nil ?? ""
        bytes = (try? c.decodeIfPresent(Int64.self, forKey: .bytes)) ?? nil ?? 0
        capturedAt = (try? c.decodeIfPresent(Int64.self, forKey: .capturedAt)) ?? nil
        personal = (try? c.decodeIfPresent(Bool.self, forKey: .personal)) ?? nil ?? false
        from = (try? c.decodeIfPresent(String.self, forKey: .from)) ?? nil
        text = (try? c.decodeIfPresent(String.self, forKey: .text)) ?? nil ?? ""
        verdict = (try? c.decodeIfPresent(String.self, forKey: .verdict)) ?? nil
    }
}

/// What the far end is offering: its timeline, and a description of every item on it.
struct HandoverManifest: Codable {
    var timeline: TimelineCache = TimelineCache()
    var media: [OfferedMedia] = []
    /// Declared per category by the source, sealed with everything else, so a truncated
    /// item list is visible rather than looking like a smaller library.
    var counts: [String: Int] = [:]
    /// Who I am — see `Identities`. Empty on a Contact manifest, which never carries one.
    var identities: Identities = Identities()

    init(timeline: TimelineCache = TimelineCache(), media: [OfferedMedia] = [],
         counts: [String: Int] = [:], identities: Identities = Identities()) {
        self.timeline = timeline
        self.media = media
        self.counts = counts
        self.identities = identities
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        timeline = (try? c.decodeIfPresent(TimelineCache.self, forKey: .timeline)) ?? nil ?? TimelineCache()
        media = (try? c.decodeIfPresent([OfferedMedia].self, forKey: .media)) ?? nil ?? []
        counts = (try? c.decodeIfPresent([String: Int].self, forKey: .counts)) ?? nil ?? [:]
        identities = (try? c.decodeIfPresent(Identities.self, forKey: .identities)) ?? nil ?? Identities()
    }
}

/// A candidate already on this device, from the receiver's own gallery.
///
/// The caller narrows candidates by capture date before hashing any of them, so this is
/// a short list rather than the whole library. That narrowing is a prefilter and nothing
/// more: the match itself is by `hash`, because a timestamp alone would happily grab a
/// neighbouring frame from the same minute.
struct GalleryItem: Equatable {
    let ref: String
    let hash: String
}
