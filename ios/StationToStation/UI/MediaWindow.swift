import AVKit
import SwiftUI

/// Unstamped. `-1` rather than `0`, because `0` is a real time: the first song.
let notStamped: Int64 = -1

/// mm:ss, or h:mm:ss once a recording runs past the hour — a full gig usually does.
func formatOffset(_ ms: Int64) -> String {
    let total = ms / 1000
    let h = total / 3600
    return h > 0
        ? String(format: "%d:%02d:%02d", h, (total % 3600) / 60, total % 60)
        : String(format: "%d:%02d", total / 60, total % 60)
}

// --- The palette, per file as everywhere else in this package ---
private let ink = Color(red: 0xED / 255, green: 0xE9 / 255, blue: 0xF2 / 255)
private let muted = Color(red: 0x8B / 255, green: 0x82 / 255, blue: 0x99 / 255)
private let faint = Color(red: 0x5A / 255, green: 0x53 / 255, blue: 0x68 / 255)
private let amber = Color(red: 0xE7 / 255, green: 0xB2 / 255, blue: 0x4C / 255)

/// A **Window** onto one keepsake: a photograph at its own size, a clip played back.
///
/// It opens here rather than in Photos because the night is the frame this belongs in
/// — and because when the keepsake is the night's whole recording, the setlist rides
/// underneath it. Play, and tap a song as it starts to record where it sits in the
/// video (#27). Nothing is inferred from a stamp: the recording and the setlist need
/// not hold the same songs, so a clip setlist.fm left out sits in the gap between two.
///
/// Twin of Android's `MediaViewerDialog`.
struct MediaWindow: View {
    let media: StoredMedia
    /// The night's songs — empty unless this *is* the recording. A one-song clip is
    /// still just a keepsake, and a song list under it would be noise.
    var songs: [FmSong] = []
    var offsets: [Int64] = []
    /// Where to start, when the Window was opened by tapping a stamped song.
    var startAtMs: Int64 = notStamped
    var onStamp: (Int, Int64) -> Void = { _, _ in }
    let onDismiss: () -> Void

    @State private var player: AVPlayer?
    @State private var image: UIImage?

    private var isVideo: Bool { media.kind == StoredMedia.Kind.video }

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.ignoresSafeArea()
            content
            Button(action: onDismiss) {
                Image(systemName: "xmark").font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(.white).padding(12)
            }
            .accessibilityLabel("Close")
        }
        .task {
            if isVideo {
                let item = await PhotoLibrary.playerItem(assetId: media.ref)
                guard let item else { return }
                let p = AVPlayer(playerItem: item)
                if startAtMs > notStamped { await seek(p, to: startAtMs) }
                player = p
                p.play()
            } else {
                image = await PhotoLibrary.fullImage(assetId: media.ref)
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        if isVideo && !songs.isEmpty {
            VStack(spacing: 0) {
                VideoPlayer(player: player)
                    .frame(maxHeight: .infinity)
                    .layoutPriority(0.45)
                Text("Tap a song as it starts. Long-press to clear.")
                    .font(.system(size: 11)).foregroundStyle(faint)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 20).padding(.top, 10).padding(.bottom, 6)
                List(Array(songs.enumerated()), id: \.offset) { index, song in
                    stampRow(index, song)
                        .listRowBackground(Color.black)
                        .listRowSeparator(.hidden)
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .layoutPriority(0.55)
            }
        } else if isVideo {
            VideoPlayer(player: player)
        } else if let image {
            Image(uiImage: image).resizable().scaledToFit()
                .accessibilityLabel("Your photo from this show")
                .onTapGesture(perform: onDismiss)
        } else {
            ProgressView().tint(.white)
        }
    }

    /// One song inside the recording. A stamped song is a place to jump to; an
    /// unstamped one is a place to mark. The same row, told apart by whether it
    /// already knows where it lives.
    private func stampRow(_ index: Int, _ song: FmSong) -> some View {
        let at = offsets.indices.contains(index) ? offsets[index] : notStamped
        let stamped = at > notStamped
        return HStack(spacing: 0) {
            Text("\(index + 1)").font(.system(size: 11)).foregroundStyle(faint).frame(width: 24, alignment: .leading)
            Text(song.name).font(.system(size: 15)).foregroundStyle(stamped ? ink : muted)
                .frame(maxWidth: .infinity, alignment: .leading)
            Text(stamped ? formatOffset(at) : "–")
                .font(.system(size: 12)).foregroundStyle(stamped ? amber : faint)
        }
        .padding(.vertical, 11)
        .contentShape(Rectangle())
        .onTapGesture {
            guard let player else { return }
            if stamped {
                Task { await seek(player, to: at) }
            } else {
                // A player that has not loaded yet answers NaN, and NaN cast to
                // Int64 is a crash, not a zero.
                let now = player.currentTime().seconds
                onStamp(index, now.isFinite ? Int64(now * 1000) : 0)
            }
        }
        .onLongPressGesture { if stamped { onStamp(index, notStamped) } }
        .accessibilityLabel(song.name)
        .accessibilityValue(stamped ? "at \(formatOffset(at))" : "not stamped")
    }

    private func seek(_ player: AVPlayer, to ms: Int64) async {
        await player.seek(to: CMTime(value: CMTimeValue(max(ms, 0)), timescale: 1000))
    }
}
