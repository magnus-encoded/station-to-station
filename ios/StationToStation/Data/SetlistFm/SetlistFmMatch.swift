import Foundation

/// What a setlist.fm lookup for a **Ticket** has to go on (#531): at least an artist and a
/// night, and the venue when the ticket printed one. `date` is `dd-MM-yyyy`, the one shape
/// both platforms store a night in. Plain fields rather than the **Ticket** itself, so the
/// matcher does not move every time the ticket's own shape does. The twin of Android's
/// `TicketLookup`.
struct TicketLookup: Equatable {
    var artist: String
    var venue: String?
    var date: String
}

/// How well one field of a hit agrees with the **Ticket**. Declared best first.
enum MatchLevel: String, Comparable {
    case strong, weak, none

    private var rank: Int {
        switch self {
        case .strong: return 0
        case .weak: return 1
        case .none: return 2
        }
    }

    static func < (a: MatchLevel, b: MatchLevel) -> Bool { a.rank < b.rank }
}

/// One hit's level on each field. `venue` is nil when the ticket has no venue to compare,
/// which is neither a match nor a conflict. A hit whose `artist` or `date` is `.none` is
/// dropped: it is a different night, not a worse candidate.
struct FieldLevels: Equatable {
    var artist: MatchLevel
    var date: MatchLevel
    var venue: MatchLevel?

    var survives: Bool { artist != .none && date != .none }

    /// Linked without a question: Strong on artist and date, and on venue if there is one.
    var meetsTheBar: Bool {
        artist == .strong && date == .strong && (venue == nil || venue == .strong)
    }
}

/// A surviving hit and the levels it earned.
struct SetlistFmCandidate {
    var setlist: FmSetlist
    var levels: FieldLevels
}

/// What a lookup's hits come to.
enum SetlistFmMatch {
    /// The one hit that meets the bar, with nothing else as good. No question asked.
    case linked(SetlistFmCandidate)
    /// At most `maxAsked` hits, best first, for the person to choose among or refuse.
    case ask([SetlistFmCandidate])
    /// Every hit was dropped, or there were none.
    case noMatch
}

/// The question offers this many hits at most, plus "None of these".
let maxAsked = 3

/// Whether a **Ticket** links to one of setlist.fm's `search/setlists` hits, asks about
/// them, or matches none (#531, section 2). Levels, not a hit count: one hit is not proof
/// and two are not doubt, so what decides is how well the best hit agrees, field by field.
///
/// **Linked** is the magic path and the one to keep wide: the best hit is Strong on artist
/// and date, Strong on venue or the ticket has none, and no other hit has the same levels.
/// **Ask** is everything that survives short of that, including two hits tied at the top.
/// A venue conflict is never read as the ticket being right; setlist.fm may know about the
/// sell-out upgrade the ticket predates, which is why a Weak venue asks rather than drops.
///
/// The ranking is venue first, as the spec has it, then artist, then date, then the order
/// setlist.fm sent. Venue's own order already puts the same city before a different one.
///
/// `lineArtists` are the artists on the person's **Line**: a hit carrying the MusicBrainz
/// id the **Line** holds for the ticket's artist is Strong however setlist.fm spells it.
/// Term for term with Android's `matchSetlistFm`, asserted by `fixtures/setlistfm-match/`.
func matchSetlistFm(_ ticket: TicketLookup,
                    hits: [FmSetlist],
                    lineArtists: [FmArtist]) -> SetlistFmMatch {
    let surviving: [(order: Int, candidate: SetlistFmCandidate)] = hits.enumerated().compactMap { i, hit in
        let levels = fieldLevels(ticket, hit: hit, lineArtists: lineArtists)
        return levels.survives ? (i, SetlistFmCandidate(setlist: hit, levels: levels)) : nil
    }
    // `sorted` is not promised stable, so setlist.fm's order is the last key outright.
    let ranked = surviving.sorted { a, b in
        let x = a.candidate.levels, y = b.candidate.levels
        let keyX: (MatchLevel, MatchLevel, MatchLevel) = (x.venue ?? .strong, x.artist, x.date)
        let keyY: (MatchLevel, MatchLevel, MatchLevel) = (y.venue ?? .strong, y.artist, y.date)
        if keyX != keyY { return keyX < keyY }
        return a.order < b.order
    }.map { $0.candidate }
    guard let best = ranked.first else { return .noMatch }
    let tied = ranked.count > 1 && ranked[1].levels == best.levels
    if best.levels.meetsTheBar && !tied { return .linked(best) }
    return .ask(Array(ranked.prefix(maxAsked)))
}

/// One hit's level on each field. Internal so the shared fixtures can assert them.
func fieldLevels(_ ticket: TicketLookup, hit: FmSetlist, lineArtists: [FmArtist]) -> FieldLevels {
    FieldLevels(
        artist: artistLevel(ticket.artist, hit: hit.artist, lineArtists: lineArtists),
        date: dateLevel(ticket.date, hit.eventDate),
        venue: ticket.venue?.nilIfBlank.map { venueLevel($0, hit: hit.venue) }
    )
}

/// Strong: the same name after folding, or the MusicBrainz id the **Line** holds for the
/// ticket's artist. Weak: one name contains the other, word for word — `Wilco (US)` and
/// `Wilco`, never `Wilco` inside `Wilcox`.
private func artistLevel(_ ticketArtist: String, hit: FmArtist?, lineArtists: [FmArtist]) -> MatchLevel {
    let mine = words(ticketArtist)
    let theirs = words(hit?.name ?? "")
    if mine.isEmpty { return .none }
    if !theirs.isEmpty && sameName(mine, theirs) { return .strong }
    if let mbid = hit?.mbid, !mbid.isEmpty,
       lineArtists.contains(where: { $0.mbid == mbid && sameName(words($0.name), mine) }) {
        return .strong
    }
    return contains(mine, theirs) || contains(theirs, mine) ? .weak : .none
}

/// Strong: the same day. Weak: a day either side, such as a ticket dated by doors after
/// midnight. Both sides are `dd-MM-yyyy` text parsed by `parseFmDate`, fixed to GMT, so no
/// time zone and no DST jump can move either one (ADR-0002).
private func dateLevel(_ ticketDate: String, _ eventDate: String?) -> MatchLevel {
    guard let mine = parseFmDate(ticketDate), let theirs = eventDate.flatMap(parseFmDate)
    else { return .none }
    switch abs((theirs.timeIntervalSince(mine) / 86_400).rounded()) {
    case 0: return .strong
    case 1: return .weak
    default: return .none
    }
}

/// Strong: the same room after folding, or one name containing the other (`Rockefeller`,
/// `Rockefeller Music Hall`). Weak: another room in the ticket's city. None: anything else,
/// which is a different city or a city the ticket never said.
///
/// A ticket names its city only as a suffix — `Sentrum Scene, Oslo` or `John Dee Oslo` —
/// so that is where the city is read from, and it is folded off before the rooms compare.
private func venueLevel(_ ticketVenue: String, hit: FmVenue?) -> MatchLevel {
    let (room, suffix) = splitCitySuffix(ticketVenue)
    let mine = words(room)
    let theirs = words(splitCitySuffix(hit?.name ?? "").room)
    if !mine.isEmpty && !theirs.isEmpty &&
        (sameName(mine, theirs) || contains(mine, theirs) || contains(theirs, mine)) {
        return .strong
    }
    let city = words(hit?.city?.name ?? "")
    if city.isEmpty { return .none }
    let sameCity = (suffix.map { sameName(words($0), city) } ?? false) ||
        (mine.count > city.count && Array(mine.suffix(city.count)) == city)
    return sameCity ? .weak : .none
}

/// `Sentrum Scene, Oslo` as the room and what follows its last comma.
private func splitCitySuffix(_ venue: String) -> (room: String, suffix: String?) {
    guard let comma = venue.lastIndex(of: ","), comma != venue.startIndex else { return (venue, nil) }
    return (String(venue[..<comma]), String(venue[venue.index(after: comma)...]))
}

/// A name as folded words. Words, so containment respects them; folded one by one through
/// `foldName`, so the fold is the one the rest of the app matches names by. `&` reads as
/// `and`, the same substitution `nameKey` makes.
private func words(_ text: String) -> [String] {
    text.replacingOccurrences(of: "&", with: " and ")
        .split(whereSeparator: { !($0.isLetter || $0.isNumber) })
        .map { foldName(String($0)) }
        .filter { !$0.isEmpty }
}

/// Equal once the spacing is gone too, so `AC/DC` is `ACDC` and `Melody's` is `Melodys`.
private func sameName(_ a: [String], _ b: [String]) -> Bool { a.joined() == b.joined() }

/// `outer` holds `inner` as a run of whole words.
private func contains(_ outer: [String], _ inner: [String]) -> Bool {
    guard !inner.isEmpty, outer.count >= inner.count else { return false }
    return (0...(outer.count - inner.count)).contains { Array(outer[$0..<($0 + inner.count)]) == inner }
}
