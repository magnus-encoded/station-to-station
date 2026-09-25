import Foundation

// When a local **Gig** is next looked up on setlist.fm (#531, section 4). Pure: the
// foreground timer, launch and return-to-foreground hooks are plumbing that ask this and
// go, and nothing here runs in the background (ADR-0019 grants no exception for it).
// Term for term with Android's `SetlistFmLookupSchedule.kt`, and both assert
// `fixtures/setlistfm-lookup/` case for case.
//
// | Gig state                                    | Rate                   |
// |----------------------------------------------|------------------------|
// | Future, before the day of the night          | once a day             |
// | The day of the night, not checked in         | once an hour           |
// | Checked in, inside `gossipParticipationEnds` | every 5 minutes        |
// | After the night                              | once a day for 14 days |
// | Not local, or a possible-match chip pending  | never / paused         |

/// Future nights, and the 14 days after one.
let lookupDailySeconds: TimeInterval = 24 * 60 * 60

/// The day of the night, through `nightEndsHour` the morning after.
let lookupHourlySeconds: TimeInterval = 60 * 60

/// Checked in and still participating: the set is being played, so setlist.fm may be too.
let lookupCheckedInSeconds: TimeInterval = 5 * 60

/// How long after its night a local **Gig** is still looked for. Counted from the night's
/// end at `nightEndsHour`, not from its date, so every night gets the same 14 full days.
let lookupAfterNightSeconds: TimeInterval = 14 * 24 * 60 * 60

/// A pull to refresh this soon after the last lookup sends nothing.
let lookupFrictionSeconds: TimeInterval = 60

/// What a pull that met `lookupFrictionSeconds` shows. Word for word with Android's
/// `LOOKUP_FRICTION_MESSAGE`.
let lookupFrictionMessage = "We'll keep checking for you"

/// When a local **Gig**'s next automatic setlist.fm lookup is due, or nil for never.
///
/// Nil means one of: not local (matched and adopted, so there is nothing to find), a
/// "possible match" chip waiting for an answer, no date to search by, or the 14 days after
/// the night are over. A pull to refresh is `manualSetlistFmLookup`'s question, and works
/// on all of these.
///
/// The rate is the rate *at the moment the lookup would go out*, not at `now`: a future
/// night looked up yesterday evening is due at midnight, when its day starts and the rate
/// becomes hourly, rather than a whole day after the last one. So this walks the night's
/// boundaries from `now` and answers the first instant that is at least one interval after
/// `lastLookupAt` under the rate in force then. Never earlier than `now`; a lookup that is
/// overdue is due now.
///
/// - `gigDate`'s day, and its boundaries, are `nightWindow`'s, the same window
///   `gigTimeState`'s `.dayOf` draws, in `calendar`'s time zone.
/// - `participationUntil` is this Gig's entry in `gossipParticipationEnds`, or nil where it
///   is 0. Before it, the Gig is checked in and inside the window.
/// - `lastLookupAt` later than `now` is a clock that moved and is treated as no lookup at
///   all, `sharedQuotaSpent`'s rule for the same mistake.
/// - While `sharedQuotaSpent` believes the shared key spent, nothing is due before that
///   memory lapses. A key of the person's own (`sharedKey` false) is not held back by it.
func setlistFmLookupDue(gigDate: String, now: Date, calendar: Calendar = .current,
                        local: Bool, lastLookupAt: Date?, participationUntil: Date?,
                        possibleMatchPending: Bool,
                        sharedKey: Bool, sharedQuotaSpentAt: TimeInterval?) -> Date? {
    guard local, !possibleMatchPending,
          let window = nightWindow(gigDate: gigDate, calendar: calendar) else { return nil }
    let opens = window.lowerBound
    let ends = window.upperBound
    let stops = ends.addingTimeInterval(lookupAfterNightSeconds)

    func rate(at t: Date) -> TimeInterval? {
        if let participationUntil, t < participationUntil { return lookupCheckedInSeconds }
        if t < opens { return lookupDailySeconds }
        if t < ends { return lookupHourlySeconds }
        if t < stops { return lookupDailySeconds }
        return nil
    }

    var from = now
    if sharedKey, let spentAt = sharedQuotaSpentAt,
       sharedQuotaSpent(spentAt: spentAt, now: now.timeIntervalSince1970) {
        from = max(now, Date(timeIntervalSince1970: spentAt + sharedQuotaMemorySeconds))
    }
    let last = lastLookupAt.flatMap { $0 > now ? nil : $0 }
    // Every instant the rate can change at, after `from`; the rate is constant between two.
    let edges = Set([participationUntil, opens, ends, stops].compactMap { $0 })
        .filter { $0 > from }.sorted()
    var start = from
    for end in edges + [Date.distantFuture] {
        guard let interval = rate(at: start) else { return nil }
        let due = last.map { max(start, $0.addingTimeInterval(interval)) } ?? start
        if due < end { return due }
        start = end
    }
    return nil
}

/// What a pull to refresh on a local **Gig** does.
enum ManualLookup: Equatable {
    /// Send the lookup.
    case lookUpNow
    /// Send nothing and show `lookupFrictionMessage`: the Gig stays in the automatic checks.
    case friction
}

/// A pull to refresh on a local **Gig**: looks up now unless the last lookup, automatic or
/// pulled, went out less than `lookupFrictionSeconds` ago. Asks nothing of the schedule, so
/// a Gig whose automatic checks have stopped can still be pulled, and the friction still
/// holds on it. A last lookup in the future is a clock that moved, and does not hold a pull
/// back. The shared quota is not asked here either: `SetlistFmClient` already sends nothing
/// on a spent shared key, and its refusal carries the advice to add a key.
func manualSetlistFmLookup(lastLookupAt: Date?, now: Date) -> ManualLookup {
    guard let lastLookupAt else { return .lookUpNow }
    let elapsed = now.timeIntervalSince(lastLookupAt)
    return elapsed >= 0 && elapsed < lookupFrictionSeconds ? .friction : .lookUpNow
}
