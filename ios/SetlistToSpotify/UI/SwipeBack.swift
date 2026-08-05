import SwiftUI

extension View {
    /// Swipe right to go back, the iOS twin of Android's `swipeRightToBack`.
    /// Every pushed screen hides the system back button (custom chevrons), which
    /// also disables the interactive edge-pop, so this restores a back gesture.
    ///
    /// A horizontal-dominant rightward drag past the threshold pops. The dominance
    /// check lets it live alongside a vertical ScrollView: vertical drags go to the
    /// scroll view, sideways drags come here.
    func swipeBack(_ nav: Nav, threshold: CGFloat = 90) -> some View {
        gesture(
            DragGesture(minimumDistance: 20)
                .onEnded { v in
                    if v.translation.width > threshold,
                       abs(v.translation.width) > abs(v.translation.height) {
                        nav.pop()
                    }
                }
        )
    }
}
