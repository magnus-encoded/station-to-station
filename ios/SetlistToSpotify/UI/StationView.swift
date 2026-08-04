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

// --- Lane geometry (rendering side; the model side lives in Timeline.swift) ---

private func laneXf(_ offset: CGFloat, _ step: CGFloat) -> CGFloat { SpineX + step * (offset + 1) }

/// Which Lines were at a row: Spine for me, plus a Lane index per friend present.
private func linesAt(_ row: WovenRow, _ lanes: [Friend]) -> [Int] {
    var out: [Int] = []
    if row.mine { out.append(Spine) }
    for (i, f) in lanes.enumerated() where row.others.contains(where: { $0.setlistfm == f.setlistfm }) {
        out.append(i)
    }
    return out
}

/// Where the row's Node sits, as a Lane offset. INNERMOST: the innermost Line that
/// was there — mine (Spine = -1, the smallest) whenever I am one of them, so a
/// night we shared happens *on* my Spine and theirs travels to it.
private func nodeOffset(_ row: WovenRow, _ lanes: [Friend]) -> CGFloat {
    CGFloat(linesAt(row, lanes).min() ?? Spine)
}

/// Where a Line is drawn at a row: on the Node if it was there, otherwise its own Lane.
private func lineDrawnOffset(_ row: WovenRow?, _ line: Int, _ lanes: [Friend]) -> CGFloat {
    guard let row else { return CGFloat(line) }
    let there = line == Spine
        ? row.mine
        : (lanes.indices.contains(line) && row.others.contains { $0.setlistfm == lanes[line].setlistfm })
    return there ? nodeOffset(row, lanes) : CGFloat(line)
}

/// Where a row's Node sits in points. My Line never moves — a shared night happens
/// on my Spine and theirs comes to meet it.
private func crossingX(_ row: WovenRow, _ lanes: [Friend], _ laneWidth: CGFloat) -> CGFloat {
    let offset = nodeOffset(row, lanes)
    if laneWidth <= 0 || offset == CGFloat(Spine) { return SpineX }
    let step = laneStep(lanes.count)
    let open = min(max(laneWidth / stripWidth(lanes.count), 0), 1)
    return SpineX + (laneXf(offset, step) - SpineX) * open
}

struct StationView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var nav: Nav

    /// In-progress pinch, as a fraction of open (0...1). Nil once the gesture
    /// has ended and settled into `model.state.zoomedOut`. View-local: it is
    /// visual feedback for a gesture in flight, not app state to persist.
    @State private var dragFraction: CGFloat?

    private var lanes: [Friend] { model.state.friends }

    /// Open enough to show Lanes, whether settled or mid-pinch.
    private var showingLanes: Bool { model.state.zoomedOut || (dragFraction ?? 0) > 0 }

    private var rows: [WovenRow] {
        let s = model.state
        return weaveTimelines(
            mine: s.timelineShows,
            festivalNames: s.festivalNames,
            friends: showingLanes ? lanes : [],
            theirs: showingLanes ? s.showsByFriend : [:],
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
            if s.timelineShows.isEmpty {
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
        // "act on this level" gesture, people axis. Android's 90dp threshold; the
        // vertical bound is what keeps a diagonal scroll from opening a screen.
        .simultaneousGesture(
            DragGesture(minimumDistance: 20)
                .onEnded { drag in
                    if drag.translation.width <= -90 && abs(drag.translation.height) < 60 {
                        nav.push(.exchange)
                    }
                }
        )
        .onAppear { model.loadTimeline() }
        // Fetch friends' Lanes when the strip opens, not at launch — a
        // Resolution never opened shouldn't spend setlist.fm's budget.
        .onChange(of: model.state.zoomedOut) { open in
            if open { model.loadFriendTimelines() }
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
                            if row.node.isFestival { withAnimation(.easeInOut(duration: 0.2)) { model.toggleFestival(row.key) } }
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

    /// The future edge: the Line runs on above today. Planned Gigs (#31, not yet
    /// imported on iOS) would hang here as slate hollow rings — never amber.
    private var future: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("\u{2191}  THE FUTURE")
                .font(.system(size: 11, weight: .semibold)).kerning(1.5)
                .foregroundStyle(slate)
            Text("the shows ahead")
                .font(.system(size: 12)).foregroundStyle(faint)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 20)
        .padding(.bottom, 18)
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
    private var isFestival: Bool { row.node.isFestival }
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
                Circle().strokeBorder(nodeColor, lineWidth: 2)
                Text(row.sharedCount > 0 ? "\(row.sharedCount)" : "\(row.node.shows.count)")
                    .font(.system(size: 10, weight: .semibold)).foregroundStyle(nodeColor)
            }
            .frame(width: size, height: size)
            .offset(x: nodeX - size / 2, y: 15 - size / 2)
        } else if row.mine {
            let size: CGFloat = row.depth > 0 ? 10 : 14
            Circle().strokeBorder(nodeColor, lineWidth: 2)
                .frame(width: size, height: size)
                .offset(x: nodeX - size / 2, y: 13 - size / 2)
        }
    }

    @ViewBuilder
    private var content: some View {
        switch row.node {
        case .festival(let name, let shows):
            VStack(alignment: .leading, spacing: 3) {
                Text("FESTIVAL")
                    .font(.system(size: 10, weight: .semibold)).kerning(1.5).foregroundStyle(slate)
                Text(name).font(.system(size: 17, design: .serif)).foregroundStyle(ink)
                Text(festivalDateRange(shows)).font(.system(size: 13)).foregroundStyle(muted)
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
                Text(gigStatus(show)).font(.system(size: 12)).foregroundStyle(faint).padding(.top, 4)
            }
        }
    }

    private func gigStatus(_ show: FmSetlist) -> String {
        let n = show.performed().count
        return n > 0 ? "\(n) songs" : "setlist not logged"
    }

    /// Whose is only worth saying when someone else is on screen; on My timeline a
    /// festival reads "13 gigs". A shared festival leads with what the Resolution
    /// is for: together first, each part in its own colour.
    private func festivalCounts(_ shows: [FmSetlist]) -> Text {
        let mineCount = shows.count
        let theirCount = row.showsHereByFriends.count
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

    private let edgeBend: CGFloat = 56
    private let lineWidth: CGFloat = 2
    private let perPerson: CGFloat = 1.2

    var body: some View {
        Canvas { ctx, size in draw(&ctx, size) }
    }

    private func draw(_ ctx: inout GraphicsContext, _ size: CGSize) {
        guard laneWidth > 0, !lanes.isEmpty else { return }
        let spineX = SpineX + 1
        let step = laneStep(lanes.count)
        let open = min(max(laneWidth / stripWidth(lanes.count), 0), 1) * CGFloat(lanes.count)
        let isFestival = row.node.isFestival
        let nodeY: CGFloat = isFestival ? 15 : 13
        let h = size.height
        let nodeR: CGFloat = isFestival ? 11 : (row.depth > 0 ? 5 : 7)
        let lines = [Spine] + Array(0..<lanes.count)

        func slideOf(_ line: Int) -> CGFloat { line == Spine ? 1 : min(max(open - CGFloat(line), 0), 1) }
        func xOf(_ offset: CGFloat, _ line: Int) -> CGFloat {
            spineX + (laneXf(offset, step) - spineX) * slideOf(line)
        }
        func thereAt(_ r: WovenRow, _ line: Int) -> Bool {
            line == Spine ? r.mine
                : (lanes.indices.contains(line) && r.others.contains { $0.setlistfm == lanes[line].setlistfm })
        }
        // How many Lines lie on this one where it runs — merged Lines are one Line,
        // so weight is what says how many. Green when more than one.
        func peopleAt(_ r: WovenRow?, _ line: Int) -> Int {
            guard let r else { return 1 }
            let here = lineDrawnOffset(r, line, lanes)
            return max(lines.filter { lineDrawnOffset(r, $0, lanes) == here && thereAt(r, $0) }.count, 1)
        }
        func peopleAlong(_ to: WovenRow?, _ line: Int) -> Int {
            guard let to else { return peopleAt(row, line) }
            let a = lineDrawnOffset(row, line, lanes)
            let b = lineDrawnOffset(to, line, lanes)
            return max(lines.filter { lineDrawnOffset(row, $0, lanes) == a && lineDrawnOffset(to, $0, lanes) == b }.count, 1)
        }
        func paint(_ people: Int, _ present: Bool, _ line: Int) -> (Color, CGFloat) {
            let colour: Color
            if people > 1 { colour = crossed }
            else if line == Spine { colour = amber.opacity(present ? 0.85 : 0.4) }
            else if present { colour = laneColor(line) }
            else { colour = lineCol }
            return (colour, lineWidth + perPerson * CGFloat(people - 1))
        }

        let nodeAt = nodeOffset(row, lanes)

        for line in lines where slideOf(line) > 0 {
            let x = xOf(lineDrawnOffset(row, line, lanes), line)
            let toX = next == nil ? x : xOf(lineDrawnOffset(next, line, lanes), line)
            let here = thereAt(row, line)
            let gap = here ? nodeR : 0

            let (atColor, atWidth) = paint(peopleAt(row, line), here, line)

            if nodeY - gap > 0 {
                var p = Path(); p.move(to: CGPoint(x: x, y: 0)); p.addLine(to: CGPoint(x: x, y: nodeY - gap))
                ctx.stroke(p, with: .color(atColor), lineWidth: atWidth)
            }

            let bendLen = max(min((h - nodeY - gap) * 0.8, edgeBend), 0)

            var body = Path()
            body.move(to: CGPoint(x: x, y: nodeY + gap))
            body.addLine(to: CGPoint(x: x, y: h - bendLen))
            ctx.stroke(body, with: .color(atColor), lineWidth: atWidth)

            let (leaveColor, leaveWidth) = paint(peopleAlong(next, line), here, line)
            var tail = Path()
            tail.move(to: CGPoint(x: x, y: h - bendLen))
            if toX == x {
                tail.addLine(to: CGPoint(x: x, y: h))
            } else {
                tail.addCurve(
                    to: CGPoint(x: toX, y: h),
                    control1: CGPoint(x: x, y: h - bendLen * 0.45),
                    control2: CGPoint(x: toX, y: h - bendLen * 0.55)
                )
            }
            ctx.stroke(tail, with: .color(leaveColor), lineWidth: leaveWidth)

            // One Node per night, drawn once by the innermost Line that was there.
            // Mine and festivals draw their own ring, so this only fills the gap for
            // a Gig of theirs.
            let drawsNode = here && !row.mine && !isFestival && line == (linesAt(row, lanes).min() ?? Spine)
            if drawsNode {
                let nx = xOf(nodeAt, line)
                let joined = linesAt(row, lanes).count > 1
                let r: CGFloat = 6
                let rect = CGRect(x: nx - r, y: nodeY - r, width: 2 * r, height: 2 * r)
                ctx.stroke(Path(ellipseIn: rect), with: .color(joined ? crossed : laneColor(line)), lineWidth: 2)
            }
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

func festivalDateRange(_ shows: [FmSetlist]) -> String {
    let dates = shows.compactMap { $0.localDate() }.sorted()
    guard let first = dates.first, let last = dates.last else { return "" }
    if first == last { return dayMonthYear.string(from: first) }
    return "\(dayMonth.string(from: first)) \u{2013} \(dayMonthYear.string(from: last))"
}
