import Foundation

/// Two devices, one **Line**: what should happen when the same person's other phone offers
/// its timeline (#142). Ported function for function from Android's `data/Handover.kt`,
/// for the reason `ContactView` and `ContactReconcile` are ports — two implementations of
/// "combine two timelines" that can disagree eventually will, and here the direction of
/// the disagreement is *silently discarding somebody's only copy of a night*.
///
/// Not a **Contact** reconcile (`ContactReconcile.swift`). That is the pairwise sync
/// between two people over the shared band; this is a union of everything between two
/// devices belonging to one person, and the far end is not a stranger.
///
/// Pure: no radio, no socket, no file, no clock. It says what should happen; applying it
/// is a separate, dumb step (`TimelineStore.applyHandover`). That split is the point —
/// every rule below is assertable on a laptop.

/// What should happen. Data, not effects.
/// Not `Equatable`, unlike `ContactReconcilePlan`: `merged` is a whole `TimelineCache`,
/// which is not — and a plan is compared field by field anyway, because "the same union"
/// is not a question anything asks.
struct HandoverPlan {
    /// My timeline unioned with theirs, with everything already resolvable attached.
    /// Items still in `request` are deliberately *not* in it: their bytes have not
    /// arrived, and a **Media** record pointing at nothing is the dead reference #97
    /// exists to prevent. They join as they land.
    var merged: TimelineCache = TimelineCache()
    /// Media id → a reference that already resolves here, so no bytes need to cross the
    /// wire for it: my own gallery's copy matched by hash, the empty ref a **Note** has
    /// everywhere, or — on the second pass — where this session's bytes just landed.
    var fromGallery: [String: String] = [:]
    /// Media ids to ask for.
    var request: [String] = []
    /// Media ids I already hold under the same id.
    var held: [String] = []
    /// Media ids whose category the source did not allow, or whose night is not here.
    /// Kept apart from `refused` because they mean different things: the source did not
    /// offer this, versus I will not take it.
    var withheld: [String] = []
    /// Media ids blocked by hash. The bytes never transfer.
    var refused: [String] = []
    /// Per category, how many items should end up here when this is applied.
    var expected: [String: Int] = [:]
    /// The manifest's own counts disagree with the items it lists.
    var countMismatch: Bool = false
    /// Their **Gigs** with no `setlistId` and no id of mine to land on. These duplicate,
    /// because a night added by hand has a random per-device id. Surfaced, not solved.
    var unkeyed: [String] = []
}

/// The handover decision.
///
/// `verified` is the verdict on the manifest's seal, reached by the transport
/// (`openManifest`) and passed in rather than computed here, so this function stays free
/// of crypto and of anything that can fail. False yields an empty plan: a manifest that
/// does not verify writes *nothing*, because the highest-stakes bit in the payload is
/// `OfferedMedia.personal` — flipping it exposes something marked **Personal** with
/// nothing in the UI to say so — and the second is which night an item attaches to.
///
/// The operation is a **union**, never a copy of the larger side. Neither device is a
/// superset: the old phone has the history, the new one may hold the only copy of last
/// night. Picking a winner by volume is a silent discard.
///
/// Nothing is removed from the source. Publishing is not moving, so the old phone stays a
/// complete second copy for free. (Accounts are the one exception, and they are #143.)
///
/// `received` is media id → the local ref its bytes have *already* landed at, for a second
/// pass over the same offer once the transfer has run. The plan is computed twice on
/// purpose rather than mutated: once before the transfer, where it says what to ask for,
/// and once after, where what arrived is no longer a request but a resolvable local ref —
/// so a cancelled transfer's smaller `received` simply yields a smaller union.
func handoverPlan(
    mine: TimelineCache,
    offer: HandoverManifest,
    allow: Set<String>,
    verified: Bool,
    refusedHashes: Set<String> = [],
    gallery: [GalleryItem] = [],
    received: [String: String] = [:]
) -> HandoverPlan {
    if !verified { return HandoverPlan() }

    let facts = allow.contains(categorySetlists)
    let (gigs, rename) = absorbingGigs(mine: mine, theirs: offer.timeline, facts: facts)

    let mineIds = Set(mine.gigMedia.values.flatMap { $0 }.map(\.id))
    // Empty hashes excluded: a **Note** hashes to nothing and so does anything the hasher
    // could not read, and without this every one of them matches whichever unhashable
    // thing the gallery listed first — landing a note wearing a photograph's ref. The same
    // rule, for the same reason, as `contactReconcilePlan`'s.
    var byHash: [String: String] = [:]
    for item in gallery where !item.hash.isEmpty && byHash[item.hash] == nil {
        byHash[item.hash] = item.ref
    }

    var plan = HandoverPlan()
    for item in offer.media {
        if !isSafeMediaId(item.id) {
            continue
        } else if !item.hash.isEmpty && refusedHashes.contains(item.hash) {
            plan.refused.append(item.id)
        } else if !allow.contains(item.category) {
            plan.withheld.append(item.id)
        } else if rename[item.gigId] == nil {
            // A photo whose night did not come across has nowhere to hang. One rule, and
            // it covers both a source that offered media without setlists and a manifest
            // naming a gig it did not send.
            plan.withheld.append(item.id)
        } else if mineIds.contains(item.id) {
            plan.held.append(item.id)
        } else if let landed = received[item.id] {
            // Already arrived, this session. Resolvable locally now, whatever it was.
            plan.fromGallery[item.id] = landed
        } else if item.kind == StoredMedia.Kind.note {
            // A **Note** is complete the moment the manifest is: text and a **Verdict**,
            // no bytes to fetch. Asking for it would be asking for zero bytes and then
            // dropping the note when zero bytes arrived.
            plan.fromGallery[item.id] = ""
        } else if let ref = byHash[item.hash] {
            plan.fromGallery[item.id] = ref
        } else {
            plan.request.append(item.id)
        }
    }

    // Attribution comes off the manifest, not off the record: the sender's own copy says
    // `from == nil` because it is theirs, and it is *mine* that has to remember it was
    // not. My media and received media stay distinguishable at every layer.
    var attribution: [String: String] = [:]
    for item in offer.media { attribution[item.id] = item.from }

    var landing: [String: [StoredMedia]] = [:]
    for (gigId, items) in offer.timeline.gigMedia {
        let landed: [StoredMedia] = items.compactMap { item in
            guard isSafeMediaId(item.id), let ref = plan.fromGallery[item.id] else { return nil }
            var copy = item
            copy.ref = ref
            copy.from = attribution[item.id] ?? item.from
            return copy
        }
        if !landed.isEmpty { landing[gigId] = landed }
    }

    let resolvable = Set(plan.fromGallery.keys)
        .union(plan.request)
        .union(plan.held)
    for item in offer.media where resolvable.contains(item.id) {
        plan.expected[item.category, default: 0] += 1
    }

    var counted: [String: Int] = [:]
    for item in offer.media { counted[item.category, default: 0] += 1 }
    plan.countMismatch = !offer.counts.isEmpty && offer.counts != counted

    plan.merged = absorbing(mine: mine, theirs: offer.timeline, gigs: gigs,
                            rename: rename, media: landing, facts: facts)
    plan.unkeyed = offer.timeline.gigs.values
        .filter { $0.setlistId == nil && mine.gigs[$0.id] == nil }
        .map(\.id)
        .sorted()
    return plan
}

/// What this device offers its own other phone: the timeline, and every media item whose
/// category the source ticked.
///
/// The mirror of `contactManifest` and deliberately not the same function. That one
/// narrows to the shared band and rewrites attribution; this one offers **Personal**
/// alongside everything else when the box is ticked, and leaves `StoredMedia.from` exactly
/// as it stands — a **Contact**'s photograph stays theirs on the new phone too.
///
/// **The tick list is applied here, at construction**, for the same reason the Personal
/// categories are absent from a Contact's manifest rather than unticked: an item the
/// source did not offer never reaches the wire at all, so no receiver-side filter can
/// forget to apply. `handoverPlan`'s own `allow` is then the receiver restating what
/// arrived, not a second chance to keep something out.
///
/// `hash` and `bytes` are left at their defaults: reading files is the device layer's
/// business (`AppModel`), not this function's.
func deviceManifest(_ cache: TimelineCache, allow: Set<String>,
                    identities: Identities = Identities()) -> HandoverManifest {
    let media = cache.gigMedia
        .mapValues { $0.filter { allow.contains(categoryOf(kind: $0.kind, personal: $0.personal)) } }
        .filter { !$0.value.isEmpty }

    var timeline: TimelineCache
    if allow.contains(categorySetlists) {
        timeline = cache
        timeline.gigMedia = media
    } else {
        // Facts unticked means media only: the nights themselves are still named, because
        // an item whose gig the receiver cannot find has nowhere to hang, but nothing else
        // of the timeline — no **Log**, no attendance, no playlists — travels.
        timeline = TimelineCache()
        timeline.gigMedia = media
        timeline.gigs = cache.gigs.filter { media[$0.key] != nil }
    }

    // Sorted by gig id only so the same timeline always produces the same manifest — a
    // Swift dictionary has no order of its own, and a wire format that reshuffles per run
    // is one nobody can assert against.
    let offered = media.keys.sorted().flatMap { gigId in
        media[gigId, default: []].map {
            OfferedMedia(id: $0.id, gigId: gigId, kind: $0.kind, capturedAt: $0.capturedAt,
                         personal: $0.personal, from: $0.from, text: $0.text, verdict: $0.verdict)
        }
    }
    return HandoverManifest(timeline: timeline, media: offered, identities: identities)
}

// MARK: - The union itself

/// Their **Gigs** landed on mine, and the id translation that lands everything else.
///
/// Two records of one night collapse and **the older id wins**, by `createdAt` and then by
/// the id itself — so two devices reach the same answer with no synchronised clock, exactly
/// as `TimelineStore.mergeGigs` merges a pair within one device. The rename table covers
/// *both* directions, because the survivor can be theirs: when it is, my own maps have to
/// move onto their id too.
private func absorbingGigs(mine: TimelineCache, theirs: TimelineCache,
                           facts: Bool) -> ([String: StoredGig], [String: String]) {
    var out = mine.gigs
    var rename: [String: String] = [:]
    if !facts {
        // Media only: their items may still land, but only on nights I already have.
        for id in theirs.gigs.keys where out[id] != nil { rename[id] = id }
        return (out, rename)
    }
    // Sorted, so two of their gigs claiming one `setlistId` resolve the same way on every
    // run rather than by dictionary order.
    for theirId in theirs.gigs.keys.sorted() {
        let theirGig = theirs.gigs[theirId]!
        let ours = theirGig.setlistId.flatMap { sid in
            out.values.sorted { $0.id < $1.id }.first { $0.setlistId == sid }
        } ?? out[theirId]
        guard let ours else {
            out[theirId] = theirGig
            rename[theirId] = theirId
            continue
        }
        let oursIsOlder = ours.createdAt != theirGig.createdAt
            ? ours.createdAt < theirGig.createdAt
            : ours.id <= theirGig.id
        var kept = oursIsOlder ? ours : theirGig
        let gone = oursIsOlder ? theirGig : ours
        kept.setlistId = kept.setlistId ?? gone.setlistId
        if kept.date.isEmpty { kept.date = gone.date }
        if kept.artist.isEmpty { kept.artist = gone.artist }
        if kept.venue.isEmpty { kept.venue = gone.venue }
        out[ours.id] = nil
        out[theirId] = nil
        out[kept.id] = kept
        rename[theirId] = kept.id
        if ours.id != kept.id { rename[ours.id] = kept.id }
    }
    return (out, rename)
}

/// The union, map by map, through the same `unionMedia` a merge within one device uses.
/// Reusing it is not tidiness: two implementations of "combine two nights" is how one of
/// them ends up quietly dropping a map the other unions — a **Log** someone typed at a gig,
/// gone with no error and no trace.
private func absorbing(mine: TimelineCache, theirs: TimelineCache,
                       gigs: [String: StoredGig], rename: [String: String],
                       media: [String: [StoredMedia]], facts: Bool) -> TimelineCache {
    /// Mine first, so it is the `kept` side of every union — the same position the survivor
    /// takes when one device merges a pair of its own. Anything of theirs whose night did
    /// not land (`rename` has no entry) is dropped: it has nowhere to go.
    func join<V>(_ theirMap: [String: V], _ mineMap: [String: V],
                 _ union: (V, V) -> V) -> [String: V] {
        var out: [String: V] = [:]
        for (k, v) in mineMap {
            let key = rename[k] ?? k
            out[key] = out[key].map { union($0, v) } ?? v
        }
        for (k, v) in theirMap {
            guard let key = rename[k] else { continue }
            out[key] = out[key].map { union($0, v) } ?? v
        }
        return out
    }

    var merged = mine
    merged.gigs = gigs
    merged.gigMedia = join(media, mine.gigMedia, unionMedia)
    if !facts { return merged }

    merged.gigAttendance = join(theirs.gigAttendance, mine.gigAttendance, unionAttendance)
    merged.gigLogs = join(theirs.gigLogs, mine.gigLogs, unionLog)
    merged.gigPlaylists = join(theirs.gigPlaylists, mine.gigPlaylists, unionPlaylists)
    // One current value per night, and mine is the one I am standing in front of.
    merged.gigPlanned = join(theirs.gigPlanned, mine.gigPlanned) { k, _ in k }
    merged.gigCalendarEvent = join(theirs.gigCalendarEvent, mine.gigCalendarEvent) { k, _ in k }
    merged.gigSongOffsets = join(theirs.gigSongOffsets, mine.gigSongOffsets) { k, _ in k }
    merged.shows = Dictionary(uniqueKeysWithValues:
        Set(mine.shows.keys).union(theirs.shows.keys).map { user in
            let kept = mine.shows[user] ?? []
            return (user, kept + (theirs.shows[user] ?? []).filter { s in !kept.contains { $0.id == s.id } })
        })
    merged.festivalNames = theirs.festivalNames.merging(mine.festivalNames) { _, keep in keep }
    // Identities union. This device wins a collision, as everything else here does —
    // except over an identity the other one *authored*, which no scrape of mine may
    // overwrite. That is `mergedWith`'s rule, unchanged by the transport (#166).
    merged.festivals = mergedWith(theirs.festivals, mine.festivals)
    merged.festivalIdByShow = theirs.festivalIdByShow.merging(mine.festivalIdByShow) { _, keep in keep }
    merged.festivalsAsked = theirs.festivalsAsked.union(mine.festivalsAsked)
    merged.attendedTotals = Dictionary(uniqueKeysWithValues:
        Set(mine.attendedTotals.keys).union(theirs.attendedTotals.keys).map { user in
            (user, max(mine.attendedTotals[user] ?? 0, theirs.attendedTotals[user] ?? 0))
        })
    return merged
}

/// Every playlist link from both: a url is the thing you send someone, so none go.
func unionPlaylists(_ kept: [StoredPlaylist], _ arriving: [StoredPlaylist]) -> [StoredPlaylist] {
    kept + arriving.filter { p in !kept.contains { $0.url == p.url } }
}

/// The longer **Log** survives, and stays **Open** unless both were **Closed** — a merge
/// must not upgrade a claim nobody made.
func unionLog(_ kept: StoredLog, _ arriving: StoredLog) -> StoredLog {
    var longer = arriving.songs.count > kept.songs.count ? arriving : kept
    longer.closed = kept.closed && arriving.closed
    return longer
}

/// One claim about one night, and the stronger evidence wins: a check-in reached by one
/// route must not be flattened back to `planned` by the other. An unrecognised provenance
/// ranks lowest rather than throwing — the field is a plain string precisely so a newer
/// app's value costs this one gig, not the cache.
func unionAttendance(_ kept: StoredAttendance, _ arriving: StoredAttendance) -> StoredAttendance {
    evidence(arriving.provenance) > evidence(kept.provenance) ? arriving : kept
}

private func evidence(_ provenance: String) -> Int {
    switch provenance {
    case "checked_in": return 2
    case "attended": return 1
    default: return 0
    }
}
