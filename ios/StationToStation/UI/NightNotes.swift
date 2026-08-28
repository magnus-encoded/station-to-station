import SwiftUI

// The Note: media with a Personal bit (#50, #170). Not a new container, not a
// new privacy model — StoredMedia with Kind.note, sitting in one of the two
// Bands NightGrid already draws. Drafting is the vault; publishing is
// dragging it up (a long-press here, the same gesture the grid uses for a
// drag).
//
// Idiomatic SwiftUI and not a literal port of Android's GigNotes/BandNotes/
// VerdictThumbs (`ui/StationScreen.kt`) — ADR-0001 puts this on the plumbing
// side of the line. What must agree across platforms already does, in
// Preamble.swift and MediaBands.swift, both asserted by their own tests.

private let ink = Color(red: 0xED / 255, green: 0xE9 / 255, blue: 0xF2 / 255)
private let muted = Color(red: 0x8B / 255, green: 0x82 / 255, blue: 0x99 / 255)
private let faint = Color(red: 0x5A / 255, green: 0x53 / 255, blue: 0x68 / 255)
private let slate = Color(red: 0x6D / 255, green: 0x7E / 255, blue: 0x9B / 255)
private let crossed = Color(red: 0x6F / 255, green: 0xBF / 255, blue: 0x9C / 255)
private let amber = Color(red: 0xE7 / 255, green: 0xB2 / 255, blue: 0x4C / 255)
private let unlitField = Color(red: 0x1C / 255, green: 0x17 / 255, blue: 0x26 / 255)

/// The band's own colour, and the same three facts the grid's frames carry:
/// **amber** is the vault and means *only I can read this*, **slate** is a shared
/// band holding only my prose, **crossed** is a shared band more than one of us
/// wrote in. Twin of Android's `bandAccent`.
private func bandAccent(_ band: Band, crossed isCrossed: Bool) -> Color {
    if band == .vault { return amber }
    return isCrossed ? crossed : slate
}

private func bandMeaning(_ band: Band, _ isCrossed: Bool) -> String {
    if band == .vault { return "In the vault, only you can read these" }
    return isCrossed ? "Shared, more than one of you wrote here" : "Shared, only yours so far"
}

func verdictGlyph(_ verdict: String?) -> String {
    switch verdict {
    case StoredMedia.Verdict.down: return "\u{1F44E}"
    case StoredMedia.Verdict.up: return "\u{1F44D}"
    case StoredMedia.Verdict.doubleUp: return "\u{1F44D}\u{1F44D}"
    default: return ""
    }
}

/// Both Bands' Notes for the open night, shared above vault always — the same
/// order the Bands are drawn in and the same claim: up is what my Audience
/// reads, down is what reaches nobody.
struct NightNotes: View {
    @EnvironmentObject var model: AppModel
    /// The night's own facts, already composed (see `preamble(people:venue:songCount:)`).
    /// Empty when the record knows nothing.
    let preamble: String
    let senderName: (String) -> String?

    /// What a Contact would actually be shown, routed through the one rule
    /// (#180) — the same gate NightGrid's `visibleShared` uses — before it is
    /// split into Notes at all.
    private var contactMedia: [StoredMedia] {
        model.state.contactLight ? visibleToContacts(model.state.gigMedia) : model.state.gigMedia
    }

    private var noteBands: MediaBandSplit {
        bandsOf(contactMedia.filter { $0.kind == StoredMedia.Kind.note })
    }

    var body: some View {
        let bands = noteBands
        let contactLight = model.state.contactLight
        // The same question the grid asks, answered the same way: a Contact's night
        // was never mine to write on, and the light is a look and not a desk (#327).
        let editable = model.state.selectedIsMine && !contactLight
        VStack(alignment: .leading, spacing: 16) {
            BandNote(
                band: .shared,
                mine: bands.shared.first,
                received: bands.received,
                // The prose's own crossing, not the night's: this outline is a
                // statement about what is written here (#268).
                crossed: bands.crossed,
                // Once per night, over whichever note is uppermost. The same
                // sentence twice is noise, and it is a fact about the night
                // rather than about either band.
                preamble: bands.shared.isEmpty ? "" : preamble,
                senderName: senderName,
                editable: editable,
                onWrite: { model.setGigNote(.shared, text: $0) },
                onVerdict: { v in if let id = bands.shared.first?.id { model.setGigVerdict(id, verdict: v) } },
                // Withdrawing: the same move a photograph makes, through the
                // same function. One note per band, so there is no index.
                onLift: { id in model.moveMedia(id, to: .vault) }
            )
            // Absent under the contact light for the reason the vault strip
            // is: a Contact cannot see the vault, and an empty row drawn
            // there would claim nothing is held back over a vault that
            // holds something.
            if !contactLight {
                BandNote(
                    band: .vault,
                    mine: bands.vault.first,
                    // Nothing arrives here. A Contact's note is something
                    // they put in the commons; there is no path by which one
                    // lands in my vault.
                    received: [],
                    // Never — the vault outlines amber whatever it holds.
                    crossed: false,
                    preamble: bands.shared.isEmpty ? preamble : "",
                    senderName: senderName,
                    // Was `true`: the vault is only ever mine, which is true of the
                    // *band* and says nothing about whose *night* this is (#327).
                    editable: editable,
                    onWrite: { model.setGigNote(.vault, text: $0) },
                    onVerdict: { v in if let id = bands.vault.first?.id { model.setGigVerdict(id, verdict: v) } },
                    // Publishing a draft. The upward move earns the green
                    // promise for free, because `hintForMoving` never asked
                    // what kind of item it was holding.
                    onLift: { id in model.moveMedia(id, to: .shared) }
                )
            }
        }
        .padding(.horizontal, 24)
    }
}

/// One band's prose: my Note, then any Received ones.
///
/// Position is the bit here too. There is no switch and no badge — a note in
/// the lower band reaches nobody, a note in the upper one reaches my
/// Audience, and moving it is the act that changes its mind. Which write-line
/// you tapped is which question you answered, so nothing has to ask a second
/// time.
private struct BandNote: View {
    let band: Band
    let mine: StoredMedia?
    let received: [StoredMedia]
    /// More than one of us in this band's prose — see `bandAccent`.
    let crossed: Bool
    let preamble: String
    let senderName: (String) -> String?
    let editable: Bool
    let onWrite: (String) -> Void
    let onVerdict: (String?) -> Void
    let onLift: (String) -> Void

    @State private var editing = false
    @State private var draft = ""
    @State private var expanded = false
    @FocusState private var focused: Bool

    var body: some View {
        let accent = bandAccent(band, crossed: crossed)
        // A Contact looking at a night nobody wrote about gets no frame around the
        // nothing. The write-line is what an empty frame is *for*, and there isn't one.
        return Group {
            if !editable && mine == nil && received.isEmpty {
                EmptyView()
            } else {
                content(accent)
            }
        }
    }

    private func content(_ accent: Color) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(band == .shared ? "SHARED" : "IN THE VAULT")
                .font(.system(size: 10, weight: .semibold)).kerning(1.5).foregroundStyle(faint)
                .accessibilityLabel(bandMeaning(band, crossed))

            // Always first, whether it opens the field or reopens it over what is
            // already there. Everything written lands underneath — you come here to
            // write, and reading a Contact *after* saying your own piece is the order
            // that keeps the sentence yours (#268).
            if editable && !editing {
                Text(mine != nil ? "Edit"
                     : band == .shared ? "Write something to share"
                     : "Write something just for you")
                    .font(.system(size: 12))
                    .foregroundStyle(mine != nil ? accent : muted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 6)
                    .onTapGesture { draft = mine?.text ?? ""; editing = true }
            }

            if editing {
                editor(accent)
            } else if let mine {
                mineNote(mine)
            }

            ForEach(received, id: \.id) { note in
                VStack(alignment: .leading, spacing: 2) {
                    // A name where the key resolves to one, and never an
                    // invented name: the same degradation the green promise
                    // makes.
                    Text(senderName(note.from ?? "") ?? "Someone else")
                        .font(.system(size: 11)).foregroundStyle(slate)
                    Text(note.text).font(.system(size: 13)).foregroundStyle(slate)
                    if let verdict = note.verdict {
                        Text(verdictGlyph(verdict)).font(.system(size: 13)).foregroundStyle(slate)
                    }
                }
                .padding(.top, 4)
            }
        }
        // One frame for the whole band, thickening while it is being written in —
        // the same language the grid's strips use, and for the same reason: a second
        // outline around the field inside this one is two boundaries drawn for one
        // boundary. It was amber besides, which on a shared note is the colour of
        // the other answer (#268).
        .padding(.horizontal, 10).padding(.vertical, 8)
        .overlay(
            RoundedRectangle(cornerRadius: 6)
                .strokeBorder(accent, lineWidth: editing ? 2 : 1)
        )
    }

    // One field, no toolbar. The phone is the wrong surface for long form
    // (ADR-0012) and the answer is to keep the room visible around it, not
    // to grow an editor.
    private func editor(_ accent: Color) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            TextEditor(text: $draft)
                .font(.system(size: 13))
                .foregroundStyle(ink)
                .scrollContentBackground(.hidden)
                .frame(minHeight: 108)
                .padding(6)
                .background(RoundedRectangle(cornerRadius: 6).fill(unlitField))
                .overlay(RoundedRectangle(cornerRadius: 6).stroke(accent, lineWidth: 1))
                .focused($focused)
                .onAppear { focused = true }
            HStack(spacing: 16) {
                Text("done").font(.system(size: 12)).foregroundStyle(accent)
                    .onTapGesture { onWrite(draft); editing = false }
                Text("discard").font(.system(size: 12)).foregroundStyle(faint)
                    .onTapGesture { draft = mine?.text ?? ""; editing = false }
            }
        }
    }

    private func mineNote(_ mine: StoredMedia) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            if !preamble.isEmpty {
                // Not editable, and drawn apart from the typed text: nothing
                // generated may be mistaken for something I said.
                Text(preamble).font(.system(size: 11)).foregroundStyle(faint)
            }
            Text(mine.text)
                .font(.system(size: 13)).foregroundStyle(ink)
                .lineLimit(expanded ? nil : 3)
                .onTapGesture { expanded.toggle() }
                // A long-press lifts the note into the other band. The same
                // act as dragging a photograph across, minus the index — one
                // note per band means there is no position to choose.
                .onLongPressGesture { if editable { onLift(mine.id) } }
            // Editing is the line above now, so this row is the verdict alone.
            if editable { VerdictThumbs(current: mine.verdict, onVerdict: onVerdict) }
        }
    }
}

/// Down, up, up twice — and unset, which is reachable by tapping the one
/// that is set.
private struct VerdictThumbs: View {
    let current: String?
    let onVerdict: (String?) -> Void

    private let all = [StoredMedia.Verdict.down, StoredMedia.Verdict.up, StoredMedia.Verdict.doubleUp]

    private func verdictLabel(_ v: String) -> String {
        switch v {
        case StoredMedia.Verdict.down: return "Rate down"
        case StoredMedia.Verdict.up: return "Rate up"
        default: return "Rate up twice"
        }
    }

    var body: some View {
        HStack(spacing: 10) {
            // Choosing one takes the others away: three glyphs left standing beside
            // the chosen one read as three unmade choices (#268). Tapping what is
            // left reopens the question.
            ForEach(all.filter { current == nil || current == $0 }, id: \.self) { v in
                Text(verdictGlyph(v))
                    .font(.system(size: 15))
                    .foregroundStyle(current == v ? amber : faint)
                    .accessibilityLabel(verdictLabel(v))
                    .accessibilityAddTraits(current == v ? [.isButton, .isSelected] : .isButton)
                    .onTapGesture { onVerdict(current == v ? nil : v) }
            }
        }
    }
}
