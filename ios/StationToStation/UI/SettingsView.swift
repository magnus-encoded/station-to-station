import CoreLocation
import EventKit
import Photos
import SwiftUI
import UIKit

// Settings is the **Field** (#563): what feeds My timeline, drawn as a picture you move
// around in, rather than a form of keys grouped by vendor. What each service *is* and
// whether it is **Lit** is `serviceGraph`'s; where it goes is `fieldLayout`'s. This file
// only draws those two and moves today's controls into the sheet a tile opens. Term for
// term with Android's `SettingsScreen.kt`.

// Each file keeps its own copy of the palette it needs.
private let ground = Color(red: 0x0E / 255, green: 0x0B / 255, blue: 0x14 / 255)
private let raised = Color(red: 0x17 / 255, green: 0x12 / 255, blue: 0x1F / 255)
private let ink = Color(red: 0xED / 255, green: 0xE9 / 255, blue: 0xF2 / 255)
private let muted = Color(red: 0x8B / 255, green: 0x82 / 255, blue: 0x99 / 255)
private let faint = Color(red: 0x5A / 255, green: 0x53 / 255, blue: 0x68 / 255)
private let lineCol = Color(red: 0x2E / 255, green: 0x27 / 255, blue: 0x40 / 255)
private let amber = Color(red: 0xE7 / 255, green: 0xB2 / 255, blue: 0x4C / 255)
private let onAmber = Color(red: 0x24 / 255, green: 0x1A / 255, blue: 0x08 / 255)
private let timelineLitFill = Color(red: 0x2A / 255, green: 0x22 / 255, blue: 0x15 / 255)
private let slate = Color(red: 0x6F / 255, green: 0x80 / 255, blue: 0x9D / 255)

/// What the phone itself has granted. Re-read whenever the app comes back to the front:
/// it changes in the system's Settings app, and that is where "Open app permissions" goes.
private struct DeviceAccess: Equatable {
    var photos: PhotoAccess = .none
    var calendar = false
    var location = false

    static func read() -> DeviceAccess {
        let photoStatus = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        let photos: PhotoAccess
        switch photoStatus {
        case .authorized: photos = .full
        // "Selected Photos": some access, which still lights the tile.
        case .limited: photos = .partial
        default: photos = .none
        }

        // iOS 17 split calendar access into full and write-only. Either is enough to
        // add a gig, which is all this app asks a calendar for.
        let calendarStatus = EKEventStore.authorizationStatus(for: .event)
        var calendar = calendarStatus == .authorized
        if #available(iOS 17.0, *) {
            calendar = calendar || calendarStatus == .fullAccess || calendarStatus == .writeOnly
        }

        let locationStatus = CLLocationManager().authorizationStatus
        let location = locationStatus == .authorizedWhenInUse || locationStatus == .authorizedAlways

        return DeviceAccess(photos: photos, calendar: calendar, location: location)
    }
}

private func servicesAsKnown(_ s: UiState, _ access: DeviceAccess) -> ServicesAsKnown {
    ServicesAsKnown(
        setlistFmKeyAvailable: s.setlistFmReady,
        setlistFmOwnKey: !s.setlistFmApiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
        setlistFmSharedQuotaSpent: s.setlistFmSharedQuotaSpent,
        clashfinderUser: s.clashfinderUser,
        clashfinderKey: !s.clashfinderPrivateKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
        spotifyConnected: s.spotifyConnected,
        spotifyScope: s.grantedScope,
        knownTimelines: s.friends.count,
        photos: access.photos,
        calendar: access.calendar,
        location: access.location,
        gigActive: s.gossipActiveGig != nil
    )
}

/// The open sheet, by id rather than by node: the node is rebuilt every time state
/// moves, and the open sheet should follow it (a Save that lights setlist.fm turns its
/// status amber).
private struct OpenTile: Identifiable {
    let id: String
}

struct SettingsView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var nav: Nav
    @Environment(\.scenePhase) private var scenePhase
    @State private var access = DeviceAccess.read()
    @State private var open: OpenTile?
    /// Set by the Spotify sheet's Log in, acted on once the sheet has gone: the browser
    /// takes over from there, and a banner if the login cannot even start should not
    /// land behind a sheet.
    @State private var spotifyLoginPending = false

    var body: some View {
        let graph = serviceGraph(servicesAsKnown(model.state, access))
        VStack(spacing: 0) {
            SettingsField(graph: graph, onBack: { nav.pop() }, onOpen: { open = OpenTile(id: $0) })
            // Outside the Field, so a pan never carries it away. Moving to a new phone
            // lives here rather than on the Exchange screen: that screen is for meeting
            // *people*, and this is the same person's second device (#142).
            HStack {
                Button { nav.push(.handover) } label: {
                    Text("Move to a new phone").foregroundColor(ink)
                }
                Spacer()
                Text(buildLabel)
                    .font(.system(size: 11))
                    .foregroundColor(faint)
                    .lineLimit(1)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
        }
        .background(ground.ignoresSafeArea())
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(ground, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        // Coming back from the system's Settings app is a return to the foreground, and
        // the whole point of the trip was to change what this reads.
        .onAppear { access = DeviceAccess.read() }
        .onChange(of: scenePhase) { phase in
            if phase == .active { access = DeviceAccess.read() }
        }
        .sheet(item: $open, onDismiss: {
            guard spotifyLoginPending else { return }
            spotifyLoginPending = false
            model.loginSpotify()
        }) { tile in
            ServiceSheet(
                id: tile.id,
                graph: graph,
                onSpotifyLogin: { clientId in
                    model.saveSettings(apiKey: model.state.setlistFmApiKey, clientId: clientId)
                    spotifyLoginPending = true
                    open = nil
                }
            )
            .environmentObject(model)
        }
    }

    /// iOS has no git sha baked in, so the build is the version the bundle carries.
    private var buildLabel: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        return "Build \(version)"
    }
}

// ---------------------------------------------------------------------------------
// The Field itself: one canvas for strips and lines, tiles laid over it, all in field
// points under one transform. Drag pans, pinch zooms, double-tap puts it back.

private enum FieldDrag { case move, backOut }

private struct SettingsField: View {
    let graph: ServiceGraph
    let onBack: () -> Void
    let onOpen: (String) -> Void

    /// Nil until the Field is first moved. Kept across state changes (a Save must not
    /// snap the view back), and dropped when the viewport itself changes, as on rotation.
    @State private var view: FieldView?
    @State private var viewSize: CGSize = .zero
    @State private var drag: FieldDrag?
    @State private var lastTranslation: CGSize = .zero
    @State private var pinchStart: FieldView?

    var body: some View {
        GeometryReader { geo in
            let size = geo.size
            let layout = fieldLayout(graph, viewportW: size.width, viewportH: size.height)
            let v = current(layout, size)
            fieldContent(layout)
                .frame(width: layout.width, height: layout.height, alignment: .topLeading)
                .scaleEffect(v.zoom, anchor: .topLeading)
                .offset(x: v.offsetX, y: v.offsetY)
                .frame(width: size.width, height: size.height, alignment: .topLeading)
                .clipped()
                .contentShape(Rectangle())
                .onTapGesture(count: 2) { set(layout.defaultView, size) }
                .simultaneousGesture(dragGesture(layout, size))
                .modifier(PinchModifier(
                    changed: { factor, anchor in pinchChanged(layout, size, factor, anchor) },
                    ended: { pinchStart = nil }
                ))
        }
    }

    private func current(_ layout: FieldLayout, _ size: CGSize) -> FieldView {
        if let view, viewSize == size { return view }
        return layout.defaultView
    }

    private func set(_ next: FieldView, _ size: CGSize) {
        view = next
        viewSize = size
    }

    /// Pan, and back out. A one-finger drag to the right that *starts* with the Field at
    /// its left edge is the way back, the same as `swipeRight` everywhere else; any
    /// other drag moves the Field. The choice is made once, when the drag is first
    /// recognised, so a gesture is never both — a pan that happens to reach the edge
    /// does not then go back.
    private func dragGesture(_ layout: FieldLayout, _ size: CGSize) -> some Gesture {
        DragGesture(minimumDistance: 10)
            .onChanged { value in
                let t = value.translation
                let now = current(layout, size)
                let mode: FieldDrag
                if let drag {
                    mode = drag
                } else {
                    let fromEdge = layout.atLeftEdge(now)
                    mode = pinchStart == nil && fromEdge && t.width > abs(t.height) ? .backOut : .move
                    drag = mode
                    lastTranslation = t
                }
                // While a pinch is on, it moves the Field; the one finger a drag tracks
                // would only fight it.
                if mode == .move && pinchStart == nil {
                    set(layout.pan(now, dx: t.width - lastTranslation.width, dy: t.height - lastTranslation.height), size)
                }
                lastTranslation = t
            }
            .onEnded { value in
                if drag == .backOut && value.translation.width >= FieldDp.backOutThreshold { onBack() }
                drag = nil
                lastTranslation = .zero
            }
    }

    private func pinchChanged(_ layout: FieldLayout, _ size: CGSize, _ factor: CGFloat, _ anchor: CGPoint?) {
        let start: FieldView
        if let pinchStart {
            start = pinchStart
        } else {
            start = current(layout, size)
            pinchStart = start
            // A second finger makes it a pinch, never a way back.
            if drag == .backOut { drag = .move }
        }
        let c = anchor ?? CGPoint(x: size.width / 2, y: size.height / 2)
        set(layout.zoomAbout(start, cx: c.x, cy: c.y, factor: factor), size)
    }

    @ViewBuilder
    private func fieldContent(_ layout: FieldLayout) -> some View {
        let order = readingOrder(layout.strips)
        ZStack(alignment: .topLeading) {
            Canvas { ctx, _ in drawField(&ctx, layout) }
                .frame(width: layout.width, height: layout.height)
                .accessibilityHidden(true)
            // By index: "This phone" is a strip on both sides, so a title is no id.
            ForEach(layout.strips.indices, id: \.self) { i in
                stripTitle(layout.strips[i], priority: order["strip:\(i)"] ?? 0)
            }
            ForEach(layout.tiles, id: \.id) { tile in
                if let node = graph.node(tile.id) {
                    ServiceTileView(node: node) { onOpen(node.id) }
                        .frame(width: FieldDp.columnWidth)
                        .offset(x: tile.center.x - FieldDp.columnWidth / 2, y: tile.rect.top)
                        .accessibilitySortPriority(order[node.id] ?? 0)
                }
            }
            TimelineBox(lit: graph.timelineLit)
                .frame(width: layout.timeline.width, height: layout.timeline.height)
                .offset(x: layout.timeline.left, y: layout.timeline.top)
                .accessibilitySortPriority(order["timeline"] ?? 0)
        }
        // VoiceOver's order: strip by strip, left to right, then My timeline, then the
        // Alcoves — as the Field reads, not as the rows happen to fall on screen.
        .accessibilityElement(children: .contain)
    }

    /// Higher sorts first, so the first thing read gets the highest number.
    private func readingOrder(_ strips: [FieldStrip]) -> [String: Double] {
        var keys: [String] = []
        for (i, s) in strips.enumerated() where s.role == .input {
            keys.append("strip:\(i)")
            keys.append(contentsOf: s.ids)
        }
        keys.append("timeline")
        for (i, s) in strips.enumerated() where s.role == .alcove {
            keys.append("strip:\(i)")
            keys.append(contentsOf: s.ids)
        }
        var order: [String: Double] = [:]
        for (i, key) in keys.enumerated() { order[key] = Double(keys.count - i) }
        return order
    }

    private func stripTitle(_ strip: FieldStrip, priority: Double) -> some View {
        Text(strip.strip.title.uppercased())
            .font(.system(size: 10))
            .kerning(1.2)
            .foregroundColor(faint)
            .fixedSize()
            .offset(x: strip.rect.left + 12, y: strip.rect.top + 9)
            .accessibilityAddTraits(.isHeader)
            .accessibilitySortPriority(priority)
    }
}

/// Pinch about the fingers where the OS says where they are (iOS 17's `MagnifyGesture`
/// knows where the pinch started); iOS 16's `MagnificationGesture` does not, so there
/// it is about the middle of the screen.
private struct PinchModifier: ViewModifier {
    let changed: (CGFloat, CGPoint?) -> Void
    let ended: () -> Void

    func body(content: Content) -> some View {
        if #available(iOS 17.0, *) {
            content.simultaneousGesture(
                MagnifyGesture()
                    .onChanged { value in changed(value.magnification, value.startLocation) }
                    .onEnded { _ in ended() }
            )
        } else {
            content.simultaneousGesture(
                MagnificationGesture()
                    .onChanged { scale in changed(scale, nil) }
                    .onEnded { _ in ended() }
            )
        }
    }
}

private func point(_ p: FieldPoint) -> CGPoint { CGPoint(x: p.x, y: p.y) }

private func drawStrips(_ ctx: inout GraphicsContext, _ layout: FieldLayout) {
    for s in layout.strips {
        let rect = CGRect(x: s.rect.left, y: s.rect.top, width: s.rect.width, height: s.rect.height)
        let shape = Path(roundedRect: rect, cornerRadius: 14)
        ctx.fill(shape, with: .color(raised))
        ctx.stroke(shape, with: .color(lineCol), lineWidth: 1)
    }
}

/// Lit is a solid amber line; unlit is a thin dashed one, there but not carrying anything.
private func drawLine(_ ctx: inout GraphicsContext, _ path: Path, lit: Bool) {
    ctx.stroke(
        path,
        with: .color(lit ? amber : faint),
        style: lit ? StrokeStyle(lineWidth: 2) : StrokeStyle(lineWidth: 1, dash: [12, 10])
    )
}

/// A lane's corners with each bend rounded, then the curve on to the join (`curvePoints`'s).
private func lanePath(_ points: [CGPoint], _ end: CGPoint) -> Path {
    var path = Path()
    guard let first = points.first, let last = points.last else { return path }
    let r = FieldDp.cornerRadius
    path.move(to: first)
    if points.count > 2 {
        for i in 1..<(points.count - 1) {
            let prev = points[i - 1]
            let at = points[i]
            let next = points[i + 1]
            let inLen = ((at.x - prev.x) * (at.x - prev.x) + (at.y - prev.y) * (at.y - prev.y)).squareRoot()
            let outLen = ((next.x - at.x) * (next.x - at.x) + (next.y - at.y) * (next.y - at.y)).squareRoot()
            guard inLen > 0, outLen > 0 else {
                path.addLine(to: at)
                continue
            }
            let a = CGPoint(x: at.x - (at.x - prev.x) / inLen * r, y: at.y - (at.y - prev.y) / inLen * r)
            let b = CGPoint(x: at.x + (next.x - at.x) / outLen * r, y: at.y + (next.y - at.y) / outLen * r)
            path.addLine(to: a)
            path.addQuadCurve(to: b, control: at)
        }
    }
    path.addLine(to: last)
    let midX = (last.x + end.x) / 2
    path.addCurve(to: end, control1: CGPoint(x: midX, y: last.y), control2: CGPoint(x: midX, y: end.y))
    return path
}

private extension SettingsField {
    func drawField(_ ctx: inout GraphicsContext, _ layout: FieldLayout) {
        drawStrips(&ctx, layout)
        // The trunk runs under the timeline box, which is opaque.
        var trunk = Path()
        trunk.move(to: point(layout.join))
        trunk.addLine(to: point(layout.split))
        drawLine(&ctx, trunk, lit: graph.timelineLit)
        for lane in layout.lanes {
            drawLine(&ctx, lanePath(lane.corners.map(point), point(lane.join)), lit: graph.node(lane.id)?.lit == true)
        }
        for line in layout.alcoveLines {
            let from = point(line.from)
            let to = point(line.to)
            let mid = (from.x + to.x) / 2
            var path = Path()
            path.move(to: from)
            path.addCurve(to: to, control1: CGPoint(x: mid, y: from.y), control2: CGPoint(x: mid, y: to.y))
            drawLine(&ctx, path, lit: graph.alcoveLineLit(line.id))
        }
    }
}

// ---------------------------------------------------------------------------------
// Tiles and the box in the middle.

private struct ServiceTileView: View {
    let node: ServiceNode
    let onOpen: () -> Void

    var body: some View {
        Button(action: onOpen) {
            VStack(spacing: 4) {
                ZStack {
                    // Pure black inside the ring: the one background Spotify allows its
                    // green on, and the most contrast for every other mark.
                    RoundedRectangle(cornerRadius: 11)
                        .fill(Color.black)
                    ServiceLogo(node: node)
                        .frame(width: FieldDp.tileSize - 6, height: FieldDp.tileSize - 6)
                        .clipShape(RoundedRectangle(cornerRadius: 11))
                }
                .frame(width: FieldDp.tileSize - 6, height: FieldDp.tileSize - 6)
                .padding(3)
                .overlay(
                    RoundedRectangle(cornerRadius: 14)
                        .stroke(node.lit ? amber : faint, lineWidth: node.lit ? 2 : 1)
                )
                Text(node.name)
                    .font(.system(size: 11))
                    .foregroundColor(node.lit ? ink : muted)
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
            .frame(width: FieldDp.columnWidth)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        // One element per tile, saying everything the ring and the label say.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(node.spoken)
        .accessibilityAddTraits(.isButton)
    }
}

/// Brand marks are never dimmed or tinted, lit or not — both Spotify's and MusicBrainz's
/// guidelines forbid it. An unlit brand says so with its ring, its label and its line.
private struct ServiceLogo: View {
    let node: ServiceNode

    var body: some View {
        switch node.id {
        // App icons that are their own tile: they fill it.
        case "setlistfm":
            Image("settings_setlistfm").resizable().scaledToFill()
        case "clashfinder":
            Image("settings_clashfinder").resizable().scaledToFill()
        // Spotify asks for clear space of half the mark's height around it.
        case "spotify":
            Image("settings_spotify").renderingMode(.original).resizable().scaledToFit()
                .frame(width: 25, height: 25)
        case "musicbrainz":
            Image("settings_musicbrainz").renderingMode(.original).resizable().scaledToFit()
                .frame(height: 28)
        default:
            symbol
        }
    }

    @ViewBuilder
    private var symbol: some View {
        let name: String? = {
            switch node.id {
            case "photos": return "photo.on.rectangle"
            case "tickets": return "ticket"
            case "location": return "location"
            case "contacts": return "person.2"
            case "gossip": return "dot.radiowaves.left.and.right"
            case "calendar": return "calendar"
            default: return nil
            }
        }()
        if let name {
            Image(systemName: name)
                .font(.system(size: 22))
                .frame(width: 26, height: 26)
                .foregroundColor(node.lit ? ink : muted)
        } else {
            Text(String(node.name.prefix(1)))
                .foregroundColor(node.lit ? ink : muted)
        }
    }
}

/// No words: the app's own mark, amber-lit when anything feeds it.
private struct TimelineBox: View {
    let lit: Bool

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 14)
                .fill(lit ? timelineLitFill : raised)
            RoundedRectangle(cornerRadius: 14)
                .stroke(lit ? amber : faint, lineWidth: 2)
            AppMark()
                .aspectRatio(1, contentMode: .fit)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("My timeline, \(lit ? "lit" : "not lit")")
    }
}

/// The launcher mark, drawn rather than shipped as an image: its 108-unit adaptive-icon
/// grid, scaled to whatever square it is given. The same shapes as Android's
/// `ic_launcher_foreground`.
private struct AppMark: View {
    var body: some View {
        Canvas { ctx, size in
            let k = min(size.width, size.height) / 108
            let dx = (size.width - 108 * k) / 2
            let dy = (size.height - 108 * k) / 2
            func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: dx + x * k, y: dy + y * k) }
            let stroke = StrokeStyle(lineWidth: 4 * k, lineCap: .round)
            let segments: [(CGFloat, CGFloat, Color)] = [(22, 28, slate), (48, 60, amber), (80, 86, amber)]
            for (y0, y1, color) in segments {
                var path = Path()
                path.move(to: p(54, y0))
                path.addLine(to: p(54, y1))
                ctx.stroke(path, with: .color(color), style: stroke)
            }
            let ring = Path(ellipseIn: CGRect(origin: p(44, 28), size: CGSize(width: 20 * k, height: 20 * k)))
            ctx.stroke(ring, with: .color(slate), lineWidth: 4 * k)
            let dot = Path(ellipseIn: CGRect(origin: p(44, 60), size: CGSize(width: 20 * k, height: 20 * k)))
            ctx.fill(dot, with: .color(amber))
        }
        .accessibilityHidden(true)
    }
}

// ---------------------------------------------------------------------------------
// The sheet a tile opens: what the service gives, how to light it, and the controls
// that used to be the whole of Settings. They call the model exactly as before.

private struct ServiceSheet: View {
    @EnvironmentObject var model: AppModel
    let id: String
    let graph: ServiceGraph
    let onSpotifyLogin: (String) -> Void

    var body: some View {
        ScrollView {
            if let node = graph.node(id) {
                VStack(alignment: .leading, spacing: 0) {
                    HStack(spacing: 8) {
                        Text(node.name)
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundColor(ink)
                            .accessibilityAddTraits(.isHeader)
                        if node.experimental {
                            Text("Experimental")
                                .font(.system(size: 11))
                                .foregroundColor(muted)
                        }
                    }
                    Text(node.status)
                        .font(.system(size: 13))
                        .foregroundColor(node.lit ? amber : faint)
                        .padding(.bottom, 12)
                        .spokenOnChange(node.status)
                    ForEach(node.unlocks, id: \.self) { unlock in
                        Text((node.lit ? "✓ " : "· ") + unlock)
                            .font(.system(size: 14))
                            .foregroundColor(node.lit ? ink : muted)
                    }
                    if !node.lit, let next = node.nextStep {
                        Text(next)
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundColor(ink)
                            .padding(.top, 12)
                    }
                    ServiceControls(node: node, onSpotifyLogin: onSpotifyLogin)
                        .padding(.top, 16)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(20)
            }
        }
        .background(raised.ignoresSafeArea())
        .presentationDetents([.medium, .large])
    }
}

private struct ServiceControls: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.openURL) private var openURL
    let node: ServiceNode
    let onSpotifyLogin: (String) -> Void

    @State private var apiKey = ""
    @State private var clientId = ""
    @State private var clashfinderUser = ""
    @State private var clashfinderPrivateKey = ""

    var body: some View {
        let s = model.state
        VStack(alignment: .leading, spacing: 8) {
            controls(s)
        }
        .onAppear {
            apiKey = s.setlistFmApiKey
            clientId = s.spotifyClientId
            clashfinderUser = s.clashfinderUser
            clashfinderPrivateKey = s.clashfinderPrivateKey
        }
    }

    @ViewBuilder
    private func controls(_ s: UiState) -> some View {
        switch node.id {
        case "setlistfm":
            // While the shared key is spent, the sheet leads with that (#457): someone who
            // followed the nudge here came for one thing.
            if s.setlistFmSharedQuotaSpent {
                SheetBody(text: sharedQuotaMessage)
            } else if s.bundledSetlistFmKey {
                SheetBody(text: "The setlist.fm API has no user login — to load your attended concerts, "
                    + "just enter your setlist.fm username on the My concerts tab.")
            }
            SheetLink(title: "Request one at setlist.fm/settings/api", url: "https://www.setlist.fm/settings/api")
            // The field is here whether or not a key is bundled. Hiding it when one was
            // meant "the bundled key cannot be replaced without a rebuild", which is the
            // opposite of what a bring-your-own-source app should offer — and it is the
            // only way out if the bundled key is ever revoked or rate-limited.
            KeyField(label: "setlist.fm API key", text: $apiKey)
            AmberButton(title: "Save") {
                model.saveSettings(apiKey: apiKey, clientId: s.spotifyClientId)
            }
            .padding(.top, 4)

        // No bundled fallback here, unlike setlist.fm: one account shared by every install
        // would put all of this app's traffic on a single credential against a host that
        // runs active bot protection.
        case "clashfinder":
            if !node.lit {
                SheetBody(text: "clashfinder needs a free account of your own. Register, then copy the "
                    + "private key off your account page. It is not your password.")
            }
            SheetLink(title: "Register at clashfinder.com", url: "https://clashfinder.com/m/account")
            KeyField(label: "clashfinder username", text: $clashfinderUser)
            KeyField(label: "clashfinder private key", text: $clashfinderPrivateKey, secure: true)
            AmberButton(
                title: "Save clashfinder account",
                enabled: !clashfinderUser.trimmingCharacters(in: .whitespaces).isEmpty
                    && !clashfinderPrivateKey.trimmingCharacters(in: .whitespaces).isEmpty
            ) {
                model.saveClashfinderAccount(user: clashfinderUser, privateKey: clashfinderPrivateKey)
            }
            .padding(.top, 4)

        case "spotify":
            if s.spotifyConnected {
                Button { model.disconnectSpotify() } label: {
                    Text("Log out")
                        .foregroundColor(ink)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .overlay(Capsule().stroke(faint, lineWidth: 1))
                }
                .buttonStyle(.plain)
            } else {
                AmberButton(
                    title: "Log in with Spotify",
                    enabled: s.bundledSpotifyClientId
                        || !clientId.trimmingCharacters(in: .whitespaces).isEmpty
                ) { onSpotifyLogin(clientId) }
            }
            SheetBody(text: "Spotify allows five signed-in users per app, so logging in may be "
                + "refused. Use your own Spotify app instead: create one at "
                + "developer.spotify.com/dashboard with Web API enabled and "
                + "redirect URI \(spotifyRedirectURI), paste its Client ID "
                + "below, Save, then log out and back in.")
                .padding(.top, 8)
            // The steps above are the ones people get wrong, and a phone is a bad place
            // to follow them. The site has the same list plus a way to ask for one of
            // the five slots.
            SheetLink(title: "Step by step, and how to ask for a slot",
                      url: "https://magnus-encoded.github.io/station-to-station/")
            KeyField(label: "Spotify Client ID", text: $clientId)
            AmberButton(title: "Save") {
                model.saveSettings(apiKey: s.setlistFmApiKey, clientId: clientId)
            }
            .padding(.top, 4)

        case "photos", "calendar", "location":
            if !node.lit {
                AmberButton(title: "Open app permissions") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                }
            }

        // Cards are swapped peer to peer and never expire on their own, so without this
        // the only way to drop a lane was to wipe the app.
        case "contacts":
            if s.friends.isEmpty {
                SheetBody(text: "Swipe left from your timeline to swap cards with someone, and their line opens beside yours.")
            } else {
                ForEach(s.friends) { friend in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(friend.name).foregroundColor(ink)
                            Text("@\(friend.setlistfm) · \(s.showsByFriend[friend.setlistfm]?.count ?? 0) shows")
                                .font(.system(size: 12))
                                .foregroundColor(muted)
                        }
                        Spacer()
                        Button("Remove") { model.removeFriend(friend) }
                            .foregroundColor(amber)
                            .accessibilityLabel("Remove \(friend.name)")
                    }
                    .padding(.vertical, 4)
                }
            }

        // Experimental (#462): no two phones have completed a v2 Pass in the field yet.
        case "gossip":
            SheetBody(text: "Check in to share small public observations with nearby phones. Completing "
                + "the set keeps gossip active for 30 minutes, until 06:00 at the latest. "
                + "Delivery is best effort.")
            // Whether gossip is running is read back off the recomputed deadline rather
            // than a bit this screen sets, so the button tells the truth after a stop,
            // after the grace runs out, and on a fresh launch alike.
            // Three states, not two (#501). A stop can now be undone, so a night that was
            // stopped while it could still gossip offers Resume here as well as on its own
            // dim **Presence row** — the row also says *which* night is being stood at,
            // where this one leaves that to the fallback rule. "Off until your next
            // check-in" stays for the case it was always about: nothing eligible at all.
            let stopped = !s.gossipStoppedGigs.isEmpty
            AmberButton(
                title: s.gossipActiveUntil != nil ? "Stop gossip"
                    : stopped ? "Resume gossip" : "Gossip is off until your next check-in",
                enabled: s.gossipActiveUntil != nil || stopped
            ) {
                if s.gossipActiveUntil != nil { model.stopGossip() } else { model.resumeGossip() }
            }
            .padding(.top, 4)

        case "musicbrainz":
            SheetBody(text: "MusicBrainz is an open music database. The app asks it for song titles when "
                + "a setlist.fm record is empty, and for artist names as you type. There is "
                + "nothing to set up.")

        case "tickets":
            SheetBody(text: "Share a ticket PDF to Station to Station from your mail or files app.")

        default:
            EmptyView()
        }
    }
}

private struct SheetBody: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.system(size: 13))
            .foregroundColor(muted)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.vertical, 4)
    }
}

private struct SheetLink: View {
    let title: String
    let url: String

    var body: some View {
        if let destination = URL(string: url) {
            Link(title, destination: destination)
                .font(.system(size: 14))
                .foregroundColor(amber)
                .padding(.vertical, 6)
        }
    }
}

private struct KeyField: View {
    let label: String
    @Binding var text: String
    var secure = false

    var body: some View {
        Group {
            if secure {
                SecureField(label, text: $text)
            } else {
                TextField(label, text: $text)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
            }
        }
        .foregroundColor(ink)
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .overlay(RoundedRectangle(cornerRadius: 8).stroke(faint, lineWidth: 1))
    }
}

private struct AmberButton: View {
    let title: String
    var enabled = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 15, weight: .medium))
                .foregroundColor(onAmber)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
                .background(Capsule().fill(amber))
                .opacity(enabled ? 1 : 0.4)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}
