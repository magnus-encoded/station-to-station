package io.github.magnusencoded.setlist2spotify.ui

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** How many days out "approaching" starts counting down from. */
internal const val APPROACHING_DAYS = 7L

/**
 * What a future gig node shows, purely as a function of the calendar — never a
 * background job, never a notification. Recomputed at render from wall-clock time.
 *
 * [DAY_OF] is a one-way door once entered: a gig that has passed but has no
 * setlist yet stays [DAY_OF] indefinitely. Only a setlist landing (out of band,
 * not time) moves a gig off the future-node path entirely — that's #31/#33's job,
 * not this function's.
 *
 * [AT_VENUE] from the issue sketch — handing off to check-in — needs a GPS fix,
 * not just a clock, so it isn't a value this pure function can produce. That's #33.
 */
enum class GigTimeState {
    /** More than [APPROACHING_DAYS] out: the plain future node, no countdown yet. */
    FUTURE,

    /** Within [APPROACHING_DAYS]: show a countdown. */
    APPROACHING,

    /** Today, or any day after: the action becomes "open the venue in maps". */
    DAY_OF,
}

/** Pure: no Android, no I/O. `now` and `gigDate` are both calendar dates, not instants. */
fun gigTimeState(now: LocalDate, gigDate: LocalDate): GigTimeState {
    val daysUntil = ChronoUnit.DAYS.between(now, gigDate)
    return when {
        daysUntil <= 0 -> GigTimeState.DAY_OF
        daysUntil <= APPROACHING_DAYS -> GigTimeState.APPROACHING
        else -> GigTimeState.FUTURE
    }
}

/** "6 days", "1 day" — the countdown text for [GigTimeState.APPROACHING]. `daysUntil` must be >= 1. */
fun formatCountdown(daysUntil: Long): String {
    require(daysUntil >= 1) { "formatCountdown is for the approaching window, not day-of: $daysUntil" }
    return if (daysUntil == 1L) "1 day" else "$daysUntil days"
}

/**
 * "Venue Name, City" for a maps text query. setlist.fm carries no coordinates —
 * not even city-level — so this is the whole query; the OS geocodes it.
 * Null if there's nothing worth searching for.
 */
fun venueMapsQuery(venueName: String?, city: String?): String? {
    val parts = listOfNotNull(
        venueName?.trim()?.takeIf { it.isNotEmpty() },
        city?.trim()?.takeIf { it.isNotEmpty() },
    )
    return parts.joinToString(", ").takeIf { it.isNotEmpty() }
}
