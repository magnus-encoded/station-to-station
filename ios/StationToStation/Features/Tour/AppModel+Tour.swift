import Foundation
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
        let meetFriend = makeMeetFriendEffects(addGig)
        let night = TourNightArrivesEffects(
            demoGig: { [unowned self] in self.state.plannedGigs.first { addGig.demoGigIds.contains($0.id) } },
            deleteCalendarEvent: { deleteCalendarEvent($0) })
        let tour = TourController(host: self, settings: settings,
                                  connectivity: DeviceTourConnectivity(),
                                  demoWorld: DemoWorldRegistry(parts: [night, meetFriend, addGig]),
                                  addGig: addGig, meetFriend: meetFriend, night: night)
        planning.onGigAdded = { [unowned tour] in tour.gigAdded($0) }
        planning.onCalendarAdded = { [unowned tour] in tour.calendarAdded($0, eventId: $1) }
        gig.onCheckedIn = { [unowned tour] in tour.checkedIn($0) }
        return tour
    }

    private func makeMeetFriendEffects(_ addGig: TourAddGigEffects) -> TourMeetFriendEffects {
        TourMeetFriendEffects(
            friendName: { Self.characterName() },
            demoGig: { [unowned self] in
                self.state.plannedGigs.first { addGig.demoGigIds.contains($0.id) }
            },
            locate: { [unowned self] in
                await self.location.fixAskingFirst().map { TourLocation(latitude: $0.lat, longitude: $0.lon) }
            },
            placeVenue: { [unowned self] gig in
                await self.timelines.savePlanned(gig)
                self.state.plannedGigs = self.state.plannedGigs.map { $0.id == gig.id ? gig : $0 }
            },
            addContact: { [unowned self] in self.contacts.addFriend($0) },
            landNights: { [unowned self] key, nights, withdrawn in
                await self.contacts.landContactNights(key, nights, withdrawn)
            },
            removeContact: { [unowned self] in self.contacts.removeFriend($0) },
            importTicket: { [unowned self] in await self.tickets.importTicket($0) })
    }

    /// The Virtual friend's name from the character definition, empty until it is bundled.
    private static func characterName() -> String {
        guard let url = Bundle.main.url(forResource: "character", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return "" }
        return json["name"] as? String ?? ""
    }
}
