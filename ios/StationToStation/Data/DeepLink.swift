import Foundation

// The Swift twin of Android's `DeepLink.kt`. `fixtures/deeplinks/` is the contract.

/// The screens a link can name and that do something. `log` is reserved and is not one.
enum LinkScreen: String { case timeline, timelines, programme, settings }

/// Where a place link lands: the **Gig** on its own, my single **Line** scrolled to it,
/// or the weave scrolled to it.
enum GigLink { case setlist, singleLine, woven }

/// The links that keep their own handler, carried to it unchanged.
enum PassThroughKind: String { case friend, handover, callback, ticket }

/// What a `station-to-station://` link asks for. Closed: a link outside it does nothing.
enum LinkIntent: Equatable {
    /// `date` is ISO `yyyy-MM-dd`; only `.timeline` and `.timelines` carry one.
    case open(LinkScreen, date: String?)
    case openGig(String)
    /// `date` is ISO `yyyy-MM-dd`. A field the link did not give, or gave blank, is nil.
    case addGig(artist: String?, venue: String?, date: String?)
    /// `replacements` is keyed by 1-based song number.
    case writeToLog(gigId: String, appends: [String], replacements: [Int: String])
    case legacyPlace(gigId: String, as: GigLink)
    case me
    case fixture(name: String, open: Bool)
    case passThrough(PassThroughKind, link: String)
}

private let passThroughs: [String: PassThroughKind] = [
    "friend": .friend, "handover": .handover, "callback": .callback, "ticket": .ticket,
]

/// Reads a whole link into what it asks for, or nil when it asks for nothing: a foreign
/// scheme, a blank link, a reserved-but-unwired screen (`log`), an unknown screen or an
/// unknown action on a known one.
///
/// Screen and action names match case-insensitively; ids and text keep their case.
/// Reserved screen names are checked before the legacy place grammar, so a **Line**
/// named like one has no place link. Grammar and cases: `fixtures/deeplinks/README.md`.
func parseDeepLink(_ link: String) -> LinkIntent? {
    let trimmed = link.trimmingCharacters(in: .whitespacesAndNewlines)
    guard let schemeEnd = trimmed.range(of: "://") else { return nil }
    let scheme = trimmed[..<schemeEnd.lowerBound].lowercased()
    guard scheme == "station-to-station" || scheme == "setlist2spotify" else { return nil }

    let rest = String(trimmed[schemeEnd.upperBound...]).split(separator: "#", maxSplits: 1,
                                                              omittingEmptySubsequences: false)[0]
    let path = rest.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false)[0]
    let query = rest.contains("?")
        ? String(rest[rest.index(after: rest.firstIndex(of: "?")!)...]) : ""
    let segments = path.split(separator: "/").map { decode(String($0)) }
    guard let screen = segments.first?.lowercased() else { return nil }
    let action = segments.count > 2 ? segments[2].lowercased() : nil
    let items = queryItems(query)

    if let kind = passThroughs[screen] { return .passThrough(kind, link: trimmed) }
    switch screen {
    case "timeline":
        if segments.count == 1 { return .open(.timeline, date: isoDate(items.value("date"))) }
        if segments.count == 2 && segments[1].lowercased() == "add-gig" {
            return .addGig(artist: given(items.value("artist")),
                           venue: given(items.value("venue")),
                           date: isoDate(items.value("date")))
        }
        return nil
    case "timelines":
        return segments.count == 1 ? .open(.timelines, date: isoDate(items.value("date"))) : nil
    case "programme": return segments.count == 1 ? .open(.programme, date: nil) : nil
    case "settings": return segments.count == 1 ? .open(.settings, date: nil) : nil
    case "log": return nil
    case "gig":
        if segments.count == 1 { return given(items.value("id")).map { .openGig($0) } }
        if segments.count == 2 { return given(segments[1]).map { .openGig($0) } }
        if segments.count == 3 && action == "write-to-log" { return writeToLog(segments[1], query) }
        return nil
    case "me": return .me
    case "fixture":
        if segments.count == 2 { return .fixture(name: segments[1], open: false) }
        if segments.count == 3 && action == "open" { return .fixture(name: segments[1], open: true) }
        return nil
    default:
        return segments.count <= 2 ? parseGigLink(segments) : nil
    }
}

/// Where a bare `<gigId>`, `<line>/<gigId>` or `Friends/<gigId>` lands.
private func parseGigLink(_ segments: [String]) -> LinkIntent? {
    let parts = segments.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
    guard let gig = parts.last else { return nil }
    let at: GigLink = parts.count < 2 ? .setlist
        : parts[0].lowercased() == "friends" ? .woven : .singleLine
    return .legacyPlace(gigId: gig, as: at)
}

private func writeToLog(_ gigId: String, _ query: String) -> LinkIntent? {
    guard let id = given(gigId) else { return nil }
    var appends: [String] = []
    var replacements: [Int: String] = [:]
    for raw in query.split(separator: "&") {
        guard let eq = raw.firstIndex(of: "=") else {
            if let text = given(decode(String(raw))) { appends.append(text) }
            continue
        }
        guard let n = Int(decode(String(raw[..<eq])).trimmingCharacters(in: .whitespaces)), n >= 1,
              let text = given(decode(String(raw[raw.index(after: eq)...])))
        else { continue }
        replacements[n] = text
    }
    return .writeToLog(gigId: id, appends: appends, replacements: replacements)
}

/// Raw items in order: `URLComponents.queryItems` would do, but reading them the same
/// way Kotlin does keeps the two from drifting on the odd link.
private func queryItems(_ query: String) -> [(name: String, value: String?)] {
    query.split(separator: "&").map { raw in
        guard let eq = raw.firstIndex(of: "=") else { return (decode(String(raw)), nil) }
        return (decode(String(raw[..<eq])), decode(String(raw[raw.index(after: eq)...])))
    }
}

private extension Array where Element == (name: String, value: String?) {
    func value(_ name: String) -> String? { first { $0.name == name }?.value ?? nil }
}

/// The text trimmed, or nil when nothing is left.
private func given(_ text: String?) -> String? {
    guard let t = text?.trimmingCharacters(in: .whitespacesAndNewlines), !t.isEmpty else { return nil }
    return t
}

/// Percent-decoding only: `+` stays a plus, as on Android, so a link means the same on both.
private func decode(_ s: String) -> String { s.removingPercentEncoding ?? s }

/// ISO `yyyy-MM-dd` and a real calendar day, or nil.
private func isoDate(_ text: String?) -> String? {
    guard let text, text.range(of: #"^\d{4}-\d{2}-\d{2}$"#, options: .regularExpression) != nil,
          dayNumber(text) != nil else { return nil }
    return text
}

/// Days since 1970-01-01 for an ISO date, nil when it is not a real day. Plain
/// arithmetic, so no calendar or zone can move it.
func dayNumber(_ iso: String) -> Int? {
    let p = iso.split(separator: "-").compactMap { Int($0) }
    guard p.count == 3, (1...12).contains(p[1]), p[2] >= 1 else { return nil }
    let (y, m, d) = (p[0], p[1], p[2])
    let leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
    let lengths = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
    guard d <= lengths[m - 1] else { return nil }
    let yy = m <= 2 ? y - 1 : y
    let era = (yy >= 0 ? yy : yy - 399) / 400
    let yoe = yy - era * 400
    let doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097 + doe - 719468
}

/// An ISO date from setlist.fm's `dd-MM-yyyy`, nil when it is not one.
func isoDate(fromFm text: String?) -> String? {
    guard let p = text?.split(separator: "-"), p.count == 3 else { return nil }
    return isoDate("\(p[2])-\(p[1])-\(p[0])")
}

/// The **Gig** in a nearest-date scroll: the smallest distance in days from `target`
/// (ISO), ties to the earlier **Gig**. Nil when there is nothing to scroll to.
func nearestGig(_ gigs: [(id: String, date: String)], to target: String) -> String? {
    guard let t = dayNumber(target) else { return nil }
    return gigs
        .compactMap { g in dayNumber(g.date).map { (id: g.id, day: $0) } }
        .min { a, b in
            let (da, db) = (abs(a.day - t), abs(b.day - t))
            return da != db ? da < db : a.day < b.day
        }?.id
}

/// `goingTo` is planned and claims nothing; `wasAt` is attended, minted locally with
/// nothing on setlist.fm to collide with.
enum NightKind { case goingTo, wasAt }

/// The rule the add form applies: a night before `today` (ISO) is one I was at; today,
/// later or no date is one I am going to.
func nightKind(date: String?, today: String) -> NightKind {
    guard let date, date < today else { return .goingTo }
    return .wasAt
}

/// A pre-filled add form; `date` is in the form's own dd-MM-yyyy, blank when the link gave none.
struct AddGigLink: Equatable {
    let artist: String
    let venue: String
    let date: String
}

enum OpenGigPlan { case open, fetchThenOpen, refuse }

/// What a link to a **Gig** does. On my **Line**, planned or attended, it opens and
/// adds nothing: an invite must not turn a night I attended into a plan. Otherwise a
/// setlist.fm id is fetched and opened unkept, and anything else is refused.
func planOpenGig(_ id: String, onMyLine: Bool) -> OpenGigPlan {
    if onMyLine { return .open }
    return parseSetlistId(id) != nil ? .fetchThenOpen : .refuse
}

extension StoredLog {
    /// The **Log** after a `write-to-log` link, as typed input would leave it.
    /// Replacements go first, in song order: song N replaces, N just past the end
    /// appends, and one further out is ignored, so a link never makes a **Gap** typing
    /// could not. The `appends` then follow in order.
    func writing(appends: [String], replacements: [Int: String], now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> StoredLog {
        var log = self
        for n in replacements.keys.sorted() {
            let at = n - 1
            let text = replacements[n]!
            if at < log.songs.count { log = log.correctingAt(at, title: text) }
            else if at == log.songs.count { log = log.adding(text, now: now) }
        }
        return appends.reduce(log) { $0.adding($1, now: now) }
    }
}

/// Today as ISO, in the phone's own calendar: the day the person is living through.
func isoToday(_ now: Date = Date(), calendar: Calendar = .current) -> String {
    let c = calendar.dateComponents([.year, .month, .day], from: now)
    return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
}
