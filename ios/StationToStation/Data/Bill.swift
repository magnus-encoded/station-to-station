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
