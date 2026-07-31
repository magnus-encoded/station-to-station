import SwiftUI

// My Line at the My-timeline Resolution: one continuous vertical stroke, my Gigs
// and Festivals on it, Festivals uncollapsing in place. Amber means mine — at
// every Resolution, without exception (UBIQUITOUS_LANGUAGE.md).
//
// The Lanes, Crossings and Joined runs of the Timelines resolution are not drawn
// here yet; the model behind them (weaveTimelines) already is, and this screen
// calls it with no friends so there is one code path, not two.

private let ground = Color(red: 0x0E / 255, green: 0x0B / 255, blue: 0x14 / 255)
private let ink = Color(red: 0xED / 255, green: 0xE9 / 255, blue: 0xF2 / 255)
private let muted = Color(red: 0x8B / 255, green: 0x82 / 255, blue: 0x99 / 255)
private let faint = Color(red: 0x5A / 255, green: 0x53 / 255, blue: 0x68 / 255)
private let slate = Color(red: 0x6D / 255, green: 0x7E / 255, blue: 0x9B / 255)
/// Mine. Never "the accent colour" — it means *mine*.
private let amber = Color(red: 0xE7 / 255, green: 0xB2 / 255, blue: 0x4C / 255)

private let nodeSize: CGFloat = 22

struct StationView: View {
    @EnvironmentObject var model: AppModel

    private var rows: [WovenRow] {
        weaveTimelines(
            mine: model.state.timelineShows,
            festivalNames: model.state.festivalNames,
            expanded: model.state.expandedFestivals
        )
    }

    var body: some View {
        let s = model.state
        ZStack {
            ground.ignoresSafeArea()
            if s.timelineShows.isEmpty {
                empty(loading: s.timelineLoading)
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(Array(rows.enumerated()), id: \.element.key) { i, row in
                            StationRow(
                                row: row,
                                open: s.expandedFestivals.contains(row.key),
                                // Brightness carries one extra meaning only:
                                // brighter = most recent.
                                highlight: i == 0,
                                onTap: { if row.node.isFestival { model.toggleFestival(row.key) } }
                            )
                        }
                    }
                    .padding(.top, 8)
                }
            }
        }
        .navigationTitle("My timeline")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            // ToolbarItem with an explicit placement: iOS 16 puts an unplaced
            // item somewhere else entirely.
            ToolbarItem(placement: .navigationBarTrailing) {
                Button { model.refreshTimeline() } label: {
                    if s.timelineLoading {
                        ProgressView()
                    } else {
                        Image(systemName: "arrow.clockwise")
                    }
                }
                .disabled(s.timelineLoading)
            }
        }
        .onAppear { model.loadTimeline() }
    }

    @ViewBuilder
    private func empty(loading: Bool) -> some View {
        VStack(spacing: 12) {
            if loading {
                ProgressView().tint(amber)
                Text("Pulling your attended shows…").foregroundStyle(muted)
            } else {
                Text("Nothing on your line yet.").font(.title3).foregroundStyle(ink)
                Text("Import the shows you've marked attended on setlist.fm.")
                    .font(.subheadline).foregroundStyle(muted)
                    .multilineTextAlignment(.center)
                Button("Import my concerts") { model.refreshTimeline() }
                    .buttonStyle(.borderedProminent)
                    .tint(amber)
            }
        }
        .padding(32)
    }
}

/// One Node on the Line. The Spine column is a fixed width at every row, so
/// nothing moves between Resolutions.
private struct StationRow: View {
    let row: WovenRow
    let open: Bool
    let highlight: Bool
    let onTap: () -> Void

    private var accent: Color { highlight ? amber : amber.opacity(0.6) }

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            ZStack(alignment: .topLeading) {
                // The Line: unbroken, whatever is drawn beside it.
                Rectangle()
                    .fill(amber.opacity(0.3))
                    .frame(width: 2)
                    .frame(maxHeight: .infinity)
                    .offset(x: SpineX)
                // A Node is a ring; nothing is drawn inside one but a count.
                ZStack {
                    Circle().strokeBorder(accent, lineWidth: 2)
                    if case .festival(_, let shows) = row.node {
                        Text("\(shows.count)")
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(accent)
                    }
                }
                .frame(width: nodeSize, height: nodeSize)
                .offset(x: SpineX - nodeSize / 2 + 1, y: 4)
            }
            .frame(width: SpineWidth)

            content
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.trailing, 18)
                .padding(.bottom, 22)
                .padding(.leading, row.depth > 0 ? 12 : 0)
        }
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
    }

    @ViewBuilder
    private var content: some View {
        switch row.node {
        case .festival(let name, let shows):
            VStack(alignment: .leading, spacing: 3) {
                Text("FESTIVAL")
                    .font(.system(size: 10, weight: .semibold))
                    .kerning(1.5)
                    .foregroundStyle(slate)
                Text(name).font(.system(size: 17, design: .serif)).foregroundStyle(ink)
                Text(festivalDateRange(shows)).font(.system(size: 13)).foregroundStyle(muted)
                // Whose is only worth saying when someone else is on screen; at
                // this Resolution nobody is, so it reads "13 gigs".
                Text("\(shows.count) gigs" + (open ? " · tap to close" : ""))
                    .font(.system(size: 12))
                    .foregroundStyle(faint)
                    .padding(.top, 4)
            }
        case .concert(let show):
            VStack(alignment: .leading, spacing: 3) {
                Text(show.artist?.name ?? "Unknown artist")
                    .font(.system(size: 17, design: .serif))
                    .foregroundStyle(ink)
                Text(show.venueLine()).font(.system(size: 13)).foregroundStyle(muted)
                Text(show.readableDate() ?? "").font(.system(size: 12)).foregroundStyle(faint)
            }
        }
    }
}

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
    return "\(dayMonth.string(from: first)) – \(dayMonthYear.string(from: last))"
}
