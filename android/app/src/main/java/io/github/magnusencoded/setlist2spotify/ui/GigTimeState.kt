package io.github.magnusencoded.setlist2spotify.ui

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
 * [AT_VENUE] from the issue sketch — handing off to check-in — needs a GPS fix,
 * not just a clock, so it isn't a value this pure function can produce. That's #33.
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
 * What a node for a gig you're going to says under the venue — how far off it is.
 * Leans on [gigTimeState]'s real [GigTimeState.PAST] now that it has one, rather
 * than guarding the past by hand.
 */
fun plannedStatus(date: LocalDate?, now: LocalDateTime = LocalDateTime.now()): String {
    if (date == null) return "you're going"
    return when (gigTimeState(now, date)) {
        GigTimeState.FUTURE, GigTimeState.APPROACHING ->
            formatCountdown(ChronoUnit.DAYS.between(now.toLocalDate(), date))
        GigTimeState.DAY_OF -> "tonight"
        GigTimeState.PAST -> "no setlist yet"
    }
}

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
