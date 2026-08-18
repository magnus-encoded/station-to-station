import Foundation

/// The manifest envelope, ported from Android's `data/Handover.kt` (#142/#257).
///
/// **Only the envelope.** Android's file also holds `handoverPlan` — the union of one
/// person's whole timeline across their own devices — and that is not here, because
/// nothing on iOS does a device handover yet (#142 is unported). What #265 needs is the
/// wire shape a **Contact** reconcile describes itself with, and the two must not be
/// merged: a handover is a union of everything, a reconcile is the shared band only.
///
/// Field for field with Android's, including the ones iOS never sets, because this is
/// the format two phones agree on rather than a struct one of them finds convenient.
/// `identities` is the one field deliberately absent — it carries the accounts step of
/// #143, which no Contact path touches, and both platforms default it when missing.

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
    /// Declared per category by the source, so a truncated item list is visible rather
    /// than looking like a smaller library. Carried, not yet checked on this platform.
    var counts: [String: Int] = [:]

    init(timeline: TimelineCache = TimelineCache(), media: [OfferedMedia] = [],
         counts: [String: Int] = [:]) {
        self.timeline = timeline
        self.media = media
        self.counts = counts
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        timeline = (try? c.decodeIfPresent(TimelineCache.self, forKey: .timeline)) ?? nil ?? TimelineCache()
        media = (try? c.decodeIfPresent([OfferedMedia].self, forKey: .media)) ?? nil ?? []
        counts = (try? c.decodeIfPresent([String: Int].self, forKey: .counts)) ?? nil ?? [:]
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
