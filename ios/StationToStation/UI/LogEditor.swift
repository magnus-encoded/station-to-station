import SwiftUI

// The Log: my own witness statement for a night, asserted one tap at a time
// (#169). Ported from Android's `LogEditor` (`ui/BillScreen.kt`) with one
// deliberate cut remaining: the Bill/Act candidate pool, which needs a Bill
// (#172, #173) and iOS has none. The MusicBrainz catalogue the panel ranks
// against is here now — it is what this Room's Curtain fetches when a Log is
// open on a night nobody has posted (#129). The rules that matter (a Gap is
// never corrected, a second correction keeps the first words, restoring is
// never a one-way door, Close is a person's word and nothing else's) are
// unchanged — they live in StoredLog itself and are asserted there.

private let ink = Color(red: 0xED / 255, green: 0xE9 / 255, blue: 0xF2 / 255)
private let muted = Color(red: 0x8B / 255, green: 0x82 / 255, blue: 0x99 / 255)
private let faint = Color(red: 0x5A / 255, green: 0x53 / 255, blue: 0x68 / 255)
private let amber = Color(red: 0xE7 / 255, green: 0xB2 / 255, blue: 0x4C / 255)
private let slate = Color(red: 0x6B / 255, green: 0x7A / 255, blue: 0x8F / 255)

struct LogEditor: View {
    @EnvironmentObject var model: AppModel
    let setlist: FmSetlist

    @State private var typed = ""
    /// Which entry's correction panel is open, if any. One at a time: this is a
    /// room you are standing in, not a list of forms.
    @State private var correcting: Int?

    var body: some View {
        let log = model.state.gigLog
        VStack(alignment: .leading, spacing: 0) {
            Text("THE LOG")
                .font(.system(size: 10, weight: .semibold)).kerning(1.5).foregroundStyle(faint)
            Spacer().frame(height: 6)
            Text(log.songs.isEmpty ? "What did they play?" : "Your log of this night")
                .font(.system(size: 16, design: .serif)).foregroundStyle(ink)
            Text("Yours, on this phone. Only what you tap is recorded — nothing here is guessed on your behalf.")
                .font(.system(size: 11)).foregroundStyle(faint)
            Spacer().frame(height: 10)

            ForEach(Array(log.songs.enumerated()), id: \.offset) { i, song in
                entryRow(i, song, log)
                if correcting == i {
                    let written = log.rememberedAt(i) ?? song
                    CorrectionPanel(
                        written: written,
                        // The whole pool, ranked against what was written down —
                        // never close matches only, because a remembered line
                        // sharing no words with any title still has to be
                        // correctable.
                        candidates: rankTitles(written, catalogue),
                        canRestore: log.rememberedAt(i) != nil,
                        looking: model.state.catalogueFetching != nil,
                        onPick: { model.correctLogEntry(i, title: $0); correcting = nil },
                        onRestore: { model.restoreLogEntry(i); correcting = nil }
                    )
                }
            }

            Spacer().frame(height: 10)
            addField
            gapButton
            Spacer().frame(height: 14)
            closedToggle(log)

            if log.gaps > 0 {
                Text("\(log.gaps) you couldn't name — still true, still in the record")
                    .font(.system(size: 11)).foregroundStyle(faint)
                    .padding(.top, 6)
            }
            if let published, published != log.songs.count {
                Text("setlist.fm has \(published) songs for this night; your log has "
                    + "\(log.songs.count). Neither is changed by the other.")
                    .font(.system(size: 11)).foregroundStyle(slate)
                    .padding(.top, 10)
            }
        }
        .padding(.horizontal, 24).padding(.vertical, 16)
    }

    /// The artist's own songs, as far as this session has been told. Fetched by the
    /// Room's Curtain rather than on open: a catalogue is a prompt for a correction,
    /// and a night nobody is correcting should cost MusicBrainz nothing.
    private var catalogue: [String] {
        guard let mbid = setlist.artist?.mbid, !mbid.isEmpty else { return [] }
        return model.state.catalogueByArtist[mbid] ?? []
    }

    /// How many songs setlist.fm's own record holds, when there is one — and only
    /// once I have written something down. An untouched log beside an imported
    /// setlist is not a divergence, it is a log I have not started.
    private var published: Int? {
        guard setlist.url != nil, !model.state.gigLog.songs.isEmpty else { return nil }
        return setlist.performed().count
    }

    @ViewBuilder
    private func entryRow(_ i: Int, _ song: String, _ log: StoredLog) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Text("\(i + 1)").font(.system(size: 12)).foregroundStyle(faint)
                .frame(width: 20, alignment: .trailing)
            VStack(alignment: .leading, spacing: 1) {
                // A Gap is a song that was played and could not be named. It is in
                // the record on purpose: an acknowledged hole is a true fact.
                Text(song.trimmed.isEmpty ? "— one I couldn't name —" : song)
                    .font(.system(size: 15)).foregroundStyle(song.trimmed.isEmpty ? faint : ink)
                if let remembered = log.rememberedAt(i) {
                    Text("\"\(remembered)\"").font(.system(size: 12)).foregroundStyle(faint)
                }
            }
            // A Gap offers no correction: "one I couldn't name" is an
            // acknowledged fact, not an invitation to guess.
            .contentShape(Rectangle())
            .onTapGesture { if !song.trimmed.isEmpty { correcting = correcting == i ? nil : i } }
            Spacer()
            Text("\u{00D7}").font(.system(size: 18)).foregroundStyle(faint)
                .padding(.horizontal, 10).padding(.vertical, 4)
                .onTapGesture { correcting = nil; model.removeFromLog(i) }
        }
        .padding(.vertical, 6)
    }

    private var addField: some View {
        VStack(alignment: .leading, spacing: 8) {
            TextField("a song they played", text: $typed)
                .font(.system(size: 14)).foregroundStyle(ink)
                .textFieldStyle(.plain)
                .padding(8)
                .background(RoundedRectangle(cornerRadius: 6).fill(faint.opacity(0.12)))
            // The escape hatch, always present and never a fallback: a pool built
            // from what an artist has played before cannot contain a new song, a
            // cover, a guest spot, or anything by an artist setlist.fm has never
            // heard of.
            if !typed.trimmed.isEmpty {
                Text("+ add \"\(typed.trimmed)\"")
                    .font(.system(size: 13)).foregroundStyle(amber)
                    .padding(.vertical, 6)
                    .onTapGesture { model.addToLog(typed.trimmed); typed = "" }
            }
        }
    }

    private var gapButton: some View {
        Text("+ they played one I can't name")
            .font(.system(size: 13)).foregroundStyle(slate)
            .padding(.vertical, 8)
            .onTapGesture { model.addToLog("") }
    }

    /// Whether this log claims to be the whole set. Open is the default and the
    /// honest one; only a person may Close it. The label does not swap with the
    /// state — an unticked box beside "there may be more" is the claim, and
    /// unticked means nobody has made it.
    @ViewBuilder
    private func closedToggle(_ log: StoredLog) -> some View {
        HStack(spacing: 12) {
            RoundedRectangle(cornerRadius: 4)
                .fill(log.closed ? amber : Color.clear)
                .frame(width: 16, height: 16)
                .overlay(RoundedRectangle(cornerRadius: 4).stroke(log.closed ? amber : faint, lineWidth: 1.5))
            VStack(alignment: .leading, spacing: 2) {
                Text("That was the whole set")
                    .font(.system(size: 14)).foregroundStyle(log.closed ? ink : muted)
                Text(log.closed ? "tap if you remember more" : "there may be more until you tick this")
                    .font(.system(size: 11)).foregroundStyle(faint)
            }
        }
        .contentShape(Rectangle())
        .onTapGesture { model.setLogClosed(!log.closed) }
    }
}

/// Correcting one Log entry, in place, under the row it belongs to (#126).
/// Nothing is rewritten without a tap: the pool is a *prompt*, never a claim,
/// and the free-text field above it is the escape hatch that is always present
/// and never a fallback — an artist with nothing known is the ordinary case
/// here, not a failure. Its own view (not a helper method on `LogEditor`) so
/// its typed text is its own state — a room you are standing in, not the "add
/// a song" field wearing a different hat.
private struct CorrectionPanel: View {
    let written: String
    let candidates: [String]
    let canRestore: Bool
    /// Whether a Curtain pull is in flight. "Looking up their songs" and "nothing
    /// known for this artist" mean opposite things to someone mid-correction.
    let looking: Bool
    let onPick: (String) -> Void
    let onRestore: () -> Void

    @State private var typed = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("What was this really called? \"\(written)\" is kept either way.")
                .font(.system(size: 11)).foregroundStyle(slate)
            TextField("the title", text: $typed)
                .font(.system(size: 14)).foregroundStyle(ink)
                .textFieldStyle(.plain)
                .padding(8)
                .background(RoundedRectangle(cornerRadius: 6).fill(faint.opacity(0.12)))
            if !typed.trimmed.isEmpty {
                Text("\u{2192} call it \"\(typed.trimmed)\"")
                    .font(.system(size: 13)).foregroundStyle(amber)
                    .onTapGesture { onPick(typed.trimmed) }
            }
            if candidates.isEmpty {
                Text(looking
                    ? "Looking up their songs\u{2026}"
                    : "Nothing known for this artist \u{2014} type the title above, or pull down to look them up.")
                    .font(.system(size: 11)).foregroundStyle(faint)
                    .padding(.top, 4)
            } else {
                ForEach(candidates, id: \.self) { title in
                    Text(title)
                        .font(.system(size: 14)).foregroundStyle(muted)
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                        .contentShape(Rectangle())
                        .onTapGesture { onPick(title) }
                }
            }
            // A wrong correction is never a one-way door.
            if canRestore {
                Text("\u{21A9} put \"\(written)\" back as the entry")
                    .font(.system(size: 12)).foregroundStyle(slate)
                    .onTapGesture(perform: onRestore)
            }
        }
        .padding(.leading, 28).padding(.bottom, 10)
    }
}

private extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}
