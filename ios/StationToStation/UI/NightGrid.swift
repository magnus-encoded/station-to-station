import SwiftUI

// The night's grid, on the Gig resolution: what I shot that night, drawn from the
// copies the app owns rather than from the library (#98). Idiomatic SwiftUI and
// not a port of the Compose tree — ADR-0001 puts the grid on the plumbing side of
// the line, where the two platforms are allowed to differ.

private let raised = Color(red: 0x17 / 255, green: 0x12 / 255, blue: 0x1F / 255)
private let ink = Color(red: 0xED / 255, green: 0xE9 / 255, blue: 0xF2 / 255)
private let muted = Color(red: 0x8B / 255, green: 0x82 / 255, blue: 0x99 / 255)
private let faint = Color(red: 0x5A / 255, green: 0x53 / 255, blue: 0x68 / 255)
private let amber = Color(red: 0xE7 / 255, green: 0xB2 / 255, blue: 0x4C / 255)

struct NightGrid: View {
    @EnvironmentObject var model: AppModel
    @State private var picking = false

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 4), count: 3)

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("THE NIGHT")
                    .font(.system(size: 10, weight: .semibold)).kerning(1.5).foregroundStyle(faint)
                Spacer()
                Button { picking = true } label: {
                    Label("Add", systemImage: "plus").font(.system(size: 13))
                }
                .tint(amber)
            }
            .padding(.horizontal, 24)

            if !model.state.gigMediaSuggestions.isEmpty { suggestions }

            if model.state.gigMedia.isEmpty {
                Text("Nothing from this night yet.")
                    .font(.system(size: 13)).foregroundStyle(muted)
                    .padding(.horizontal, 24)
            } else {
                LazyVGrid(columns: columns, spacing: 4) {
                    ForEach(model.state.gigMedia, id: \.id) { media in
                        MediaTile(mediaId: media.id, isVideo: media.kind == StoredMedia.Kind.video)
                            .contextMenu {
                                Button("Remove", role: .destructive) { model.removeMedia(media) }
                            }
                    }
                }
                .padding(.horizontal, 20)
            }
        }
        .padding(.vertical, 16)
        .sheet(isPresented: $picking) {
            MediaPicker { model.attachMedia(assetIds: $0) }.ignoresSafeArea()
        }
    }

    /// The library's own photos from the window (`PhotoWindow`), offered before the
    /// picker is. Tapping one attaches it — the same **Attach** the picker runs.
    private var suggestions: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("From this night")
                .font(.system(size: 12)).foregroundStyle(muted).padding(.horizontal, 24)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(model.state.gigMediaSuggestions, id: \.self) { assetId in
                        Button { model.attachMedia(assetIds: [assetId]) } label: {
                            SuggestionTile(assetId: assetId)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 24)
            }
        }
    }
}

/// One keepsake, drawn from the durable tier. The library is never asked: that is
/// what makes the grid of a night still render once the original is gone.
private struct MediaTile: View {
    let mediaId: String
    let isVideo: Bool
    @State private var image: UIImage?

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            Rectangle().fill(raised)
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            }
            if isVideo {
                Image(systemName: "play.circle.fill")
                    .font(.system(size: 16)).foregroundStyle(ink.opacity(0.9)).padding(6)
            }
        }
        .aspectRatio(1, contentMode: .fill)
        .clipped()
        .task {
            // Off the main actor: a grid of thirty decodes should not stutter the
            // scroll it is being scrolled in.
            image = await Task.detached { PhotoLibrary.gridImage(mediaId) }.value
        }
    }
}

private struct SuggestionTile: View {
    let assetId: String
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            Rectangle().fill(raised)
            if let image { Image(uiImage: image).resizable().scaledToFill() }
        }
        .frame(width: 64, height: 64)
        .clipped()
        .overlay(RoundedRectangle(cornerRadius: 2).stroke(faint.opacity(0.4), lineWidth: 1))
        .task { image = await PhotoLibrary.preview(assetId: assetId, edgePx: 192) }
    }
}
