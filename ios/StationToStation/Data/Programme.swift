import Foundation

/// The published festival programme: `ProgrammeAct` is *who, when, where*. The Swift
/// twin of Android's `data/Programme.kt` (#173).
///
/// Not a `StoredAct`. A **Bill** is the *hedged* case — a poster with names and no
/// nights — and this is its opposite: a schedule the festival has committed to, down
/// to the minute and the stage. Nothing here is ever written by a user and nothing
/// here is evidence of attendance — it is the noticeboard, not the timeline.
///
/// `stage` is the collision axis. Two acts at one festival clash only because a
/// person cannot be in two places, so a clash is defined across *different* stages;
/// two names on one stage at one time would be a mistake in the programme, not a
/// choice to make.
struct ProgrammeAct: Codable, Equatable, Hashable {
    var artist: String = ""
    /// ISO yyyy-MM-dd, the festival day as the programme lists it.
    var date: String = ""
    /// HH:mm, local.
    var start: String = ""
    var stage: String = ""

    /// When this act starts, as a moment.
    ///
    /// A start before `nightEndsHour` (see `GigTimeState.swift`) belongs to the
    /// *next* calendar day — a programme lists a 01:00 set under the night it
    /// belongs to, which is how everyone at the festival talks about it and the
    /// same boundary the rest of the app draws elsewhere. Without this an
    /// after-midnight act sorts to the front of its own day and clashes with the
    /// afternoon.
    func startsAt(calendar: Calendar = .current) -> Date? {
        let dateParts = date.split(separator: "-").compactMap { Int($0) }
        let timeParts = start.split(separator: ":").compactMap { Int($0) }
        guard dateParts.count == 3, timeParts.count == 2 else { return nil }
        var comps = DateComponents()
        comps.year = dateParts[0]
        comps.month = dateParts[1]
        comps.day = dateParts[2]
        guard let day = calendar.date(from: comps) else { return nil }
        let (hour, minute) = (timeParts[0], timeParts[1])
        let base = (hour, minute) < (nightEndsHour, 0)
            ? (calendar.date(byAdding: .day, value: 1, to: day) ?? day)
            : day
        return calendar.date(bySettingHour: hour, minute: minute, second: 0, of: base)
    }
}

/// How long an act runs when the programme does not say — and it never says.
///
/// ponytail: a flat hour, because the only alternatives are worse. Øya publishes
/// start times and stage only, so an end time is always inferred; the inference
/// below prefers the *next act on the same stage*, which is real information, and
/// falls back to this for the last act of the night, where there is none.
let defaultSetMinutes = 60

/// When each act ends, inferred — because no festival publishes it.
///
/// The next act on the same stage is the honest source: a stage runs one act at a
/// time, so the following start is an upper bound on this one's end, and it is
/// usually close to exact because changeovers are short. Where there is no next
/// act — the last set of the night on that stage — there is nothing to lean on and
/// `defaultSetMinutes` stands in.
///
/// Returned as a dictionary rather than a field on `ProgrammeAct` because an act's
/// end is not a property of the act. It is a property of the act *and everything
/// after it*, and baking it into the record would make it look like published data.
func endTimes(_ acts: [ProgrammeAct], calendar: Calendar = .current) -> [ProgrammeAct: Date] {
    var ends: [ProgrammeAct: Date] = [:]
    for (_, onStage) in Dictionary(grouping: acts, by: { $0.stage }) {
        let sorted = onStage
            .compactMap { act -> (ProgrammeAct, Date)? in act.startsAt(calendar: calendar).map { (act, $0) } }
            .sorted { $0.1 < $1.1 }
        for (i, pair) in sorted.enumerated() {
            let (act, start) = pair
            let next = i + 1 < sorted.count ? sorted[i + 1].1 : nil
            let capped = calendar.date(byAdding: .minute, value: defaultSetMinutes, to: start) ?? start
            // A gap of hours means the stage went quiet, not that the act played
            // on. Cap at the default so an afternoon set doesn't swallow the
            // evening.
            ends[act] = [next, capped].compactMap { $0 }.min() ?? capped
        }
    }
    return ends
}

/// Half-open: an act ending exactly as another starts is a dash between stages,
/// not a clash.
private func overlaps(_ aStart: Date, _ aEnd: Date, _ bStart: Date, _ bEnd: Date) -> Bool {
    aStart < bEnd && bStart < aEnd
}

/// What `act` is a choice *against*: everything on another stage that overlaps it.
///
/// This is the question the whole feature exists to answer, and it is asked of one
/// act at a time because that is how it is asked in life — you know who you want to
/// see and you want to know the cost.
func clashesWith(_ act: ProgrammeAct, _ acts: [ProgrammeAct], calendar: Calendar = .current) -> [ProgrammeAct] {
    let ends = endTimes(acts, calendar: calendar)
    guard let start = act.startsAt(calendar: calendar), let end = ends[act] else { return [] }
    return acts.filter { other in
        guard other != act, other.stage != act.stage,
              let s = other.startsAt(calendar: calendar), let e = ends[other]
        else { return false }
        return overlaps(start, end, s, e)
    }.sorted { ($0.startsAt(calendar: calendar) ?? .distantFuture) < ($1.startsAt(calendar: calendar) ?? .distantFuture) }
}

/// Everything playing at `moment`, across all stages. The "what's on right now" list.
func playingAt(_ moment: Date, _ acts: [ProgrammeAct], calendar: Calendar = .current) -> [ProgrammeAct] {
    let ends = endTimes(acts, calendar: calendar)
    return acts.filter { act in
        guard let s = act.startsAt(calendar: calendar), let e = ends[act] else { return false }
        return moment >= s && moment < e
    }.sorted { $0.stage < $1.stage }
}

/// The next acts to start after `moment`, earliest first — the "and then" list.
func nextAfter(_ moment: Date, _ acts: [ProgrammeAct], limit: Int = 6, calendar: Calendar = .current) -> [ProgrammeAct] {
    acts.filter { $0.startsAt(calendar: calendar).map { $0 > moment } == true }
        .sorted { a, b in
            let sa = a.startsAt(calendar: calendar) ?? .distantFuture
            let sb = b.startsAt(calendar: calendar) ?? .distantFuture
            return sa != sb ? sa < sb : a.stage < b.stage
        }
        .prefix(limit)
        .map { $0 }
}

/// The festival days the programme covers, in order.
func programmeDays(_ acts: [ProgrammeAct], calendar: Calendar = .current) -> [Date] {
    Array(Set(acts.compactMap { parseISODay($0.date, calendar: calendar) })).sorted()
}

/// Acts on one festival day, in running order.
func actsOn(_ day: Date, _ acts: [ProgrammeAct], calendar: Calendar = .current) -> [ProgrammeAct] {
    let key = isoString(day, calendar: calendar)
    return acts.filter { $0.date == key }
        .sorted { a, b in
            let sa = a.startsAt(calendar: calendar) ?? .distantFuture
            let sb = b.startsAt(calendar: calendar) ?? .distantFuture
            return sa != sb ? sa < sb : a.stage < b.stage
        }
}

private func parseISODay(_ iso: String, calendar: Calendar = .current) -> Date? {
    let parts = iso.split(separator: "-").compactMap { Int($0) }
    guard parts.count == 3 else { return nil }
    var comps = DateComponents()
    comps.year = parts[0]
    comps.month = parts[1]
    comps.day = parts[2]
    return calendar.date(from: comps)
}

private func isoString(_ day: Date, calendar: Calendar = .current) -> String {
    let c = calendar.dateComponents([.year, .month, .day], from: day)
    return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
}

// MARK: - Cache

private let programmeEncoder = JSONEncoder()
private let programmeDecoder = JSONDecoder()

/// Reads a cached programme back.
func parseProgramme(_ text: String) -> [ProgrammeAct] {
    guard let data = text.data(using: .utf8),
          let acts = try? programmeDecoder.decode([ProgrammeAct].self, from: data)
    else { return [] }
    return acts
}

/// Writes one out, for the local cache and — later — for handing to another phone.
func encodeProgramme(_ acts: [ProgrammeAct]) -> String {
    (try? programmeEncoder.encode(acts)).flatMap { String(data: $0, encoding: .utf8) } ?? "[]"
}

// MARK: - Parsing the published page

/// The published programme, read off the festival's own page.
///
/// **The app never carries a copy of anyone's programme.** It is fetched by the
/// user's own device, from the public page, exactly as a browser would — the app
/// is a user agent here, not a publisher. Nothing about a festival's line-up ships
/// inside the binary, and that is a deliberate line, not an oversight.
///
/// Parsing is by regex over the server-rendered HTML, which is a thing to be
/// honest about: it is coupled to markup nobody promised to keep. That is
/// survivable because of *when* it runs — at home, the night before, with signal
/// and a screen — and because it fails visibly (no acts) rather than subtly. A
/// silent partial parse is the failure that would matter, which is why
/// `oyaProgramme` is all-or-nothing per act: an act missing any of its four fields
/// is dropped rather than half-built.
///
/// ponytail: one festival's markup, because one festival is what there is. The
/// shape generalises when a second one does.
let oyaProgrammeURL = "https://www.oyafestivalen.no/program/program-2026"

private let norwegianMonths: [String: Int] = [
    "januar": 1, "februar": 2, "mars": 3, "april": 4, "mai": 5, "juni": 6,
    "juli": 7, "august": 8, "september": 9, "oktober": 10, "november": 11,
    "desember": 12,
]

private let namedEntities: [String: String] = [
    "amp": "&", "lt": "<", "gt": ">", "quot": "\"", "apos": "'", "nbsp": " ",
]

private func compiledRegex(_ pattern: String, dotAll: Bool = false) -> NSRegularExpression {
    // Only ever called with the literal patterns below, so a throw here would be
    // a coding mistake, not a runtime condition to recover from.
    try! NSRegularExpression(pattern: pattern, options: dotAll ? [.dotMatchesLineSeparators] : [])
}

private let blockRegex = compiledRegex(#"<h3[^>]*>(.*?)</h3>(.*?)</ul>"#, dotAll: true)
private let listItemRegex = compiledRegex(#"<li>(.*?)</li>"#, dotAll: true)
private let dayRegex = compiledRegex(#"(\d{1,2})\.\s*(\p{L}+)"#)
private let clockRegex = compiledRegex(#"([0-2]\d:[0-5]\d)"#)
private let tagsRegex = compiledRegex(#"<!--.*?-->|<[^>]+>"#, dotAll: true)
private let entityRegex = compiledRegex(#"&(#x[0-9a-fA-F]+|#\d+|\w+);"#)
private let whitespaceRegex = compiledRegex(#"[\s ]+"#)

/// A code point as text, or nil if it is not one — `Character(Unicode.Scalar)`
/// requires a valid scalar, unlike a narrowing UTF-16 cast that would turn every
/// code point above U+FFFF into garbage. A band name is exactly the place an
/// emoji or a rare script turns up.
private func codePointString(_ code: UInt32) -> String? {
    Unicode.Scalar(code).map { String(Character($0)) }
}

/// One entity, decoded — or nil if it does not resolve, exactly one way, mirroring
/// Android's `when`: an entity's shape decides which branch answers it, and only
/// that branch gets to.
private func decodeEntity(_ entity: String) -> String? {
    if entity.hasPrefix("#x") {
        return UInt32(entity.dropFirst(2), radix: 16).flatMap(codePointString)
    } else if entity.hasPrefix("#") {
        return UInt32(entity.dropFirst(1)).flatMap(codePointString)
    } else {
        return namedEntities[entity]
    }
}

private extension String {
    /// Entities back into characters, because a name is what a person reads, not
    /// what a page encodes. Øya's own headliner is written `Nick Cave &amp; The
    /// Bad Seeds`, and an act stored that way never matches the **Gig** pasted
    /// from setlist.fm — the ampersand is the join between the programme and the
    /// timeline, and it has to be an ampersand.
    func decodingEntities() -> String {
        let ns = self as NSString
        var result = ""
        var lastEnd = 0
        for match in entityRegex.matches(in: self, range: NSRange(location: 0, length: ns.length)) {
            guard match.numberOfRanges >= 2 else { continue }
            let full = match.range
            result += ns.substring(with: NSRange(location: lastEnd, length: full.location - lastEnd))
            let entity = ns.substring(with: match.range(at: 1))
            result += decodeEntity(entity) ?? ns.substring(with: full)
            lastEnd = full.location + full.length
        }
        result += ns.substring(from: lastEnd)
        return result
    }

    /// Markup out, text in — the pages put artist names inside nested spans and
    /// comments.
    ///
    /// Stripping runs before decoding, so `&lt;b&gt;` in the source survives as
    /// the literal text `<b>`. That is right for a name a person reads, and safe
    /// only because the result is a display string and a match key: it goes to a
    /// SwiftUI `Text`, which renders characters and not markup. Never hand it to
    /// a `WKWebView`.
    func strippingTags() -> String {
        let ns = self as NSString
        let stripped = tagsRegex.stringByReplacingMatches(
            in: self, range: NSRange(location: 0, length: ns.length), withTemplate: "")
        let decoded = stripped.decodingEntities()
        let decodedNS = decoded as NSString
        // Collapse after decoding, and count a decoded non-breaking space as a
        // space: the page uses one to hold a name together, and it is a space to
        // everyone reading it.
        let collapsed = whitespaceRegex.stringByReplacingMatches(
            in: decoded, range: NSRange(location: 0, length: decodedNS.length), withTemplate: " ")
        return collapsed.trimmingCharacters(in: .whitespaces)
    }
}

/// Øya's programme page into acts. Pure: hand it the HTML, however you got it.
///
/// `year` is a parameter because the page writes "torsdag 13. august" with no
/// year in it at all. Taking it from the url rather than the clock means a
/// programme read in January is not dated to January.
func oyaProgramme(_ html: String, year: Int) -> [ProgrammeAct] {
    let ns = html as NSString
    var acts: [ProgrammeAct] = []
    var seen = Set<[String]>()

    for match in blockRegex.matches(in: html, range: NSRange(location: 0, length: ns.length)) {
        guard match.numberOfRanges >= 3 else { continue }
        let artist = ns.substring(with: match.range(at: 1)).strippingTags()
        guard !artist.isEmpty else { continue }

        let body = ns.substring(with: match.range(at: 2))
        let bodyNS = body as NSString
        let items = listItemRegex.matches(in: body, range: NSRange(location: 0, length: bodyNS.length))
            .compactMap { $0.numberOfRanges >= 2 ? bodyNS.substring(with: $0.range(at: 1)).strippingTags() : nil }
        guard items.count >= 3 else { continue }

        let firstItemNS = items[0] as NSString
        guard let dayMatch = dayRegex.firstMatch(in: items[0], range: NSRange(location: 0, length: firstItemNS.length)),
              dayMatch.numberOfRanges >= 3,
              let dayNum = Int(firstItemNS.substring(with: dayMatch.range(at: 1)))
        else { continue }
        let monthName = firstItemNS.substring(with: dayMatch.range(at: 2)).lowercased()
        guard let month = norwegianMonths[monthName] else { continue }

        let secondItemNS = items[1] as NSString
        guard let clockMatch = clockRegex.firstMatch(in: items[1], range: NSRange(location: 0, length: secondItemNS.length)),
              clockMatch.numberOfRanges >= 2
        else { continue }
        let clock = secondItemNS.substring(with: clockMatch.range(at: 1))

        let stage = items[2]
        guard !stage.isEmpty else { continue }

        let act = ProgrammeAct(
            artist: artist,
            date: String(format: "%04d-%02d-%02d", year, month, dayNum),
            start: clock,
            stage: stage
        )
        if seen.insert([act.artist, act.date, act.start, act.stage]).inserted {
            acts.append(act)
        }
    }

    return acts.sorted { a, b in
        if a.date != b.date { return a.date < b.date }
        if a.start != b.start { return a.start < b.start }
        return a.stage < b.stage
    }
}
