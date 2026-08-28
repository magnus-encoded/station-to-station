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
    /// The id of the **Bill** whose song pools are being looked up right now, if any.
    /// One at a time: the lookups are sequential and the line that reports them is one
    /// line, so a second in flight would have nowhere truthful to say so.
    var billFetching: String?
    /// The **Bills** on the wall (#172) — festivals whose **Gigs** do not exist yet.
    /// A list, not a map, because the future lane draws them in one order with the
    /// tickets and nothing looks one up by id except an edit.
    var bills: [StoredBill] = []
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
    /// Every night's media (#97), in the order it was attached, keyed by setlist id.
    ///
    /// The whole map rather than the open **Gig**'s alone, because the Timeline draws
    /// a night's first keepsakes on its own row: a night at a time was enough while
    /// only the grid read this, and it is not any more.
    var mediaBySetlist: [String: [StoredMedia]] = [:]
    /// The open **Gig**'s share of it. **Derived, never assigned** — it was a second
    /// copy, and two places holding one night's media is a drift waiting to happen.
    var gigMedia: [StoredMedia] {
        guard let id = selectedSetlist?.id else { return [] }
        return mediaBySetlist[id] ?? []
    }
    /// Asset ids the library holds from that night's window and this gig has not
    /// attached — the suggestion the grid offers before the picker is opened.
    var gigMediaSuggestions: [String] = []
    /// The open **Gig**'s attendance claim (#174/#29) — whether, and how, I'm
    /// known to have been there. Loaded alongside the gig's media so the header
    /// badge has something to read.
    var selectedAttendance: StoredAttendance?
    /// Whether the open **Gig** is a night of my own rather than a **Contact**'s I am
    /// only looking at (#327). Decided by `isMyNight`, the one rule, so the grid and
    /// anything else that edits cannot answer it differently.
    var selectedIsMine = false
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
    /// An artist's own songs, once a **Curtain** pull has asked for them (#129) —
    /// the pool a **Log** entry is corrected against. Session-lived rather than
    /// stored: a pull is a gesture someone made on purpose, and a catalogue is a
    /// prompt, so losing it on a cold start costs one deliberate pull.
    var catalogueByArtist: [String: [String]] = [:]
    /// The mbid being fetched, so the panel can say "looking up" instead of
    /// "nothing known" — the two mean opposite things to someone mid-correction.
    var catalogueFetching: String?
    /// The handover screen's whole state (#142). Nil `role` means no handover is
    /// running, which is also what the screen reads to know whether to exist.
    var handover = HandoverUi()
    // Transient banners
    var error: String?
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
final class AppModel: ObservableObject {

    @Published var state = UiState()

    let settings = Settings()
    private lazy var setlistFm = SetlistFmClient { [settings] in settings.setlistFmApiKeyValue }
    private lazy var spotify = SpotifyClient(settings)
    private let musicBrainz = MusicBrainzClient()
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
                state.festivals = spine.festivals
            }
        }
        loadPlannedGigs()
    }

    /// Furthest-future first — the same descending order the attended rows below
    /// already use: up is always later, and a planned gig is not an exception to that.
    private func sortedPlanned(_ gigs: [FmSetlist]) -> [FmSetlist] {
        gigs.sorted { ($0.localDate() ?? .distantPast) > ($1.localDate() ?? .distantPast) }
    }

    /// The future edge, from disk (#175). Called alongside the Spine at launch, and
    /// again by every write below so the timeline never shows stale plans.
    func loadPlannedGigs() {
        Task {
            let cache = await timelines.load()
            state.plannedGigs = sortedPlanned(cache.planned())
            state.attendanceByGig = cache.attendance()
            state.bills = Array(cache.bills.values).sorted { $0.name < $1.name }
            state.calendarEventByGig = cache.calendarEvents()
            // The Timeline draws keepsakes on its rows, so this has to be here before
            // any night is opened — and this already reads the cache at launch and
            // after every write.
            state.mediaBySetlist = cache.media()
        }
    }

    /// Adds a gig I'm going to, from whatever was pasted off setlist.fm — the page url
    /// or the bare id.
    ///
    /// Fetched by id, never searched: setlist.fm's search index stops about a day out
    /// (#29), so a show weeks away cannot be found by artist, venue or date — only
    /// asked for by the id sitting in the url of the page the user was on.
    /// Puts a **Bill** on the wall.
    ///
    /// Written unconditionally and with no network in it at all: entering a lineup has
    /// to work with the radio off, because the one place it definitely will not run is
    /// inside the enclosure. Android also goes looking for each **Act**'s song pool
    /// here — that is `fetchCandidates`, and it is the next split of #172.
    func addBill(name: String, city: String, from: String, to: String, lineup: String) {
        let acts = parseLineup(lineup)
        guard !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, !acts.isEmpty else {
            state.error = "A bill needs a name and at least one act."
            return
        }
        let bill = StoredBill(
            id: UUID().uuidString.lowercased(),
            name: name.trimmingCharacters(in: .whitespacesAndNewlines),
            city: city.trimmingCharacters(in: .whitespacesAndNewlines),
            from: from.trimmingCharacters(in: .whitespacesAndNewlines),
            to: to.trimmingCharacters(in: .whitespacesAndNewlines),
            acts: acts
        )
        state.bills.append(bill)
        Task { await timelines.saveBill(bill) }
    }

    /// Takes a **Bill** off the wall. The **Gigs** its **Acts** became are *not*
    /// touched: they are nights that happened, and they outlive the poster.
    func removeBill(_ billId: String) {
        state.bills.removeAll { $0.id == billId }
        Task { await timelines.removeBill(billId) }
    }

    /// An **Act** becomes a **Gig** I was at — the field gesture the whole **Bill**
    /// exists for.
    ///
    /// The night comes from `gigNight`, which refuses outright rather than guessing: the
    /// invariant is that a **Gig** minted from a **Bill** is dated inside that Bill's
    /// range, and a tap on the day after the festival ended has no honest answer.
    ///
    /// The venue is deliberately blank. A **Bill**'s name is the festival, not the room,
    /// and writing "Nordlys Fields" where setlist.fm will one day say "Verandaen, Skotbu"
    /// does not merely look wrong — it stops this night ever recognising itself in the
    /// published record of the same night. Blank resolves itself; a wrong answer does not.
    ///
    /// Tapped with no night chosen means "tonight", and that is a check-in — I am
    /// standing here. Picked off the range afterwards is `attended`: still my own
    /// evidence, but not a claim about where I am now.
    func markActPlayed(billId: String, actIndex: Int, chosen: String? = nil,
                       now: Date = Date()) {
        guard let bill = state.bills.first(where: { $0.id == billId }),
              actIndex < bill.acts.count else { return }
        let act = bill.acts[actIndex]
        guard act.gigId == nil else { return }
        guard let night = gigNight(bill, chosen: chosen, now: now) else { return }
        Task {
            let gigId = await timelines.createLocalGig(date: night, artist: act.name, venue: "")
            let gig = localGigSetlist(gigId: gigId, artist: act.name, date: night,
                                      venue: "", city: bill.city)
            await timelines.savePlanned(gig)
            let attendance = chosen == nil
                ? StoredAttendance(provenance: "checked_in",
                                   checkedInAt: Int64(Date().timeIntervalSince1970 * 1000))
                : StoredAttendance(provenance: "attended")
            await timelines.saveAttendance(setlistId: gigId, attendance: attendance)
            state.plannedGigs = sortedPlanned(state.plannedGigs + [gig])
            state.attendanceByGig[gigId] = attendance
            editBill(billId) { bill in
                var edited = bill
                edited.acts[actIndex].gigId = gigId
                return edited
            }
        }
    }

    /// A night I was at that setlist.fm has never heard of, typed in.
    ///
    /// The only way into this app that does not end at setlist.fm: no account, no API
    /// key, no catalogue — the poster in the window, the small venue nobody lists. It
    /// is the same `createLocalGig` a **Bill** mints an **Act**'s night with, reached
    /// from the other side.
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

    /// A mistap, undone — and what "undone" means depends on where the act came from.
    ///
    /// An act off the **Bill** goes back to having no night: the poster still says it is
    /// playing, so there is something to return to. A **Surprise** was typed by hand and
    /// has nothing to return to, so the whole act goes with the night.
    ///
    /// Refused when the night has media on it. That is not a mistap, and the photographs
    /// are irreplaceable — the act stops being on the **Bill** and the night stays,
    /// reachable as an ordinary night above today.
    func unmarkAct(billId: String, actIndex: Int) {
        guard let bill = state.bills.first(where: { $0.id == billId }),
              actIndex < bill.acts.count else { return }
        let act = bill.acts[actIndex]
        guard act.gigId != nil || act.surprise else { return }
        if let gigId = act.gigId {
            state.plannedGigs.removeAll { $0.id == gigId }
            state.timelineShows.removeAll { $0.id == gigId }
            state.attendanceByGig[gigId] = nil
        }
        Task {
            // An undated Surprise — a typo caught before it was ever tapped — has no gig
            // to strip, only an act to drop.
            var gone = true
            if let gigId = act.gigId { gone = await timelines.deleteGig(gigId) }
            let dropped = gone
            editBill(billId) { bill in
                var edited = bill
                if act.surprise && dropped {
                    edited.acts.remove(at: actIndex)
                } else {
                    edited.acts[actIndex].gigId = nil
                }
                return edited
            }
            if !gone { state.notice = "That night has photos on it, so it's been kept." }
        }
    }

    /// Fills in every unanswered **Act**'s candidate songs from setlist.fm.
    ///
    /// Opportunistic, never scheduled: opening a **Bill** that still holds acts nobody
    /// has an answer for is the reason to think a lookup might work. Not a timer, not a
    /// background job, no retry loop — in the enclosure it fails once and stops, and
    /// re-opening the **Bill** is the retry, a gesture someone makes when they have a
    /// reason to.
    ///
    /// An act is skipped once setlist.fm has *answered* about it, empty or not (see
    /// `StoredAct.tried`) — so a small local act nobody has ever logged costs one lookup
    /// in its life, while an act missed for want of signal is asked again.
    ///
    /// ponytail: sequential, one artist at a time. A lineup is a dozen names and this
    /// runs on a screen being looked at. Parallelise if a hundred-act bill turns up.
    func fetchCandidates(_ billId: String) {
        guard state.billFetching == nil else { return }
        let pending = (state.bills.first { $0.id == billId }?.acts ?? [])
            .enumerated()
            .filter { !$0.element.tried && $0.element.candidates.isEmpty }
        guard !pending.isEmpty else { return }
        state.billFetching = billId
        Task {
            defer { state.billFetching = nil }
            for (i, act) in pending {
                // A thrown request is *no answer*: leave the act untried so the next
                // open asks again. Only a reply — including "no such artist" — settles
                // the question.
                guard let found = try? await setlistFm.searchArtists(act.name) else { continue }
                // The first exact-name match, and there may be five of them — which
                // artist this landed on is recorded and shown, because a pool whose
                // source is unnamed cannot be distrusted.
                let artist = found.artist.first { $0.name.lowercased() == act.name.lowercased() }
                var songs: [String] = []
                if let artist {
                    guard let sets = try? await setlistFm.artistSetlists(artist.mbid) else { continue }
                    songs = candidateSongs(sets.setlist)
                }
                // Re-read each time: the field may have dated this act in between, and a
                // stale snapshot written back would undo it.
                editBill(billId) { bill in
                    var edited = bill
                    guard i < edited.acts.count else { return edited }
                    edited.acts[i].candidates = songs
                    edited.acts[i].matchedArtist = artist
                        .map { artistLabel(name: $0.name, disambiguation: $0.disambiguation) } ?? ""
                    edited.acts[i].mbid = artist?.mbid ?? ""
                    edited.acts[i].tried = true
                    return edited
                }
            }
        }
    }

    /// The name on the poster, corrected — and asked about again.
    ///
    /// A lineup is copied off a wall, and the wall is not authoritative about spelling:
    /// the history says *The* Silent Majority where the programme says Silent Majority,
    /// and `fetchCandidates` matches on the exact name, so one character is the
    /// difference between a song pool and "no setlist.fm history". Rather than guess at
    /// normalising names nobody has seen, let the person standing in front of the stage
    /// fix it and ask upstream again.
    ///
    /// Clearing `tried` is the point: it is what makes the act eligible for a lookup at
    /// all, and the pool, matched artist and mbid go with it because they describe an
    /// answer to the *old* name.
    ///
    /// The night, if the act already has one, is deliberately untouched — it is a record
    /// of what happened, not a line on a poster.
    func renameAct(billId: String, actIndex: Int, name: String) {
        let corrected = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let bill = state.bills.first(where: { $0.id == billId }),
              actIndex < bill.acts.count,
              !corrected.isEmpty, corrected != bill.acts[actIndex].name else { return }
        editBill(billId) { bill in
            var edited = bill
            edited.acts[actIndex].name = corrected
            edited.acts[actIndex].candidates = []
            edited.acts[actIndex].matchedArtist = ""
            edited.acts[actIndex].mbid = ""
            edited.acts[actIndex].tried = false
            return edited
        }
        fetchCandidates(billId)
    }

    /// An **Act** that never was on the poster. Added already dated, because a
    /// **Surprise** is only ever discovered after it has happened — there is no state in
    /// which an unannounced act is pending.
    ///
    /// Dated by the same rule as any other act, and refused outright when there is no
    /// honest date to give it: a hand-typed act is the easiest place of all to fabricate
    /// a night, not an exception to the invariant.
    func addSurpriseAct(billId: String, name: String, chosen: String? = nil,
                        now: Date = Date()) {
        let typed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !typed.isEmpty,
              let bill = state.bills.first(where: { $0.id == billId }),
              gigNight(bill, chosen: chosen, now: now) != nil else { return }
        let at = bill.acts.count
        editBill(billId) { bill in
            var edited = bill
            edited.acts.append(StoredAct(name: typed, surprise: true))
            return edited
        }
        markActPlayed(billId: billId, actIndex: at, chosen: chosen, now: now)
    }

    /// The pool came from the wrong band. Name one song you *know* they play, and the
    /// right one is found by it.
    ///
    /// A picker would ask "which of these five identically-named artists?", which nobody
    /// standing in a field can answer — the names are identical, that is the entire
    /// problem. "Name a song you know they play" is always answerable and is
    /// *meaningful*: it is the fact that actually distinguishes them.
    ///
    /// Done by pulling each same-named artist's recent setlists and looking, because
    /// there is no other way: `/search/setlists` has no song parameter. Cost is one
    /// extra request per namesake, on a deliberate tap.
    ///
    /// The named song is used to *identify* and is never written into the **Log**.
    /// Naming a song a band is known for is not a claim that they played it tonight, and
    /// quietly recording it as one would be the exact fabrication this feature avoids.
    func disambiguateAct(gigId: String, song: String) {
        let named = song.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !named.isEmpty, state.billFetching == nil,
              let bill = state.bills.first(where: { b in b.acts.contains { $0.gigId == gigId } }),
              let index = bill.acts.firstIndex(where: { $0.gigId == gigId })
        else { return }
        let act = bill.acts[index]
        state.billFetching = bill.id
        Task {
            defer { state.billFetching = nil }
            guard let found = try? await setlistFm.searchArtists(act.name) else {
                state.error = "Couldn't reach setlist.fm to check."
                return
            }
            let namesakes = found.artist.filter { $0.name.lowercased() == act.name.lowercased() }
            for artist in namesakes {
                guard let sets = try? await setlistFm.artistSetlists(artist.mbid) else { continue }
                guard playsSong(sets.setlist, named) else { continue }
                let label = artistLabel(name: artist.name, disambiguation: artist.disambiguation)
                let pool = candidateSongs(sets.setlist)
                editBill(bill.id) { b in
                    var edited = b
                    guard index < edited.acts.count else { return edited }
                    edited.acts[index].candidates = pool
                    edited.acts[index].matchedArtist = label
                    edited.acts[index].mbid = artist.mbid
                    edited.acts[index].tried = true
                    return edited
                }
                // The label, never the bare name. The name is the ambiguous string —
                // five bands answer to it — so "songs are from Silent Majority" confirms
                // nothing at all. What distinguishes them is the disambiguation, and the
                // song count says the swap actually landed.
                state.notice = "\(label) — \(pool.count) songs, from \"\(named)\"."
                return
            }
            // Honest dead end. The pool is left exactly as it was rather than cleared: a
            // pool that might be wrong still beats no pool, and it is labelled with
            // whose it is.
            state.error = "No band called \(act.name) has \"\(named)\" logged on setlist.fm."
        }
    }

    /// A night this app minted, now catalogued on setlist.fm, takes their id.
    ///
    /// A pasted link rather than a search by artist and date. #34 sketched the search,
    /// but the moment this is used is the moment you are looking at the page you just
    /// created, so its url is in your hand — and matching heuristics are a way to be
    /// wrong about which night you meant.
    func adoptSetlistLink(gigId: String, linkOrId: String) {
        guard let setlistId = parseSetlistId(linkOrId) else {
            state.error = "That doesn't look like a setlist.fm link."
            return
        }
        Task {
            guard await timelines.adoptSetlistId(gigId: gigId, setlistId: setlistId) else {
                state.error = "That night already has a setlist.fm id."
                return
            }
            state.notice = "Adopted — this night is on setlist.fm now."
            // The real record replaces the stub: it has the url, the songs whoever typed
            // them in logged, and an id friends' lines can meet at.
            guard let fresh = try? await setlistFm.setlist(setlistId) else { return }
            state.attendanceByGig[fresh.id] = await timelines.savePlanned(fresh)
            state.plannedGigs = sortedPlanned(state.plannedGigs.filter { $0.id != gigId } + [fresh])
            if state.selectedSetlist?.id == gigId { state.selectedSetlist = fresh }
            // An **Act** holds the id its night was minted with, and the line above just
            // changed the id that night is known by. Left alone the poster points at
            // nothing: the act's "open" leads nowhere, so the songs whoever typed them in
            // are unreachable — and because the lane hides a ticket only when some act
            // claims its id, the night draws a second time beside the **Bill** it
            // belongs to.
            repointActs(from: gigId, to: fresh.id)
        }
    }

    /// Moves every **Act**'s pointer from one gig id to another — the other half of
    /// adoption, which renames a night without moving any of its data.
    ///
    /// Touches only the **Bills** that actually point at `from`, so publishing one act
    /// does not rewrite a festival's whole poster.
    private func repointActs(from: String, to: String) {
        for bill in state.bills where bill.acts.contains(where: { $0.gigId == from }) {
            editBill(bill.id) { b in
                var edited = b
                for i in edited.acts.indices where edited.acts[i].gigId == from {
                    edited.acts[i].gigId = to
                }
                return edited
            }
        }
    }

    /// One **Bill**, changed in state and on disk together. Every edit goes through here
    /// so the two cannot drift.
    private func editBill(_ billId: String, _ change: (StoredBill) -> StoredBill) {
        guard let i = state.bills.firstIndex(where: { $0.id == billId }) else { return }
        let edited = change(state.bills[i])
        state.bills[i] = edited
        Task { await timelines.saveBill(edited) }
    }

    func addPlannedGig(_ linkOrId: String) {
        guard let id = parseSetlistId(linkOrId) else {
            state.error = "That doesn't look like a setlist.fm gig link."
            return
        }
        if state.plannedGigs.contains(where: { $0.id == id }) { return }
        state.planningLoading = true
        Task {
            do {
                let gig = try await setlistFm.setlist(id)
                state.attendanceByGig[gig.id] = await timelines.savePlanned(gig)
                state.plannedGigs = sortedPlanned(state.plannedGigs.filter { $0.id != gig.id } + [gig])
                state.planningLoading = false
            } catch {
                state.planningLoading = false
                fail(error)
            }
        }
    }

    /// Forgets a gig I'm not going to after all.
    func removePlannedGig(_ gigId: String) {
        state.plannedGigs = state.plannedGigs.filter { $0.id != gigId }
        state.attendanceByGig[gigId] = nil
        Task { await timelines.removePlanned(setlistId: gigId) }
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
                resolveFestivals()
            } catch {
                state.timelineLoading = false
                fail(error)
            }
        }
    }

    /// Asks which **Festival**, if any, the unidentified evenings currently on the
    /// timeline belong to. The rule itself (which evenings are candidates, that "no
    /// festival" is a real answer worth keeping, and that the answers are stored)
    /// lives in the logic layer; this is the after-a-fresh-import caller of it.
    func resolveFestivals() {
        let mine = state.timelineShows
        let ahead = plannedLane(state.plannedGigs, state.attendanceByGig)
        let known = state.festivals
        Task {
            // Two passes rather than one concatenated list, so a night ahead and a night
            // behind can never be read as one evening. The future lane grows its own
            // Sections (#134) and they want identities too — and it is `plannedLane` here
            // for the reason that function exists: the resolver and the lane have to be
            // looking at the same list.
            let found = await logic.resolveFestivals(mine: mine, known: known)
            let alsoAhead = await logic.resolveFestivals(mine: ahead, known: found)
            if alsoAhead == known { return }
            state.festivals = alsoAhead
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

    /// A Festival uncollapses in place — it never pushes a screen.
    func toggleFestival(_ key: String) {
        if state.expandedFestivals.contains(key) {
            state.expandedFestivals.remove(key)
        } else {
            state.expandedFestivals.insert(key)
        }
    }

    /// **Back out** of **Festival resolution** (#176). A Festival is uncollapsed in
    /// place and is never a screen, so there is no stack entry to pop — but it is
    /// still a rung, and **Back out** has no per-screen exception. Every uncollapsed
    /// Festival is at that one rung, so one swipe collapses all of them: that *is*
    /// one rung **Outer**, not several.
    ///
    /// False means nothing was open, and the Timeline is then at its outermost rung
    /// — where **Pinch**, not **Back out**, is the gesture.
    @discardableResult
    func backOutOfFestivals() -> Bool {
        if state.expandedFestivals.isEmpty { return false }
        state.expandedFestivals.removeAll()
        return true
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

    /// The same card as a BLE payload: adds the public key #28 makes the identity.
    /// Nil until I've set my username — a blank card is nothing to hand over.
    ///
    /// The key was 32 random bytes per launch until #265, a stand-in that read as an
    /// identity and was not one: a Contact who stored it could never match this device
    /// again, because the next launch was a different person as far as the key was
    /// concerned. It is now the durable Secure Enclave identity, which is what makes a
    /// card exchanged today still recognisable on a WiFi network next month.
    ///
    /// **Nil if the keychain refuses, which stops BLE exchange entirely — deliberately.**
    /// The alternative is handing out a card carrying a throwaway key, and that is the
    /// precise bug above: the far end persists it, believes it has a Contact, and has one
    /// that can never be matched again. A pairing that visibly does not happen is
    /// recoverable; one that appears to work and did not is not.
    func myProbeCard() -> ProbeCard? {
        guard let me = state.mySetlistFmUser.trimmingCharacters(in: .whitespaces).nilIfBlank,
              let key = ContactIdentity.publicKeyBase64()
        else { return nil }
        return ProbeCard(name: me, publicKey: key, setlistfm: me)
    }

    // MARK: - Reconcile over the same WiFi (#265)

    /// Whether there is anybody worth searching a network for: a **Contact** whose public
    /// key was actually persisted.
    ///
    /// This is the whole of the local-network permission gate. iOS raises that prompt the
    /// first time a browser or listener starts and offers no separate way to ask, so
    /// *when discovery starts* is the only lever there is — and a brand-new user pairing
    /// for the first time should not be asked for a permission that would do nothing.
    var hasReconcilableContact: Bool {
        state.friends.contains { $0.publicKey?.nilIfBlank != nil }
    }

    /// #265's LAN reconcile, screen-scoped: `start`/`stop` sit on `ExchangeView`'s own
    /// lifecycle, alongside the BLE session it already runs there.
    ///
    /// Contact keys are re-read on every session rather than captured at `start` — the
    /// list is the authority at the moment it is used, which is also what makes removing a
    /// Contact the whole of revocation (#265).
    ///
    /// The `hasReconcilableContact` gate above, by contrast, is only consulted at `start`.
    /// That is exactly right rather than a gap: every path that adds a Contact pops back to
    /// the root, so there is no way to gain a first Contact and still be on this screen.
    private lazy var contactExchange = ContactExchange(
        contactKeys: { [settings] in settings.friends.compactMap { $0.publicKey?.nilIfBlank } },
        manifest: { [timelines] in
            guard let me = ContactIdentity.publicKeyBase64() else { return HandoverManifest() }
            return await hashedContactManifest(await timelines.load(), me: me)
        },
        mine: { [timelines] in await timelines.load() },
        gallery: { [timelines] in
            let windows = await timelines.load().gigs.values
                .compactMap { photoWindow(gigDate: $0.date) }
            return await PhotoLibrary.galleryItems(dates: windows)
        },
        onLanded: { [timelines] landing in await timelines.mergeContactMedia(landing) }
    )

    func startContactExchange() {
        if hasReconcilableContact { contactExchange.start() }
    }

    func stopContactExchange() { contactExchange.stop() }

    // MARK: - Moving to a new phone (#142)

    private lazy var handoverExchange = HandoverExchange()

    /// Media id → the asset id its bytes live under, filled in when the manifest is built
    /// and read when the far end asks for an item. Held rather than re-derived per item:
    /// the alternative is loading the whole cache once per requested photograph.
    private var handoverRefs: [String: String] = [:]

    /// The old phone: listen, show the code, and hand over exactly what was ticked.
    ///
    /// The manifest is built *after* somebody connects rather than before the code is
    /// shown, because hashing the library walks every keepsake — the code should be on
    /// screen while that happens, not after it.
    func offerHandover(_ allow: Set<String>) {
        state.handover = HandoverUi(role: .source)
        let identities = Identities(setlistFmUser: settings.mySetlistFmUser)
        handoverExchange.offer(
            manifest: { [timelines, weak self] in
                let cache = await timelines.load()
                var refs: [String: String] = [:]
                for item in cache.gigMedia.values.flatMap({ $0 }) { refs[item.id] = item.ref }
                await self?.rememberHandoverRefs(refs)
                return await hashedDeviceManifest(cache, allow: allow, identities: identities)
            },
            identities: identities,
            mediaSource: { [weak self] id in
                guard let self, let ref = await self.handoverRef(id) else { return nil }
                return await PhotoLibrary.reconcileExport(assetId: ref, mediaId: id)
            },
            invite: { [weak self] uri in self?.state.handover.inviteUri = uri },
            progress: { [weak self] p in self?.state.handover.progress = p },
            finished: { [weak self] receipt, trouble in
                self?.state.handover.receipt = receipt
                self?.state.handover.error = trouble
            }
        )
    }

    /// The new phone, from the code the old one is showing. A link that is not a handover
    /// invite is not this screen's business and is left alone.
    func joinHandover(_ url: URL) {
        guard let invite = parseHandoverInvite(url.absoluteString) else { return }
        state.handover = HandoverUi(role: .receiver)
        handoverExchange.join(
            invite,
            mine: { [timelines] in await timelines.load() },
            gallery: { [timelines] in
                let windows = await timelines.load().gigs.values
                    .compactMap { photoWindow(gigDate: $0.date) }
                return await PhotoLibrary.galleryItems(dates: windows)
            },
            storeAccounts: { [weak self] payload in await self?.storeHandoverAccounts(payload) },
            apply: { [timelines] replan in
                let written = await timelines.applyHandover(replan)
                // The grid draws from the durable thumbnail tier and never from `ref`
                // (#98), so an item that skipped this would land as a blank cell. Both
                // ways an item can land need it: bytes that came over the wire, and a
                // hash match resolved against my own library under the sender's media id.
                for (id, ref) in written.fromGallery where !ref.isEmpty {
                    await PhotoLibrary.writeReconcileTiers(mediaId: id, ref: ref)
                }
            },
            progress: { [weak self] p in self?.state.handover.progress = p },
            finished: { [weak self] receipt, trouble in
                self?.state.handover.receipt = receipt
                self?.state.handover.error = trouble
                self?.loadTimeline()
            }
        )
    }

    /// Stops whatever is running. Cancelling is closing the connection — see
    /// `HandoverExchange` — and what already landed stays landed.
    func cancelHandover() {
        handoverExchange.stop()
        if state.handover.receipt == nil && state.handover.error == nil {
            state.handover.error = "The transfer was stopped. What arrived was kept."
        }
    }

    /// Leaves the screen: the session first, then the state the screen exists for.
    func dismissHandover() {
        handoverExchange.stop()
        handoverRefs = [:]
        state.handover = HandoverUi()
    }

    private func rememberHandoverRefs(_ refs: [String: String]) { handoverRefs = refs }

    private func handoverRef(_ id: String) -> String? { handoverRefs[id]?.nilIfBlank }

    /// The receiving half of the accounts step. Stored *before* the ack goes back, which
    /// is what the source's own sign-out is gated on — see `HandoverExchange.join`.
    private func storeHandoverAccounts(_ payload: AccountsPayload) async {
        if let user = payload.identities.setlistFmUser?.nilIfBlank {
            settings.saveMySetlistFmUser(user)
            state.mySetlistFmUser = user
        }
        if let token = payload.credentials.spotifyRefreshToken?.nilIfBlank {
            settings.saveHandoverCredentials(refresh: token, scope: payload.credentials.spotifyScope)
            state.spotifyConnected = true
            state.grantedScope = payload.credentials.spotifyScope ?? state.grantedScope
        }
    }

    /// A card handed to me. Writes into an empty space, promotes a **Followed line** the
    /// moment a key arrives, and **asks before changing a contact I already hold** (#188).
    ///
    /// Every route in comes through here — a deep link, a QR scan, a BLE write, a typed
    /// username, a playlist collaborator — so the question is answered once rather than
    /// at each door. Android decides it with the same function over the same four cases.
    func addFriend(_ friend: Friend) {
        switch friendArrival(friend, known: state.friends) {
        case .unchanged: break
        case .new(let f): writeFriend(f)
        // A **Followed line** becoming a **Contact**. Written as silently as a new one:
        // there was no key held, so nothing is being overwritten.
        case .promotion(let f): writeFriend(f)
        case .conflict(let existing, let incoming):
            state.friendConflict = FriendConflict(existing: existing, incoming: incoming)
        }
    }

    /// The confirmed overwrite, and the only thing the prompt can do besides nothing.
    func confirmFriendOverwrite() {
        guard let pending = state.friendConflict else { return }
        state.friendConflict = nil
        writeFriend(pending.incoming)
    }

    /// Cancel: the held record is left exactly as it was. Also what dismissing does, so
    /// doing nothing can never be an accidental yes.
    func dismissFriendOverwrite() { state.friendConflict = nil }

    private func writeFriend(_ friend: Friend) {
        // De-dupe on setlist.fm username; a re-share updates the display name.
        let existing = state.friends.first { $0.setlistfm.lowercased() == friend.setlistfm.lowercased() }
        var incoming = friend
        // A key already held is never dropped by a later, thinner way of meeting the same
        // person. Only a BLE card carries one — a share link, a username typed in, a
        // playlist collaborator, all arrive without — and replacing the record wholesale
        // would silently unmake the Contact for #265, permanently: there is no second
        // moment to collect the key (`CardWire`), only a second exchange.
        if incoming.publicKey?.nilIfBlank == nil { incoming.publicKey = existing?.publicKey }
        let next = state.friends.filter { $0.setlistfm.lowercased() != friend.setlistfm.lowercased() } + [incoming]
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
            state.attendanceByGig[fetched.id] = await timelines.savePlanned(fetched)
            // Keeps the future edge in step with what was just written — without this
            // an invited-in gig would not appear above tonight until the next launch.
            state.plannedGigs = sortedPlanned(state.plannedGigs.filter { $0.id != fetched.id } + [fetched])
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
            for: setlist, mine: state.timelineShows, festivals: state.festivals
        )

        state.selectedSetlist = setlist
        // Answered twice on purpose: now from the two lists, which are already in
        // hand, and again in `loadGigMedia` once the store hands back the attendance
        // claim. Starting at the answer the lists give rather than at `false` is what
        // keeps a night that is plainly mine from drawing itself read-only for a frame.
        markSelectedOwnership(setlist, attendance: nil)
        loadGigMedia(setlist)
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

    private func loadGigMedia(_ setlist: FmSetlist) {
        state.gigMediaSuggestions = []
        state.selectedAttendance = nil
        Task {
            let cache = await timelines.load()
            guard state.selectedSetlist?.id == setlist.id else { return }
            // The whole map: opening any night is also the cheapest moment to refresh
            // what the Timeline behind it is drawing.
            state.mediaBySetlist = cache.media()
            state.selectedAttendance = cache.attendance()[setlist.id]
            markSelectedOwnership(setlist, attendance: state.selectedAttendance)
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
            // The claim goes into state as well as onto disk: the night has just stopped
            // being a plan, and the lane it leaves is drawn from this map.
            state.attendanceByGig[gigId] = attendance
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
                state.mediaBySetlist[setlist.id] = media
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
        state.mediaBySetlist[setlist.id] = media
        Task { await timelines.saveMedia(setlistId: setlist.id, media: media) }
    }

    /// Removing means removing: the record goes, and so do the bytes it owned.
    func removeMedia(_ media: StoredMedia) {
        guard let setlist = state.selectedSetlist else { return }
        let kept = state.gigMedia.filter { $0.id != media.id }
        state.mediaBySetlist[setlist.id] = kept
        Task {
            await timelines.saveMedia(setlistId: setlist.id, media: kept)
            PhotoLibrary.deleteThumbnails(media.id)
            refreshSuggestions(setlist)
        }
    }

    /// Write, edit or clear my Note in one Band (#50, porting Android's
    /// `setGigNote`).
    ///
    /// At most one of mine per band, so this is an upsert keyed by band rather
    /// than by id: the write-line the finger landed on already said which one
    /// it means. Two notes in a band would need arranging, arranging would
    /// need the handle, and the thing being served is one opinion about one
    /// night.
    ///
    /// Emptying it removes it. A note with nothing in it is not something
    /// anyone wrote, and leaving an empty record behind would make the shared
    /// band claim a contributor who said nothing — which would turn a night
    /// green over blank text.
    func setGigNote(_ band: Band, text: String) {
        guard let setlist = state.selectedSetlist else { return }
        let had = state.gigMedia
        let personal = band == .vault
        let mine = had.first { $0.kind == StoredMedia.Kind.note && $0.from == nil && $0.personal == personal }
        let written = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let media: [StoredMedia]
        if let mine, written.isEmpty {
            media = had.filter { $0.id != mine.id }
        } else if let mine {
            media = had.map { item in
                guard item.id == mine.id else { return item }
                var m = item
                m.text = written
                return m
            }
        } else if written.isEmpty {
            media = had
        } else {
            media = had + [StoredMedia(
                id: UUID().uuidString.lowercased(),
                kind: StoredMedia.Kind.note,
                // When it was written. It is what sorts received notes, and a
                // note has no camera to ask for anything better.
                capturedAt: Int64(Date().timeIntervalSince1970 * 1000),
                personal: personal,
                text: written
            )]
        }
        state.mediaBySetlist[setlist.id] = media
        Task { await timelines.saveMedia(setlistId: setlist.id, media: media) }
    }

    /// Set or unset the Verdict on one of my Notes (porting Android's
    /// `setGigVerdict`).
    ///
    /// Tapping the one already set passes nil, because unset has to stay
    /// reachable — it is a real state, and a night I have stopped having an
    /// opinion about must not be stuck wearing the one I had.
    func setGigVerdict(_ noteId: String, verdict: String?) {
        guard let setlist = state.selectedSetlist else { return }
        let had = state.gigMedia
        // Mine only. A received note's verdict is its sender's statement and
        // is not mine to edit, the same way their photograph is not mine to
        // reposition.
        guard had.contains(where: { $0.id == noteId && $0.from == nil }) else { return }
        let media = had.map { item -> StoredMedia in
            guard item.id == noteId else { return item }
            var m = item
            m.verdict = verdict
            return m
        }
        state.mediaBySetlist[setlist.id] = media
        Task { await timelines.saveMedia(setlistId: setlist.id, media: media) }
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

    // --- The Curtain: the Window this Room has onto a data source (#129) ---

    /// Pull the **Curtain** down on the open **Gig**.
    ///
    /// Which **Window** is behind it is `gigOffers`' answer and never this call
    /// site's — two places deciding when to fetch is how they drift, which is the
    /// whole reason `Curtain` is a returned instruction. `curtainAction` is the
    /// dispatch, pure and asserted by the same cases on both platforms; only the
    /// plumbing it names lives here.
    ///
    /// A failed pull changes nothing and shows nothing. Being offline costs nothing.
    func pullCurtain(_ curtain: Curtain) async {
        switch curtainAction(curtain) {
        case .fetchCatalogue: await fetchCatalogue(state.selectedSetlist?.artist?.mbid)
        case .fetchSetlist: await refreshSelectedSetlist()
        // No "did this event move" endpoint exists, so `checkEvent` asks for
        // nothing rather than for a fetch pretending to be one.
        case .nothing: break
        }
    }

    /// Ask setlist.fm what this night says now: someone may have posted the set, or
    /// filled a record that was linked and empty.
    ///
    /// A local **Gig**'s id is this app's and not setlist.fm's, so asking for it is a
    /// guaranteed 404 dressed up as an error nobody can act on. The way a local night
    /// gets a real record is adoption, not refresh.
    private func refreshSelectedSetlist() async {
        guard let open = state.selectedSetlist, open.url != nil,
              let fresh = try? await setlistFm.setlist(open.id),
              state.selectedSetlist?.id == fresh.id
        else { return }
        state.selectedSetlist = fresh
        state.timelineShows = state.timelineShows.map { $0.id == fresh.id ? fresh : $0 }
        // A gig I'm going to lives in its own list, so refreshing one has to write
        // back there — otherwise the night's setlist appears on screen and is gone
        // again on the next launch. Provenance is untouched: songs landing is
        // setlist.fm filling a record in, not evidence that I went.
        if state.plannedGigs.contains(where: { $0.id == fresh.id }) {
            state.plannedGigs = state.plannedGigs.map { $0.id == fresh.id ? fresh : $0 }
            await timelines.savePlanned(fresh)
        }
    }

    /// The artist's own songs, for a **Log** being typed with nothing posted yet.
    ///
    /// Asked once per artist. MusicBrainz is CC0 and a back catalogue does not change
    /// over an evening, so re-asking would spend a rate limit on the same answer.
    private func fetchCatalogue(_ mbid: String?) async {
        guard let mbid, !mbid.trimmingCharacters(in: .whitespaces).isEmpty,
              state.catalogueByArtist[mbid] == nil, state.catalogueFetching == nil
        else { return }
        state.catalogueFetching = mbid
        let titles = await musicBrainz.catalogue(mbid: mbid)
        state.catalogueFetching = nil
        // An empty answer is not stored: MusicBrainz going quiet (ADR-0004) must not
        // become "this artist has no songs" for the rest of the session.
        if !titles.isEmpty { state.catalogueByArtist[mbid] = titles }
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
        Task { await timelines.saveLog(setlistId: setlist.id, log: updated) }
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
