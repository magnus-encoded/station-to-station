import SwiftUI

/// One presentation for merge rows, Room tags, and the question before sharing.
/// Keep the existing modifier name so every sharing entry point uses this sheet.
struct MaybeNightAlert: ViewModifier {
    @EnvironmentObject var model: AppModel
    @Binding var asking: MaybeNight?
    var sharing: Bool = false
    var then: () -> Void = {}

    func body(content: Content) -> some View {
        content.sheet(item: $asking, onDismiss: then) { maybe in
            MaybeCompareSheet(maybe: maybe, sharing: sharing) { asking = nil }
                .environmentObject(model)
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
        }
    }
}

struct MaybeCompareSheet: View {
    @EnvironmentObject var model: AppModel
    let maybe: MaybeNight
    var sharing: Bool = false
    let close: () -> Void
    @State private var answering = false
    /// "Same night" on mine typed by hand against theirs from setlist.fm asks one more
    /// thing: take their entry? Asked, because taking it can't be undone.
    @State private var adopting = false

    var body: some View {
        if adopting { adoptStep } else { compareStep }
    }

    private var adoptStep: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text(maybeAdoptQuestion(maybe))
                .font(.title2.bold())
                .accessibilityAddTraits(.isHeader)
            Text(maybeAdoptLine(maybe)).font(.callout)
            Spacer(minLength: 0)
            VStack(spacing: 12) {
                Button("Take it") { answer(same: true, adopt: true) }
                    .buttonStyle(.borderedProminent)
                Button("Keep mine") { answer(same: true) }
                    .buttonStyle(.bordered)
            }
            .frame(maxWidth: .infinity)
            .disabled(answering)
        }
        .padding(24)
        .interactiveDismissDisabled(answering)
    }

    private var compareStep: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text("Were you both at this night?")
                    .font(.title2.bold())
                    .accessibilityAddTraits(.isHeader)
                HStack(alignment: .top) {
                    Text("Yours").frame(maxWidth: .infinity, alignment: .leading)
                    Text(maybeWhose(maybe)).frame(maxWidth: .infinity, alignment: .leading)
                }
                .font(.headline)
                .padding(.horizontal, 10)
                .accessibilityElement(children: .combine)
                ForEach(compareMaybe(maybe), id: \.label) { field in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(field.label + (field.differs ? " (differs)" : ""))
                            .font(.caption.bold())
                        HStack(alignment: .top, spacing: 16) {
                            Text(field.yours).frame(maxWidth: .infinity, alignment: .leading)
                            Text(field.theirs).frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                    .padding(10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(field.differs ? Color.secondary.opacity(0.15) : Color.clear,
                                in: RoundedRectangle(cornerRadius: 8))
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(maybeFieldSpoken(maybe, field))
                }
                Text(sameNightLine(maybe)).font(.callout)
                if sharing {
                    Text("You're sharing from this night, so it's worth knowing.")
                        .font(.callout)
                }
                Text("Only you see the answer.").font(.footnote).foregroundStyle(.secondary)
            }
            .padding(24)
        }
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 12) {
                Button("Same night") {
                    if maybeAdoptable(maybe) { adopting = true } else { answer(same: true) }
                }
                    .buttonStyle(.borderedProminent)
                Button("Not the same") { answer(same: false) }
                    .buttonStyle(.bordered)
                Button("Not now", role: .cancel, action: close)
            }
            .frame(maxWidth: .infinity)
            .padding()
            .background(.regularMaterial)
            .disabled(answering)
        }
        .interactiveDismissDisabled(answering)
    }

    private func answer(same: Bool, adopt: Bool = false) {
        guard !answering else { return }
        answering = true
        Task { @MainActor in
            if adopt { await model.contacts.adoptMaybe(maybe) } else { await model.contacts.answerMaybe(maybe, same: same) }
            close()
        }
    }
}

/// Mounted above the navigation stack's bottom edge, not inside a disappearing tag.
/// Dismissible rather than timed: VoiceOver and a share picker must not consume the
/// opportunity before the person can reach it. Still ephemeral: nothing survives launch.
struct MaybeUndoBanner: View {
    @ObservedObject var model: AppModel

    var body: some View {
        if let answer = model.contacts.maybeUndo {
            VStack(alignment: .leading, spacing: 8) {
                Text(maybeAnswered(answer.maybe, same: answer.same)).font(.callout)
                HStack {
                    Button("Undo") { Task { await model.contacts.undoMaybe(answer) } }
                        .buttonStyle(.borderedProminent)
                    Spacer()
                    Button("Dismiss") {
                        if model.contacts.maybeUndo?.id == answer.id { model.contacts.maybeUndo = nil }
                    }
                }
            }
            .padding()
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
            .padding(.horizontal)
            .padding(.bottom, 8)
            .accessibilityElement(children: .contain)
        }
    }
}
