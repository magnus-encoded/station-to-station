import SwiftUI

/// The delete a long press asked for, held while the alert for lost photographs is up.
struct GigDeleteAlert: ViewModifier {
    @EnvironmentObject var model: AppModel
    @Binding var asked: FmSetlist?

    func body(content: Content) -> some View {
        content.alert("Delete this night?", isPresented: Binding(
            get: { asked != nil }, set: { if !$0 { asked = nil } })) {
            Button("Delete", role: .destructive) {
                if let gig = asked { model.gig.deleteGig(gig.id) }
                asked = nil
            }
            Button("Keep it", role: .cancel) { asked = nil }
        } message: {
            let lost = asked.map { model.gig.photosLostByDeleting($0.id) } ?? 0
            Text("\(lost) of its photographs are only stored here. Deleting the night deletes them. There is no undo.")
        }
    }
}
