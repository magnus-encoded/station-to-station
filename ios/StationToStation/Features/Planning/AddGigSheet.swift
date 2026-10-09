import SwiftUI

private let ink = Color(red: 0xED / 255, green: 0xE9 / 255, blue: 0xF2 / 255)
private let muted = Color(red: 0x8B / 255, green: 0x82 / 255, blue: 0x99 / 255)
private let faint = Color(red: 0x5A / 255, green: 0x53 / 255, blue: 0x68 / 255)
private let slate = Color(red: 0x6F / 255, green: 0x80 / 255, blue: 0x9D / 255)

/// The add sheet, on the screen rather than on the future edge, so an add-gig link
/// finds it wherever the list is scrolled and the empty timeline has it too.
struct AddGigSheets: ViewModifier {
    @EnvironmentObject var model: AppModel
    @Binding var adding: Bool
    @Binding var prefill: AddGigLink?

    func body(content: Content) -> some View {
        content
            .sheet(isPresented: $adding) {
                AddGigSheet(initial: prefill) { artist, venue, date in
                    model.planning.addGig(artist: artist, venue: venue, date: date)
                    adding = false
                    prefill = nil
                } onAddByLink: { link in
                    model.planning.addPlannedGig(link)
                    adding = false
                    prefill = nil
                } onCancel: { adding = false; prefill = nil }
            }
    }
}

/// Add a **Gig**: who played or is playing, where, and when. One form for both, because
/// the input is the same and both put a **Gig** on my **Line**; the date decides the rule
/// underneath (see `nightKind`). Typing is the default and the link is the alternative:
/// the paste is the faster door only for a show already catalogued, and a future show
/// usually is not (#349). Twin of Android's `AddGigDialog`.
///
/// **The artist completes; the venue does not.** MusicBrainz has a `place` entity and
/// its coverage of small rooms is thin, so a completion box that fails most of the
/// time would teach people to ignore the one above it. A plain field that never
/// guesses is the honest version of a venue.
struct AddGigSheet: View {
    let initial: AddGigLink?
    let onAdd: (String, String, String) -> Void
    let onAddByLink: (String) -> Void
    let onCancel: () -> Void

    @EnvironmentObject var model: AppModel
    @State private var artist: String
    @State private var venue: String
    @State private var date: String
    @State private var link = ""
    @State private var pasting = false
    /// The last spelling picked from the list, so writing it into the field is not
    /// mistaken for typing it.
    @State private var picked = ""

    init(initial: AddGigLink? = nil, onAdd: @escaping (String, String, String) -> Void,
         onAddByLink: @escaping (String) -> Void, onCancel: @escaping () -> Void) {
        self.initial = initial
        self.onAdd = onAdd
        self.onAddByLink = onAddByLink
        self.onCancel = onCancel
        _artist = State(initialValue: initial?.artist ?? "")
        _venue = State(initialValue: initial?.venue ?? "")
        _date = State(initialValue: initial?.date ?? "")
    }

    private var kind: NightKind {
        nightKind(date: isoDate(fromFm: date.trimmingCharacters(in: .whitespaces)),
                  today: isoToday())
    }

    private var tourPlanning: Bool { !model.state.tourFinished && (model.state.tourStep == .s3 || model.state.tourStep == .s4) }

    private var ready: Bool {
        if tourPlanning {
            return !artist.trimmingCharacters(in: .whitespaces).isEmpty && picked == artist &&
                !venue.trimmingCharacters(in: .whitespaces).isEmpty &&
                (isoDate(fromFm: date.trimmingCharacters(in: .whitespaces)).map { $0 > isoToday() } ?? false)
        }
        return pasting
            ? !link.trimmingCharacters(in: .whitespaces).isEmpty
            : !artist.trimmingCharacters(in: .whitespaces).isEmpty
                && !date.trimmingCharacters(in: .whitespaces).isEmpty
    }

    var body: some View {
        NavigationStack {
            Form {
                if !model.state.tourFinished,
                   model.state.tourStep == .s3 || model.state.tourStep == .s4,
                   let mark = model.state.tourCoachMark {
                    TourCoachMarkView(model: model, mark: mark)
                        .listRowInsets(EdgeInsets()).listRowBackground(Color.clear)
                }
                if pasting {
                    Section {
                        TextField("setlist.fm link", text: $link)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                    } footer: {
                        Text("Paste the setlist.fm page for the show. It brings the real "
                             + "venue and date with it.")
                    }
                } else {
                    Section {
                        TextField("who's playing", text: $artist)
                            // Picking a spelling writes the field, and `onChange`
                            // cannot tell that from typing — without the guard the
                            // list the pick just dismissed comes straight back.
                            .onChange(of: artist) { name in
                                if name != picked { picked = ""; model.planning.suggestArtists(name) }
                            }
                        // Suggestions sit directly under the field they belong to and
                        // nowhere else. Capped at four rows: this is a prompt above a
                        // keyboard, and a list that scrolls is a search result page
                        // pretending to be a hint.
                        ForEach(model.state.artistSuggestions.prefix(4)) { hit in
                            Button {
                                picked = hit.name
                                artist = hit.name
                                model.planning.pickArtist(hit)
                                model.tour.send(.bandPicked)
                            } label: {
                                Text(hit.disambiguation.isEmpty
                                     ? hit.name : "\(hit.name)  · \(hit.disambiguation)")
                                    .font(.footnote).foregroundStyle(slate)
                            }
                        }
                        TextField(tourPlanning ? "venue" : "venue (optional)", text: $venue)
                        TextField("date (dd-MM-yyyy)", text: $date)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                    } footer: {
                        Text(kind == .goingTo
                             ? "A night ahead can't be searched for, so it lives on this phone "
                               + "until setlist.fm catches up with it."
                             : "No account needed. This night lives on this phone, and what "
                               + "was played goes in its log afterwards.")
                    }
                }
                if !tourPlanning { Section {
                    Button(pasting ? "or type it in" : "or paste a setlist.fm link") {
                        pasting.toggle()
                    }
                    .font(.footnote)
                } }
            }
            .navigationTitle("Add a gig")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { model.planning.clearArtistSuggestions(); onCancel() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") {
                        if pasting { onAddByLink(link) } else { onAdd(artist, venue, date) }
                    }
                    .disabled(!ready)
                }
            }
        }
    }
}

/// One night I hold a ticket for, above today (#175). Not woven into `rows` — it isn't
/// an attended show and has no Lane/Crossing geometry of its own to draw — just the
/// fact of the gig and `plannedStatus`'s answer to "how far off is it", the same words
/// `gigStatus` gives an attended row once it has passed.
struct PlannedGigRow: View {
    let setlist: FmSetlist

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(setlist.readableDate() ?? "Unknown date")
                .font(.system(size: 11, weight: .semibold)).kerning(1).foregroundStyle(faint)
            Text(setlist.artist?.name ?? "Unknown artist")
                .font(.system(size: 15, design: .serif)).foregroundStyle(ink)
            Text(setlist.venueLine()).font(.system(size: 13)).foregroundStyle(muted)
            Text(plannedStatus(gigDate: setlist.eventDate, now: Date(), songCount: setlist.performed().count))
                .font(.system(size: 12)).foregroundStyle(slate).padding(.top, 2)
        }
        .padding(.vertical, 8)
        // Every place this row is drawn, a tap opens the Gig (#164).
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
    }
}
