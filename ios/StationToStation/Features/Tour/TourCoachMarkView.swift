import SwiftUI

struct TourCoachMarkView: View {
    @ObservedObject var model: AppModel
    let mark: TourCoachMark

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Tour").font(.headline)
                Spacer()
                Button { model.tour.dismissCoachMark() } label: {
                    Image(systemName: "xmark")
                }
                .accessibilityLabel("Dismiss tour card")
            }
            if mark == .spotify {
                Text(model.tour.virtualFriendName).font(.headline)
                Text(model.tour.spotifyCoachLine)
            } else {
                Text(mark == .line
                     ? "Vertical is time: your line runs down the screen"
                     : "Try this step in the app to continue the Tour.")
            }
            HStack {
                Button("Skip") { model.tour.skip() }
                Spacer()
                if mark == .spotify {
                    Button("Not now") { model.tour.declineSpotify() }
                    Button("Spotify") { model.tour.exportSpotify() }
                        .disabled(model.state.creatingPlaylist)
                } else {
                    Button("OK") {
                        model.tour.dismissCoachMark()
                        model.tour.send(.acknowledged)
                    }
                }
            }
        }
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
        .padding()
    }
}

struct TourUpgradePromptView: View {
    @ObservedObject var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("New: a guided tour").font(.headline)
            Text("Walk through the app at a demo gig. It takes a few minutes and leaves nothing behind.")
            HStack {
                Button("No thanks") { model.tour.dismissUpgradePrompt() }
                Spacer()
                Button("Take the tour") { model.tour.acceptUpgradePrompt() }
                    .buttonStyle(.borderedProminent)
            }
        }
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
        .padding()
    }
}

private struct TourModifier: ViewModifier {
    @ObservedObject var model: AppModel

    func body(content: Content) -> some View {
        content
            .overlay(alignment: .bottom) {
                if model.state.tourUpgradePrompt {
                    TourUpgradePromptView(model: model)
                } else if !model.state.tourFinished, model.state.tourStep != nil, model.state.tourStep != .s20 {
                    if let mark = model.state.tourCoachMark {
                        TourCoachMarkView(model: model, mark: mark)
                    } else {
                        Button("Skip tour") { model.tour.skip() }
                            .buttonStyle(.borderedProminent)
                            .padding()
                    }
                }
            }
            .onAppear { model.tour.start() }
    }
}

extension View {
    func tourOverlay(_ model: AppModel) -> some View {
        modifier(TourModifier(model: model))
    }
}
