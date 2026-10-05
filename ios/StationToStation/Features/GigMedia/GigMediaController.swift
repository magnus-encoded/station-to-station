import Foundation

/// **GigMedia**: what is attached to the open night, where it sits in its Bands, the song
/// stamps on a recording, and the library's suggestions from that night.
@MainActor
final class GigMediaController {
    unowned let host: StateHost
    private let timelines: TimelineStore
    /// Playlist's, reached through `AppModel` until that feature is a controller.
    private let markSelectedOwnership: (FmSetlist, StoredAttendance?) -> Void

    init(host: StateHost, timelines: TimelineStore,
         markSelectedOwnership: @escaping (FmSetlist, StoredAttendance?) -> Void) {
        self.host = host
        self.timelines = timelines
        self.markSelectedOwnership = markSelectedOwnership
    }

    func loadGigMedia(_ setlist: FmSetlist) {
        host.state.gigMediaSuggestions = []
        host.state.selectedAttendance = nil
        Task {
            let cache = await timelines.load()
            guard host.state.selectedSetlist?.id == setlist.id else { return }
            // The whole map: opening any night is also the cheapest moment to refresh
            // what the Timeline behind it is drawing.
            host.state.mediaBySetlist = cache.media()
            host.state.mediaOffers = cache.mediaOffers
            host.state.playlistsBySetlist = cache.playlists()
            host.state.selectedAttendance = cache.attendance()[setlist.id]
            markSelectedOwnership(setlist, attendance: host.state.selectedAttendance)
            refreshSuggestions(setlist)
        }
    }

    /// Where each song sits in a recording, as long as the setlist is now.
    ///
    /// Padded and truncated to `songCount` rather than returned as stored: the setlist
    /// can be edited on setlist.fm after a night was stamped, and a stored list of the
    /// old length would otherwise shift every song's time by one.
    func songOffsets(mediaId: String?, songCount: Int) -> [Int64] {
        let stored = mediaId.flatMap { id in
            host.state.mediaBySetlist.values.compactMap { $0.first { $0.id == id } }.first
        }?.songOffsets ?? []
        return (0..<max(songCount, 0)).map { stored.indices.contains($0) ? stored[$0] : notStamped }
    }

    /// Records that song `index` starts at `atMs` in the night's recording, or clears
    /// it with `notStamped`.
    ///
    /// Only this one song moves. The recording and the setlist need not hold the same
    /// songs — a clip setlist.fm left out sits in the gap between two stamps — so
    /// nothing may be inferred about its neighbours from one stamp.
    func stampSong(mediaId: String, index: Int, atMs: Int64, songCount: Int) {
        var offsets = songOffsets(mediaId: mediaId, songCount: songCount)
        guard offsets.indices.contains(index) else { return }
        offsets[index] = atMs
        for (gigId, media) in host.state.mediaBySetlist where media.contains(where: { $0.id == mediaId }) {
            host.state.mediaBySetlist[gigId] = media.map {
                var m = $0
                if m.id == mediaId { m.songOffsets = offsets }
                return m
            }
        }
        Task { await timelines.saveSongOffsets(mediaId: mediaId, offsets: offsets) }
    }

    private func refreshSuggestions(_ setlist: FmSetlist) {
        // Silent without permission: the picker is what asks, so a prompt only
        // ever follows a tap.
        guard PhotoLibrary.isAuthorized,
              let date = setlist.eventDate,
              let window = photoWindow(gigDate: date)
        else { host.state.gigMediaSuggestions = []; return }
        let attached = Set(host.state.gigMedia.map(\.ref))
        Task {
            let found = await Task.detached { PhotoLibrary.assetsFromNight(window) }.value
            guard host.state.selectedSetlist?.id == setlist.id else { return }
            host.state.gigMediaSuggestions = found.filter { !attached.contains($0) }
        }
    }

    /// **Attach**: the picked assets become this night's, with both thumbnail
    /// tiers written before the record exists. Anything whose bytes could not be
    /// read is *not* attached and says so — a record with nothing behind it is the
    /// failure this refuses.
    ///
    /// **Attach asks once** (porting Android's `AttachHandle`): `band` is
    /// the answer to "shared or vault", named by whichever control the gesture
    /// landed on. There is no default path into this — every caller names one.
    func attachMedia(assetIds: [String], to band: Band = .shared) {
        guard let setlist = host.state.selectedSetlist else { return }
        let had = host.state.gigMedia
        let wanted = assetIds.filter { id in !had.contains { $0.ref == id } }
        guard !wanted.isEmpty else { return }
        Task {
            let (fetched, failed) = await PhotoLibrary.attach(assetIds: wanted)
            if !fetched.isEmpty {
                let fresh = fetched.map { item -> StoredMedia in
                    var m = item
                    m.personal = (band == .vault)
                    return m
                }
                // Normalised through the bands so a fresh item lands at the end of
                // its own run rather than after somebody else's media.
                let split = bandsOf(had + fresh)
                let media = split.shared + split.received + split.vault
                host.state.mediaBySetlist[setlist.id] = media
                await timelines.saveMedia(setlistId: setlist.id, media: media)
                refreshSuggestions(setlist)
            }
            if failed > 0 {
                host.state.error = failed == 1
                    ? "Couldn't read that one — not attached."
                    : "Couldn't read \(failed) of those — not attached."
            }
        }
    }

    /// Moves one of my items into `band`, at the end of its run — the drag
    /// between bands, and what letting go of it there means (porting
    /// Android's `moveGigMedia`).
    ///
    /// A move between bands *is* the change to its **Personal** bit; there is no
    /// separate gesture and no night-level grant above it. **Received media** is
    /// refused by `moveMedia` rather than here: whose disposition it is belongs
    /// with the rule, not with the caller.
    func moveMedia(_ mediaId: String, to band: Band) {
        guard let setlist = host.state.selectedSetlist else { return }
        let target = band == .shared ? bandsOf(host.state.gigMedia).shared.count : bandsOf(host.state.gigMedia).vault.count
        let media = StationToStation.moveMedia(host.state.gigMedia, id: mediaId, to: band, index: target)
        host.state.mediaBySetlist[setlist.id] = media
        Task { await timelines.saveMedia(setlistId: setlist.id, media: media) }
    }

    /// Removing means removing: the record goes, and so do the bytes it owned.
    func removeMedia(_ media: StoredMedia) {
        guard let setlist = host.state.selectedSetlist else { return }
        let kept = host.state.gigMedia.filter { $0.id != media.id }
        host.state.mediaBySetlist[setlist.id] = kept
        Task {
            await timelines.saveMedia(setlistId: setlist.id, media: kept)
            PhotoLibrary.deleteThumbnails(media.id)
            refreshSuggestions(setlist)
        }
    }
}
