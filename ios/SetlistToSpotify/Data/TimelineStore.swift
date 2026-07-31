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

/// The file's whole contents. Fields iOS does not use yet are still carried
/// through a save: dropping Android's photos or song offsets on the first write
/// from this side would be data loss, not scope.
struct TimelineCache: Codable {
    /// Attended shows by setlist.fm username — mine and every friend's alike.
    var shows: [String: [FmSetlist]] = [:]
    /// Festival name by its cluster's first show id.
    var festivalNames: [String: String] = [:]
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
    var photosBySetlist: [String: [String]] = [:]
    /// Song start times inside a night's recording. Android-only feature; carried.
    var songOffsetsBySetlist: [String: [Int64]] = [:]

    init() {}

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        func map<T: Decodable>(_ key: CodingKeys, _: T.Type) -> [String: T] {
            (try? c.decodeIfPresent([String: T].self, forKey: key)) ?? nil ?? [:]
        }
        shows = map(.shows, [FmSetlist].self)
        festivalNames = map(.festivalNames, String.self)
        playlistsMade = map(.playlistsMade, [StoredPlaylist].self)
        attendedTotals = map(.attendedTotals, Int.self)
        photosBySetlist = map(.photosBySetlist, [String].self)
        songOffsetsBySetlist = map(.songOffsetsBySetlist, [Int64].self)
    }
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
    func load() -> TimelineCache {
        guard let data = try? Data(contentsOf: file),
              let cache = try? JSONDecoder().decode(TimelineCache.self, from: data)
        else { return TimelineCache() }
        return cache
    }

    /// Merges into what is already stored and writes it back. Merging, not
    /// replacing: a refresh of one lane must not wipe the others, and a partial
    /// fetch (one friend's request failed) must not delete their last good copy.
    func save(
        shows: [String: [FmSetlist]] = [:],
        festivalNames: [String: String] = [:],
        playlists: [String: StoredPlaylist] = [:],
        attendedTotals: [String: Int] = [:]
    ) {
        writeMerged { cache in
            var c = cache
            c.shows.merge(shows.filter { !$0.value.isEmpty }) { _, new in new }
            c.festivalNames.merge(festivalNames) { _, new in new }
            c.attendedTotals.merge(attendedTotals) { _, new in new }
            // Appended, never replaced. De-duped on url so recording the same
            // playlist twice is a no-op.
            for (night, made) in playlists {
                var had = c.playlistsMade[night] ?? []
                if !had.contains(where: { $0.url == made.url }) { had.append(made) }
                c.playlistsMade[night] = had
            }
            return c
        }
    }

    /// The Reliver's current set of photos for one gig, replacing what was there.
    func savePhotos(setlistId: String, uris: [String]) {
        writeMerged { var c = $0; c.photosBySetlist[setlistId] = uris; return c }
    }

    /// A night's song start times inside its recording, replacing what was there.
    func saveSongOffsets(setlistId: String, offsets: [Int64]) {
        writeMerged { var c = $0; c.songOffsetsBySetlist[setlistId] = offsets; return c }
    }

    /// Drops one playlist link from a night — the Spotify playlist itself was
    /// deleted outside the app, so the pointer to it is now dead weight.
    func removePlaylist(setlistId: String, url: String) {
        writeMerged { cache in
            var c = cache
            c.playlistsMade[setlistId] = (c.playlistsMade[setlistId] ?? []).filter { $0.url != url }
            return c
        }
    }

    private func writeMerged(_ transform: (TimelineCache) -> TimelineCache) {
        let merged = transform(load())
        guard let data = try? encoder.encode(merged) else { return }
        // .atomic: a crash mid-write leaves the old cache intact rather than a
        // truncated one that fails to parse.
        try? data.write(to: file, options: .atomic)
    }
}
