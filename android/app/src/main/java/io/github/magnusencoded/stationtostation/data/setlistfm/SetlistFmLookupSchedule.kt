package io.github.magnusencoded.stationtostation.data.setlistfm

import io.github.magnusencoded.stationtostation.ui.nightWindow
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * When a local **Gig** is next looked up on setlist.fm (#531, section 4). Pure: the
 * foreground timer, launch and return-to-foreground hooks are plumbing that ask this and
 * go, and nothing here runs in the background (ADR-0019 grants no exception for it).
 * Term for term with iOS's `SetlistFmLookupSchedule.swift`, and both assert
 * `fixtures/setlistfm-lookup/` case for case.
 *
 * | Gig state                                    | Rate                   |
 * |----------------------------------------------|------------------------|
 * | Future, before the day of the night          | once a day             |
 * | The day of the night, not checked in         | once an hour           |
 * | Checked in, inside `gossipParticipationEnds` | every 5 minutes        |
 * | After the night                              | once a day for 14 days |
 * | Not local, or a possible-match chip pending  | never / paused         |
 */

/** Future nights, and the 14 days after one. */
val LOOKUP_DAILY: Duration = Duration.ofDays(1)

/** The day of the night, through `NIGHT_ENDS` the morning after. */
val LOOKUP_HOURLY: Duration = Duration.ofHours(1)

/** Checked in and still participating: the set is being played, so setlist.fm may be too. */
val LOOKUP_CHECKED_IN: Duration = Duration.ofMinutes(5)

/**
 * How long after its night a local **Gig** is still looked for. Counted from the night's
 * end at `NIGHT_ENDS`, not from its date, so every night gets the same 14 full days.
 */
val LOOKUP_AFTER_NIGHT: Duration = Duration.ofDays(14)

/** A pull to refresh this soon after the last lookup sends nothing. */
val LOOKUP_FRICTION: Duration = Duration.ofMinutes(1)

/** What a pull that met [LOOKUP_FRICTION] shows. Word for word with iOS's `lookupFrictionMessage`. */
const val LOOKUP_FRICTION_MESSAGE: String = "We'll keep checking for you"

/**
 * When a local **Gig**'s next automatic setlist.fm lookup is due, or null for never.
 *
 * Null means one of: not local (matched and adopted, so there is nothing to find), a
 * "possible match" chip waiting for an answer, no date to search by, or the 14 days after
 * the night are over. A pull to refresh is [manualSetlistFmLookup]'s question, and works
 * on all of these.
 *
 * The rate is the rate *at the moment the lookup would go out*, not at [now]: a future
 * night looked up yesterday evening is due at midnight, when its day starts and the rate
 * becomes hourly, rather than a whole day after the last one. So this walks the night's
 * boundaries from [now] and answers the first instant that is at least one interval after
 * [lastLookupAt] under the rate in force then. Never earlier than [now]; a lookup that is
 * overdue is due now.
 *
 * - [night]'s day, and its boundaries, are [nightWindow]'s, the same window
 *   `gigTimeState`'s DAY_OF draws. Its zone is [zone].
 * - [participationUntil] is this Gig's entry in `gossipParticipationEnds`, or null where it
 *   is 0. Before it, the Gig is checked in and inside the window.
 * - [lastLookupAt] later than [now] is a clock that moved and is treated as no lookup at
 *   all, [sharedQuotaSpent]'s rule for the same mistake.
 * - While [sharedQuotaSpent] believes the shared key spent, nothing is due before that
 *   memory lapses. A key of the person's own ([sharedKey] false) is not held back by it.
 */
fun setlistFmLookupDue(
    night: LocalDate?,
    now: Instant,
    zone: ZoneId,
    local: Boolean,
    lastLookupAt: Instant?,
    participationUntil: Instant?,
    possibleMatchPending: Boolean,
    sharedKey: Boolean,
    sharedQuotaSpentAt: Long?,
): Instant? {
    if (!local || possibleMatchPending || night == null) return null
    val window = nightWindow(night)
    val opens = window.start.atZone(zone).toInstant()
    val ends = window.endInclusive.atZone(zone).toInstant()
    val stops = ends.plus(LOOKUP_AFTER_NIGHT)

    fun rateAt(t: Instant): Duration? = when {
        participationUntil != null && t.isBefore(participationUntil) -> LOOKUP_CHECKED_IN
        t.isBefore(opens) -> LOOKUP_DAILY
        t.isBefore(ends) -> LOOKUP_HOURLY
        t.isBefore(stops) -> LOOKUP_DAILY
        else -> null
    }

    val spent = sharedKey && sharedQuotaSpent(sharedQuotaSpentAt, now.toEpochMilli())
    val from = if (spent) maxOf(now, Instant.ofEpochMilli(sharedQuotaSpentAt!! + SHARED_QUOTA_MEMORY_MS)) else now
    val last = lastLookupAt?.takeUnless { it.isAfter(now) }
    // Every instant the rate can change at, after `from`; the rate is constant between two.
    val edges = listOfNotNull(participationUntil, opens, ends, stops).filter { it.isAfter(from) }.distinct().sorted()
    var start = from
    for (end in edges + Instant.MAX) {
        val rate = rateAt(start) ?: return null
        val due = last?.plus(rate)?.let { maxOf(start, it) } ?: start
        if (due.isBefore(end)) return due
        start = end
    }
    return null
}

/** What a pull to refresh on a local **Gig** does. */
enum class ManualLookup {
    /** Send the lookup. */
    LOOK_UP_NOW,

    /** Send nothing and show [LOOKUP_FRICTION_MESSAGE]: the Gig stays in the automatic checks. */
    FRICTION,
}

/**
 * A pull to refresh on a local **Gig**: looks up now unless the last lookup, automatic or
 * pulled, went out less than [LOOKUP_FRICTION] ago. Asks nothing of the schedule, so a
 * Gig whose automatic checks have stopped can still be pulled, and the friction still
 * holds on it. A last lookup in the future is a clock that moved, and does not hold a
 * pull back. The shared quota is not asked here either: `SetlistFmClient` already sends
 * nothing on a spent shared key, and its refusal carries the advice to add a key.
 */
fun manualSetlistFmLookup(lastLookupAt: Instant?, now: Instant): ManualLookup {
    val elapsed = lastLookupAt?.let { Duration.between(it, now) }
    return if (elapsed != null && !elapsed.isNegative && elapsed < LOOKUP_FRICTION) {
        ManualLookup.FRICTION
    } else {
        ManualLookup.LOOK_UP_NOW
    }
}
