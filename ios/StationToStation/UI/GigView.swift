import SwiftUI

// The Gig resolution: one night. Its real setlist as a spine, encores marked, and
// the playlist conversion still here (iOS already had it; #52 keeps it) — on
// swipe-left, the "act on this level" gesture, not a control. Reached by tapping
// a Gig Node on the Timeline. Which action is offered is #177's question.

private let ground = Color(red: 0x0E / 255, green: 0x0B / 255, blue: 0x14 / 255)
private let raised = Color(red: 0x17 / 255, green: 0x12 / 255, blue: 0x1F / 255)
private let ink = Color(red: 0xED / 255, green: 0xE9 / 255, blue: 0xF2 / 255)
private let muted = Color(red: 0x8B / 255, green: 0x82 / 255, blue: 0x99 / 255)
private let faint = Color(red: 0x5A / 255, green: 0x53 / 255, blue: 0x68 / 255)
/// Mine. Never "the accent colour" — it means *mine*, at every Resolution
/// (same mark StationView draws its Spine with).
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
        let rows = show.map(eventRows) ?? []
        ZStack {
            ground.ignoresSafeArea()
            if let show {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        header(show)
                        if rows.isEmpty {
                            Text("No setlist was logged for this night on setlist.fm.")
                                .font(.system(size: 13)).foregroundStyle(muted)
                                .padding(.horizontal, 24).padding(.top, 8)
                        } else {
                            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in songRow(row) }
                        }
                        // The night's grid (#99): what I shot, under what was played.
                        NightGrid()
                    }
                    .padding(.top, 8)
                }
            } else {
                Text("No gig selected.").foregroundStyle(muted)
            }
        }
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button { nav.pop() } label: { Image(systemName: "chevron.left") }
                    .tint(faint)
                    // Without this a reader hears the symbol's own name, "chevron
                    // left" — a shape, where every other control here is named by
                    // what it does.
                    .accessibilityLabel("Back")
            }
        }
        .toolbarBackground(ground, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .navigationBarBackButtonHidden(true)
        .navigationBarTitleDisplayMode(.inline)
        .swipeBack(nav)
        // Act on this level: the playlist. Nothing to convert on a night nobody
        // logged, so an empty setlist offers nothing rather than an empty screen.
        .swipeLeft { if !rows.isEmpty { nav.push(.confirm) } }
        // The same move for VoiceOver, which takes the flick for itself. This used
        // to be a button, and the button was reachable; the gesture on its own is
        // not, so the grammar cannot cost a reader the action.
        .accessibilityAction(named: "Make a Spotify playlist") {
            if !rows.isEmpty { nav.push(.confirm) }
        }
        // Back is a chevron with no label, and the swipe that also does it is a
        // gesture VoiceOver consumes.
        .accessibilityAction(.escape) { nav.pop() }
    }

    private func header(_ show: FmSetlist) -> some View {
        // A night I'm going to, not one I was at (#175's claim, not `gigPlanned`
        // membership). Manual check-in (#174) is the only one there is when
        // location was refused or the venue couldn't be geocoded — same night
        // window as the ambient offer, no location involved at all.
        let provenance = model.state.selectedAttendance?.provenance
        let planned = isPlanned(provenance)
        let checkedIn = provenance == "checked_in"
        return VStack(alignment: .leading, spacing: 4) {
            Text(show.readableDate() ?? "Unknown date")
                .font(.system(size: 11, weight: .semibold)).kerning(1).foregroundStyle(faint)
            Text(show.artist?.name ?? "Unknown artist")
                .font(.system(size: 26, design: .serif)).foregroundStyle(ink)
            Text(show.venueLine()).font(.system(size: 14)).foregroundStyle(muted)
            if checkedIn {
                Text("\u{2713} checked in").font(.system(size: 13)).foregroundStyle(amber)
                    .padding(.top, 6)
            } else if planned, canCheckInManually(gig: show, now: Date()) {
                Text("I'm here — check in").font(.system(size: 13)).foregroundStyle(amber)
                    .padding(.top, 6)
                    .onTapGesture { model.checkIn(show.id) }
            }
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
