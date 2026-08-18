import SwiftUI
import UniformTypeIdentifiers

// The night's grid, on the Gig resolution: what I shot that night, drawn from the
// copies the app owns rather than from the library (#98). Idiomatic SwiftUI and
// not a port of the Compose tree — ADR-0001 puts the grid on the plumbing side of
// the line, where the two platforms are allowed to differ.
//
// Two Bands, position is the bit (#171, porting Android's #162). Which band an
// item sits in *is* StoredMedia.personal — there is no badge and nothing to
// open. Attach asks once: the "Add" under each label is the whole of the
// question, answered by which one you tapped. Dragging a tile to the other
// band's row is the only way to change your mind.
//
// ponytail: no live index math while dragging — a moved item always lands at
// the end of its new band's run, rather than at the exact slot under the
// finger. Android tracks strip coordinates to open a mid-row gap; that is
// considerably more code for a difference nobody but the person mid-drag
// notices. Add precise slotting if reordering within a crowded band turns out
// to matter.

private let raised = Color(red: 0x17 / 255, green: 0x12 / 255, blue: 0x1F / 255)
private let ink = Color(red: 0xED / 255, green: 0xE9 / 255, blue: 0xF2 / 255)
private let muted = Color(red: 0x8B / 255, green: 0x82 / 255, blue: 0x99 / 255)
private let faint = Color(red: 0x5A / 255, green: 0x53 / 255, blue: 0x68 / 255)
private let amber = Color(red: 0xE7 / 255, green: 0xB2 / 255, blue: 0x4C / 255)
private let crossed = Color(red: 0x6E / 255, green: 0xC2 / 255, blue: 0x8E / 255)

struct NightGrid: View {
    @EnvironmentObject var model: AppModel
    @State private var pickingBand: Band?
    @State private var draggingId: String?
    @State private var sharedTargeted = false
    @State private var vaultTargeted = false

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 4), count: 3)

    private var hint: ReleaseHint {
        guard let draggingId else { return .none }
        if sharedTargeted { return hintForMoving(model.state.gigMedia, id: draggingId, to: .shared) }
        if vaultTargeted { return hintForMoving(model.state.gigMedia, id: draggingId, to: .vault) }
        return .none
    }

    var body: some View {
        let bands = bandsOf(model.state.gigMedia)
        VStack(alignment: .leading, spacing: 20) {
            if !model.state.gigMediaSuggestions.isEmpty { suggestions }

            band(
                title: "SHARED",
                mine: bands.shared,
                received: bands.received,
                empty: "Nothing shared yet.",
                hint: hint,
                targeted: sharedTargeted,
                band: .shared
            )
            .onDrop(of: [.text], isTargeted: $sharedTargeted) { drop($0, into: .shared) }

            band(
                title: "IN THE VAULT",
                mine: bands.vault,
                received: [],
                empty: "Nothing held back.",
                hint: hint,
                targeted: vaultTargeted,
                band: .vault
            )
            .onDrop(of: [.text], isTargeted: $vaultTargeted) { drop($0, into: .vault) }
        }
        .padding(.vertical, 16)
        .sheet(item: $pickingBand) { band in
            MediaPicker { model.attachMedia(assetIds: $0, to: band) }.ignoresSafeArea()
        }
    }

    private func band(
        title: String,
        mine: [StoredMedia],
        received: [StoredMedia],
        empty: String,
        hint: ReleaseHint,
        targeted: Bool,
        band: Band
    ) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(title)
                    .font(.system(size: 10, weight: .semibold)).kerning(1.5)
                    .foregroundStyle(targeted && hint != .none ? crossed : faint)
                if targeted, let say = say(for: hint, band: band) {
                    Text(say).font(.system(size: 10)).foregroundStyle(crossed)
                }
                Spacer()
                Button { pickingBand = band } label: {
                    Label("Add", systemImage: "plus").font(.system(size: 12))
                }
                .tint(amber)
            }
            .padding(.horizontal, 24)

            if mine.isEmpty && received.isEmpty {
                Text(empty)
                    .font(.system(size: 13)).foregroundStyle(muted)
                    .padding(.horizontal, 24)
            } else {
                LazyVGrid(columns: columns, spacing: 4) {
                    ForEach(mine, id: \.id) { media in tile(media, band: band) }
                    ForEach(received, id: \.id) { media in tile(media, band: band) }
                }
                .padding(.horizontal, 20)
            }
        }
        .padding(.vertical, 6)
        .background(targeted ? crossed.opacity(0.12) : Color.clear)
    }

    private func tile(_ media: StoredMedia, band: Band) -> some View {
        MediaTile(mediaId: media.id, isVideo: media.kind == StoredMedia.Kind.video)
            .contextMenu {
                Button("Remove", role: .destructive) { model.removeMedia(media) }
            }
            // Received media never drags: its disposition is not mine to set.
            .onDrag {
                guard media.from == nil else { return NSItemProvider() }
                draggingId = media.id
                return NSItemProvider(object: media.id as NSString)
            }
    }

    private func drop(_ providers: [NSItemProvider], into band: Band) -> Bool {
        guard let provider = providers.first else { return false }
        _ = provider.loadObject(ofClass: NSString.self) { value, _ in
            guard let id = value as? String else { return }
            DispatchQueue.main.async {
                model.moveMedia(id, to: band)
                draggingId = nil
            }
        }
        return true
    }

    private func say(for hint: ReleaseHint, band: Band) -> String? {
        switch hint {
        case .none: return nil
        case .gained: return band == .shared ? "let go and this becomes a night you shared" : nil
        case .lost: return band == .shared ? nil : "let go and this stops being a night you shared"
        }
    }

    /// The library's own photos from the window (`PhotoWindow`), offered before the
    /// picker is. Tapping one attaches it — the same **Attach** the picker runs,
    /// straight into the shared band, matching the picker's own default.
    private var suggestions: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("From this night")
                .font(.system(size: 12)).foregroundStyle(muted).padding(.horizontal, 24)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(model.state.gigMediaSuggestions, id: \.self) { assetId in
                        Button { model.attachMedia(assetIds: [assetId], to: .shared) } label: {
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

extension Band: Identifiable {
    var id: Self { self }
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
