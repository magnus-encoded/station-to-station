import Foundation

// What a local **Gig**'s setlist.fm lookup comes to (#531, sections 1–4), decided without
// the network, the clock or the store: the loop, the import and the chip are plumbing
// that ask these and act. Term for term with Android's `SetlistFmLookupFlow.kt`, and both
// assert `fixtures/setlistfm-outcome/`, `fixtures/setlistfm-lookup/plan.json` and
// `fixtures/setlistfm-question/` case for case.
//
// The schedule is `setlistFmLookupDue`'s and the matching `matchSetlistFm`'s; nothing
// here restates either.

/// One **Gig** the automatic checks are asked about. `date` is setlist.fm's `dd-MM-yyyy`;
/// `participationUntil` is its `gossipParticipationEnds` entry, nil where that is 0.
struct LookupGig {
    let id: String
    let date: String
    let local: Bool
    let lookup: StoredSetlistFmLookup?
    let participationUntil: Date?
}

/// What the loop does next: look up `dueNow`, one at a time and in that order, then sleep
/// until `nextWakeAt` (nil when nothing is due again).
struct LookupPlan: Equatable {
    let dueNow: [String]
    let nextWakeAt: Date?
}

/// Every `gigs`' next lookup by `setlistFmLookupDue`, split into the ones due now — by
/// night, earliest first, then by id, so both platforms send them in the same order — and
/// the earliest of the rest. A Gig that is not local, has a chip pending or is past its
/// 14 days is in neither.
///
/// `sharedQuotaSpentAt` is seconds since 1970, as `SetlistFmRateLimit` stores it here.
func setlistFmLookupPlan(_ gigs: [LookupGig], now: Date, calendar: Calendar = .current,
                         sharedKey: Bool, sharedQuotaSpentAt: TimeInterval?) -> LookupPlan {
    let due: [(gig: LookupGig, at: Date)] = gigs.compactMap { (gig: LookupGig) -> (gig: LookupGig, at: Date)? in
        let last = gig.lookup?.lastLookupAt.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) }
        guard let at = setlistFmLookupDue(
            gigDate: gig.date, now: now, calendar: calendar,
            local: gig.local, lastLookupAt: last, participationUntil: gig.participationUntil,
            possibleMatchPending: gig.lookup?.possibleMatchPending ?? false,
            sharedKey: sharedKey, sharedQuotaSpentAt: sharedQuotaSpentAt)
        else { return nil }
        return (gig: gig, at: at)
    }
    let ready = due.filter { $0.at <= now }.map { $0.gig }.sorted { a, b in
        let dayA = parseFmDate(a.date) ?? .distantFuture
        let dayB = parseFmDate(b.date) ?? .distantFuture
        return dayA != dayB ? dayA < dayB : a.id < b.id
    }
    return LookupPlan(dueNow: ready.map(\.id),
                      nextWakeAt: due.filter { $0.at > now }.map { $0.at }.min())
}

/// What one lookup came to, and the lookup state to store with it. `next` is always
/// stamped with the lookup, which went out whatever it found.
enum LookupOutcome {
    /// One hit is sure: adopt it, with the "Adopted" notice and no question.
    case adopt(FmSetlist, next: StoredSetlistFmLookup)
    /// Something, but nothing sure: `next` carries the chip. At most `askAtMost`, best first.
    case ask([SetlistFmCandidate], next: StoredSetlistFmLookup)
    /// Nothing survived.
    case nothing(next: StoredSetlistFmLookup)

    var next: StoredSetlistFmLookup {
        switch self {
        case .adopt(_, let next), .ask(_, let next), .nothing(let next): return next
        }
    }
}

/// One lookup's `hits` held to `ticket` — for a **Gig**, `Ticket(artist:, venue:,
/// date: gigDay(eventDate))` — by `matchSetlistFm`, after dropping every hit the person
/// already said was not this night: a hit rejected once is never offered again, and one
/// left standing alone may link without asking. `lookup` nil is a night never looked up.
func setlistFmLookupOutcome(_ ticket: Ticket,
                            hits: [FmSetlist],
                            lineArtists: [FmArtist],
                            lookup: StoredSetlistFmLookup?,
                            nowMillis: Int64,
                            calendar: Calendar = .current) -> LookupOutcome {
    let before = lookup ?? StoredSetlistFmLookup()
    let offered = hits.filter { !before.rejectedIds.contains($0.id) }
    let stamped = before.lookedUp(at: nowMillis)
    switch matchSetlistFm(ticket, hits: offered, lineArtists: lineArtists, calendar: calendar) {
    case .linked(let candidate):
        return .adopt(candidate.setlist, next: stamped)
    case .ask(let candidates):
        return .ask(candidates, next: stamped.asking(candidates.map { StoredSetlistFmHit($0) }))
    case .noMatch:
        return .nothing(next: stamped)
    }
}

/// What a shared **Ticket** becomes once its routing and its setlist.fm lookup are both in.
enum TicketImport {
    /// Its **Admissions** go onto `gigId`: the night was already known, or the sure hit
    /// already on the **Line**.
    case attach(gigId: String)
    /// Onto `gigId`, a local **Gig**, which is then looked up at once: a link adopts, a
    /// question is the chip.
    case attachThenLookUp(gigId: String)
    /// A new **Gig** from the sure hit, planned as setlist.fm has it.
    case mintFromSetlistFm(FmSetlist)
    /// A new local **Gig**, stamped as looked up, so the schedule does not ask again at once.
    case mintLocal
    /// The confirm prompt, with the candidates above "None of these"; `preselectedId` is ticked.
    case prompt([SetlistFmCandidate], preselectedId: String?)
}

/// `route` and `match` together, per #531's table. `match` is nil where no lookup was
/// made (no artist or date on the ticket) or where it failed; either reads as no match.
/// `knownIds` are the ids of the nights already on the **Line**, and `localIds` the ones
/// of those that are local **Gigs** — `TicketRoute.match` carries an id only, where
/// Android's `AlreadyKnown` carries the night and asks it `isLocal()`.
///
/// | Route              | Match         | Result                                          |
/// |--------------------|---------------|-------------------------------------------------|
/// | add                | linked        | attach if the hit is known, else mintFromSetlistFm |
/// | add                | ask           | prompt                                          |
/// | add                | noMatch/nil   | mintLocal                                       |
/// | confirm/unreadable | linked        | prompt, the hit preselected                     |
/// | confirm/unreadable | ask/noMatch   | prompt                                          |
/// | match              | local Gig     | attachThenLookUp                                |
/// | match              | setlist.fm Gig| attach                                          |
func ticketImport(_ route: TicketRoute, match: SetlistFmMatch?,
                  knownIds: Set<String>, localIds: Set<String>) -> TicketImport {
    switch route {
    case .match(let gigId):
        return localIds.contains(gigId) ? .attachThenLookUp(gigId: gigId) : .attach(gigId: gigId)
    case .add:
        switch match ?? .noMatch {
        case .linked(let candidate):
            let id = candidate.setlist.id
            return knownIds.contains(id) ? .attach(gigId: id) : .mintFromSetlistFm(candidate.setlist)
        case .ask(let candidates):
            return .prompt(candidates, preselectedId: nil)
        case .noMatch:
            return .mintLocal
        }
    case .confirm, .unreadable:
        switch match ?? .noMatch {
        case .linked(let candidate):
            return .prompt([candidate], preselectedId: candidate.setlist.id)
        case .ask(let candidates):
            return .prompt(candidates, preselectedId: nil)
        case .noMatch:
            return .prompt([], preselectedId: nil)
        }
    }
}

/// The question a candidate row asks when the room is in doubt: `hit`'s venue level
/// `weak` or `noMatch`, and a venue named on both sides. Nil otherwise, and the row shows
/// `artist — venueLine — date` instead. `fromTicket` is whether the **Gig** has
/// **Admissions** (always, at import): the venue `yourVenue` came off a ticket, or was
/// typed. Word for word with Android.
func setlistFmQuestion(yourVenue: String?, fromTicket: Bool, hit: StoredSetlistFmHit) -> String? {
    let yours = (yourVenue ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    let theirs = hit.venue.trimmingCharacters(in: .whitespacesAndNewlines)
    let inDoubt = hit.venueLevel == MatchLevel.weak.storedName || hit.venueLevel == MatchLevel.noMatch.storedName
    guard inDoubt, !yours.isEmpty, !theirs.isEmpty else { return nil }
    let says = fromTicket ? "Your ticket says \(yours)." : "You have it at \(yours)."
    return "\(says) setlist.fm lists it at \(theirs). Same gig?"
}

/// `setlistFmQuestion` for a candidate the prompt holds rather than one the chip stored.
func setlistFmQuestion(yourVenue: String?, fromTicket: Bool, candidate: SetlistFmCandidate) -> String? {
    setlistFmQuestion(yourVenue: yourVenue, fromTicket: fromTicket, hit: StoredSetlistFmHit(candidate))
}
