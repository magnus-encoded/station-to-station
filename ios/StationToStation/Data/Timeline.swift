import Foundation

// The timeline's derived shape. Nothing here is stored (see TimelineStore): the
// spine is recomputed from the facts every time it is drawn, so changing a rule
// in this file never needs a migration.
//
// Ported from the Android FestivalScreen.kt/StationScreen.kt logic, term for term
// with UBIQUITOUS_LANGUAGE.md. The vocabulary is exact: Line, Spine, Lane, Node,
// Crossing, Joined, Absorb.

/// What one **Node** on the **Line** stands for: a lone **Gig**, an evening of
/// several, or a **Festival**.
///
/// The three are not three shapes of the same claim. A **Section** says *these
/// performances were the same night in the same room* — a fact we have, from the date
/// and the venue we were given. A **Festival** says *this evening was Øyafestivalen
/// 2025*, which is a claim about what happened and needs a source that knows. #166 is
/// the fifth application of ADR-0002's thesis: festivalhood is demoted from a shape
/// the app computes to an identity a **Section** may acquire.
///
/// Kotlin gives the last two a shared `Several` supertype so that nothing downstream
/// can branch on which kind it got. A Swift enum cannot have one, so the supertype is
/// expressed as the questions both cases answer — ``isSeveral``, ``label``,
/// ``setTimes``, ``runningOrder(also:)``, ``severalKey`` — and the rule is the same:
/// the weave and the geometry ask *how many nights*, never *what kind*.
enum TimelineNode {
    case concert(FmSetlist)
    /// One evening: two or more **Gigs** on the same date at the same venue, and
    /// nothing else. It makes no claim about what the evening *was*, and it is named
    /// from its own acts — never from the room it happened in.
    case section([FmSetlist])
    /// A **Section** that has an identity — and the identity is the whole of it. It
    /// arrives from setlist.fm's own festival page or from a **Bill** typed in by
    /// hand, and it is never inferred: a run of nights at one venue that nothing has
    /// named is a run of nights.
    case festival(StoredFestival, [FmSetlist])

    var shows: [FmSetlist] {
        switch self {
        case .concert(let s): return [s]
        case .section(let shows): return shows
        case .festival(_, let shows): return shows
        }
    }

    /// Several **Gigs** drawn as one **Node** — the only thing the screens and the
    /// geometry need to tell from a **Concert**.
    var isSeveral: Bool {
        if case .concert = self { return false }
        return true
    }

    /// Whether something actually knows this evening was a festival. The *one* place
    /// the difference is allowed to show: the eyebrow above the label, which must not
    /// say FESTIVAL about a claim nothing supports.
    var isIdentified: Bool {
        if case .festival = self { return true }
        return false
    }

    /// What to draw. **Computed, never stored** — for the **Preamble**'s reason:
    /// **Reconcile** has no time bound, a support act can be corrected upstream years
    /// later, and a stored label would be the record freezing a fact it has since
    /// learned better.
    var label: String {
        switch self {
        case .concert(let s): return s.artist?.name ?? ""
        case .section(let shows): return billedAs(shows)
        case .festival(let identity, _): return identity.name
        }
    }

    /// When each act went on, `HH:mm` by setlist.fm id, where a source published it.
    var setTimes: [String: String] {
        if case .festival(let identity, _) = self { return identity.setTimes ?? [:] }
        return [:]
    }

    /// The evening as it went: earliest set first, where the source said. Nights with
    /// no published time keep the order they arrived in, after the ones that have one
    /// — a running order is a fact, and the absence of one is not a reason to invent
    /// a different order.
    ///
    /// `also` is what other people were at here and I was not, so opening a node
    /// lists the whole evening rather than my half of it.
    func runningOrder(also: [FmSetlist] = []) -> [FmSetlist] {
        var seen = Set<String>()
        let deduped = (shows + also).filter { seen.insert($0.id).inserted }
        let times = setTimes
        // Kotlin's `sortedWith` is stable and Swift's `sorted` is not, so the source
        // index is the explicit tiebreak that keeps the two platforms in step.
        return deduped.enumerated().sorted { a, b in
            let da = a.element.localDate()
            let db = b.element.localDate()
            if da != db {
                switch (da, db) {
                case let (x?, y?): return x > y
                case (nil, _?): return false
                case (_?, nil): return true
                case (nil, nil): break
                }
            }
            let ta = times[a.element.id] ?? sortsLast
            let tb = times[b.element.id] ?? sortsLast
            if ta != tb { return ta < tb }
            return a.offset < b.offset
        }.map(\.element)
    }

    /// What a **Node** of several nights is keyed by wherever it is drawn. Nil for a
    /// **Concert**, which is keyed by its own setlist and its depth.
    ///
    /// A **Festival** is keyed by the identity's own id, which is the point of it
    /// having one (#166): the key used to be the cluster's first show, so adopting a
    /// setlist or correcting a venue typo moved it and took the row's open state —
    /// and, before #256, its stored name — with it.
    var severalKey: String? {
        switch self {
        case .concert: return nil
        case .section(let shows): return "s-\(shows.first?.id ?? "")"
        case .festival(let identity, _): return "f-\(identity.id)"
        }
    }
}

/// Sorts after every real `HH:mm` — see ``TimelineNode/runningOrder(also:)``.
private let sortsLast = "~"

/// Two supports named, the rest counted. See ``billedAs(_:setTimes:cap:)``.
let supportCap = 2

/// What an evening of several acts is called when nothing knows it was a festival:
/// **the headliner, then its supports in parentheses.** "Devin Townsend (Haken)".
///
/// This is the label half of #166. A **Node** was named after its venue whenever the
/// festival-name lookup had not landed, and a room is not an event.
///
/// **The headliner is who played last, and every fallback is a weaker answer to that
/// same question** — never a different question:
///
/// 1. **the latest scheduled set time**, where the source published them — setlist.fm
///    puts them on the festival page, and they are the real evidence for the running
///    order rather than a stand-in for it;
/// 2. **the longest set** — right for support-plus-headliner, which is the case this
///    fixes, and uninformative for a festival day, which an identity should name;
/// 3. **the order the source returned**, which at least makes the label stable rather
///    than arbitrary. Ties at every rung fall through to it.
func billedAs(_ shows: [FmSetlist], setTimes: [String: String] = [:], cap: Int = supportCap) -> String {
    let named = shows.filter { !($0.artist?.name ?? "").isEmpty }
    guard let first = named.first else { return shows.first?.venue?.name ?? "Several acts" }
    // `reduce` with a strict `>` keeps the *first* maximal element — the order the
    // source gave — where `max(by:)` would return the last one. Kotlin's
    // `maxByOrNull` keeps the first, so the tiebreak has to be spelled out here.
    let timed = named.filter { setTimes[$0.id] != nil }
    let headliner: FmSetlist
    if let firstTimed = timed.first {
        headliner = timed.dropFirst().reduce(firstTimed) {
            playedLast(setTimes[$1.id] ?? "") > playedLast(setTimes[$0.id] ?? "") ? $1 : $0
        }
    } else {
        headliner = named.dropFirst().reduce(first) {
            $1.performed().count > $0.performed().count ? $1 : $0
        }
    }
    let supports = named.filter { $0.id != headliner.id }.map { $0.artist?.name ?? "" }
    let head = headliner.artist?.name ?? ""
    if supports.isEmpty { return head }
    let shown = supports.prefix(cap)
    let tail = supports.count > shown.count
        ? "\(shown.joined(separator: ", ")) +\(supports.count - shown.count)"
        : shown.joined(separator: ", ")
    return "\(head) (\(tail))"
}

/// How late in the *night* a `HH:mm` set time is, as something sortable.
///
/// A 00:30 slot closed the evening; it did not open it. This draws the same line
/// `nightEndsHour` does for a check-in — the night is still going on at 01:30 —
/// because a headliner picked by clock time alone would hand the billing to the first
/// band on.
private func playedLast(_ time: String) -> String {
    time < String(format: "%02d:00", nightEndsHour) ? "~\(time)" : time
}

/// What an unidentified cluster calls itself above its label: "ONE NIGHT" for several
/// acts on one date, "N NIGHTS" for a run. Both are things the data actually says.
func eveningKicker(_ shows: [FmSetlist]) -> String {
    var days = Set<Date>()
    for show in shows { if let d = show.localDate() { days.insert(d) } }
    return days.count <= 1 ? "ONE NIGHT" : "\(days.count) NIGHTS"
}

/// **The one seam: what becomes one Node.** Everything that draws a **Line** — the
/// **Spine**, every **Lane** beside it, the future lane — comes through here, which is
/// why the rule can be changed in one place and why nothing downstream needs to know
/// which kind it got.
///
/// Three rules, and there is no fourth:
///
/// - **An identity supplied for a set of Gigs → a `festival`.** Membership comes from
///   the identity's own day grouping where the source published one, and otherwise
///   from the **Gigs** carrying that identity. One night of a four-day festival is
///   still that festival: going for one day does not shrink it.
/// - **Same date, same venue → a `section`.** One evening, drawn as one **Node**,
///   named from its acts.
/// - **Nothing else groups.** Two nights at one venue with no identity are two
///   **Nodes** — a residency, a local haunt, or a coincidence, and the record says the
///   true, smaller thing rather than inventing an event that never happened.
///
/// The four-day window that used to make the second decision is gone. It guessed in
/// both directions: it invented festivals out of a headline show with support, and it
/// named the real ones after their room whenever the lookup had not landed.
///
/// Nodes come back in the order their first member appears in `setlists`, so a
/// date-ordered list stays date-ordered. Ported term for term from Android's
/// `groupIntoFestivals`.
func groupIntoFestivals(_ setlists: [FmSetlist], _ festivals: Festivals = Festivals()) -> [TimelineNode] {
    var order: [String] = []
    var groups: [String: [FmSetlist]] = [:]
    for show in setlists {
        let key = groupKey(show, festivals)
        if groups[key] == nil { order.append(key) }
        groups[key, default: []].append(show)
    }
    return order.compactMap { key in
        guard let shows = groups[key], let head = shows.first else { return nil }
        if let identity = festivals.of(head.id) { return .festival(identity, shows) }
        return shows.count >= 2 ? .section(shows) : .concert(head)
    }
}

/// What decides that two **Gigs** are the same **Node**: an identity, or one evening
/// in one room.
///
/// A show missing either half of "which evening" is keyed to itself and groups with
/// nothing — unknown is not a venue, and it is not a date either, so two nights that
/// cannot say where or when they were must never land on one **Node** together.
private func groupKey(_ show: FmSetlist, _ festivals: Festivals) -> String {
    if let identity = festivals.of(show.id) { return "f:\(identity.id)" }
    let venue = (show.venue?.name ?? "").lowercased()
    guard !venue.isEmpty, let date = show.localDate() else { return "x:\(show.id)" }
    return "e:\(date.timeIntervalSince1970)|\(venue)"
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
        if case .concert(let s) = node { return "c-\(s.id)-\(depth)" }
        return node.severalKey ?? ""
    }

    var id: String { key }

    var date: Date? { node.shows.compactMap { $0.localDate() }.max() }

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

/// Whether `other`'s node belongs on this one rather than beside it — the same three
/// facts the grouping seam uses, read across two **Lines** instead of down one, so a
/// **Crossing** is decided by exactly what makes a **Node**:
///
/// - **the same identity** — their nights at Øyafestivalen 2025 land on my Øya node,
///   however few of the days either of us went to;
/// - **the same Gig** on both lists, which is what **Together** means;
/// - **the same evening in the same room**, which is the **Section** rule.
///
/// Anything looser — same venue, different nights, an identity nobody supplied — would
/// mark unshared nights as shared, which is the four-day window #166 removed.
private func hosts(_ node: TimelineNode, _ other: TimelineNode) -> Bool {
    if sameIdentity(node, other) { return true }
    let otherIds = Set(other.shows.map(\.id))
    if node.shows.contains(where: { otherIds.contains($0.id) }) { return true }
    return sameEvening(node, other)
}

/// The one place the weave touches the kind, and only to compare two identities. It
/// never branches on Section-vs-Festival for behaviour.
private func sameIdentity(_ node: TimelineNode, _ other: TimelineNode) -> Bool {
    guard case .festival(let a, _) = node, case .festival(let b, _) = other else { return false }
    return a.id == b.id
}

/// Every night on both nodes in one room on one date. See `groupKey`.
private func sameEvening(_ node: TimelineNode, _ other: TimelineNode) -> Bool {
    let all = node.shows + other.shows
    guard let head = all.first, let date = head.localDate() else { return false }
    let venue = head.venue?.name ?? ""
    if venue.isEmpty { return false }
    return all.allSatisfy {
        $0.localDate() == date && venue.caseInsensitiveCompare($0.venue?.name ?? "") == .orderedSame
    }
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
/// A friend's shows go through the same `groupIntoFestivals` mine do, and a node of
/// theirs that `hosts` says is the same thing as one of mine — the same **Festival**
/// identity, the same **Gig**, or the same evening in the same room — is folded into
/// mine rather than sitting beside it: one Tons of Rock, marked shared. Expanding that
/// node (`expanded` holds row keys) lists the individual gigs so the two attendances
/// can be compared inside it.
func weaveTimelines(
    mine: [FmSetlist],
    festivals: Festivals = Festivals(),
    friends: [Friend] = [],
    theirs: [String: [FmSetlist]] = [:],
    expanded: Set<String> = []
) -> [WovenRow] {
    let myNodes = groupIntoFestivals(mine, festivals)
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
        for node in groupIntoFestivals(shows, festivals) {
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
        guard row.node.isSeveral, expanded.contains(row.key) else { return [row] }
        // Whose a gig is comes from my own timeline, never from the node holding
        // it — reading it off the node made every gig inside a friend's festival
        // look mine.
        let myIds = Set(mine.map(\.id))
        let inner = row.node.runningOrder(also: row.showsHereByFriends).map { show in
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
