import SwiftUI

private let raised = Color(red: 0x17 / 255, green: 0x12 / 255, blue: 0x1F / 255)
private let faint = Color(red: 0x5A / 255, green: 0x53 / 255, blue: 0x68 / 255)
private let amber = Color(red: 0xE7 / 255, green: 0xB2 / 255, blue: 0x4C / 255)

/// The Gig screen's "Possible match on setlist.fm" chip, opened (#531): each hit a lookup
/// was not sure of, as a single choice, with `setlistFmQuestion` under a hit where the
/// room is in doubt. Confirm on a hit is "yes, this one"; on "None of these" it rejects
/// them all; "Not now" leaves the question waiting. The hits are the stored snapshot,
/// fetched afresh where that was lost.
struct PossibleMatchSheet: View {
    let gigId: String
    let yourVenue: String?
    let fromTicket: Bool
    let onPick: (String) -> Void
    let onNone: () -> Void
    let onDismiss: () -> Void

    @EnvironmentObject var model: AppModel
    @State private var hits: [StoredSetlistFmHit]?
    /// Nothing ticked until the person picks: Confirm stays off, so no tap adopts by accident.
    @State private var chosen: String?
    @State private var picked = false

    var body: some View {
        NavigationStack {
            List {
                if let hits {
                    ForEach(hits, id: \.id) { hit in
                        SetlistFmChoiceRow(
                            line: hit.line(),
                            question: setlistFmQuestion(yourVenue: yourVenue, fromTicket: fromTicket, hit: hit),
                            selected: picked && chosen == hit.id
                        ) { chosen = hit.id; picked = true }
                    }
                    SetlistFmChoiceRow(line: "None of these", question: nil,
                                       selected: picked && chosen == nil) { chosen = nil; picked = true }
                } else {
                    HStack {
                        Spacer()
                        ProgressView().tint(amber)
                            .accessibilityLabel("Looking on setlist.fm")
                        Spacer()
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(raised)
            .navigationTitle(possibleMatchTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Not now") { onDismiss() }.tint(faint)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Confirm") {
                        if let chosen { onPick(chosen) } else { onNone() }
                    }
                    .disabled(!picked)
                }
            }
        }
        .presentationDetents([.medium, .large])
        .preferredColorScheme(.dark)
        .task(id: gigId) { hits = await model.setlistFmChipHits(gigId: gigId) }
    }
}
