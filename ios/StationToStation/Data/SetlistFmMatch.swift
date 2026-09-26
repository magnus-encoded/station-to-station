import Foundation

// Deliberately *not* in `Data/Ticket/`, which is the folder the Share Extension
// compiles: matching needs `FmSetlist` and the artists already on the **Line**, and the
// extension has neither. The twin of Android's `SetlistFmMatch.kt`, held to the same
// corpus (`fixtures/setlistfm-match/`).

/// How well one field of a setlist.fm hit agrees with a **Ticket** (#531, section 2).
///
/// A level, not a score: the decision below only ever asks "is it strong", and the
/// ranking only ever compares levels, so there is no number anywhere to tune. Ordered
/// best first, which is the order the ranking sorts by.
enum MatchLevel: Int, Comparable {
    case strong, weak, noMatch

    static func < (lhs: MatchLevel, rhs: MatchLevel) -> Bool { lhs.rawValue < rhs.rawValue }
}

/// A setlist.fm hit that survived: its artist and date were at least `.weak`. A hit
/// that fails on either never becomes a candidate at all.
///
/// `venue` is nil when the **Ticket** named no venue. Nothing to compare is not a
/// mismatch — the spec's link rule reads "strong on venue *or* the ticket has no
/// venue" — so it is kept apart from `.noMatch` rather than folded into it.
struct SetlistFmCandidate {
    let setlist: FmSetlist
    let artist: MatchLevel
    let date: MatchLevel
    let venue: MatchLevel?
}

/// What a **Ticket** looked up on setlist.fm comes to. The twin of Android's `SetlistFmMatch`.
enum SetlistFmMatch {
    /// One hit clears the bar and nothing else is as good: adopt it without asking.
    case linked(SetlistFmCandidate)
    /// Something survived but nothing is sure. At most `askAtMost`, best first; the
    /// prompt adds "None of these" itself, because that is a choice, not a hit.
    case ask([SetlistFmCandidate])
    /// Every hit was dropped, or there was nothing to look up with.
    case noMatch
}

/// Three is what fits in the review prompt without it becoming a list to scroll.
let askAtMost = 3

/// Matches a parsed **Ticket** against what setlist.fm's `search/setlists` returned for
/// its artist and day (#531). Pure: the lookup, the clock and the **Line** are all the
/// caller's, which is what lets both platforms be held to one corpus.
///
/// `lineArtists` are the artists already on the person's **Line**, as setlist.fm named
/// them. It is there for one rule only: a hit whose MusicBrainz id is the one that
/// artist already carries is the same artist whatever the ticket vendor printed.
///
/// **Link** is the magic path and the one this is shaped around: strong on artist and
/// date, strong on venue or no venue on the ticket, and no other hit as good. A tie at
/// the top is a question, never a coin toss — adoption sharpens a **Gig** (ADR-0002),
/// so it may only happen where nobody would have answered differently.
///
/// `calendar` is the one the **Ticket**'s date was read in, and is only used to turn
/// that date back into the day the person meant.
func matchSetlistFm(_ ticket: Ticket,
                    hits: [FmSetlist],
                    lineArtists: [FmArtist],
                    calendar: Calendar = .current) -> SetlistFmMatch {
    let ranked = rankSetlistFmHits(ticket, hits: hits, lineArtists: lineArtists, calendar: calendar)
    guard let best = ranked.first else { return .noMatch }
    let clearsBar = best.artist == .strong && best.date == .strong
        && (best.venue == nil || best.venue == .strong)
    // Ranked, so anything as good as the best sits right behind it.
    let tied = ranked.count > 1 && ranked[1].sameLevels(as: best)
    return clearsBar && !tied ? .linked(best) : .ask(Array(ranked.prefix(askAtMost)))
}

/// Every hit that survives, with its levels, best first. `matchSetlistFm` decides from
/// this; it is exposed so the corpus can assert the level of a hit the decision cut.
///
/// Venue first, because that is what the spec ranks by and a hit in the same city is
/// the likelier upgrade than one elsewhere. Artist and then date break a tie in venue,
/// and setlist.fm's own order breaks whatever is left, so the same hits always rank
/// the same way on both platforms.
func rankSetlistFmHits(_ ticket: Ticket,
                       hits: [FmSetlist],
                       lineArtists: [FmArtist],
                       calendar: Calendar = .current) -> [SetlistFmCandidate] {
    // A lookup needs an artist and a day. Without one there is nothing to hold a hit
    // to, and a hit held to nothing is not a match — it is just a setlist.
    guard let artist = ticket.artist.map(artistWords), !artist.isEmpty,
          let date = ticket.date,
          let night = parseFmDate(fmDate(date, calendar: calendar))
    else { return [] }

    // "That artist on the Line" is the one whose name folds to the ticket's.
    let knownIds = Set(lineArtists
        .filter { !$0.mbid.isEmpty && sameWords(artistWords($0.name), artist) }
        .map(\.mbid))

    let survivors: [(hit: FmSetlist, artist: MatchLevel, date: MatchLevel)] = hits.compactMap { hit in
        guard let a = artistLevel(artist, knownIds: knownIds, hit: hit),
              let d = dateLevel(night, hit: hit)
        else { return nil }
        return (hit, a, d)
    }

    let candidates: [SetlistFmCandidate]
    if let venue = ticket.venue, !venue.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
        let sameRoom = survivors.map { isSameRoom(venue, hit: $0.hit) }
        let cities = ticketCities(venue, hits: survivors.map { $0.hit }, sameRoom: sameRoom)
        candidates = survivors.enumerated().map { i, s in
            let level: MatchLevel
            if sameRoom[i] {
                level = .strong
            } else if cities.isEmpty {
                // No city on the ticket and none learned from a hit: the question is
                // asked either way, so the hit keeps the benefit of the doubt rather
                // than being ranked as though it were known to be elsewhere.
                level = .weak
            } else if let city = cityOf(s.hit), cities.contains(city) {
                level = .weak
            } else {
                level = .noMatch
            }
            return SetlistFmCandidate(setlist: s.hit, artist: s.artist, date: s.date, venue: level)
        }
    } else {
        candidates = survivors.map {
            SetlistFmCandidate(setlist: $0.hit, artist: $0.artist, date: $0.date, venue: nil)
        }
    }

    // `sorted(by:)` promises no stability, and setlist.fm's order is the last tie-break,
    // so the index rides along explicitly.
    return candidates.enumerated().sorted { l, r in
        let a = (l.element.venue ?? .strong, l.element.artist, l.element.date)
        let b = (r.element.venue ?? .strong, r.element.artist, r.element.date)
        return a != b ? a < b : l.offset < r.offset
    }.map { $0.element }
}

private extension SetlistFmCandidate {
    func sameLevels(as other: SetlistFmCandidate) -> Bool {
        artist == other.artist && date == other.date && venue == other.venue
    }
}

/// Strong: the same name after folding, or the MusicBrainz id that artist already has
/// on the **Line**. Weak: one name holds the other, word for word. Nil drops the hit.
private func artistLevel(_ ticket: [String], knownIds: Set<String>, hit: FmSetlist) -> MatchLevel? {
    if let mbid = hit.artist?.mbid, !mbid.isEmpty, knownIds.contains(mbid) { return .strong }
    let name = artistWords(hit.artist?.name ?? "")
    if sameWords(name, ticket) { return .strong }
    if holds(name, ticket) || holds(ticket, name) { return .weak }
    return nil
}

/// Strong on the day, weak a day either side — a ticket dated by the doors, a set that
/// ran past midnight. Anything further is another night, and the hit is dropped.
///
/// Both sides are midnight GMT by now (`parseFmDate`, `localDate()`), so the difference
/// is whole days with no DST jump to round away.
private func dateLevel(_ night: Date, hit: FmSetlist) -> MatchLevel? {
    guard let day = hit.localDate() else { return nil }
    switch Int((day.timeIntervalSince(night) / 86_400).rounded()) {
    case 0: return .strong
    case -1, 1: return .weak
    default: return nil
    }
}

/// The same room: equal after folding, or one name holding the other.
private func isSameRoom(_ venue: String, hit: FmSetlist) -> Bool {
    let ticket = roomWords(venue, hit: hit)
    let room = roomWords(hit.venue?.name ?? "", hit: hit)
    guard !ticket.isEmpty, !room.isEmpty else { return false }
    return sameWords(ticket, room) || holds(ticket, room) || holds(room, ticket)
}

/// Where the **Ticket** says the night is. A ticket has no city field, so this is what
/// can be read off it: a trailing `, City` that names a city a hit is in, and the city
/// of any hit already in the same room. Empty when neither says anything.
private func ticketCities(_ venue: String, hits: [FmSetlist], sameRoom: [Bool]) -> Set<String> {
    let known = Set(hits.compactMap(cityOf))
    var cities = Set<String>()
    if let comma = venue.range(of: ",", options: .backwards) {
        let suffix = foldWords(String(venue[comma.upperBound...])).joined()
        if !suffix.isEmpty, known.contains(suffix) { cities.insert(suffix) }
    }
    for (hit, same) in zip(hits, sameRoom) where same {
        if let city = cityOf(hit) { cities.insert(city) }
    }
    return cities
}

private func cityOf(_ hit: FmSetlist) -> String? {
    let city = foldWords(hit.venue?.city?.name ?? "").joined()
    return city.isEmpty ? nil : city
}

/// A venue name with a trailing `, Oslo` (or `, Norway`) taken off when it names the
/// hit's own city or country, so `Sentrum Scene, Oslo` = `Sentrum Scene`. Only a
/// segment that *is* the place comes off — `Rockefeller, Torggata 16` keeps its street.
private func roomWords(_ name: String, hit: FmSetlist) -> [String] {
    let places = Set([hit.venue?.city?.name, hit.venue?.city?.country?.name]
        .compactMap { $0 }
        .map { foldWords($0).joined() }
        .filter { !$0.isEmpty })
    var segments = name.components(separatedBy: ",")
    while segments.count > 1, let last = segments.last, places.contains(foldWords(last).joined()) {
        segments.removeLast()
    }
    return foldWords(segments.joined(separator: ","))
}

/// An artist name as this matcher compares it. The one place to change if the owner
/// picks `nameKey`'s rule instead (#531, second comment): stripping a trailing
/// parenthetical here makes `Wilco (US)` = `Wilco` strong, where the spec as written
/// rates it weak and asks.
private func artistWords(_ name: String) -> [String] { foldWords(name) }

/// The spec's folding — case, accents (ø/o), punctuation, whitespace — kept as words so
/// "one holds the other" can mean whole words: `Owl` is in `Owl Choir` and not in
/// `Owls`. Each word goes through `foldName`, so Nordic letters fold exactly as they do
/// for a festival search. `&` reads as `and`, the one spelling difference that is a
/// word rather than a mark.
///
/// A word is letters, digits and marks — Android's `[\p{L}\p{N}\p{M}]` — with marks
/// kept inside it so a decomposed ø or å is folded, not split on.
private func foldWords(_ text: String) -> [String] {
    var words: [String] = []
    var word = String.UnicodeScalarView()
    for scalar in text.replacingOccurrences(of: "&", with: " and ").unicodeScalars {
        if isWordScalar(scalar) {
            word.append(scalar)
        } else if !word.isEmpty {
            words.append(String(word))
            word = String.UnicodeScalarView()
        }
    }
    if !word.isEmpty { words.append(String(word)) }
    return words.map(foldName).filter { !$0.isEmpty }
}

private func isWordScalar(_ scalar: Unicode.Scalar) -> Bool {
    switch scalar.properties.generalCategory {
    case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter,
         .decimalNumber, .letterNumber, .otherNumber,
         .nonspacingMark, .spacingMark, .enclosingMark:
        return true
    default:
        return false
    }
}

/// Equal once spacing is off too: `Kjøkken Hagen` and `Kjøkkenhagen` are one name.
private func sameWords(_ a: [String], _ b: [String]) -> Bool {
    !a.isEmpty && a.joined() == b.joined()
}

/// `outer` holds `inner` as a run of whole words.
private func holds(_ outer: [String], _ inner: [String]) -> Bool {
    guard !inner.isEmpty, inner.count <= outer.count else { return false }
    return (0...(outer.count - inner.count)).contains { Array(outer[$0..<($0 + inner.count)]) == inner }
}
