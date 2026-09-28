import Foundation

/// The other pairwise sync (#257/#265): two **Contacts**, same WiFi, whatever's sitting
/// in the shared band that the far end is still missing. Ported term-for-term from
/// Android's `data/ContactReconcile.kt` — the same reason `ContactView` is a port and
/// not a re-derivation. Two implementations that can disagree eventually will, and here
/// the direction of the disagreement is *sending someone more than they were offered*.
///
/// Not a device handover: that is a union of one person's whole timeline across their
/// own devices. This is the opposite trust model, over a manifest `contactManifest` has
/// already narrowed to exactly what a Contact may see. That narrowing is why this is so
/// small — there is no category allow-list to apply and no night to decide is off-limits,
/// because `offer` arrives pre-filtered. What's left is only "do I already have this".
struct ContactReconcilePlan: Equatable {
    /// Media ids I already hold under the same id.
    var held: [String] = []
    /// Media id → the reference to my own copy, matched by hash. No bytes cross the
    /// wire for these.
    var fromGallery: [String: String] = [:]
    /// Media ids that are already complete: a **Note** is text and a **Verdict**, and both
    /// rode the manifest. There is nothing to fetch, so asking for them would be asking
    /// for zero bytes and then dropping the note when zero bytes arrived.
    var noBytes: [String] = []
    /// Media ids to ask for.
    var request: [String] = []
    /// The **Nights** they offered that their **Lane** on this phone does not hold yet
    /// (#405) — hand-logged and imported alike, because where a Night came from stops
    /// mattering once it is theirs. Complete as they stand, like a **Note**: nothing is
    /// fetched for them. See `landNights` for where they go.
    var nights: [FmSetlist] = []

    /// By id: `FmSetlist` is not `Equatable`, and the id is what makes two records one Night.
    static func == (a: ContactReconcilePlan, b: ContactReconcilePlan) -> Bool {
        a.held == b.held && a.fromGallery == b.fromGallery && a.noBytes == b.noBytes
            && a.request == b.request && a.nights.map(\.id) == b.nights.map(\.id)
    }
}

/// Whether a media id from a peer is safe to use as an identity and, downstream, as a
/// **filename**.
///
/// A media id is a UUID this app minted at **Attach** (#97) — but an id arriving over the
/// wire is whatever the far end chose to send, and it reaches `Thumbnails.gridFile` and
/// the received-media directory as a path component. `URL.appendingPathComponent` does not
/// escape a `/`, so an id of `../../…` would write outside the directory it was meant for
/// and could overwrite an existing keepsake's thumbnail.
///
/// Checked here rather than at each of those call sites: this is the one door every
/// peer-supplied id comes through, and a check that has to be remembered three times is a
/// check that will be forgotten once. An allow-list, for the reason
/// `isPlausibleSetlistFmUser` is one — the interesting characters are the ones nobody
/// thought of.
func isSafeMediaId(_ id: String) -> Bool {
    guard !id.isEmpty, id.count <= 64 else { return false }
    return id.unicodeScalars.allSatisfy {
        CharacterSet.alphanumerics.contains($0) || $0 == "-" || $0 == "_"
    }
}

/// The LAN reconcile decision. Pure: no radio, no socket, no clock — the same split
/// Android makes, for the same reason. This is the hardest-to-get-right part of #265 and
/// the one part that needs neither a device nor a networking stack to check.
///
/// `verified` is the challenge-response outcome (a signature over a nonce, checked
/// against the Contact's persisted `Friend.publicKey`), reached by the caller and passed
/// in rather than computed here. False yields an empty plan — a peer that hasn't proven
/// who they are gets nothing, and gets it by construction rather than by remembering to
/// check upstream.
///
/// Idempotent: running it twice against the same `mine`/`offer` yields the same plan,
/// which is what lets an Exchange visit simply re-diff on every discovery rather than
/// track session state of its own.
func contactReconcilePlan(
    mine: TimelineCache,
    offer: HandoverManifest,
    verified: Bool,
    gallery: [GalleryItem] = [],
    /// The Lane I already hold for this Contact — whatever `laneKey` files it under.
    heldLane: [FmSetlist] = []
) -> ContactReconcilePlan {
    if !verified { return ContactReconcilePlan() }

    // Held by id, the one thing that says two records are one Night. A Night without an
    // id is no Night, and one offered twice is taken once.
    var heldNights = Set(heldLane.map(\.id))
    let nights = offer.nights.filter { $0.id.nilIfBlank != nil && heldNights.insert($0.id).inserted }

    let mineIds = Set(mine.gigMedia.values.flatMap { $0 }.map(\.id))
    var byHash: [String: String] = [:]
    for item in gallery where !item.hash.isEmpty && byHash[item.hash] == nil {
        byHash[item.hash] = item.ref
    }

    var plan = ContactReconcilePlan(nights: nights)
    for item in offer.media {
        if !isSafeMediaId(item.id) {
            continue
        } else if mineIds.contains(item.id) {
            plan.held.append(item.id)
        } else if item.kind == StoredMedia.Kind.note {
            plan.noBytes.append(item.id)
        } else if let ref = byHash[item.hash] {
            plan.fromGallery[item.id] = ref
        } else {
            plan.request.append(item.id)
        }
    }
    return plan
}

/// The dumb half of `contactReconcilePlan`: turns resolved items into what
/// `TimelineStore.mergeContactMedia` should write. `resolved` is media id → my own local
/// ref — the plan's `fromGallery` and `noBytes` entries as soon as the plan exists, its
/// `request` entries once their bytes have actually arrived over the wire. A **Note**'s
/// ref is the empty string, which is what a note's ref is everywhere else too.
///
/// A received item only lands on a Night I have **joined** (`joinedNights`): one I hold
/// under the same `setlistId`, the one key that means the same thing on two people's
/// timelines (#28), or one I joined by accepting an offer for it. This never mints a new
/// **Gig**. Media for a Night I have not joined is not filed here at all: it is
/// `contactOffers`'s, and waits for me to say yes (#405).
func contactLanding(
    mine: TimelineCache,
    offer: HandoverManifest,
    resolved: [String: String],
    joined: [String: String]? = nil
) -> [String: [StoredMedia]] {
    let joined = joined ?? joinedNights(mine)
    var attribution: [String: String] = [:]
    for item in offer.media { attribution[item.id] = item.from }

    var landing: [String: [StoredMedia]] = [:]
    for (theirGigId, items) in offer.timeline.gigMedia {
        guard offer.timeline.gigs[theirGigId] != nil,
              let myGigId = joined[offer.timeline.keyOf(theirGigId)]
        else { continue }
        let landed: [StoredMedia] = items.compactMap { item in
            // Checked again here rather than trusted from the plan: these items come from
            // `offer.timeline.gigMedia`, which is a different part of the peer's message
            // than `offer.media` and could disagree with it.
            guard isSafeMediaId(item.id), let ref = resolved[item.id] else { return nil }
            var copy = item
            copy.ref = ref
            copy.from = attribution[item.id] ?? item.from
            return copy
        }
        if !landed.isEmpty { landing[myGigId] = landed }
    }
    return landing
}

// MARK: - Media a Contact sends is offered, never filed (#405)
//
// `contactLanding` used to be the whole of the receive path: a Contact's media went
// straight onto whichever of my Gigs shared its setlist.fm id. That is safe only while the
// id is a catalogue key both of us derived on our own. Once a Night can be joined by hand
// it is not: a Contact's belief that we shared a Night would be written onto my record. So
// media for a Night I have not joined is held apart, as an **Offer**, and nothing on my
// timeline moves until I say. Android's `ContactReconcile.kt`, term for term.

/// Media a **Contact** sent for a Night of theirs I have not joined, held apart from my
/// timeline until I accept it or decline it (#405). Keyed in `TimelineCache.mediaOffers`
/// by *their* Night id.
///
/// `date`, `artist` and `venue` are their Night's facts, kept so the offer can be shown on
/// the Night of mine it might be and described without their Lane at hand. `declined` is
/// the media ids I said no to: they are not offered again.
struct MediaOffer: Codable, Equatable {
    var date: String = ""
    var artist: String = ""
    var venue: String = ""
    /// Waiting for an answer: `ref` is the local copy, `from` is the Contact's key.
    var media: [StoredMedia] = []
    var declined: [String] = []

    init(date: String = "", artist: String = "", venue: String = "",
         media: [StoredMedia] = [], declined: [String] = []) {
        self.date = date
        self.artist = artist
        self.venue = venue
        self.media = media
        self.declined = declined
    }

    /// Every field optional on the way in, like every other stored record: a missing field
    /// costs that field, never the whole timeline.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        date = (try? c.decodeIfPresent(String.self, forKey: .date)) ?? nil ?? ""
        artist = (try? c.decodeIfPresent(String.self, forKey: .artist)) ?? nil ?? ""
        venue = (try? c.decodeIfPresent(String.self, forKey: .venue)) ?? nil ?? ""
        media = (try? c.decodeIfPresent([StoredMedia].self, forKey: .media)) ?? nil ?? []
        declined = (try? c.decodeIfPresent([String].self, forKey: .declined)) ?? nil ?? []
    }
}

/// The Nights I have joined, as Night id → the id of my own **Gig** it is (#405).
///
/// **This is the seam hand-joins will use.** Today a Night is joined in two ways: I hold
/// it under that very catalogue id, or I accepted a Contact's offer for it, which wrote
/// `TimelineCache.nightJoins`. A later "yes, same Night" writes the same map and arrives
/// here without anything else changing. My own local ids are deliberately not in it: a
/// peer who names one has named it on purpose, which is exactly the belief that must be
/// offered rather than filed.
func joinedNights(_ mine: TimelineCache) -> [String: String] {
    var out: [String: String] = [:]
    for (night, gigId) in mine.nightJoins where night.nilIfBlank != nil && mine.gigs[gigId] != nil {
        out[night] = gigId
    }
    for gig in mine.gigs.values {
        if let setlistId = gig.setlistId?.nilIfBlank { out[setlistId] = gig.id }
    }
    return out
}

/// The other half of `contactLanding`: what a Contact sent for Nights of theirs I have not
/// joined, as their Night id → `MediaOffer`. Pure, like the plan; nothing here touches my
/// timeline.
///
/// An offer is only made for a Night on a date I was out myself (`myNights`, my
/// **Spine**): a Contact's manifest carries every item they share, so without the date
/// every photograph of every Night of theirs would arrive as a question. A Night I hold
/// under its own id is never an offer. Items are checked with `isSafeMediaId` again, and
/// an item I already hold, or already declined, is not offered.
func contactOffers(
    mine: TimelineCache,
    offer: HandoverManifest,
    resolved: [String: String],
    myNights: [FmSetlist],
    joined: [String: String]? = nil
) -> [String: MediaOffer] {
    let joined = joined ?? joinedNights(mine)
    let myIds = Set(myNights.map(\.id))
    let myDates = Set(myNights.compactMap { $0.eventDate?.nilIfBlank })
    let held = Set(mine.gigMedia.values.flatMap { $0 }.map(\.id))
    var theirNights: [String: FmSetlist] = [:]
    for night in offer.nights where theirNights[night.id] == nil { theirNights[night.id] = night }
    var attribution: [String: String] = [:]
    for item in offer.media { attribution[item.id] = item.from }

    var out: [String: MediaOffer] = [:]
    for (theirGigId, items) in offer.timeline.gigMedia {
        guard let gig = offer.timeline.gigs[theirGigId] else { continue }
        let night = offer.timeline.keyOf(theirGigId)
        if night.nilIfBlank == nil || joined[night] != nil || myIds.contains(night) { continue }
        let facts = theirNights[night]
        let date = gig.date.nilIfBlank ?? facts?.eventDate ?? ""
        guard myDates.contains(date) else { continue }
        let declined = Set(mine.mediaOffers[night]?.declined ?? [])
        let waiting: [StoredMedia] = items.compactMap { item in
            guard isSafeMediaId(item.id), !held.contains(item.id), !declined.contains(item.id),
                  let ref = resolved[item.id] else { return nil }
            var copy = item
            copy.ref = ref
            copy.from = attribution[item.id] ?? item.from
            return copy
        }
        if waiting.isEmpty { continue }
        out[night] = MediaOffer(
            date: date,
            artist: gig.artist.nilIfBlank ?? facts?.artist?.name ?? "",
            venue: gig.venue.nilIfBlank ?? facts?.venue?.name ?? "",
            media: waiting
        )
    }
    return out
}

extension TimelineCache {
    /// Offers that just arrived, kept (#405). Adds to an offer already waiting for the
    /// same Night rather than replacing it, and never brings back an item I declined.
    func holdingOffers(_ arrived: [String: MediaOffer]) -> TimelineCache {
        if arrived.isEmpty { return self }
        var c = self
        for (night, offer) in arrived {
            let had = c.mediaOffers[night]
            let declined = Set(had?.declined ?? [])
            let fresh = offer.media.filter { !declined.contains($0.id) }
            if var had {
                had.media = unionMedia(had.media, fresh)
                c.mediaOffers[night] = had
            } else {
                var kept = offer
                kept.media = fresh
                c.mediaOffers[night] = kept
            }
        }
        return c
    }

    /// Yes (#405): the offer for their Night `night` is filed onto my **Gig** `gigId`, and
    /// the Night is joined, so what they send for it later lands there directly.
    func acceptingOffer(_ night: String, gigId: String) -> TimelineCache {
        guard let offer = mediaOffers[night] else { return self }
        var c = self
        c.gigMedia[gigId] = unionMedia(c.gigMedia[gigId] ?? [], offer.media)
        c.nightJoins[night] = gigId
        c.mediaOffers[night] = nil
        return c
    }

    /// No (#405). My timeline is untouched, and the items are remembered as declined so
    /// the next **Reconcile** does not ask again.
    func decliningOffer(_ night: String) -> TimelineCache {
        guard var offer = mediaOffers[night] else { return self }
        var c = self
        var declined = offer.declined
        for item in offer.media where !declined.contains(item.id) { declined.append(item.id) }
        offer.declined = declined
        offer.media = []
        c.mediaOffers[night] = offer
        return c
    }
}

/// One offer waiting on a Night of mine: their Night id and what they sent for it.
struct WaitingOffer: Identifiable {
    let night: String
    let offer: MediaOffer
    var id: String { night }
}

/// The offers waiting on a Night of mine dated `date` (dd-MM-yyyy), in a stable order.
/// Android's `waitingOn`.
func offersWaiting(_ offers: [String: MediaOffer], on date: String?) -> [WaitingOffer] {
    guard let date = date?.nilIfBlank else { return [] }
    return offers
        .filter { $0.value.date == date && !$0.value.media.isEmpty }
        .sorted { $0.key < $1.key }
        .map { WaitingOffer(night: $0.key, offer: $0.value) }
}
