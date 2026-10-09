import Foundation

/// The demo **Log** and **Gossip** stay in a tagged store, outside the timeline and
/// radio ledgers. A **Room** reads them only while the **Tour** runs.
@MainActor
final class TourLogEffects: DemoWorld {
    struct Gossip: Codable, Equatable, Identifiable {
        enum Source: String, Codable, Equatable { case virtualFriend, user }
        var id: String
        var demo = true
        var source: Source
        var contactKey: String?
        var song: String
        var characterLine: String
        var enteredAt: Int64
    }

    struct Record: Codable, Equatable {
        var demo = true
        var log = StoredLog()
        var gossip: [Gossip] = []
        var filled = false
    }

    private static let key = "tour.demoLogs"
    private let host: StateHost
    private let store: UserDefaults
    private let setlistFm: SetlistFmClient
    private let musicBrainz: MusicBrainzClient
    private let friendKey: () -> String?
    private let characterLine: (TourStep) -> String
    private let pause: (UInt64) async -> Void
    private var generation = UUID()
    private var pools: [String: (fm: [String], mb: [String])] = [:]

    init(host: StateHost, store: UserDefaults = .standard,
         setlistFm: SetlistFmClient, musicBrainz: MusicBrainzClient,
         friendKey: @escaping () -> String?, characterLine: @escaping (TourStep) -> String,
         pause: @escaping (UInt64) async -> Void = { try? await Task.sleep(nanoseconds: $0 * 1_000_000) }) {
        self.pause = pause
        self.host = host
        self.store = store
        self.setlistFm = setlistFm
        self.musicBrainz = musicBrainz
        self.friendKey = friendKey
        self.characterLine = characterLine
    }

    var records: [String: Record] {
        guard let data = store.data(forKey: Self.key),
              let records = try? JSONDecoder().decode([String: Record].self, from: data) else { return [:] }
        return records
    }

    func loadLog(_ gigId: String) -> StoredLog { records[gigId]?.log ?? StoredLog() }
    func gossip(_ gigId: String) -> [Gossip] { records[gigId]?.gossip ?? [] }
    func fillLine(_ gigId: String) -> String? { records[gigId]?.filled == true ? characterLine(.s17) : nil }

    func writeLog(_ gigId: String, log: StoredLog, reply: Bool, now: Int64) {
        var record = records[gigId] ?? Record()
        if reply {
            let changes = gossipLogChanges(before: record.log, after: log)
            for line in changes.keys.sorted() {
                guard let song = changes[line], !song.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { continue }
                record.gossip.append(Gossip(id: UUID().uuidString, source: .user, contactKey: nil,
                                            song: song, characterLine: "", enteredAt: now))
            }
        }
        record.log = log
        save(gigId, record)
    }

    /// The **Virtual friend** names a **Gap** with a real song by the chosen band.
    /// The character definition supplies the accompanying line, never a fabricated title.
    @discardableResult
    func deliverGossip(for gig: FmSetlist, now: Int64) async -> Bool {
        let token = generation
        let pool = await songs(for: gig)
        guard generation == token, !Task.isCancelled else { return false }
        if records[gig.id]?.gossip.contains(where: { $0.source == .virtualFriend }) == true { return true }
        await pause(2500)
        guard generation == token, !Task.isCancelled else { return false }
        var record = records[gig.id] ?? Record()
        if record.gossip.contains(where: { $0.source == .virtualFriend }) { return true }
        let titles = dedupe(pool.fm.isEmpty ? pool.mb : pool.fm)
        guard let gap = record.log.songs.firstIndex(where: { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }),
              let song = titles.dropFirst().first(where: { title in !record.log.songs.contains { sameSong($0, title) } })
        else { return false }
        record.log = record.log.fillingTourGap(at: gap, title: song)
        record.gossip.append(Gossip(id: UUID().uuidString, source: .virtualFriend, contactKey: friendKey(),
                                    song: song, characterLine: characterLine(.s16), enteredAt: now))
        save(gig.id, record)
        return true
    }

    @discardableResult
    func fillSetlist(for gig: FmSetlist, now: Int64) async -> Bool {
        let token = generation
        let pool = await songs(for: gig)
        guard generation == token, !Task.isCancelled else { return false }
        var record = records[gig.id] ?? Record()
        if record.filled { return true }
        let additions = tourSetlistFill(userSongs: record.log.songs, setlistFmSongs: pool.fm, musicBrainzSongs: pool.mb)
        guard !additions.isEmpty else { return false }
        for song in additions {
            await pause(500)
            guard generation == token, !Task.isCancelled else { return false }
            record = records[gig.id] ?? Record()
            if !record.log.songs.contains(where: { sameSong($0, song) }) {
                record.log = record.log.adding(song, now: now)
                save(gig.id, record)
            }
        }
        record.filled = true
        save(gig.id, record)
        return true
    }

    func opener(for gig: FmSetlist) async -> String? {
        let pool = await songs(for: gig)
        return (pool.fm.isEmpty ? pool.mb : pool.fm).first
    }

    func purge() {
        generation = UUID()
        pools = [:]
        let ids = Set(records.keys)
        store.removeObject(forKey: Self.key)
        if let id = host.state.selectedSetlist?.id, ids.contains(id) { host.state.gigLog = StoredLog() }
    }

    private func save(_ gigId: String, _ record: Record) {
        var all = records
        all[gigId] = record
        if let data = try? JSONEncoder().encode(all) { store.set(data, forKey: Self.key) }
        if host.state.selectedSetlist?.id == gigId { host.state.gigLog = record.log }
    }

    private func songs(for gig: FmSetlist) async -> (fm: [String], mb: [String]) {
        guard let mbid = gig.artist?.mbid, !mbid.isEmpty else {
            print("Tour: song pool has no artist MBID for \(gig.id)")
            return ([], [])
        }
        if let pool = pools[mbid] { return pool }
        let token = generation
        var fm: [String] = []
        var page = 1
        var fetched = 0
        while generation == token, !Task.isCancelled {
            let response: SetlistsResponse
            do { response = try await setlistFm.artistSetlists(mbid, page: page) }
            catch { print("Tour: setlist.fm song pool failed for \(mbid): \(error)"); break }
            let recent = response.setlist.sorted { ($0.localDate() ?? .distantPast) > ($1.localDate() ?? .distantPast) }
            fm = recent.first { !$0.performed().isEmpty }?.performed().map(\.name) ?? []
            fetched += response.setlist.count
            if !fm.isEmpty || response.setlist.isEmpty || fetched >= response.total { break }
            page += 1
        }
        var mb: [String] = []
        if fm.isEmpty, generation == token, !Task.isCancelled { mb = await musicBrainz.catalogue(mbid: mbid) }
        if fm.isEmpty && mb.isEmpty { print("Tour: no performed setlist or MusicBrainz songs for \(mbid)") }
        let pool = (fm: fm, mb: mb)
        if generation == token, !Task.isCancelled, (!fm.isEmpty || !mb.isEmpty) { pools[mbid] = pool }
        return pool
    }
}

private extension StoredLog {
    /// A **Gap** has no remembered words to correct; its entry time and line number survive.
    func fillingTourGap(at index: Int, title: String) -> StoredLog {
        var titles = songs
        titles[index] = title
        return StoredLog(songs: titles, closed: closed, remembered: remembered, enteredAt: enteredAt,
                         completedAt: completedAt, lineNumbers: lineNumbers, nextLineNumber: nextLineNumber)
    }
}

/// Additions only: the person's **Log** survives even when it already exceeds ten songs.
func tourSetlistFill(userSongs: [String], setlistFmSongs: [String], musicBrainzSongs: [String]) -> [String] {
    let source = dedupe(setlistFmSongs).isEmpty ? musicBrainzSongs : setlistFmSongs
    let remaining = dedupe(source).filter { song in !userSongs.contains { sameSong($0, song) } }
    let named = userSongs.filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }.count
    return Array(remaining.prefix(max(0, 10 - named)))
}
