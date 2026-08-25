import SwiftUI

extension View {
    /// Swipe left to act on this level — the other half of the same grammar, and
    /// the gesture the Timeline already carries. `.simultaneousGesture`, because a
    /// screen's ScrollView claims an exclusive gesture first and this would never
    /// fire; the vertical bound is what keeps a diagonal scroll from acting.
    func swipeLeft(threshold: CGFloat = 90, _ act: @escaping () -> Void) -> some View {
        simultaneousGesture(
            DragGesture(minimumDistance: 20)
                .onEnded { v in
                    if v.translation.width <= -threshold, abs(v.translation.height) < 60 { act() }
                }
        )
    }
}
