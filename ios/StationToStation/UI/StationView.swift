import SwiftUI

// Station to Station — the Timeline face of the app. My Line is the Spine: one
// continuous amber stroke, my Gigs and Festivals on it. Pinch out and the strip
// of friends' Lanes opens *in place* (never a screen); a night we shared is one
// green Crossing on my Spine, and the Joined run after it is green too.
//
// The grammar and every rejected alternative (rungs, midpoint merges, wide lanes)
// live in UBIQUITOUS_LANGUAGE.md and the #22/#23 resolutions. This is the SwiftUI
// rendering of what weaveTimelines already decides; it is not a port of the
// Compose widget tree (#12: native, not a port).
//
// That slogan is scoped, and the scope is the half that keeps getting dropped.
// #12 split the two builds by *volatility, not by platform*, and said a grammar
// that took two issues and several device passes to settle is not to be
// re-derived. So it licenses SwiftUI idiom in material, shape and control — and
// never a different answer to what a Crossing is, what Amber means, or where a
// place lives. ADR-0017 is which half is which; ADR-0006 is the topology.

// --- Nocturnal palette. Amber only ever marks mine-and-happened. ---
private let ground = Color(red: 0x0E / 255, green: 0x0B / 255, blue: 0x14 / 255)
private let ink = Color(red: 0xED / 255, green: 0xE9 / 255, blue: 0xF2 / 255)
private let muted = Color(red: 0x8B / 255, green: 0x82 / 255, blue: 0x99 / 255)
private let faint = Color(red: 0x5A / 255, green: 0x53 / 255, blue: 0x68 / 255)
private let lineCol = Color(red: 0x2E / 255, green: 0x27 / 255, blue: 0x40 / 255)
private let slate = Color(red: 0x6D / 255, green: 0x7E / 255, blue: 0x9B / 255)
/// Mine. Never "the accent colour" — it means *mine*, at every Resolution.
private let amber = Color(red: 0xE7 / 255, green: 0xB2 / 255, blue: 0x4C / 255)
/// A Crossing and the Joined run after it. A meeting belongs to neither person,
/// so it is never amber and never a Lane colour.
private let crossed = Color(red: 0x6F / 255, green: 0xBF / 255, blue: 0x9C / 255)

/// One cool Lane colour per friend, by Lane index. Read as clearly not-amber and
/// not-green. Same list as Android's RailColors.
private let laneColors: [Color] = [
    slate,
    Color(red: 0x8A / 255, green: 0x6D / 255, blue: 0xA0 / 255),
    Color(red: 0x5F / 255, green: 0x8E / 255, blue: 0x8A / 255),
    Color(red: 0xA0 / 255, green: 0x7E / 255, blue: 0x6D / 255),
    Color(red: 0x7B / 255, green: 0x8F / 255, blue: 0xC4 / 255),
    Color(red: 0xA8 / 255, green: 0x74 / 255, blue: 0x8C / 255),
    Color(red: 0x6E / 255, green: 0x9B / 255, blue: 0x77 / 255),
    Color(red: 0x9A / 255, green: 0x8F / 255, blue: 0x5F / 255),
]
private func laneColor(_ index: Int) -> Color { laneColors[((index % laneColors.count) + laneColors.count) % laneColors.count] }

/// Fires `recompute` whenever any of the woven Timeline's real inputs settle to a new
/// value — never on the live pinch drag, which has no business here (see `rows` on
/// `StationView`, #308). Grouped into one ViewModifier rather than chained straight
/// onto `body`: six back-to-back `.onChange(of:)` calls of six different types blew
/// the type-checker's budget on this already-long modifier chain.
private struct RowsRecomputeModifier: ViewModifier {
    let zoomedOut: Bool
    let expandedFestivals: Set<String>
    let lanes: [Friend]
    let festivals: Festivals
    /// `FmSetlist` isn't `Equatable`, so its id stands in — cheap to derive and
    /// enough to know the underlying shows actually changed.
    let showIds: [String]
    let friendShowIds: [String: [String]]
    let recompute: () -> Void

    func body(content: Content) -> some View {
        content
            .onChange(of: zoomedOut) { _ in recompute() }
            .onChange(of: expandedFestivals) { _ in recompute() }
            .onChange(of: lanes) { _ in recompute() }
            .onChange(of: festivals) { _ in recompute() }
            .onChange(of: showIds) { _ in recompute() }
            .onChange(of: friendShowIds) { _ in recompute() }
    }
}

// --- Lane geometry lives in Timeline.swift ---
//
// It used to be written twice: a tested copy in the model layer deciding the Node's
// colour, and a private copy here deciding every path that was actually stroked.
// Nothing made them agree. `linesAt`, `nodeHost`, `lineDrawnOffset`, `laneXf` and
// `crossingX` are now the model's, and this file draws what they answer (#69).

struct StationView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var nav: Nav

    /// In-progress pinch, as a fraction of open (0...1). Nil once the gesture
    /// has ended and settled into `model.state.zoomedOut`. View-local: it is
    /// visual feedback for a gesture in flight, not app state to persist.
    @State private var dragFraction: CGFloat?
    /// The paste-a-link entry point for the future edge (#175). A sheet's-worth of
    /// state, not model state: it exists only while the alert is open.
    @State private var addingPlanned = false
    @State private var plannedLink = ""

    private var lanes: [Friend] { model.state.friends }

    /// Open enough to show Lanes, whether settled or mid-pinch. Only feeds cheap
    /// UI (the loading spinner) — never the weave, see `rows` below.
    private var showingLanes: Bool { model.state.zoomedOut || (dragFraction ?? 0) > 0 }

    /// Cached so a live pinch (`dragFraction`, which updates every gesture
    /// frame) never re-triggers the weave: it only ever drives `laneWidth`'s
    /// geometry below. `weaveTimelines` recomputes only when `recomputeRows`
    /// is actually called, from `.onChange` of the settled inputs it reads —
    /// same reason Android's `remember(...)` on this call is keyed on
    /// `zoomedOut`, never on the live drag value (#308).
    @State private var rows: [WovenRow] = []

    private func recomputeRows() {
        let s = model.state
        rows = weaveTimelines(
            mine: s.timelineShows,
            festivals: s.festivals,
            friends: s.zoomedOut ? lanes : [],
            theirs: s.zoomedOut ? s.showsByFriend : [:],
            expanded: s.expandedFestivals
        )
    }

    private var laneWidth: CGFloat {
        let full = stripWidth(lanes.count)
        if let f = dragFraction { return full * f }
        return model.state.zoomedOut ? full : 0
    }

    private var earliest: Int? {
        model.state.timelineShows.compactMap { Int($0.year() ?? "") }.min()
    }

    var body: some View {
        let s = model.state
        ZStack {
            ground.ignoresSafeArea()
            if s.timelineShows.isEmpty && s.plannedGigs.isEmpty {
                empty(loading: s.timelineLoading)
            } else {
                timeline
            }
        }
        .toolbar {
            // Explicit placement: iOS 16 puts an unplaced item somewhere else.
            ToolbarItem(placement: .principal) { wordmark }
            ToolbarItem(placement: .navigationBarTrailing) { menu }
        }
        .toolbarBackground(ground, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(true)
        // Zoom out (pinch fingers together) opens the friends' Lanes beside my
        // Spine — the Timelines resolution is the "larger" one, same sense as
        // Android's onZoomOut. Zoom in (spread apart) closes them. `scale` from
        // MagnificationGesture rises as fingers spread, so zooming out is
        // falling scale, hence `1 - scale` below. `.simultaneousGesture` rather
        // than `.gesture`: the enclosing ScrollView claims an exclusive gesture
        // first and the pinch never fires, so this must run alongside the
        // scroll's own recognisers instead of competing with them. No friends,
        // no strip to open — the gesture is a no-op rather than opening an
        // empty one.
        .simultaneousGesture(
            MagnificationGesture()
                .onChanged { scale in
                    guard !lanes.isEmpty else { return }
                    let base: CGFloat = model.state.zoomedOut ? 1 : 0
                    dragFraction = min(max(base + (1 - scale), 0), 1)
                }
                .onEnded { _ in
                    guard let f = dragFraction else { return }
                    withAnimation(.spring()) {
                        model.setZoomedOut(f > 0.5)
                        dragFraction = nil
                    }
                }
        )
        // Swipe the timeline left to start connecting with someone nearby — the
        // "act on this level" gesture, people axis.
        .swipeLeft { nav.push(.exchange) }
        // The same two moves for VoiceOver, which takes the flick and the pinch for
        // itself. A gesture is the whole of how this screen changes Resolution, so
        // without these the other lines are not awkward to reach — they are absent.
        // Named as verbs, and the strip's action says which way it goes, because the
        // rotor reads them with nothing on screen to disambiguate.
        .accessibilityAction(named: "Connect with someone nearby") { nav.push(.exchange) }
        .accessibilityAction(
            named: model.state.zoomedOut ? "Close the other timelines" : "Open the other timelines beside yours"
        ) {
            guard !lanes.isEmpty else { return }
            withAnimation(.spring()) { model.setZoomedOut(!model.state.zoomedOut) }
        }
        .onAppear {
            model.loadTimeline()
            recomputeRows()
        }
        // Fetch friends' Lanes when the strip opens, not at launch — a
        // Resolution never opened shouldn't spend setlist.fm's budget.
        .onChange(of: model.state.zoomedOut) { open in
            if open { model.loadFriendTimelines() }
        }
        // One modifier, not six chained `.onChange`s: that many distinct
        // `Equatable` types stacked in a single `body` expression blew the
        // type-checker's budget ("unable to type-check ... in reasonable
        // time"). A dedicated ViewModifier gives it one concrete type to
        // resolve instead of six nested opaque ones.
        .modifier(RowsRecomputeModifier(
            zoomedOut: model.state.zoomedOut,
            expandedFestivals: model.state.expandedFestivals,
            lanes: lanes,
            festivals: model.state.festivals,
            showIds: model.state.timelineShows.map(\.id),
            friendShowIds: model.state.showsByFriend.mapValues { $0.map(\.id) },
            recompute: recomputeRows
        ))
        // Check-in (#174): opening the timeline takes one fix and compares it
        // against what's already known. Foreground, one-shot, nothing
        // scheduled. The permission is only ever asked for on a night there is
        // something to check into — never merely for opening the app.
        .task {
            guard await model.checkInDue() else { return }
            if model.hasLocationPermission() { model.offerCheckIn() }
            else { model.requestLocationPermission() }
        }
        .sheet(item: Binding(
            get: { model.state.checkInOffer },
            set: { if $0 == nil { model.dismissCheckInOffer() } }
        )) { gig in
            CheckInDialog(
                gig: gig,
                onCheckIn: { model.checkIn(gig.id) },
                onDismiss: { model.dismissCheckInOffer() }
            )
            .presentationDetents([.fraction(0.3)])
        }
    }

    private var wordmark: some View {
        HStack(spacing: 4) {
            Text("\u{25E6}").foregroundStyle(amber).font(.system(size: 12))
            Text("Station to Station")
                .font(.system(size: 16, design: .serif))
                .foregroundStyle(muted)
        }
    }

    private var menu: some View {
        HStack(spacing: 2) {
            // Distinguishes an empty strip from one still arriving.
            if showingLanes && model.state.lanesLoading { ProgressView().tint(faint) }
            // The converter is not gone — it lives behind search, as on Android.
            Button { nav.push(.search) } label: { Image(systemName: "magnifyingglass") }
            Button { nav.push(.friends) } label: { Image(systemName: "person.2") }
            Button { nav.push(.programme) } label: { Image(systemName: "clock") }
                .accessibilityLabel("Festival programme")
            Button { model.refreshTimeline() } label: {
                if model.state.timelineLoading { ProgressView() }
                else { Image(systemName: "arrow.clockwise") }
            }
            .disabled(model.state.timelineLoading)
            Button { nav.push(.settings) } label: { Image(systemName: "gearshape") }
        }
        .tint(faint)
    }

    private var timeline: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                header
                future
                ForEach(Array(rows.enumerated()), id: \.element.key) { i, row in
                    StationRow(
                        row: row,
                        next: rows.indices.contains(i + 1) ? rows[i + 1] : nil,
                        lanes: lanes,
                        laneWidth: laneWidth,
                        // Brightness carries one extra meaning only: brighter = most recent.
                        highlight: i == 0,
                        onTap: {
                            if row.node.isSeveral { withAnimation(.easeInOut(duration: 0.2)) { model.toggleFestival(row.key) } }
                            else if case .concert(let show) = row.node { openGig(show) }
                        }
                    )
                }
            }
            .padding(.top, 4)
        }
    }

    /// "N gigs · since YYYY", and — only when someone else is on screen — the Lane
    /// key: You in amber, each friend in their Lane colour.
    private var header: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("\(model.state.timelineShows.count) gigs" + (earliest.map { " · since \($0)" } ?? ""))
                .font(.system(size: 12)).foregroundStyle(faint)
            if laneWidth > 0 {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 14) {
                        laneKey(amber, "You")
                        ForEach(Array(lanes.enumerated()), id: \.element.id) { i, f in
                            laneKey(laneColor(i), f.name)
                        }
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 20)
        .padding(.bottom, 12)
    }

    private func laneKey(_ color: Color, _ label: String) -> some View {
        HStack(spacing: 5) {
            Rectangle().fill(color).frame(width: 3, height: 12)
            Text(label).font(.system(size: 11)).foregroundStyle(muted)
        }
    }

    /// The future edge: the Line runs on above today (#175). A gig I hold a ticket for
    /// hangs here, furthest-future first — the same order the attended rows below use,
    /// because up is always later and a plan is not an exception to that.
    ///
    /// **Simplification from Android's curtain.** Android opens this door by pulling
    /// down at the top of the list, a custom `NestedScrollConnection` with three
    /// detents (#175's `PlanningPull`). SwiftUI has no equivalent gesture primitive to
    /// port faithfully, and the capability the issue actually asks for is "a way to add
    /// a planned gig", not the drag itself — so this is a plain button that opens an
    /// alert with a text field instead. Bills (the festival-lineup door) are not ported
    /// either: the issue's four parts are setlist.fm-link, calendar, maps and time
    /// state, and a Bill is a different record with no time-state question of its own.
    private var future: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("\u{2191}  THE FUTURE")
                .font(.system(size: 11, weight: .semibold)).kerning(1.5)
                .foregroundStyle(slate)
            HStack {
                Text("the shows ahead")
                    .font(.system(size: 12)).foregroundStyle(faint)
                Spacer()
                if model.state.planningLoading { ProgressView().tint(faint) }
                Button { addingPlanned = true } label: {
                    Image(systemName: "plus.circle").foregroundStyle(slate)
                }
                .accessibilityLabel("Add a gig you're going to")
            }
            ForEach(model.state.plannedGigs) { gig in
                PlannedGigRow(setlist: gig)
                    .contentShape(Rectangle())
                    .onTapGesture { openGig(gig) }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 20)
        .padding(.bottom, 18)
        .alert("Add a gig you're going to", isPresented: $addingPlanned) {
            TextField("setlist.fm link or id", text: $plannedLink)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Button("Add") {
                model.addPlannedGig(plannedLink)
                plannedLink = ""
            }
            Button("Cancel", role: .cancel) { plannedLink = "" }
        } message: {
            Text("Paste the setlist.fm page for the show — its search can't find one that hasn't happened yet.")
        }
    }

    private func openGig(_ show: FmSetlist) {
        model.selectSetlist(show)
        nav.push(.gig)
    }

    @ViewBuilder
    private func empty(loading: Bool) -> some View {
        VStack(spacing: 12) {
            if loading {
                ProgressView().tint(amber)
                Text("Pulling your attended shows\u{2026}").foregroundStyle(muted)
            } else {
                Text("Nothing on your Line yet.")
                    .font(.system(size: 18, design: .serif)).foregroundStyle(ink)
                Text("Import the shows you\u{2019}ve marked attended on setlist.fm.")
                    .font(.subheadline).foregroundStyle(muted)
                    .multilineTextAlignment(.center)
                Button("Import my concerts") { model.refreshTimeline() }
                    .buttonStyle(.borderedProminent).tint(amber).foregroundStyle(Color.black)
            }
        }
        .padding(32)
    }
}

/// One row of the Spine. The Spine column is a fixed width at every Resolution, so
/// nothing moves when the Lanes open.
// Internal, not private: StationSnapshotTests renders a column of these directly,
// which is the only way CI can photograph the Spine without a device.
struct StationRow: View {
    let row: WovenRow
    let next: WovenRow?
    let lanes: [Friend]
    let laneWidth: CGFloat
    let highlight: Bool
    let onTap: () -> Void

    private var zoomedOut: Bool { laneWidth > 0 }
    /// Several nights on one Node — a **Section** or a **Festival** alike. The
    /// drawing asks how many, never which kind (#166).
    private var isFestival: Bool { row.node.isSeveral }
    private var nodeX: CGFloat { crossingX(row, lanes, laneWidth) }

    /// The ring's colour. A Crossing (a night I shared) is green — the meeting
    /// belongs to neither of us; otherwise amber means mine, brighter = recent.
    private var nodeColor: Color {
        if row.mine {
            if row.sharedCount > 0 { return crossed }
            return highlight ? amber : amber.opacity(0.6)
        }
        // A festival only theirs draws its ring in the innermost friend's colour;
        // two or more of them makes it a meeting.
        return row.others.count > 1 ? crossed : laneColor(max(nodeHost(row, lanes), 0))
    }

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            // The Node only. The Lines are a background of the whole row (see
            // `lines`), because a sibling here is proposed nothing taller than its
            // own ideal height — the node's — so `maxHeight: .infinity` on the
            // stroke could never reach the bottom of the row. The row's height
            // comes from `content`, and the part below the node (the text and its
            // 22pt bottom padding) drew no line at all: the gaps between rows.
            //
            // Leading, not the default centre: this frame is wider than the node,
            // and centring shifts the column by a node-size-dependent amount, so
            // the Spine lands at a different x on every row (festival 22pt, gig
            // 14pt, inner 10pt) and zig-zags down the screen.
            ZStack(alignment: .topLeading) { node }
                .frame(width: SpineWidth + laneWidth, alignment: .leading)

            content
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.trailing, 18)
                .padding(.bottom, 22)
                .padding(.leading, row.depth > 0 ? 14 : 0)
        }
        .background(alignment: .topLeading) { lines }
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
    }

    /// The Lines running through this row, drawn behind it so they span its whole
    /// height. A background is handed the row's final size, which is the one thing
    /// that makes the Spine continuous from one Node to the next.
    @ViewBuilder
    private var lines: some View {
        Group {
            // Zoomed out, the canvas owns the Lines (it has friends' Lanes to
            // draw). Zoomed in, the Spine is a single amber stroke.
            if zoomedOut {
                PeopleRails(row: row, next: next, lanes: lanes, laneWidth: laneWidth)
            } else {
                Rectangle().fill(amber.opacity(0.3)).frame(width: 2).offset(x: SpineX)
            }
        }
        .frame(width: SpineWidth + laneWidth, alignment: .leading)
    }

    /// My own Node, and a Festival's, are drawn here as a ring. A Gig only friends
    /// were at is drawn by the canvas instead, on the Line it merged onto.
    @ViewBuilder
    private var node: some View {
        if isFestival {
            let size: CGFloat = 22
            ZStack {
                // Opaque Ground behind the ring, so the Line stops at the rim
                // instead of running through the centre — a Node is a ring you
                // see the Ground through, not the line behind it. The stroke is
                // a transparent-centre border, and the Spine now spans the whole
                // row behind it. Matches Android's TimelineItem fill.
                Circle().fill(ground)
                Circle().strokeBorder(nodeColor, lineWidth: 2)
                Text(row.sharedCount > 0 ? "\(row.sharedCount)" : "\(row.node.shows.count)")
                    .font(.system(size: 10, weight: .semibold)).foregroundStyle(nodeColor)
            }
            .frame(width: size, height: size)
            .offset(x: nodeX - size / 2, y: 15 - size / 2)
        } else if row.mine {
            let size: CGFloat = row.depth > 0 ? 10 : 14
            Circle().fill(ground)
                .overlay(Circle().strokeBorder(nodeColor, lineWidth: 2))
                .frame(width: size, height: size)
                .offset(x: nodeX - size / 2, y: 13 - size / 2)
        }
    }

    @ViewBuilder
    private var content: some View {
        switch row.node {
        case .section(let shows), .festival(_, let shows):
            VStack(alignment: .leading, spacing: 3) {
                // Only a Node with an identity is called a festival. Without one this
                // is still one evening drawn as one Node — a fact we have — and the
                // eyebrow says only that (#166).
                Text(row.node.isIdentified ? "FESTIVAL" : eveningKicker(shows))
                    .font(.system(size: 10, weight: .semibold)).kerning(1.5).foregroundStyle(slate)
                Text(row.node.label).font(.system(size: 17, design: .serif)).foregroundStyle(row.mine ? ink : muted)
                Text(festivalDateRange(row.node)).font(.system(size: 13)).foregroundStyle(muted)
                festivalCounts(shows).font(.system(size: 12)).padding(.top, 4)
            }
        case .concert(let show):
            VStack(alignment: .leading, spacing: 3) {
                Text(show.readableDate() ?? "Unknown date")
                    .font(.system(size: 11, weight: .semibold)).kerning(1).foregroundStyle(faint)
                Text(show.artist?.name ?? "Unknown artist")
                    .font(.system(size: 17, design: .serif))
                    .foregroundStyle(row.mine ? ink : muted)
                Text(show.venueLine()).font(.system(size: 13)).foregroundStyle(muted)
                // The shared rule, not a local one: what the record says about its own
                // songs (#127). Said "setlist not logged" here while Android said "no
                // setlist yet" for the identical state — one line, two apps.
                Text(setlistStatus(songCount: show.performed().count))
                    .font(.system(size: 12)).foregroundStyle(faint).padding(.top, 4)
            }
        }
    }

    /// Whose is only worth saying when someone else is on screen; on My timeline a
    /// festival reads "13 gigs". A shared festival leads with what the Resolution
    /// is for: together first, each part in its own colour.
    private func festivalCounts(_ shows: [FmSetlist]) -> Text {
        let mineCount = shows.count
        let theirCount = row.theirsCount
        let together = row.sharedCount
        if theirCount == 0 && together == 0 {
            return Text("\(mineCount) gigs").foregroundColor(faint)
        }
        if !row.mine {
            return Text("\(theirCount) theirs").foregroundColor(nodeColor)
        }
        var t = Text("")
        if together > 0 {
            t = t + Text("\(together) together").foregroundColor(crossed).bold()
                + Text(" \u{00B7} ").foregroundColor(faint)
        }
        t = t + Text("\(mineCount) yours").foregroundColor(amber.opacity(0.75))
        if theirCount > 0 {
            t = t + Text(" \u{00B7} ").foregroundColor(faint)
                + Text("\(theirCount) theirs").foregroundColor(laneColor(max(nodeHost(row, lanes), 0)))
        }
        return t
    }
}

/// One night I hold a ticket for, above today (#175). Not woven into `rows` — it isn't
/// an attended show and has no Lane/Crossing geometry of its own to draw — just the
/// fact of the gig and `plannedStatus`'s answer to "how far off is it", the same words
/// `gigStatus` gives an attended row once it has passed.
private struct PlannedGigRow: View {
    let setlist: FmSetlist

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(setlist.readableDate() ?? "Unknown date")
                .font(.system(size: 11, weight: .semibold)).kerning(1).foregroundStyle(faint)
            Text(setlist.artist?.name ?? "Unknown artist")
                .font(.system(size: 15, design: .serif)).foregroundStyle(ink)
            Text(setlist.venueLine()).font(.system(size: 13)).foregroundStyle(muted)
            Text(plannedStatus(gigDate: setlist.eventDate, now: Date(), songCount: setlist.performed().count))
                .font(.system(size: 12)).foregroundStyle(slate).padding(.top, 2)
        }
        .padding(.vertical, 8)
    }
}

// --- The woven Lines, drawn as one Canvas per row (SwiftUI's spine mechanics) ---

/// One Canvas behind the row draws every Line where it runs through this row: mine
/// (amber) plus each friend's (Lane colour), bending toward the next row's node and
/// turning green wherever two or more lie on the same stretch. A Node is a ring you
/// see through, so every Line stops at its rim. Faithful to Android's PeopleRails.
private struct PeopleRails: View {
    let row: WovenRow
    let next: WovenRow?
    let lanes: [Friend]
    let laneWidth: CGFloat

    var body: some View {
        Canvas { ctx, size in draw(&ctx, size) }
    }

    /// Strokes the description and keeps no rule of its own (#116). Where a Line goes is
    /// `rowGeometry`'s answer; this decides only what a role looks like and how a bend is
    /// curved. A geometry rule that appears in here is a rule in the wrong place.
    private func draw(_ ctx: inout GraphicsContext, _ size: CGSize) {
        guard laneWidth > 0, !lanes.isEmpty else { return }
        let h = size.height
        let isFestival = row.node.isSeveral
        let nodeAt = nodeHost(row, lanes)

        for d in rowGeometry(row, next, lanes, laneWidth, h) {
            let atColor = color(d.colour)

            if d.nodeY - d.nodeR > 0 {
                var p = Path()
                p.move(to: CGPoint(x: d.x, y: 0))
                p.addLine(to: CGPoint(x: d.x, y: d.nodeY - d.nodeR))
                ctx.stroke(p, with: .color(atColor), lineWidth: d.width)
            }

            var body = Path()
            body.move(to: CGPoint(x: d.x, y: d.nodeY + d.nodeR))
            body.addLine(to: CGPoint(x: d.x, y: h - d.bendLen))
            ctx.stroke(body, with: .color(atColor), lineWidth: d.width)

            var tail = Path()
            tail.move(to: CGPoint(x: d.x, y: h - d.bendLen))
            if d.toX == d.x {
                tail.addLine(to: CGPoint(x: d.x, y: h))
            } else {
                tail.addCurve(
                    to: CGPoint(x: d.toX, y: h),
                    control1: CGPoint(x: d.x, y: h - d.bendLen * 0.45),
                    control2: CGPoint(x: d.toX, y: h - d.bendLen * 0.55)
                )
            }
            ctx.stroke(tail, with: .color(color(d.colourAhead)), lineWidth: d.widthAhead)

            // One Node per night, drawn once by the innermost Line that was there.
            // Mine and festivals draw their own ring, so this only fills the gap for
            // a Gig of theirs.
            if d.present && !row.mine && !isFestival && d.line == nodeAt {
                let joined = linesAt(row, lanes).count > 1
                let r: CGFloat = 6
                let rect = CGRect(x: d.x - r, y: d.nodeY - r, width: 2 * r, height: 2 * r)
                ctx.stroke(Path(ellipseIn: rect), with: .color(joined ? crossed : laneColor(d.line)), lineWidth: 2)
            }
        }
    }

    /// The Canvas is the only thing that knows what a role looks like — which is what
    /// lets the colour rules be asserted in a unit test with nothing rendered.
    private func color(_ role: LineColour) -> Color {
        switch role {
        case .meeting: return crossed
        case .mine(let present): return amber.opacity(present ? 0.85 : 0.4)
        case .rail(let lane): return laneColor(lane)
        case .absent: return lineCol
        }
    }
}

// --- Dates ---

private let dayMonth: DateFormatter = {
    let f = DateFormatter()
    f.locale = Locale(identifier: "en_US_POSIX")
    f.timeZone = TimeZone(secondsFromGMT: 0)
    f.dateFormat = "d MMM"
    return f
}()

private let dayMonthYear: DateFormatter = {
    let f = DateFormatter()
    f.locale = Locale(identifier: "en_US_POSIX")
    f.timeZone = TimeZone(secondsFromGMT: 0)
    f.dateFormat = "d MMM yyyy"
    return f
}()

func festivalDateRange(_ node: TimelineNode) -> String {
    // A **Festival** says its *own* range where the source published one — "Tons of
    // Rock 2026" is four days whether or not I went to four — and falls back to the
    // nights on the node when it does not (#166).
    var dates: [Date] = []
    if case .festival(let identity, _) = node {
        dates = [identity.rangeFrom, identity.rangeTo].compactMap { $0.flatMap(parseFmDate) }
    }
    if dates.isEmpty { dates = node.shows.compactMap { $0.localDate() } }
    dates.sort()
    guard let first = dates.first, let last = dates.last else { return "" }
    if first == last { return dayMonthYear.string(from: first) }
    return "\(dayMonth.string(from: first)) \u{2013} \(dayMonthYear.string(from: last))"
}

/// "Are you here?" — the one thing a check-in asks (#174). Shown only when a
/// fix already put the phone at the venue on the night, so it states what it
/// thinks and offers the two honest answers.
private struct CheckInDialog: View {
    let gig: FmSetlist
    let onCheckIn: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Are you here?")
                .font(.system(size: 19, design: .serif)).foregroundStyle(ink)
            Text("\(gig.artist?.name ?? "This show") at \(gig.venue?.name ?? "the venue"), tonight.")
                .font(.system(size: 13)).foregroundStyle(muted)
            Text("Checking in records that you were at it — on this phone, nowhere else.")
                .font(.system(size: 11)).foregroundStyle(faint)
            Spacer(minLength: 12)
            HStack {
                Spacer()
                Button("Not now") { onDismiss() }.foregroundStyle(faint)
                Button("Check in") { onCheckIn() }.foregroundStyle(amber)
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(ground.ignoresSafeArea())
    }
}
