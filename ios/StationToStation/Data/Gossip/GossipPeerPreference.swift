import Foundation

// Lives in GossipBudget.swift on the v2 branch; kept apart while v1's GossipBudget.swift is
// unchanged on main. Folds back in with #462.

/// How long the central would gather sightings before picking one peer, if it had to pick
/// (#444, story 38). **Android holds the same second and a half** (`GOSSIP_PICK_WINDOW_MS`).
///
/// **Provisional.** Nothing has measured it.
let gossipPickWindow: TimeInterval = 1.5

/// The order to try the peers seen in one window, best first (#444, stories 38 and 39).
///
/// Preference, never exclusion. Every candidate is returned — a neighbour with no credit is
/// still pushed to, just later in the list — because a phone that only ever spoke to the
/// neighbours that had already proved useful would never learn that any other one is. The
/// shuffle runs over the whole list before the partition, so both bands are shuffled, and
/// `generator` is a parameter rather than a global for the only reason that matters: a test
/// cannot assert "randomly" without a seed.
///
/// **Not yet wired on this platform**, and that is the honest state rather than an oversight.
/// Android's central holds one connection at a time, so a sighting it takes is a sighting it
/// spends; `GossipTransport` opens a meeting with every peripheral `didDiscover` reports and
/// has no scarce slot to ration. The rule lives here so the two platforms cannot drift apart
/// on what "useful first" means, and so the case has its twin; wiring it is a change to
/// `beginMeeting`, and it only becomes worth making when iOS caps concurrent meetings.
func gossipPreferredPeers(_ candidates: [String], credited: (String) -> Bool,
                          using generator: inout some RandomNumberGenerator) -> [String] {
    var seen = Set<String>()
    let unique = candidates.filter { seen.insert($0).inserted }
    let shuffled = unique.shuffled(using: &generator)
    return shuffled.filter(credited) + shuffled.filter { !credited($0) }
}
