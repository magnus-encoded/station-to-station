import Foundation

/// Planned **Gigs**: loading them, adding one by link or by hand, minting, the calendar,
/// and committing a **Programme**.
@MainActor
final class PlanningController {
    let host: StateHost
    private let timelines: TimelineStore
    private let setlistFm: SetlistFmClient
    private let musicBrainz: MusicBrainzClient
    private let gig: GigController
    private let gossip: GossipController
    private let loadTimeline: () -> Void
    private let markSelectedOwnership: (FmSetlist, StoredAttendance?) -> Void
    /// The in-flight suggestion lookup, held so the next keystroke can cancel it.
    private var artistSearch: Task<Void, Never>?
    var onGigAdded: ((String) -> Void)?

    init(
        host: StateHost,
        timelines: TimelineStore,
        setlistFm: SetlistFmClient,
        musicBrainz: MusicBrainzClient,
        gig: GigController,
        gossip: GossipController,
        loadTimeline: @escaping () -> Void,
        markSelectedOwnership: @escaping (FmSetlist, StoredAttendance?) -> Void
    ) {
        self.host = host
        self.timelines = timelines
        self.setlistFm = setlistFm
        self.musicBrainz = musicBrainz
        self.gig = gig
        self.gossip = gossip
        self.loadTimeline = loadTimeline
        self.markSelectedOwnership = markSelectedOwnership
    }

    /// Furthest-future first — the same descending order the attended rows below
    /// already use: up is always later, and a planned gig is not an exception to that.

    /// The future edge, from disk (#175). Called alongside the Spine at launch, and
    /// again by every write below so the timeline never shows stale plans.
    func loadPlannedGigs() {
        Task { await refreshPlannedGigs() }
    }

    func refreshPlannedGigs() async {
        let cache = await timelines.load()
        host.state.plannedGigs = sortedPlanned(cache.planned())
        // The Contact list is read at launch, but the nights are not there until here,
        // and the gossip channel wants both (#417).
        gossip.contactsChanged()
        host.state.attendanceByGig = cache.attendance()
        host.state.calendarEventByGig = cache.calendarEvents()
        // The Timeline draws keepsakes on its rows, so this has to be here before
        // any night is opened — and this already reads the cache at launch and
        // after every write.
        host.state.mediaBySetlist = cache.media()
        host.state.mediaOffers = cache.mediaOffers
        host.state.nightJoins = cache.spineJoins()
        host.state.nightsApart = cache.spineDismissals()
        host.state.playlistsBySetlist = cache.playlists()
        host.state.hiddenAt = cache.hiddenLines
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
            host.state.artistSuggestions = []
            return
        }
        artistSearch = Task { [musicBrainz] in
            try? await Task.sleep(nanoseconds: 350_000_000)
            guard !Task.isCancelled else { return }
            let hits = await musicBrainz.searchArtists(query: query)
            guard !Task.isCancelled else { return }
            host.state.artistSuggestions = hits
        }
    }

    /// The typed name was replaced by a picked one, so the list has done its job.
    func clearArtistSuggestions() {
        artistSearch?.cancel()
        host.state.artistSuggestions = []
    }

    /// The one add form's write. The date decides the rule underneath: a night before
    /// today is one I was at (`addLocalGig`), any other is one I am going to
    /// (`addPlannedGigByHand`).
    func addGig(artist: String, venue: String, date: String, today: String = isoToday()) {
        guard let night = isoDate(fromFm: date.trimmingCharacters(in: .whitespaces)),
              !artist.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            host.state.error = "A night needs who played and a date as dd-MM-yyyy."
            host.state.errorKind = nil
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
            host.state.error = "A night needs who is playing and a date as dd-MM-yyyy."
            host.state.errorKind = nil
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
    func mintPlannedGig(artist: String, venue: String, night: Date) async -> String {
        let day = fmDate(night)
        let gigId = await timelines.createLocalGig(date: day, artist: artist, venue: venue)
        let gig = localGigSetlist(gigId: gigId, artist: artist, date: day,
                                  venue: venue, city: "")
        // The claim goes into state as well as onto disk. `plannedLane` filters on
        // it, so a gig added without it is written correctly and then drawn by
        // nothing — the night appears only after a restart, which reads as Add
        // having done nothing at all.
        host.state.attendanceByGig[gigId] = await timelines.savePlanned(gig)
        host.state.plannedGigs = sortedPlanned(host.state.plannedGigs + [gig])
        onGigAdded?(gigId)
        return gigId
    }

    /// A **Ticket**'s night, claimed by its date as `joinGig` claims a **Contact**'s: a
    /// night before `today` is one I was at. A ticket names a night, not a night still to
    /// come, and left `planned` a past one sits above today forever.
    func claimTicketNight(_ gigId: String, date: String?, today: String = isoToday()) async {
        guard nightKind(date: isoDate(fromFm: date), today: today) == .wasAt else { return }
        let attended = (host.state.attendanceByGig[gigId] ?? StoredAttendance()).withProvenance("attended")
        await timelines.saveAttendance(setlistId: gigId, attendance: attended)
        host.state.attendanceByGig[gigId] = attended
    }

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
            host.state.error = "A night needs who played and a date as dd-MM-yyyy."
            host.state.errorKind = nil
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
            host.state.plannedGigs = sortedPlanned(host.state.plannedGigs + [gig])
            host.state.attendanceByGig[gigId] = attendance
            // The Spine is built from the store, and this night has just joined it.
            loadTimeline()
            onGigAdded?(gigId)
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
            host.state.plannedGigs = sortedPlanned(host.state.plannedGigs.filter { !dropped.contains($0.id) } + minted)
            for (id, attendance) in attendances { host.state.attendanceByGig[id] = attendance }
            for id in dropped { host.state.attendanceByGig[id] = nil }
            host.state.festivals = host.state.festivals + Festivals(byId: [festivalId: festival], idByShow: membership)
            // The Spine is built from the store, and a played act just committed
            // may have joined it this instant rather than at the next cold start.
            loadTimeline()
        }
    }

    func addPlannedGig(_ linkOrId: String) {
        guard let id = parseSetlistId(linkOrId) else {
            host.state.error = "That doesn't look like a setlist.fm gig link."
            host.state.errorKind = nil
            return
        }
        if host.state.plannedGigs.contains(where: { $0.id == id }) { return }
        host.state.planningLoading = true
        Task {
            do {
                let gig = try await setlistFm.setlist(id)
                await planFmGig(gig)
                host.state.planningLoading = false
            } catch {
                host.state.planningLoading = false
                host.fail(error)
            }
        }
    }

    /// A setlist.fm night onto the plan, as setlist.fm has it: `addPlannedGig`'s write,
    /// shared with a ticket whose lookup found its night (#531).
    func planFmGig(_ hit: FmSetlist) async {
        host.state.attendanceByGig[hit.id] = await timelines.savePlanned(hit)
        host.state.plannedGigs = sortedPlanned(host.state.plannedGigs.filter { $0.id != hit.id } + [hit])
    }

    /// A calendar event was just made for a planned gig; remember its identifier.
    /// Presence of it is what a leaf reads as "already added" (#175).
    func markCalendarAdded(_ gigId: String, eventId: String) {
        host.state.calendarEventByGig[gigId] = eventId
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
                host.state.error = "Couldn't add this to your calendar."
                host.state.errorKind = nil
            }
        }
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
            host.state.plannedGigs = sortedPlanned(host.state.plannedGigs.filter { $0.id != show.id } + [show])
            host.state.attendanceByGig[show.id] = attendance
            host.state.selectedAttendance = attendance
            markSelectedOwnership(show, attendance)
            loadTimeline()
        }
    }
}
