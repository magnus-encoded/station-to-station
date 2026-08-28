import SwiftUI

// The Log: my own witness statement for a night, asserted one tap at a time
// (#169). Ported from Android's `LogEditor` (`ui/BillScreen.kt`). The Bill/Act
// candidate pool is here since #172 gave iOS a Bill to have one from — it is the
// songs this act has been playing lately, offered to tap rather than type, and
// named with whose they are. The MusicBrainz catalogue the panel ranks
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

            // The entries themselves are on the spine above, woven into the set
            // (#268) — what is left here is the way in.
            addField
            gapButton
            pool
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

    /// How many songs setlist.fm's own record holds, when there is one — and only
    /// once I have written something down. An untouched log beside an imported
    /// setlist is not a divergence, it is a log I have not started.
    private var published: Int? {
        guard setlist.url != nil, !model.state.gigLog.songs.isEmpty else { return nil }
        return setlist.performed().count
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
                // The way out of a wrong match, on the song already typed above. It gets
                // no field of its own: "name a song you know they play" is the same song
                // — one you just heard them play is one you know they play — and two
                // fields wanting the same thing is a question about which, with no
                // answer. A picker would be worse: it offers five identical names, and
                // the names being identical is the entire problem.
                //
                // Only the tap decides where it goes. "+ add" puts it in the **Log**;
                // this looks the band up and writes nothing — naming a song to identify
                // a band is not a claim they played it tonight.
                if act?.matchedArtist.isEmpty == false {
                    let searching = model.state.billFetching != nil
                    Text(searching ? "looking for a band that plays it\u{2026}"
                         : "\u{2192} not them? find who plays \"\(typed.trimmed)\"")
                        .font(.system(size: 13)).foregroundStyle(searching ? faint : slate)
                        .padding(.vertical, 6)
                        .onTapGesture {
                            guard !searching else { return }
                            model.disambiguateAct(gigId: setlist.id, song: typed.trimmed)
                        }
                }
            }
        }
    }

    /// The **Act** this night was minted from, if a **Bill** minted it. Nil for an
    /// ordinary night, which is most of them — everything below reads as absent then.
    private var act: StoredAct? {
        model.state.bills.lazy.compactMap { bill in
            bill.acts.first { $0.gigId == setlist.id }
        }.first
    }

    /// The songs this artist has been playing lately, minus the ones already in the
    /// **Log** — a prompt to tap, never a claim. Nothing enters the record until it is
    /// tapped, so "I think they played X" never becomes "they played X" by inaction.
    @ViewBuilder
    private var pool: some View {
        let chosen = model.state.gigLog.songs
        let remaining = (act?.candidates ?? [])
            .filter { c in !chosen.contains { $0.lowercased() == c.lowercased() } }
        if !remaining.isEmpty {
            Spacer().frame(height: 14)
            // Named, not implied. The pool comes from whichever artist a name search
            // landed on, and names are not unique — this line is what turns a wrong
            // match from an invisible corruption into an obvious one.
            Text((act?.matchedArtist.isEmpty == false ? "\(act!.matchedArtist) has" : "They have")
                 + " been playing these — tap the ones you heard")
                .font(.system(size: 11, weight: .semibold)).foregroundStyle(slate)
            Spacer().frame(height: 4)
            ForEach(remaining, id: \.self) { song in
                HStack(spacing: 8) {
                    Text("+").font(.system(size: 13)).foregroundStyle(slate)
                    Text(song).font(.system(size: 14)).foregroundStyle(ink)
                    Spacer(minLength: 0)
                }
                .padding(.vertical, 7)
                .contentShape(Rectangle())
                .onTapGesture { model.addToLog(song) }
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

/// One Log entry's correction, placed under whichever spine row holds it. Its own
/// view so the catalogue lookup stays in one file with the rest of the Log — GigView
/// draws the row, this answers "what was it really called?" (#268).
struct LogCorrection: View {
    @EnvironmentObject var model: AppModel
    let setlist: FmSetlist
    let index: Int
    let onDone: () -> Void

    var body: some View {
        let log = model.state.gigLog
        let written = log.rememberedAt(index) ?? log.songs[index]
        CorrectionPanel(
            written: written,
            // The whole pool, ranked against what was written down — never close
            // matches only, because a remembered line sharing no words with any
            // title still has to be correctable.
            candidates: rankTitles(written, catalogue),
            canRestore: log.rememberedAt(index) != nil,
            looking: model.state.catalogueFetching != nil,
            onPick: { model.correctLogEntry(index, title: $0); onDone() },
            onRestore: { model.restoreLogEntry(index); onDone() }
        )
    }

    /// The artist's own songs, as far as this session has been told. Fetched by the
    /// Room's Curtain rather than on open: a catalogue is a prompt for a correction,
    /// and a night nobody is correcting should cost MusicBrainz nothing.
    private var catalogue: [String] {
        guard let mbid = setlist.artist?.mbid, !mbid.isEmpty else { return [] }
        return model.state.catalogueByArtist[mbid] ?? []
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
