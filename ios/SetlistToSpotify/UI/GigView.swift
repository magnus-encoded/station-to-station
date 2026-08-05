import SwiftUI

// The Gig resolution: one night. Its real setlist as a spine, encores marked, and
// the existing playlist conversion still one tap away (iOS already had it; #52
// keeps it). Reached by tapping a Gig Node on the Timeline.

private let ground = Color(red: 0x0E / 255, green: 0x0B / 255, blue: 0x14 / 255)
private let raised = Color(red: 0x17 / 255, green: 0x12 / 255, blue: 0x1F / 255)
private let ink = Color(red: 0xED / 255, green: 0xE9 / 255, blue: 0xF2 / 255)
private let muted = Color(red: 0x8B / 255, green: 0x82 / 255, blue: 0x99 / 255)
private let faint = Color(red: 0x5A / 255, green: 0x53 / 255, blue: 0x68 / 255)
private let amber = Color(red: 0xE7 / 255, green: 0xB2 / 255, blue: 0x4C / 255)

/// A row of the night: an encore divider, or a performed song (numbered; a tape
/// track has no number — it played but is not one of the band's songs).
private enum EventRow {
    case encore(Int)
    case song(number: Int?, name: String, cover: String?)
}

private func eventRows(_ setlist: FmSetlist) -> [EventRow] {
    var out: [EventRow] = []
    var n = 0
    var encores = 0
    for set in setlist.sets?.set ?? [] {
        if set.encore != nil { encores += 1; out.append(.encore(encores)) }
        for song in set.song where song.name.nilIfBlank != nil {
            out.append(.song(number: song.tape ? nil : { n += 1; return n }(),
                             name: song.name, cover: song.cover?.name))
        }
    }
    return out
}

struct GigView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var nav: Nav

    var body: some View {
        let show = model.state.selectedSetlist
        ZStack {
            ground.ignoresSafeArea()
            if let show {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        header(show)
                        let rows = eventRows(show)
                        if rows.isEmpty {
                            Text("No setlist was logged for this night on setlist.fm.")
                                .font(.system(size: 13)).foregroundStyle(muted)
                                .padding(.horizontal, 24).padding(.top, 8)
                        } else {
                            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in songRow(row) }
                        }
                        // The night's grid (#99): what I shot, under what was played.
                        NightGrid()
                        if !rows.isEmpty {
                            Button {
                                nav.push(.confirm)
                            } label: {
                                Text("Make a Spotify playlist").frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.borderedProminent).tint(amber).foregroundStyle(Color.black)
                            .padding(24)
                        }
                    }
                    .padding(.top, 8)
                }
            } else {
                Text("No gig selected.").foregroundStyle(muted)
            }
        }
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button { nav.pop() } label: { Image(systemName: "chevron.left") }.tint(faint)
            }
        }
        .toolbarBackground(ground, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .navigationBarBackButtonHidden(true)
        .navigationBarTitleDisplayMode(.inline)
        .swipeBack(nav)
    }

    private func header(_ show: FmSetlist) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(show.readableDate() ?? "Unknown date")
                .font(.system(size: 11, weight: .semibold)).kerning(1).foregroundStyle(faint)
            Text(show.artist?.name ?? "Unknown artist")
                .font(.system(size: 26, design: .serif)).foregroundStyle(ink)
            Text(show.venueLine()).font(.system(size: 14)).foregroundStyle(muted)
            // ponytail: the self-logged tag (issue #52 item 5) needs the
            // attendance provenance the store carries but the model never loads —
            // iOS has no self-log/check-in path yet (#29 is Android-only). Wire
            // this to state.attendanceByGig once an iOS check-in lands.
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 24).padding(.bottom, 16)
    }

    @ViewBuilder
    private func songRow(_ row: EventRow) -> some View {
        switch row {
        case .encore:
            HStack(spacing: 8) {
                Rectangle().fill(faint.opacity(0.4)).frame(height: 1)
                Text("ENCORE").font(.system(size: 10, weight: .semibold)).kerning(1.5).foregroundStyle(faint)
                Rectangle().fill(faint.opacity(0.4)).frame(height: 1)
            }
            .padding(.horizontal, 24).padding(.vertical, 10)
        case .song(let number, let name, let cover):
            HStack(alignment: .top, spacing: 12) {
                Text(number.map { "\($0)" } ?? "\u{266A}")
                    .font(.system(size: 12, weight: .medium)).foregroundStyle(faint)
                    .frame(width: 20, alignment: .trailing)
                VStack(alignment: .leading, spacing: 1) {
                    Text(name).font(.system(size: 15)).foregroundStyle(ink)
                    if let cover { Text("\(cover) cover").font(.system(size: 11)).foregroundStyle(muted) }
                }
                Spacer()
            }
            .padding(.horizontal, 24).padding(.vertical, 5)
        }
    }
}
