import SwiftUI

private let slate = Color(red: 0x6D / 255, green: 0x7E / 255, blue: 0x9B / 255)

/// What a shared **Ticket** read, put in front of the person holding it (#412).
///
/// **This is the norm and not the error path.** The parse function reports what it
/// found; this asks. A ticket that read perfectly still comes through here unless all
/// four facts are present, and one that read nothing comes through here too — an
/// honest blank form with a line saying so beats a guess on the **Line**.
///
/// The **Admissions** are on it as they will be shown at the door (#441, story 7), not
/// as fields: nothing about a barcode is a person's to correct, and they are kept
/// whatever is done with the rest. What the form adds is the chance to see, while the
/// PDF is still to hand, that one cannot be shown (story 29) or that none was read at
/// all (story 8).
struct ConfirmTicketSheet: View {
    let ticket: Ticket
    /// `TicketDraft.possibleMatch`: said on the form, never acted on.
    let possibleMatch: String?
    let onAdd: (String, String, String) -> Void
    let onCancel: () -> Void

    @EnvironmentObject var model: AppModel
    @State private var artist: String
    @State private var venue: String
    @State private var date: String
    /// The last spelling picked from the list, so writing it into the field is not
    /// mistaken for typing it — the same guard `AddPlannedGigSheet` needs.
    @State private var picked = ""

    init(ticket: Ticket,
         possibleMatch: String? = nil,
         onAdd: @escaping (String, String, String) -> Void,
         onCancel: @escaping () -> Void) {
        self.ticket = ticket
        self.possibleMatch = possibleMatch
        self.onAdd = onAdd
        self.onCancel = onCancel
        _artist = State(initialValue: ticket.artist ?? "")
        _venue = State(initialValue: ticket.venue ?? "")
        _date = State(initialValue: ticket.date.map { fmDate($0) } ?? "")
    }

    private var ready: Bool {
        !artist.trimmingCharacters(in: .whitespaces).isEmpty
            && !date.trimmingCharacters(in: .whitespaces).isEmpty
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("who's playing", text: $artist)
                        .onChange(of: artist) { name in
                            if name != picked { model.suggestArtists(name) }
                        }
                    // Suggestions matter more here than anywhere else: the name in
                    // this field came off an OCR pass, so a near miss is the expected
                    // case rather than a typo.
                    ForEach(model.state.artistSuggestions.prefix(4)) { hit in
                        Button {
                            picked = hit.name
                            artist = hit.name
                            model.clearArtistSuggestions()
                        } label: {
                            Text(hit.disambiguation.isEmpty
                                 ? hit.name : "\(hit.name)  · \(hit.disambiguation)")
                                .font(.footnote).foregroundStyle(slate)
                        }
                    }
                    TextField("venue (optional)", text: $venue)
                    TextField("date (dd-MM-yyyy)", text: $date)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                } footer: {
                    Text(footer)
                }
                if !ticket.admissions.isEmpty {
                    Section {
                        ConfirmAdmissions(admissions: ticket.admissions)
                    } header: {
                        Text(ticket.admissions.count == 1
                             ? "The barcode, as the door will see it"
                             : "The \(ticket.admissions.count) barcodes, as the door will see them")
                    }
                }
            }
            .navigationTitle("From your ticket")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { model.clearArtistSuggestions(); onCancel() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") {
                        model.clearArtistSuggestions()
                        onAdd(artist, venue, date)
                    }
                    .disabled(!ready)
                }
            }
        }
    }

    private var footer: String {
        if ticket.isEmpty {
            return "Nothing could be read from this PDF — no text layer, or a layout "
                + "this app can't make sense of. Fill it in and it goes on your line "
                + "like any night you add by hand."
        }
        var lines = [possibleMatch.map { "This looks like a night already on your line (\($0)). "
                         + "Check it before saving: it is added to that night only if who's "
                         + "playing and the date match it." }
                     ?? "Read from the ticket. Check it before it goes on your line."]
        switch ticket.admissions.count {
        case 0:
            // Story 8: the text read, the barcode did not. Said here, not discovered at
            // the door.
            lines.append("No barcode could be read off this ticket, so the app has nothing "
                         + "to show at the door. Bring the original PDF.")
        case 1: lines.append("Its barcode is kept whatever you put here.")
        case let n: lines.append("Its \(n) barcodes are kept whatever you put here.")
        }
        return lines.joined(separator: " ")
    }
}
