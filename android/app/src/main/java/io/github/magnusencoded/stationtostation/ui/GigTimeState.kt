package io.github.magnusencoded.stationtostation.ui

import io.github.magnusencoded.stationtostation.data.StoredAttendance
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** How many days out "approaching" starts counting down from. */
internal const val APPROACHING_DAYS = 7L

/**
 * What a future gig node shows, purely as a function of the calendar — never a
 * background job, never a notification. Recomputed at render from wall-clock time.
 *
 * [DAY_OF] is the gig date and its night: it lasts through [NIGHT_ENDS] the next
 * morning (the same window a check-in uses), so a show that ran past midnight is
 * still tonight to everyone who was at it. Once that window closes the night is
 * [PAST] — and only then does the setlist.fm nudge make sense, since adding a
 * setlist is something you do after the show.
 *
 * `AT_VENUE` from the issue sketch — handing off to check-in — needs a GPS fix,
 * not just a clock, so it is deliberately not a value of this enum. Check-in lives
 * in `CheckIn.kt` instead, and reuses this window via [withinCheckInWindow].
 */
enum class GigTimeState {
    /** More than [APPROACHING_DAYS] out: the plain future node, no countdown yet. */
    FUTURE,

    /** Within [APPROACHING_DAYS]: show a countdown. */
    APPROACHING,

    /** The gig date, through [NIGHT_ENDS] next morning: maps and check-in territory. */
    DAY_OF,

    /** The night has been and gone: the setlist.fm crumb, where adding a setlist belongs. */
    PAST,
}

/**
 * Pure: no Android, no I/O. Needs the time of day, not just the date, because the
 * [GigTimeState.DAY_OF]/[GigTimeState.PAST] line falls at [NIGHT_ENDS] the morning
 * after — see [withinCheckInWindow], which draws exactly that window and is reused
 * here so the two can never drift apart.
 */
fun gigTimeState(now: LocalDateTime, gigDate: LocalDate): GigTimeState {
    val daysUntil = ChronoUnit.DAYS.between(now.toLocalDate(), gigDate)
    return when {
        daysUntil > APPROACHING_DAYS -> GigTimeState.FUTURE
        daysUntil >= 1 -> GigTimeState.APPROACHING
        withinCheckInWindow(now, gigDate) -> GigTimeState.DAY_OF
        else -> GigTimeState.PAST
    }
}

/**
 * What a **Gig**'s leaf *offers first*, on the clock — the same rule as [gigTimeState]
 * and #55, one step finer, and it governs **primacy only**.
 *
 * Read that last part as a hard rule: time decides which action is the headline, never
 * what remains possible. My own **Log** is editable forever — remembering a song three
 * days later, or three years later, must cost nothing — so [PUBLISH] does not take the
 * editor away, it just stops leading with it.
 */
enum class GigLeaf {
    /** Still ahead: the calendar, the invite, the map. Nothing has been played. */
    PLAN,

    /** It is happening: note what they play. Publishing is not the headline mid-set. */
    CAPTURE,

    /** Over: hand it to setlist.fm. The **Log** stays editable underneath. */
    PUBLISH,
}

/**
 * When a **Gig** counts as still going on, as coarse or as fine as the caller knows.
 *
 * A window rather than an instant, and given rather than computed, because precision
 * here is entirely a property of the *source*. Ringnes announces no set times at all,
 * so the honest window is the night — [nightWindow] — and every act from that evening
 * stays in capture until [NIGHT_ENDS]. A gig that one day arrives with real stage
 * times passes a tight window instead and this function does not change.
 *
 * [checkedIn] widens the opening edge and nothing else: standing there before the
 * listed start is the strongest evidence the thing has begun, which is the same
 * instinct `showsMediaBlock` already encodes.
 */
fun gigLeaf(
    now: LocalDateTime,
    window: ClosedRange<LocalDateTime>?,
    checkedIn: Boolean = false,
): GigLeaf = when {
    // An undated gig has no clock to follow, so it keeps the plan-ahead actions —
    // exactly what StationEventScreen already does with an unparseable date.
    window == null -> GigLeaf.PLAN
    now > window.endInclusive -> GigLeaf.PUBLISH
    checkedIn || !now.isBefore(window.start) -> GigLeaf.CAPTURE
    else -> GigLeaf.PLAN
}

/**
 * The default window: the whole night, closing at [NIGHT_ENDS] the next morning.
 *
 * The same soft edge [withinCheckInWindow] draws, reused rather than restated. A hard
 * cut at a computed end-of-set would be a precision the input does not have; the 6am
 * edge is this codebase's existing, honest answer to that.
 */
fun nightWindow(gigDate: LocalDate): ClosedRange<LocalDateTime> =
    gigDate.atStartOfDay()..gigDate.plusDays(1).atTime(NIGHT_ENDS)

/**
 * The humanised countdown for a gig still ahead — coarser the further off it is, so
 * "in 377 days" reads as "in 12 months". [daysUntil] must be >= 1; today, the night
 * itself and the past are other states' words, not a countdown's.
 */
fun formatCountdown(daysUntil: Long): String {
    require(daysUntil >= 1) { "formatCountdown is for a gig still ahead, not day-of or past: $daysUntil" }
    return when {
        daysUntil == 1L -> "tomorrow"
        daysUntil <= 13L -> "in $daysUntil days"
        daysUntil <= 30L -> "in ${daysUntil / 7} weeks"
        daysUntil / 30 == 1L -> "in 1 month"
        else -> "in ${daysUntil / 30} months"
    }
}

/**
 * What the **record** says about its own songs — never the calendar (#127). A night
 * that has passed can hold fifteen songs, and holding none is a fact about the record
 * whether the date is behind us or ahead. [songCount] is `FmSetlist.performed().size`.
 */
fun setlistStatus(songCount: Int): String =
    if (songCount > 0) "$songCount songs" else "no setlist yet"

/**
 * What a node for a gig you're going to says under the venue — how far off it is.
 * Leans on [gigTimeState]'s real [GigTimeState.PAST] now that it has one, rather
 * than guarding the past by hand.
 *
 * Once the night is [GigTimeState.PAST] the calendar has nothing left to say, so the
 * words come from the record via [setlistStatus] instead of being implied by the date.
 */
fun plannedStatus(
    date: LocalDate?,
    now: LocalDateTime = LocalDateTime.now(),
    songCount: Int = 0,
): String {
    if (date == null) return "you're going"
    return when (gigTimeState(now, date)) {
        GigTimeState.FUTURE, GigTimeState.APPROACHING ->
            formatCountdown(ChronoUnit.DAYS.between(now.toLocalDate(), date))
        GigTimeState.DAY_OF -> "tonight"
        GigTimeState.PAST -> setlistStatus(songCount)
    }
}

/**
 * Whether a **Gig** is still only a plan — asked of the attendance claim, never of
 * `gigPlanned` membership (#127). Nothing ever takes a night out of that map (it is
 * also the only home of the `FmSetlist` for a **Gig** with no import behind it), so
 * membership would make every night I ever planned a plan forever.
 *
 * `attended` and `checked_in` are evidence I was there; only `planned` is a plan. No
 * claim at all — an imported night — is not a plan either.
 */
fun isPlanned(provenance: String?): Boolean = provenance == StoredAttendance.Provenance.PLANNED

/**
 * The words on a **Gig**'s headline chip. A plan speaks in the calendar's terms until
 * its night passes; everything else — and every plan whose night has gone — speaks in
 * the record's, via [setlistStatus].
 */
fun gigStatus(
    planned: Boolean,
    date: LocalDate?,
    songCount: Int,
    now: LocalDateTime = LocalDateTime.now(),
): String = if (planned) plannedStatus(date, now, songCount) else setlistStatus(songCount)

/**
 * Whether the keepsake/media block belongs on a gig's detail screen. Never on a
 * planned gig nobody has checked into yet — nothing can be pinned to a night that
 * hasn't happened. A check-in is real attendance even while the gig is still in
 * `planned`, so it earns the block back on its own, ahead of setlist.fm's data.
 */
fun showsMediaBlock(planned: Boolean, checkedIn: Boolean): Boolean = !planned || checkedIn

/**
 * "Venue Name, City" for a maps text query — and the same string the check-in's
 * forward geocoder is given. setlist.fm carries coordinates for the *city* only
 * (`venue.city.coords`), never for the venue, so the venue's own position has to
 * come from geocoding this. Null if there's nothing worth searching for.
 */
fun venueMapsQuery(venueName: String?, city: String?): String? {
    val parts = listOfNotNull(
        venueName?.trim()?.takeIf { it.isNotEmpty() },
        city?.trim()?.takeIf { it.isNotEmpty() },
    )
    return parts.joinToString(", ").takeIf { it.isNotEmpty() }
}
