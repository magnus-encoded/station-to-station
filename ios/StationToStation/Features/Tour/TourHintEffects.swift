import SwiftUI

/// Shows a due **Context hint** and remembers it as seen, so it shows once.
@MainActor
final class TourHintEffects {
    private let host: StateHost
    private let tour: TourController

    init(host: StateHost, tour: TourController) {
        self.host = host
        self.tour = tour
    }

    func offer(_ hint: ContextHint) {
        guard !host.state.tourUpgradePrompt, host.state.contextHint == nil,
              contextHintDue(hint, seen: tour.state.seenHints, tourRunning: tour.running) else { return }
        tour.markHintSeen(hint.key)
        host.state.contextHint = hint
    }

    /// The open **Gig**'s hints, one at a time.
    func offerInRoom(now: Date = Date()) {
        let state = host.state
        let media = state.gigMedia
        offer(.pullDown(.room))
        offer(.longPress(editableRows: state.selectedIsMine && !state.contactLight ? media.count : 0))
        // iOS has no Flyover yet, so the rotate hint would send no one anywhere.
    }

    func offerProgramme() {
        let state = host.state
        let festivals = Set(state.timelineShows.compactMap { state.festivals.of($0.id)?.id })
        offer(.programme(festivals: festivals.count))
    }

    func dismiss() { host.state.contextHint = nil }
}

private struct ContextHintModifier: ViewModifier {
    @ObservedObject var model: AppModel

    func body(content: Content) -> some View {
        content.overlay(alignment: .top) {
            if !model.state.tourUpgradePrompt, !model.tour.running, let hint = model.state.contextHint {
                HStack(alignment: .top, spacing: 12) {
                    Text(hint.text)
                    Spacer()
                    Button { model.hints.dismiss() } label: { Image(systemName: "xmark") }
                        .accessibilityLabel("Dismiss hint")
                }
                .padding()
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
                .padding()
            }
        }
    }
}

extension View {
    func contextHints(_ model: AppModel) -> some View {
        modifier(ContextHintModifier(model: model))
    }
}
