import Foundation

// The two bands a night's Media is drawn in, and what letting go would do to
// them (#171, porting Android's data/MediaBands.kt from #162).
//
// Position is the bit. A Gig's media is one shared band and one vault band, and
// which band an item sits in *is* StoredMedia.personal. There is no badge and
// nothing to open: the whole night's disposition reads at a glance, and the
// gesture that changes it is the gesture that moves it. Colour never carries the
// bit — Amber means mine in *both* bands, because mine and held-back are
// different facts and one signal must not carry two.
//
// This is a straight port of the pure logic: no toBands migration here, because
// iOS never had a night-level grant to migrate away from — every StoredMedia on
// this platform already defaults `personal = false`, which is already the
// correct shared-band answer under this model.

/// Which band, and therefore which side of the tier line.
enum Band {
    case shared
    case vault
}

/// What letting go right now would do to the shared band's Crossing.
enum ReleaseHint {
    /// Nothing changes, or the change is invisible. The usual answer.
    case none
    /// One more contributor arrives and the night becomes one I shared.
    case gained
    /// The last of mine leaves and the night stops being one I shared.
    case lost
}

/// One night's media, split.
///
/// `received` is always in the shared band — it arrived through someone's
/// deliberate act and I hold no disposition over it — and always to the right
/// of `shared`.
struct MediaBandSplit {
    var shared: [StoredMedia] = []
    var received: [StoredMedia] = []
    var vault: [StoredMedia] = []

    /// How many people's media the shared band holds: me, if any of mine is in
    /// it, plus each distinct sender.
    ///
    /// One sender and nothing of mine is **one** contributor, so the band stays
    /// ungreen — green has to keep meaning *more than one of us* or it means
    /// nothing.
    var contributors: Int { (shared.isEmpty ? 0 : 1) + senders() }

    /// Whether the shared band is a Crossing: more than one of us in it.
    var crossed: Bool { contributors > 1 }

    func senders() -> Int { Set(received.compactMap { $0.from }).count }
}

/// Split one night's media into its bands.
///
/// My own items keep their stored order; that order is the arrangement, and
/// nothing here may reshuffle it. Received media is sorted by capture time
/// instead, so the arrivals still read as the night they came from — and
/// because Reconcile has no time bound, a Contact made years from now drops
/// media into an old night. Sorting only their run is what keeps my half
/// stable: new arrivals can never move one of my photographs.
func bandsOf(_ media: [StoredMedia]) -> MediaBandSplit {
    let mine = media.filter { $0.from == nil }
    let received = media.filter { $0.from != nil }
        .sorted { a, b in
            switch (a.capturedAt, b.capturedAt) {
            case let (x?, y?): return x < y
            case (nil, nil): return false
            case (nil, _): return false // nils last
            case (_, nil): return true
            }
        }
    return MediaBandSplit(
        shared: mine.filter { !$0.personal },
        received: received,
        vault: mine.filter { $0.personal }
    )
}

/// What the shared band would say if you let go now, given how many of my own
/// photographs it would then hold.
///
/// Both gestures come through here. The hint is only ever shown when releasing
/// genuinely changes the fact — a band already holding two contributors has
/// nothing to promise, and one that would still hold two has nothing to warn
/// about.
func releaseHint(_ media: [StoredMedia], mineSharedAfter: Int) -> ReleaseHint {
    let bands = bandsOf(media)
    let after = (mineSharedAfter > 0 ? 1 : 0) + bands.senders()
    let crossedNow = bands.contributors > 1
    let crossedAfter = after > 1
    switch (crossedNow, crossedAfter) {
    case (false, true): return .gained
    case (true, false): return .lost
    default: return .none
    }
}

/// The hint for a photograph arriving from the handle into `to`.
func hintForAdding(_ media: [StoredMedia], to: Band) -> ReleaseHint {
    let shared = bandsOf(media).shared.count
    return releaseHint(media, mineSharedAfter: shared + (to == .shared ? 1 : 0))
}

/// The hint for an item already on the night being dragged into `to`.
///
/// Received media answers `.none` because it cannot move: its disposition is
/// not mine to set, and offering one would be offering to publish on someone
/// else's behalf.
func hintForMoving(_ media: [StoredMedia], id: String, to: Band) -> ReleaseHint {
    guard let item = media.first(where: { $0.id == id }) else { return .none }
    if item.from != nil { return .none }
    let shared = bandsOf(media).shared.count
    let leaving = item.personal ? 0 : 1
    let arriving = to == .shared ? 1 : 0
    return releaseHint(media, mineSharedAfter: shared - leaving + arriving)
}

/// Move one of my items to `index` within `to`, returning the night's new
/// media.
///
/// The result is normalised — my shared run, then Received media, then my
/// vault run — which is what keeps my own items to the left of anyone else's
/// without a separate rule to enforce it. A move between bands flips
/// `StoredMedia.personal`; a move within one leaves every bit untouched, so
/// arranging keepsakes is never accidentally a privacy act.
///
/// A Received item, or an id that is not on this night, returns the list
/// unchanged rather than trapping: this is driven by a finger.
func moveMedia(_ media: [StoredMedia], id: String, to: Band, index: Int) -> [StoredMedia] {
    guard let item = media.first(where: { $0.id == id }) else { return media }
    if item.from != nil { return media }
    let bands = bandsOf(media)
    var shared = bands.shared.filter { $0.id != id }
    var vault = bands.vault.filter { $0.id != id }
    var moved = item
    moved.personal = (to == .vault)
    let clampedIndex: Int
    if to == .shared {
        clampedIndex = min(max(index, 0), shared.count)
        shared.insert(moved, at: clampedIndex)
    } else {
        clampedIndex = min(max(index, 0), vault.count)
        vault.insert(moved, at: clampedIndex)
    }
    return shared + bands.received + vault
}
