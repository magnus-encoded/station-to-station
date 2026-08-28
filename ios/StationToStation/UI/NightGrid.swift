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

    /// A Note has no bytes and an empty `ref` (#170) — every path below that
    /// resolves a reference has to be handed the visual run instead of the
    /// night, the same split Android's `gigVisuals` makes.
    private var visualMedia: [StoredMedia] {
        model.state.gigMedia.filter { $0.kind != StoredMedia.Kind.note }
    }

    private var hint: ReleaseHint {
        guard let draggingId else { return .none }
        if sharedTargeted { return hintForMoving(visualMedia, id: draggingId, to: .shared) }
        if vaultTargeted { return hintForMoving(visualMedia, id: draggingId, to: .vault) }
        return .none
    }

    /// Whether this grid may be edited at all. Two quite different reasons it may
    /// not be: the light is a preview of what a **Contact** sees and not a place to
    /// change it mid-look, and a **Contact**'s own night was never mine to put
    /// anything on (#327). Asked once here so Add, Remove, the drag and the drop
    /// cannot answer it differently — the drag was the one that would have gone on
    /// working.
    private var editable: Bool {
        model.state.selectedIsMine && !model.state.contactLight
    }

    /// What a Contact is actually offered from the shared band — or the whole
    /// shared band with the light off. Routed through the one rule (#180)
    /// rather than re-derived: `visibleToContacts` also decides what a
    /// handover sends. (It excludes received media too, so it lines up with
    /// `bandsOf(...).shared` exactly when the light is off.)
    private var visibleShared: [StoredMedia] {
        model.state.contactLight
            ? visibleToContacts(visualMedia)
            : bandsOf(visualMedia).shared
    }

    /// What is being held back, only while the light is on. Never rendered as
    /// content — a photo I chose not to share does not get shown to prove it
    /// exists.
    private var withheld: [StoredMedia] {
        model.state.contactLight ? withheldFromContacts(visualMedia) : []
    }

    var body: some View {
        let bands = bandsOf(visualMedia)
        let light = model.state.contactLight
        VStack(alignment: .leading, spacing: 20) {
            if light { contactLightBanner }

            // Tapping a suggestion is an Attach, so it is gated with the rest of them.
            if editable && !model.state.gigMediaSuggestions.isEmpty { suggestions }

            band(
                title: "SHARED",
                mine: visibleShared,
                // Received media never shows to a Contact: passing it on would be
                // publishing on its sender's behalf (see visibleToContacts).
                received: light ? [] : bands.received,
                empty: light ? "Nothing shared from this night." : "Nothing shared yet.",
                hint: hint,
                targeted: sharedTargeted,
                band: .shared
            )
            .onDrop(of: [.text], isTargeted: $sharedTargeted) { drop($0, into: .shared) }

            // The vault is never a Contact's to see. With the light on it is
            // replaced by the audit of what is being kept back, not shown empty.
            if light {
                if !withheld.isEmpty { withheldAudit }
            } else {
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
                if editable {
                    Button { pickingBand = band } label: {
                        Label("Add", systemImage: "plus").font(.system(size: 12))
                    }
                    .tint(amber)
                }
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
                if editable {
                    Button("Remove", role: .destructive) { model.removeMedia(media) }
                }
            }
            // Received media never drags: its disposition is not mine to set. Nor does
            // anything drag on a night that is not mine, or under the light — it is a
            // look, not a grip.
            .onDrag {
                guard editable, media.from == nil else { return NSItemProvider() }
                draggingId = media.id
                return NSItemProvider(object: media.id as NSString)
            }
    }

    private func drop(_ providers: [NSItemProvider], into band: Band) -> Bool {
        // The receiving end of the same guard. Nothing on this night can start a drag
        // when the grid is not editable, but a drop is an open door: it takes text
        // from anywhere, and the id it carries is the only thing it is trusted on.
        guard editable, let provider = providers.first else { return false }
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

    private var contactLightBanner: some View {
        Text("AS YOUR CONTACTS SEE IT")
            .font(.system(size: 10, weight: .semibold)).kerning(1.5).foregroundStyle(amber)
            .padding(.horizontal, 24)
    }

    /// "They see N of M here", and a tap target to look at what is being kept
    /// back — as a count and as blank placeholders, never the photo itself.
    private var withheldAudit: some View {
        let total = visualMedia.count
        return VStack(alignment: .leading, spacing: 8) {
            Text("They see \(visibleShared.count) of \(total) here.")
                .font(.system(size: 12)).foregroundStyle(muted)
                .padding(.horizontal, 24)
            Button {
                model.setShowWithheld(!model.state.showWithheld)
            } label: {
                Text(model.state.showWithheld
                     ? "hide the \(withheld.count) you are keeping back"
                     : "show the \(withheld.count) you are keeping back")
                    .font(.system(size: 12)).underline()
            }
            .tint(muted)
            .padding(.horizontal, 24)

            if model.state.showWithheld {
                LazyVGrid(columns: columns, spacing: 4) {
                    ForEach(withheld, id: \.id) { _ in
                        Rectangle().fill(raised)
                            .overlay(RoundedRectangle(cornerRadius: 2).stroke(faint.opacity(0.4), lineWidth: 1))
                            .aspectRatio(1, contentMode: .fill)
                    }
                }
                .padding(.horizontal, 20)
            }
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
