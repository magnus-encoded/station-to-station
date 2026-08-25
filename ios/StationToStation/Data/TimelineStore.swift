import CryptoKit
import Foundation

/// Every timeline on the device, in one file — the same `timelines.json` the
/// Android build writes, field for field.
///
/// Only the *facts* are stored: the shows themselves, keyed by setlist.fm
/// username, plus the festival names that cost a fetch each. The spine's shape
/// (what clusters into a festival, what merges with a friend's node) is derived
/// at render time by `groupIntoFestivals`/`weaveTimelines`, so changing those
/// rules never needs a migration.
///
/// ponytail: one file, not one per user — no filename escaping, one read at
/// launch. Split per user if a collection ever gets big enough to stutter.

/// A playlist this app made from a night. Kept so the night can point at it
/// later — Spotify has no way to ask "which playlist came from this setlist".
struct StoredPlaylist: Codable, Equatable {
    var url: String
    var name: String = ""
    var trackCount: Int = 0

    init(url: String, name: String = "", trackCount: Int = 0) {
        self.url = url
        self.name = name
        self.trackCount = trackCount
    }

    // Written by hand rather than synthesized so a missing key falls back to the
    // default instead of throwing — kotlinx does that, and one absent field must
    // not take the whole cache down with it.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        url = (try? c.decodeIfPresent(String.self, forKey: .url)) ?? nil ?? ""
        name = (try? c.decodeIfPresent(String.self, forKey: .name)) ?? nil ?? ""
        trackCount = (try? c.decodeIfPresent(Int.self, forKey: .trackCount)) ?? nil ?? 0
    }
}

/// My relationship to one gig: how sure the app is I was there, and — for a live
/// check-in — when. Android-only so far (#29); carried so a save from here does
/// not erase it.
///
/// `provenance` is a plain string, not an enum, for the same reason it is one in
/// Kotlin: an unknown value written by a newer build should cost this one gig at
/// worst, never the whole cache.
struct StoredAttendance: Codable, Equatable {
    var provenance: String = "planned"
    var checkedInAt: Int64?
    var venueLat: Double?
    var venueLon: Double?

    init(provenance: String = "planned", checkedInAt: Int64? = nil,
         venueLat: Double? = nil, venueLon: Double? = nil) {
        self.provenance = provenance
        self.checkedInAt = checkedInAt
        self.venueLat = venueLat
        self.venueLon = venueLon
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        provenance = (try? c.decodeIfPresent(String.self, forKey: .provenance)) ?? nil ?? "planned"
        checkedInAt = (try? c.decodeIfPresent(Int64.self, forKey: .checkedInAt)) ?? nil
        venueLat = (try? c.decodeIfPresent(Double.self, forKey: .venueLat)) ?? nil
        venueLon = (try? c.decodeIfPresent(Double.self, forKey: .venueLon)) ?? nil
    }
}

/// One night, as *this app* knows it — the identity everything else hangs off
/// (#107). Field for field with Android's `StoredGig`.
///
/// The setlist.fm id is an attribute here, not the key. #28 made the same change
/// for people ("the public key is the identity; setlistfm becomes a nullable
/// attribute") and named the same gap for events; this is that half. A night from
/// a poster in a window has no vendor id and may never get one, and once media
/// (#97) hangs off a gig the data is irreplaceable, so a key that can change — or
/// that a night can fail to have — is a key that can orphan a keepsake.
///
/// `setlistId` is still the correspondence key *between people*: two devices
/// assign different local ids to the same night, so **Crossings** and anything
/// cross-person resolve through it. A local-only Gig is local-only by design.
///
/// `createdAt` exists for one rule: two local gigs found to be the same night
/// merge, and the older id wins. Migrated gigs carry 0 — they predate everything
/// minted since, and every device agrees on that without a clock.
struct StoredGig: Codable, Equatable {
    var id: String = ""
    /// dd-MM-yyyy, the shape setlist.fm sends. Blank until the facts are known.
    var date: String = ""
    var artist: String = ""
    var venue: String = ""
    /// Nil for a night setlist.fm has never heard of. Set once, by adoption (#34).
    var setlistId: String?
    /// Epoch millis. 0 means "came in with the migration".
    var createdAt: Int64 = 0

    init(id: String = "", date: String = "", artist: String = "", venue: String = "",
         setlistId: String? = nil, createdAt: Int64 = 0) {
        self.id = id
        self.date = date
        self.artist = artist
        self.venue = venue
        self.setlistId = setlistId
        self.createdAt = createdAt
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = (try? c.decodeIfPresent(String.self, forKey: .id)) ?? nil ?? ""
        date = (try? c.decodeIfPresent(String.self, forKey: .date)) ?? nil ?? ""
        artist = (try? c.decodeIfPresent(String.self, forKey: .artist)) ?? nil ?? ""
        venue = (try? c.decodeIfPresent(String.self, forKey: .venue)) ?? nil ?? ""
        setlistId = (try? c.decodeIfPresent(String.self, forKey: .setlistId)) ?? nil
        createdAt = (try? c.decodeIfPresent(Int64.self, forKey: .createdAt)) ?? nil ?? 0
    }
}

/// One photo or video on a night (#97). Field for field with Android's
/// `StoredMedia`.
///
/// Before this, **Attach** stored a raw gallery reference and copied nothing, so
/// the app owned no bytes: tidying the gallery, reinstalling, or granting access
/// to only some photos each emptied a night with nothing deleted. A list of
/// strings also had nowhere to put a capture time, a **Pointer**, the **Personal**
/// bit, provenance, or a stable id.
///
/// `id` is assigned by the owner at **Attach** and carried forever: it names the
/// thumbnail files #98 writes, and it is what makes any future sync idempotent.
/// A UUID and not a content hash — hashing full-res means reading a 233 MB
/// recording at attach time, for a dedup that only applies to the same bytes
/// attached twice.
///
/// `kind` is *stored*, not sniffed at read time: asking for a MIME type works
/// right up until the reference dies, which is the entire premise of this record.
struct StoredMedia: Codable, Equatable {
    enum Kind {
        static let photo = "photo"
        static let video = "video"
        /// A **Note**: text, and no bytes at all. `ref` is empty and `pointer` is
        /// nil, which is why every path that resolves a reference has to skip it.
        static let note = "note"
        /// The reference was already dead when we looked. Not a guess.
        static let unknown = "unknown"
    }

    /// A Verdict on the night, carried by the Note it was written on. Down,
    /// up, double-up, or nil for unset — see `verdict` below.
    enum Verdict {
        static let down = "down"
        static let up = "up"
        static let doubleUp = "double_up"
    }

    var id: String = ""
    /// `Kind`. A plain string, not an enum, for the reason `provenance` is one.
    var kind: String = Kind.photo
    /// The local reference: a content URI on Android, an asset id on iOS.
    var ref: String = ""
    /// When the camera took it — not when it was attached. Nil when unknowable.
    var capturedAt: Int64?
    /// Whose camera it came from: a **Contact**'s public key, per #28. Nil means
    /// mine. **My media** and **Received media** must stay distinguishable above.
    var from: String?
    /// **Personal**: attached, but never sent. One bit, default off.
    var personal: Bool = false
    /// A **Pointer** into the owner's own cloud. One nullable string, because
    /// sharing is deferred (#101–#104 are parked).
    var pointer: String?
    /// For a video: where each song starts *inside this recording*, in
    /// milliseconds, one entry per song in setlist order, `-1` for not stamped.
    /// On the record and not on the night, because a night with two recordings
    /// has to put the second one's stamps somewhere (#27).
    var songOffsets: [Int64] = []
    /// A **Note**'s text: what I wrote about the night (#50). Empty otherwise.
    ///
    /// A **Note** is **Media** rather than a record of its own, because ADR-0012
    /// said so — *"notes are media with a Personal bit"* — so everything a note
    /// needs is inherited rather than re-implemented: a band, a disposition, and
    /// arrival as **Received media**.
    var text: String = ""
    /// A **Verdict** on the night, carried by the **Note** it was written on:
    /// `down`, `up`, `double_up`, or nil for unset.
    ///
    /// On the note and not on the **Gig** so that its **Band** decides who reads
    /// it — a verdict in the vault is mine, a verdict in the shared band travels.
    /// A string and not an enum, for the reason `kind` is one.
    var verdict: String?

    init(id: String = "", kind: String = Kind.photo, ref: String = "",
         capturedAt: Int64? = nil, from: String? = nil, personal: Bool = false,
         pointer: String? = nil, songOffsets: [Int64] = [], text: String = "",
         verdict: String? = nil) {
        self.id = id
        self.kind = kind
        self.ref = ref
        self.capturedAt = capturedAt
        self.from = from
        self.personal = personal
        self.pointer = pointer
        self.songOffsets = songOffsets
        self.text = text
        self.verdict = verdict
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = (try? c.decodeIfPresent(String.self, forKey: .id)) ?? nil ?? ""
        kind = (try? c.decodeIfPresent(String.self, forKey: .kind)) ?? nil ?? Kind.photo
        ref = (try? c.decodeIfPresent(String.self, forKey: .ref)) ?? nil ?? ""
        capturedAt = (try? c.decodeIfPresent(Int64.self, forKey: .capturedAt)) ?? nil
        from = (try? c.decodeIfPresent(String.self, forKey: .from)) ?? nil
        personal = (try? c.decodeIfPresent(Bool.self, forKey: .personal)) ?? nil ?? false
        pointer = (try? c.decodeIfPresent(String.self, forKey: .pointer)) ?? nil
        songOffsets = (try? c.decodeIfPresent([Int64].self, forKey: .songOffsets)) ?? nil ?? []
        text = (try? c.decodeIfPresent(String.self, forKey: .text)) ?? nil ?? ""
        verdict = (try? c.decodeIfPresent(String.self, forKey: .verdict)) ?? nil
    }
}

/// A UUID derived from `name` rather than drawn at random — RFC 4122 version 5,
/// the SHA-1 flavour, byte for byte with Android's `uuidFrom`.
///
/// Random would have been less code, and wrong: the same old cache has to migrate
/// to the same ids on both platforms, or a user with two phones ends up with two
/// histories of the same nights. Deriving it also makes the migration idempotent
/// and lets both platforms assert *fixed* expected ids rather than "some uuid",
/// so neither can drift by agreeing with itself.
func uuidFrom(_ name: String) -> String {
    var h = Array(Insecure.SHA1.hash(data: Data(name.utf8)))
    h[6] = (h[6] & 0x0f) | 0x50 // version 5
    h[8] = (h[8] & 0x3f) | 0x80 // RFC 4122 variant
    let hex = h.prefix(16).map { String(format: "%02x", $0) }.joined()
    func part(_ from: Int, _ to: Int) -> String {
        let s = hex.index(hex.startIndex, offsetBy: from)
        let e = hex.index(hex.startIndex, offsetBy: to)
        return String(hex[s..<e])
    }
    return "\(part(0, 8))-\(part(8, 12))-\(part(12, 16))-\(part(16, 20))-\(part(20, 32))"
}

/// The id a **Gig** gets the first time it is seen through a setlist.fm id.
func gigIdForSetlistId(_ setlistId: String) -> String { uuidFrom("gig:\(setlistId)") }

/// A **Festival**: an identity, not a shape (#166). It exists only when something
/// knows it — setlist.fm's own festival page, or a **Bill** typed in by hand — and it
/// is never inferred from a venue string and a date window.
///
/// `id` is local and ours, the same "the identity is ours; the vendors' are
/// attributes" move #107 made for a **Gig**: `setlistFmSlug` and `mbid` are enrichment
/// that may land later or never, and storage never moves because of them.
///
/// `rangeFrom`/`rangeTo` are dd-MM-yyyy, the shape setlist.fm sends everywhere else in
/// this app. Nil when the range isn't known, per ADR-0004's "every field degrades
/// independently to nil".
///
/// `dayMembership` is per-day membership from the source, date (dd-MM-yyyy) to the
/// setlist.fm ids that played that day. `groupIntoFestivals` prefers it over the
/// **Gigs** that merely carry this identity's `id`, because the source's own grouping
/// is evidence and "which gigs happen to be mine" is not.
///
/// `setTimes` is when each act was scheduled to go on, `HH:mm`, by setlist.fm id — the
/// evidence for who played last, which is what the headliner rule is really asking.
///
/// `source` decides who wins on conflict: an authored **Bill** identity is never
/// overwritten by a scrape. The Swift twin of Android's `StoredFestival`.
struct StoredFestival: Codable, Equatable {
    var id: String = ""
    var name: String = ""
    var rangeFrom: String?
    var rangeTo: String?
    var setlistFmSlug: String?
    var mbid: String?
    var source: String = FestivalSource.scraped
    var dayMembership: [String: [String]]?
    var setTimes: [String: String]?

    /// Which source wins on conflict. Plain strings, for `StoredMedia.Kind`'s reason:
    /// a value a newer build writes must cost this field, never the whole cache.
    enum FestivalSource {
        static let scraped = "scraped"
        static let authored = "authored"
    }

    init(
        id: String = "", name: String = "",
        rangeFrom: String? = nil, rangeTo: String? = nil,
        setlistFmSlug: String? = nil, mbid: String? = nil,
        source: String = FestivalSource.scraped,
        dayMembership: [String: [String]]? = nil,
        setTimes: [String: String]? = nil
    ) {
        self.id = id
        self.name = name
        self.rangeFrom = rangeFrom
        self.rangeTo = rangeTo
        self.setlistFmSlug = setlistFmSlug
        self.mbid = mbid
        self.source = source
        self.dayMembership = dayMembership
        self.setTimes = setTimes
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        func str(_ key: CodingKeys) -> String? {
            (try? c.decodeIfPresent(String.self, forKey: key)) ?? nil
        }
        id = str(.id) ?? ""
        name = str(.name) ?? ""
        rangeFrom = str(.rangeFrom)
        rangeTo = str(.rangeTo)
        setlistFmSlug = str(.setlistFmSlug)
        mbid = str(.mbid)
        source = str(.source) ?? FestivalSource.scraped
        dayMembership = (try? c.decodeIfPresent([String: [String]].self, forKey: .dayMembership)) ?? nil
        setTimes = (try? c.decodeIfPresent([String: String].self, forKey: .setTimes)) ?? nil
    }
}

extension TimelineCache {
    /// The identities as the timeline reads them. See `Festivals`.
    func festivalIdentities() -> Festivals {
        Festivals(byId: festivals, idByShow: festivalIdByShow, asked: festivalsAsked)
    }
}

/// The id a scraped **Festival** gets the first time its setlist.fm slug is seen.
func festivalIdForSlug(_ slug: String) -> String { uuidFrom("festival:\(slug)") }

/// **Precedence: setlist.fm, then the author, and the author wins.** An authored
/// **Bill** identity is never overwritten by a scrape — what I know beats what was
/// guessed at upstream — which is a rule about the record and so lives in one place
/// rather than at each of the seams that merge these.
func mergedWith(
    _ kept: [String: StoredFestival],
    _ found: [String: StoredFestival]
) -> [String: StoredFestival] {
    kept.merging(
        found.filter { kept[$0.key]?.source != StoredFestival.FestivalSource.authored }
    ) { _, new in new }
}

/// Every **Festival** identity this device knows, and which **Gigs** carry one.
///
/// This is what `groupIntoFestivals` decides with, and it replaces the name map it used
/// to take (#166). The difference is the whole issue: a name keyed by a cluster's first
/// show could only ever *label* a shape the app had already inferred, so festivalhood
/// was arithmetic. An identity is evidence — it came from setlist.fm's own festival
/// page or from a **Bill** somebody typed — and nothing else makes a **Node** a
/// **Festival**.
struct Festivals: Equatable {
    let byId: [String: StoredFestival]
    /// Festival id by the **Gig**'s setlist.fm id.
    let idByShow: [String: String]
    /// Which **Gigs** have already been asked about. See `TimelineCache.festivalsAsked`.
    let asked: Set<String>

    /// Which identity each **Gig** belongs to, the source's own day grouping winning
    /// over membership carried on the Gig: the festival page saying "these played on
    /// the Thursday" is evidence, and "this is one of the nights I happened to attend"
    /// is not. My attendance decides which nights I *see*, never which nights belong.
    ///
    /// Resolved once here rather than per lookup — `groupIntoFestivals` asks it once
    /// per **Gig**, which is why Kotlin memoises the same map behind `by lazy`.
    private let identityOfShow: [String: String]

    init(
        byId: [String: StoredFestival] = [:],
        idByShow: [String: String] = [:],
        asked: Set<String> = []
    ) {
        self.byId = byId
        self.idByShow = idByShow
        self.asked = asked
        var resolved = idByShow
        for festival in byId.values {
            for ids in (festival.dayMembership ?? [:]).values {
                for id in ids { resolved[id] = festival.id }
            }
        }
        identityOfShow = resolved
    }

    /// The identity this **Gig** belongs to, or nil — which is the common answer.
    func of(_ showId: String) -> StoredFestival? {
        guard let id = identityOfShow[showId] else { return nil }
        return byId[id]
    }

    var isEmpty: Bool { byId.isEmpty }

    /// What was known, plus what has just been learned. See `mergedWith` for who wins.
    static func + (known: Festivals, found: Festivals) -> Festivals {
        Festivals(
            byId: mergedWith(known.byId, found.byId),
            idByShow: known.idByShow.merging(found.idByShow) { _, new in new },
            asked: known.asked.union(found.asked)
        )
    }
}

private let videoExtensions: Set<String> = ["mp4", "mov", "m4v", "3gp", "mkv", "webm"]
private let photoExtensions: Set<String> = ["jpg", "jpeg", "png", "heic", "heif", "webp", "gif"]

/// A picker reference usually has no extension, so this catches the copies the app
/// made for itself and little else. Honest ignorance beats a guess.
func mediaKind(of ref: String) -> String {
    let ext = (ref as NSString).pathExtension.lowercased()
    if videoExtensions.contains(ext) { return StoredMedia.Kind.video }
    if photoExtensions.contains(ext) { return StoredMedia.Kind.photo }
    return StoredMedia.Kind.unknown
}

/// The file's whole contents. Fields iOS does not use yet are still carried
/// through a save: dropping Android's photos or song offsets on the first write
/// from this side would be data loss, not scope.
///
/// The keys declared here are the ones iOS *reads*. The ones it has never heard
/// of — `bills`, `gigLogs` and whatever Android adds next — are carried without
/// being modelled, by `carryingUnknownKeys` on the way out (#168).
struct TimelineCache: Codable {
    /// Attended shows by setlist.fm username — mine and every friend's alike.
    var shows: [String: [FmSetlist]] = [:]
    /// Festival name by its cluster's first show id.
    ///
    /// **Dead since #166**, which gave a **Festival** an identity of its own. Never
    /// read and never written from here again; kept declared so an existing cache
    /// still decodes, and so a handover from a device still on the old build carries
    /// it rather than dropping it.
    var festivalNames: [String: String] = [:]
    /// Every **Festival** identity this app knows, by `StoredFestival.id`. See #166.
    var festivals: [String: StoredFestival] = [:]
    /// A **Gig**'s membership of a **Festival**, by the Gig's setlist.fm id — the
    /// fallback `groupIntoFestivals` uses when `StoredFestival.dayMembership` doesn't
    /// already say which nights belong to it.
    var festivalIdByShow: [String: String] = [:]
    /// The **Gigs** whose setlist.fm page has already been read for a **Festival**
    /// identity, by setlist.fm id — *asked*, not *answered*.
    ///
    /// "There is no festival behind this night" is a correct, final answer and most of
    /// the line, while "the page could not be reached" is a question still open.
    /// Without it every multi-act night with no festival costs a page fetch on every
    /// single launch, forever.
    var festivalsAsked: Set<String> = []
    /// The playlists made from a night, by that night's setlist id, oldest first.
    /// A list rather than one entry because a playlist url is the thing you send
    /// someone: converting a night twice must not overwrite the link a friend
    /// already holds. Named apart from the `playlists` key it replaced, so an
    /// older cache still parses (the old key is simply unknown now).
    var playlistsMade: [String: [StoredPlaylist]] = [:]
    /// How many shows setlist.fm says a user has attended — not how many we hold.
    /// Without it a restored spine looks complete at whatever page it got to.
    var attendedTotals: [String: Int] = [:]
    /// The Reliver's own photos by setlist id. Android-only feature; carried.
    /// Dead since #107: read once by `migrated()`, never written again.
    var photosBySetlist: [String: [String]] = [:]
    /// Song start times inside a night's recording. Android-only feature; carried.
    /// Dead since #107; see `gigSongOffsets`.
    var songOffsetsBySetlist: [String: [Int64]] = [:]
    /// How I came to be marked as at a gig, by setlist id. Android-only (#29);
    /// carried. Dead since #107; see `gigAttendance`.
    var attendanceByGig: [String: StoredAttendance] = [:]
    /// The gigs Android holds a ticket for. Dead since #107; see `gigPlanned`.
    /// Was not carried at all before #107 — a save from here dropped it, which is
    /// the exact data-loss this struct exists to prevent.
    var plannedShows: [FmSetlist] = []
    /// The calendar event made for a gig, by gig id. Dead since #107; see
    /// `gigCalendarEvent`. Not carried before #107 either.
    var calendarEventByGig: [String: String] = [:]

    // MARK: - Keyed by the app's own Gig id (#107)
    //
    // Every map above that was keyed by a night is re-keyed here, and the six of
    // them moved together on purpose: a half-migration leaves two identity schemes
    // and is worse than either. New keys rather than changed value shapes, per the
    // playlistsMade precedent — the old keys stay in the format, are read exactly
    // once by `migrated()`, and are never written again, so an older build still
    // round-trips its own cache instead of failing to decode ours.

    /// Every night this app knows about, by its own id. See `StoredGig`.
    var gigs: [String: StoredGig] = [:]
    /// Replaced `photosBySetlist`; dead in turn since #97, which gave media a
    /// record instead of a bare reference. See `gigMedia`.
    var gigPhotos: [String: [String]] = [:]
    /// Replaced `songOffsetsBySetlist`; dead in turn since #97, which moved
    /// offsets onto the video they belong to. See `StoredMedia.songOffsets`.
    var gigSongOffsets: [String: [Int64]] = [:]
    /// Replaces `attendanceByGig`.
    var gigAttendance: [String: StoredAttendance] = [:]
    /// Replaces `calendarEventByGig`.
    var gigCalendarEvent: [String: String] = [:]
    /// Replaces `playlistsMade`.
    var gigPlaylists: [String: [StoredPlaylist]] = [:]
    /// Replaces `plannedShows`. A map rather than a list because the gig id is now
    /// the identity; the value is unchanged, and the order it used to carry was
    /// re-sorted on read anyway.
    var gigPlanned: [String: FmSetlist] = [:]
    /// The media on each night, by **Gig** id, in the order the user arranged it
    /// (#97). No sort field on the record: deriving and correcting a night's
    /// arrangement is #75's subject, and a speculative field would prejudge it.
    var gigMedia: [String: [StoredMedia]] = [:]
    /// The **Log** for each night, by **Gig** id (#169). See `StoredLog`.
    ///
    /// **This key stops being carried raw and starts being modelled**, which reverses
    /// the call #168 made for it — deliberately. That rule was about "records this
    /// platform never reads", and iOS reads this one now. Carrying it blind is what
    /// you do with a record you cannot use; you cannot render a witness statement you
    /// have not decoded.
    ///
    /// The cost, stated so it is not discovered later: a field Android adds to
    /// `StoredLog` was previously preserved inside the raw blob and now is not,
    /// because `carryingUnknownKeys` works on top-level keys only. `StoredLog` on both
    /// sides must move together — the same rule the rest of this file already lives
    /// under, now applying one level deeper.
    var gigLogs: [String: StoredLog] = [:]

    init() {}

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        func map<T: Decodable>(_ key: CodingKeys, _: T.Type) -> [String: T] {
            (try? c.decodeIfPresent([String: T].self, forKey: key)) ?? nil ?? [:]
        }
        shows = map(.shows, [FmSetlist].self)
        festivalNames = map(.festivalNames, String.self)
        festivals = map(.festivals, StoredFestival.self)
        festivalIdByShow = map(.festivalIdByShow, String.self)
        festivalsAsked = Set((try? c.decodeIfPresent([String].self, forKey: .festivalsAsked)) ?? nil ?? [])
        playlistsMade = map(.playlistsMade, [StoredPlaylist].self)
        attendedTotals = map(.attendedTotals, Int.self)
        photosBySetlist = map(.photosBySetlist, [String].self)
        songOffsetsBySetlist = map(.songOffsetsBySetlist, [Int64].self)
        attendanceByGig = map(.attendanceByGig, StoredAttendance.self)
        plannedShows = (try? c.decodeIfPresent([FmSetlist].self, forKey: .plannedShows)) ?? nil ?? []
        calendarEventByGig = map(.calendarEventByGig, String.self)
        gigs = map(.gigs, StoredGig.self)
        gigPhotos = map(.gigPhotos, [String].self)
        gigSongOffsets = map(.gigSongOffsets, [Int64].self)
        gigAttendance = map(.gigAttendance, StoredAttendance.self)
        gigCalendarEvent = map(.gigCalendarEvent, String.self)
        gigPlaylists = map(.gigPlaylists, [StoredPlaylist].self)
        gigPlanned = map(.gigPlanned, FmSetlist.self)
        gigMedia = map(.gigMedia, [StoredMedia].self)
        gigLogs = map(.gigLogs, StoredLog.self)
    }

    /// The id this gig is known by *outside* the store: its setlist.fm id where it
    /// has one, otherwise its own. Exactly the convention `attendanceByGig`
    /// already documented, which is why the screens above need no re-keying —
    /// adoption changes what this returns for one gig and moves no data at all.
    func keyOf(_ gigId: String) -> String {
        guard let gig = gigs[gigId] else { return gigId }
        return gig.setlistId ?? gig.id
    }

    /// Given a setlist.fm id — from a friend's timeline, say — the local **Gig**.
    ///
    /// ponytail: a scan, not a second index. A collection is hundreds of nights.
    /// Add a reverse map when a scan is actually felt.
    func gigForSetlist(_ setlistId: String) -> StoredGig? {
        gigs.values.first { $0.setlistId == setlistId }
    }

    /// The other direction: given a local **Gig**, its setlist.fm record's id.
    func setlistIdFor(_ gigId: String) -> String? { gigs[gigId]?.setlistId }

    // What the screens read: the gig-keyed maps, back under the id the UI uses.
    func media() -> [String: [StoredMedia]] { rekeyed(gigMedia) }
    func attendance() -> [String: StoredAttendance] { rekeyed(gigAttendance) }
    func calendarEvents() -> [String: String] { rekeyed(gigCalendarEvent) }
    func playlists() -> [String: [StoredPlaylist]] { rekeyed(gigPlaylists) }
    func planned() -> [FmSetlist] { Array(gigPlanned.values) }

    // uniquingKeysWith rather than uniqueKeysWithValues: two gigs claiming one
    // setlist.fm id is a bug, but it must not be a crash on the launch path.
    private func rekeyed<V>(_ map: [String: V]) -> [String: V] {
        Dictionary(map.map { (keyOf($0.key), $0.value) }, uniquingKeysWith: { first, _ in first })
    }
}

/// Both nights' media, the one already here winning any id that appears twice. The twin
/// of Android's `unionMedia`, and the reason a reconcile can simply re-run: an item that
/// arrives again is the copy already held, not a second of it.
func unionMedia(_ kept: [StoredMedia], _ arriving: [StoredMedia]) -> [StoredMedia] {
    kept + arriving.filter { item in !kept.contains { $0.id == item.id } }
}

/// An actor, which is the whole of the locking story: `save` is read-modify-write
/// and several call sites fire independently (my import, the friend lanes, the
/// festival names). Without serialization two overlapping saves both read the old
/// cache and the loser's writes vanish.
actor TimelineStore {

    private let file: URL

    /// `file` is injectable only so the merge can be tested off a device.
    init(file: URL = TimelineStore.defaultFile) {
        self.file = file
    }

    static var defaultFile: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("timelines.json")
    }

    // sortedKeys so two writes of the same content produce the same bytes, which
    // makes a cache diffable against the Android one.
    private let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.outputFormatting = [.sortedKeys]
        return e
    }()

    /// The cache as last written. Empty (never nil) on first run or an unreadable
    /// file — a corrupt cache must cost the timeline, not the launch.
    ///
    /// #107's migration is applied on read rather than as a one-shot upgrade step:
    /// there is no schema version to hang one off, and this way an old cache
    /// restored onto the device later migrates too. It is a no-op once `gigs` is
    /// populated, which the first write after a migration makes permanent.
    func load() -> TimelineCache {
        guard let data = try? Data(contentsOf: file),
              let cache = try? JSONDecoder().decode(TimelineCache.self, from: data)
        else { return TimelineCache() }
        return cache.migrated()
    }

    /// Merges into what is already stored and writes it back. Merging, not
    /// replacing: a refresh of one lane must not wipe the others, and a partial
    /// fetch (one friend's request failed) must not delete their last good copy.
    func save(
        shows: [String: [FmSetlist]] = [:],
        festivals: [String: StoredFestival] = [:],
        festivalIdByShow: [String: String] = [:],
        festivalsAsked: Set<String> = [],
        playlists: [String: StoredPlaylist] = [:],
        attendedTotals: [String: Int] = [:]
    ) {
        writeMerged { cache in
            var c = cache
            c.shows.merge(shows.filter { !$0.value.isEmpty }) { _, new in new }
            // The author beats the scrape, wherever the two meet — see `mergedWith`.
            c.festivals = mergedWith(c.festivals, festivals)
            c.festivalIdByShow.merge(festivalIdByShow) { _, new in new }
            // Asked accumulates and is never cleared: forgetting a "no festival here"
            // is what makes 44 multi-act nights cost 44 fetches on the next launch.
            c.festivalsAsked.formUnion(festivalsAsked)
            c.attendedTotals.merge(attendedTotals) { _, new in new }
            // Appended, never replaced. De-duped on url so recording the same
            // playlist twice is a no-op.
            for (night, made) in playlists {
                let gigId = c.withGig(night)
                var had = c.gigPlaylists[gigId] ?? []
                if !had.contains(where: { $0.url == made.url }) { had.append(made) }
                c.gigPlaylists[gigId] = had
            }
            return c
        }
    }

    /// The **Log** for one night, replacing what was there (#169).
    ///
    /// Keyed by **Gig** id via `withGig`, the same way media is — a **Log** written
    /// before a night was adopted has to survive adoption, and the gig id is the
    /// identity that does not move.
    func saveLog(setlistId: String, log: StoredLog) {
        writeMerged { cache in
            var c = cache
            let gigId = c.withGig(setlistId)
            c.gigLogs[gigId] = log
            return c
        }
    }

    /// The **Log** for one night, or an empty open one — a night not yet logged and a
    /// night logged and emptied are the same thing to a reader.
    func log(setlistId: String) -> StoredLog {
        let c = load()
        guard let gigId = c.gigIdOrNil(setlistId) else { return StoredLog() }
        return c.gigLogs[gigId] ?? StoredLog()
    }

    /// How I came to be marked as at one gig, replacing what was there — this is the
    /// current state of one relationship, not an append-only log.
    ///
    /// The counterpart Android has had since #29. It arrives here alongside
    /// `savePlanned`/`removePlanned` because both of those are *about* the claim — one
    /// refuses to downgrade it, the other refuses to erase it — and neither rule can be
    /// stated, or tested, without a way to write one.
    func saveAttendance(setlistId: String, attendance: StoredAttendance) {
        writeMerged { cache in
            var c = cache
            let gigId = c.withGig(setlistId)
            c.gigAttendance[gigId] = attendance
            return c
        }
    }

    /// Adds a gig I am going to, with the attendance claim that goes with it (#175).
    ///
    /// **One write**, because the record and the claim are useless apart: a planned gig
    /// whose provenance did not land would read as attended on the next launch.
    /// Re-adding the same gig replaces its record rather than duplicating it.
    ///
    /// **Never downgrades the claim.** Re-storing the record when the night's setlist
    /// finally lands must not throw away a check-in that happened in between — which is
    /// exactly the sequence a night you planned and then went to produces.
    func savePlanned(_ setlist: FmSetlist) {
        writeMerged { cache in
            var c = cache
            let gigId = c.withGig(setlist.id)
            c.gigPlanned[gigId] = setlist
            if c.gigAttendance[gigId] == nil {
                c.gigAttendance[gigId] = StoredAttendance(provenance: "planned")
            }
            return c
        }
    }

    /// Forgets a gig I am no longer going to.
    ///
    /// Drops the attendance claim with it — **but only while it is still `planned`**. A
    /// gig since checked into or attended is a night that happened, and taking it out
    /// of my plans must not quietly erase the evidence that I was there.
    func removePlanned(setlistId: String) {
        writeMerged { cache in
            var c = cache
            guard let id = c.gigIdOrNil(setlistId) else { return c }
            c.gigPlanned[id] = nil
            if c.gigAttendance[id]?.provenance == "planned" { c.gigAttendance[id] = nil }
            return c
        }
    }

    /// Remembers the calendar event made for a planned gig, by its EventKit identifier
    /// (#175). The counterpart to Android's `markCalendarAdded`, which keeps a content
    /// URI in the same field — both are just "the handle that proves an event exists".
    func markCalendarAdded(gigId: String, eventId: String) {
        writeMerged { cache in
            var c = cache
            let id = c.withGig(gigId)
            c.gigCalendarEvent[id] = eventId
            return c
        }
    }

    /// The Reliver's current media for one gig, replacing what was there.
    func saveMedia(setlistId: String, media: [StoredMedia]) {
        writeMerged { cache in
            var c = cache
            let gigId = c.withGig(setlistId)
            c.gigMedia[gigId] = media
            return c
        }
    }

    /// What a **Contact** just sent over the same WiFi (#265), added to the nights
    /// it belongs to. The twin of Android's `mergeContactMedia`.
    ///
    /// Additive and keyed by media id, which is what makes a reconcile idempotent:
    /// the same item arriving twice — a second Exchange visit, a re-diff after a
    /// dropped connection — is the copy already held, not a duplicate. Nothing here
    /// mints a night; `contactLanding` has already refused anything that would.
    func mergeContactMedia(_ landing: [String: [StoredMedia]]) {
        if landing.isEmpty { return }
        writeMerged { cache in
            var c = cache
            for (gigId, items) in landing {
                c.gigMedia[gigId] = unionMedia(c.gigMedia[gigId] ?? [], items)
            }
            return c
        }
    }

    /// The union a device handover decided, written under this actor's own lock (#142).
    ///
    /// Takes the *plan function*, not a plan: a handover can run for minutes, and a union
    /// computed against a cache read before it started would discard everything this
    /// device wrote meanwhile — a Contact reconcile landing, a note typed. Running it here
    /// means the cache it merges into is the cache being written.
    /// Returns the plan it actually wrote, so the caller can cut thumbnails for exactly
    /// what landed (#98) without recomputing it against a cache that has since moved.
    @discardableResult
    func applyHandover(_ plan: (TimelineCache) -> HandoverPlan) -> HandoverPlan {
        var written = HandoverPlan()
        writeMerged { cache in
            written = plan(cache)
            return written.merged
        }
        return written
    }

    /// Where each song starts inside one recording, replacing what was there.
    ///
    /// By media id, not by night: a night with two recordings has two answers,
    /// and before #97 the second one had nowhere to live. A stamp for a video no
    /// longer attached is dropped rather than resurrecting the record.
    func saveSongOffsets(mediaId: String, offsets: [Int64]) {
        writeMerged { cache in
            var c = cache
            guard let gigId = c.gigMedia.first(where: { $0.value.contains { $0.id == mediaId } })?.key
            else { return c }
            c.gigMedia[gigId] = c.gigMedia[gigId]?.map {
                var m = $0
                if m.id == mediaId { m.songOffsets = offsets }
                return m
            }
            return c
        }
    }

    /// Drops one playlist link from a night — the Spotify playlist itself was
    /// deleted outside the app, so the pointer to it is now dead weight.
    func removePlaylist(setlistId: String, url: String) {
        writeMerged { cache in
            var c = cache
            guard let id = c.gigIdOrNil(setlistId) else { return c }
            c.gigPlaylists[id] = (c.gigPlaylists[id] ?? []).filter { $0.url != url }
            return c
        }
    }

    /// A night setlist.fm has never heard of — the poster in the window, the small
    /// venue nobody catalogues. Returns the id everything else keys by; it is also
    /// the id the screens use, until `adoptSetlistId` gives the night a vendor one.
    ///
    /// Random rather than derived: there is no setlist.fm id to derive from, and
    /// the facts are exactly what cannot be trusted as a key (venues get renamed,
    /// artists rename, festival days split) — that is why a natural key was
    /// rejected.
    @discardableResult
    func createLocalGig(date: String, artist: String, venue: String) -> String {
        let id = UUID().uuidString.lowercased()
        writeMerged { cache in
            var c = cache
            c.gigs[id] = StoredGig(id: id, date: date, artist: artist, venue: venue,
                                   createdAt: c.nextCreatedAt())
            return c
        }
        return id
    }

    /// A night that setlist.fm has now catalogued takes their id (#34's search
    /// found the match; this is all that is left to do). One field on one record —
    /// no data moves, because nothing was ever keyed by the vendor id.
    ///
    /// Refuses a gig that already has one: two setlist.fm ids for one night is a
    /// bug upstream, not a merge case, and silently overwriting would hide it.
    @discardableResult
    func adoptSetlistId(gigId: String, setlistId: String) -> Bool {
        var adopted = false
        writeMerged { cache in
            var c = cache
            guard var gig = c.gigs[gigId], gig.setlistId == nil else { return c }
            gig.setlistId = setlistId
            c.gigs[gigId] = gig
            adopted = true
            return c
        }
        return adopted
    }

    /// Two records found to be the same night become one — the case where a night
    /// added by hand is later also imported.
    ///
    /// The older id wins, and the survivor takes the union: nothing a merge
    /// touches may cost the user a photo, a check-in or a playlist link. Returns
    /// the id that survived, or nil if either gig is unknown.
    @discardableResult
    func mergeGigs(_ gigIdA: String, _ gigIdB: String) -> String? {
        var survivor: String?
        writeMerged { cache in
            var c = cache
            guard let a = c.gigs[gigIdA], let b = c.gigs[gigIdB], a.id != b.id else { return c }
            // createdAt, then the id itself, so two devices merging the same pair
            // reach the same answer without a synchronised clock.
            let keepsA = a.createdAt != b.createdAt ? a.createdAt < b.createdAt : a.id < b.id
            var keep = keepsA ? a : b
            let gone = keepsA ? b : a
            survivor = keep.id

            keep.setlistId = keep.setlistId ?? gone.setlistId
            if keep.date.isEmpty { keep.date = gone.date }
            if keep.artist.isEmpty { keep.artist = gone.artist }
            if keep.venue.isEmpty { keep.venue = gone.venue }
            c.gigs[gone.id] = nil
            c.gigs[keep.id] = keep

            // Photos and playlists are collections of separate things, so the
            // union is every one of them. The rest are one current value per
            // night, where the survivor's own answer is the one to keep.
            c.gigMedia = c.gigMedia.folded(keep.id, gone.id) { k, d in
                k + d.filter { m in !k.contains(where: { $0.id == m.id }) }
            }
            c.gigPlaylists = c.gigPlaylists.folded(keep.id, gone.id) { k, d in
                k + d.filter { p in !k.contains(where: { $0.url == p.url }) }
            }
            c.gigSongOffsets = c.gigSongOffsets.folded(keep.id, gone.id) { k, _ in k }
            c.gigAttendance = c.gigAttendance.folded(keep.id, gone.id) { k, _ in k }
            c.gigCalendarEvent = c.gigCalendarEvent.folded(keep.id, gone.id) { k, _ in k }
            c.gigPlanned = c.gigPlanned.folded(keep.id, gone.id) { k, _ in k }
            return c
        }
        return survivor
    }

    private func writeMerged(_ transform: (TimelineCache) -> TimelineCache) {
        let merged = transform(load()).withGigFacts()
        guard let data = try? encoder.encode(merged) else { return }
        // .atomic: a crash mid-write leaves the old cache intact rather than a
        // truncated one that fails to parse.
        try? carryingUnknownKeys(data).write(to: file, options: .atomic)
    }

    /// Puts back every top-level key the file on disk had that `TimelineCache`
    /// does not model at all (#168).
    ///
    /// `Codable` drops what it never decoded, so without this the first save from
    /// here erases whatever Android has learned to write since — and `bills` and
    /// `gigLogs` are the two records in this app with no upstream, so a setlist.fm
    /// pull cannot put them back. That is data loss, not scope, which is the rule
    /// `TimelineCache` states about itself.
    ///
    /// Carried raw rather than modelled: five Swift types for records this
    /// platform never reads would fix exactly today's five, and break again on
    /// the next field the other side adds. Every key iOS *does* know is
    /// non-optional with a default and is therefore always encoded, so "absent
    /// from what we just encoded" is precisely "unknown to this platform".
    ///
    /// `.sortedKeys` again on the way out, so the same content still produces the
    /// same bytes and a cache stays diffable against the Android one.
    private func carryingUnknownKeys(_ encoded: Data) -> Data {
        guard let old = try? Data(contentsOf: file),
              let stored = (try? JSONSerialization.jsonObject(with: old)) as? [String: Any],
              var object = (try? JSONSerialization.jsonObject(with: encoded)) as? [String: Any]
        else { return encoded }
        for (key, value) in stored where object[key] == nil { object[key] = value }
        return (try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])) ?? encoded
    }
}

extension TimelineCache {

    /// #107's migration: every map that was keyed by a night gets re-keyed to a
    /// **Gig** the app owns, and one record per night appears to hang them off.
    /// Byte for byte with Android's, which is what the derived ids are for.
    ///
    /// All six move at once, deliberately — a half-migration leaves two identity
    /// schemes and is worse than either. Nothing is deleted: the old keys keep
    /// their values and are simply never written again, so an older build reading
    /// this file still finds everything where it left it.
    ///
    /// Every old key is taken to be a setlist.fm id. The old comment on
    /// `attendanceByGig` allowed for a local id there too, but #34 — the only
    /// thing that would ever have minted one — was never built, so no cache in
    /// existence contains one.
    func migrated() -> TimelineCache { withGigs().withMedia() }

    private func withGigs() -> TimelineCache {
        guard gigs.isEmpty else { return self }
        var oldKeys: [String] = []
        var seen = Set<String>()
        for key in photosBySetlist.keys.sorted() + songOffsetsBySetlist.keys.sorted()
            + attendanceByGig.keys.sorted() + calendarEventByGig.keys.sorted()
            + playlistsMade.keys.sorted() + plannedShows.map(\.id) where seen.insert(key).inserted {
            oldKeys.append(key)
        }
        if oldKeys.isEmpty { return self }
        // One id per *distinct* night, so a setlist id appearing in five maps lands
        // on one Gig with five associations rather than five gigs with one each.
        var idOf: [String: String] = [:]
        for key in oldKeys { idOf[key] = gigIdForSetlistId(key) }

        var c = self
        for (old, id) in idOf { c.gigs[id] = StoredGig(id: id, setlistId: old) }
        c.gigPhotos = rekey(photosBySetlist, idOf)
        c.gigSongOffsets = rekey(songOffsetsBySetlist, idOf)
        c.gigAttendance = rekey(attendanceByGig, idOf)
        c.gigCalendarEvent = rekey(calendarEventByGig, idOf)
        c.gigPlaylists = rekey(playlistsMade, idOf)
        for show in plannedShows { c.gigPlanned[idOf[show.id] ?? show.id] = show }
        return c.withGigFacts()
    }

    /// #97's migration: a bare gallery reference becomes a record with an
    /// identity, and a night's song stamps move onto the recording they describe.
    ///
    /// Kind is resolved from the reference's extension and otherwise recorded as
    /// `unknown` — an honest gap rather than a guess. Android additionally asks
    /// its ContentResolver while the reference may still be alive, which is the
    /// one moment kind can still be learned; there is no equivalent here, because
    /// the references in an old cache are Android content URIs this platform
    /// cannot resolve at all. Whichever device migrates first writes `gigMedia`
    /// and the other simply reads it.
    ///
    /// The offsets rule is **exactly one video, or nothing**. A night with none or
    /// with two leaves the old entry untouched in the dead key rather than
    /// guessing: a wrong guess silently mis-stamps a recording, and nothing is
    /// lost by declining.
    func withMedia() -> TimelineCache {
        guard gigMedia.isEmpty, !gigPhotos.isEmpty else { return self }
        var c = self
        for (gigId, refs) in gigPhotos {
            var items = refs.map { ref in
                StoredMedia(
                    // Derived, like the gig ids, so both platforms migrate one
                    // cache to one set of ids — and #98's filenames stay stable.
                    id: uuidFrom("media:\(gigId):\(ref)"),
                    kind: mediaKind(of: ref),
                    ref: ref
                )
            }
            if let offsets = gigSongOffsets[gigId] {
                let videoIndexes = items.indices.filter { items[$0].kind == StoredMedia.Kind.video }
                if videoIndexes.count == 1 { items[videoIndexes[0]].songOffsets = offsets }
            }
            c.gigMedia[gigId] = items
        }
        return c
    }

    /// The **Gig** `key` names, minting one if this is the first thing ever hung
    /// off that night. `key` is what the screens use — a setlist.fm id, or a gig
    /// id for a night setlist.fm has never heard of.
    ///
    /// ponytail: minted on demand rather than at import. A Gig record for a night
    /// with nothing attached to it holds nothing the FmSetlist doesn't already,
    /// and minting here happens inside the actor, so two writes for the same night
    /// can't race into two gigs. Mint at import when #34 needs a night to exist
    /// before anything hangs off it.
    mutating func withGig(_ key: String) -> String {
        if let id = gigIdOrNil(key) { return id }
        // The same derivation the migration uses, so attaching to a night here and
        // migrating a cache that already knew it produce one id, not two.
        let id = gigIdForSetlistId(key)
        gigs[id] = StoredGig(id: id, setlistId: key, createdAt: nextCreatedAt())
        return id
    }

    /// The Gig `key` names, or nil — the read side of `withGig`, minting nothing.
    func gigIdOrNil(_ key: String) -> String? {
        gigForSetlist(key)?.id ?? (gigs[key] != nil ? key : nil)
    }

    /// The stamp a new **Gig** gets: the clock, unless the clock has not moved
    /// since the last one — two gigs created in the same millisecond would
    /// otherwise be the same age, and "the older id wins" needs an answer for
    /// every pair. Strictly increasing records the order they were created in,
    /// which is the thing the rule actually means.
    func nextCreatedAt() -> Int64 {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return max(now, (gigs.values.map(\.createdAt).max() ?? 0) + 1)
    }

    /// Fills in the facts of any **Gig** that has none, from a setlist.fm record
    /// already in the cache. A gig minted by attaching a photo knows only its id
    /// and its setlist id; this is what makes it a night — a date, an artist, a
    /// venue — as soon as the import that describes it arrives, in whichever order
    /// the two happen.
    func withGigFacts() -> TimelineCache {
        guard !gigs.isEmpty else { return self }
        var known: [String: FmSetlist] = [:]
        for list in shows.values { for show in list { known[show.id] = show } }
        for show in gigPlanned.values { known[show.id] = show }
        var c = self
        for (id, gig) in gigs where gig.date.isEmpty {
            guard let setlistId = gig.setlistId, let fm = known[setlistId] else { continue }
            var filled = gig
            filled.date = fm.eventDate ?? ""
            filled.artist = fm.artist?.name ?? ""
            filled.venue = fm.venue?.name ?? ""
            c.gigs[id] = filled
        }
        return c
    }

    private func rekey<V>(_ map: [String: V], _ idOf: [String: String]) -> [String: V] {
        Dictionary(map.map { (idOf[$0.key] ?? $0.key, $0.value) }, uniquingKeysWith: { a, _ in a })
    }
}

extension Dictionary where Key == String {
    /// Moves `drop`'s entry onto `keep`, combining the two with `union` if both exist.
    func folded(_ keep: Key, _ drop: Key, _ union: (Value, Value) -> Value) -> Self {
        guard let dropped = self[drop] else { return self }
        var copy = self
        copy[drop] = nil
        copy[keep] = self[keep].map { union($0, dropped) } ?? dropped
        return copy
    }
}
