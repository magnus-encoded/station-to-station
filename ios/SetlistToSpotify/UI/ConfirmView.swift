import SwiftUI

struct ConfirmView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var nav: Nav
    @Environment(\.openURL) private var openURL
    @State private var expandedIndex = -1

    var body: some View {
        let s = model.state
        let selectedCount = s.matches.filter { $0.included && $0.selected != nil }.count

        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    if let setlist = s.selectedSetlist {
                        VStack(alignment: .leading) {
                            Text("\(setlist.artist?.name ?? "") · \(setlist.eventDate ?? "")")
                                .font(.headline)
                            Text(setlist.venueLine()).font(.caption).foregroundStyle(.secondary)
                        }
                        .padding(.horizontal)
                    }
                    TextField("Playlist name", text: Binding(
                        get: { s.playlistName }, set: model.setPlaylistName))
                        .textFieldStyle(.roundedBorder)
                        .padding(.horizontal)

                    HStack(alignment: .top) {
                        VStack(alignment: .leading) {
                            Text("Public playlist").font(.subheadline)
                            Text(s.playlistPublic
                                 ? "Friends can discover it from the shared link, and it shows on your Spotify profile."
                                 : "Kept private — only people you send the link to can open it, and friends' apps can't auto-add you from it.")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Toggle("", isOn: Binding(
                            get: { s.playlistPublic }, set: model.setPlaylistPublic)).labelsHidden()
                    }
                    .padding(.horizontal)

                    if s.matching {
                        let done = s.matches.filter { !$0.loading }.count
                        ProgressView(value: s.matches.isEmpty ? 0 : Double(done) / Double(s.matches.count))
                            .padding(.horizontal)
                        Text("Matching songs on Spotify… \(done)/\(s.matches.count)")
                            .font(.caption).padding(.horizontal)
                    }

                    ForEach(Array(s.matches.enumerated()), id: \.element.id) { index, match in
                        SongMatchRow(
                            match: match,
                            expanded: expandedIndex == index,
                            onToggleExpand: { expandedIndex = expandedIndex == index ? -1 : index },
                            onToggleIncluded: { model.toggleIncluded(index) },
                            onChooseCandidate: { model.chooseCandidate(index, $0) },
                            onResearch: { model.researchSong(index, $0) })
                    }
                }
                .padding(.vertical, 8)
            }

            // Bottom bar
            VStack {
                if !s.spotifyConnected {
                    Button {
                        if s.spotifyLoginReady { model.loginSpotify() } else { nav.push(.settings) }
                    } label: { Text("Log in with Spotify").frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent)
                } else {
                    Button { model.createPlaylist() } label: {
                        if s.creatingPlaylist {
                            ProgressView()
                        } else {
                            Text("Create playlist (\(selectedCount) songs)").frame(maxWidth: .infinity)
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(selectedCount == 0 || s.creatingPlaylist || s.matching)
                }
            }
            .padding()
        }
        .navigationTitle("Confirm songs")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: Binding(
            get: { s.createdPlaylistUrl != nil },
            set: { if !$0 { model.dismissCreated() } })
        ) {
            CreatedSheet(
                name: s.createdPlaylistName,
                trackCount: s.createdTrackCount,
                refusedCount: s.createdRefusedCount,
                url: URL(string: s.createdPlaylistUrl ?? "") ?? URL(string: "https://open.spotify.com")!,
                onOpen: { openURL(URL(string: s.createdPlaylistUrl ?? "")!) },
                onDone: { model.dismissCreated(); nav.pop() })
        }
    }
}

private struct CreatedSheet: View {
    let name: String
    let trackCount: Int
    let refusedCount: Int
    let url: URL
    let onOpen: () -> Void
    let onDone: () -> Void

    var body: some View {
        VStack(spacing: 20) {
            Text("Playlist created").font(.title2).bold()
            Text("\"\(name)\" was created with \(trackCount) songs."
                 + (refusedCount > 0 ? " \(refusedCount) were refused by Spotify." : ""))
                .multilineTextAlignment(.center)
            ShareLink(item: url) { Text("Send to a friend").frame(maxWidth: .infinity) }
                .buttonStyle(.borderedProminent)
            Button("Open in Spotify", action: onOpen)
            Button("Done", action: onDone)
        }
        .padding()
        .presentationDetents([.medium])
    }
}

private struct SongMatchRow: View {
    let match: SongMatch
    let expanded: Bool
    let onToggleExpand: () -> Void
    let onToggleIncluded: () -> Void
    let onChooseCandidate: (SpotifyTrack) -> Void
    let onResearch: (String) -> Void

    var body: some View {
        VStack {
            HStack(alignment: .top) {
                Button(action: onToggleIncluded) {
                    Image(systemName: match.included && match.selected != nil ? "checkmark.square.fill" : "square")
                }
                .disabled(match.selected == nil)
                .buttonStyle(.plain)

                VStack(alignment: .leading, spacing: 2) {
                    Text(label).lineLimit(1)
                    subtitle
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
                .onTapGesture(perform: onToggleExpand)

                Button(action: onToggleExpand) {
                    Image(systemName: expanded ? "chevron.up" : "chevron.down")
                }
                .buttonStyle(.plain)
            }
            if expanded {
                CandidatePicker(match: match, onChoose: onChooseCandidate, onResearch: onResearch)
            }
        }
        .padding(8)
        .background(RoundedRectangle(cornerRadius: 10).fill(Color(.secondarySystemBackground)))
        .padding(.horizontal).padding(.vertical, 4)
    }

    private var label: String {
        var l = match.song.name
        if match.isCover { l += " (\(match.searchArtist) cover)" }
        if match.song.tape { l += " [tape]" }
        return l
    }

    @ViewBuilder private var subtitle: some View {
        if match.loading {
            Text("Searching…").font(.caption).foregroundStyle(.secondary)
        } else if let sel = match.selected {
            Text("\(sel.name) · \(sel.artistNames())" + (sel.album?.name.map { " · \($0)" } ?? ""))
                .font(.caption).foregroundStyle(.tint).lineLimit(1)
        } else {
            Text(match.error ?? "No match found — tap to search manually")
                .font(.caption).foregroundStyle(.red)
        }
    }
}

private struct CandidatePicker: View {
    let match: SongMatch
    let onChoose: (SpotifyTrack) -> Void
    let onResearch: (String) -> Void
    @State private var query: String

    init(match: SongMatch, onChoose: @escaping (SpotifyTrack) -> Void, onResearch: @escaping (String) -> Void) {
        self.match = match
        self.onChoose = onChoose
        self.onResearch = onResearch
        _query = State(initialValue: "\(match.song.name) \(match.searchArtist)")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(match.candidates) { track in
                HStack {
                    Image(systemName: "checkmark")
                        .foregroundStyle(.tint).frame(width: 18)
                        .opacity(track.uri == match.selected?.uri ? 1 : 0)
                    VStack(alignment: .leading) {
                        Text(track.name).font(.subheadline).lineLimit(1)
                        Text(track.artistNames() + (track.album?.name.map { " · \($0)" } ?? ""))
                            .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                    Spacer()
                    Text(formatDuration(track.durationMs)).font(.caption).foregroundStyle(.secondary)
                }
                .contentShape(Rectangle())
                .onTapGesture { onChoose(track) }
            }
            HStack {
                TextField("Search Spotify", text: $query)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit { onResearch(query) }
                Button { onResearch(query) } label: { Image(systemName: "magnifyingglass") }
            }
        }
        .padding(.top, 4)
    }
}

private func formatDuration(_ ms: Int64) -> String {
    let total = ms / 1000
    return String(format: "%d:%02d", total / 60, total % 60)
}
