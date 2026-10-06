import Foundation

/// Where the app is looking: which **Gig**, place or Resolution is open, which **Lines** and
/// **Festivals** are shown, and the Contact light.
@MainActor
final class NavigationController {
    let host: StateHost
    private let timelines: TimelineStore
    private let fetchSetlist: (String) async throws -> FmSetlist
    private let identifyFestivals: ([FmSetlist], Festivals) async -> Festivals
    private let refreshLine: (Friend) -> Void
    private let loadGigMedia: (FmSetlist) -> Void

    init(
        host: StateHost,
        timelines: TimelineStore,
        fetchSetlist: @escaping (String) async throws -> FmSetlist,
        identifyFestivals: @escaping ([FmSetlist], Festivals) async -> Festivals,
        refreshLine: @escaping (Friend) -> Void,
        loadGigMedia: @escaping (FmSetlist) -> Void
    ) {
        self.host = host
        self.timelines = timelines
        self.fetchSetlist = fetchSetlist
        self.identifyFestivals = identifyFestivals
        self.refreshLine = refreshLine
        self.loadGigMedia = loadGigMedia
    }

    /// Asks which **Festival**, if any, the unidentified evenings currently on the
    /// timeline belong to. The rule itself (which evenings are candidates, that "no
    /// festival" is a real answer worth keeping, and that the answers are stored)
    /// lives in the logic layer; this is the after-a-fresh-import caller of it.
    func resolveFestivals() {
        let mine = host.state.timelineShows
        let ahead = plannedLane(host.state.plannedGigs, host.state.attendanceByGig)
        let known = host.state.festivals
        Task {
            // Two passes rather than one concatenated list, so a night ahead and a night
            // behind can never be read as one evening. The future lane grows its own
            // Sections (#134) and they want identities too — and it is `plannedLane` here
            // for the reason that function exists: the resolver and the lane have to be
            // looking at the same list.
            let found = await identifyFestivals(mine, known)
            let alsoAhead = await identifyFestivals(ahead, found)
            if alsoAhead == known { return }
            host.state.festivals = alsoAhead
        }
    }

    /// Tap a name in the legend to take their **Line** off the strip, tap it again to
    /// bring it back. Nothing is sent and nothing says anything about the
    /// relationship — but the toggle and its moment are persisted (#396), which is
    /// what lets the legend's recency order survive a launch.
    /// Showing a **Line** also asks setlist.fm for its latest: it may have been off for a while.
    func toggleLineHidden(_ lane: String) {
        let showing = host.state.hiddenAt[lane] != nil
        if showing { host.state.hiddenAt[lane] = nil }
        else { host.state.hiddenAt[lane] = Int64(Date().timeIntervalSince1970 * 1000) }
        Task { await timelines.saveHiddenLines(host.state.hiddenAt) }
        if showing, let friend = host.state.friends.first(where: { $0.laneKey == lane }) {
            refreshLine(friend)
        }
    }

    /// Pinch out to open the friends' Lanes beside my Spine, pinch in to close
    /// them. Nothing navigates — the same one Timeline, at a different Resolution.
    func setZoomedOut(_ v: Bool) {
        if v && host.state.friends.isEmpty { return }
        host.state.zoomedOut = v
    }

    /// Flip the light switch: my own Line, as a Contact sees it. Always comes on
    /// faithful — withheld items stay hidden until asked for again.
    func toggleContactLight() {
        host.state.contactLight.toggle()
        host.state.showWithheld = false
    }

    func setShowWithheld(_ v: Bool) {
        host.state.showWithheld = v
    }

    /// A Festival uncollapses in place — it never pushes a screen.
    func toggleFestival(_ key: String) {
        if host.state.expandedFestivals.contains(key) {
            host.state.expandedFestivals.remove(key)
        } else {
            host.state.expandedFestivals.insert(key)
        }
    }

    /// **Back out** of **Festival resolution** (#176). A Festival is uncollapsed in
    /// place and is never a screen, so there is no stack entry to pop — but it is
    /// still a rung, and **Back out** has no per-screen exception. Every uncollapsed
    /// Festival is at that one rung, so one swipe collapses all of them: that *is*
    /// one rung **Outer**, not several.
    ///
    /// False means nothing was open, and the Timeline is then at its outermost rung
    /// — where **Pinch**, not **Back out**, is the gesture.
    @discardableResult
    func backOutOfFestivals() -> Bool {
        if host.state.expandedFestivals.isEmpty { return false }
        host.state.expandedFestivals.removeAll()
        return true
    }

    /// A **Gig** a link names. On my **Line** it opens as it is; an unknown setlist.fm id
    /// is fetched and opened without being kept: joining it is a question the **Room**
    /// asks, and an invite never answers it. An id with nothing to fetch says so. A failed
    /// fetch reports nothing: an invite for a night setlist.fm cannot serve is a dead
    /// link, and a banner would be telling the reader about the sender's problem.
    func openGig(_ id: String, onOpen: @escaping () -> Void) {
        let known = host.state.timelineShows.first(where: { $0.id == id })
            ?? host.state.plannedGigs.first(where: { $0.id == id })
        switch planOpenGig(id, onMyLine: known != nil) {
        case .open:
            open(known!, onOpen)
        case .refuse:
            host.state.error = "That doesn't look like a setlist.fm gig link."
            host.state.errorKind = nil
        case .fetchThenOpen:
            Task {
                guard let fetched = try? await fetchSetlist(id) else { return }
                open(fetched, onOpen)
            }
        }
    }

    private func open(_ show: FmSetlist, _ onOpen: () -> Void) {
        host.state.selectedSetlist = show
        loadGigMedia(show)
        onOpen()
    }

    /// `timeline` and `timelines`: the Resolution, and a date to scroll to if there is one.
    func openTimeline(zoomedOut: Bool, date: String?) {
        setZoomedOut(zoomedOut)
        host.state.linkedDate = date
    }

    /// A place link: the **Gig** on its own, or my **Line** or the weave scrolled to it.
    func openPlace(_ id: String, as at: GigLink, onOpen: @escaping () -> Void) {
        if at == .setlist { openGig(id, onOpen: onOpen); return }
        setZoomedOut(at == .woven)
        host.state.linkedGig = id
        host.state.linkedGigAs = at
    }

    func openAddGig(artist: String?, venue: String?, date: String?) {
        host.state.addGigLink = AddGigLink(
            artist: artist ?? "",
            venue: venue ?? "",
            date: date.map { $0.split(separator: "-").reversed().joined(separator: "-") } ?? ""
        )
    }
}
