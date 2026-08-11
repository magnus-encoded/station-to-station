import Foundation

// The timeline's derived shape. Nothing here is stored (see TimelineStore): the
// spine is recomputed from the facts every time it is drawn, so changing a rule
// in this file never needs a migration.
//
// Ported from the Android FestivalScreen.kt/StationScreen.kt logic, term for term
// with UBIQUITOUS_LANGUAGE.md. The vocabulary is exact: Line, Spine, Lane, Node,
// Crossing, Joined, Absorb.

/// A timeline is a mix of single gigs and festivals (a run of shows at one venue).
enum TimelineNode {
    case concert(FmSetlist)
    case festival(name: String, shows: [FmSetlist])

    var shows: [FmSetlist] {
        switch self {
        case .concert(let s): return [s]
        case .festival(_, let shows): return shows
        }
    }

    var isFestival: Bool {
        if case .festival = self { return true }
        return false
    }
}

/// A Festival is two or more Gigs at the same venue within a few days.
private let festivalWindowDays: TimeInterval = 4 * 24 * 60 * 60

/// Two adjacent shows belong together when they share a venue and fall within
/// the window.
private func sameFestival(_ a: FmSetlist, _ b: FmSetlist) -> Bool {
    guard let venueA = a.venue?.name, let venueB = b.venue?.name,
          venueA.caseInsensitiveCompare(venueB) == .orderedSame,
          let da = a.localDate(), let db = b.localDate()
    else { return false }
    return abs(da.timeIntervalSince(db)) <= festivalWindowDays
}

/// The festival's real name — "Øyafestivalen 2025", not "Tøyenparken" — resolved
/// from setlist.fm's festival entity and keyed by the cluster's first show. Until
/// it lands (or if it never does) the venue stands in.
private func festivalName(_ shows: [FmSetlist], _ names: [String: String]) -> String {
    guard let first = shows.first else { return "Festival" }
    return names[first.id] ?? first.venue?.name ?? "Festival"
}

/// Groups a date-ordered list of shows into festivals, leaving lone shows as
/// concerts. `names` maps a cluster's first show id to the festival's real name.
func groupIntoFestivals(_ setlists: [FmSetlist], names: [String: String] = [:]) -> [TimelineNode] {
    var nodes: [TimelineNode] = []
    var i = 0
    while i < setlists.count {
        var cluster = [setlists[i]]
        var j = i + 1
        while j < setlists.count, sameFestival(cluster[cluster.count - 1], setlists[j]) {
            cluster.append(setlists[j])
            j += 1
        }
        nodes.append(cluster.count >= 2
            ? .festival(name: festivalName(cluster, names), shows: cluster)
            : .concert(cluster[0]))
        i = j
    }
    return nodes
}

/// A row of the timeline at whatever Resolution it is shown at. `node` is always
/// my own shape of the thing — a gig or a collapsed festival — so a row keeps the
/// same size whether or not other people's lines are on screen. `others` are the
/// friends who were also there; `depth` 1 marks a gig listed inside an open
/// festival.
struct WovenRow: Identifiable {
    let node: TimelineNode
    let mine: Bool
    let others: [Friend]
    var depth: Int = 0
    /// The shows on this node that friends attended — a union across all of them,
    /// deduped by id, and some of them are mine too. Not a partition: calling this
    /// `theirShows` is exactly why concatenating two friends' lists looked fine
    /// and double-counted every gig they both went to.
    var showsHereByFriends: [FmSetlist] = []

    /// Shows I was at with company: the number this Resolution exists to surface.
    /// Zero on a node that isn't mine — there, `shows` are already a friend's, so
    /// intersecting them with what friends attended matched everything and called
    /// a festival I never went to "3 together".
    var sharedCount: Int {
        guard mine else { return 0 }
        let alsoTheirs = Set(showsHereByFriends.map(\.id))
        return shows.filter { alsoTheirs.contains($0.id) }.count
    }

    /// Shows a friend was at here **and I was not** — which is what Theirs means: a Gig
    /// on a friend's timeline and not on mine.
    ///
    /// `showsHereByFriends` is a union and not a partition, so counting it directly says
    /// "theirs" about nights we were at together. Where their list is a subset of mine
    /// that reads as "4 together · 4 yours · 4 theirs" — four nights of theirs that do
    /// not exist. Ported with Android.
    var theirsCount: Int {
        guard mine else { return showsHereByFriends.count }
        let mineHere = Set(shows.map(\.id))
        return showsHereByFriends.filter { !mineHere.contains($0.id) }.count
    }

    var key: String {
        switch node {
        case .concert(let s): return "c-\(s.id)-\(depth)"
        case .festival(_, let shows): return "f-\(shows.first?.id ?? "")"
        }
    }

    var id: String { key }

    var date: Date? {
        switch node {
        case .concert(let s): return s.localDate()
        case .festival(_, let shows): return shows.compactMap { $0.localDate() }.max()
        }
    }

    var shows: [FmSetlist] { node.shows }

    /// Somebody else is on this row — which is **not** the same as Together, and
    /// deliberately not named `shared` for that reason. A Festival that merely
    /// Absorbs a friend's cluster (my 24–25 June swallowing their 26–27 at the
    /// same venue) has company while we shared no night at all: `hasCompany` is
    /// true and `sharedCount` is 0. "Absorb puts their cluster in my node; it
    /// doesn't make the nights shared."
    var hasCompany: Bool { mine && !others.isEmpty }

    /// What the row reads out. Together is a Gig on both lists — never inferred
    /// from company on the node.
    var ownership: RowOwnership {
        if !mine { return .theirs }
        return sharedCount > 0 ? .together : .mine
    }
}

enum RowOwnership: String {
    case mine, theirs, together
}

/// Whether `other`'s cluster belongs on this node rather than beside it: my
/// Festival Absorbing their run at the same venue, or — the case a lone gig used
/// to miss — simply the same gig on both lists. Anything looser (same venue,
/// different nights, neither of us clustering it) would mark unshared nights as
/// shared.
private func hosts(_ node: TimelineNode, _ other: TimelineNode) -> Bool {
    if absorbs(node, other) { return true }
    let otherIds = Set(other.shows.map(\.id))
    return node.shows.contains { otherIds.contains($0.id) }
}

/// Same venue, overlapping few days — near enough to be the same festival.
private func absorbs(_ node: TimelineNode, _ other: TimelineNode) -> Bool {
    guard case .festival(_, let mineShows) = node else { return false }
    return other.shows.contains { show in mineShows.contains { sameFestival($0, show) } }
}

/// Newest first, undated last, ties broken by the order the rows were built.
/// Kotlin's `sortedByDescending` is stable and Swift's `sorted` is not, so the
/// index tiebreak is what keeps the two platforms drawing the same order.
private func newestFirst<T>(_ items: [T], date: (T) -> Date?) -> [T] {
    items.enumerated()
        .sorted { a, b in
            switch (date(a.element), date(b.element)) {
            case let (x?, y?): return x == y ? a.offset < b.offset : x > y
            case (nil, _?): return false
            case (_?, nil): return true
            case (nil, nil): return a.offset < b.offset
            }
        }
        .map(\.element)
}

/// Everything on one Spine: my Nodes, plus the ones only other people were at. A
/// run of shows nobody but a friend attended doesn't compress my Line — it just
/// makes the Edge between my own Nodes longer, which is the whole point of
/// zooming out.
///
/// A friend's shows are clustered into festivals the same way mine are, and a
/// cluster of theirs that lands at my venue within the same few days is folded
/// into my festival node rather than sitting beside it: one Tons of Rock, marked
/// shared. Expanding that node (`expanded` holds row keys) lists the individual
/// gigs so the two attendances can be compared inside the festival.
func weaveTimelines(
    mine: [FmSetlist],
    festivalNames: [String: String] = [:],
    friends: [Friend] = [],
    theirs: [String: [FmSetlist]] = [:],
    expanded: Set<String> = []
) -> [WovenRow] {
    let myNodes = groupIntoFestivals(mine, names: festivalNames)
    // Every node on the spine, mine first so a night I was at always hosts the
    // meeting. A cluster of theirs that no existing host takes becomes a host
    // itself, which is what lets two friends at a gig I missed land on one node
    // instead of one each.
    //
    // Keyed by index rather than by node value: two friends' clusters can be
    // equal by value and must still not collide.
    var hostNodes = myNodes
    var friendsAt: [Int: [Friend]] = [:]
    var showsAt: [Int: [FmSetlist]] = [:]

    for friend in friends {
        let shows = theirs[friend.setlistfm] ?? []
        if shows.isEmpty { continue }
        for node in groupIntoFestivals(shows, names: festivalNames) {
            let host: Int
            if let existing = hostNodes.firstIndex(where: { hosts($0, node) }) {
                host = existing
            } else {
                hostNodes.append(node)
                host = hostNodes.count - 1
            }
            var here = friendsAt[host] ?? []
            if !here.contains(where: { $0.setlistfm == friend.setlistfm }) { here.append(friend) }
            friendsAt[host] = here
            // Deduped by show id: two friends at the same gig contribute it once,
            // or every count taken off this node double-counts as soon as there
            // are two of them.
            var hereShows = showsAt[host] ?? []
            for show in node.shows where !hereShows.contains(where: { $0.id == show.id }) {
                hereShows.append(show)
            }
            showsAt[host] = hereShows
        }
    }

    let rows = newestFirst(
        hostNodes.enumerated().map { i, node in
            WovenRow(
                node: node,
                mine: i < myNodes.count,
                others: friendsAt[i] ?? [],
                showsHereByFriends: showsAt[i] ?? []
            )
        },
        date: { $0.date }
    )

    if expanded.isEmpty { return rows }
    // Open festivals list their gigs underneath, each tagged with who was at that one.
    return rows.flatMap { row -> [WovenRow] in
        guard row.node.isFestival, expanded.contains(row.key) else { return [row] }
        // Whose a gig is comes from my own timeline, never from the node holding
        // it — reading it off the node made every gig inside a friend's festival
        // look mine.
        let myIds = Set(mine.map(\.id))
        var seen = Set<String>()
        let deduped = (row.node.shows + row.showsHereByFriends).filter { seen.insert($0.id).inserted }
        let inner = newestFirst(deduped, date: { $0.localDate() }).map { show in
            WovenRow(
                node: .concert(show),
                mine: myIds.contains(show.id),
                others: row.others.filter { f in
                    (theirs[f.setlistfm] ?? []).contains { $0.id == show.id }
                },
                depth: 1
            )
        }
        return [row] + inner
    }
}

// MARK: - Lane geometry

/// My own Line. Not a Lane: it is the fixed thing every Lane is measured against.
let Spine = -1

/// The Spine's geometry, shared by every row so nothing moves between Resolutions.
let SpineWidth: CGFloat = 52
let SpineX: CGFloat = 25

private let LaneStep: CGFloat = 20

/// How wide the strip may grow. Past this the Lanes tighten instead of pushing
/// the text off the phone, so the view survives more friends than fit.
private let MaxStripWidth: CGFloat = 132

/// Lane spacing for `count` friends: full step until the strip is full, then tighter.
func laneStep(_ count: Int) -> CGFloat {
    count <= 0 ? LaneStep : min(LaneStep, MaxStripWidth / CGFloat(count))
}

/// The strip's width at `count` friends — never more than `MaxStripWidth`.
func stripWidth(_ count: Int) -> CGFloat { laneStep(count) * CGFloat(count) }

/// A Line index in points. `Spine` is -1, so Lane 0 sits one step out from my Spine.
///
/// *Which* Line is a whole number; the only honest float in this area is *where in
/// points*, which is this function's result and the strip's openness in `crossingX`.
func laneXf(_ offset: Int, _ step: CGFloat) -> CGFloat { SpineX + step * CGFloat(offset + 1) }

/// Which Lines were at a row: Spine for me, plus a Lane index per friend present.
///
/// The single which-Line primitive. Everything else here is a question asked of this
/// list — the Node's host is its minimum, presence is membership, and company is its
/// count — so the merge rule is written once and cannot drift out of step with the
/// canvas that draws it (#69). It used to live private to StationView.swift, where
/// the copy that actually drew was the one nothing could test.
func linesAt(_ row: WovenRow, _ lanes: [Friend]) -> [Int] {
    var out: [Int] = []
    if row.mine { out.append(Spine) }
    for (i, f) in lanes.enumerated() where row.others.contains(where: { $0.setlistfm == f.setlistfm }) {
        out.append(i)
    }
    return out
}

/// Which Line a row's Node sits on. Lines that share a Node become one Line, so a
/// night has exactly one Node — mine when I was there (my Line never moves to
/// meet anyone), otherwise the innermost Lane among the friends who were, which
/// the others come to.
///
/// The innermost Line *is* the minimum: Spine is -1 and so sorts below every Lane
/// index, and `row.mine` is what puts it in the set. That equivalence used to be
/// something to verify by reading two implementations against each other.
func nodeHost(_ row: WovenRow, _ lanes: [Friend]) -> Int {
    linesAt(row, lanes).min() ?? Spine
}

/// Where a Line is drawn at a row: on the Node if it was there, otherwise its own
/// Lane. `line` is Spine for mine or a Lane index for a friend's. The Line-index-keyed
/// twin of `hostLane`, and the one the canvas asks.
func lineDrawnOffset(_ row: WovenRow?, _ line: Int, _ lanes: [Friend]) -> Int {
    guard let row else { return line }
    return linesAt(row, lanes).contains(line) ? nodeHost(row, lanes) : line
}

/// Which Line `friend` is drawn on at `row`: the Node's host if they were there,
/// otherwise their own Lane. This is the whole merge rule — asking it per friend
/// is what makes a Parting on the row someone else joins two independent answers
/// instead of one shared boolean.
///
/// Resolves the Friend to a Lane index and hands the same rule to `lineDrawnOffset`:
/// one rule, two key types, one implementation. Spine, not 0, when they have no
/// Lane — 0 is a real Lane and would draw a stranger's Line next to mine (Kotlin's
/// indexOfFirst returns -1 here, which is why it reads as `?? Spine`).
func hostLane(_ row: WovenRow?, _ friend: Friend, _ lanes: [Friend]) -> Int {
    let own = lanes.firstIndex { $0.setlistfm == friend.setlistfm } ?? Spine
    return lineDrawnOffset(row, own, lanes)
}

/// Where a row's Node sits in points. My Line never moves — a shared night happens
/// on my Spine and theirs comes to meet it. Putting the Node between the two made
/// both timelines leave their own path to attend it.
///
/// The Int→points conversion the whole grammar rests on, and the one thing here that
/// legitimately produces a float: the Lanes are still sliding out while the strip
/// opens, so the Node travels with them.
func crossingX(_ row: WovenRow, _ lanes: [Friend], _ laneWidth: CGFloat) -> CGFloat {
    let offset = nodeHost(row, lanes)
    if laneWidth <= 0 || offset == Spine { return SpineX }
    let step = laneStep(lanes.count)
    let open = min(max(laneWidth / stripWidth(lanes.count), 0), 1)
    return SpineX + (laneXf(offset, step) - SpineX) * open
}

// MARK: - Lane staleness

/// Does a friend's fetched shows reach back at least as far as my own oldest
/// Gig? `nil` (I have no Gigs of my own yet) always reaches back — there is
/// nothing to fall short of. Ported term for term from Android's `reachesBack`.
func laneReachesBack(_ shows: [FmSetlist], to oldestOfMine: Date?) -> Bool {
    guard let oldestOfMine else { return true }
    guard let theirOldest = shows.compactMap({ $0.localDate() }).min() else { return false }
    return theirOldest <= oldestOfMine
}

/// A Lane is worth refetching when it is missing, empty, or truncated short of
/// my own oldest Gig — cached-and-complete is the common case this exists to
/// skip.
func laneIsStale(_ shows: [FmSetlist]?, oldestOfMine: Date?) -> Bool {
    guard let shows, !shows.isEmpty else { return true }
    return !laneReachesBack(shows, to: oldestOfMine)
}
