import Foundation

/// The published festival programme: `ProgrammeAct` is *who, when, where*. The Swift
/// twin of Android's `data/Programme.kt` (#173, #389).
///
/// A schedule the festival has committed to, down to the minute and the stage.
/// Nothing here is ever written by a user and nothing here is evidence of
/// attendance — it is the noticeboard, not the timeline.
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
    /// HH:mm, local — the end the source *published*, blank where it published none.
    ///
    /// Øya's own page never carried this and the inference below was the whole
    /// story. Clashfinder does carry it, and it is worth having: the default set
    /// length is an hour, a headline set is not, and an hour-long guess makes
    /// `clashesWith` report a real conflict as free time.
    var end: String = ""
    /// The MusicBrainz id the source published for this act, blank where it
    /// published none.
    ///
    /// Kept because it is the only field in a timetable that identifies an artist
    /// *exactly*. Where it is there, an **Act** added off this row resolves for
    /// free and without a name to get wrong.
    var mbid: String = ""

    /// When this act starts, as a moment.
    ///
    /// A start before `nightEndsHour` (see `GigTimeState.swift`) belongs to the
    /// *next* calendar day — a programme lists a 01:00 set under the night it
    /// belongs to, which is how everyone at the festival talks about it and the
    /// same boundary the rest of the app draws elsewhere. Without this an
    /// after-midnight act sorts to the front of its own day and clashes with the
    /// afternoon.
    func startsAt(calendar: Calendar = .current) -> Date? {
        setTimeOnNight(date, start, calendar: calendar)
    }

    /// The published end as a moment, on the same night rule as `startsAt`.
    func endsAt(calendar: Calendar = .current) -> Date? {
        end.isEmpty ? nil : setTimeOnNight(date, end, calendar: calendar)
    }
}

/// An `HH:mm` slot on the night of `dateString`, as a moment — see `ProgrammeAct.startsAt`.
private func setTimeOnNight(_ dateString: String, _ hhmm: String, calendar: Calendar) -> Date? {
    let dateParts = dateString.split(separator: "-").compactMap { Int($0) }
    let timeParts = hhmm.split(separator: ":").compactMap { Int($0) }
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

/// How long an act runs when the programme does not say.
///
/// ponytail: a flat hour, because the only alternatives are worse. It is the last
/// resort of three: a published end where there is one, otherwise the *next act on
/// the same stage*, which is real information, and this for the last act of the
/// night, where there is neither.
let defaultSetMinutes = 60

/// The longest a published end may run past its start before it reads as an error.
private let maxSetHours = 12

/// The published end of `act`, placed on the right side of the night boundary.
///
/// `ProgrammeAct.endsAt` reads the end clock on its own, which lands it a day early
/// for exactly one shape: an act that starts after midnight and ends at or after
/// `nightEndsHour` — the 02:00–06:00 stage that runs until it is light. Its start was
/// pushed into the next day and its end was not, so the end arrives *before* the
/// start and the real length is thrown away. Rolling it forward a day is what the
/// timestamps meant in the first place.
///
/// ponytail: a set longer than `maxSetHours` is a source that is simply wrong rather
/// than one crossing a boundary, and rolling that forward would invent a day-long act
/// that clashes with everything.
private func declaredEnd(_ act: ProgrammeAct, start: Date, calendar: Calendar) -> Date? {
    guard let end = act.endsAt(calendar: calendar) else { return nil }
    let placed = end > start ? end : (calendar.date(byAdding: .day, value: 1, to: end) ?? end)
    guard placed > start, let ceiling = calendar.date(byAdding: .hour, value: maxSetHours, to: start),
          placed <= ceiling
    else { return nil }
    return placed
}

/// When each act ends: as published, or inferred where it is not.
///
/// A declared end wins outright, uncapped — a 105-minute headline set truncated to
/// the default hour makes `clashesWith` report a genuine conflict as free time, which
/// is the one thing this feature exists to prevent.
///
/// The inference stays behind it rather than being deleted: a malformed act should
/// degrade to a guessed hour rather than drop out of clash detection entirely. It
/// prefers the next act on the same stage: a stage runs one act at a time, so the
/// following start is an upper bound and usually close to exact.
///
/// Returned as a dictionary rather than a field on `ProgrammeAct` because an
/// *inferred* end is not a property of the act. It is a property of the act and
/// everything after it.
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
            ends[act] = declaredEnd(act, start: start, calendar: calendar)
                ?? [next, capped].compactMap { $0 }.min() ?? capped
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

// MARK: - Stored programme

/// One festival's timetable as this phone holds it: the acts, and whose it is.
///
/// **The app never carries a copy of anyone's programme.** It is fetched by this
/// device, with the user's own clashfinder account, and kept only here — the app is
/// a user agent and not a publisher, and no line-up ships inside the binary.
///
/// `copyright` is not decoration. The data is CC BY-NC 3.0 and attribution is a
/// *condition* of that licence, so the line the payload carries is stored with the
/// acts and rendered with them. `lastEdit` is kept for a different reason: a
/// clashfinder is edited up to and past the doors, so how old this copy is decides
/// whether to trust it.
struct StoredProgramme: Codable, Equatable {
    /// The clashfinder's own id — what a refetch asks for.
    var id: String = ""
    /// The festival's name as its own document gives it: the screen's title.
    var name: String = ""
    var copyright: String = ""
    var lastEdit: String = ""
    var acts: [ProgrammeAct] = []
}

private let programmeEncoder = JSONEncoder()
private let programmeDecoder = JSONDecoder()

/// Reads a cached programme back. Anything unreadable is no programme at all.
func parseProgramme(_ text: String) -> StoredProgramme {
    guard let data = text.data(using: .utf8),
          let programme = try? programmeDecoder.decode(StoredProgramme.self, from: data)
    else { return StoredProgramme() }
    return programme
}

/// Writes one out, for the local cache and — later — for handing to another phone.
func encodeProgramme(_ programme: StoredProgramme) -> String {
    (try? programmeEncoder.encode(programme)).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
}
