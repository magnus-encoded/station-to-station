package io.github.magnusencoded.stationtostation.data.gossip

// Lives in GossipPolicy.kt on the v2 branch. Kept apart while v1's GossipPolicy.kt has
// different signatures for the functions around it; folds back in with #462.

/**
 * How long the central gathers sightings before it picks one peer to push to (#444, story 38).
 *
 * **Provisional.** The scan reports one device at a time, so without a window there is no set
 * to prefer *within* — the first advertisement seen always wins, and usefulness could never
 * change a decision. A second and a half is long enough to see a second radio in the same
 * room and short enough to stay well inside the time two people stand near each other, which
 * is the same budget the scan mode was chosen against. It costs that much latency on every
 * push; a real night's figures should settle whether that is the right trade.
 */
const val GOSSIP_PICK_WINDOW_MS = 1500L

/**
 * The order to try the peers seen in one window, best first (#444, stories 38 and 39).
 *
 * Preference, never exclusion. Every candidate is returned — a neighbour with no credit is
 * still pushed to, just later in the list — because a phone that only ever spoke to the
 * neighbours that had already proved useful would never learn that any other one is.
 *
 * Ties break randomly, and the shuffle runs over the *whole* list before the partition, so
 * the uncredited band is shuffled too. [random] is a parameter rather than a global for the
 * only reason that matters: a test cannot assert "randomly" without a seed.
 *
 * [credited] answers whether a candidate holds live credit right now; expiry is the caller's
 * business, and `PublicGossipState.prune` has already done it.
 */
fun gossipPreferredPeers(
    candidates: List<String>,
    credited: (String) -> Boolean,
    random: kotlin.random.Random,
): List<String> = candidates.distinct().shuffled(random).sortedByDescending(credited)
