import Combine
import Foundation

/// Contacts: adding, overwriting and removing them, the contact exchange, landing their
/// Nights, their media offers, and the *maybes*. Android's `ContactsController`.
@MainActor
final class ContactsController: ObservableObject {

    /// Ephemeral, never persisted or sent. Shared by the Spine and Room comparison.
    @Published var maybeUndo: MaybeAnswer?

    private let host: StateHost
    private let settings: Settings
    private let timelines: TimelineStore
    private let setlistFm: SetlistFmClient
    private let spotify: SpotifyClient
    private let logic: TimelineLogic
    private let gossip: GossipController
    private let gig: GigController
    /// What this phone may offer a **Contact**: the **Tour**'s demo media left out.
    var mediaExchangeCache: (TimelineCache) -> TimelineCache = { $0 }

    init(host: StateHost, settings: Settings, timelines: TimelineStore,
         setlistFm: SetlistFmClient, spotify: SpotifyClient, logic: TimelineLogic,
         gossip: GossipController, gig: GigController) {
        self.host = host
        self.settings = settings
        self.timelines = timelines
        self.setlistFm = setlistFm
        self.spotify = spotify
        self.logic = logic
        self.gossip = gossip
        self.gig = gig
    }

    /// Asks setlist.fm for `friend`'s whole **Line** again. A failure keeps the last
    /// good copy. Android's `refreshLine`.
    func refreshLine(_ friend: Friend) {
        if friend.setlistfm.nilIfBlank == nil { return }
        Task {
            guard let shows = try? await setlistFm.attendedShows(friend.setlistfm).shows
            else { return }
            let fetched = [friend.laneKey: shows]
            host.state.showsByFriend = holdLanes(host.state.showsByFriend, fetched)
            await timelines.save(shows: fetched)
        }
    }

    /// Fetches whichever Followed Lanes `laneNeedsFetch` says need it (nothing
    /// held, or maybe cut short at a page) and holds what comes back, empty
    /// or not. Called when the strip opens — a cached-and-complete Lane costs
    /// nothing here. One friend's failure keeps their last good Lane and never
    /// blocks the others. Ported term for term from Android's
    /// `loadFriendTimelines`.
    func loadFriendTimelines() {
        let friends = host.state.friends
        if friends.isEmpty { return }
        let stale = friends.filter { laneNeedsFetch($0, held: host.state.showsByFriend[$0.laneKey]) }
        if stale.isEmpty { return }
        host.state.lanesLoading = true
        Task {
            // A failed fetch is left out entirely, so the friend keeps their last good
            // Lane; an empty answer is kept, so it is not asked for again (#405).
            var loaded: [String: [FmSetlist]] = [:]
            for friend in stale {
                let shows = try? await setlistFm.attendedShows(friend.setlistfm).shows
                if let shows { loaded[friend.setlistfm] = shows }
            }
            host.state.showsByFriend = holdLanes(host.state.showsByFriend, loaded)
            host.state.lanesLoading = false
            await timelines.save(shows: loaded)
        }
    }

    /// My shareable identity card, or nil until I've set my setlist.fm username.
    func myCardURL() async -> URL? {
        let me = host.state.mySetlistFmUser.trimmingCharacters(in: .whitespaces)
        if me.isEmpty { return nil }
        let user = try? await spotify.currentUser()
        return Friend(setlistfm: me,
                      name: user?.displayName?.nilIfBlank ?? me,
                      spotifyId: user?.id).shareURL
    }

    /// My card for the radio: the public key #28 makes the identity, and a username only
    /// if I have one (#405). Without one it is named by `myCardName`; see `probeCardFor`.
    /// Nil with neither — a card nobody can label is nothing to hand over. Only the radio
    /// carries this: a link cannot carry a key, so the QR stays username-only.
    ///
    /// The key was 32 random bytes per launch until #265, a stand-in that read as an
    /// identity and was not one: a Contact who stored it could never match this device
    /// again, because the next launch was a different person as far as the key was
    /// concerned. It is now the durable Secure Enclave identity, which is what makes a
    /// card exchanged today still recognisable on a WiFi network next month.
    ///
    /// **Nil if the keychain refuses, which stops BLE exchange entirely — deliberately.**
    /// The alternative is handing out a card carrying a throwaway key, and that is the
    /// precise bug above: the far end persists it, believes it has a Contact, and has one
    /// that can never be matched again. A pairing that visibly does not happen is
    /// recoverable; one that appears to work and did not is not.
    func myProbeCard() -> ProbeCard? {
        guard let key = ContactIdentity.publicKeyBase64() else { return nil }
        return probeCardFor(setlistfm: host.state.mySetlistFmUser, name: host.state.myCardName, publicKey: key)
    }

    /// The name on a card with no username (#405).
    func saveMyCardName(_ name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        settings.saveMyCardName(trimmed)
        host.state.myCardName = trimmed
    }

    /// A **Contact**'s **Nights**, off a **Reconcile** (#405): held under their Lane, on
    /// disk and on screen, so the Lane draws now and after a relaunch without asking
    /// anyone. `contactKey` is the key that verified; a Contact removed mid-session lands
    /// nothing. `withdrawn` is the Nights of theirs they took back, which leave the Lane
    /// here too, along with the bytes of whatever was offered me for them. Android's
    /// `landContactNights`.
    func landContactNights(_ contactKey: String, _ nights: [FmSetlist], _ withdrawn: [String] = []) async {
        let key = contactKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let friend = host.state.friends.first(where: {
            $0.publicKey?.trimmingCharacters(in: .whitespacesAndNewlines) == key
        }) else { return }
        let lane = friend.laneKey
        let (held, dropped) = await timelines.mergeContactNights(lane, nights, withdrawn)
        for media in dropped { PhotoLibrary.deleteReceivedMedia(media) }
        // The Lane as written, never one rebuilt from what is on screen: a Reconcile can
        // land before the screen has its copy, and rebuilding from nothing drew the whole
        // Lane as the few Nights the session touched.
        if let held { host.state.showsByFriend[lane] = held }
        if !withdrawn.isEmpty { host.state.mediaOffers = await timelines.load().mediaOffers }
    }

    /// What a Contact sent for Nights I have not joined, kept and shown (#405).
    func holdMediaOffers(_ offers: [String: MediaOffer]) async {
        await timelines.holdMediaOffers(offers)
        host.state.mediaOffers = await timelines.load().mediaOffers
    }

    /// Yes to a **Contact**'s offer (#405): their media is filed on my Night `key` and
    /// their Night `night` is joined, so what they send for it later lands there directly.
    func acceptMediaOffer(_ night: String, key: String) {
        Task {
            await timelines.acceptMediaOffer(night, key: key)
            let cache = await timelines.load()
            host.state.mediaOffers = cache.mediaOffers
            host.state.mediaBySetlist = cache.media()
            host.state.nightJoins = cache.spineJoins()
        }
    }

    /// No to a **Contact**'s offer (#405): my Night is left exactly as it was.
    func declineMediaOffer(_ night: String) {
        Task {
            await timelines.declineMediaOffer(night)
            host.state.mediaOffers = await timelines.load().mediaOffers
        }
    }

    /// The *maybes* on the open Night (#405): a Contact out the same date under a Night
    /// nothing links to mine. The same rule the Spine's weave draws them by, asked of this
    /// one Night, so the Room and the Line cannot disagree about which are open.
    func maybesOnSelected() -> [MaybeNight] {
        guard let show = host.state.selectedSetlist else { return [] }
        return maybeNights(
            mine: host.state.timelineShows,
            friends: host.state.friends,
            theirs: host.state.showsByFriend,
            festivals: host.state.festivals,
            joins: host.state.nightJoins,
            apart: host.state.nightsApart
        ).filter { $0.mine.id == show.id }
    }

    /// "Same Night" to a *maybe* (#405): their Night `night` is joined to my Night `key`.
    /// Mine alone — nothing is sent — and from here the Spine draws it **Joined** and what
    /// they send for it lands directly.
    func joinNight(_ night: String, key: String) async {
        await timelines.joinNight(night, key: key)
        host.state.nightJoins = await timelines.load().spineJoins()
    }

    /// "Not the same" to a *maybe* (#405): the marker goes, and stays gone. Mine alone.
    func dismissMaybe(_ night: String, key: String) async {
        await timelines.dismissMaybe(night, key: key)
        host.state.nightsApart = await timelines.load().spineDismissals()
    }

    /// Offer Undo only once the write and the visible state have both settled.
    func answerMaybe(_ maybe: MaybeNight, same: Bool) async {
        if same { await joinNight(maybe.theirs.id, key: maybe.mine.id) }
        else { await dismissMaybe(maybe.theirs.id, key: maybe.mine.id) }
        maybeUndo = MaybeAnswer(maybe: maybe, same: same)
    }

    /// "Same night", then "Take it" (#580): my typed-by-hand Night adopts their setlist.fm
    /// entry, so both Nights answer to one id and meet without a join. No Undo: the
    /// adoption says "Adopted" itself. Where it can't happen it falls back to a join.
    func adoptMaybe(_ maybe: MaybeNight) async {
        maybeUndo = nil
        if !(await gig.adoptSetlist(gigId: maybe.mine.id, setlistId: maybe.theirs.id, fresh: nil, notice: true)) {
            await answerMaybe(maybe, same: true)
        }
    }

    func undoMaybe(_ answer: MaybeAnswer) async {
        guard maybeUndo?.id == answer.id else { return }
        maybeUndo = nil
        if answer.same {
            await timelines.unjoinNight(answer.maybe.theirs.id, key: answer.maybe.mine.id)
            host.state.nightJoins = await timelines.load().spineJoins()
        } else {
            await timelines.undismissMaybe(answer.maybe.theirs.id, key: answer.maybe.mine.id)
            host.state.nightsApart = await timelines.load().spineDismissals()
        }
    }

    /// Whether there is anybody worth searching a network for: a **Contact** whose public
    /// key was actually persisted.
    ///
    /// This is the whole of the local-network permission gate. iOS raises that prompt the
    /// first time a browser or listener starts and offers no separate way to ask, so
    /// *when discovery starts* is the only lever there is — and a brand-new user pairing
    /// for the first time should not be asked for a permission that would do nothing.
    var hasReconcilableContact: Bool {
        host.state.friends.contains { $0.publicKey?.nilIfBlank != nil }
    }

    /// #265's LAN reconcile, screen-scoped: `start`/`stop` sit on `ExchangeView`'s own
    /// lifecycle, alongside the BLE session it already runs there.
    ///
    /// Contact keys are re-read on every session rather than captured at `start` — the
    /// list is the authority at the moment it is used, which is also what makes removing a
    /// Contact the whole of revocation (#265).
    ///
    /// The `hasReconcilableContact` gate above, by contrast, is only consulted at `start`.
    /// That is exactly right rather than a gap: every path that adds a Contact pops back to
    /// the root, so there is no way to gain a first Contact and still be on this screen.
    private lazy var contactExchange = ContactExchange(
        contactKeys: { [settings] in settings.friends.compactMap { $0.publicKey?.nilIfBlank } },
        manifest: { [timelines, settings, weak self] in
            guard let me = ContactIdentity.publicKeyBase64() else { return HandoverManifest() }
            let cache = await timelines.load()
            let offered = await self?.mediaExchangeCache(cache) ?? TimelineCache()
            return await hashedContactManifest(offered, me: me,
                                               setlistfm: settings.mySetlistFmUser ?? "")
        },
        mine: { [timelines, weak self] in
            let cache = await timelines.load()
            return await self?.mediaExchangeCache(cache) ?? TimelineCache()
        },
        gallery: { [timelines] in
            let windows = await timelines.load().gigs.values
                .compactMap { photoWindow(gigDate: $0.date) }
            return await PhotoLibrary.galleryItems(dates: windows)
        },
        onLanded: { [timelines] landing in await timelines.mergeContactMedia(landing) },
        lanesByKey: { [timelines, settings] in
            let shows = await timelines.load().shows
            var out: [String: [FmSetlist]] = [:]
            for friend in settings.friends {
                if let key = friend.publicKey?.nilIfBlank, let lane = shows[friend.laneKey] {
                    out[key] = lane
                }
            }
            return out
        },
        onNights: { [weak self] key, nights, withdrawn in await self?.landContactNights(key, nights, withdrawn) },
        myNights: { [timelines, settings] in
            await timelines.load().mySpine(settings.mySetlistFmUser ?? "")
        },
        onOffers: { [weak self] offers in await self?.holdMediaOffers(offers) }
    )

    func startContactExchange() {
        if hasReconcilableContact { contactExchange.start() }
    }

    func stopContactExchange() { contactExchange.stop() }

    /// A card handed to me. Writes into an empty space, promotes a **Followed line** the
    /// moment a key arrives, and **asks before changing a contact I already hold** (#188).
    ///
    /// Every route in comes through here — a deep link, a QR scan, a BLE write, a typed
    /// username, a playlist collaborator — so the question is answered once rather than
    /// at each door. Android decides it with the same function over the same four cases.
    func addFriend(_ friend: Friend) {
        switch friendArrival(friend, known: host.state.friends) {
        case .unchanged: break
        case .new(let f): writeFriend(f)
        // A **Followed line** becoming a **Contact**. Written as silently as a new one:
        // there was no key held, so nothing is being overwritten.
        case .promotion(let f): writeFriend(f)
        case .conflict(let existing, let incoming):
            host.state.friendConflict = FriendConflict(existing: existing, incoming: incoming)
        }
    }

    func confirmFriendOverwrite() {
        guard let pending = host.state.friendConflict else { return }
        host.state.friendConflict = nil
        writeFriend(pending.incoming)
    }

    /// Cancel: the held record is left exactly as it was. Also what dismissing does, so
    /// doing nothing can never be an accidental yes.
    func dismissFriendOverwrite() { host.state.friendConflict = nil }

    private func writeFriend(_ friend: Friend) {
        // De-duped on the key, then the username (#405), and never dropping a key or a
        // username a thinner card is silent about. See `withFriend`.
        let next = withFriend(host.state.friends, friend)
        settings.saveFriends(next)
        host.state.friends = next
        gossip.contactsChanged()
    }

    func addFriendByUsername(_ username: String) {
        let u = username.trimmingCharacters(in: .whitespaces)
        if !u.isEmpty { addFriend(Friend(setlistfm: u)) }
    }

    func handleFriendLink(_ url: URL) {
        if let friend = friendFromURL(url) { addFriend(friend) }
    }

    func removeFriend(_ friend: Friend) {
        // By Lane, not by username: two Contacts without an account share a blank one.
        let next = host.state.friends.filter { $0.laneKey != friend.laneKey }
        settings.saveFriends(next)
        host.state.friends = next
        gossip.contactsChanged()
    }

    /// Loads the concerts both `friend` and I attended into the setlists list, so
    /// the existing SetlistsView renders them and tapping one flows into the
    /// normal confirm → create-playlist path.
    func openSharedConcerts(_ friend: Friend) {
        let me = host.state.mySetlistFmUser.trimmingCharacters(in: .whitespaces)
        // Either of us without an account: the intersection is of what this phone already
        // holds — my Spine and their Lane — rather than of two setlist.fm lists (#405).
        if me.isEmpty || friend.setlistfm.nilIfBlank == nil {
            let theirs = Set((host.state.showsByFriend[friend.laneKey] ?? []).map(\.id))
            host.state.sharedWith = friend
            host.state.source = .user
            host.state.setlistsTitle = "You & \(friend.name)"
            host.state.setlists = []
            host.state.setlistsPage = 1
            host.state.setlistsTotal = 0
            host.state.setlistsLoading = true
            Task {
                let shared = await timelines.load().mySpine(me).filter { theirs.contains($0.id) }
                host.state.setlists = shared
                host.state.setlistsTotal = shared.count
                host.state.setlistsLoading = false
            }
            return
        }
        host.state.sharedWith = friend
        host.state.source = .user // shared list mixes artists; show "date · artist"
        host.state.setlistsTitle = "You & \(friend.name)"
        host.state.setlists = []
        host.state.setlistsPage = 1
        host.state.setlistsTotal = 0
        host.state.setlistsLoading = true
        Task {
            do {
                // The intersection and its paging cap are the logic layer's; see
                // TimelineLogic.attendedPageCap for what raising it would cost.
                let shared = try await logic.sharedConcerts(me: me, friend: friend.setlistfm)
                // total == count so loadMoreSetlists() won't try to paginate this list.
                host.state.setlists = shared
                host.state.setlistsTotal = shared.count
                host.state.setlistsLoading = false
            } catch {
                host.fail(error)
            }
        }
    }

}
