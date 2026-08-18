import CoreLocation

/// Where the phone is, and where a venue is. One fix, in the foreground, when
/// the timeline is opened (#174) — nothing here is scheduled and nothing runs
/// with the app closed. There is no background-location permission requested,
/// to go with it.
///
/// The iOS twin of Android's `data/DeviceLocation.kt`: `CLLocationManager` in
/// place of the platform `LocationManager`, `CLGeocoder` in place of the
/// keyless platform `Geocoder` — both foreground-only, both free.
@MainActor
final class DeviceLocation: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var fixContinuation: CheckedContinuation<(lat: Double, lon: Double)?, Never>?
    /// Fires once the permission prompt is answered — the iOS stand-in for
    /// Android's `rememberLauncherForActivityResult` callback, which is what
    /// lets a "not now" or "allow" answer resume the offer that asked for it.
    var onAuthorizationChanged: (() -> Void)?

    /// How long to wait for a fix before giving up and showing no prompt.
    ///
    /// The user is looking at their timeline; a check-in offer that arrives
    /// after they have started scrolling is worse than none. Indoors at a
    /// venue a GPS fix can take far longer than this — that is the case being
    /// given up on.
    private let fixTimeoutNs: UInt64 = 8_000_000_000

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    var hasPermission: Bool {
        manager.authorizationStatus == .authorizedWhenInUse || manager.authorizationStatus == .authorizedAlways
    }

    /// Only ever asked for on a night there is something to check into — never
    /// merely for opening the app.
    func requestPermission() {
        manager.requestWhenInUseAuthorization()
    }

    /// One fix, or nil — no permission, no provider, location switched off, or
    /// it took too long. Every one of those is "no prompt", never an error on
    /// screen.
    func currentFix() async -> (lat: Double, lon: Double)? {
        guard hasPermission, CLLocationManager.locationServicesEnabled() else { return nil }
        return await withCheckedContinuation { (continuation: CheckedContinuation<(lat: Double, lon: Double)?, Never>) in
            self.fixContinuation = continuation
            self.manager.requestLocation()
            Task {
                try? await Task.sleep(nanoseconds: self.fixTimeoutNs)
                self.resumeFix(with: nil)
            }
        }
    }

    /// The venue's own coordinates, from the keyless native forward geocoder —
    /// the refinement setlist.fm's city-level coords can't give. Nil when the
    /// venue name means nothing to the geocoder, which is a gig that simply
    /// gets no prompt.
    func geocodeVenue(_ query: String) async -> (lat: Double, lon: Double)? {
        guard let coord = try? await CLGeocoder().geocodeAddressString(query).first?.location?.coordinate
        else { return nil }
        return (coord.latitude, coord.longitude)
    }

    private func resumeFix(with fix: (lat: Double, lon: Double)?) {
        guard let continuation = fixContinuation else { return }
        fixContinuation = nil
        continuation.resume(returning: fix)
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last else { return }
        Task { @MainActor in self.resumeFix(with: (loc.coordinate.latitude, loc.coordinate.longitude)) }
    }

    // A failed fix is "no prompt", not an error on screen — the same contract
    // as a timeout.
    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor in self.resumeFix(with: nil) }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        Task { @MainActor in self.onAuthorizationChanged?() }
    }
}
