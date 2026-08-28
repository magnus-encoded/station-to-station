import Foundation

// A **Bill** and its **Acts** — the Swift twin of Android's `data/Bill.kt`, ported
// term for term rather than re-derived. The two shapes share one cache file, so a
// rule that holds on one side and not the other is a corruption with extra steps.
//
// This file is the **Logic** layer only (ADR-0017): the records, the range, the
// clock and the paste. The screen that stands on it is #172's next split.

/// A **Festival** whose **Gigs** do not exist yet — the case setlist.fm cannot hold,
/// because which night each act plays is not known until the poster goes up.
///
/// It holds the name, the city, the date range and the list of names, and nothing
/// more. Inventing a day per act so the existing machinery works is the fabrication
/// the record must not commit.
struct StoredBill: Codable, Equatable {
    var id: String = ""
    /// "Ringnes Festival 2026". The festival's name, and nothing else's (#128).
    var name: String = ""
    var city: String = ""
    /// dd-MM-yyyy, the shape setlist.fm sends — the range, which *is* known.
    var from: String = ""
    var to: String = ""
    /// In poster order. Order is the only thing a lineup reliably carries.
    var acts: [StoredAct] = []

    init(id: String = "", name: String = "", city: String = "",
         from: String = "", to: String = "", acts: [StoredAct] = []) {
        self.id = id
        self.name = name
        self.city = city
        self.from = from
        self.to = to
        self.acts = acts
    }

    // By hand for `StoredLog`'s reason: kotlinx falls back to the default on a
    // missing key, and one absent field must not take the whole cache down with it.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = (try? c.decodeIfPresent(String.self, forKey: .id)) ?? nil ?? ""
        name = (try? c.decodeIfPresent(String.self, forKey: .name)) ?? nil ?? ""
        city = (try? c.decodeIfPresent(String.self, forKey: .city)) ?? nil ?? ""
        from = (try? c.decodeIfPresent(String.self, forKey: .from)) ?? nil ?? ""
        to = (try? c.decodeIfPresent(String.self, forKey: .to)) ?? nil ?? ""
        acts = (try? c.decodeIfPresent([StoredAct].self, forKey: .acts)) ?? nil ?? []
    }
}

/// One name on a **Bill**.
///
/// `maybe` is the hedge the poster itself makes. It is a property of the **Bill**,
/// never of a **Gig**: the moment an act is dated it played, so there is nothing left
/// to be unsure about and no "unconfirmed gig" state can ever be reached.
///
/// `gigId` is nil until someone standing there says which night this played. Then it
/// is a local **Gig** and this act is done being an act.
struct StoredAct: Codable, Equatable {
    var name: String = ""
    var maybe: Bool = false
    /// What #93 asks for — a plausible song set to tick off rather than type, fetched
    /// while there is still signal. Empty is the honest and common answer for a small
    /// local act setlist.fm has never heard of.
    var candidates: [String] = []
    var gigId: String?
    /// *Which* artist the pool came from, shown wherever the pool is. Five bands are
    /// called Silent Majority; naming the source does not prevent a wrong match, it
    /// makes a wrong match visible in the second it happens.
    var matchedArtist: String = ""
    /// The MusicBrainz id the pool was fetched against. Empty until one is resolved.
    var mbid: String = ""
    /// Whether setlist.fm has *answered* about this act — not whether we asked. "No
    /// pool because they have no history" is a correct, final answer; "no pool because
    /// the radio couldn't reach anyone" is a question still open, and gets retried.
    var tried: Bool = false
    /// Never on the poster — typed in the field, by hand, dated on arrival. An act from
    /// the **Bill** tapped by accident returns to being an undated act, because the
    /// poster still says it is playing; a **Surprise** entered wrongly has nothing to
    /// return to and must be able to go entirely.
    var surprise: Bool = false

    init(name: String = "", maybe: Bool = false, candidates: [String] = [],
         gigId: String? = nil, matchedArtist: String = "", mbid: String = "",
         tried: Bool = false, surprise: Bool = false) {
        self.name = name
        self.maybe = maybe
        self.candidates = candidates
        self.gigId = gigId
        self.matchedArtist = matchedArtist
        self.mbid = mbid
        self.tried = tried
        self.surprise = surprise
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = (try? c.decodeIfPresent(String.self, forKey: .name)) ?? nil ?? ""
        maybe = (try? c.decodeIfPresent(Bool.self, forKey: .maybe)) ?? nil ?? false
        candidates = (try? c.decodeIfPresent([String].self, forKey: .candidates)) ?? nil ?? []
        gigId = (try? c.decodeIfPresent(String.self, forKey: .gigId)) ?? nil
        matchedArtist = (try? c.decodeIfPresent(String.self, forKey: .matchedArtist)) ?? nil ?? ""
        mbid = (try? c.decodeIfPresent(String.self, forKey: .mbid)) ?? nil ?? ""
        tried = (try? c.decodeIfPresent(Bool.self, forKey: .tried)) ?? nil ?? false
        surprise = (try? c.decodeIfPresent(Bool.self, forKey: .surprise)) ?? nil ?? false
    }
}

/// A date as `dd-MM-yyyy`, the one shape this app and setlist.fm both speak.
///
/// The calendar's own zone, not GMT: a **Bill**'s nights are the days the person
/// standing at the festival is living through. `parseFmDate` in `SetlistFmModels.swift`
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

/// The night an **Act** tapped at `now` belongs to. Before `nightEndsHour` it is still
/// last night — you are walking out of the tent at half one and the act you are logging
/// played yesterday's date, which is the one moment this matters.
///
/// ponytail: today or last night, nothing else. Logging Thursday's act on Saturday is a
/// date picker, and at a three-day festival you log the act as you leave it.
func billNight(now: Date, calendar: Calendar = .current) -> String {
    let hour = calendar.component(.hour, from: now)
    let day = hour < nightEndsHour
        ? calendar.date(byAdding: .day, value: -1, to: now) ?? now
        : now
    return fmDate(day, calendar: calendar)
}

/// Every night this **Bill** has — the only days a **Gig** it mints may be dated.
///
/// The range is the one temporal fact a poster does carry, and it is what makes the
/// question askable afterwards: three or four days is a list to tap, not a date picker.
/// A **Bill** with no dates typed in has no nights, and then the clock is all there is.
///
/// A free function rather than a method, because Android's `StoredBill.nights()` is an
/// extension on a record the wire format owns; the record stays a record on both sides.
func billNights(_ bill: StoredBill, calendar: Calendar = .current) -> [String] {
    guard let first = gigDay(bill.from, calendar: calendar)
        ?? gigDay(bill.to, calendar: calendar) else { return [] }
    let second = gigDay(bill.to, calendar: calendar) ?? first
    let (a, b) = (min(first, second), max(first, second))
    var out: [String] = []
    var day = a
    while day <= b {
        out.append(fmDate(day, calendar: calendar))
        guard let next = calendar.date(byAdding: .day, value: 1, to: day) else { break }
        day = next
    }
    return out
}

/// Where the clock stands relative to a **Bill**: not started, on, or over.
enum BillWhen { case before, during, after }

/// Which of the three a **Bill** is in, judged on `billNight` rather than the calendar
/// day — so half one in the morning on the last night is still **during**, the same
/// boundary the check-in window draws.
func billWhen(_ bill: StoredBill, now: Date, calendar: Calendar = .current) -> BillWhen {
    let nights = billNights(bill, calendar: calendar)
    // Nothing to disagree with. An undated Bill behaves exactly as it always has.
    guard let first = nights.first.flatMap({ gigDay($0, calendar: calendar) }),
          let last = nights.last.flatMap({ gigDay($0, calendar: calendar) }),
          let night = gigDay(billNight(now: now, calendar: calendar), calendar: calendar)
    else { return .during }
    if night < first { return .before }
    if night > last { return .after }
    return .during
}

/// **The invariant: a Gig minted from a Bill is dated inside that Bill's range.**
///
/// Nil is "there is no honest answer here" and the only correct one in two cases: the
/// festival has not opened, and it has closed with nobody having said which night this
/// was. Where the clock disagrees with the range the range wins — the clock does not get
/// to date a night at a festival that was already over, which is `StoredBill`'s own
/// "inventing a day per act" fabrication arriving by the back door.
///
/// `chosen` is a night a person picked off the range. It is checked against the range
/// too rather than trusted: the invariant holds for every path into it or it is not an
/// invariant.
func gigNight(_ bill: StoredBill, chosen: String?, now: Date,
              calendar: Calendar = .current) -> String? {
    let nights = billNights(bill, calendar: calendar)
    let night = chosen ?? billNight(now: now, calendar: calendar)
    if nights.isEmpty { return night }
    return nights.contains(night) ? night : nil
}

/// A pasted lineup, one **Act** per line.
///
/// A line beginning `?` is a **Maybe** — the poster's own hedge, kept as the poster made
/// it. Blank lines and duplicates are dropped; leading bullets are tolerated because a
/// lineup is usually copied out of a PDF that had them.
func parseLineup(_ text: String) -> [StoredAct] {
    var seen = Set<String>()
    var out: [StoredAct] = []
    for raw in text.split(separator: "\n", omittingEmptySubsequences: false) {
        var line = raw.trimmingCharacters(in: .whitespaces)
        for bullet in ["-", "*", "\u{2022}"] where line.hasPrefix(bullet) {
            line = String(line.dropFirst(bullet.count))
        }
        line = line.trimmingCharacters(in: .whitespaces)
        guard !line.isEmpty else { continue }
        let maybe = line.hasPrefix("?")
        let name = (maybe ? String(line.dropFirst()) : line)
            .trimmingCharacters(in: .whitespaces)
        guard !name.isEmpty, seen.insert(name.lowercased()).inserted else { continue }
        out.append(StoredAct(name: name, maybe: maybe))
    }
    return out
}

/// "Silent Majority (US hardcore)" — the name, plus whatever tells it from its namesakes.
func artistLabel(name: String, disambiguation: String?) -> String {
    [name.isBlank ? nil : name,
     (disambiguation?.isBlank == false) ? "(\(disambiguation!))" : nil]
        .compactMap { $0 }.joined(separator: " ")
}

/// The songs an artist has been playing lately, most-played first — the pool an
/// **Act**'s setlist is ticked off from rather than typed.
///
/// Frequency across the most recent setlists, not the single latest one: a set has a
/// stable core and a rotating edge, and the core is what is worth offering first.
/// Covers and tape tracks come through `performed()`'s filter already.
func candidateSongs(_ recent: [FmSetlist], take: Int = 4, limit: Int = 40) -> [String] {
    var order: [String] = []
    var counts: [String: Int] = [:]
    for set in recent.prefix(take) {
        for song in set.performed() {
            if counts[song.name] == nil { order.append(song.name) }
            counts[song.name, default: 0] += 1
        }
    }
    // Stable: `order` is first-seen order and the sort below is a stable enumerated
    // tiebreak on it, so equal counts stay in the order the most recent setlist
    // played them. Swift's `sorted` is not documented as stable; the index is.
    return order.enumerated()
        .sorted { a, b in
            let (ca, cb) = (counts[a.element] ?? 0, counts[b.element] ?? 0)
            return ca == cb ? a.offset < b.offset : ca > cb
        }
        .map(\.element)
        .prefix(limit)
        .map { $0 }
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
/// line. A **Bill** and a ticket are different kinds of thing and sort by the same rule.
enum FutureRow: Identifiable {
    case onBill(StoredBill)
    /// A **Gig** I hold a ticket for — or the **Festival** a few of them at one venue
    /// on one night turn out to be. Two nights above today at the same place is the
    /// same shape as two nights below it, and the lane drew them as loose nodes only
    /// because it did its own grouping, which was none (#134).
    case ticket(TimelineNode)

    var id: String {
        switch self {
        case .onBill(let bill): return "bill-\(bill.id)"
        case .ticket(let node):
            if case .concert(let s) = node { return "planned-\(s.id)" }
            return node.severalKey ?? ""
        }
    }

    var date: Date? {
        switch self {
        // A **Bill** sorts by when it *starts*. Its last day is the wrong handle: a
        // three-day festival beginning tonight would sort above a gig two days out,
        // which is this same bug one step smaller.
        case .onBill(let bill): return parseFmDate(bill.from) ?? parseFmDate(bill.to)
        // Same rule for a cluster: the night it opens, not the night it ends.
        case .ticket(let node): return node.shows.compactMap { $0.localDate() }.min()
        }
    }
}

/// Everything above today, furthest future first — the same descending order the
/// attended rows below already use, which is the whole point: one line, one rule.
///
/// A **Bill** is not folded into the grouping. It is its own kind of node with its own
/// lineup, which is why it arrives here as a separate argument.
///
/// A row with no date sorts to the *bottom* of the future, not the top. Unknown is not
/// "the furthest away". It still renders: a **Bill** whose dates were never typed in is
/// a real thing to be holding.
///
/// `tickets` arrives already filtered and is not re-filtered here. Android's
/// `plannedLane` drops a night that has stopped being a plan, and its `spineNights`
/// catches the night that drops — the two are a matched pair, and iOS has neither yet.
/// Landing one half here would strand a checked-into night on no list at all.
func futureRows(bills: [StoredBill], tickets: [FmSetlist],
                festivals: Festivals = Festivals()) -> [FutureRow] {
    let rows = bills.map(FutureRow.onBill)
        + groupIntoFestivals(tickets, festivals).map(FutureRow.ticket)
    return rows.sorted { ($0.date ?? .distantPast) > ($1.date ?? .distantPast) }
}
