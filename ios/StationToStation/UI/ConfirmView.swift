import SwiftUI

struct ConfirmView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var nav: Nav
    @Environment(\.openURL) private var openURL
    @State private var expandedIndex = -1
    /// The clip standing between "create" and the playlist, while its frame is
    /// being chosen.
    @State private var pickingFrameFor: CoverClip?

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

                    // Without a date there is no window to search the gallery for.
                    if s.selectedSetlist?.eventDate != nil { CoverPicker() }

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
                    Button {
                        // A clip has no one picture until a frame is chosen, so the
                        // choosing is the last step before the playlist rather than a
                        // slot on this form — the same order Android puts it in.
                        if let cover = s.selectedCoverAssetId, PhotoLibrary.isVideo(assetId: cover) {
                            pickingFrameFor = CoverClip(assetId: cover)
                        } else {
                            model.createPlaylist()
                        }
                    } label: {
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
                coverError: s.coverUploadError,
                url: URL(string: s.createdPlaylistUrl ?? "") ?? URL(string: "https://open.spotify.com")!,
                onOpen: { openURL(URL(string: s.createdPlaylistUrl ?? "")!) },
                onDone: { model.dismissCreated(); nav.pop() })
        }
        .sheet(item: $pickingFrameFor) { clip in
            CoverFrameSheet(
                assetId: clip.assetId,
                frameMs: s.selectedCoverFrameMs,
                onFrameChange: model.setCoverFrame,
                onCancel: { pickingFrameFor = nil },
                onConfirm: {
                    pickingFrameFor = nil
                    model.createPlaylist()
                })
        }
    }
}

/// The clip being scrubbed. A wrapper because `sheet(item:)` wants an identity and
/// an asset id is a bare string.
private struct CoverClip: Identifiable {
    let assetId: String
    var id: String { assetId }
}

/// Where in a clip a drag down its track lands, in milliseconds.
///
/// Pulled out of the sheet because it is the only part of scrubbing that can be
/// wrong in a way a test can catch: a drag that leaves the track must clamp to the
/// clip's ends rather than seek past them.
func scrubFrameMs(y: CGFloat, trackHeight: CGFloat, durationMs: Int64) -> Int64 {
    guard trackHeight > 0, durationMs > 0 else { return 0 }
    let fraction = min(max(y / trackHeight, 0), 1)
    return Int64(fraction * CGFloat(durationMs))
}

/// Picks the one frame of a clip worth being the cover.
///
/// Scrubbed vertically, like the timeline the night itself is read on: the start of
/// the clip at the top, the end at the bottom. Twin of Android's `VideoFrameDialog`
/// — a sheet rather than a dialog because that is what a full-width picture wants on
/// iOS, and the same thing either way.
private struct CoverFrameSheet: View {
    let assetId: String
    let frameMs: Int64
    let onFrameChange: (Int64) -> Void
    let onCancel: () -> Void
    let onConfirm: () -> Void

    @State private var duration: Int64 = 0
    @State private var frame: UIImage?

    private let scrubHeight: CGFloat = 220

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                HStack(spacing: 16) {
                    ZStack {
                        Rectangle().fill(Color(.secondarySystemBackground))
                        if let frame {
                            Image(uiImage: frame).resizable().scaledToFill()
                        } else {
                            ProgressView()
                        }
                    }
                    .frame(height: scrubHeight)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    .clipped()
                    .accessibilityLabel("The frame this clip will use as the cover")

                    // The track: tall and thin, dragged with a thumb marking where in
                    // the clip this frame sits.
                    GeometryReader { geo in
                        let height = max(geo.size.height, 1)
                        let fraction = duration <= 0 ? 0
                            : min(max(CGFloat(frameMs) / CGFloat(duration), 0), 1)
                        ZStack(alignment: .top) {
                            Rectangle().fill(Color(.tertiarySystemFill)).frame(width: 2)
                            Circle().fill(Color.accentColor).frame(width: 20, height: 20)
                                .offset(y: (height - 20) * fraction)
                        }
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .contentShape(Rectangle())
                        .gesture(DragGesture(minimumDistance: 0).onChanged { drag in
                            onFrameChange(scrubFrameMs(y: drag.location.y,
                                                       trackHeight: height,
                                                       durationMs: duration))
                        })
                    }
                    .frame(width: 32, height: scrubHeight)
                }
                .padding(.horizontal)
                Spacer()
            }
            .padding(.top, 16)
            .navigationTitle("Choose the cover frame")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onCancel)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Use this frame (\(formatDuration(frameMs)))", action: onConfirm)
                }
            }
        }
        .task { duration = PhotoLibrary.videoDurationMs(assetId: assetId) }
        // Decoding trails the drag rather than racing it: a frame grab costs more than
        // a finger moves, so this only ever chases the value the scrub has settled on.
        .task(id: frameMs) {
            frame = await PhotoLibrary.videoFrame(assetId: assetId, atMs: frameMs, edgePx: 512)
        }
    }
}

private struct CreatedSheet: View {
    let name: String
    let trackCount: Int
    let refusedCount: Int
    let coverError: String?
    let url: URL
    let onOpen: () -> Void
    let onDone: () -> Void

    var body: some View {
        VStack(spacing: 20) {
            Text("Playlist created").font(.title2).bold()
            Text("\"\(name)\" was created with \(trackCount) songs."
                 + (refusedCount > 0 ? " \(refusedCount) were refused by Spotify." : "")
                 + (coverError.map { " \($0)" } ?? ""))
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

/// Offers the photos the phone took on the night of the show as the playlist
/// cover. Gallery access is only ever asked for after a tap here, so opening a
/// setlist never triggers a permission prompt on its own.
///
/// Twin of Android's `CoverPicker` (`ConfirmScreen.kt`). A clip is offered here like
/// any other capture of the night, standing as its poster frame; which frame it
/// actually goes out as is asked once, on the way to the playlist — see
/// `CoverFrameSheet`.
private struct CoverPicker: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        let s = model.state
        VStack(spacing: 6) {
            if !s.coverCandidateIds.isEmpty {
                TabView(selection: Binding(
                    get: { s.selectedCoverAssetId },
                    set: { model.setCover($0) })
                ) {
                    // Spotify's own collage is always one swipe left of the
                    // suggested photo, so it stays reachable however many photos
                    // follow the suggestion.
                    VStack {
                        Image(systemName: "square.grid.2x2").font(.system(size: 32)).foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color(.secondarySystemBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    .tag(Optional<String>.none)

                    ForEach(s.coverCandidateIds, id: \.self) { assetId in
                        CoverCandidateTile(assetId: assetId).tag(Optional(assetId))
                    }
                }
                .tabViewStyle(.page(indexDisplayMode: .automatic))
                .aspectRatio(1, contentMode: .fit)
                .frame(maxWidth: 240)
                .frame(maxWidth: .infinity)
                Text(s.selectedCoverAssetId == nil
                     ? "Spotify builds the cover from the album art"
                     : "Playlist cover — swipe for another photo, or left for Spotify's collage")
                    .font(.caption).foregroundStyle(.secondary)
                if !s.coverPermissionGranted {
                    Button("Find more from your gallery") { requestGalleryAccess() }
                        .font(.caption)
                }
            } else if !s.coverPermissionGranted {
                Text("Playlist cover").font(.subheadline)
                Text("Use one of your own photos from the show.")
                    .font(.caption).foregroundStyle(.secondary)
                Button("Find photos from that night") { requestGalleryAccess() }
                    .font(.caption)
            } else if s.coverLoading {
                HStack {
                    ProgressView()
                    Text("Looking through your gallery…").font(.caption).foregroundStyle(.secondary)
                }
            } else if s.coverSearched {
                Text("No photos from that night in your gallery — "
                     + "Spotify will build the cover from the album art.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal)
    }

    private func requestGalleryAccess() {
        Task {
            _ = await PhotoLibrary.authorize()
            model.refreshCoverCandidates()
        }
    }
}

private struct CoverCandidateTile: View {
    let assetId: String
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            Rectangle().fill(Color(.secondarySystemBackground))
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .clipped()
        .task { image = await PhotoLibrary.preview(assetId: assetId, edgePx: 512) }
    }
}
