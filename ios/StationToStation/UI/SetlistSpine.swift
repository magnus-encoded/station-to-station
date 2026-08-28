import SwiftUI

// The night's set as one line down the page (#268): setlist.fm's record and my Log
// woven into a single running order by `weaveSetlist`, drawn on one spine.
//
// The spine is what makes the weave readable. A song both records hold is one row
// with an amber ring; a song only setlist.fm has is a pale one; a song only I wrote
// down is amber with no number, because a number is a position in a *record* and
// that record is setlist.fm's wherever there is one. Twin of Android's `SongRow` and
// `LoggedRow` (`ui/StationScreen.kt`).

private let ground = Color(red: 0x0E / 255, green: 0x0B / 255, blue: 0x14 / 255)
private let ink = Color(red: 0xED / 255, green: 0xE9 / 255, blue: 0xF2 / 255)
private let muted = Color(red: 0x8B / 255, green: 0x82 / 255, blue: 0x99 / 255)
private let faint = Color(red: 0x5A / 255, green: 0x53 / 255, blue: 0x68 / 255)
private let amber = Color(red: 0xE7 / 255, green: 0xB2 / 255, blue: 0x4C / 255)
private let lineCol = Color(red: 0x2E / 255, green: 0x27 / 255, blue: 0x40 / 255)
private let lineLit = Color(red: 0x4A / 255, green: 0x3F / 255, blue: 0x63 / 255)

/// One song on the spine, whichever record it came from.
struct SpineRow: View {
    /// Its place in the published set. Nil for a tape track and for a song only my
    /// Log holds — both happened, both sit on the line, neither is a numbered song.
    let number: Int?
    let title: String
    /// "X cover", where there is one.
    let note: String?
    /// The words I wrote before a title replaced them, where there were any. Kept for
    /// the reason it is always kept: it is often *the* memory (#126).
    let remembered: String?
    /// Two records, independently, agree — or this row is my Log's alone. Either way
    /// I am on it, and amber means mine at every Resolution (ADR-0006).
    let mine: Bool
    /// A Gap: played, and could not be named. Drawn as a filled dot, because there is
    /// no title for the ring to stand beside.
    var gap: Bool = false
    var onTap: (() -> Void)? = nil
    /// Drops my Log entry, leaving setlist.fm's row where it was.
    var onRemove: (() -> Void)? = nil

    private var ringSize: CGFloat { number == nil ? 8 : 18 }

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            ZStack(alignment: .top) {
                // The line runs the full height of the row and the disc is painted in
                // the page's own colour on top of it, so it passes *underneath* the
                // number rather than showing through it (#268).
                Rectangle().fill(lineCol).frame(width: 2)
                    .frame(maxHeight: .infinity)
                Circle()
                    .fill(gap && number == nil ? amber : ground)
                    .frame(width: ringSize, height: ringSize)
                    .overlay(Circle().strokeBorder(mine ? amber : lineLit, lineWidth: 1.5))
                    .overlay(
                        // Centred on the digit itself: `.font` alone leaves the glyph
                        // sitting high in a circle this small.
                        Text(number.map { "\($0)" } ?? "")
                            .font(.system(size: 10, weight: .medium))
                            .foregroundStyle(mine ? amber : faint)
                    )
                    .padding(.top, number == nil ? 7 : 2)
            }
            .frame(width: 50)

            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(.system(size: 15))
                    .foregroundStyle(gap ? faint : (number == nil && !mine ? muted : ink))
                if let note { Text(note).font(.system(size: 11)).foregroundStyle(faint) }
                if let remembered {
                    Text("\"\(remembered)\"").font(.system(size: 12)).foregroundStyle(faint)
                }
            }
            .padding(.top, 1).padding(.bottom, 15)
            Spacer(minLength: 0)

            if let onRemove {
                Text("\u{00D7}").font(.system(size: 20)).foregroundStyle(faint)
                    .padding(.horizontal, 10)
                    .accessibilityLabel("Remove")
                    .onTapGesture(perform: onRemove)
            }
        }
        .fixedSize(horizontal: false, vertical: true)
        .padding(.trailing, 20)
        .contentShape(Rectangle())
        .onTapGesture { onTap?() }
    }
}

struct EncoreLabel: View {
    var body: some View {
        Text("\u{2014} ENCORE \u{2014}")
            .font(.system(size: 11, weight: .semibold)).kerning(2)
            .foregroundStyle(amber)
            .padding(.leading, 50).padding(.top, 4).padding(.bottom, 14)
    }
}
