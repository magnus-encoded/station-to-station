import Foundation

/// What a future **Gig** shows, purely as a function of the calendar (#175).
///
/// The Swift twin of Android's `ui/GigTimeState.kt`, ported with its fixtures. Never a
/// background job and never a notification — recomputed at render from wall-clock time,
/// which is why it can be a pure function at all.
///
/// Dates arrive as setlist.fm's `dd-MM-yyyy` string and the `Calendar` is injected,
/// following `photoWindow`: the same pattern, so the two date rules on this platform
/// are read the same way and a device on a non-Gregorian calendar cannot change what a
/// night means.
///
/// `GigLeaf` and `nightWindow` are here now (#177): the finer fold about what a **Gig**
/// offers is rendered on both platforms, so its shape is no longer a guess. What it
/// decides lives in `GigOffers.swift`; only the clock is here.

/// How many days out "approaching" starts counting down from.
let approachingDays = 7

/// The hour a gig night ends: 06:00 the following morning.
///
/// Numerically `photoWindowEndHour`, and deliberately its own constant, exactly as
/// Android keeps `NIGHT_ENDS` apart from the photo window. Same edge, different
/// questions — one is when a night stops being tonight, the other is which photographs
/// belong to it — and a future reason to move one is not a reason to move the other.
///
/// Extending past midnight is the part that is not arbitrary: a show ending at 01:30 is
/// still that night to everyone who was at it.
let nightEndsHour = 6

enum GigTimeState {
    /// More than `approachingDays` out: the plain future node, no countdown yet.
    case future
    /// Within `approachingDays`: show a countdown.
    case approaching
    /// The gig date, through `nightEndsHour` next morning: maps and check-in territory.
    case dayOf
    /// Been and gone: where adding a setlist belongs, because that is done afterwards.
    case past
}

/// setlist.fm's `dd-MM-yyyy` as a date, or nil where it will not parse.
///
/// A formatter rather than splitting on "-", for `photoWindow`'s reason: "2026-08-04"
/// splits into three numbers just fine and means the year 4, which is a wrong answer
/// rather than no answer.
func gigDay(_ gigDate: String, calendar: Calendar = .current) -> Date? {
    let parser = DateFormatter()
    parser.locale = Locale(identifier: "en_US_POSIX")
    parser.dateFormat = "dd-MM-yyyy"
    parser.timeZone = calendar.timeZone
    return parser.date(from: gigDate)
}

/// When a **Gig** counts as still going on, as coarse or as fine as the caller knows.
///
/// A window rather than an instant, and given to `gigLeaf` rather than computed by it,
/// because precision here is entirely a property of the *source*. A festival announces no
/// set times at all, so the honest window is the night, closing at `nightEndsHour` the
/// next morning. Half-open: 06:00 sharp is already the next day, which is the line
/// `gigTimeState` has always drawn.
///
/// The one window on this platform. `withinCheckInWindow`, the time state's day-of/past
/// line and the **Room**'s check-in all read it, because two windows that can disagree
/// eventually will.
func nightWindow(gigDate: String, calendar: Calendar = .current) -> Range<Date>? {
    guard let day = gigDay(gigDate, calendar: calendar),
          let nextDay = calendar.date(byAdding: .day, value: 1, to: day),
          let ends = calendar.date(bySettingHour: nightEndsHour, minute: 0, second: 0, of: nextDay)
    else { return nil }
    return day..<ends
}

/// Is `gigDate` happening now? False for every past night.
func withinCheckInWindow(now: Date, gigDate: String, calendar: Calendar = .current) -> Bool {
    nightWindow(gigDate: gigDate, calendar: calendar)?.contains(now) == true
}

/// What a **Gig**'s **Room** *offers first*, on the clock — the same rule as
/// `gigTimeState` and #55, one step finer, and it governs **primacy only**.
///
/// Read that last part as a hard rule: time decides which action is the headline, never
/// what remains possible. My own **Log** is editable forever — remembering a song three
/// days later, or three years later, must cost nothing — so `publish` does not take the
/// editor away, it just stops leading with it.
enum GigLeaf {
    /// Still ahead: the calendar, the invite, the map. Nothing has been played.
    case plan
    /// It is happening: note what they play. Publishing is not the headline mid-set.
    case capture
    /// Over: hand it to setlist.fm. The **Log** stays editable underneath.
    case publish
}

/// The leaf, from a window and a claim.
///
/// `checkedIn` widens the opening edge and nothing else: standing there before the listed
/// start is the strongest evidence the thing has begun, which is the same instinct
/// `showsMediaBlock` already encodes. An undated **Gig** has no clock to follow, so it
/// keeps the plan-ahead actions.
func gigLeaf(now: Date, window: Range<Date>?, checkedIn: Bool = false) -> GigLeaf {
    guard let window else { return .plan }
    if now >= window.upperBound { return .publish }
    return checkedIn || now >= window.lowerBound ? .capture : .plan
}

/// Whole days from `now`'s date to the gig's, ignoring the time of day — Android's
/// `ChronoUnit.DAYS.between(now.toLocalDate(), gigDate)`.
func daysUntilGig(now: Date, gigDate: String, calendar: Calendar = .current) -> Int? {
    guard let day = gigDay(gigDate, calendar: calendar) else { return nil }
    return calendar.dateComponents([.day], from: calendar.startOfDay(for: now), to: day).day
}

func gigTimeState(now: Date, gigDate: String, calendar: Calendar = .current) -> GigTimeState? {
    guard let days = daysUntilGig(now: now, gigDate: gigDate, calendar: calendar) else { return nil }
    if days > approachingDays { return .future }
    if days >= 1 { return .approaching }
    return withinCheckInWindow(now: now, gigDate: gigDate, calendar: calendar) ? .dayOf : .past
}

/// The humanised countdown for a gig still ahead — coarser the further off it is, so
/// "in 377 days" reads as "in 12 months".
///
/// `daysUntil` must be at least 1: today, the night itself and the past are other
/// states' words, not a countdown's. Android asserts that with `require`; here it
/// returns nil, because a precondition failure on a render path would take the whole
/// timeline down over a word.
func formatCountdown(daysUntil: Int) -> String? {
    guard daysUntil >= 1 else { return nil }
    switch daysUntil {
    case 1: return "tomorrow"
    case ...13: return "in \(daysUntil) days"
    case ...30: return "in \(daysUntil / 7) weeks"
    default: return daysUntil / 30 == 1 ? "in 1 month" : "in \(daysUntil / 30) months"
    }
}

/// What the **record** says about its own songs — never the calendar (#127). A night
/// that has passed can hold fifteen songs, and holding none is a fact about the record
/// whether the date is behind us or ahead.
func setlistStatus(songCount: Int) -> String {
    songCount > 0 ? "\(songCount) songs" : "no setlist yet"
}

/// What a node for a gig you are going to says under the venue — how far off it is.
///
/// Once the night is past the calendar has nothing left to say, so the words come from
/// the record via `setlistStatus` rather than being implied by the date.
func plannedStatus(gigDate: String?, now: Date, songCount: Int = 0,
                   calendar: Calendar = .current) -> String {
    guard let gigDate, let state = gigTimeState(now: now, gigDate: gigDate, calendar: calendar)
    else { return "you're going" }
    switch state {
    case .future, .approaching:
        let days = daysUntilGig(now: now, gigDate: gigDate, calendar: calendar) ?? 0
        return formatCountdown(daysUntil: days) ?? "you're going"
    case .dayOf:
        return "tonight"
    case .past:
        return setlistStatus(songCount: songCount)
    }
}

/// Whether a **Gig** is still only a plan — asked of the attendance claim, never of
/// `gigPlanned` membership (#127). Nothing ever takes a night out of that map, so
/// membership would make every night I ever planned a plan forever.
///
/// `attended` and `checked_in` are evidence I was there; only `planned` is a plan. No
/// claim at all — an imported night — is not a plan either.
func isPlanned(_ provenance: String?) -> Bool { provenance == "planned" }

/// The words on a **Gig**'s headline chip. A plan speaks in the calendar's terms until
/// its night passes; everything else speaks in the record's.
func gigStatus(planned: Bool, gigDate: String?, songCount: Int, now: Date,
               calendar: Calendar = .current) -> String {
    planned
        ? plannedStatus(gigDate: gigDate, now: now, songCount: songCount, calendar: calendar)
        : setlistStatus(songCount: songCount)
}

/// Whether the keepsake block belongs on a gig's screen. Never on a planned night
/// nobody has checked into — nothing can be pinned to a night that has not happened. A
/// check-in is real attendance even while the gig is still `planned`, so it earns the
/// block back on its own, ahead of setlist.fm's data.
func showsMediaBlock(planned: Bool, checkedIn: Bool) -> Bool { !planned || checkedIn }

/// "Venue Name, City" for a maps query. setlist.fm carries coordinates for the *city*
/// only, never the venue, so the venue's own position has to come from geocoding this.
/// Nil when there is nothing worth searching for.
func venueMapsQuery(venueName: String?, city: String?) -> String? {
    let parts = [venueName, city]
        .compactMap { $0?.trimmingCharacters(in: .whitespaces) }
        .filter { !$0.isEmpty }
    return parts.isEmpty ? nil : parts.joined(separator: ", ")
}
