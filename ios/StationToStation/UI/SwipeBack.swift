import SwiftUI

extension View {
    /// **Back out** where there is no screen to pop: a rung that was uncollapsed in
    /// place goes one rung **Outer** without touching the navigation stack (#176).
    ///
    /// `NavigationStack`'s own interactive edge-pop is **Back out** wherever there is
    /// a screen to leave (ADR-0017), and it is untouched by this — the Timeline is
    /// the outermost rung and has nothing to pop. `.simultaneousGesture` for the same
    /// reason `swipeLeft` needs it: the Timeline's ScrollView claims an exclusive
    /// gesture first, so a plain `.gesture` would never fire here.
    func swipeRight(threshold: CGFloat = 90, _ act: @escaping () -> Void) -> some View {
        simultaneousGesture(
            DragGesture(minimumDistance: 20)
                .onEnded { v in
                    if v.translation.width >= threshold, abs(v.translation.height) < 60 { act() }
                }
        )
    }

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
