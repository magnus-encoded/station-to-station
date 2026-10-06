import Foundation

/// Searching setlist.fm, the attended list and its paging, pulls on a **Gig**'s **Curtain**,
/// and the setlist.fm lookups and matches for local **Gig**s.
@MainActor
final class SetlistsController {

    unowned let host: StateHost
    private let setlistFm: SetlistFmClient
    private let musicBrainz: MusicBrainzClient
    private let timelines: TimelineStore
    private let settings: Settings
    private let saveMySetlistFmUser: (String) -> Void
    private let adoptSetlist: (_ gigId: String, _ setlistId: String, _ fresh: FmSetlist?, _ notice: Bool) async -> Bool
    private let storeAttendance: (String, StoredAttendance) -> Void
    private let lineArtists: () -> [FmArtist]
    private var lookupChecks: Task<Void, Never>?

    init(
        host: StateHost,
        setlistFm: SetlistFmClient,
        musicBrainz: MusicBrainzClient,
        timelines: TimelineStore,
        settings: Settings,
        saveMySetlistFmUser: @escaping (String) -> Void,
        adoptSetlist: @escaping (String, String, FmSetlist?, Bool) async -> Bool,
        storeAttendance: @escaping (String, StoredAttendance) -> Void,
        lineArtists: @escaping () -> [FmArtist]
    ) {
        self.host = host
        self.setlistFm = setlistFm
        self.musicBrainz = musicBrainz
        self.timelines = timelines
        self.settings = settings
        self.saveMySetlistFmUser = saveMySetlistFmUser
        self.adoptSetlist = adoptSetlist
        self.storeAttendance = storeAttendance
        self.lineArtists = lineArtists
    }

    func setArtistQuery(_ q: String) { host.state.artistQuery = q }
    func setUserQuery(_ q: String) { host.state.userQuery = q }

    func searchArtists() {
        let query = host.state.artistQuery.trimmingCharacters(in: .whitespaces)
        if query.isEmpty { return }
        host.state.searchLoading = true
        Task {
            do {
                let result = try await setlistFm.searchArtists(query)
                host.state.artistResults = result.artist
                host.state.searchLoading = false
            } catch {
                host.fail(error)
            }
        }
    }

    func openArtist(_ artist: FmArtist) {
        host.state.source = .artist
        host.state.setlistsTitle = artist.name
        host.state.setlists = []
        host.state.setlistsPage = 1
        host.state.setlistsTotal = 0
        host.state.setlistsLoading = true
        Task {
            do {
                let result = try await setlistFm.artistSetlists(artist.mbid)
                host.state.setlists = result.setlist
                host.state.setlistsTotal = result.total
                host.state.setlistsLoading = false
            } catch {
                host.fail(error)
            }
        }
    }

    func openUserAttended() {
        let userId = host.state.userQuery.trimmingCharacters(in: .whitespaces)
        if userId.isEmpty { return }
        // "My concerts" is your own username; adopt it as the identity used to
        // stamp playlists and find shared concerts — but never clobber an
        // explicit choice.
        if host.state.mySetlistFmUser.trimmingCharacters(in: .whitespaces).isEmpty {
            saveMySetlistFmUser(userId)
        }
        host.state.source = .user
        host.state.setlistsTitle = "Attended by \(userId)"
        host.state.setlists = []
        host.state.setlistsPage = 1
        host.state.setlistsTotal = 0
        host.state.setlistsLoading = true
        Task {
            do {
                let result = try await setlistFm.userAttended(userId)
                host.state.setlists = result.setlist
                host.state.setlistsTotal = result.total
                host.state.setlistsLoading = false
            } catch {
                host.fail(error)
            }
        }
    }

    func loadMoreSetlists() {
        let s = host.state
        if s.setlistsLoading || s.setlists.count >= s.setlistsTotal { return }
        let nextPage = s.setlistsPage + 1
        host.state.setlistsLoading = true
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
                host.state.setlists += result.setlist
                host.state.setlistsPage = nextPage
                host.state.setlistsTotal = result.total
                host.state.setlistsLoading = false
            } catch {
                host.fail(error)
            }
        }
    }

    /// Pull the **Curtain** down on the open **Gig**.
    ///
    /// Which **Window** is behind it is `gigOffers`' answer and never this call
    /// site's — two places deciding when to fetch is how they drift, which is the
    /// whole reason `Curtain` is a returned instruction. `curtainAction` is the
    /// dispatch, pure and asserted by the same cases on both platforms; only the
    /// plumbing it names lives here.
    ///
    /// A failed pull changes nothing and shows nothing. Being offline costs nothing.
    ///
    /// A local **Gig** is asked of setlist.fm first, whatever the curtain says (#531): the
    /// pull is how a person says "is it there yet?". The curtain's own action then runs
    /// as it always has, less the setlist refresh the lookup already was.
    func pullCurtain(_ curtain: Curtain) async {
        let local = host.state.selectedSetlist?.isLocal == true
        if local { await refreshSelectedSetlist() }
        switch curtainAction(curtain) {
        case .fetchCatalogue: await fetchCatalogue(host.state.selectedSetlist?.artist?.mbid)
        case .fetchSetlist: if !local { await refreshSelectedSetlist() }
        // No "did this event move" endpoint exists, so `checkEvent` asks for
        // nothing rather than for a fetch pretending to be one.
        case .nothing: break
        }
    }

    /// Ask setlist.fm what this night says now: someone may have posted the set, or
    /// filled a record that was linked and empty.
    ///
    /// A local **Gig**'s id is this app's and not setlist.fm's, so asking for it is a
    /// guaranteed 404. A pull on one asks setlist.fm whether the night is there yet
    /// instead (#531), by artist and day, the way the automatic checks do.
    private func refreshSelectedSetlist() async {
        guard let open = host.state.selectedSetlist else { return }
        if open.isLocal {
            await refreshLocalGig(open.id)
            return
        }
        guard let fresh = try? await setlistFm.setlist(open.id),
              host.state.selectedSetlist?.id == fresh.id
        else { return }
        host.state.selectedSetlist = fresh
        host.state.timelineShows = host.state.timelineShows.map { $0.id == fresh.id ? fresh : $0 }
        // A gig I'm going to lives in its own list, so refreshing one has to write
        // back there — otherwise the night's setlist appears on screen and is gone
        // again on the next launch. Provenance is untouched: songs landing is
        // setlist.fm filling a record in, not evidence that I went.
        if host.state.plannedGigs.contains(where: { $0.id == fresh.id }) {
            host.state.plannedGigs = host.state.plannedGigs.map { $0.id == fresh.id ? fresh : $0 }
            await timelines.savePlanned(fresh)
        }
    }

    /// The automatic checks' longest sleep: a night coming due is noticed within this.
    private static let lookupCheckCap: TimeInterval = 5 * 60

    private var nowMillis: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    /// `answer` onto local Gig `gigId`'s stored lookup; nothing for a setlist.fm night or
    /// an empty answer.
    func stampLookup(gigId: String, _ answer: TicketSetlistFmAnswer) async {
        guard answer.recordsAnything,
              let gig = await timelines.load().gigs[gigId], gig.setlistId == nil,
              let settled = await timelines.editSetlistFmLookup(gigId: gigId, { answer.applyTo($0) })
        else { return }
        storeAttendance(gigId, settled)
    }

    /// A pull on local Gig `gigId`: one lookup now, whatever the schedule says, unless
    /// the last one went out under a minute ago — then nothing is sent or stamped, and
    /// the notice says the checks carry on.
    private func refreshLocalGig(_ gigId: String) async {
        let last = host.state.attendanceByGig[gigId]?.setlistFmLookup?.lastLookupAt
            .map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) }
        if manualSetlistFmLookup(lastLookupAt: last, now: Date()) == .friction {
            host.state.notice = lookupFrictionMessage
            return
        }
        await lookUpLocalGig(gigId, manual: true)
    }

    /// One setlist.fm lookup for local Gig `gigId`: `search/setlists` by its artist and
    /// day, no venue, held to the night by `setlistFmLookupOutcome`. A sure hit is adopted
    /// with the "Adopted" notice; a doubtful one becomes the "Possible match" chip;
    /// nothing only stamps. `manual` is a pull, which says so when setlist.fm refuses or
    /// fails; the automatic checks say nothing.
    ///
    /// False when setlist.fm refused for its quota, which stops a batch of checks.
    @discardableResult
    func lookUpLocalGig(_ gigId: String, manual: Bool) async -> Bool {
        guard let gig = await timelines.load().gigs[gigId] else { return true }
        if gig.setlistId != nil || gig.artist.trimmingCharacters(in: .whitespaces).isEmpty
            || parseFmDate(gig.date) == nil { return true }
        let at = nowMillis
        let hits: [FmSetlist]
        do {
            hits = try await setlistFm.searchSetlists(artistName: gig.artist, date: gig.date).setlist
        } catch {
            // The checks were stopped under it: nothing was learned, so nothing is stamped.
            if Task.isCancelled || error is CancellationError { return true }
            // The request went out (or was refused on the quota): stamped either way, so
            // the schedule does not ask again at once.
            if let settled = await timelines.editSetlistFmLookup(gigId: gigId, { $0.lookedUp(at: at) }) {
                storeAttendance(gigId, settled)
            }
            if error is SetlistFmRateLimited {
                if manual { host.fail(error) }
                return false
            }
            if manual { host.state.notice = "setlist.fm didn't have that one just now — showing what's saved." }
            return true
        }
        let ticket = Ticket(artist: gig.artist, venue: gig.venue.nilIfBlank, date: gigDay(gig.date))
        let artists = lineArtists()
        var outcome: LookupOutcome?
        let settled = await timelines.editSetlistFmLookup(gigId: gigId) { had in
            let next = setlistFmLookupOutcome(ticket, hits: hits, lineArtists: artists,
                                              lookup: had, nowMillis: at)
            outcome = next
            // A sure hit settles any question a chip was still asking.
            if case .adopt = next { return next.next.asking([]) }
            return next.next
        }
        guard let settled else { return true }
        storeAttendance(gigId, settled)
        if case .adopt(let hit, _)? = outcome {
            _ = await adoptSetlist(gigId, hit.id, hit, true)
        }
        return true
    }

    /// The automatic setlist.fm checks, while the app is in the foreground: at launch, on
    /// coming back, and on a timer. Each pass plans every local Gig with
    /// `setlistFmLookupPlan`, looks up the ones due one at a time, and sleeps until the
    /// next is due, five minutes at most. Nothing at all without a setlist.fm key. Called
    /// from `init` and on `.active`; `stopLookupChecks` on leaving the foreground.
    func startLookupChecks() {
        guard lookupChecks == nil else { return }
        lookupChecks = Task {
            while !Task.isCancelled {
                for gigId in (await lookupPlan())?.dueNow ?? [] {
                    if Task.isCancelled { return }
                    if !(await lookUpLocalGig(gigId, manual: false)) { break }
                }
                let next = await lookupPlan()
                var pause = Self.lookupCheckCap
                if let next, next.dueNow.isEmpty, let wake = next.nextWakeAt {
                    pause = min(max(wake.timeIntervalSinceNow, 1), Self.lookupCheckCap)
                }
                try? await Task.sleep(nanoseconds: UInt64(pause * 1_000_000_000))
            }
        }
    }

    /// The app left the foreground: no lookups until `startLookupChecks` again.
    func stopLookupChecks() {
        lookupChecks?.cancel()
        lookupChecks = nil
    }

    /// What the automatic checks do next, from the store: every local Gig with a claim on
    /// it (planned or attended) and an artist to search by. Nil without a setlist.fm key.
    private func lookupPlan() async -> LookupPlan? {
        guard let key = settings.setlistFmKey else { return nil }
        let cache = await timelines.load()
        let ends = gossipParticipationEnds(cache: cache, stoppedAt: GossipTransport.shared.stoppedAt)
        let gigs: [LookupGig] = cache.gigs.values.compactMap { (gig: StoredGig) -> LookupGig? in
            guard gig.setlistId == nil, !gig.artist.trimmingCharacters(in: .whitespaces).isEmpty,
                  parseFmDate(gig.date) != nil,
                  let attendance = cache.gigAttendance[gig.id]
            else { return nil }
            let until = ends[gig.id].flatMap { $0 > 0 ? Date(timeIntervalSince1970: TimeInterval($0) / 1000) : nil }
            return LookupGig(id: gig.id, date: gig.date, local: true,
                             lookup: attendance.setlistFmLookup, participationUntil: until)
        }
        return setlistFmLookupPlan(gigs, now: Date(), sharedKey: key.shared,
                                   sharedQuotaSpentAt: settings.setlistFmSharedQuotaSpentAt)
    }

    /// The hits local Gig `gigId`'s "Possible match on setlist.fm" chip asks about, best
    /// first: the stored snapshot, or — where that was lost but ids are still pending —
    /// each fetched from setlist.fm afresh. Empty when nothing is pending.
    func setlistFmChipHits(gigId: String) async -> [StoredSetlistFmHit] {
        guard let lookup = host.state.attendanceByGig[gigId]?.setlistFmLookup,
              lookup.possibleMatchPending else { return [] }
        let stored = lookup.chipHits()
        if !stored.isEmpty { return stored }
        var fetched: [StoredSetlistFmHit] = []
        for id in lookup.pendingHitIds {
            if let hit = try? await setlistFm.setlist(id) { fetched.append(hit.asStoredHit()) }
        }
        return fetched
    }

    /// The chip's "Yes": the question is settled and local Gig `gigId` takes hit `setlistId`.
    func acceptSetlistFmMatch(gigId: String, setlistId: String) {
        Task {
            if let settled = await timelines.editSetlistFmLookup(gigId: gigId, { $0.asking([]) }) {
                storeAttendance(gigId, settled)
            }
            if !(await adoptSetlist(gigId, setlistId, nil, true)) {
                host.state.error = "That night already has a setlist.fm id."
                host.state.errorKind = nil
            }
        }
    }

    func rejectSetlistFmMatches(gigId: String) {
        Task {
            guard let settled = await timelines.editSetlistFmLookup(gigId: gigId, { $0.rejectingPending() })
            else { return }
            storeAttendance(gigId, settled)
        }
    }

    /// The artist's own songs, for a **Log** being typed with nothing posted yet.
    ///
    /// Asked once per artist. MusicBrainz is CC0 and a back catalogue does not change
    /// over an evening, so re-asking would spend a rate limit on the same answer.
    private func fetchCatalogue(_ mbid: String?) async {
        guard let mbid, !mbid.trimmingCharacters(in: .whitespaces).isEmpty,
              host.state.catalogueByArtist[mbid] == nil, host.state.catalogueFetching == nil
        else { return }
        host.state.catalogueFetching = mbid
        let titles = await musicBrainz.catalogue(mbid: mbid)
        host.state.catalogueFetching = nil
        // An empty answer is not stored: MusicBrainz going quiet (ADR-0004) must not
        // become "this artist has no songs" for the rest of the session.
        if !titles.isEmpty { host.state.catalogueByArtist[mbid] = titles }
    }
}
