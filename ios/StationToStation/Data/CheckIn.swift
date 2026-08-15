import Foundation

/// Am I at this gig, right now? The pure half of check-in (#174) — time and distance
/// only, no CoreLocation, no permissions, no I/O, so both halves of the predicate can
/// be checked without a venue and a phone.
///
/// The Swift twin of Android's `ui/CheckIn.kt`. `withinCheckInWindow` already lives in
/// `GigTimeState.swift`, where the time state reuses it to draw its own day-of/past
/// line — the same sharing Android does, for the same reason: two windows that can
/// disagree eventually will.
///
/// **Two distances rather than one**, because there are two sources of coordinates and
/// they differ wildly in precision. setlist.fm carries city coordinates on every
/// record — free, but city-centre, so on its own it would offer a check-in to the whole
/// metro area. The venue itself has to be geocoded, which costs a call, so the city
/// coordinates are the coarse gate deciding whether that call is worth making.

/// How close to the **venue's** geocoded point counts as being there.
///
/// A festival field is hundreds of metres across, an arena has a car park, and a
/// forward geocoder returns one point — an entrance or a centroid, not the outline — so
/// the far side of a festival site has to still be inside this. Deliberately generous:
/// the cost of being wrong is one dismissed prompt, while being too tight means the
/// prompt never appears at the gig it was built for.
///
/// Untested against a real venue on either platform. Expect to tune it standing in a
/// field.
let venueRadiusM = 500.0

/// How close to the **city** centre is worth geocoding the venue for.
///
/// Only ever answers "am I in the right city" — a city centre to its outskirts is
/// around 15 km, so 30 km covers a city and its suburban arenas without letting the
/// next city over through.
let cityGateM = 30_000.0

/// Great-circle metres. Haversine: exact enough at these distances, and no dependency.
func metersBetween(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
    let r = 6_371_000.0
    let dLat = (lat2 - lat1) * .pi / 180
    let dLon = (lon2 - lon1) * .pi / 180
    let a = sin(dLat / 2) * sin(dLat / 2)
        + cos(lat1 * .pi / 180) * cos(lat2 * .pi / 180) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * asin(min(1.0, a.squareRoot()))
}

extension FmSetlist {
    /// The city coordinates setlist.fm ships with the record, if it has any. A half
    /// known point — one of the two missing — is no point at all.
    func cityCoords() -> (lat: Double, lon: Double)? {
        guard let c = venue?.city?.coords, let lat = c.lat, let lon = c.long else { return nil }
        return (lat, lon)
    }
}

/// The gig to offer a check-in for, or nil.
///
/// `where` is the device's fix. A gig whose city coordinates are missing is **skipped
/// rather than guessed at** — no coordinates means no prompt.
///
/// Coarse only: passing this earns the gig a venue geocode, not a prompt.
func checkInCandidate(gigs: [FmSetlist], now: Date, where fix: (lat: Double, lon: Double)?,
                      calendar: Calendar = .current) -> FmSetlist? {
    guard let fix else { return nil }
    return gigs.first { gig in
        guard let date = gig.eventDate,
              withinCheckInWindow(now: now, gigDate: date, calendar: calendar),
              let city = gig.cityCoords()
        else { return false }
        return metersBetween(fix.lat, fix.lon, city.lat, city.lon) <= cityGateM
    }
}

/// The fine gate: at the venue itself, once it has been geocoded.
func atVenue(where fix: (lat: Double, lon: Double), venue: (lat: Double, lon: Double)) -> Bool {
    metersBetween(fix.lat, fix.lon, venue.lat, venue.lon) <= venueRadiusM
}

/// Can this gig be checked into by hand? Same window, no location — the fallback for a
/// refused permission or a venue no geocoder can find, and the reason neither of those
/// is a dead end.
func canCheckInManually(gig: FmSetlist, now: Date, calendar: Calendar = .current) -> Bool {
    guard let date = gig.eventDate else { return false }
    return withinCheckInWindow(now: now, gigDate: date, calendar: calendar)
}
