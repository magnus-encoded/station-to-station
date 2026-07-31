import Foundation

/// One setlist song together with its Spotify match candidates and selection.
struct SongMatch: Identifiable {
    let id = UUID()
    let song: FmSong
    let searchArtist: String
    var included = true
    var loading = true
    var candidates: [SpotifyTrack] = []
    var selected: SpotifyTrack?
    var error: String?

    var isCover: Bool { song.cover != nil }
}

enum SetlistSource { case artist, user }

struct UiState {
    // Settings
    var setlistFmApiKey = ""
    var spotifyClientId = ""
    var spotifyConnected = false
    var spotifyLoginReady = false
    var setlistFmReady = false
    var bundledSpotifyClientId = false
    var bundledSetlistFmKey = false
    var grantedScope: String?
    // Search
    var artistQuery = ""
    var userQuery = ""
    var artistResults: [FmArtist] = []
    var searchLoading = false
    // Setlists
    var source: SetlistSource = .artist
    var setlistsTitle = ""
    var setlists: [FmSetlist] = []
    var setlistsPage = 1
    var setlistsTotal = 0
    var setlistsLoading = false
    // Selected setlist + matching
    var selectedSetlist: FmSetlist?
    var matches: [SongMatch] = []
    var matching = false
    var playlistName = ""
    var playlistPublic = false
    // Playlist creation
    var creatingPlaylist = false
    var createdPlaylistUrl: String?
    var createdPlaylistName = ""
    var createdTrackCount = 0
    var createdRefusedCount = 0
    // Friends (peer-to-peer, on-device)
    var mySetlistFmUser = ""
    var friends: [Friend] = []
    var sharedWith: Friend?
    // My timeline (the Spine). Facts only — the shape is derived at render time.
    var timelineShows: [FmSetlist] = []
    var festivalNames: [String: String] = [:]
    var timelineLoading = false
    /// Row keys of the Festivals uncollapsed in place. Not a screen: a Festival
    /// opens where it stands.
    var expandedFestivals: Set<String> = []
    // Transient banners
    var error: String?
    var notice: String?
}

@MainActor
final class AppModel: ObservableObject {

    @Published var state = UiState()

    let settings = Settings()
    private lazy var setlistFm = SetlistFmClient { [settings] in settings.setlistFmApiKeyValue }
    private lazy var spotify = SpotifyClient(settings)
    private let timelines = TimelineStore()

    private var matchTask: Task<Void, Never>?

    init() {
        state.setlistFmApiKey = settings.setlistFmApiKey ?? ""
        // Effective value, so Settings shows the bundled ID and lets it be
        // swapped for another app's without a rebuild.
        state.spotifyClientId = settings.spotifyClientIdValue ?? ""
        state.spotifyConnected = spotify.isConnected
        state.spotifyLoginReady = settings.spotifyClientIdValue != nil
        state.setlistFmReady = settings.setlistFmApiKeyValue != nil
        state.bundledSpotifyClientId = settings.hasBundledSpotifyClientId
        state.bundledSetlistFmKey = settings.hasBundledSetlistFmKey
        state.grantedScope = settings.grantedScope
        state.mySetlistFmUser = settings.mySetlistFmUser ?? ""
        state.friends = settings.friends
    }

    func consumeError() { state.error = nil }
    func consumeNotice() { state.notice = nil }

    // --- The timeline ---

    /// The last spine we drew, straight off disk. Called at launch so the
    /// timeline is there before any network is.
    func loadTimeline() {
        Task {
            let cache = await timelines.load()
            let me = state.mySetlistFmUser.trimmingCharacters(in: .whitespaces)
            state.festivalNames = cache.festivalNames
            state.timelineShows = cache.shows[me] ?? []
        }
    }

    /// Pulls my Attended list from setlist.fm and stores it. The reported total
    /// is stored with it: without it a restored spine looks complete at whatever
    /// page it got to.
    func refreshTimeline() {
        let me = state.mySetlistFmUser.trimmingCharacters(in: .whitespaces)
        if me.isEmpty {
            state.error = "Set your setlist.fm username first (Friends screen)."
            return
        }
        state.timelineLoading = true
        Task {
            do {
                let (shows, total) = try await setlistFm.attendedShows(me)
                state.timelineShows = shows
                state.timelineLoading = false
                await timelines.save(shows: [me: shows], attendedTotals: [me: total])
                resolveFestivalNames()
            } catch {
                state.timelineLoading = false
                fail(error)
            }
        }
    }

    /// Fills in the real Festival names for the clusters currently on the
    /// timeline — one page fetch per festival, only for ones not already
    /// resolved. Failures are silent: the venue name stays as the label.
    func resolveFestivalNames() {
        let firsts = groupIntoFestivals(state.timelineShows)
            .compactMap { node -> FmSetlist? in
                guard node.isFestival, let first = node.shows.first else { return nil }
                return first
            }
            .filter { state.festivalNames[$0.id] == nil && $0.url?.nilIfBlank != nil }
        if firsts.isEmpty { return }
        Task {
            var found: [String: String] = [:]
            for show in firsts {
                if let name = await setlistFm.festivalName(setlistURL: show.url!) {
                    found[show.id] = name
                }
            }
            if found.isEmpty { return }
            state.festivalNames.merge(found) { _, new in new }
            // A festival name costs a fetch each; store them so it's paid once.
            await timelines.save(festivalNames: found)
        }
    }

    /// A Festival uncollapses in place — it never pushes a screen.
    func toggleFestival(_ key: String) {
        if state.expandedFestivals.contains(key) {
            state.expandedFestivals.remove(key)
        } else {
            state.expandedFestivals.insert(key)
        }
    }

    private func fail(_ error: Error) {
        state.error = userMessage(error)
        state.searchLoading = false
        state.setlistsLoading = false
        state.creatingPlaylist = false
    }

    // --- Settings ---

    func saveSettings(apiKey: String, clientId: String) {
        settings.saveSetlistFmApiKey(apiKey)
        settings.saveSpotifyClientId(clientId)
        state.setlistFmApiKey = apiKey.trimmingCharacters(in: .whitespaces)
        state.spotifyClientId = clientId.trimmingCharacters(in: .whitespaces)
        state.spotifyLoginReady = settings.spotifyClientIdValue != nil
        state.setlistFmReady = settings.setlistFmApiKeyValue != nil
    }

    func loginSpotify() {
        Task {
            do {
                try await spotify.login()
                state.spotifyConnected = true
                state.grantedScope = settings.grantedScope
            } catch {
                fail(error)
            }
        }
    }

    func disconnectSpotify() {
        settings.clearSpotifyAuth()
        state.spotifyConnected = false
        state.grantedScope = nil
    }

    // --- Friends (peer-to-peer) ---

    func saveMySetlistFmUser(_ username: String) {
        let trimmed = username.trimmingCharacters(in: .whitespaces)
        settings.saveMySetlistFmUser(trimmed)
        state.mySetlistFmUser = trimmed
    }

    /// My shareable identity card, or nil until I've set my setlist.fm username.
    func myCardURL() async -> URL? {
        let me = state.mySetlistFmUser.trimmingCharacters(in: .whitespaces)
        if me.isEmpty { return nil }
        let user = try? await spotify.currentUser()
        return Friend(setlistfm: me,
                      name: user?.displayName?.nilIfBlank ?? me,
                      spotifyId: user?.id).shareURL
    }

    func addFriend(_ friend: Friend) {
        // De-dupe on setlist.fm username; a re-share updates the display name.
        let next = state.friends.filter { $0.setlistfm.lowercased() != friend.setlistfm.lowercased() } + [friend]
        settings.saveFriends(next)
        state.friends = next
    }

    func addFriendByUsername(_ username: String) {
        let u = username.trimmingCharacters(in: .whitespaces)
        if !u.isEmpty { addFriend(Friend(setlistfm: u)) }
    }

    func handleFriendLink(_ url: URL) {
        if let friend = friendFromURL(url) { addFriend(friend) }
    }

    func removeFriend(_ friend: Friend) {
        let next = state.friends.filter { $0.setlistfm != friend.setlistfm }
        settings.saveFriends(next)
        state.friends = next
    }

    /// Fetches attended concerts for one user across up to `maxPages` pages.
    private func attendedConcerts(_ userId: String, maxPages: Int) async throws -> [FmSetlist] {
        var all: [FmSetlist] = []
        for page in 1...maxPages {
            let resp = try await setlistFm.userAttended(userId, page: page)
            all += resp.setlist
            if all.count >= resp.total || resp.setlist.isEmpty { break }
        }
        return all
    }

    /// Loads the concerts both `friend` and I attended into the setlists list, so
    /// the existing SetlistsView renders them and tapping one flows into the
    /// normal confirm → create-playlist path.
    func openSharedConcerts(_ friend: Friend) {
        let me = state.mySetlistFmUser.trimmingCharacters(in: .whitespaces)
        if me.isEmpty {
            state.error = "Set your setlist.fm username first (Friends screen)."
            return
        }
        state.sharedWith = friend
        state.source = .user // shared list mixes artists; show "date · artist"
        state.setlistsTitle = "You & \(friend.name)"
        state.setlists = []
        state.setlistsPage = 1
        state.setlistsTotal = 0
        state.setlistsLoading = true
        Task {
            do {
                // ponytail: caps at 60 concerts each. Bump maxPages or cache per
                // user if power users miss older overlaps.
                let mine = try await attendedConcerts(me, maxPages: 3)
                let theirs = Set(try await attendedConcerts(friend.setlistfm, maxPages: 3).map(\.id))
                let shared = mine.filter { theirs.contains($0.id) }
                // total == count so loadMoreSetlists() won't try to paginate this list.
                state.setlists = shared
                state.setlistsTotal = shared.count
                state.setlistsLoading = false
            } catch {
                fail(error)
            }
        }
    }

    /// Discovers a friend from a Spotify playlist link they shared: reads the
    /// playlist's description, and if it carries a setlist.fm stamp, adds the owner.
    func discoverFriendFromPlaylist(_ link: String) {
        guard let id = spotifyPlaylistId(link) else {
            state.error = "That doesn't look like a Spotify playlist link."
            return
        }
        Task {
            do {
                let playlist = try await spotify.getPlaylist(id)
                let username = sfmUserFromDescription(playlist.description)
                let ownerId = playlist.owner?.id
                let me = try? await spotify.currentUser().id
                if username == nil {
                    state.error = "That playlist wasn't made with this app, so there's no setlist.fm user to add."
                } else if let ownerId, ownerId == me {
                    state.notice = "That's your own playlist."
                } else {
                    addFriend(Friend(setlistfm: username!,
                                     name: playlist.owner?.displayName?.nilIfBlank ?? username!,
                                     spotifyId: ownerId))
                    state.notice = "Added @\(username!) as a friend."
                }
            } catch {
                fail(error)
            }
        }
    }

    // --- Search ---

    func setArtistQuery(_ q: String) { state.artistQuery = q }
    func setUserQuery(_ q: String) { state.userQuery = q }

    func searchArtists() {
        let query = state.artistQuery.trimmingCharacters(in: .whitespaces)
        if query.isEmpty { return }
        state.searchLoading = true
        Task {
            do {
                let result = try await setlistFm.searchArtists(query)
                state.artistResults = result.artist
                state.searchLoading = false
            } catch {
                fail(error)
            }
        }
    }

    func openArtist(_ artist: FmArtist) {
        state.source = .artist
        state.setlistsTitle = artist.name
        state.setlists = []
        state.setlistsPage = 1
        state.setlistsTotal = 0
        state.setlistsLoading = true
        Task {
            do {
                let result = try await setlistFm.artistSetlists(artist.mbid)
                state.setlists = result.setlist
                state.setlistsTotal = result.total
                state.setlistsLoading = false
            } catch {
                fail(error)
            }
        }
    }

    func openUserAttended() {
        let userId = state.userQuery.trimmingCharacters(in: .whitespaces)
        if userId.isEmpty { return }
        // "My concerts" is your own username; adopt it as the identity used to
        // stamp playlists and find shared concerts — but never clobber an
        // explicit choice.
        if state.mySetlistFmUser.trimmingCharacters(in: .whitespaces).isEmpty {
            saveMySetlistFmUser(userId)
        }
        state.source = .user
        state.setlistsTitle = "Attended by \(userId)"
        state.setlists = []
        state.setlistsPage = 1
        state.setlistsTotal = 0
        state.setlistsLoading = true
        Task {
            do {
                let result = try await setlistFm.userAttended(userId)
                state.setlists = result.setlist
                state.setlistsTotal = result.total
                state.setlistsLoading = false
            } catch {
                fail(error)
            }
        }
    }

    func loadMoreSetlists() {
        let s = state
        if s.setlistsLoading || s.setlists.count >= s.setlistsTotal { return }
        let nextPage = s.setlistsPage + 1
        state.setlistsLoading = true
        Task {
            do {
                let result: SetlistsResponse
                switch s.source {
                case .user:
                    result = try await setlistFm.userAttended(s.userQuery.trimmingCharacters(in: .whitespaces), page: nextPage)
                case .artist:
                    guard let mbid = s.setlists.first?.artist?.mbid else {
                        throw AppError("No artist context")
                    }
                    result = try await setlistFm.artistSetlists(mbid, page: nextPage)
                }
                state.setlists += result.setlist
                state.setlistsPage = nextPage
                state.setlistsTotal = result.total
                state.setlistsLoading = false
            } catch {
                fail(error)
            }
        }
    }

    // --- Matching ---

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
        var defaultName = artistName.isEmpty ? "Setlist" : artistName
        if let v = setlist.venue?.name { defaultName += " – \(v)" }
        if let d = setlist.eventDate { defaultName += " – \(d)" }

        state.selectedSetlist = setlist
        state.matches = matches
        state.matching = true
        state.playlistName = defaultName
        state.createdPlaylistUrl = nil

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
            state.matching = false
        }
    }

    private func findCandidates(_ track: String, _ artist: String) async -> ([SpotifyTrack], String?) {
        do {
            var results = try await spotify.searchTracks("track:\"\(track)\" artist:\"\(artist)\"")
            if results.isEmpty {
                results = try await spotify.searchTracks("\(track) \(artist)")
            }
            return (results, nil)
        } catch {
            return ([], userMessage(error))
        }
    }

    private func updateMatch(_ index: Int, _ transform: (inout SongMatch) -> Void) {
        guard state.matches.indices.contains(index) else { return }
        transform(&state.matches[index])
    }

    func toggleIncluded(_ index: Int) { updateMatch(index) { $0.included.toggle() } }

    func chooseCandidate(_ index: Int, _ track: SpotifyTrack) {
        updateMatch(index) { $0.selected = track; $0.included = true }
    }

    func setPlaylistName(_ name: String) { state.playlistName = name }
    func setPlaylistPublic(_ isPublic: Bool) { state.playlistPublic = isPublic }

    /// Dismisses the "playlist created" result so it isn't shown again.
    func dismissCreated() { state.createdPlaylistUrl = nil }

    /// Manual re-search for one song with a user-provided query.
    func researchSong(_ index: Int, _ query: String) {
        if query.trimmingCharacters(in: .whitespaces).isEmpty { return }
        updateMatch(index) { $0.loading = true; $0.error = nil }
        Task {
            do {
                let results = try await spotify.searchTracks(query.trimmingCharacters(in: .whitespaces), limit: 10)
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

    // --- Playlist creation ---

    func createPlaylist() {
        let s = state
        let tracks = s.matches.filter { $0.included && $0.selected != nil }.compactMap(\.selected)
        if tracks.isEmpty {
            state.error = "No songs selected"
            return
        }
        let name = s.playlistName.isEmpty ? "Setlist" : s.playlistName
        state.creatingPlaylist = true
        Task {
            do {
                // Unknown scope means the login predates scope tracking — the
                // remedy is the same as a missing scope: a fresh login.
                if spotify.hasPlaylistScopes() != true {
                    throw AppError("Your Spotify login is missing playlist permissions. "
                        + "Log out in Settings, then log in again and approve the playlist "
                        + "access on the Spotify page that opens.")
                }
                var description = "Setlist"
                if let venue = s.selectedSetlist?.venueLine() { description += " at \(venue)" }
                if let date = s.selectedSetlist?.eventDate { description += " on \(date)" }
                description += ". Created from setlist.fm"
                if let url = s.selectedSetlist?.url { description += ": \(url)" }
                // Stamp the creator so a friend's app can discover the mapping.
                let me = s.mySetlistFmUser.trimmingCharacters(in: .whitespaces)
                if !me.isEmpty { description += " \(sfmStamp(me))" }

                let playlist = try await spotify.createPlaylist(name: name, description: description, isPublic: s.playlistPublic)
                let result: AddTracksResult
                do {
                    result = try await spotify.addTracks(playlist.id, uris: tracks.map(\.uri))
                } catch {
                    // The playlist exists at this point, so say so rather than
                    // leaving the user with a bare failure and a stray playlist.
                    throw AppError("Playlist \"\(name)\" was created but the songs could not be added. \(userMessage(error))")
                }
                state.creatingPlaylist = false
                state.createdPlaylistUrl = playlist.externalUrls["spotify"]
                state.createdPlaylistName = name
                state.createdTrackCount = result.added
                state.createdRefusedCount = result.refused.count
            } catch {
                fail(error)
            }
        }
    }
}
