import Foundation

// The Swift twin of Android's `data/Bill.kt`. `StoredBill`/`StoredAct` — the poster
// you paste in by hand — were decommissioned in #391: **Departures**, the published
// festival timetable, replaced the hand-typed poster as the way a planned night
// enters the timeline. What is left is the local-gig and future-lane logic that has
// nothing to do with a Bill and stayed live.

/// A date as `dd-MM-yyyy`, the one shape this app and setlist.fm both speak.
///
/// The calendar's own zone, not GMT: a planned night's date is the day the person
/// standing at the venue is living through. `parseFmDate` in `SetlistFmModels.swift`
/// reads a *published* setlist's date and is right to be zone-fixed; this is the local
/// half of the same format, and reads back through `gigDay`, which parses in the same
/// zone. Pairing it with `parseFmDate` instead would land a day out wherever the two
/// zones disagree at midnight — which is most of the world, most of the time.
func fmDate(_ date: Date, calendar: Calendar = .current) -> String {
    let f = DateFormatter()
    f.locale = Locale(identifier: "en_US_POSIX")
    f.dateFormat = "dd-MM-yyyy"
    f.timeZone = calendar.timeZone
    return f.string(from: date)
}

/// The setlist face of a **Gig** this app minted rather than setlist.fm.
func localGigSetlist(gigId: String, artist: String, date: String,
                     venue: String, city: String) -> FmSetlist {
    FmSetlist(
        id: gigId,
        eventDate: date,
        artist: FmArtist(name: artist),
        // Nil, not "", for an unknown room (#128). Empty strings compare equal, so a
        // blank venue left as "" would make `sameFestival` cluster two nights that
        // merely both lack a venue — an unknown is not a place two gigs have in common.
        venue: FmVenue(name: venue.isBlank ? nil : venue,
                       city: FmCity(name: city.isBlank ? nil : city)),
        // No songs, ever. What was played lives in the **Log**, which is a record of my
        // own observation and is deliberately not dressed up as a setlist.fm setlist —
        // that conflation is exactly how a partial capture starts looking complete.
        sets: nil,
        url: nil
    )
}

private extension String {
    /// Kotlin's `isBlank()`: empty, or nothing but whitespace.
    var isBlank: Bool { trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
}

extension FmSetlist {
    /// A gig this app minted rather than setlist.fm: the one thing that has no page.
    var isLocal: Bool { url == nil }
}

/// One row of the future lane — everything above today, in one list because it is one
/// line.
enum FutureRow: Identifiable {
    /// A **Gig** I hold a ticket for — or the **Festival** a few of them at one venue
    /// on one night turn out to be. Two nights above today at the same place is the
    /// same shape as two nights below it, and the lane drew them as loose nodes only
    /// because it did its own grouping, which was none (#134).
    case ticket(TimelineNode)

    var id: String {
        switch self {
        case .ticket(let node):
            if case .concert(let s) = node { return "planned-\(s.id)" }
            return node.severalKey ?? ""
        }
    }

    var date: Date? {
        switch self {
        // A cluster sorts by the night it opens, not the night it ends.
        case .ticket(let node): return node.shows.compactMap { $0.localDate() }.min()
        }
    }
}

/// Everything above today, furthest future first — the same descending order the
/// attended rows below already use, which is the whole point: one line, one rule.
///
/// A row with no date sorts to the *bottom* of the future, not the top. Unknown is not
/// "the furthest away".
///
/// `tickets` is filtered through `plannedLane` here rather than by the caller, so the
/// lane and the identity resolver above it cannot end up reading different lists.
func futureRows(tickets: [FmSetlist],
                attendance: [String: StoredAttendance],
                festivals: Festivals = Festivals()) -> [FutureRow] {
    let rows = groupIntoFestivals(plannedLane(tickets, attendance), festivals).map(FutureRow.ticket)
    return rows.sorted { ($0.date ?? .distantPast) > ($1.date ?? .distantPast) }
}

/// The nights the future lane is made of: still a plan, newest first.
///
/// Date-ordered because the lane is drawn newest first and `gigPlanned`'s own order is
/// whatever they happened to be added in. Its own function because the identity resolver
/// has to see the exact same list the lane does.
func plannedLane(_ gigs: [FmSetlist],
                 _ attendance: [String: StoredAttendance]) -> [FmSetlist] {
    gigs.filter { isPlanned(attendance[$0.id]?.provenance) }
        .sorted { ($0.localDate() ?? .distantPast) > ($1.localDate() ?? .distantPast) }
}

/// The nights the **Spine** is made of: setlist.fm's **Attended** list, plus my own
/// evidenced nights it has never heard of. Newest first, for `plannedLane`'s reason.
///
/// The counterpart to `plannedLane`, and the half that was missing. The Spine used to be
/// `shows[me]` alone, so a night's only route onto the timeline was setlist.fm knowing
/// about it — and `plannedLane` drops a night the moment it stops being a plan. A night
/// I checked into that setlist.fm has never heard of therefore left the future lane and
/// arrived nowhere: on neither list, holding a **Log** and seven photographs that nothing
/// would draw.
///
/// "Evidenced" is `isPlanned` read the other way round — `attended` and `checked_in` are
/// evidence I was there, and a check-in is the strongest claim this app can hold. A night
/// carrying it must outrank the absence of a vendor's row about it.
///
/// Deduplicated on the setlist.fm id, which is what the two lists share: a planned night
/// that later turns up in the **Attended** import is one night, and the imported copy
/// wins because it is the published record of the same evening.
func spineNights(attended: [FmSetlist], planned: [FmSetlist],
                 attendance: [String: StoredAttendance]) -> [FmSetlist] {
    let known = Set(attended.map(\.id))
    let mine = planned.filter {
        !known.contains($0.id) && !isPlanned(attendance[$0.id]?.provenance)
    }
    guard !mine.isEmpty else { return attended }
    return (attended + mine)
        .sorted { ($0.localDate() ?? .distantPast) > ($1.localDate() ?? .distantPast) }
}

/// Whether a **Contact**'s **Lane** needs a setlist.fm fetch when the strip opens (#405).
///
/// `held` is what I already hold for them: nil when nothing is, and an empty list when
/// setlist.fm answered and they have no **Nights** there. The two are different facts.
/// Reading them as one was the re-fetch loop: an empty answer was never held, so the
/// Lane looked missing on every zoom-out and was asked for again, forever, against the
/// one bundled key every tester shares. Once asked, an empty Lane is an answer.
///
/// - no username: false. There is no address to fetch from, and whatever is held is all
///   there is.
/// - nothing held: true.
/// - whole pages: true. A Lane's setlist.fm Nights counting a whole number of pages
///   may be one cut short — a Lane held from before the whole history was fetched
///   stopped at my own oldest Gig, always on a page boundary. Anything else is their
///   whole history. Hand-logged Nights from the Reconcile are not setlist.fm's and
///   are not counted.
/// - otherwise: false.
///
/// ponytail: a whole history that happens to be whole pages long is fetched again on
/// every zoom-out. Store their reported total if that ever matters. Term for term with
/// Android's `laneNeedsFetch`.
func laneNeedsFetch(_ contact: Friend, held: [FmSetlist]?) -> Bool {
    if contact.setlistfm.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return false }
    guard let held else { return true }
    let fromSetlistFm = held.filter { !$0.isLocal }.count
    return fromSetlistFm > 0 && fromSetlistFm % SetlistFmClient.pageSize == 0
}

/// What I hold after `fetched` Lanes land on top of `held`: each fetched Lane replaces
/// what was there, and an empty one is held too, so `laneNeedsFetch` reads it as an
/// answer on the next pass. The one exception is an empty answer over a Lane that had
/// Nights — that keeps the last good copy rather than trusting a blank page over it.
///
/// Shared by the in-memory Lanes and `TimelineStore.save`, so what is drawn and what is
/// stored are one rule. A failed fetch is not an empty one: callers leave it out of
/// `fetched` altogether. Term for term with Android's `holdLanes`.
///
/// A hand-logged Night of theirs reached me on the **Reconcile** (#405), and setlist.fm has
/// never heard of it — so its answer, however complete, is not an answer about that Night.
/// It stays.
func holdLanes(_ held: [String: [FmSetlist]], _ fetched: [String: [FmSetlist]]) -> [String: [FmSetlist]] {
    var out = held
    for (user, shows) in fetched where !shows.isEmpty || (held[user] ?? []).isEmpty {
        let ids = Set(shows.map(\.id))
        let onlyReconciled = (held[user] ?? []).filter { $0.isLocal && !ids.contains($0.id) }
        out[user] = onlyReconciled.isEmpty ? shows : newestFirst(shows + onlyReconciled)
    }
    return out
}

/// Newest first, as every Lane is; a Night with no date sinks to the bottom.
private func newestFirst(_ nights: [FmSetlist]) -> [FmSetlist] {
    nights.sorted { ($0.localDate() ?? .distantPast) > ($1.localDate() ?? .distantPast) }
}

extension TimelineCache {
    /// My own **Nights**, as a cache holds them: the **Spine** `spineNights` draws, read
    /// straight from the store. `me` is my setlist.fm username, blank when I have none —
    /// then the Spine is my evidenced nights alone, which is the whole of it for someone
    /// who logs by hand. Android's `mySpine`.
    func mySpine(_ me: String) -> [FmSetlist] {
        let attended = me.nilIfBlank.flatMap { shows[$0] } ?? []
        return spineNights(attended: attended, planned: planned(), attendance: attendance())
    }
}

/// A **Contact**'s **Lane** with the Nights they took back on a **Reconcile** taken out
/// (`ContactReconcilePlan.withdrawn`). Hand-logged Nights only, whatever `withdrawn` names:
/// a setlist.fm Night is only ever revised by setlist.fm. Android's `withdrawNights`.
func withdrawNights(_ held: [FmSetlist]?, _ withdrawn: [String]) -> [FmSetlist] {
    let gone = Set(withdrawn)
    return (held ?? []).filter { !($0.isLocal && gone.contains($0.id)) }
}

/// A **Contact**'s **Lane** once the **Nights** they offered on a **Reconcile** land on
/// what I `held` (#405). `received` is the plan's `nights` — already only what I did not
/// hold — so this adds and never removes: the same Night twice is one Night, and a Lane
/// setlist.fm filled in is not emptied by a Contact whose own copy of it is shorter.
/// Newest first, as every Lane is. Android's `landNights`.
func landNights(_ held: [FmSetlist]?, _ received: [FmSetlist]) -> [FmSetlist] {
    let had = held ?? []
    var ids = Set(had.map(\.id))
    let fresh = received.filter { $0.id.nilIfBlank != nil && ids.insert($0.id).inserted }
    if fresh.isEmpty { return had }
    return newestFirst(had + fresh)
}
