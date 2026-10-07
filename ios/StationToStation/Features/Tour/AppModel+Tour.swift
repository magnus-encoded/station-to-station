import SystemConfiguration

struct DeviceTourConnectivity: TourConnectivity {
    func isOnline() -> Bool {
        guard let reachability = SCNetworkReachabilityCreateWithName(nil, "musicbrainz.org") else { return false }
        var flags = SCNetworkReachabilityFlags()
        guard SCNetworkReachabilityGetFlags(reachability, &flags) else { return false }
        return flags.contains(.reachable) && !flags.contains(.connectionRequired)
    }
}

extension AppModel {
    func makeTourController() -> TourController {
        let addGig = TourAddGigEffects { [unowned self] in self.gig.deleteGig($0) }
        let tour = TourController(host: self, settings: settings,
                                  connectivity: DeviceTourConnectivity(),
                                  demoWorld: DemoWorldRegistry(parts: [addGig]),
                                  addGig: addGig)
        planning.onGigAdded = { [unowned tour] in tour.gigAdded($0) }
        return tour
    }
}
