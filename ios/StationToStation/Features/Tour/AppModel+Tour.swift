import Foundation
import SystemConfiguration
import UIKit

struct DeviceTourConnectivity: TourConnectivity {
    func isOnline() -> Bool {
        guard let reachability = SCNetworkReachabilityCreateWithName(nil, "musicbrainz.org") else { return false }
        var flags = SCNetworkReachabilityFlags()
        guard SCNetworkReachabilityGetFlags(reachability, &flags) else { return false }
        return flags.contains(.reachable) && !flags.contains(.connectionRequired)
    }
}

extension AppModel {
    func makeTourController(setlistFm: SetlistFmClient, musicBrainz: MusicBrainzClient,
                            spotify: SpotifyClient) -> TourController {
        let addGig = TourAddGigEffects { [unowned self] in self.gig.deleteGig($0, keepingPlaylists: true) }
        let meetFriend = makeMeetFriendEffects(addGig)
        let night = TourNightArrivesEffects(
            demoGig: { [unowned self] in self.state.plannedGigs.first { addGig.demoGigIds.contains($0.id) } },
            deleteCalendarEvent: { deleteCalendarEvent($0) })
        let log = TourLogEffects(host: self, setlistFm: setlistFm, musicBrainz: musicBrainz,
                                 friendKey: { meetFriend.contactKey }, characterLine: { Self.characterLine($0) })
        let selfie = TourSelfieEffects(host: self, timelines: timelines,
                                      demoGigIds: { addGig.demoGigIds }, friendKey: { meetFriend.contactKey },
                                      selfie: { Self.characterSelfie() }, characterLine: { Self.characterLine(.s18) })
        let ending = TourSpotifyEffects(host: self, settings: settings, playlist: playlist, login: spotify,
                                        characterLine: { Self.characterLine($0) })
        let tour = TourController(host: self, settings: settings,
                                  connectivity: DeviceTourConnectivity(),
                                  demoWorld: DemoWorldRegistry(parts: [selfie, log, night, meetFriend, addGig]),
                                  addGig: addGig, meetFriend: meetFriend, night: night, log: log, selfie: selfie,
                                  spotify: ending)
        planning.onGigAdded = { [unowned tour] in tour.gigAdded($0) }
        planning.onCalendarAdded = { [unowned tour] in tour.calendarAdded($0, eventId: $1) }
        gig.onCheckedIn = { [unowned tour] in tour.checkedIn($0) }
        gig.logNow = { [unowned tour] in tour.now(for: $0) }
        gig.onLogWritten = { [unowned tour] in tour.logWritten($0, before: $1, after: $2, now: $3) }
        gigMedia.onMediaAttached = { [unowned tour] in tour.mediaAttachment(for: $0, to: $1) }
        contacts.mediaExchangeCache = { selfie.mediaExchangeCache($0) }
        handover.mediaExchangeCache = { selfie.mediaExchangeCache($0) }
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

    private static func characterLine(_ step: TourStep) -> String {
        characterLine(step.rawValue)
    }

    private static func characterLine(_ key: String) -> String {
        guard let url = Bundle.main.url(forResource: "character", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return "" }
        if key.hasPrefix("playlist."), let playlist = json["playlist"] as? [String: String] {
            return playlist[String(key.dropFirst("playlist.".count))] ?? ""
        }
        return (json["lines"] as? [String: String])?[key] ?? ""
    }

    private static func characterSelfie() -> Data? {
        guard let url = Bundle.main.url(forResource: "character", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let asset = (json["selfie"] as? String)?.nilIfBlank else { return nil }
        return UIImage(named: asset)?.jpegData(compressionQuality: 0.95)
    }
}
