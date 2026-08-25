import SwiftUI

// The noticeboard: what is on, and what each thing costs you. The Swift twin of
// Android's `ui/ProgrammeScreen.kt` (#173).
//
// Deliberately read-only and deliberately not wired into the timeline. A programme
// is what the festival announced, not evidence anybody went — turning a listing
// into attendance is the check-in's job and the **Bill**'s, and conflating them
// here would put dozens of acts on a **Line** that nobody attended.
//
// The whole screen is one question asked two ways: *right now* (the on-now block,
// only while the festival is running) and *later today* (the day list, where every
// act carries what it clashes with).

private let ground = Color(red: 0x0E / 255, green: 0x0B / 255, blue: 0x14 / 255)
private let raised = Color(red: 0x17 / 255, green: 0x12 / 255, blue: 0x1F / 255)
private let ink = Color(red: 0xED / 255, green: 0xE9 / 255, blue: 0xF2 / 255)
private let muted = Color(red: 0x8B / 255, green: 0x82 / 255, blue: 0x99 / 255)
private let faint = Color(red: 0x5A / 255, green: 0x53 / 255, blue: 0x68 / 255)
private let amber = Color(red: 0xE7 / 255, green: 0xB2 / 255, blue: 0x4C / 255)
private let slate = Color(red: 0x6D / 255, green: 0x7E / 255, blue: 0x9B / 255)
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

private func cachedProgramme() -> [ProgrammeAct] {
    guard let text = try? String(contentsOf: programmeCacheFile, encoding: .utf8) else { return [] }
    return parseProgramme(text)
}

/// Fetch the public page and keep what it says.
///
/// The programme is retrieved by this device, from the festival's own site, and
/// stored only here. Nothing is redistributed and nothing was shipped — see
/// `oyaProgramme`.
private func fetchProgramme() async -> Result<[ProgrammeAct], Error> {
    guard let url = URL(string: oyaProgrammeURL) else { return .failure(AppError("Bad programme URL.")) }
    do {
        let (data, response) = try await URLSession.shared.data(from: url)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            return .failure(AppError("The festival's site returned an error."))
        }
        let acts = oyaProgramme(String(decoding: data, as: UTF8.self), year: 2026)
        // An empty parse means the page changed shape. Saying so beats caching
        // nothing and showing an empty timetable that looks like a festival with
        // no bands.
        guard !acts.isEmpty else {
            return .failure(AppError("Could not read the programme — the site's layout has changed."))
        }
        try? encodeProgramme(acts).write(to: programmeCacheFile, atomically: true, encoding: .utf8)
        return .success(acts)
    } catch {
        return .failure(error)
    }
}

struct ProgrammeView: View {
    @EnvironmentObject var nav: Nav
    var now: Date = Date()

    @State private var acts: [ProgrammeAct] = cachedProgramme()
    @State private var loading = false
    @State private var error: String?
    @State private var day: Date?

    private var calendar: Calendar { .current }

    var body: some View {
        let days = programmeDays(acts, calendar: calendar)
        let onNow = playingAt(now, acts, calendar: calendar)

        ZStack {
            ground.ignoresSafeArea()
            if acts.isEmpty {
                emptyState
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        if !onNow.isEmpty {
                            label("ON NOW", color: amber).padding(.top, 4).padding(.bottom, 8)
                            ForEach(onNow, id: \.self) { act in actRow(act, in: acts, accent: amber) }
                            Spacer().frame(height: 20)
                        }
                        dayChips(days)
                        let listed = day.map { actsOn($0, acts, calendar: calendar) } ?? []
                        ForEach(listed, id: \.self) { act in actRow(act, in: acts, accent: slate) }
                        Spacer().frame(height: 32)
                    }
                    .padding(.horizontal, 16)
                }
            }
        }
        .toolbar {
            ToolbarItem(placement: .principal) {
                Text("Øya 2026").font(.system(size: 16, design: .serif)).foregroundStyle(ink)
            }
        }
        .toolbarBackground(ground, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .navigationBarTitleDisplayMode(.inline)
        // Opens on the night you are actually standing in. Before the festival
        // that is the first day, after it the last — never an empty screen.
        .onAppear { if day == nil { day = openingDay(days) } }
        .onChange(of: acts) { day = openingDay(programmeDays($0, calendar: calendar)) }
    }

    private func openingDay(_ days: [Date]) -> Date? {
        let tonight = calendar.startOfDay(for: billNight(of: now))
        return days.first(where: { $0 >= tonight }) ?? days.last
    }

    private var emptyState: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("The line-up lives on the festival's own site. Fetch it here and it "
                + "stays on this phone — get it before you go, the signal in a field "
                + "is not the signal you have now.")
                .font(.system(size: 14)).foregroundStyle(muted)
            Text(loading ? "Fetching…" : "Fetch the programme")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(ground)
                .padding(.horizontal, 18).padding(.vertical, 11)
                .background(loading ? faint : amber)
                .clipShape(RoundedRectangle(cornerRadius: 4))
                .onTapGesture { if !loading { load() } }
            if let error {
                Text(error).font(.system(size: 13)).foregroundStyle(slate)
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    private func load() {
        loading = true
        error = nil
        Task { @MainActor in
            switch await fetchProgramme() {
            case .success(let fetched): acts = fetched
            case .failure(let err): error = userMessage(err)
            }
            loading = false
        }
    }

    private func label(_ text: String, color: Color) -> some View {
        Text(text).font(.system(size: 10, weight: .semibold)).kerning(1.5).foregroundStyle(color)
    }

    private func dayChips(_ days: [Date]) -> some View {
        let fmt: DateFormatter = {
            let f = DateFormatter()
            f.locale = Locale(identifier: "en_US")
            f.dateFormat = "EEE d"
            return f
        }()
        return HStack(spacing: 8) {
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
        .padding(.bottom, 14)
    }

    /// One act, and underneath it the acts it is a choice against.
    ///
    /// The clash list is the payload of the whole screen, so it is shown by
    /// default rather than behind a tap: the moment you need it is while walking
    /// between stages, and a gesture you have to remember is one you will not
    /// make. Tapping collapses it, for the evenings where you have already
    /// decided.
    private func actRow(_ act: ProgrammeAct, in all: [ProgrammeAct], accent: Color) -> some View {
        ActRowView(act: act, clashes: clashesWith(act, all, calendar: calendar), accent: accent)
    }
}

/// The same night boundary the rest of the app uses (`nightEndsHour`), so 01:30
/// is still tonight here too.
private func billNight(of now: Date, calendar: Calendar = .current) -> Date {
    let hour = calendar.component(.hour, from: now)
    let day = calendar.startOfDay(for: now)
    return hour < nightEndsHour ? (calendar.date(byAdding: .day, value: -1, to: day) ?? day) : day
}

private struct ActRowView: View {
    let act: ProgrammeAct
    let clashes: [ProgrammeAct]
    let accent: Color
    @State private var open = true

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .center, spacing: 12) {
                Text(act.start).font(.system(size: 14, weight: .semibold)).foregroundStyle(accent)
                VStack(alignment: .leading, spacing: 1) {
                    Text(act.artist).font(.system(size: 16, design: .serif)).foregroundStyle(ink)
                    Text(act.stage).font(.system(size: 12)).foregroundStyle(muted)
                }
                Spacer()
            }
            if clashes.isEmpty {
                // Worth saying out loud. "No clash" is the rarest and most useful
                // fact on a festival timetable, and silence would read as "not
                // computed".
                Text("clear").font(.system(size: 11)).foregroundStyle(faint).padding(.top, 5)
            } else if open {
                Text("INSTEAD OF").font(.system(size: 9, weight: .semibold)).kerning(1.2)
                    .foregroundStyle(faint).padding(.top, 9).padding(.bottom, 4)
                ForEach(clashes, id: \.self) { c in
                    Text("\(c.start)  \(c.artist) \u{00B7} \(c.stage)")
                        .font(.system(size: 12)).foregroundStyle(muted).padding(.top, 2)
                }
            } else {
                Text("\(clashes.count) clash\(clashes.count == 1 ? "" : "es")")
                    .font(.system(size: 11)).foregroundStyle(faint).padding(.top, 5)
            }
        }
        .padding(.horizontal, 14).padding(.vertical, 11)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(raised)
        .clipShape(RoundedRectangle(cornerRadius: 6))
        .padding(.bottom, 10)
        .contentShape(Rectangle())
        .onTapGesture { open.toggle() }
    }
}
