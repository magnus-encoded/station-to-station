import Foundation

/// The selfie (S18): the person's own attach on the demo **Gig** and the **Virtual friend**'s
/// selfie, which arrives as **Received media** from the demo **Contact**.
///
/// Both are remembered by id so the purge can take them back. The purge removes only the
/// app's records and thumbnails; the person's photo stays in their library. While the Tour
/// runs none of it is offered to a real **Contact**.
@MainActor
final class TourSelfieEffects: DemoWorld {
    private static let key = "tour.demoSelfieMedia"
    private let host: StateHost
    private let store: UserDefaults
    private let timelines: TimelineStore
    private let demoGigIds: () -> [String]
    private let friendKey: () -> String?
    private let selfie: () -> Data?
    private let deleteThumbnails: (String) -> Void
    private var generation = UUID()

    init(host: StateHost, store: UserDefaults = .standard, timelines: TimelineStore,
         demoGigIds: @escaping () -> [String], friendKey: @escaping () -> String?,
         selfie: @escaping () -> Data?,
         deleteThumbnails: @escaping (String) -> Void = { PhotoLibrary.deleteThumbnails($0) }) {
        self.host = host
        self.store = store
        self.timelines = timelines
        self.demoGigIds = demoGigIds
        self.friendKey = friendKey
        self.selfie = selfie
        self.deleteThumbnails = deleteThumbnails
    }

    private var records: [String: [StoredMedia]] {
        guard let data = store.data(forKey: Self.key),
              let records = try? JSONDecoder().decode([String: [StoredMedia]].self, from: data) else { return [:] }
        return records
    }

    func attachment(for gigId: String, completed: @escaping () -> Void) -> ([StoredMedia]) -> Void {
        let token = generation
        let held = records[gigId] ?? []
        return { [self] media in
            guard !media.isEmpty else { return }
            remember(media, for: gigId)
            guard token == generation else { discard(held + media); return }
            completed()
        }
    }

    func deliverFriendSelfie(for gig: FmSetlist, now: Int64) async {
        let token = generation
        guard demoGigIds().contains(gig.id), let key = friendKey()?.nilIfBlank, let source = selfie(),
              let grid = thumbnailJpeg(from: source, maxEdge: Thumbnails.gridEdgePx, quality: Thumbnails.gridQuality),
              let full = thumbnailJpeg(from: source, maxEdge: Thumbnails.fullEdgePx, quality: Thumbnails.fullQuality)
        else { return }
        let cache = await timelines.load()
        guard token == generation, !Task.isCancelled,
              !(records[gig.id] ?? []).contains(where: { $0.from == key }) else { return }
        let id = UUID().uuidString.lowercased()
        do {
            try grid.write(to: Thumbnails.gridFile(id), options: .atomic)
            try full.write(to: Thumbnails.cacheFile(id), options: .atomic)
        } catch {
            deleteThumbnails(id)
            return
        }
        let item = StoredMedia(id: id, kind: StoredMedia.Kind.photo, capturedAt: now,
                               from: key, personal: false)
        remember([item], for: gig.id)
        let split = bandsOf((cache.media()[gig.id] ?? []) + [item])
        let media = split.shared + split.received + split.vault
        await timelines.saveMedia(setlistId: gig.id, media: media)
        guard token == generation, !Task.isCancelled else { discard([item]); return }
        host.state.mediaBySetlist[gig.id] = media
    }

    /// Demo media stays on this phone, including the shared Band. Filter both the
    /// manifest and the byte source so a real Contact cannot request its originals.
    func mediaExchangeCache(_ cache: TimelineCache) -> TimelineCache {
        let gigs = Set(demoGigIds() + Array(records.keys))
        let owned = Set(records.values.flatMap { $0 }.map(\.id))
        var safe = cache
        safe.gigMedia = cache.gigMedia.mapValues { $0.filter { !owned.contains($0.id) } }
        for (id, gig) in cache.gigs where gigs.contains(id) || gigs.contains(gig.setlistId ?? "") {
            safe.gigMedia[id] = nil
        }
        for id in gigs { safe.gigMedia[id] = nil }
        safe.mediaOffers = cache.mediaOffers.filter { !gigs.contains($0.key) }
        return safe
    }

    func purge() {
        generation = UUID()
        discard(records.values.flatMap { $0 })
    }

    private func remember(_ media: [StoredMedia], for gigId: String) {
        var all = records
        all[gigId] = unionMedia(all[gigId] ?? [], media)
        save(all)
    }

    private func discard(_ media: [StoredMedia]) {
        let ids = Set(media.map(\.id))
        guard !ids.isEmpty else { return }
        host.state.mediaBySetlist = host.state.mediaBySetlist.mapValues { $0.filter { !ids.contains($0.id) } }
        Task {
            // Remove by item id, without minting a Gig if another Demo world part already purged it.
            await timelines.removeMedia(ids: ids)
            media.forEach { deleteThumbnails($0.id) }
            let kept = records.mapValues { $0.filter { !ids.contains($0.id) } }.filter { !$0.value.isEmpty }
            save(kept)
        }
    }

    private func save(_ records: [String: [StoredMedia]]) {
        if records.isEmpty { store.removeObject(forKey: Self.key) }
        else if let data = try? JSONEncoder().encode(records) { store.set(data, forKey: Self.key) }
    }
}
