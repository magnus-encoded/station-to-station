import Foundation

protocol PlaylistSpotify: AnyObject {
    func hasPlaylistScopes() -> Bool?
    func hasImageUploadScope() -> Bool
    func searchTracks(_ query: String, limit: Int) async throws -> [SpotifyTrack]
    func createPlaylist(name: String, description: String, isPublic: Bool) async throws -> PlaylistResponse
    func addTracks(_ playlistId: String, uris: [String]) async throws -> AddTracksResult
    func uploadCover(_ playlistId: String, jpeg: Data) async throws
    func getPlaylist(_ playlistId: String) async throws -> SimplePlaylist
    func currentUser() async throws -> SpotifyUser
}

extension SpotifyClient: PlaylistSpotify {}

@MainActor
final class PlaylistController {
    private let host: StateHost
    private let spotify: PlaylistSpotify
    private let timelines: TimelineStore
    private let loadGigMedia: (FmSetlist) -> Void
    private let loadGigLog: (FmSetlist) -> Void
    private let addFriend: (Friend) -> Void

    private var matchTask: Task<Void, Never>?

    init(host: StateHost, spotify: PlaylistSpotify, timelines: TimelineStore,
         loadGigMedia: @escaping (FmSetlist) -> Void,
         loadGigLog: @escaping (FmSetlist) -> Void,
         addFriend: @escaping (Friend) -> Void) {
        self.host = host
        self.spotify = spotify
        self.timelines = timelines
        self.loadGigMedia = loadGigMedia
        self.loadGigLog = loadGigLog
        self.addFriend = addFriend
    }

    /// Discovers a friend from a Spotify playlist link they shared: reads the
    /// playlist's description, and if it carries a setlist.fm stamp, adds the owner.
    func discoverFriendFromPlaylist(_ link: String) {
        guard let id = spotifyPlaylistId(link) else {
            host.state.error = "That doesn't look like a Spotify playlist link."
            host.state.errorKind = nil
            return
        }
        Task {
            do {
                let playlist = try await spotify.getPlaylist(id)
                let username = sfmUserFromDescription(playlist.description)
                let ownerId = playlist.owner?.id
                let me = try? await spotify.currentUser().id
                if username == nil {
                    host.state.error = "That playlist wasn't made with this app, so there's no setlist.fm user to add."
                    host.state.errorKind = nil
                } else if let ownerId, ownerId == me {
                    host.state.notice = "That's your own playlist."
                } else {
                    addFriend(Friend(setlistfm: username!,
                                     name: playlist.owner?.displayName?.nilIfBlank ?? username!,
                                     spotifyId: ownerId))
                    host.state.notice = "Added @\(username!) as a friend."
                }
            } catch {
                host.fail(error)
            }
        }
    }

    func selectSetlist(_ setlist: FmSetlist) {
        matchTask?.cancel()
        let artistName = setlist.artist?.name ?? ""
        let matches = setlist.songs()
            .filter { !$0.name.trimmingCharacters(in: .whitespaces).isEmpty }
            .map { song in
                SongMatch(song: song,
                          searchArtist: song.cover?.name ?? artistName,
                          // Tape songs are intro/outro recordings, not performed live; excluded by default.
                          included: !song.tape)
            }
        // Year – Artist – Where. The rule itself is the logic layer's, asserted by
        // the same cases on both platforms — it is the one that drifted before.
        let defaultName = TimelineLogic.playlistName(
            for: setlist, mine: host.state.timelineShows, festivals: host.state.festivals
        )

        host.state.selectedSetlist = setlist
        // Answered twice on purpose: now from the two lists, which are already in
        // hand, and again in `loadGigMedia` once the store hands back the attendance
        // claim. Starting at the answer the lists give rather than at `false` is what
        // keeps a night that is plainly mine from drawing itself read-only for a frame.
        markSelectedOwnership(setlist, attendance: nil)
        loadGigMedia(setlist)
        loadGigLog(setlist)
        host.state.matches = matches
        host.state.matching = true
        host.state.playlistName = defaultName
        host.state.createdPlaylistUrl = nil
        host.state.coverCandidateIds = []
        host.state.selectedCoverAssetId = nil
        host.state.selectedCoverFrameMs = 0
        host.state.coverSearched = false
        host.state.coverUploadError = nil
        loadCoverCandidates(setlist)

        matchTask = Task {
            for (index, match) in matches.enumerated() {
                if Task.isCancelled { return }
                let (candidates, error) = await findCandidates(match.song.name, match.searchArtist)
                updateMatch(index) {
                    $0.loading = false
                    $0.candidates = candidates
                    $0.selected = candidates.first
                    $0.included = $0.included && !candidates.isEmpty
                    $0.error = error
                }
                // Stay polite with the Spotify search API.
                try? await Task.sleep(nanoseconds: 120_000_000)
            }
            host.state.matching = false
        }
    }

    /// Whether the open night is mine, through the one rule (#327) — never re-derived
    /// at a call site, because the direction a second implementation would drift is
    /// offering an edit on someone else's night.
    func markSelectedOwnership(_ setlist: FmSetlist, attendance: StoredAttendance?) {
        host.state.selectedIsMine = isMyNight(
            setlist.id,
            attendance: attendance,
            mine: host.state.timelineShows,
            planned: host.state.plannedGigs
        )
    }

    /// Offers the gig's own keepsakes first — already chosen for this night, so
    /// they need no permission and no re-asking — then the gallery's same-night
    /// match once that permission is granted. The gallery half is silent when
    /// missing: the confirm screen asks for it instead, so a prompt only ever
    /// follows a tap.
    private func loadCoverCandidates(_ setlist: FmSetlist) {
        guard let date = setlist.eventDate, let window = photoWindow(gigDate: date) else { return }
        let granted = PhotoLibrary.isAuthorized
        host.state.coverPermissionGranted = granted
        host.state.coverLoading = true
        Task {
            // Clips included: a night whose only capture is a clip has a cover in it,
            // one frame at a time (`CoverFrameSheet`). Pictures only, though — a Note
            // holds no bytes and a dead reference resolves to nothing, and neither is
            // a picture of the night.
            let pinned = host.state.gigMedia
                .filter { $0.kind == StoredMedia.Kind.photo || $0.kind == StoredMedia.Kind.video }
                .map(\.ref)
            let gallery = granted ? await Task.detached { PhotoLibrary.assetsFromNight(window) }.value : []
            var seen = Set<String>()
            let candidates = (pinned + gallery).filter { seen.insert($0).inserted }
            guard host.state.selectedSetlist?.id == setlist.id else { return }
            host.state.coverCandidateIds = candidates
            host.state.coverLoading = false
            host.state.coverSearched = true
            // The first photo is the suggestion, so it is the cover until the
            // picker is swiped somewhere else.
            host.state.selectedCoverAssetId = candidates.first
        }
    }

    /// The cover the picker has landed on, or nil for Spotify's own collage.
    func setCover(_ assetId: String?) {
        guard host.state.selectedCoverAssetId != assetId else { return }
        host.state.selectedCoverAssetId = assetId
        host.state.selectedCoverFrameMs = 0
    }

    /// The frame of the clip the scrub has settled on.
    func setCoverFrame(_ atMs: Int64) {
        if host.state.selectedCoverFrameMs != atMs { host.state.selectedCoverFrameMs = atMs }
    }

    /// Re-runs the cover search after the gallery permission prompt the picker
    /// itself triggered — the rest of the confirm screen (matches, playlist name)
    /// is untouched.
    func refreshCoverCandidates() {
        guard let setlist = host.state.selectedSetlist else { return }
        loadCoverCandidates(setlist)
    }

    /// Returns nil on success, or the reason the cover did not make it.
    private func uploadCover(playlistId: String, assetId: String) async -> String? {
        guard spotify.hasImageUploadScope() else {
            return "The cover needs a permission your Spotify login predates. "
                + "Log out in Settings and log in again to enable playlist covers."
        }
        guard let jpeg = await PhotoLibrary.coverJpeg(assetId: assetId,
                                                      frameMs: host.state.selectedCoverFrameMs) else {
            return "That photo could not be prepared as a cover."
        }
        do {
            try await spotify.uploadCover(playlistId, jpeg: jpeg)
            return nil
        } catch {
            return "The cover could not be uploaded. \(userMessage(error))"
        }
    }

    private func findCandidates(_ track: String, _ artist: String) async -> ([SpotifyTrack], String?) {
        do {
            var results = try await spotify.searchTracks("track:\"\(track)\" artist:\"\(artist)\"", limit: 10)
            if results.isEmpty {
                results = try await spotify.searchTracks("\(track) \(artist)", limit: 10)
            }
            // Best-first rather than Spotify-first: the auto-selection below takes
            // the head of this list, and the picker lists them in this order too.
            return (rankCandidates(results, track, artist), nil)
        } catch {
            return ([], userMessage(error))
        }
    }

    private func updateMatch(_ index: Int, _ transform: (inout SongMatch) -> Void) {
        guard host.state.matches.indices.contains(index) else { return }
        transform(&host.state.matches[index])
    }

    func toggleIncluded(_ index: Int) { updateMatch(index) { $0.included.toggle() } }

    func chooseCandidate(_ index: Int, _ track: SpotifyTrack) {
        updateMatch(index) { $0.selected = track; $0.included = true }
    }

    func setPlaylistName(_ name: String) { host.state.playlistName = name }
    func setPlaylistPublic(_ isPublic: Bool) { host.state.playlistPublic = isPublic }

    /// Dismisses the "playlist created" result so it isn't shown again.
    func dismissCreated() { host.state.createdPlaylistUrl = nil }

    /// Manual re-search for one song with a user-provided query.
    func researchSong(_ index: Int, _ query: String) {
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty { return }
        updateMatch(index) { $0.loading = true; $0.error = nil }
        Task {
            do {
                let found = try await spotify.searchTracks(trimmed, limit: 10)
                // Ranked like the automatic search, or searching by hand would be
                // the one path that still hands you Spotify's karaoke rendition.
                // The query is the user's, but which recording we mean is still
                // this song by this artist.
                let songName = host.state.matches.indices.contains(index) ? host.state.matches[index].song.name : trimmed
                let artist = host.state.matches.indices.contains(index) ? host.state.matches[index].searchArtist : ""
                let results = rankCandidates(found, songName, artist)
                updateMatch(index) {
                    $0.loading = false
                    $0.candidates = results
                    $0.selected = results.first ?? $0.selected
                    $0.error = results.isEmpty ? "No results for \"\(query)\"" : nil
                }
            } catch {
                updateMatch(index) { $0.loading = false; $0.error = userMessage(error) }
            }
        }
    }

    func createPlaylist() {
        let s = host.state
        let tracks = s.matches.filter { $0.included && $0.selected != nil }.compactMap(\.selected)
        if tracks.isEmpty {
            host.state.error = "No songs selected"
            host.state.errorKind = nil
            return
        }
        let name = s.playlistName.isEmpty ? "Setlist" : s.playlistName
        host.state.creatingPlaylist = true
        Task {
            do {
                var description = "Setlist"
                if let venue = s.selectedSetlist?.venueLine() { description += " at \(venue)" }
                if let date = s.selectedSetlist?.eventDate { description += " on \(date)" }
                description += ". Created from setlist.fm"
                if let url = s.selectedSetlist?.url { description += ": \(url)" }
                // Stamp the creator so a friend's app can discover the mapping.
                let me = s.mySetlistFmUser.trimmingCharacters(in: .whitespaces)
                if !me.isEmpty { description += " \(sfmStamp(me))" }

                let made = try await exportPlaylist(tracks: tracks, name: name, description: description,
                                                    isPublic: s.playlistPublic, coverAssetId: s.selectedCoverAssetId)
                if let night = s.selectedSetlist?.id.nilIfBlank {
                    host.state.playlistsBySetlist[night, default: []].append(made)
                    await timelines.save(playlists: [night: made])
                }
            } catch {
                host.state.creatingPlaylist = false
                host.fail(error)
            }
        }
    }

    /// Exports supplied songs with the same matching and creation path as the picker.
    /// The caller holds the receipt when there is no lasting Gig to attach it to.
    func exportSetlist(_ setlist: FmSetlist, name: String, description: String) async throws -> StoredPlaylist {
        var tracks: [SpotifyTrack] = []
        for song in setlist.songs() where !song.tape && song.name.nilIfBlank != nil {
            try Task.checkCancellation()
            let (candidates, error) = await findCandidates(song.name, song.cover?.name ?? setlist.artist?.name ?? "")
            if let error { throw AppError(error) }
            if let track = candidates.first { tracks.append(track) }
            try await Task.sleep(nanoseconds: 120_000_000)
        }
        try Task.checkCancellation()
        return try await exportPlaylist(tracks: tracks, name: name, description: description,
                                        isPublic: true, coverAssetId: nil)
    }

    private func exportPlaylist(tracks: [SpotifyTrack], name: String, description: String,
                                isPublic: Bool, coverAssetId: String?) async throws -> StoredPlaylist {
        guard !tracks.isEmpty else { throw AppError("No songs selected") }
        // Unknown scope means a login predates scope tracking and needs fresh consent too.
        guard spotify.hasPlaylistScopes() == true else {
            throw AppError("Your Spotify login is missing playlist permissions. "
                + "Log out in Settings, then log in again and approve the playlist "
                + "access on the Spotify page that opens.")
        }
        let playlist = try await spotify.createPlaylist(name: name, description: description, isPublic: isPublic)
        let result: AddTracksResult
        do {
            result = try await spotify.addTracks(playlist.id, uris: tracks.map(\.uri))
        } catch {
            throw AppError("Playlist \"\(name)\" was created but the songs could not be added. \(userMessage(error))")
        }
        let coverError: String?
        if let coverAssetId {
            coverError = await uploadCover(playlistId: playlist.id, assetId: coverAssetId)
        } else {
            coverError = nil
        }
        let url = playlist.externalUrls["spotify"] ?? "https://open.spotify.com/playlist/\(playlist.id)"
        host.state.creatingPlaylist = false
        host.state.createdPlaylistUrl = url
        host.state.createdPlaylistName = name
        host.state.createdTrackCount = result.added
        host.state.createdRefusedCount = result.refused.count
        host.state.coverUploadError = coverError
        return StoredPlaylist(url: url, name: name, trackCount: result.added)
    }

    /// Drops one playlist link from a night.
    ///
    /// For a playlist deleted on Spotify, where the pointer left behind is dead
    /// weight. It removes the *link*, never the night — which is why it is a separate
    /// door from `deleteGig` and not a step inside it.
    func removePlaylist(_ setlistId: String, url: String) {
        host.state.playlistsBySetlist[setlistId] =
            (host.state.playlistsBySetlist[setlistId] ?? []).filter { $0.url != url }
        Task { await timelines.removePlaylist(setlistId: setlistId, url: url) }
    }
}
