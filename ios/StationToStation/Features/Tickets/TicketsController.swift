import Foundation

/// A shared **Ticket**, for a past night or a future one: the share-extension inbox, the
/// routing, the confirm prompt, its setlist.fm search, and the **Admissions** it carries.
@MainActor
final class TicketsController {

    private let host: StateHost
    private let timelines: TimelineStore
    private let setlistFm: SetlistFmClient
    private let planning: PlanningController
    private let setlists: SetlistsController
    private let gig: GigController

    init(host: StateHost,
         timelines: TimelineStore,
         setlistFm: SetlistFmClient,
         planning: PlanningController,
         setlists: SetlistsController,
         gig: GigController) {
        self.host = host
        self.timelines = timelines
        self.setlistFm = setlistFm
        self.planning = planning
        self.setlists = setlists
        self.gig = gig
    }

    /// Everything the Share Extension has left in the inbox, routed onto the **Line**.
    ///
    /// Called on every foreground rather than at launch alone: the share sheet does
    /// not bring this app forward, so a **Ticket** is almost always deposited while
    /// the app is in the background and has to be noticed on the way back in.
    ///
    /// **A deposit leaves the box only once what it became is on disk** (the #441
    /// review): minted or attached here, or — for one that goes to the prompt — once the
    /// prompt is answered either way. A kill anywhere before that routes it again on the
    /// next launch, which is safe: a match attaches nothing twice (`attachAdmissions`
    /// dedups by payload), and a mint that landed is a known night the second time.
    /// `depositsInHand` keeps a second drain in the same run (the foreground arriving
    /// while the first is still in Vision) from routing one twice.
    func drainTicketInbox(now: Date = Date()) {
        let deposits = TicketInbox.pending().filter { !depositsInHand.contains($0.id) }
        guard !deposits.isEmpty else { return }
        depositsInHand.formUnion(deposits.map(\.id))
        Task {
            for deposit in deposits {
                let waitsOnThePrompt = await routeShared(deposit.ticket, depositId: deposit.id,
                                                         original: TicketInbox.originalURL(deposit), now: now)
                if !waitsOnThePrompt { settleDeposit(deposit.id) }
            }
        }
    }

    /// Deposits read from the box and not yet settled: being routed, or on the prompt.
    private var depositsInHand: Set<String> = []

    private func settleDeposit(_ id: String?) {
        guard let id else { return }
        TicketInbox.remove(id)
        depositsInHand.remove(id)
    }

    /// Routes one deposit. True when it is left waiting on the prompt, and its deposit
    /// is then settled by `confirmTicket` or `dismissTicket` instead.
    ///
    /// Where an **Admission** does not redraw, the deposit's `original` file is copied
    /// into the app's own store (#568) and every such **Admission** names it: the Room
    /// shows that file in its place, so the ticket needs no prompt for it.
    private func routeShared(_ deposited: Ticket, depositId: String, original: URL?,
                             now: Date) async -> Bool {
        // Every Admission redrawn and read back before anything is decided (#441, story
        // 29): here in the app rather than in the extension, which deposits what it read
        // and nothing more (ADR-0020). One Vision pass each, off the main actor.
        let ticket = await Task.detached(priority: .userInitiated) { () -> Ticket in
            let checked = deposited.checkedForRedraw()
            guard checked.needsOriginal, let original else { return checked }
            return checked.keepingOriginal(TicketOriginals.shared.keep(original))
        }.value
        let parse: TicketParse = ticket.isEmpty ? .nothingUsable : .ticket(ticket)
        // Plans from disk, not from state: at a cold launch the drain can run before
        // `loadPlannedGigs` has put them there, and a ticket for a night planned by hand
        // would then be minted a second time.
        let known = host.state.timelineShows + (await timelines.load()).planned()
        let route = routeTicket(parse, knownNights: known, now: now)
        // setlist.fm is asked before anything is written or asked (#531): by artist and
        // day, never venue. A night already known needs no search here; a local one is
        // looked up once its Admissions are on it.
        let artist = ticket.artist?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfBlank
        let date = ticket.date.map { fmDate($0) }
        let alreadyKnown: Bool = { if case .match = route { return true } else { return false } }()
        var search: TicketSearch?
        if !alreadyKnown, let artist, let date {
            search = await ticketSearch(ticket, artist: artist, date: date)
        }
        let knownIds = Set(known.filter { !$0.isLocal }.map(\.id))
        let localIds = Set(known.filter { $0.isLocal }.map(\.id))
        switch ticketImport(route, match: search?.match, knownIds: knownIds, localIds: localIds) {
        case .attach(let gigId):
            await attachAdmissions(gigId: gigId, ticket.admissions)
            host.state.notice = "That night is already on your line."
        case .attachThenLookUp(let gigId):
            await attachAdmissions(gigId: gigId, ticket.admissions)
            host.state.notice = "That night is already on your line."
            await setlists.lookUpLocalGig(gigId, manual: false)
        case .mintFromSetlistFm(let hit):
            await planning.planFmGig(hit)
            await planning.claimTicketNight(hit.id, date: hit.eventDate)
            await attachAdmissions(gigId: hit.id, ticket.admissions)
        case .mintLocal:
            guard case .add(let complete) = route else { return false }
            if let gigId = await put(complete), let search {
                await setlists.stampLookup(gigId: gigId,
                                           TicketSetlistFmAnswer(chosen: nil, rejectedIds: [], lookedUpAt: search.at))
            }
        case .prompt(let candidates, let preselectedId):
            let offered = search.map {
                TicketSetlistFm(candidates: candidates, preselectedId: preselectedId,
                                artist: artist ?? "", date: date ?? "", lookedUpAt: $0.at)
            }
            switch route {
            case .confirm(let found, let possible):
                let hint = possible.flatMap { id in known.first { $0.id == id } }.map { night in
                    [night.artist?.name ?? "", night.venueLine(), night.eventDate ?? ""]
                        .filter { !$0.isEmpty }.joined(separator: " — ")
                }
                host.state.ticketDrafts.append(TicketDraft(ticket: found, possibleMatch: hint,
                                                           depositId: depositId, setlistFm: offered))
            case .add(let found):
                host.state.ticketDrafts.append(TicketDraft(ticket: found, depositId: depositId, setlistFm: offered))
            case .match, .unreadable:
                host.state.ticketDrafts.append(TicketDraft(ticket: Ticket(), depositId: depositId, setlistFm: offered))
            }
            return true
        }
        return false
    }


    /// Every **Admission** onto the night's attendance record, appended, and into state
    /// with it (#412, #441). No-op when there is none to keep — most confirmations and
    /// most matches.
    ///
    /// State as well as disk for the reason `mintPlannedGig` writes the claim into
    /// both: the day-of view (#414) reads the Admissions out of `attendanceByGig`, so a
    /// write that only landed on disk would show nothing until the next cold start.
    private func attachAdmissions(gigId: String, _ admissions: [Admission]) async {
        guard !admissions.isEmpty else { return }
        let settled = await timelines.attachAdmissions(setlistId: gigId,
                                                       admissions: admissions.map { StoredAdmission($0) })
        // The same ticket shared twice keeps its first file; the second copy is named by nothing.
        let named = Set(settled.admissions.compactMap(\.original))
        for name in Set(admissions.compactMap(\.original)).subtracting(named) {
            TicketOriginals.shared.forget(name)
        }
        host.state.attendanceByGig[gigId] = settled
        if host.state.selectedSetlist?.id == gigId { host.state.selectedAttendance = settled }
    }

    /// What was confirmed — by the parse being complete, or by a person — put on the
    /// **Line**. The Admissions ride along whether or not the text parse managed anything.
    /// The new night's id, nil where the ticket lacked an artist or a date.
    @discardableResult
    private func put(_ ticket: Ticket) async -> String? {
        guard let artist = ticket.artist, let night = ticket.date else { return nil }
        let gigId = await planning.mintPlannedGig(artist: artist, venue: ticket.venue ?? "", night: night)
        await planning.claimTicketNight(gigId, date: fmDate(night))
        await attachAdmissions(gigId: gigId, ticket.admissions)
        return gigId
    }

    /// The prompt answered: what the parse read, corrected and filled in by the person
    /// holding the ticket.
    ///
    /// The match is checked *again* here rather than trusted from `routeTicket`. What
    /// was routed was a partial parse that matched nothing; what is being confirmed is
    /// a full one, and it may well name a night that was already there — which is
    /// exactly the duplicate this feature is supposed to be safe from.
    ///
    /// Acts on the draft it was shown for, by id, never on whichever is first by the
    /// time it runs: its deposit is removed from the inbox once the write has landed.
    ///
    /// `chosenSetlistId` is the setlist.fm hit ticked above "None of these" (#531), nil
    /// for "None of these" or where no list was shown. An edited artist or date hid the
    /// list, so it then neither chooses nor rejects anything (`TicketSetlistFm.answer`).
    func confirmTicket(_ draftId: UUID, artist: String, venue: String, date: String,
                       chosenSetlistId: String? = nil) {
        guard let draft = host.state.ticketDrafts.first(where: { $0.id == draftId }) else { return }
        let pending = draft.ticket
        let who = artist.trimmingCharacters(in: .whitespacesAndNewlines)
        let room = venue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !who.isEmpty, let night = gigDay(date.trimmingCharacters(in: .whitespaces)) else {
            host.state.error = "A night needs who is playing and a date as dd-MM-yyyy."
            host.state.errorKind = nil
            return
        }
        host.state.ticketDrafts.removeAll { $0.id == draftId }
        // The Admissions are the parse's, whatever the person corrected (story 16).
        let confirmed = Ticket(admissions: pending.admissions, artist: who,
                               venue: room.nilIfBlank, date: night)
        let answer = draft.setlistFm?.answer(artist: artist, date: date, chosenId: chosenSetlistId)
            ?? .unasked
        Task {
            let known = host.state.knownNights
            let landing = knownNight(confirmed, among: known)
            if let hit = answer.chosen {
                if known.contains(where: { $0.id == hit.id }) {
                    await attachAdmissions(gigId: hit.id, confirmed.admissions)
                    host.state.notice = "That night is already on your line."
                } else if let local = landing, local.isLocal {
                    // The night is already here as a local Gig: it takes the hit's id.
                    await attachAdmissions(gigId: local.id, confirmed.admissions)
                    await gig.adoptSetlist(gigId: local.id, setlistId: hit.id, fresh: hit, notice: true)
                } else {
                    await planning.planFmGig(hit)
                    await planning.claimTicketNight(hit.id, date: hit.eventDate)
                    await attachAdmissions(gigId: hit.id, confirmed.admissions)
                }
            } else {
                let gigId: String?
                if let landing {
                    await attachAdmissions(gigId: landing.id, confirmed.admissions)
                    host.state.notice = "That night is already on your line."
                    gigId = landing.id
                } else {
                    gigId = await put(confirmed)
                }
                // "None of these": every hit offered is not this night, and the lookup counts.
                if let gigId { await setlists.stampLookup(gigId: gigId, answer) }
            }
            settleDeposit(draft.depositId)
        }
    }

    /// The prompt dismissed. The **Ticket** is dropped and nothing is written: a PDF
    /// shared by mistake must cost nothing, and it can always be shared again. Its
    /// deposit goes with it, so it does not come back at the next launch.
    func dismissTicket(_ draftId: UUID) {
        guard let draft = host.state.ticketDrafts.first(where: { $0.id == draftId }) else { return }
        host.state.ticketDrafts.removeAll { $0.id == draftId }
        // Nothing is written, so no file is kept for it (#568).
        draft.ticket.originals.forEach { TicketOriginals.shared.forget($0) }
        settleDeposit(draft.depositId)
    }

    /// How long a shared ticket's import waits on setlist.fm before reading it as no match.
    private static let ticketLookupTimeout: UInt64 = 5_000_000_000

    /// One import lookup: what it came to (nil where it failed) and when it went out.
    private struct TicketSearch {
        let match: SetlistFmMatch?
        let at: Int64
    }

    /// setlist.fm's `search/setlists` for a shared ticket's `artist` and `date`, held to
    /// `ticket` by the matcher. The person is waiting on the import, so it gets a few
    /// seconds and no more; a failure, a refusal or a timeout reads as no match.
    ///
    /// Raced rather than awaited: the client's retries sleep through a cancellation, so
    /// the import stops waiting on the request instead, and the timer then cancels it.
    private func ticketSearch(_ ticket: Ticket, artist: String, date: String) async -> TicketSearch {
        let at = epochMs(Date())
        let client = setlistFm
        let timeout = Self.ticketLookupTimeout
        let hits: [FmSetlist]? = await withCheckedContinuation { continuation in
            let once = ResumeOnce(continuation)
            let request = Task {
                let found = try? await client.searchSetlists(artistName: artist, date: date).setlist
                once.resume(found)
            }
            once.timer = Task {
                try? await Task.sleep(nanoseconds: timeout)
                guard !Task.isCancelled else { return }
                once.resume(nil)
                request.cancel()
            }
        }
        var asked = ticket
        asked.artist = artist
        let match = hits.map { matchSetlistFm(asked, hits: $0, lineArtists: host.state.lineArtists) }
        return TicketSearch(match: match, at: at)
    }
}

/// A continuation resumed by whichever of two tasks gets there first, the other ignored:
/// `TicketsController.ticketSearch`'s request against its timer. The winner cancels the timer.
private final class ResumeOnce<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<T, Never>?
    private var _timer: Task<Void, Never>?

    init(_ continuation: CheckedContinuation<T, Never>) { self.continuation = continuation }

    var timer: Task<Void, Never>? {
        get { lock.lock(); defer { lock.unlock() }; return _timer }
        set { lock.lock(); _timer = newValue; lock.unlock() }
    }

    func resume(_ value: T) {
        lock.lock()
        let waiting = continuation
        continuation = nil
        let timer = _timer
        lock.unlock()
        guard let waiting else { return }
        timer?.cancel()
        waiting.resume(returning: value)
    }
}
