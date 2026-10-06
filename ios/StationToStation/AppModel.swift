import Combine
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

/// Why an error is on screen, where that changes what can be offered about it.
///
/// Only the cases a screen acts on differently are named; everything else has no kind
/// and is shown as its message alone. Term for term with Android's `ErrorKind`.
enum ErrorKind {
    /// setlist.fm refused the *bundled* key: a free key of their own is the way out.
    case setlistFmSharedQuota
}

struct UiState {
    // Settings
    var setlistFmApiKey = ""
    var spotifyClientId = ""
    var clashfinderUser = ""
    var clashfinderPrivateKey = ""
    var spotifyConnected = false
    var spotifyLoginReady = false
    var setlistFmReady = false
    /// The shared setlist.fm key is believed spent (#457) and no key of the user's own
    /// is saved. What Settings leads its setlist.fm section with.
    var setlistFmSharedQuotaSpent = false
    var bundledSpotifyClientId = false
    var bundledSetlistFmKey = false
    var grantedScope: String?
    /// Whether the first-run door has been passed (#358). The splash is shown while
    /// this is false, and a launch after it never sees one again.
    var onboarded = false
    /// True once launch has put the saved timeline on screen, Festivals and all.
    /// Until then the launch look stays over the Timeline, so a reopened app never
    /// shows an empty timeline or a "0 shows" count on the way to its own.
    var launched = false
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
    /// The name on my **Card** when there is no username to name it (#405).
    var myCardName = ""
    var friends: [Friend] = []
    /// The **Lines** tapped out of the legend, by setlist.fm username, each with the
    /// moment it was turned off (#396). Still a reading aid and nothing about the
    /// person — it says nothing about the relationship and is never sent — but it
    /// now survives a launch, which is what lets the legend's recency order mean
    /// anything: with nothing remembered, every name is active and there is nothing
    /// to sort by (#266).
    var hiddenAt: [String: Int64] = [:]
    /// Who is currently tapped out. Derived rather than held separately, so there is
    /// only one fact — `hiddenAt` — to keep in step; a lane not in it is active.
    var hiddenLines: Set<String> { Set(hiddenAt.keys) }
    /// A card that would change a **Contact** already held, waiting on the one question
    /// this app asks (#188). Nothing is written and nothing is persisted while it stands.
    var friendConflict: FriendConflict?
    var sharedWith: Friend?
    // My timeline (the Spine). Facts only — the shape is derived at render time.
    var timelineShows: [FmSetlist] = []
    /// The future edge: gigs I hold a ticket for, furthest-future first (#175). Kept
    /// apart from `timelineShows` the same way `showsByFriend` is — a plan is not an
    /// attended show, and `gigTimeState` is what tells them apart on screen.
    var plannedGigs: [FmSetlist] = []
    /// What each night above today claims about itself, by gig id. The future lane is
    /// `plannedGigs` filtered by this — a night that has stopped being a plan leaves the
    /// lane and joins the Spine — so a write that changes a claim has to land here too,
    /// or the night stays put until the next cold start.
    var attendanceByGig: [String: StoredAttendance] = [:]
    /// **Gigs** whose own check-in a directly-present device witnessed (#442).
    ///
    /// A decoration on `attendanceByGig`, never a substitute for it: the user saying they
    /// were there and a stranger's phone agreeing are two different claims, and a night
    /// with nobody else running the radio is still a night they attended.
    var witnessedGigs: Set<String> = []
    /// When a verified check-in last arrived from each recognised **Contact**, by durable
    /// **Card** key (#484). Read through `gossipNearby`, which is what decides "is also here".
    ///
    /// In memory and never persisted, because presence that did not survive the process was
    /// not presence: a relaunched phone has heard from nobody yet, and restoring stamps would
    /// name a room the app has not been in since. Merged, never replaced — each **Pass**
    /// carries only who it just proved, and the window forgets the rest.
    var metAt: [String: Date] = [:]
    /// The **Gigs** `metAt`'s stamps are about: the nights this device was still participating
    /// in when the last **Pass** landed. `metAt` names people and not nights, so without this a
    /// **Contact** heard from tonight would be printed under last month's **Gig** page for as
    /// long as the window lasts. Replaced rather than merged, for the reason `observePresence`
    /// gives.
    var presentGigs: Set<String> = []
    /// The calendar event made for a planned gig, by gig id — EventKit's
    /// `eventIdentifier`. Presence is what the leaf reads as "already added".
    var calendarEventByGig: [String: String] = [:]
    /// True while `addPlannedGig` is out fetching the setlist.fm record.
    var planningLoading = false
    /// Friends' attended shows by setlist.fm username, drawn as Lanes when zoomed
    /// out. Kept apart from `timelineShows` (mine) so ownership is never read off
    /// the node holding a gig.
    var showsByFriend: [String: [FmSetlist]] = [:]
    /// The Timelines resolution: the strip of friends' Lanes opened beside my
    /// Spine, in place. Not a screen — pinch toggles it.
    var zoomedOut = false
    /// Every **Festival** identity this device knows, and which **Gigs** carry one
    /// (#166). Nothing infers a Festival; this is the only thing that makes one.
    var festivals: Festivals = Festivals()
    var timelineLoading = false
    /// Distinguishes "no Lanes yet" from "Lanes arriving" when the strip opens.
    var lanesLoading = false
    /// Row keys of the Festivals uncollapsed in place. Not a screen: a Festival
    /// opens where it stands.
    var expandedFestivals: Set<String> = []
    /// A **Gig** a link asked for, and how it wants to be shown. The timeline is the one
    /// place that can find a row, so it does the scrolling and clears this when done.
    var linkedGig: String?
    var linkedGigAs: GigLink?
    /// The night (ISO) a link wants the **Line** scrolled to; the timeline finds the
    /// nearest **Gig** and clears it.
    var linkedDate: String?
    /// The add form a link asked for, pre-filled and unsaved; the timeline opens it and clears this.
    var addGigLink: AddGigLink?
    /// Every night's media (#97), in the order it was attached, keyed by setlist id.
    ///
    /// The whole map rather than the open **Gig**'s alone, because the Timeline draws
    /// a night's first keepsakes on its own row: a night at a time was enough while
    /// only the grid read this, and it is not any more.
    var mediaBySetlist: [String: [StoredMedia]] = [:]
    /// Media **Contacts** sent for Nights of theirs I have not joined, by their Night id
    /// (#405): offered, never filed, and shown on my Night of the same date until I answer.
    var mediaOffers: [String: MediaOffer] = [:]
    /// What I said about a **Contact**'s Night that nothing else links to mine (#405),
    /// under the ids the **Spine** uses: their Night id → my Night id it is (`nightJoins`),
    /// and → the Nights of mine it is not (`nightsApart`). Mine alone; the weave reads both.
    var nightJoins: [String: String] = [:]
    var nightsApart: [String: Set<String>] = [:]
    /// The open **Gig**'s share of it. **Derived, never assigned** — it was a second
    /// copy, and two places holding one night's media is a drift waiting to happen.
    var gigMedia: [StoredMedia] {
        guard let id = selectedSetlist?.id else { return [] }
        return mediaBySetlist[id] ?? []
    }
    /// Asset ids the library holds from that night's window and this gig has not
    /// attached — the suggestion the grid offers before the picker is opened.
    var gigMediaSuggestions: [String] = []
    /// What each night has already been turned into (#360). Every playlist, not
    /// the last one: each url may be in somebody's hands, so converting a night
    /// again must not orphan a link already sent.
    var playlistsBySetlist: [String: [StoredPlaylist]] = [:]
    /// The open **Gig**'s attendance claim (#174/#29) — whether, and how, I'm
    /// known to have been there. Loaded alongside the gig's media so the header
    /// badge has something to read.
    var selectedAttendance: StoredAttendance?
    /// Whether the open **Gig** is a night of my own rather than a **Contact**'s I am
    /// only looking at (#327). Decided by `isMyNight`, the one rule, so the grid and
    /// anything else that edits cannot answer it differently.
    var selectedIsMine = false
    /// **Tickets** waiting to be confirmed, oldest first (#412). A queue rather than
    /// one slot: several PDFs can be shared before the app is next opened, and
    /// silently dropping all but the last would lose nights with no sign that it had.
    /// The prompt shows the first; answering or dismissing it brings the next.
    var ticketDrafts: [TicketDraft] = []
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
    /// Where in a clip the cover frame was scrubbed to. Zero for a photo, which has
    /// only the one picture, and zero again the moment the picker lands somewhere
    /// else — a frame belongs to the clip it was chosen out of.
    var selectedCoverFrameMs: Int64 = 0
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
    var publicGossip = PublicGossipState()
    /// Every id a night has been known by, under each of them (#497, #499).
    ///
    /// A **Room** asks the gossip record about *its* night, and the id it holds is whichever one
    /// the night is displayed under now. An adopted setlist.fm id leaves an older local id the
    /// record may still be filed under, so the lookup is a set. See `gossipGigAliases`.
    var gossipGigAliases: [String: Set<String>] = [:]
    /// When gossip participation runs out, or nil when the radio is off (#448). Recomputed
    /// by `gossipActiveUntil` rather than stored, so it is the same answer the radio acts on
    /// and the screens have nothing of their own to fall out of step with.
    var gossipActiveUntil: Date?
    /// When each night *could* **Gossip** until, under every id it answers to — the stop
    /// deliberately not applied (#501).
    ///
    /// A stop zeroes every participation deadline, and the dim bullet is the one control that can
    /// undo a stop; asking the stopped-aware deadline here would make it undrawable. What the
    /// radio actually runs on stays `gossipActiveUntil`, with the stop, as it was.
    var gossipEligibleUntil: [String: Int64] = [:]
    /// The **Active Gig**, by its local id. A **Room** compares it through `gossipGigAliases`,
    /// because the id a **Room** holds is the adopted one where the night has one.
    var gossipActiveGig: String?
    /// The nights a stop actually ended — the dim half of a **Presence row**'s bullet.
    ///
    /// A set and not a flag, because a stop is not global: it ends the nights already stood in,
    /// and a **Check-in** made *after* it is a fresh consent the earlier stop says nothing about.
    /// Keyed under both of a night's ids, like the deadlines it is derived from.
    var gossipStoppedGigs: Set<String> = []
    /// An artist's own songs, once a **Curtain** pull has asked for them (#129) —
    /// the pool a **Log** entry is corrected against. Session-lived rather than
    /// stored: a pull is a gesture someone made on purpose, and a catalogue is a
    /// prompt, so losing it on a cold start costs one deliberate pull.
    var catalogueByArtist: [String: [String]] = [:]
    /// The mbid being fetched, so the panel can say "looking up" instead of
    /// "nothing known" — the two mean opposite things to someone mid-correction.
    var catalogueFetching: String?
    /// Spellings MusicBrainz offered for the artist name being typed into one of the
    /// by-hand doors (#350). Session-lived and cleared the moment one is picked: it
    /// is a prompt, and nothing downstream is keyed on the **mbid** it carries.
    var artistSuggestions: [MbArtist] = []
    /// The handover screen's whole state (#142). Nil `role` means no handover is
    /// running, which is also what the screen reads to know whether to exist.
    var handover = HandoverUi()
    // Transient banners
    var error: String?
    /// What kind of thing `error` is, for the screens that offer a way out of one.
    ///
    /// A bare message cannot be acted on: the shared setlist.fm quota running out is the
    /// one error the app can hand the user a button for (#457), and telling it apart
    /// from "no results" or "that isn't a link" takes more than the words.
    var errorKind: ErrorKind?
    var notice: String?
}

/// Which end of a handover this phone is. The screen shows two quite different things:
/// the source picks what to send and shows a code, the receiver only watches.
enum HandoverRole { case source, receiver }

struct HandoverUi {
    var role: HandoverRole?
    /// The invite as a URI, once the listener is up — this is what the QR draws.
    var inviteUri: String?
    var progress = HandoverProgress()
    var receipt: HandoverReceipt?
    var error: String?
}

@MainActor
final class AppModel: ObservableObject, StateHost {

    @Published var state = UiState()

    let settings = Settings()
    private lazy var setlistFm = SetlistFmClient(
        keySource: { [settings] in settings.setlistFmKey },
        sharedQuotaSpentAt: { [settings] in settings.setlistFmSharedQuotaSpentAt },
        recordSharedQuotaSpent: { [settings] instant in settings.recordSharedQuotaSpent(at: instant) }
    )
    private lazy var spotify = SpotifyClient(settings)
    private(set) lazy var settingsController = SettingsController(host: self, settings: settings, spotify: spotify)
    private let musicBrainz = MusicBrainzClient()
    /// The in-flight suggestion lookup, held so the next keystroke can cancel it.
    private var artistSearch: Task<Void, Never>?
    private let timelines = TimelineStore()
    private(set) lazy var gigMedia = GigMediaController(
        host: self, timelines: timelines,
        markSelectedOwnership: { [unowned self] in self.markSelectedOwnership($0, attendance: $1) }
    )
    private(set) lazy var gossip = GossipController(host: self, timelines: timelines)
    /// The device half of the Timeline (ADR-0001): the store, the client, the
    /// bundle. Held as the concrete type because seeding a fixture is an iOS-only
    /// entry point that the shared logic layer only ever *reads* the result of.
    private lazy var plumbing = DeviceTimelinePlumbing(store: timelines, client: setlistFm)
    /// The shared half: the sequence and the rules, testable because the plumbing
    /// above is handed in rather than constructed inside it.
    private lazy var logic = TimelineLogic(plumbing: plumbing)
    private lazy var location = DeviceLocation()
    private(set) lazy var navigation = NavigationController(
        host: self,
        timelines: timelines,
        fetchSetlist: { [unowned self] id in try await setlistFm.setlist(id) },
        identifyFestivals: { [unowned self] mine, known in await logic.resolveFestivals(mine: mine, known: known) },
        refreshLine: { [unowned self] friend in contacts.refreshLine(friend) },
        loadGigMedia: { [unowned self] show in gigMedia.loadGigMedia(show) }
    )

    private(set) lazy var setlists = SetlistsController(
        host: self,
        setlistFm: setlistFm,
        musicBrainz: musicBrainz,
        timelines: timelines,
        settings: settings,
        saveMySetlistFmUser: { [unowned self] in settingsController.saveMySetlistFmUser($0) },
        adoptSetlist: { [unowned self] in await gig.adoptSetlist(gigId: $0, setlistId: $1, fresh: $2, notice: $3) },
        storeAttendance: { [unowned self] in gig.storeAttendance($0, $1) },
        lineArtists: { [unowned self] in lineArtists() }
    )

    private(set) lazy var gig = GigController(
        host: self,
        timelines: timelines,
        setlistFm: setlistFm,
        location: location,
        sortedPlanned: { [unowned self] in sortedPlanned($0) },
        gossip: gossip
    )

    private(set) lazy var contacts: ContactsController = {
        let contacts = ContactsController(
            host: self, settings: settings, timelines: timelines,
            setlistFm: setlistFm, spotify: spotify, logic: logic,
            gossip: gossip, gig: gig)
        // Views observe AppModel only, so the controller's `maybeUndo` redraws through it.
        contactsChanges = contacts.objectWillChange.sink { [unowned self] in objectWillChange.send() }
        return contacts
    }()
    private var contactsChanges: AnyCancellable?

    private var matchTask: Task<Void, Never>?

    private(set) lazy var tickets = TicketsController(
        host: self,
        timelines: timelines,
        setlistFm: setlistFm,
        mintPlannedGig: { [unowned self] in await mintPlannedGig(artist: $0, venue: $1, night: $2) },
        planFmGig: { [unowned self] in await planFmGig($0) },
        lineArtists: { [unowned self] in lineArtists() },
        setlists: setlists,
        gig: gig
    )

    lazy var handover = HandoverController(host: self, settings: settings, timelines: timelines, spotify: spotify,
                                           loadTimeline: { [unowned self] in self.loadTimeline() })

    init() {
        state.setlistFmApiKey = settings.setlistFmApiKey ?? ""
        // Effective value, so Settings shows the bundled ID and lets it be
        // swapped for another app's without a rebuild.
        state.spotifyClientId = settings.spotifyClientIdValue ?? ""
        state.spotifyConnected = spotify.isConnected
        state.spotifyLoginReady = settings.spotifyClientIdValue != nil
        state.setlistFmReady = settings.setlistFmApiKeyValue != nil
        state.setlistFmSharedQuotaSpent = settings.setlistFmSharedQuotaSpentNow
        state.bundledSpotifyClientId = settings.hasBundledSpotifyClientId
        state.bundledSetlistFmKey = settings.hasBundledSetlistFmKey
        state.grantedScope = settings.grantedScope
        state.onboarded = settings.onboarded
        state.mySetlistFmUser = settings.mySetlistFmUser ?? ""
        state.myCardName = settings.myCardName ?? ""
        state.friends = settings.friends
        state.clashfinderUser = settings.clashfinderUser ?? ""
        state.clashfinderPrivateKey = settings.clashfinderPrivateKey ?? ""

        // CI (and a URL bar) seed a Resolution here: `-seedFixture <name>` on the
        // launch line. UserDefaults maps `-key value` argv automatically, so no
        // `simctl openurl` — which pops a system "Open in app?" prompt that blocks
        // the URL from ever reaching us — is needed.
        if let fixture = UserDefaults.standard.string(forKey: "seedFixture")?.nilIfBlank {
            loadFixture(fixture, open: UserDefaults.standard.bool(forKey: "seedOpen"))
            // A seeded launch is asking for a timeline, so it is past the first-run
            // door by definition — nobody wants to answer a splash to see a fixture.
            state.onboarded = true
        }

        // Refusing the location prompt is not a dead end and not an error: the
        // ambient offer just never appears, and the gig's own screen still has
        // a check-in you can press by hand.
        location.onAuthorizationChanged = { [weak self] in self?.gig.offerCheckIn() }

        // The cold-launch half of the inbox drain; the foreground half is in
        // `App.swift`. A launch that goes straight to active may never register as a
        // scene-phase *change*, so a ticket shared just before opening the app would
        // otherwise sit in the box until the next background round trip.
        tickets.drainTicketInbox()

        // A backstop for the launch look, not its timing: if reading the phone's own
        // storage ever hangs, the app still opens.
        Task {
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            state.launched = true
        }
    }

    func consumeError() {
        state.error = nil
        state.errorKind = nil
    }
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
            // The plans first, so the launch look lifts on the whole timeline rather
            // than on the Spine with the future edge still to arrive.
            await refreshPlannedGigs()
            // Launch is done at the first Spine, not after the Festival retry: that
            // one asks setlist.fm, and the launch look never waits on the network.
            await logic.loadSpine(me: me) { spine in
                state.timelineShows = spine.mine
                state.festivals = spine.festivals
                state.launched = true
            }
            state.launched = true
        }
    }

    /// Furthest-future first — the same descending order the attended rows below
    /// already use: up is always later, and a planned gig is not an exception to that.
    private func sortedPlanned(_ gigs: [FmSetlist]) -> [FmSetlist] {
        gigs.sorted { ($0.localDate() ?? .distantPast) > ($1.localDate() ?? .distantPast) }
    }

    /// The future edge, from disk (#175). Called alongside the Spine at launch, and
    /// again by every write below so the timeline never shows stale plans.
    func loadPlannedGigs() {
        Task { await refreshPlannedGigs() }
    }

    private func refreshPlannedGigs() async {
        let cache = await timelines.load()
        state.plannedGigs = sortedPlanned(cache.planned())
        // The Contact list is read at launch, but the nights are not there until here,
        // and the gossip channel wants both (#417).
        gossip.contactsChanged()
        state.attendanceByGig = cache.attendance()
        state.calendarEventByGig = cache.calendarEvents()
        // The Timeline draws keepsakes on its rows, so this has to be here before
        // any night is opened — and this already reads the cache at launch and
        // after every write.
        state.mediaBySetlist = cache.media()
        state.mediaOffers = cache.mediaOffers
        state.nightJoins = cache.spineJoins()
        state.nightsApart = cache.spineDismissals()
        state.playlistsBySetlist = cache.playlists()
        state.hiddenAt = cache.hiddenLines
    }

    /// Spellings for a name being typed into one of the by-hand doors (#350).
    ///
    /// Debounced, and the previous lookup is cancelled: without the cancel a slow
    /// reply for "ka" can land after a fast one for "kaizers" and put the wrong four
    /// rows under a name that has moved on.
    ///
    /// A failure is an empty list, never a banner. This is a prompt on top of a field
    /// that works perfectly well without it, and MusicBrainz being unreachable is not
    /// something the person typing needs to be told about mid-word (ADR-0004).
    func suggestArtists(_ query: String) {
        artistSearch?.cancel()
        guard !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            state.artistSuggestions = []
            return
        }
        artistSearch = Task { [musicBrainz] in
            try? await Task.sleep(nanoseconds: 350_000_000)
            guard !Task.isCancelled else { return }
            let hits = await musicBrainz.searchArtists(query: query)
            guard !Task.isCancelled else { return }
            state.artistSuggestions = hits
        }
    }

    /// The typed name was replaced by a picked one, so the list has done its job.
    func clearArtistSuggestions() {
        artistSearch?.cancel()
        state.artistSuggestions = []
    }

    /// The one add form's write. The date decides the rule underneath: a night before
    /// today is one I was at (`addLocalGig`), any other is one I am going to
    /// (`addPlannedGigByHand`).
    func addGig(artist: String, venue: String, date: String, today: String = isoToday()) {
        guard let night = isoDate(fromFm: date.trimmingCharacters(in: .whitespaces)),
              !artist.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            state.error = "A night needs who played and a date as dd-MM-yyyy."
            state.errorKind = nil
            return
        }
        switch nightKind(date: night, today: today) {
        case .goingTo: addPlannedGigByHand(artist: artist, venue: venue, date: date)
        case .wasAt: addLocalGig(artist: artist, venue: venue, date: date)
        }
    }

    /// A gig I'm going to, typed in: who is playing, where, and when.
    ///
    /// **The objection that kept this a paste box is obsolete.** The alert defended
    /// taking only a setlist.fm link on two grounds. The first still holds —
    /// setlist.fm's search index stops about a day out (#29), so a future gig cannot
    /// be *found*. The second, that typing the details in would invent a second record
    /// for a gig setlist.fm already has, is no longer true: `createLocalGig` mints
    /// local **Gig**s for nights setlist.fm has never heard of, and `adoptSetlistLink`
    /// moves one onto the vendor id when setlist.fm catches up, with every photo,
    /// offset, calendar link and playlist intact.
    ///
    /// **No attendance is written**, which is the whole difference from `addLocalGig`.
    /// `savePlanned` records `planned` for a gig with no claim on it, and a night I
    /// have not been to yet has no claim to make. Writing `attended` here would be the
    /// app asserting I was somewhere I have not been.
    func addPlannedGigByHand(artist: String, venue: String, date: String) {
        let who = artist.trimmingCharacters(in: .whitespacesAndNewlines)
        let room = venue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !who.isEmpty, let night = gigDay(date.trimmingCharacters(in: .whitespaces)) else {
            state.error = "A night needs who is playing and a date as dd-MM-yyyy."
            state.errorKind = nil
            return
        }
        Task { await mintPlannedGig(artist: who, venue: room, night: night) }
    }

    /// Minting the planned night itself, with nothing decided in it.
    ///
    /// Extracted so a **Ticket** takes *this* path rather than one shaped like it
    /// (#412). "The same local-planned-gig creation path" is only true if it is
    /// literally the same code; a second copy is a divergence with a delay on it.
    @discardableResult
    private func mintPlannedGig(artist: String, venue: String, night: Date) async -> String {
        let day = fmDate(night)
        let gigId = await timelines.createLocalGig(date: day, artist: artist, venue: venue)
        let gig = localGigSetlist(gigId: gigId, artist: artist, date: day,
                                  venue: venue, city: "")
        // The claim goes into state as well as onto disk. `plannedLane` filters on
        // it, so a gig added without it is written correctly and then drawn by
        // nothing — the night appears only after a restart, which reads as Add
        // having done nothing at all.
        state.attendanceByGig[gigId] = await timelines.savePlanned(gig)
        state.plannedGigs = sortedPlanned(state.plannedGigs + [gig])
        return gigId
    }

    /// The nights a **Ticket** is matched against: everything on the **Line**, plans
    /// above today included. A ticket for a night already planned by hand is the same
    /// night, not a second one.
    private var knownNights: [FmSetlist] { state.timelineShows + state.plannedGigs }

    /// A night I was at that setlist.fm has never heard of, typed in.
    ///
    /// The only way into this app that does not end at setlist.fm: no account, no API
    /// key, no catalogue — the small venue nobody lists. It is the same
    /// `createLocalGig` a planned night is minted with, reached from the other side.
    ///
    /// **Attended**, because that is what typing it in claims. The night therefore
    /// joins the Spine rather than the future lane, which is `spineNights`' whole
    /// reason for existing (#341).
    func addLocalGig(artist: String, venue: String, date: String) {
        let who = artist.trimmingCharacters(in: .whitespacesAndNewlines)
        let where_ = venue.trimmingCharacters(in: .whitespacesAndNewlines)
        // `gigDay`, not `parseFmDate`: that one reads dd-MM-yyyy as GMT, and a night
        // typed in west of it would be normalised back out a day early.
        guard !who.isEmpty, let night = gigDay(date.trimmingCharacters(in: .whitespaces)) else {
            state.error = "A night needs who played and a date as dd-MM-yyyy."
            state.errorKind = nil
            return
        }
        Task {
            let day = fmDate(night)
            let gigId = await timelines.createLocalGig(date: day, artist: who, venue: where_)
            let gig = localGigSetlist(gigId: gigId, artist: who, date: day,
                                      venue: where_, city: "")
            let attendance = StoredAttendance(provenance: "attended")
            await timelines.savePlanned(gig)
            await timelines.saveAttendance(setlistId: gigId, attendance: attendance)
            state.plannedGigs = sortedPlanned(state.plannedGigs + [gig])
            state.attendanceByGig[gigId] = attendance
            // The Spine is built from the store, and this night has just joined it.
            loadTimeline()
        }
    }

    /// **Departures committed: a diff applied to the Line, not an import.** The
    /// Swift twin of Android's `AppViewModel.commitProgramme` (#391, #390).
    ///
    /// Adds mint a **Gig** claimed `planned` — a programme is a plan, and it must
    /// never be counted as a show I have seen. Each carries the act's stage as its
    /// venue and the **Festival**'s id, so grouping is declared rather than
    /// inferred: the **Festival** exists because somebody picked it.
    ///
    /// **An act already on the Line is adopted, never duplicated** — matched by
    /// `onLine` before anything is minted.
    ///
    /// Removes delete only a **Gig** this app minted: `deleteGig` refuses one
    /// carrying a setlist.fm id or holding media, so deselecting a plan can never
    /// erase evidence that a night happened.
    func commitProgramme(_ programme: StoredProgramme, diff: ProgrammeDiff, picked: Set<String>, now: Date = Date()) {
        guard !diff.isEmpty else { return }
        Task {
            let played = playedActs(programme.acts, now: now)
            let festivalId = programmeFestivalId(programme)
            let days = programmeDays(programme.acts)
            let name = programme.name.trimmingCharacters(in: .whitespaces)
            let festival = StoredFestival(
                id: festivalId,
                name: name.isEmpty ? programme.id : name,
                rangeFrom: days.first.map { fmDate($0) },
                rangeTo: days.last.map { fmDate($0) },
                // Authored: I picked this festival. An upstream scrape must not
                // overwrite a name I chose off its own programme.
                source: StoredFestival.FestivalSource.authored
            )

            var minted: [FmSetlist] = []
            var attendances: [String: StoredAttendance] = [:]
            var membership: [String: String] = [:]
            for act in programme.acts where picked.contains(actKey(act)) {
                let artist = act.artist.trimmingCharacters(in: .whitespaces)
                guard !artist.isEmpty else { continue }
                let gigId: String
                if let existing = self.gig.onLine(nightIso: act.date, artist: artist, mbid: act.mbid) {
                    gigId = existing.id
                } else {
                    let fmDay = fmActDate(act.date)
                    gigId = await timelines.createLocalGig(date: fmDay, artist: artist, venue: act.stage)
                    let gig = localGigSetlist(gigId: gigId, artist: artist, date: fmDay,
                                              venue: act.stage, city: "")
                    let claim = await timelines.savePlanned(gig)
                    // A set that has already finished is a night I was at, not a
                    // night I am going to — see `playedActs`. Only ever upgrades:
                    // `savePlanned` hands back whatever claim already stood, and a
                    // check-in outranks this one.
                    if played.contains(actKey(act)), claim.provenance == "planned" {
                        let attended = claim.withProvenance("attended")
                        await timelines.saveAttendance(setlistId: gigId, attendance: attended)
                        attendances[gigId] = attended
                    } else {
                        attendances[gigId] = claim
                    }
                    minted.append(gig)
                }
                membership[gigId] = festivalId
            }

            var dropped = Set<String>()
            for key in diff.remove {
                let parts = key.split(separator: "|", maxSplits: 1)
                guard parts.count == 2 else { continue }
                // The artist half is already a `nameKey` fold; `onLine` folds its
                // own query the same way, matching apples to apples.
                guard let gig = self.gig.onLine(nightIso: String(parts[0]), artist: String(parts[1])), gig.isLocal else { continue }
                if await timelines.deleteGig(gig.id) { dropped.insert(gig.id) }
            }

            await timelines.save(festivals: [festivalId: festival], festivalIdByShow: membership)
            state.plannedGigs = sortedPlanned(state.plannedGigs.filter { !dropped.contains($0.id) } + minted)
            for (id, attendance) in attendances { state.attendanceByGig[id] = attendance }
            for id in dropped { state.attendanceByGig[id] = nil }
            state.festivals = state.festivals + Festivals(byId: [festivalId: festival], idByShow: membership)
            // The Spine is built from the store, and a played act just committed
            // may have joined it this instant rather than at the next cold start.
            loadTimeline()
        }
    }

    func addPlannedGig(_ linkOrId: String) {
        guard let id = parseSetlistId(linkOrId) else {
            state.error = "That doesn't look like a setlist.fm gig link."
            state.errorKind = nil
            return
        }
        if state.plannedGigs.contains(where: { $0.id == id }) { return }
        state.planningLoading = true
        Task {
            do {
                let gig = try await setlistFm.setlist(id)
                await planFmGig(gig)
                state.planningLoading = false
            } catch {
                state.planningLoading = false
                fail(error)
            }
        }
    }

    /// A setlist.fm night onto the plan, as setlist.fm has it: `addPlannedGig`'s write,
    /// shared with a ticket whose lookup found its night (#531).
    private func planFmGig(_ hit: FmSetlist) async {
        state.attendanceByGig[hit.id] = await timelines.savePlanned(hit)
        state.plannedGigs = sortedPlanned(state.plannedGigs.filter { $0.id != hit.id } + [hit])
    }

    /// A calendar event was just made for a planned gig; remember its identifier.
    /// Presence of it is what a leaf reads as "already added" (#175).
    func markCalendarAdded(_ gigId: String, eventId: String) {
        state.calendarEventByGig[gigId] = eventId
        Task { await timelines.markCalendarAdded(gigId: gigId, eventId: eventId) }
    }

    /// Bridges the pure `insertCalendarEvent` to state a view can render: EventKit
    /// itself asks nothing of the model layer, but the result — the id, or the lack of
    /// one — has to land somewhere the leaf reads. Degrades to the same error banner
    /// every other failure in this model uses, matching Android's toast.
    func addToCalendar(_ setlist: FmSetlist) {
        Task {
            if let id = await insertCalendarEvent(setlist) {
                markCalendarAdded(setlist.id, eventId: id)
            } else {
                state.error = "Couldn't add this to your calendar."
                state.errorKind = nil
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
            state.errorKind = nil
            return
        }
        state.timelineLoading = true
        Task {
            do {
                let (shows, total) = try await setlistFm.attendedShows(me)
                state.timelineShows = shows
                state.timelineLoading = false
                await timelines.save(shows: [me: shows], attendedTotals: [me: total])
                navigation.resolveFestivals()
            } catch {
                state.timelineLoading = false
                fail(error)
            }
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
            state.errorKind = nil
            return
        }
        state.mySetlistFmUser = spine.me
        state.friends = spine.friends
        state.timelineShows = spine.mine
        state.showsByFriend = spine.byFriend
        state.festivals = spine.festivals
        // A fixture with Lanes is a Timelines-resolution scenario; one without is
        // My-timeline. Either way the shape is derived, never stored.
        state.zoomedOut = !spine.friends.isEmpty
        state.timelineLoading = false
        let rows = weaveTimelines(
            mine: state.timelineShows, festivals: state.festivals,
            friends: spine.friends, theirs: state.showsByFriend
        )
        state.expandedFestivals = open
            ? Set(rows.filter { $0.node.isSeveral }.map(\.key))
            : []
    }

    /// The one place a thrown thing becomes an error on screen — and, for a spent shared
    /// setlist.fm quota, an error with something to do about it.
    func fail(_ error: Error) {
        state.error = userMessage(error)
        state.errorKind = errorKind(of: error)
        if isSharedQuota(error) { state.setlistFmSharedQuotaSpent = true }
        state.searchLoading = false
        state.setlistsLoading = false
        state.creatingPlaylist = false
    }

    private func errorKind(of error: Error) -> ErrorKind? {
        isSharedQuota(error) ? .setlistFmSharedQuota : nil
    }

    private func isSharedQuota(_ error: Error) -> Bool {
        (error as? SetlistFmRateLimited)?.sharedKey == true
    }

    /// `nil` until both halves of a clashfinder account are on the phone — see
    /// `Clashfinder.swift` on why there is no bundled fallback here.
    var clashfinderAuth: ClashfinderAuth? {
        guard !state.clashfinderUser.isEmpty, !state.clashfinderPrivateKey.isEmpty else { return nil }
        return ClashfinderAuth(
            user: state.clashfinderUser,
            publicKey: clashfinderPublicKey(user: state.clashfinderUser, privateKey: state.clashfinderPrivateKey)
        )
    }

    /// Joins a **Contact**'s **Gig**: it goes onto my **Line** under the same id, so holding
    /// it on both **Lines** makes the **Crossing** and nothing else has to be said. The date
    /// decides the claim, as it does for the add form: a night before today is one I was
    /// there, attended; any other is one I am going to, planned and claiming nothing.
    ///
    /// Joining answers no **Maybe**. A **Maybe** is joined only by my "same night", so a
    /// hand-logged night of mine on this date stays a question, and is now asked against a
    /// night I hold.
    func joinGig(_ show: FmSetlist) {
        let kind = nightKind(date: isoDate(fromFm: show.eventDate), today: isoToday())
        Task {
            var attendance = await timelines.savePlanned(show)
            if kind == .wasAt {
                attendance = StoredAttendance(provenance: "attended")
                await timelines.saveAttendance(setlistId: show.id, attendance: attendance)
            }
            state.plannedGigs = sortedPlanned(state.plannedGigs.filter { $0.id != show.id } + [show])
            state.attendanceByGig[show.id] = attendance
            state.selectedAttendance = attendance
            markSelectedOwnership(show, attendance: attendance)
            loadTimeline()
        }
    }

    /// Discovers a friend from a Spotify playlist link they shared: reads the
    /// playlist's description, and if it carries a setlist.fm stamp, adds the owner.
    func discoverFriendFromPlaylist(_ link: String) {
        guard let id = spotifyPlaylistId(link) else {
            state.error = "That doesn't look like a Spotify playlist link."
            state.errorKind = nil
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
                    state.errorKind = nil
                } else if let ownerId, ownerId == me {
                    state.notice = "That's your own playlist."
                } else {
                    contacts.addFriend(Friend(setlistfm: username!,
                                     name: playlist.owner?.displayName?.nilIfBlank ?? username!,
                                     spotifyId: ownerId))
                    state.notice = "Added @\(username!) as a friend."
                }
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
            for: setlist, mine: state.timelineShows, festivals: state.festivals
        )

        state.selectedSetlist = setlist
        // Answered twice on purpose: now from the two lists, which are already in
        // hand, and again in `loadGigMedia` once the store hands back the attendance
        // claim. Starting at the answer the lists give rather than at `false` is what
        // keeps a night that is plainly mine from drawing itself read-only for a frame.
        markSelectedOwnership(setlist, attendance: nil)
        gigMedia.loadGigMedia(setlist)
        state.gigLog = StoredLog()
        Task {
            let log = await timelines.log(setlistId: setlist.id)
            guard state.selectedSetlist?.id == setlist.id else { return }
            state.gigLog = log
        }
        state.matches = matches
        state.matching = true
        state.playlistName = defaultName
        state.createdPlaylistUrl = nil
        state.coverCandidateIds = []
        state.selectedCoverAssetId = nil
        state.selectedCoverFrameMs = 0
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
    /// Whether the open night is mine, through the one rule (#327) — never re-derived
    /// at a call site, because the direction a second implementation would drift is
    /// offering an edit on someone else's night.
    private func markSelectedOwnership(_ setlist: FmSetlist, attendance: StoredAttendance?) {
        state.selectedIsMine = isMyNight(
            setlist.id,
            attendance: attendance,
            mine: state.timelineShows,
            planned: state.plannedGigs
        )
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
            // Clips included: a night whose only capture is a clip has a cover in it,
            // one frame at a time (`CoverFrameSheet`). Pictures only, though — a Note
            // holds no bytes and a dead reference resolves to nothing, and neither is
            // a picture of the night.
            let pinned = state.gigMedia
                .filter { $0.kind == StoredMedia.Kind.photo || $0.kind == StoredMedia.Kind.video }
                .map(\.ref)
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
        guard state.selectedCoverAssetId != assetId else { return }
        state.selectedCoverAssetId = assetId
        state.selectedCoverFrameMs = 0
    }

    /// The frame of the clip the scrub has settled on.
    func setCoverFrame(_ atMs: Int64) {
        if state.selectedCoverFrameMs != atMs { state.selectedCoverFrameMs = atMs }
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
        guard let jpeg = await PhotoLibrary.coverJpeg(assetId: assetId,
                                                      frameMs: state.selectedCoverFrameMs) else {
            return "That photo could not be prepared as a cover."
        }
        do {
            try await spotify.uploadCover(playlistId, jpeg: jpeg)
            return nil
        } catch {
            return "The cover could not be uploaded. \(userMessage(error))"
        }
    }

    /// The artists already on my **Line**, for the matcher's artist check: one per
    /// MusicBrainz id.
    private func lineArtists() -> [FmArtist] {
        var seen = Set<String>()
        return knownNights.compactMap(\.artist)
            .filter { !$0.mbid.trimmingCharacters(in: .whitespaces).isEmpty && seen.insert($0.mbid).inserted }
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
            state.errorKind = nil
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
                // Fall back to the canonical URL rather than dropping the link:
                // `externalUrls` is Spotify's to omit, the id is ours to keep.
                let url = playlist.externalUrls["spotify"]
                    ?? "https://open.spotify.com/playlist/\(playlist.id)"
                state.creatingPlaylist = false
                state.createdPlaylistUrl = url
                state.createdPlaylistName = name
                state.createdTrackCount = result.added
                state.createdRefusedCount = result.refused.count
                state.coverUploadError = coverError
                // So the night still points at it — on this screen and on the next
                // launch. Appended, never replaced: converting this night again must
                // not orphan a link already sent to someone.
                if let night = s.selectedSetlist?.id.nilIfBlank {
                    let made = StoredPlaylist(url: url, name: name, trackCount: result.added)
                    state.playlistsBySetlist[night, default: []].append(made)
                    await timelines.save(playlists: [night: made])
                }
            } catch {
                fail(error)
            }
        }
    }

    /// Drops one playlist link from a night.
    ///
    /// For a playlist deleted on Spotify, where the pointer left behind is dead
    /// weight. It removes the *link*, never the night — which is why it is a separate
    /// door from `deleteGig` and not a step inside it.
    func removePlaylist(_ setlistId: String, url: String) {
        state.playlistsBySetlist[setlistId] =
            (state.playlistsBySetlist[setlistId] ?? []).filter { $0.url != url }
        Task { await timelines.removePlaylist(setlistId: setlistId, url: url) }
    }
}
