import SwiftUI

struct SetlistsView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var nav: Nav

    var body: some View {
        let s = model.state
        Group {
            if s.setlists.isEmpty && !s.setlistsLoading {
                Text("No setlists found.").padding()
            } else {
                List {
                    ForEach(s.setlists) { setlist in
                        let songCount = setlist.songs().count
                        SetlistRow(setlist: setlist, source: s.source, songCount: songCount)
                            .contentShape(Rectangle())
                            .onTapGesture {
                                if songCount > 0 { model.selectSetlist(setlist); nav.push(.confirm) }
                            }
                    }
                    if s.setlistsLoading {
                        ProgressView().frame(maxWidth: .infinity).padding()
                    } else if s.setlists.count < s.setlistsTotal {
                        Button("Load more (\(s.setlists.count)/\(s.setlistsTotal))") {
                            model.loadMoreSetlists()
                        }
                        .frame(maxWidth: .infinity)
                    }
                }
                .listStyle(.plain)
            }
        }
        .navigationTitle(s.setlistsTitle)
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct SetlistRow: View {
    let setlist: FmSetlist
    let source: SetlistSource
    let songCount: Int

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(source == .user
                     ? "\(setlist.eventDate ?? "?") · \(setlist.artist?.name ?? "Unknown artist")"
                     : (setlist.eventDate ?? "Unknown date"))
                Text(setlist.venueLine()).font(.caption).foregroundStyle(.secondary)
                if let tour = setlist.tour?.name {
                    Text(tour).font(.caption2).foregroundStyle(.secondary)
                }
            }
            Spacer()
            Text("\(songCount) songs").font(.caption).foregroundStyle(.secondary)
        }
    }
}
