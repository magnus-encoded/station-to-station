import SwiftUI
import UniformTypeIdentifiers

// Departures: the board, and the change committing it would make. The Swift twin of
// Android's `ui/ProgrammeScreen.kt` (#173, #389, #390, #391).
//
// A day is a run of positions down the clock. A position with one act is a single
// choice: on the Line, or not. A position with more than one act is a **rung** —
// they clash, so at most one of them can be picked. Nothing here writes anywhere
// until the commit bar is tapped: picking is just a draft over `applied`, the acts
// already on the Line, and `departuresOf` is the one function that turns the two
// into what a tap would change.
//
// iOS has no PlanningCurtain — the pull-gesture door Android added is a second
// detent on a gesture this platform does not have (ADR-0017). The screen is already
// reached through an explicit button in `StationView`, so there is no second door
// to build here.

private let ground = Color(red: 0x0E / 255, green: 0x0B / 255, blue: 0x14 / 255)
private let raised = Color(red: 0x17 / 255, green: 0x12 / 255, blue: 0x1F / 255)
private let ink = Color(red: 0xED / 255, green: 0xE9 / 255, blue: 0xF2 / 255)
private let muted = Color(red: 0x8B / 255, green: 0x82 / 255, blue: 0x99 / 255)
private let faint = Color(red: 0x5A / 255, green: 0x53 / 255, blue: 0x68 / 255)
private let amber = Color(red: 0xE7 / 255, green: 0xB2 / 255, blue: 0x4C / 255)
private let slate = Color(red: 0x6D / 255, green: 0x7E / 255, blue: 0x9B / 255)
private let leaving = Color(red: 0xC2 / 255, green: 0x6B / 255, blue: 0x6B / 255)
private let chipEdge = Color(red: 0x2E / 255, green: 0x27 / 255, blue: 0x40 / 255)

/// Where a fetched programme lives on this device. One file, overwritten.
///
/// In Application Support rather than a default, matching `TimelineStore`: it is a
/// document, not a setting, and the next thing that happens to it is being handed
/// to another phone whole.
private var programmeCacheFile: URL {
    let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    return dir.appendingPathComponent("programme.json")
}

/// The reduced clashfinder index, cached separately from any one festival's
/// timetable — see `Clashfinder.swift`'s note on why the full ~4 MB index is never
/// re-parsed on every open.
private var indexCacheFile: URL {
    let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    return dir.appendingPathComponent("clashfinder-index.json")
}

private func cachedProgramme() -> StoredProgramme {
    guard let text = try? String(contentsOf: programmeCacheFile, encoding: .utf8) else { return StoredProgramme() }
    return parseProgramme(text)
}

private func cachedIndex() -> [ClashfinderFestival] {
    guard let text = try? String(contentsOf: indexCacheFile, encoding: .utf8) else { return [] }
    return decodeFestivals(text)
}

/// The window shown before the person types anything — see `rankFestivals`.
private let pickerPageSize = 40

struct ProgrammeView: View {
    @EnvironmentObject var nav: Nav
    @EnvironmentObject var model: AppModel
    @Environment(\.openURL) private var openURL
    var now: Date = Date()

    @State private var programme = cachedProgramme()
    @State private var loading = false
    @State private var error: String?
    @State private var blocked = false
    @State private var day: Date?
    @State private var showPicker = false
    @State private var showImporter = false
    @State private var picked: Set<String> = []
    /// The id `loadEvent` was fetching when it was blocked — read by `openInBrowser`
    /// and `importFile` so the browser opens (and the import validates against) the
    /// same document, not whatever the index happened to fetch last.
    @State private var pendingEventId: String?

    private var calendar: Calendar { .current }
    // Not `lazy`: a `lazy var` on a struct needs mutating access even to read, and
    // `body` (like every `View` getter) is non-mutating — that would refuse to
    // compile. Building a new client is cheap; it holds nothing but a closure and
    // `URLSession.shared`.
    private var client: ClashfinderClient { ClashfinderClient { [weak model] in await model?.clashfinderAuth } }

    private var onLine: [FmSetlist] { model.state.timelineShows + model.state.plannedGigs }
    private var applied: Set<String> { appliedActs(programme, gigs: onLine) }
    private var board: Departures { departuresOf(programme, picked: picked, applied: applied, calendar: calendar) }
    private var firstCommit: Bool { applied.isEmpty }

    var body: some View {
        let board = board
        let days = board.days

        ZStack(alignment: .bottom) {
            ground.ignoresSafeArea()
            if programme.acts.isEmpty {
                emptyState
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        dayChips(days)
                        let positions = day.flatMap { board.positions[$0] } ?? []
                        ForEach(positions.indices, id: \.self) { i in
                            positionRow(positions[i])
                        }
                        if !programme.copyright.isEmpty {
                            Text(programme.copyright)
                                .font(.system(size: 10)).foregroundStyle(faint)
                                .padding(.top, 16)
                        }
                        Spacer().frame(height: board.diff.isEmpty ? 32 : 96)
                    }
                    .padding(.horizontal, 16)
                }
                .refreshable { await reload() }
            }
            if let label = commitLabel(board.diff, festival: programme.name, firstCommit: firstCommit, calendar: calendar) {
                commitBar(label: label, diff: board.diff)
            }
        }
        .toolbar {
            ToolbarItem(placement: .principal) {
                Text(programme.name.isEmpty ? "Programme" : programme.name)
                    .font(.system(size: 16, design: .serif)).foregroundStyle(ink)
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                Button { showPicker = true } label: {
                    Image(systemName: "magnifyingglass").foregroundStyle(ink)
                }
            }
        }
        .toolbarBackground(ground, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .navigationBarTitleDisplayMode(.inline)
        // Opens on the night you are actually standing in. Before the festival
        // that is the first day, after it the last — never an empty screen.
        .onAppear { if day == nil { day = openingDay(days) } }
        .onChange(of: programme) { day = openingDay(programmeDays($0.acts, calendar: calendar)); picked = [] }
        .sheet(isPresented: $showPicker) {
            FestivalPickerView(client: client, on: now, calendar: calendar) { picked in
                showPicker = false
                loadEvent(picked.id)
            }
        }
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [.json]) { result in
            importFile(result)
        }
    }

    private func openingDay(_ days: [Date]) -> Date? {
        let tonight = calendar.startOfDay(for: billNight(of: now))
        return days.first(where: { $0 >= tonight }) ?? days.last
    }

    private var emptyState: some View {
        VStack(alignment: .leading, spacing: 18) {
            if model.clashfinderAuth == nil {
                Text("The programme comes from clashfinder, which needs your own account. "
                    + "Add a username and private key in Settings — a free account takes a "
                    + "minute at clashfinder.com.")
                    .font(.system(size: 14)).foregroundStyle(muted)
            } else {
                Text("Pick a festival and its timetable stays on this phone — get it "
                    + "before you go, the signal in a field is not the signal you have now.")
                    .font(.system(size: 14)).foregroundStyle(muted)
                Text(loading ? "Fetching…" : "Choose a festival")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(ground)
                    .padding(.horizontal, 18).padding(.vertical, 11)
                    .background(loading ? faint : amber)
                    .clipShape(RoundedRectangle(cornerRadius: 4))
                    .onTapGesture { if !loading { showPicker = true } }
            }
            if let error {
                Text(error).font(.system(size: 13)).foregroundStyle(slate)
            }
            if blocked {
                blockedActions
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    /// clashfinder's bot check answers this client with a CAPTCHA page rather than
    /// data. Nothing here can clear it — only a browser on the same device can — so
    /// the way through is the address a browser can still fetch, and a way to hand
    /// the file it downloads back to the app. The Swift twin of Android's
    /// `openClashfinderInBrowser` and its file importer.
    private var blockedActions: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("clashfinder wants a browser check before it will answer this phone. " +
                "Open the timetable in a browser, then hand the downloaded file back here.")
                .font(.system(size: 13)).foregroundStyle(muted)
            Button("Open in a browser") { openInBrowser() }
                .font(.system(size: 13, weight: .semibold)).foregroundStyle(amber)
            Button("Hand over the file") { showImporter = true }
                .font(.system(size: 13, weight: .semibold)).foregroundStyle(amber)
        }
    }

    private func openInBrowser() {
        guard let auth = model.clashfinderAuth else { return }
        let path = pendingEventId.map { "event/\($0).json" } ?? "events/all.json"
        if let url = URL(string: clashfinderUrl(path, auth: auth)) { openURL(url) }
    }

    private func importFile(_ result: Result<URL, Error>) {
        guard case .success(let url) = result else { return }
        let id = url.deletingPathExtension().lastPathComponent
        guard url.startAccessingSecurityScopedResource() else { return }
        defer { url.stopAccessingSecurityScopedResource() }
        guard let text = try? String(contentsOf: url, encoding: .utf8) else {
            error = "Could not read that file."
            return
        }
        let fetched = parseClashfinderEvent(text, id: id)
        guard !fetched.acts.isEmpty else {
            error = "That file has no timetable in it."
            return
        }
        programme = fetched
        try? encodeProgramme(fetched).write(to: programmeCacheFile, atomically: true, encoding: .utf8)
        error = nil
        blocked = false
    }

    private func reload() async {
        guard !programme.id.isEmpty else { return }
        loadEvent(programme.id)
    }

    private func loadEvent(_ id: String) {
        loading = true
        error = nil
        blocked = false
        pendingEventId = id
        Task { @MainActor in
            do {
                let fetched = try await client.event(id)
                programme = fetched
                try? encodeProgramme(fetched).write(to: programmeCacheFile, atomically: true, encoding: .utf8)
            } catch is ClashfinderBlocked {
                error = nil
                blocked = true
            } catch {
                self.error = userMessage(error)
            }
            loading = false
        }
    }

    private func dayChips(_ days: [Date]) -> some View {
        let fmt: DateFormatter = {
            let f = DateFormatter()
            f.locale = Locale(identifier: "en_US")
            f.dateFormat = "EEE d"
            return f
        }()
        return ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(days, id: \.self) { d in
                    let selected = d == day
                    Text(fmt.string(from: d))
                        .font(.system(size: 13, weight: selected ? .semibold : .regular))
                        .foregroundStyle(selected ? ground : muted)
                        .padding(.horizontal, 12).padding(.vertical, 7)
                        .background(selected ? amber : Color.clear)
                        .clipShape(RoundedRectangle(cornerRadius: 4))
                        .overlay(
                            RoundedRectangle(cornerRadius: 4)
                                .stroke(selected ? amber : chipEdge, lineWidth: 1)
                        )
                        .onTapGesture { day = d }
                }
            }
        }
        .padding(.top, 4)
        .padding(.bottom, 14)
    }

    /// One position: a single act, or a rung of acts that clash — at most one of a
    /// rung's options may be picked, which is what makes it a rung rather than two
    /// unrelated rows.
    private func positionRow(_ position: Position) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(position.options, id: \.self) { act in
                actRow(act, isRung: position.isRung)
            }
        }
        .padding(.bottom, 10)
    }

    private func actRow(_ act: ProgrammeAct, isRung: Bool) -> some View {
        let key = actKey(act)
        let isPicked = picked.contains(key)
        let isApplied = applied.contains(key)
        let (icon, accent): (String, Color) = {
            switch (isPicked, isApplied) {
            case (true, true): return ("checkmark.circle.fill", amber)
            case (true, false): return ("circle.fill", amber)
            case (false, true): return ("minus.circle", leaving)
            case (false, false): return ("circle", faint)
            }
        }()
        return HStack(alignment: .center, spacing: 12) {
            Image(systemName: icon).foregroundStyle(accent).font(.system(size: 18))
            Text(act.start).font(.system(size: 14, weight: .semibold)).foregroundStyle(accent)
            VStack(alignment: .leading, spacing: 1) {
                Text(act.artist).font(.system(size: 16, design: .serif))
                    .foregroundStyle(isApplied && !isPicked ? muted : ink)
                    .strikethrough(isApplied && !isPicked)
                Text(act.stage).font(.system(size: 12)).foregroundStyle(muted)
            }
            Spacer()
        }
        .padding(.horizontal, 14).padding(.vertical, 11)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(raised)
        .clipShape(RoundedRectangle(cornerRadius: 6))
        .contentShape(Rectangle())
        .onTapGesture {
            if isPicked {
                picked.remove(key)
            } else {
                // A rung is a clash: taking one option means letting go of the
                // others on the same rung, not stacking a second choice on top.
                if isRung {
                    for other in position(for: act).options where other != act { picked.remove(actKey(other)) }
                }
                picked.insert(key)
            }
        }
    }

    private func position(for act: ProgrammeAct) -> Position {
        // Not keyed by day here: an after-midnight act's own clock time falls on
        // the calendar day after the night it is grouped under, so hunting by a
        // computed day key would miss it. The rung it belongs to is easier to find
        // than the day it is filed under.
        board.positions.values.flatMap { $0 }.first { $0.options.contains(act) } ?? Position(options: [act])
    }

    private func commitBar(label: String, diff: ProgrammeDiff) -> some View {
        VStack(spacing: 0) {
            Divider().overlay(chipEdge)
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(label).font(.system(size: 14, weight: .semibold)).foregroundStyle(ink)
                    Text(commitSub(diff, festival: programme.name, firstCommit: firstCommit,
                                   open: board.positions.values.flatMap { $0 }.filter { rung in
                                       rung.isRung && !rung.options.contains { picked.contains(actKey($0)) }
                                   }.count))
                        .font(.system(size: 12)).foregroundStyle(muted)
                }
                Spacer()
                Button("Commit") {
                    model.commitProgramme(programme, diff: diff, picked: picked, now: now)
                    picked = []
                }
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(ground)
                .padding(.horizontal, 16).padding(.vertical, 10)
                .background(amber)
                .clipShape(RoundedRectangle(cornerRadius: 4))
            }
            .padding(.horizontal, 16).padding(.vertical, 12)
        }
        .background(ground)
    }
}

/// The same night boundary the rest of the app uses (`nightEndsHour`), so 01:30
/// is still tonight here too.
private func billNight(of now: Date, calendar: Calendar = .current) -> Date {
    let hour = calendar.component(.hour, from: now)
    let day = calendar.startOfDay(for: now)
    return hour < nightEndsHour ? (calendar.date(byAdding: .day, value: -1, to: day) ?? day) : day
}

/// Which festival's timetable to fetch: nearness-ranked, windowed by default,
/// searched over the whole catalogue — see `rankFestivals`.
private struct FestivalPickerView: View {
    let client: ClashfinderClient
    let on: Date
    let calendar: Calendar
    let onPick: (ClashfinderFestival) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var all: [ClashfinderFestival] = cachedIndex()
    @State private var query = ""
    @State private var visible = pickerPageSize
    @State private var loading = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            List {
                let ranked = rankFestivals(all, on: on, query: query, calendar: calendar)
                ForEach(ranked.prefix(query.isEmpty ? visible : ranked.count)) { festival in
                    Button { onPick(festival) } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(festival.name).foregroundStyle(.primary)
                            Text("\(festival.start) \u{00B7} \(festival.acts) acts \u{00B7} \(festival.stages) stages")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                if query.isEmpty && visible < ranked.count {
                    Button("Show more") { visible += pickerPageSize }
                }
            }
            .searchable(text: $query, prompt: "Filter by name")
            .navigationTitle("Choose a festival")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    if loading { ProgressView() } else {
                        Button("Refresh") { refresh() }
                    }
                }
            }
            .overlay {
                if all.isEmpty && !loading {
                    VStack(spacing: 8) {
                        Image(systemName: "questionmark.folder").font(.system(size: 32))
                        Text(error ?? "No festival list yet")
                        Text("Tap Refresh to fetch clashfinder's catalogue.")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
                    .padding(24)
                }
            }
        }
        .onAppear { if all.isEmpty { refresh() } }
    }

    private func refresh() {
        loading = true
        error = nil
        Task { @MainActor in
            do {
                let fetched = try await client.index()
                all = fetched
                try? encodeFestivals(fetched).write(to: indexCacheFile, atomically: true, encoding: .utf8)
            } catch {
                self.error = userMessage(error)
            }
            loading = false
        }
    }
}
