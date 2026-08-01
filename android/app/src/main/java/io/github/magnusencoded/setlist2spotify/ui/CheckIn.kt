package io.github.magnusencoded.setlist2spotify.ui

import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmSetlist
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Am I at this gig, right now? The pure half of check-in: time and distance only,
 * no Android, no permissions, no I/O — so both halves of the predicate can be
 * checked without a venue and a phone.
 *
 * Two distances rather than one because there are two sources of coordinates and
 * they are wildly different in precision. setlist.fm carries `venue.city.coords`
 * on every record — free, but city-centre, so on its own it would offer a check-in
 * to the whole metro area. The venue itself has to be geocoded (see DeviceLocation),
 * which costs a call, so the city coords are the coarse gate that decides whether
 * the call is worth making.
 */

/**
 * How close to the *venue's* geocoded point counts as being there.
 *
 * Assumed: a festival field is hundreds of metres across, an arena has a car park,
 * and a forward geocoder returns one point — an entrance or a centroid, not the
 * outline — so the far side of Tøyenparken has to still be inside this. 500 m is
 * the smallest radius that plausibly covers a festival site; it is deliberately
 * generous because the cost of being wrong is one dismissed prompt, while being
 * too tight means the prompt never appears at the gig it was built for.
 *
 * Untested against a real venue. Expect to tune this standing in a field.
 */
const val VENUE_RADIUS_M = 500.0

/**
 * How close to the *city* centre is worth geocoding the venue for.
 *
 * Only ever answers "am I in the right city" — Oslo's centre to its outskirts is
 * around 15 km, so 30 km covers a city and its suburban arenas without letting the
 * next city over through.
 */
const val CITY_GATE_M = 30_000.0

/**
 * The gig night ends at 06:00 the following morning.
 *
 * setlist.fm records a date, never a start time, so the window has to be the whole
 * day. Extending past midnight is the part that isn't arbitrary: a show that ends
 * at 01:30 is still that night to everyone who was at it, and refusing to check in
 * on the way out would be the one moment the feature exists for.
 */
val NIGHT_ENDS: LocalTime = LocalTime.of(6, 0)

/**
 * Is [gigDate] happening now? False for every past night — which is the guard that
 * matters, since [gigTimeState] has no PAST state and answers DAY_OF for a gig in
 * 2008. Nothing here may lean on it.
 */
fun withinCheckInWindow(now: LocalDateTime, gigDate: LocalDate?): Boolean {
    if (gigDate == null) return false
    return !now.isBefore(gigDate.atStartOfDay()) && now.isBefore(gigDate.plusDays(1).atTime(NIGHT_ENDS))
}

/** Great-circle metres. Haversine: exact enough at these distances, no dependency. */
fun metersBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * asin(min(1.0, sqrt(a)))
}

/** The city coordinates setlist.fm ships with the record, if it has any. */
fun FmSetlist.cityCoords(): Pair<Double, Double>? {
    val c = venue?.city?.coords ?: return null
    val lat = c.lat ?: return null
    val lon = c.long ?: return null
    return lat to lon
}

/**
 * The gig to offer a check-in for, or null. [where] is the device's fix; a gig
 * whose city coordinates are missing is skipped rather than guessed at — no
 * coordinates means no prompt.
 *
 * Coarse only: passing this earns the gig a venue geocode, not a prompt.
 */
fun checkInCandidate(
    gigs: List<FmSetlist>,
    now: LocalDateTime,
    where: Pair<Double, Double>?,
): FmSetlist? {
    if (where == null) return null
    val (lat, lon) = where
    return gigs.firstOrNull { gig ->
        withinCheckInWindow(now, gig.localDate()) &&
            gig.cityCoords()?.let { (cLat, cLon) ->
                metersBetween(lat, lon, cLat, cLon) <= CITY_GATE_M
            } == true
    }
}

/** The fine gate: at the venue itself, once it has been geocoded. */
fun atVenue(where: Pair<Double, Double>, venue: Pair<Double, Double>): Boolean =
    metersBetween(where.first, where.second, venue.first, venue.second) <= VENUE_RADIUS_M

/**
 * Can this gig be checked into by hand? Same window, no location — the fallback for
 * a refused permission or a venue no geocoder can find, and the reason neither of
 * those is a dead end.
 */
fun canCheckInManually(gig: FmSetlist, now: LocalDateTime): Boolean =
    withinCheckInWindow(now, gig.localDate())
