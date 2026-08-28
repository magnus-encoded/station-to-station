import Foundation

/// The rules and the sequence that drive the Timeline's Spine — ADR-0001's logic
/// layer, written the same shape here and in Android's `TimelineLogic.kt`, and
/// asserted by the same cases in `TimelineLogicTests` / `TimelineLogicTest`.
///
/// It holds no state of its own and reaches the device only through the
/// `TimelinePlumbing` handed to it, which is the whole seam: a test hands it a
/// fake and asks a question, with no device and no network. That is what the
/// four rules living here have in common — every one of them broke in the field
/// and none of them could be reached by a test before:
///
/// - a **Festival** whose real name never resolved must be retried *on load*, not
///   only after a fresh import (a reopened app kept showing the venue name);
/// - a fixture seeded at launch is the Spine for that run and the stored cache
///   must not clobber it (a CI screenshot came back empty);
/// - a playlist is named `Year – Artist – Festival-or-Venue` (this drifted
///   between the platforms and cost a commit to bring back in line);
/// - shared concerts are the intersection of two **Attended** lists, each paged
///   to a named cap.
///
/// Two of those are call-order rules, which is exactly why this layer is allowed
/// to call plumbing rather than being required to be pure: no pure function can
/// express "don't read the store when a fixture was seeded".

// MARK: - What a source hands over

/// A Spine as one source hands it over: my own **Line**, every **Lane** beside
/// it, and the **Festival** names resolved so far.
///
/// The stored cache and a bundled weave fixture produce the same thing — the
/// fixture additionally knows *whose* line is mine and in what **Lane** order the
/// friends sit, which the store has no opinion about (it is keyed by username and
/// nothing more). Those two fields are empty coming off disk.
struct LoadedSpine {
    /// The setlist.fm username whose **Line** is the Spine.
    var me: String = ""
    /// The **Lanes**, nearest the Spine first. Empty from the store, which does
    /// not record an order.
    var friends: [Friend] = []
    /// My **Attended** shows: the Spine itself.
    var mine: [FmSetlist] = []
    /// Every other **Line**, by setlist.fm username.
    var byFriend: [String: [FmSetlist]] = [:]
    /// Every **Festival** identity known so far, and which **Gigs** carry one (#166).
    var festivals: Festivals = Festivals()
}

// MARK: - The device half

/// Everything the logic layer needs from the device, and nothing more.
///
/// Implemented for real by `DeviceTimelinePlumbing` below (an `actor` store, the
/// IPv4-forced client, the app bundle — idiomatic iOS, and deliberately unlike
/// Android's), and by a fake in the tests. If a fake ever becomes laborious to
/// write, this interface is wrong.
protocol TimelinePlumbing {
    /// The Spine seeded at launch from a bundled weave fixture, when one was;
    /// nil in a normal run.
    ///
    /// This and `storedSpine` are two sources of the same thing, which is the
    /// point: seeding used to be a guard clause inside the loader plus an
    /// instance flag. Which source is in play is now the logic layer's own
    /// knowledge, decided in one place — `loadSpine`.
    func seededSpine() async -> LoadedSpine?

    /// The Spine as last written to disk, or nil when nothing has been.
    func storedSpine(me: String) async -> LoadedSpine?

    /// One page of a user's **Attended** list, with the total setlist.fm reports.
    func attendedPage(_ user: String, page: Int) async throws -> (shows: [FmSetlist], total: Int)

    /// The **Festival** behind a setlist page, or nil when that night belongs to none
    /// — which is the common and correct answer, and is what stops it being asked
    /// again.
    func festivalAt(setlistURL: String) async -> ScrapedFestival?

    /// Persists resolved **Festival** identities, their membership, and which **Gigs**
    /// have now been asked about. Merge semantics belong to the store, which already
    /// has them and is already the cross-platform contract.
    func saveFestivals(
        _ festivals: [String: StoredFestival],
        idByShow: [String: String],
        asked: Set<String>
    ) async
}

/// What was scraped, as an identity this app owns.
///
/// **The identity is ours; the vendors' are attributes.** The id is minted from
/// setlist.fm's slug so that two devices — and the same device twice — agree on it
/// without asking anyone, but it is *ours*: the slug sits beside it as enrichment, the
/// way a **Gig** already carries its setlist.fm id since #107, and storage never moves
/// because a vendor's key changed. A **Bill** you typed has no vendor key at all, which
/// is exactly why a vendor key cannot be the identity.
///
/// Nil when the page gave neither a name nor a slug: an identity with nothing to
/// identify it is not one.
private func storedFestival(from scraped: ScrapedFestival) -> StoredFestival? {
    guard let name = scraped.name?.nilIfBlank else { return nil }
    let id = festivalIdForSlug(scraped.slug ?? "name:\(name.lowercased())")
    return StoredFestival(
        id: id,
        name: name,
        rangeFrom: scraped.rangeFrom,
        rangeTo: scraped.rangeTo,
        setlistFmSlug: scraped.slug,
        source: StoredFestival.FestivalSource.scraped,
        dayMembership: scraped.dayMembership,
        setTimes: scraped.setTimes
    )
}

// MARK: - The rules

struct TimelineLogic {

    let plumbing: TimelinePlumbing

    /// How many pages of someone's **Attended** list a shared-concerts lookup
    /// will pull — 20 per page, so 60 concerts each side.
    ///
    /// ponytail: a named runaway guard, not a policy. Raising it is an informed
    /// decision about call volume against how far back two people's overlap
    /// reaches; burying it in a loop meant nobody could make that decision at all.
    static let attendedPageCap = 3

    // MARK: The sequence

    /// The Spine for this run, handed over as soon as it exists and again if
    /// retrying the unresolved **Festival** names finds any.
    ///
    /// Two emissions on purpose. A cached Spine has to be on screen before any
    /// network is — that is the whole reason it is cached — so the names cannot
    /// be awaited before the first one. Expressing it as a sequence here rather
    /// than as two methods and a flag in the app model is the point of the layer:
    /// the order is readable in one place, and a test can assert it.
    ///
    /// The seeded fixture wins outright and the store is never even read: it is
    /// the Spine for that run, and in CI the stored cache is empty, so reading it
    /// is precisely how a screenshot came back blank.
    func loadSpine(me: String, onSpine: (LoadedSpine) -> Void) async {
        if let seeded = await plumbing.seededSpine() {
            onSpine(seeded)
            return
        }
        guard var spine = await plumbing.storedSpine(me: me) else { return }
        onSpine(spine)

        // A cached Spine may hold evenings whose Festival identity was never resolved
        // — the import failed the scrape, or predates it. Resolving only after a
        // fresh import is what left a reopened app showing venue names.
        let found = await resolveFestivals(mine: spine.mine, known: spine.festivals)
        if found == spine.festivals { return }
        spine.festivals = found
        onSpine(spine)
    }

    /// Asks setlist.fm which **Festival**, if any, the unidentified evenings on `mine`
    /// belong to, and folds the answers into `known`.
    ///
    /// **A Section is the candidate, not a run of nights.** Several acts at one venue
    /// on one date is the shape a festival day has; it is also the shape a headline
    /// show with support has, and the *only* way to tell them apart is to ask a source
    /// that knows. So this asks, and takes no for an answer: a night whose page carries
    /// no festival link is recorded as asked and never asked again, which is what keeps
    /// 44 multi-act nights from costing 44 fetches every launch.
    ///
    /// Nothing here infers. If the page says nothing, the evening stays a **Section**
    /// for good, and that is the record saying the smaller true thing.
    @discardableResult
    func resolveFestivals(mine: [FmSetlist], known: Festivals) async -> Festivals {
        let candidates = groupIntoFestivals(mine, known).filter { node in
            guard case .section(let shows) = node else { return false }
            return !shows.contains { known.asked.contains($0.id) }
        }
        if candidates.isEmpty { return known }

        var identities: [String: StoredFestival] = [:]
        var idByShow: [String: String] = [:]
        var asked: Set<String> = []
        for node in candidates {
            // **A festival answers for every one of its days at once.** The candidates
            // were decided before the first fetch, so a three-day festival arrives here
            // as three Sections — but the first one's `dayMembership` already claimed
            // the other two, and asking again would buy the same page twice more.
            if node.shows.contains(where: { idByShow[$0.id] != nil }) { continue }
            guard let show = node.shows.first(where: { $0.url?.nilIfBlank != nil }),
                  let url = show.url
            else { continue }
            // Only a reply counts as having asked. A fetch that failed in a tunnel is a
            // question still open, and must be asked again on the next launch.
            let page = await plumbing.festivalAt(setlistURL: url)
            // **The evening is asked about, not the Gig.** Every setlist of a festival
            // carries the same link on its own page, so one page answers for the whole
            // night — and asking act by act would pay per act for one answer.
            asked.formUnion(node.shows.map(\.id))
            guard let page, let identity = storedFestival(from: page) else { continue }
            identities[identity.id] = identity
            // The nights the *source* says are the festival's, plus this evening, which
            // we know is: it is the night whose page carried the link.
            for show in node.shows { idByShow[show.id] = identity.id }
            for ids in (identity.dayMembership ?? [:]).values {
                for id in ids { idByShow[id] = identity.id }
            }
        }
        if asked.isEmpty { return known }
        await plumbing.saveFestivals(identities, idByShow: idByShow, asked: asked)
        return known + Festivals(byId: identities, idByShow: idByShow, asked: asked)
    }

    /// The nights `friend` and I were both at: the intersection of two **Attended**
    /// lists, each paged to `attendedPageCap`.
    ///
    /// An intersection and not a merge — **Attended** is the only thing that makes
    /// a **Gig** someone's, so a night is shared exactly when it is on both lists.
    /// Mine keeps its order, so the result is newest first like every other list.
    func sharedConcerts(me: String, friend: String) async throws -> [FmSetlist] {
        let mine = try await attended(me)
        let theirs = Set(try await attended(friend).map(\.id))
        return mine.filter { theirs.contains($0.id) }
    }

    /// One user's **Attended** list, up to `attendedPageCap` pages. Stops early
    /// once setlist.fm's reported total is in hand, or a page comes back empty.
    private func attended(_ user: String) async throws -> [FmSetlist] {
        var all: [FmSetlist] = []
        for page in 1...TimelineLogic.attendedPageCap {
            let (shows, total) = try await plumbing.attendedPage(user, page: page)
            all += shows
            if all.count >= total || shows.isEmpty { break }
        }
        return all
    }

    // MARK: The values

    /// What a playlist made from `setlist` is called: `Year – Artist – Where`.
    ///
    /// Year first, so an alphabetical playlist library falls into chronological
    /// order and the night reads as "when, who, where". A **Festival** cluster's
    /// "where" is the Festival name standing in for the venue — a stage is not a
    /// place — with the year stripped back out of it ("Tons of Rock 2026" →
    /// "Tons of Rock"), since the year already leads. A lone show keeps its venue.
    ///
    /// Pure, and static so a test needs no plumbing at all to ask. This is the
    /// rule that shipped wrong output from correct sequencing on iOS and had to be
    /// brought back in line with Android by hand; it is asserted identically on
    /// both platforms now.
    static func playlistName(
        for setlist: FmSetlist,
        mine: [FmSetlist],
        festivals: Festivals
    ) -> String {
        let artistName = setlist.artist?.name ?? ""
        let year = setlist.year()
        let festival = groupIntoFestivals(mine, festivals).first { node in
            guard case .festival = node else { return false }
            return node.shows.contains { $0.id == setlist.id }
        }
        let whereName: String?
        // The identity, not the label: an evening nobody has named is a **Section**,
        // and its own acts are already the artist half of this name. Falls through to
        // the venue, exactly as a lone show does.
        if case .festival(let identity, _)? = festival {
            let fname = identity.name
            whereName = year.map {
                fname.replacingOccurrences(of: $0, with: "")
                    .trimmingCharacters(in: CharacterSet(charactersIn: " -–"))
            } ?? fname
        } else {
            whereName = setlist.venue?.name
        }
        return [year, artistName.nilIfBlank, whereName?.nilIfBlank]
            .compactMap { $0 }
            .joined(separator: " – ")
            .nilIfBlank ?? "Setlist"
    }
}

// MARK: - The device half, for real

/// The iOS plumbing: the `actor` store, the IPv4-forced setlist.fm client, and
/// `Bundle.main` for a seeded fixture. Stateful and unlovely because the OS makes
/// it so, and not expected to resemble Android's — ADR-0001 draws the parity line
/// above here, not through here.
final class DeviceTimelinePlumbing: TimelinePlumbing {

    private let store: TimelineStore
    private let client: SetlistFmClient

    /// The fixture seeded for this run, if `seed(fixture:)` was called. Holding it
    /// here rather than as a Bool on the app model is what let the "don't read the
    /// store" rule move up into the logic layer.
    private var seeded: LoadedSpine?

    init(store: TimelineStore, client: SetlistFmClient) {
        self.store = store
        self.client = client
    }

    /// Reads a bundled weave fixture (`fixtures/weave/<name>/timelines.json`) and
    /// makes it the Spine for this run. Synchronous on purpose: the seed happens
    /// in `AppModel.init` and the view's `onAppear` load can follow immediately,
    /// so registering it must not be able to lose that race.
    @discardableResult
    func seed(fixture name: String) -> LoadedSpine? {
        guard let base = Bundle.main.url(forResource: "weave", withExtension: nil),
              let data = try? Data(contentsOf: base.appendingPathComponent("\(name)/timelines.json")),
              let doc = try? JSONDecoder().decode(FixtureDoc.self, from: data)
        else { return nil }
        let spine = LoadedSpine(
            me: doc.me,
            friends: doc.friends ?? [],
            mine: doc.shows[doc.me] ?? [],
            byFriend: doc.shows.filter { $0.key != doc.me },
            festivals: Festivals(
                byId: doc.festivals ?? [:],
                idByShow: doc.festivalIdByShow ?? [:]
            )
        )
        seeded = spine
        return spine
    }

    func seededSpine() async -> LoadedSpine? { seeded }

    func storedSpine(me: String) async -> LoadedSpine? {
        let cache = await store.load()
        // Nothing written yet is nil, not an empty Spine: a first run must leave
        // whatever is already on screen alone rather than blanking it.
        if cache.shows.isEmpty && cache.festivals.isEmpty { return nil }
        return LoadedSpine(
            me: me,
            // Not `shows[me]` alone: a night I checked into that setlist.fm has never
            // heard of is on no attended list, and leaves the future lane the moment it
            // stops being a plan. See `spineNights`.
            mine: spineNights(attended: cache.shows[me] ?? [],
                              planned: cache.planned(),
                              attendance: cache.attendance()),
            // Not `shows - me`. A Contact whose Card carries my own setlist.fm username
            // is my other device, and subtracting my key left that lane empty: every
            // night rendered as mine-only instead of Joined. Only friends are ever read
            // out of this map, so carrying my own key costs nothing. Ported with Android.
            byFriend: cache.shows,
            festivals: cache.festivalIdentities()
        )
    }

    func attendedPage(_ user: String, page: Int) async throws -> (shows: [FmSetlist], total: Int) {
        let resp = try await client.userAttended(user, page: page)
        return (resp.setlist, resp.total)
    }

    func festivalAt(setlistURL: String) async -> ScrapedFestival? {
        await client.festivalAt(setlistURL: setlistURL)
    }

    func saveFestivals(
        _ festivals: [String: StoredFestival],
        idByShow: [String: String],
        asked: Set<String>
    ) async {
        await store.save(festivals: festivals, festivalIdByShow: idByShow, festivalsAsked: asked)
    }
}

/// A weave fixture as stored on disk (`fixtures/weave/<name>/timelines.json`): a
/// plain TimelineCache plus the two keys the store itself ignores — which line is
/// mine, and the friends in **Lane** order (nearest the Spine first).
private struct FixtureDoc: Decodable {
    var me: String
    var friends: [Friend]?
    var shows: [String: [FmSetlist]]
    /// A **Festival** is an identity, never a shape (#166): the identities the app
    /// knows, and which **Gigs** carry one. Nothing else gathers nights together.
    var festivals: [String: StoredFestival]?
    var festivalIdByShow: [String: String]?
}
