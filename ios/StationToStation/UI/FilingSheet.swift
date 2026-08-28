import SwiftUI
import UIKit

// Handing a night over to setlist.fm (#169). Android copies the set, parks the other
// four facts in the notification shade and opens the form; the shade is the one
// surface that stays in reach while Chrome has the foreground.
//
// iOS has no shade to park anything in — but it does not need one, because a sheet
// that is still open when you come back *is* the surface that stays in reach. Swipe
// to Safari, swipe back, tap the next field, swipe forward. So the divergence is in
// the vehicle only (ADR-0017): the Grammar is unchanged — the night's facts travel
// with you rather than in the Historian's head, which is where a wrong venue comes
// from.
//
// One field at a time, because the clipboard holds one thing. The songs are already
// on it when this opens: they are the one field the form takes as a paste, and the
// reason you came.

private let ground = Color(red: 0x0E / 255, green: 0x0B / 255, blue: 0x14 / 255)
private let ink = Color(red: 0xED / 255, green: 0xE9 / 255, blue: 0xF2 / 255)
private let muted = Color(red: 0x8B / 255, green: 0x82 / 255, blue: 0x99 / 255)
private let faint = Color(red: 0x5A / 255, green: 0x53 / 255, blue: 0x68 / 255)
private let amber = Color(red: 0xE7 / 255, green: 0xB2 / 255, blue: 0x4C / 255)
private let slate = Color(red: 0x6D / 255, green: 0x7E / 255, blue: 0x9B / 255)

struct FilingSheet: View {
    @Environment(\.openURL) private var openURL
    let setlist: FmSetlist
    let log: StoredLog
    let onDismiss: () -> Void

    /// Which field is on the clipboard right now. One, because the clipboard holds
    /// one — saying "copied" beside two of them would be a claim about a clipboard
    /// that does not exist.
    @State private var copied: String?

    var body: some View {
        let fields = filingFields(setlist, log)
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text(log.songs.isEmpty
                         ? "Nothing logged yet \u{2014} the gig itself is still worth adding."
                         : "The form asks for these, in this order. Tap one to put it on the clipboard.")
                        .font(.system(size: 13)).foregroundStyle(muted)
                        .padding(.bottom, 4)
                    // Date before Venue is the form's own order and it is
                    // load-bearing: setlist.fm disables the venue field until a date
                    // is set, so a tray that offered Venue first would be offering a
                    // value with nowhere to go.
                    ForEach(fields, id: \.label) { field in
                        row(field)
                        Divider().overlay(faint.opacity(0.3))
                    }
                    Text("This stays open. Come back for the next one.")
                        .font(.system(size: 12)).foregroundStyle(faint)
                        .padding(.top, 14)
                }
                .padding(.horizontal, 24).padding(.vertical, 16)
            }
            .background(ground)
            .navigationTitle("File this night")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Done", action: onDismiss).tint(faint)
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        openURL(URL(string: setlistEditEntry(setlist))!)
                    } label: {
                        Text("setlist.fm \u{2197}")
                    }
                    .tint(amber)
                }
            }
        }
        .onAppear {
            // The songs are the one field the form takes as a paste, and the reason
            // anyone opened this. An empty log copies nothing rather than clearing
            // whatever the clipboard already held.
            let paste = setlistPaste(log)
            if !paste.isEmpty {
                UIPasteboard.general.string = paste
                copied = "Songs"
            }
        }
    }

    private func row(_ field: FilingField) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Text(field.label.uppercased())
                .font(.system(size: 10, weight: .semibold)).kerning(1.5).foregroundStyle(faint)
                .frame(width: 56, alignment: .leading)
                .padding(.top, 3)
            Text(field.shown).font(.system(size: 15)).foregroundStyle(ink)
            Spacer(minLength: 8)
            Text(copied == field.label ? "copied" : "copy")
                .font(.system(size: 12))
                .foregroundStyle(copied == field.label ? amber : slate)
                .padding(.top, 3)
        }
        .padding(.vertical, 12)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityHint(copied == field.label ? "On the clipboard" : "Copies this field")
        .onTapGesture {
            UIPasteboard.general.string = field.value
            copied = field.label
        }
    }
}
