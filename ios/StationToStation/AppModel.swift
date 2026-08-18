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
    /// Friends' attended shows by setlist.fm username, drawn as Lanes when zoomed
    /// out. Kept apart from `timelineShows` (mine) so ownership is never read off
    /// the node holding a gig.
    var showsByFriend: [String: [FmSetlist]] = [:]
    /// The Timelines resolution: the strip of friends' Lanes opened beside my
    /// Spine, in place. Not a screen — pinch toggles it.
    var zoomedOut = false
    var festivalNames: [String: String] = [:]
    var timelineLoading = false
    /// Distinguishes "no Lanes yet" from "Lanes arriving" when the strip opens.
    var lanesLoading = false
    /// Row keys of the Festivals uncollapsed in place. Not a screen: a Festival
    /// opens where it stands.
    var expandedFestivals: Set<String> = []
    /// The selected night's media (#97), in the order it was attached. Only the
    /// open **Gig**'s: the grid is the one thing that reads it, and a night at a
    /// time is what it needs.
    var gigMedia: [StoredMedia] = []
    /// Asset ids the library holds from that night's window and this gig has not
    /// attached — the suggestion the grid offers before the picker is opened.
    var gigMediaSuggestions: [String] = []
    /// The open **Gig**'s attendance claim (#174/#29) — whether, and how, I'm
    /// known to have been there. Loaded alongside the gig's media so the header
    /// badge has something to read.
    var selectedAttendance: StoredAttendance?
    /// A gig a location fix just placed me at, tonight — "Are you here?" (#174).
    /// Nil until `offerCheckIn` finds one; presenting it is the whole of the ask.
    var checkInOffer: FmSetlist?
    // Cover art (#178): the gig's own attached photos first, then the same-night
    // gallery match, offered as a playlist cover.
    var coverCandidateIds: [String] = []
    var coverLoading = false
    var coverSearched = false
    var coverPermissionGranted = false
    /// nil means Spotify's own album-art collage.
    var selectedCoverAssetId: String?
    var coverUploadError: String?
    /// The light switch (#180): my own Line, drawn as a Contact sees it. Global,
    /// not persisted, not per-night — the same flag the timeline and the gig
    /// screen both read, ported term for term from Android's `contactLight`.
    var contactLight = false
    /// Inside the light: also show what is being withheld, as placeholders never
    /// re-rendering the actual content. Reset whenever the light is toggled, so
    /// it always comes on faithful.
    var showWithheld = false
    /// The selected night's own **Log** (#169): what I saw, as opposed to what
    /// setlist.fm publishes. Only the open **Gig**'s, same reasoning as `gigMedia`.
    var gigLog = StoredLog()
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
    /// The device half of the Timeline (ADR-0001): the store, the client, the
    /// bundle. Held as the concrete type because seeding a fixture is an iOS-only
    /// entry point that the shared logic layer only ever *reads* the result of.
    private lazy var plumbing = DeviceTimelinePlumbing(store: timelines, client: setlistFm)
    /// The shared half: the sequence and the rules, testable because the plumbing
    /// above is handed in rather than constructed inside it.
    private lazy var logic = TimelineLogic(plumbing: plumbing)
    private lazy var location = DeviceLocation()

    private var matchTask: Task<Void, Never>?
    /// One-shot per launch: dismissing an offer must not make it reappear (#174).
    private var askedToCheckIn = false

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

        // CI (and a URL bar) seed a Resolution here: `-seedFixture <name>` on the
        // launch line. UserDefaults maps `-key value` argv automatically, so no
        // `simctl openurl` — which pops a system "Open in app?" prompt that blocks
        // the URL from ever reaching us — is needed.
        if let fixture = UserDefaults.standard.string(forKey: "seedFixture")?.nilIfBlank {
            loadFixture(fixture, open: UserDefaults.standard.bool(forKey: "seedOpen"))
        }

        // Refusing the location prompt is not a dead end and not an error: the
        // ambient offer just never appears, and the gig's own screen still has
        // a check-in you can press by hand.
        location.onAuthorizationChanged = { [weak self] in self?.offerCheckIn() }
    }

    func consumeError() { state.error = nil }
    func consumeNotice() { state.notice = nil }

    // --- The timeline ---

    /// The Spine for this run, put on screen. Called at launch so the timeline is
    /// there before any network is.
    ///
    /// Which source it comes from — a launch-seeded fixture or the stored cache —
    /// and the retry of unresolved **Festival** names that follows are both the
    /// logic layer's call now (`TimelineLogic.loadSpine`), which is what makes
    /// them assertable without a device. The closure runs once for the Spine and
    /// again if the retry found anything.
    func loadTimeline() {
        let me = state.mySetlistFmUser.trimmingCharacters(in: .whitespaces)
        Task {
            await logic.loadSpine(me: me) { spine in
                state.timelineShows = spine.mine
                state.festivalNames = spine.festivalNames
            }
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

    /// Fills in the real **Festival** names for the clusters currently on the
    /// timeline. The rule itself (which clusters, what counts as unresolved, and
    /// that the answers are stored) lives in the logic layer; this is the
    /// after-a-fresh-import caller of it.
    func resolveFestivalNames() {
        let mine = state.timelineShows
        let known = state.festivalNames
        Task {
            let found = await logic.resolveFestivalNames(mine: mine, known: known)
            if found.isEmpty { return }
            state.festivalNames.merge(found) { _, new in new }
        }
    }

    /// Pinch out to open the friends' Lanes beside my Spine, pinch in to close
    /// them. Nothing navigates — the same one Timeline, at a different Resolution.
    func setZoomedOut(_ v: Bool) {
        if v && state.friends.isEmpty { return }
        state.zoomedOut = v
    }

    /// Flip the light switch: my own Line, as a Contact sees it. Always comes on
    /// faithful — withheld items stay hidden until asked for again.
    func toggleContactLight() {
        state.contactLight.toggle()
        state.showWithheld = false
    }

    func setShowWithheld(_ v: Bool) {
        state.showWithheld = v
    }

    /// Fetches whichever Followed Lanes are stale (missing, empty, or not back
    /// to my own oldest Gig) and merges them in. Called when the strip opens —
    /// a cached-and-complete Lane costs nothing here. One friend's failure
    /// keeps their last good Lane and never blocks the others. Ported term for
    /// term from Android's `loadFriendTimelines`.
    func loadFriendTimelines() {
        let friends = state.friends
        if friends.isEmpty { return }
        let myOldest = state.timelineShows.compactMap { $0.localDate() }.min()
        let stale = friends.filter { laneIsStale(state.showsByFriend[$0.setlistfm], oldestOfMine: myOldest) }
        if stale.isEmpty { return }
        state.lanesLoading = true
        Task {
            var loaded: [String: [FmSetlist]] = [:]
            for friend in stale {
                let shows = try? await setlistFm.attendedShows(friend.setlistfm, backTo: myOldest).shows
                if let shows, !shows.isEmpty { loaded[friend.setlistfm] = shows }
            }
            state.showsByFriend.merge(loaded) { _, new in new }
            state.lanesLoading = false
            await timelines.save(shows: loaded)
        }
    }

    /// Seeds the Timeline from a bundled weave fixture (`fixtures/weave/<name>`).
    /// The only way CI and a URL bar can reach a populated Spine without a live
    /// setlist.fm import and without a pinch — the fixture carries who is mine,
    /// the Lanes in order, and every show. `open` uncollapses the Festivals so a
    /// festival-open Resolution can be photographed too.
    func loadFixture(_ name: String, open: Bool) {
        // Registering the fixture with the plumbing is what makes it the Spine
        // for this run: the logic layer prefers it over the stored cache from
        // then on, so the (empty in CI) cache can no longer clobber it when the
        // view appears. Synchronous, so the `onAppear` load cannot beat it.
        guard let spine = plumbing.seed(fixture: name) else {
            state.error = "Fixture \"\(name)\" not bundled."
            return
        }
        state.mySetlistFmUser = spine.me
        state.friends = spine.friends
        state.timelineShows = spine.mine
        state.showsByFriend = spine.byFriend
        state.festivalNames = spine.festivalNames
        // A fixture with Lanes is a Timelines-resolution scenario; one without is
        // My-timeline. Either way the shape is derived, never stored.
        state.zoomedOut = !spine.friends.isEmpty
        state.timelineLoading = false
        let rows = weaveTimelines(
            mine: state.timelineShows, festivalNames: state.festivalNames,
            friends: spine.friends, theirs: state.showsByFriend
        )
        state.expandedFestivals = open
            ? Set(rows.filter { $0.node.isFestival }.map(\.key))
            : []
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

    /// Stand-in for the Ed25519 identity (#28) until it exists: 32 random bytes,
    /// base64, fresh per launch — the same thing Android's `sessionKey` is.
    private let sessionKey = Data((0..<32).map { _ in UInt8.random(in: 0...255) })
        .base64EncodedString()

    /// The same card as a BLE payload: adds the public key #28 makes the identity.
    /// Nil until I've set my username — a blank card is nothing to hand over.
    func myProbeCard() -> ProbeCard? {
        guard let me = state.mySetlistFmUser.trimmingCharacters(in: .whitespaces).nilIfBlank
        else { return nil }
        return ProbeCard(name: me, publicKey: sessionKey, setlistfm: me)
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

    /// A gig invite a contact sent: open the night it names, and keep it if I do not
    /// already hold it (#179).
    ///
    /// Reaches for the night on my own line first and only asks setlist.fm when it is
    /// not there — an invite to a gig we both attended is the common case, and it
    /// should not cost a request or fail without signal. A night already on my line is
    /// **already held**, so it is opened and nothing is written: minting is for the
    /// invite that brings a night I did not have.
    ///
    /// A fetched night is stored as **planned**, which is what an invite means. That is
    /// safe on a night I turn out to have attended: `savePlanned` never downgrades a
    /// claim, so an existing check-in survives being invited to its own gig.
    ///
    /// Reports nothing on failure: an invite for a night setlist.fm cannot serve is a
    /// dead link, and a banner about it would be telling the reader about the sender's
    /// problem.
    func handleGigInvite(_ url: URL, onOpen: @escaping () -> Void) {
        guard let id = gigIdFromInvite(url) else { return }
        if let known = state.timelineShows.first(where: { $0.id == id }) {
            state.selectedSetlist = known
            loadGigMedia(known)
            onOpen()
            return
        }
        Task {
            guard let fetched = try? await setlistFm.setlist(id) else { return }
            await timelines.savePlanned(fetched)
            state.selectedSetlist = fetched
            loadGigMedia(fetched)
            onOpen()
        }
    }

    func removeFriend(_ friend: Friend) {
        let next = state.friends.filter { $0.setlistfm != friend.setlistfm }
        settings.saveFriends(next)
        state.friends = next
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
                // The intersection and its paging cap are the logic layer's; see
                // TimelineLogic.attendedPageCap for what raising it would cost.
                let shared = try await logic.sharedConcerts(me: me, friend: friend.setlistfm)
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
        // Year – Artist – Where. The rule itself is the logic layer's, asserted by
        // the same cases on both platforms — it is the one that drifted before.
        let defaultName = TimelineLogic.playlistName(
            for: setlist, mine: state.timelineShows, festivalNames: state.festivalNames
        )

        state.selectedSetlist = setlist
        loadGigMedia(setlist)
        state.gigLog = timelines.log(setlistId: setlist.id)
        state.matches = matches
        state.matching = true
        state.playlistName = defaultName
        state.createdPlaylistUrl = nil
        state.coverCandidateIds = []
        state.selectedCoverAssetId = nil
        state.coverSearched = false
        state.coverUploadError = nil
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
            state.matching = false
        }
    }

    // --- Media on a night (#99) ---

    /// What this night already holds, plus what the library says was shot that
    /// night and is not attached yet.
    private func loadGigMedia(_ setlist: FmSetlist) {
        state.gigMedia = []
        state.gigMediaSuggestions = []
        state.selectedAttendance = nil
        Task {
            let cache = await timelines.load()
            guard state.selectedSetlist?.id == setlist.id else { return }
            state.gigMedia = cache.media()[setlist.id] ?? []
            state.selectedAttendance = cache.attendance()[setlist.id]
            refreshSuggestions(setlist)
        }
    }

    // --- Check-in (#174) ---

    /// True if any gig I know about could be checked into right now on the
    /// calendar alone. Cheap and pure — it is what decides whether asking for
    /// the location permission is warranted at all, so the prompt only ever
    /// appears on a night there is actually something to check into.
    func checkInDue(now: Date = Date()) async -> Bool {
        let cache = await timelines.load()
        let attendance = cache.attendance()
        return cache.planned().contains { gig in
            canCheckInManually(gig: gig, now: now) && attendance[gig.id]?.provenance != "checked_in"
        }
    }

    func hasLocationPermission() -> Bool { location.hasPermission }

    func requestLocationPermission() { location.requestPermission() }

    /// One fix, once, when the timeline is opened: if it puts me at a gig I'm
    /// going to tonight, offer to check in. Every failure along the way —
    /// permission refused, no fix, no coordinates for the venue, too far away —
    /// is silently no offer. Nothing here is retried, scheduled or run in the
    /// background.
    ///
    /// ponytail: linear over the planned gigs, geocoding only the one that
    /// passes the city gate. You have a ticket for a handful of nights, not
    /// thousands.
    func offerCheckIn() {
        if askedToCheckIn { return }
        askedToCheckIn = true
        Task {
            guard let fix = await location.currentFix() else { return }
            let cache = await timelines.load()
            let attendance = cache.attendance()
            let candidates = cache.planned().filter { attendance[$0.id]?.provenance != "checked_in" }
            guard let gig = checkInCandidate(gigs: candidates, now: Date(), where: fix) else { return }
            guard let venue = await venueCoords(gig, cache: cache) else { return }
            guard atVenue(where: fix, venue: venue) else { return }
            state.checkInOffer = gig
        }
    }

    func dismissCheckInOffer() { state.checkInOffer = nil }

    /// The venue's coordinates, geocoded once and kept on the attendance record
    /// — the same fields #29/#174 reserved for it on Android. Nil for a venue
    /// the geocoder can't place, which costs this gig its prompt and nothing else.
    private func venueCoords(_ gig: FmSetlist, cache: TimelineCache) async -> (lat: Double, lon: Double)? {
        if let stored = cache.attendance()[gig.id], let lat = stored.venueLat, let lon = stored.venueLon {
            return (lat, lon)
        }
        guard let query = venueMapsQuery(venueName: gig.venue?.name, city: gig.venue?.city?.name)
        else { return nil }
        guard let found = await location.geocodeVenue(
            [query, gig.venue?.city?.country?.name].compactMap { $0 }.joined(separator: ", ")
        ) else { return nil }
        await timelines.saveAttendance(
            setlistId: gig.id,
            attendance: StoredAttendance(
                provenance: cache.attendance()[gig.id]?.provenance ?? "planned",
                checkedInAt: cache.attendance()[gig.id]?.checkedInAt,
                venueLat: found.lat, venueLon: found.lon
            )
        )
        return found
    }

    /// I am here. Sets the provenance the whole issue exists for, with the
    /// moment it happened — evidence of a different strength than setlist.fm's
    /// retroactive flag, not a competing record.
    func checkIn(_ gigId: String) {
        state.checkInOffer = nil
        Task {
            let existing = await timelines.load().attendance()[gigId]
            let attendance = StoredAttendance(
                provenance: "checked_in",
                checkedInAt: Int64(Date().timeIntervalSince1970 * 1000),
                venueLat: existing?.venueLat, venueLon: existing?.venueLon
            )
            await timelines.saveAttendance(setlistId: gigId, attendance: attendance)
            if state.selectedSetlist?.id == gigId { state.selectedAttendance = attendance }
        }
    }

    private func refreshSuggestions(_ setlist: FmSetlist) {
        // Silent without permission: the picker is what asks, so a prompt only
        // ever follows a tap.
        guard PhotoLibrary.isAuthorized,
              let date = setlist.eventDate,
              let window = photoWindow(gigDate: date)
        else { state.gigMediaSuggestions = []; return }
        let attached = Set(state.gigMedia.map(\.ref))
        Task {
            let found = await Task.detached { PhotoLibrary.assetsFromNight(window) }.value
            guard state.selectedSetlist?.id == setlist.id else { return }
            state.gigMediaSuggestions = found.filter { !attached.contains($0) }
        }
    }

    /// **Attach**: the picked assets become this night's, with both thumbnail
    /// tiers written before the record exists. Anything whose bytes could not be
    /// read is *not* attached and says so — a record with nothing behind it is the
    /// failure #98 exists to prevent.
    ///
    /// **Attach asks once** (#171, porting Android's `AttachHandle`): `band` is
    /// the answer to "shared or vault", named by whichever control the gesture
    /// landed on. There is no default path into this — every caller names one.
    func attachMedia(assetIds: [String], to band: Band = .shared) {
        guard let setlist = state.selectedSetlist else { return }
        let had = state.gigMedia
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
                state.gigMedia = media
                await timelines.saveMedia(setlistId: setlist.id, media: media)
                refreshSuggestions(setlist)
            }
            if failed > 0 {
                state.error = failed == 1
                    ? "Couldn't read that one — not attached."
                    : "Couldn't read \(failed) of those — not attached."
            }
        }
    }

    /// Moves one of my items into `band`, at the end of its run — the drag
    /// between bands, and what letting go of it there means (#171, porting
    /// Android's `moveGigMedia`).
    ///
    /// A move between bands *is* the change to its **Personal** bit; there is no
    /// separate gesture and no night-level grant above it. **Received media** is
    /// refused by `moveMedia` rather than here: whose disposition it is belongs
    /// with the rule, not with the caller.
    func moveMedia(_ mediaId: String, to band: Band) {
        guard let setlist = state.selectedSetlist else { return }
        let target = band == .shared ? bandsOf(state.gigMedia).shared.count : bandsOf(state.gigMedia).vault.count
        let media = StationToStation.moveMedia(state.gigMedia, id: mediaId, to: band, index: target)
        state.gigMedia = media
        Task { await timelines.saveMedia(setlistId: setlist.id, media: media) }
    }

    /// Removing means removing: the record goes, and so do the bytes it owned.
    func removeMedia(_ media: StoredMedia) {
        guard let setlist = state.selectedSetlist else { return }
        let kept = state.gigMedia.filter { $0.id != media.id }
        state.gigMedia = kept
        Task {
            await timelines.saveMedia(setlistId: setlist.id, media: kept)
            PhotoLibrary.deleteThumbnails(media.id)
            refreshSuggestions(setlist)
        }
    }

    // --- Cover art (#178) ---

    /// Offers the gig's own keepsakes first — already chosen for this night, so
    /// they need no permission and no re-asking — then the gallery's same-night
    /// match once that permission is granted. The gallery half is silent when
    /// missing: the confirm screen asks for it instead, so a prompt only ever
    /// follows a tap.
    private func loadCoverCandidates(_ setlist: FmSetlist) {
        guard let date = setlist.eventDate, let window = photoWindow(gigDate: date) else { return }
        let granted = PhotoLibrary.isAuthorized
        state.coverPermissionGranted = granted
        state.coverLoading = true
        Task {
            let pinned = state.gigMedia.filter { $0.kind == StoredMedia.Kind.photo }.map(\.ref)
            let gallery = granted ? await Task.detached { PhotoLibrary.assetsFromNight(window) }.value : []
            var seen = Set<String>()
            let candidates = (pinned + gallery).filter { seen.insert($0).inserted }
            guard state.selectedSetlist?.id == setlist.id else { return }
            state.coverCandidateIds = candidates
            state.coverLoading = false
            state.coverSearched = true
            // The first photo is the suggestion, so it is the cover until the
            // picker is swiped somewhere else.
            state.selectedCoverAssetId = candidates.first
        }
    }

    /// The cover the picker has landed on, or nil for Spotify's own collage.
    func setCover(_ assetId: String?) {
        if state.selectedCoverAssetId != assetId { state.selectedCoverAssetId = assetId }
    }

    /// Re-runs the cover search after the gallery permission prompt the picker
    /// itself triggered — the rest of the confirm screen (matches, playlist name)
    /// is untouched.
    func refreshCoverCandidates() {
        guard let setlist = state.selectedSetlist else { return }
        loadCoverCandidates(setlist)
    }

    /// Returns nil on success, or the reason the cover did not make it.
    private func uploadCover(playlistId: String, assetId: String) async -> String? {
        guard spotify.hasImageUploadScope() else {
            return "The cover needs a permission your Spotify login predates. "
                + "Log out in Settings and log in again to enable playlist covers."
        }
        guard let jpeg = await PhotoLibrary.coverJpeg(assetId: assetId) else {
            return "That photo could not be prepared as a cover."
        }
        do {
            try await spotify.uploadCover(playlistId, jpeg: jpeg)
            return nil
        } catch {
            return "The cover could not be uploaded. \(userMessage(error))"
        }
    }

    // --- The Log: what I saw, as opposed to what setlist.fm publishes (#169) ---

    /// Asserted, never derived: a song I *think* they played never becomes a song
    /// they played by inaction, so this is a tap, not a diff against a candidate
    /// pool. Editing songs never touches `closed` — "that was the whole set" is a
    /// separate, deliberate sentence.
    func addToLog(_ song: String) { writeLog { $0.adding(song) } }

    func removeFromLog(_ index: Int) { writeLog { $0.removingAt(index) } }

    /// A title replaces entry `index`, and what was written moves beneath it (#126).
    func correctLogEntry(_ index: Int, title: String) { writeLog { $0.correctingAt(index, title: title) } }

    /// The way back. A wrong correction must not be a one-way door.
    func restoreLogEntry(_ index: Int) { writeLog { $0.restoringAt(index) } }

    /// The only thing that may **Close** a **Log**, and it is a person saying so.
    /// setlist.fm has nowhere to keep this bit, so it never leaves the device.
    func setLogClosed(_ closed: Bool) {
        writeLog {
            var log = $0
            log.closed = closed
            return log
        }
    }

    private func writeLog(_ edit: (StoredLog) -> StoredLog) {
        guard let setlist = state.selectedSetlist else { return }
        let updated = edit(state.gigLog)
        state.gigLog = updated
        timelines.saveLog(setlistId: setlist.id, log: updated)
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
                let songName = state.matches.indices.contains(index) ? state.matches[index].song.name : trimmed
                let artist = state.matches.indices.contains(index) ? state.matches[index].searchArtist : ""
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
                // The songs are the point, so a cover that will not upload is
                // reported next to the success rather than thrown over it.
                let coverError: String?
                if let assetId = s.selectedCoverAssetId {
                    coverError = await uploadCover(playlistId: playlist.id, assetId: assetId)
                } else {
                    coverError = nil
                }
                state.creatingPlaylist = false
                state.createdPlaylistUrl = playlist.externalUrls["spotify"]
                state.createdPlaylistName = name
                state.createdTrackCount = result.added
                state.createdRefusedCount = result.refused.count
                state.coverUploadError = coverError
            } catch {
                fail(error)
            }
        }
    }
}
