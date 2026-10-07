import Foundation

/// The **Demo world**'s **Contact**, their **Line** and the ticket they send (S7–S9).
///
/// The Exchange happens on this phone only: the Virtual friend is a row on the Exchange
/// screen, and tapping it writes a **Contact** through the same `addFriend` a radio card
/// lands on. Their key is remembered across launches so a skip or the final purge can
/// take the **Contact** and their **Line** back. The ticket lives on the demo **Gig** as
/// its **Admission**, so it goes when that **Gig** is deleted.
@MainActor
final class TourMeetFriendEffects: DemoWorld {
    private static let keyKey = "tour.demoContactKey"
    private static let nightsKey = "tour.demoContactNights"

    private let store: UserDefaults
    private let friendName: () -> String
    private let demoGig: () -> FmSetlist?
    private let locate: () async -> TourLocation?
    private let placeVenue: (FmSetlist) async -> Void
    private let addContact: (Friend) -> Void
    private let landNights: (_ contactKey: String, _ nights: [FmSetlist], _ withdrawn: [String]) async -> Void
    private let removeContact: (Friend) -> Void
    private let importTicket: (Ticket) async -> Void

    init(store: UserDefaults = .standard,
         friendName: @escaping () -> String,
         demoGig: @escaping () -> FmSetlist?,
         locate: @escaping () async -> TourLocation?,
         placeVenue: @escaping (FmSetlist) async -> Void,
         addContact: @escaping (Friend) -> Void,
         landNights: @escaping (String, [FmSetlist], [String]) async -> Void,
         removeContact: @escaping (Friend) -> Void,
         importTicket: @escaping (Ticket) async -> Void) {
        self.store = store
        self.friendName = friendName
        self.demoGig = demoGig
        self.locate = locate
        self.placeVenue = placeVenue
        self.addContact = addContact
        self.landNights = landNights
        self.removeContact = removeContact
        self.importTicket = importTicket
    }

    var name: String { friendName() }

    /// The demo **Contact**'s key once the Exchange has happened, nil before.
    var contactKey: String? { store.string(forKey: Self.keyKey) }

    /// The nights put on the demo **Contact**'s **Line**, withdrawn again by the purge.
    var demoNights: [String] { store.stringArray(forKey: Self.nightsKey) ?? [] }

    /// The tap on the friend's row. Asks for the location once, puts the demo **Gig**'s
    /// venue where the person stands, and lands the friend with that **Gig** on their
    /// **Line**. Nil where no fix came: the venue then has no coordinates and Check-in
    /// falls back to by hand, as it does for any venue.
    func exchange() async -> TourLocation? {
        let fix = await locate()
        let key = contactKey ?? Self.mintKey()
        store.set(key, forKey: Self.keyKey)
        addContact(Friend(setlistfm: "", name: friendName(), publicKey: key))
        guard var gig = demoGig() else { return fix }
        if let fix {
            gig = placed(gig, at: fix)
            await placeVenue(gig)
        }
        if !demoNights.contains(gig.id) { store.set(demoNights + [gig.id], forKey: Self.nightsKey) }
        await landNights(key, [gig], [])
        return fix
    }

    /// S9: the friend's ticket for the demo **Gig**, through the import a shared one takes.
    func importDemoTicket() async {
        guard let gig = demoGig(), let ticket = Self.ticket(for: gig) else { return }
        await importTicket(ticket)
    }

    func purge() {
        guard let key = contactKey else { return }
        let nights = demoNights
        store.removeObject(forKey: Self.keyKey)
        store.removeObject(forKey: Self.nightsKey)
        let friend = Friend(setlistfm: "", name: friendName(), publicKey: key)
        Task {
            await landNights(key, [], nights)
            removeContact(friend)
        }
    }

    /// The gig with its venue's coordinates set to `location`, the coarse point Check-in reads.
    func placed(_ gig: FmSetlist, at location: TourLocation) -> FmSetlist {
        var placed = gig
        var venue = gig.venue ?? FmVenue()
        var city = venue.city ?? FmCity()
        city.coords = FmCoords(lat: location.latitude, long: location.longitude)
        venue.city = city
        placed.venue = venue
        return placed
    }

    /// A complete ticket for `gig` with one QR **Admission**, so the import matches it
    /// to that **Gig** without a prompt.
    static func ticket(for gig: FmSetlist) -> Ticket? {
        guard let artist = gig.artist?.name.nilIfBlank,
              let date = gig.eventDate.flatMap({ gigDay($0) }) else { return nil }
        let admission = Admission(payload: Data("tour-demo-ticket:\(gig.id)".utf8), symbology: qrSymbology)
        return Ticket(admissions: [admission], artist: artist, venue: gig.venue?.name ?? "", date: date)
    }

    /// A key shaped like a real one, so the **Contact** is a **Contact** and not a
    /// **Followed line**. Nothing is ever sent to it.
    private static func mintKey() -> String {
        Data((0..<65).map { _ in UInt8.random(in: .min ... .max) }).base64EncodedString()
    }
}
