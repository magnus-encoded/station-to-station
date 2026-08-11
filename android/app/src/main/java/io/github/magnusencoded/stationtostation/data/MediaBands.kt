package io.github.magnusencoded.stationtostation.data

/**
 * The two bands a night's **Media** is drawn in, and what letting go would do to
 * them (#162).
 *
 * **Position is the bit.** A **Gig**'s media is one shared band and one vault band,
 * and which band an item sits in *is* [StoredMedia.personal]. There is no badge and
 * nothing to open: the whole night's disposition reads at a glance, and the gesture
 * that changes it is the gesture that moves it. Colour never carries the bit —
 * **Amber** means mine in *both* bands, because mine and held-back are different
 * facts and one signal must not carry two.
 *
 * **One question, asked by both gestures.** Adding a photograph from the handle and
 * dragging one up out of the vault are the same act differently sourced, so
 * [releaseHint] takes the only thing that varies — how many of my own photographs
 * the shared band would then hold — and derives the answer. That is why the warning
 * for the opposite direction exists at all: nothing special-cases it.
 *
 * **The night-level grant is gone.** There was a second boundary here — a set of
 * shared nights, on top of each item's own bit — and two boundaries that can
 * disagree eventually do. What is left is one tier line, per item, always set by an
 * act. See [toBands] for what that costs on upgrade.
 */

/** Which band, and therefore which side of the tier line. */
enum class Band { SHARED, VAULT }

/** What letting go right now would do to the shared band's **Crossing**. */
enum class ReleaseHint {
    /** Nothing changes, or the change is invisible. The usual answer. */
    NONE,

    /** One more contributor arrives and the night becomes one I shared. */
    GAINED,

    /** The last of mine leaves and the night stops being one I shared. */
    LOST,
}

/**
 * One night's media, split.
 *
 * [received] is always in the shared band — it arrived through someone's deliberate
 * act and I hold no disposition over it — and always to the right of [shared].
 */
data class Bands(
    val shared: List<StoredMedia> = emptyList(),
    val received: List<StoredMedia> = emptyList(),
    val vault: List<StoredMedia> = emptyList(),
) {
    /**
     * How many people's media the shared band holds: me, if any of mine is in it,
     * plus each distinct sender.
     *
     * One sender and nothing of mine is **one** contributor, so the band stays
     * ungreen — green has to keep meaning *more than one of us* or it means nothing.
     */
    val contributors: Int
        get() = (if (shared.isEmpty()) 0 else 1) + senders()

    /** Whether the shared band is a **Crossing**: more than one of us in it. */
    val crossed: Boolean get() = contributors > 1

    internal fun senders(): Int = received.mapNotNull { it.from }.distinct().size
}

/**
 * Split one night's media into its bands.
 *
 * **My own items keep their stored order**; that order is the arrangement, and
 * nothing here may reshuffle it. **Received media** is sorted by capture time
 * instead, so the arrivals still read as the night they came from — and because
 * **Reconcile** has no time bound, a **Contact** made years from now drops media
 * into an old night. Sorting only their run is what keeps my half stable: new
 * arrivals can never move one of my photographs.
 */
fun bandsOf(media: List<StoredMedia>): Bands {
    val mine = media.filter { it.from == null }
    return Bands(
        shared = mine.filter { !it.personal },
        // Stable, so unknown capture times keep the order they arrived in rather
        // than being shuffled against each other.
        received = media.filter { it.from != null }
            .sortedWith(compareBy(nullsLast()) { it.capturedAt }),
        vault = mine.filter { it.personal },
    )
}

/**
 * What the shared band would say if you let go now, given how many of my own
 * photographs it would then hold.
 *
 * Both gestures come through here. The hint is only ever shown when releasing
 * genuinely changes the fact — a band already holding two contributors has nothing
 * to promise, and one that would still hold two has nothing to warn about.
 */
fun releaseHint(media: List<StoredMedia>, mineSharedAfter: Int): ReleaseHint {
    val bands = bandsOf(media)
    val after = (if (mineSharedAfter > 0) 1 else 0) + bands.senders()
    // Only the *crossing* of the line matters, not the count either side of it.
    // Going from two contributors to three is not news, and neither is going back
    // to two — the promise is about a night becoming one I shared, and nothing else.
    val crossedNow = bands.contributors > 1
    val crossedAfter = after > 1
    return when {
        !crossedNow && crossedAfter -> ReleaseHint.GAINED
        crossedNow && !crossedAfter -> ReleaseHint.LOST
        else -> ReleaseHint.NONE
    }
}

/** The hint for a photograph arriving from the handle into [to]. */
fun hintForAdding(media: List<StoredMedia>, to: Band): ReleaseHint =
    releaseHint(media, bandsOf(media).shared.size + if (to == Band.SHARED) 1 else 0)

/**
 * The hint for an item already on the night being dragged into [to].
 *
 * **Received media** answers [ReleaseHint.NONE] because it cannot move: its
 * disposition is not mine to set, and offering one would be offering to publish on
 * someone else's behalf.
 */
fun hintForMoving(media: List<StoredMedia>, id: String, to: Band): ReleaseHint {
    val item = media.firstOrNull { it.id == id } ?: return ReleaseHint.NONE
    if (item.from != null) return ReleaseHint.NONE
    val shared = bandsOf(media).shared.size
    val leaving = if (!item.personal) 1 else 0
    val arriving = if (to == Band.SHARED) 1 else 0
    return releaseHint(media, shared - leaving + arriving)
}

/**
 * Move one of my items to [index] within [to], returning the night's new media.
 *
 * The result is normalised — my shared run, then **Received media**, then my vault
 * run — which is what keeps my own items to the left of anyone else's without a
 * separate rule to enforce it. A move between bands flips
 * [StoredMedia.personal]; a move within one leaves every bit untouched, so
 * arranging keepsakes is never accidentally a privacy act.
 *
 * A **Received** item, or an id that is not on this night, returns the list
 * unchanged rather than throwing: this is driven by a finger.
 */
fun moveMedia(media: List<StoredMedia>, id: String, to: Band, index: Int): List<StoredMedia> {
    val item = media.firstOrNull { it.id == id } ?: return media
    if (item.from != null) return media
    val bands = bandsOf(media)
    val shared = bands.shared.filterNot { it.id == id }.toMutableList()
    val vault = bands.vault.filterNot { it.id == id }.toMutableList()
    val target = if (to == Band.SHARED) shared else vault
    target.add(index.coerceIn(0, target.size), item.copy(personal = to == Band.VAULT))
    return shared + bands.received + vault
}

/**
 * The upgrade, and the one genuinely dangerous thing in #162.
 *
 * Before this, an item's `personal` bit was the *second* of two boundaries and
 * defaulted to false, with a night-level grant deciding whether anything left at
 * all. Removing that grant makes `personal = false` mean shared on its own — so
 * every photograph already attached under the old model would become visible to
 * every future **Contact** the moment the app updated. That is precisely the
 * prospective grant nobody made, which is what the night-level act existed to
 * prevent.
 *
 * So the migration reads the old grant one last time and sends everything else to
 * the vault. Its direction is only ever *toward* the vault: an act someone actually
 * performed is preserved, and everything else becomes a decision they still get to
 * make. **Received media** is untouched — `personal` is a statement about my own
 * disposition and says nothing about a photograph I was given.
 */
fun toBands(media: List<StoredMedia>, nightShared: Boolean): List<StoredMedia> =
    if (nightShared) media
    else media.map { if (it.from == null && !it.personal) it.copy(personal = true) else it }
