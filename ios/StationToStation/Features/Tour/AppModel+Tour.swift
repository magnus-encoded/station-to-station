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
                                 friendKey: { meetFriend.contactKey }, characterLine: { ($0 == .s16 ? TourCharacter.bundled.notes.gapFill : TourCharacter.bundled.notes.setlistFill) })
        let selfie = TourSelfieEffects(host: self, timelines: timelines,
                                      demoGigIds: { addGig.demoGigIds }, friendKey: { meetFriend.contactKey },
                                      selfie: { UIImage(named: TourCharacter.bundled.selfie)?.jpegData(compressionQuality: 0.95) })
        let ending = TourSpotifyEffects(host: self, settings: settings, playlist: playlist, login: spotify,
                                        characterLine: { $0 == "playlist.title" ? TourCharacter.bundled.playlist.title : TourCharacter.bundled.playlist.description })
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
            friendName: { TourCharacter.bundled.name },
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
            importTicket: { [unowned self] in await self.tickets.importTicket($0) },
            extraNights: { [unowned self] gig in
                // Fictional character history stays independent of the chosen gig.
                (TourCharacter.bundled.history ?? []).enumerated().map { index, night in
                    localGigSetlist(gigId: "tour-history:\(index):\(gig.id)", artist: night.artist,
                                    date: night.date, venue: night.venue, city: night.city)
                }
            })
    }

}
