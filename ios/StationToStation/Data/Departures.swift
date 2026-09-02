import Foundation

/// **Departures**: a festival's timetable as a board of choices, and what committing
/// it would change on the **Line**. The Swift twin of Android's `data/Departures.kt`
/// (#391, #390).
///
/// One pure function (`departuresOf`) is the whole feature's logic. The screen draws
/// its return value and the button reads its return value; neither computes anything
/// itself.
///
/// **The selection is keyed by act, not by rung.** A rung keyed by the acts on it
/// loses its picks whenever the source moves a set — a clashfinder is edited up to
/// and past the doors. Keying by the *act* removes the question rather than
/// answering it: an act's night and name are what the source is least likely to
/// change, a rung is then pure layout and never stored, and "I choose none of these"
/// needs no sentinel because it is simply nothing on that rung being picked.
struct Departures {
    /// The festival's days, in order. Tabs are views over this; the diff is not
    /// scoped to one.
    var days: [Date]
    /// Each day's positions in running order.
    var positions: [Date: [Position]]
    var diff: ProgrammeDiff
}

/// One position in a day's running order: a single act, or a **rung** of acts that
/// overlap and are therefore a choice.
///
/// Recomputed on every render and never stored — see `Departures` on why a rung has
/// no identity of its own.
struct Position {
    var options: [ProgrammeAct]
    var isRung: Bool { options.count > 1 }
}

/// What committing would do to the **Line**, over the whole **Programme**: day tabs
/// never scope it.
///
/// `remove` is keys rather than acts on purpose — an act the source has since
/// dropped still has a **Gig** on the line, and that **Gig** still has to be
/// removable.
struct ProgrammeDiff {
    var add: [ProgrammeAct] = []
    var remove: [String] = []
    /// The days the *change* touches, derived from the rungs it moves and never
    /// from the tab in view.
    var days: [Date] = []

    var isEmpty: Bool { add.isEmpty && remove.isEmpty }
}

/// What identifies one act across a refetch: the night it plays and who plays it.
///
/// Not the stage and not the clock — those are exactly what an editor moves in the
/// week before the doors, and a refetch may not cost a user their picks. A festival
/// billing one artist twice on one night is the one shape this folds together, and
/// it is rare enough to accept.
func actKey(_ act: ProgrammeAct) -> String { actKey(date: act.date, artist: act.artist) }

func actKey(date: String, artist: String) -> String { "\(date)|\(nameKey(artist))" }

private let trailingTag = try! NSRegularExpression(pattern: #"\s*\([^()]*\)\s*$"#)

/// A name reduced to what two sources can be expected to agree on.
///
/// Clashfinder disambiguates by country — `Wilco (US)`, `Nick Cave and the Bad Seeds
/// (AU)` — where setlist.fm carries the plain name; one writes `and` where the other
/// writes `&`; and typographic apostrophes differ between them for the same artist.
/// Compared verbatim these are three different artists, so the same gig gets minted
/// twice and the one already on the **Line** never joins the **Festival**.
///
/// Built on `foldName` — the same fold `Clashfinder.swift` uses for its own festival
/// search — rather than a second Unicode-folding loop: `foldName` already carries the
/// table for the Nordic letters NFD does not decompose (ø, æ, ß…), and a second,
/// slightly different fold here would silently disagree with it on exactly the
/// artists this whole feature is named after.
///
/// ponytail: a trailing parenthetical is dropped whatever it says, so `Foo (DJ set)`
/// folds into `Foo`. Two sets by one artist on one night is rarer than the country
/// tags this fixes; split the rule if a festival ever bills both.
func nameKey(_ artist: String) -> String {
    let ns = trailingTag.stringByReplacingMatches(
        in: artist, range: NSRange(artist.startIndex..., in: artist), withTemplate: "")
    return foldName(ns.replacingOccurrences(of: "&", with: "and"))
}

/// The act on this night that is this artist — by MusicBrainz id where both sides
/// carry one, by name otherwise.
///
/// The id is exact and the name is a guess, so the id goes first. It is only there
/// on about one clashfinder act in fifty, which is why `nameKey` has to be good.
func matchAct(_ acts: [ProgrammeAct], name: String, mbid: String) -> ProgrammeAct? {
    if !mbid.isEmpty, let byId = acts.first(where: { !$0.mbid.isEmpty && $0.mbid == mbid }) {
        return byId
    }
    let key = nameKey(name)
    return acts.first { nameKey($0.artist) == key }
}

/// The board and the pending change, together. `picked` and `applied` are act keys.
func departuresOf(_ programme: StoredProgramme, picked: Set<String>, applied: Set<String>,
                  calendar: Calendar = .current) -> Departures {
    let days = programmeDays(programme.acts, calendar: calendar)
    var positions: [Date: [Position]] = [:]
    for day in days {
        positions[day] = rungs(actsOn(day, programme.acts, calendar: calendar), calendar: calendar)
    }
    return Departures(
        days: days,
        positions: positions,
        diff: programmeDiff(programme, picked: picked, applied: applied, calendar: calendar)
    )
}

/// A day's acts gathered into positions: everything transitively overlapping across
/// stages becomes one rung.
///
/// Transitive because a chain of three where the first and last do not touch is
/// still one decision — you cannot take the first and the last and skip the middle
/// without that being the same walk. Two acts on one stage never join a rung: a
/// stage runs one act at a time, so that is a sequence the festival posed, not a
/// choice.
///
/// ponytail: an O(n²) scan over one day's acts — a hundred rows at worst, and a
/// union-find would be more code than the thing it speeds up.
func rungs(_ acts: [ProgrammeAct], calendar: Calendar = .current) -> [Position] {
    let ends = endTimes(acts, calendar: calendar)
    var groups: [[ProgrammeAct]] = []
    for act in acts {
        var touchingIndexes: [Int] = []
        for (i, group) in groups.enumerated() {
            if group.contains(where: { clashes($0, act, ends: ends, calendar: calendar) }) {
                touchingIndexes.append(i)
            }
        }
        if touchingIndexes.isEmpty {
            groups.append([act])
        } else {
            var merged = touchingIndexes.reversed().reduce(into: [ProgrammeAct]()) { acc, idx in
                acc.append(contentsOf: groups.remove(at: idx))
            }
            merged.append(act)
            groups.append(merged)
        }
    }
    return groups
        .map { group in
            Position(options: group.sorted { a, b in
                let sa = a.startsAt(calendar: calendar) ?? .distantFuture
                let sb = b.startsAt(calendar: calendar) ?? .distantFuture
                return sa != sb ? sa < sb : a.stage < b.stage
            })
        }
        .sorted { a, b in
            let sa = a.options.first?.startsAt(calendar: calendar) ?? .distantFuture
            let sb = b.options.first?.startsAt(calendar: calendar) ?? .distantFuture
            if sa != sb { return sa < sb }
            return (a.options.first?.stage ?? "") < (b.options.first?.stage ?? "")
        }
}

private func clashes(_ a: ProgrammeAct, _ b: ProgrammeAct, ends: [ProgrammeAct: Date], calendar: Calendar) -> Bool {
    guard a.stage != b.stage,
          let aStart = a.startsAt(calendar: calendar), let bStart = b.startsAt(calendar: calendar),
          let aEnd = ends[a], let bEnd = ends[b]
    else { return false }
    return aStart < bEnd && bStart < aEnd
}

/// What is picked and not on the line, what is on the line and no longer picked.
func programmeDiff(_ programme: StoredProgramme, picked: Set<String>, applied: Set<String>,
                   calendar: Calendar = .current) -> ProgrammeDiff {
    var byKey: [String: ProgrammeAct] = [:]
    for act in programme.acts { byKey[actKey(act)] = act }
    let add = picked.subtracting(applied).compactMap { byKey[$0] }
        .sorted { a, b in
            let sa = a.startsAt(calendar: calendar) ?? .distantFuture
            let sb = b.startsAt(calendar: calendar) ?? .distantFuture
            return sa != sb ? sa < sb : a.stage < b.stage
        }
    let remove = applied.subtracting(picked).sorted()
    var days = Set(add.map { $0.date })
    days.formUnion(remove.map { String($0.split(separator: "|", maxSplits: 1)[0]) })
    let sortedDays = days.compactMap { parseISODate($0, calendar: calendar) }.sorted()
    return ProgrammeDiff(add: add, remove: remove, days: sortedDays)
}

private let weekdayNames = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"]

/// The English weekday name for `date`, read through `calendar` rather than a
/// `DateFormatter`.
///
/// A formatter's default time zone is the *system's*, not the calendar's that built
/// `date` — and `departuresOf` builds its day `Date`s at midnight in whichever
/// calendar it was handed. Reading the name back out through that same calendar is
/// what keeps the two in step; going through a formatter's own zone can read the
/// wrong side of midnight in a zone west of the one the date was built in.
private func weekdayName(_ date: Date, calendar: Calendar) -> String {
    weekdayNames[calendar.component(.weekday, from: date) - 1]
}

/// What the commit button says — the *change*, never what is on screen, so it can
/// be pressed from any tab without checking the others.
///
/// Nil when nothing has changed: a button that would do nothing should not be
/// offered.
func commitLabel(_ diff: ProgrammeDiff, festival: String, firstCommit: Bool, calendar: Calendar = .current) -> String? {
    if diff.isEmpty { return nil }
    if !diff.add.isEmpty && !diff.remove.isEmpty { return "Update \(daysPhrase(diff.days, calendar: calendar))" }
    if diff.add.isEmpty { return "Remove \(gigsWord(diff.remove.count))" }
    let name = festival.trimmingCharacters(in: .whitespaces)
    // The first commit off a programme is named in the language of the festival; a
    // later one is named by how much it adds, so an addition never reads as a
    // first commit.
    if firstCommit, diff.days.count == 1 {
        return "Add \(weekdayName(diff.days[0], calendar: calendar))" + (name.isEmpty ? "" : " at \(name)")
    }
    return "Add \(diff.add.count) more \(diff.add.count == 1 ? "gig" : "gigs")"
}

/// The line under the label: the arithmetic the label rounds off.
func commitSub(_ diff: ProgrammeDiff, festival: String, firstCommit: Bool, open: Int) -> String {
    if diff.isEmpty { return "" }
    if !diff.add.isEmpty && !diff.remove.isEmpty { return "+\(diff.add.count) \u{00B7} \u{2212}\(diff.remove.count)" }
    if diff.add.isEmpty { return festival.trimmingCharacters(in: .whitespaces) }
    if firstCommit { return gigsWord(diff.add.count) + (open > 0 ? " \u{00B7} \(open) still open" : "") }
    return festival.trimmingCharacters(in: .whitespaces)
}

private func gigsWord(_ n: Int) -> String { "\(n) \(n == 1 ? "gig" : "gigs")" }

/// "Thursday", "Thursday and Friday", "Wednesday, Thursday and Friday".
private func daysPhrase(_ days: [Date], calendar: Calendar) -> String {
    let names = days.map { weekdayName($0, calendar: calendar) }
    switch names.count {
    case 0: return "your programme"
    case 1: return names[0]
    default: return names.dropLast().joined(separator: ", ") + " and " + names.last!
    }
}

/// The acts whose sets have already finished at `now`.
///
/// Committing one of these is not a plan, it is a recollection — a **Programme** is
/// opened after the festival at least as often as before it. The claim is
/// `attended` and never `checked_in`: naming a set off a timetable days later is
/// remembering, not standing in front of the stage.
func playedActs(_ acts: [ProgrammeAct], now: Date, calendar: Calendar = .current) -> Set<String> {
    let ends = endTimes(acts, calendar: calendar)
    return Set(acts.filter { ends[$0].map { $0 < now } == true }.map { actKey($0) })
}

/// The **Festival** identity a **Programme** adopts: local id first, clashfinder id
/// under it.
func programmeFestivalId(_ programme: StoredProgramme) -> String {
    festivalIdForSlug("clashfinder:\(programme.id)")
}

/// Which of this **Programme**'s acts are already **Gigs** on the **Line**.
///
/// By night and artist across the *whole* line rather than only inside this
/// festival: an act already on the line from somewhere else is the duplicate this
/// feature exists to stop minting, so it reads as already applied and the commit
/// adopts it.
func appliedActs(_ programme: StoredProgramme, gigs: [FmSetlist]) -> Set<String> {
    let byNight = Dictionary(grouping: programme.acts, by: { $0.date })
    var out = Set<String>()
    for gig in gigs {
        guard let date = gig.localDate().map({ isoString($0, calendar: utcCalendar) }) else { continue }
        if let act = matchAct(byNight[date] ?? [], name: gig.artist?.name ?? "", mbid: gig.artist?.mbid ?? "") {
            out.insert(actKey(act))
        }
    }
    return out
}

/// `FmSetlist.localDate()` parses `eventDate` on a fixed UTC calendar (see
/// `fmFormatter`), so any conversion between a `Date` and one of the programme's
/// own `yyyy-MM-dd` day strings has to go through this same zone — otherwise a
/// person west of Greenwich could see their own gig land on the wrong night
/// relative to the programme's date string.
let utcCalendar: Calendar = {
    var c = Calendar(identifier: .gregorian)
    c.timeZone = TimeZone(secondsFromGMT: 0)!
    return c
}()

/// A `yyyy-MM-dd` day string — as `ProgrammeAct.date` and the clashfinder index
/// both give it — as a `Date` at UTC midnight. The counterpart to `isoString`.
func parseISODateUTC(_ iso: String) -> Date? { parseISODate(iso, calendar: utcCalendar) }

/// A programme act's `yyyy-MM-dd` night as `fmDate`'s `dd-MM-yyyy`, on the same UTC
/// zone `FmSetlist.localDate()` reads it back on — so a **Gig** minted from a
/// clashfinder act lands on the night the source named, not the night before it in
/// whatever zone the phone happens to be in.
func fmActDate(_ iso: String) -> String {
    parseISODateUTC(iso).map { fmDate($0, calendar: utcCalendar) } ?? iso
}

private func parseISODate(_ iso: String, calendar: Calendar) -> Date? {
    let parts = iso.split(separator: "-").compactMap { Int($0) }
    guard parts.count == 3 else { return nil }
    var comps = DateComponents()
    comps.year = parts[0]; comps.month = parts[1]; comps.day = parts[2]
    return calendar.date(from: comps)
}

private func isoString(_ day: Date, calendar: Calendar) -> String {
    let c = calendar.dateComponents([.year, .month, .day], from: day)
    return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
}
