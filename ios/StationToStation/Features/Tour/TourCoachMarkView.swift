import SwiftUI
import Combine

struct TourCoachMarkView: View {
    @ObservedObject var model: AppModel
    let mark: TourCoachMark

    var body: some View {
        let character = model.tour.character
        let line = character.line(mark, opener: model.tour.opener)
        card(character, line: line)
    }

    private func card(_ character: TourCharacter, line: TourCharacter.Line) -> some View {
        // The flexible box leaves 94% of the portrait width visible.
        ZStack(alignment: .bottomTrailing) {
            Image(character.cutout).resizable().scaledToFit()
                .frame(width: 124, height: 148.8)
                .shadow(color: .white.opacity(0.45), radius: 1)
                .shadow(color: .black.opacity(0.5), radius: 6, y: 2)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 5) {
                Text(character.name).font(.system(size: 11, weight: .bold)).foregroundStyle(.white)
                    .padding(.init(top: 2, leading: 9, bottom: 2, trailing: 16))
                    .background(model.tourAccent, in: TourNameTab())
                Text(line.instruction).font(.system(size: 14, weight: .semibold, design: .serif))
                if let why = line.why {
                    Text(why).font(.system(size: 12, design: .serif))
                        .foregroundStyle(Color(red: 207/255, green: 199/255, blue: 222/255))
                }
                HStack {
                    Button("Skip") { model.tour.skip() }.foregroundStyle(.secondary)
                    Spacer(minLength: 8)
                    Button("OK") { model.tour.acknowledgeCard() }
                }.font(.system(size: 12)).padding(.top, 3)
            }
            .foregroundStyle(Color(red: 241/255, green: 236/255, blue: 248/255))
            .padding(.init(top: 12, leading: 14, bottom: 7, trailing: 11))
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14))
            .background(Color(red: 58/255, green: 50/255, blue: 74/255).opacity(0.94), in: RoundedRectangle(cornerRadius: 14))
            .overlay(alignment: .leading) { Rectangle().fill(model.tourAccent).frame(width: 3) }
            .padding(.leading, 6).padding(.trailing, 124 * 0.94)
            .padding(.top, 10).padding(.bottom, 6)
        }
        .frame(minHeight: 155, alignment: .bottom)
        .fixedSize(horizontal: false, vertical: true)
    }
}

private struct TourNameTab: Shape {
    func path(in rect: CGRect) -> Path {
        Path { p in
            p.move(to: .zero); p.addLine(to: CGPoint(x: rect.width, y: 0))
            p.addLine(to: CGPoint(x: rect.width - 8, y: rect.height))
            p.addLine(to: CGPoint(x: 0, y: rect.height)); p.closeSubpath()
        }
    }
}

extension AppModel {
    var tourAccent: Color {
        let index = tour.virtualFriendKey.flatMap { key in Array(state.friends.reversed()).firstIndex { $0.publicKey == key } }
        // Before the Exchange there is no Contact; use the first Contact's palette index.
        let colourIndex = index ?? 0
        return laneColor(colourIndex)
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
    let dockTop: Bool

    func body(content: Content) -> some View {
        content
            .safeAreaInset(edge: .top, spacing: 0) {
                if dockTop, let mark = model.state.tourCoachMark {
                    TourCoachMarkView(model: model, mark: mark)
                }
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if model.state.tourUpgradePrompt {
                    TourUpgradePromptView(model: model)
                } else if !model.state.tourFinished, let step = model.state.tourStep,
                          step != .s20, step != .s3, step != .s4 {
                    if let mark = model.state.tourCoachMark, !dockTop {
                        TourCoachMarkView(model: model, mark: mark)
                    } else if !dockTop {
                        Button("Skip tour") { model.tour.skip() }.buttonStyle(.borderedProminent).padding()
                    }
                }
            }
            .onAppear { model.tour.start() }
    }
}

extension View {
    func tourOverlay(_ model: AppModel, inRoom: Bool) -> some View {
        modifier(TourModifier(model: model, dockTop: inRoom))
    }
}
