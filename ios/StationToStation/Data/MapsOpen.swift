import UIKit

/// Opens the venue in Maps by text query — see `venueMapsQuery`. No lat/long: setlist.fm
/// carries coordinates for the *city* only, never the venue, so a plain query string is
/// what lets Maps geocode the name itself. The iOS twin of Android's `openVenueInMaps`
/// (`ui/MapsIntent.kt`, #175).
///
/// `maps.apple.com` rather than the `maps://` scheme: it is a universal link Apple Maps
/// registers itself, so `UIApplication.open` hands the query straight to the app when it
/// is installed and falls back to a web preview in Safari when it is not (Apple Maps has
/// been removable since iOS 10) — no `LSApplicationQueriesSchemes` entry required either
/// way. A no-op when neither opens, the same degrade as Android's caught
/// ActivityNotFoundException — this call simply never fails outright.
func openVenueInMaps(_ query: String) {
    guard let encoded = query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed),
          let url = URL(string: "http://maps.apple.com/?q=\(encoded)")
    else { return }
    UIApplication.shared.open(url, options: [:], completionHandler: nil)
}
