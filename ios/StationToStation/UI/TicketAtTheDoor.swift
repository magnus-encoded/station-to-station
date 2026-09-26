import SwiftUI

// The ticket, held up to be scanned (#414), in each **Admission**'s own symbology (#441).
// CoreImage draws it (`AdmissionRedraw.swift`) as zxing does on Android, so neither side
// takes a dependency for a barcode.
//
// Whether this is drawn at all is `Room.showTicket`, from the fold both platforms read.
// Nothing here asks about check-ins or windows. The card's look is the QR's it replaces;
// its redesign, brightness and a full-screen view are #525's.

/// One Admission drawn for a scanner (#441, story 9): at a whole number of pixels per
/// module, as many as fit — `maxWidth` across, and for a matrix code `matrixMax` down —
/// so every bar and cell is the same width on screen. A linear code fills the width it
/// is given, `linearHeight` tall. Shown at its own pixel size, never resampled.
struct AdmissionBarcode: View {
    let drawing: AdmissionDrawing
    let maxWidth: CGFloat
    let matrixMax: CGFloat
    let linearHeight: CGFloat
    let label: String

    @Environment(\.displayScale) private var displayScale

    var body: some View {
        if let image {
            Image(decorative: image, scale: displayScale)
                .interpolation(.none)
                .accessibilityElement()
                .accessibilityLabel(label)
        }
    }

    private var image: CGImage? {
        let across = Int(maxWidth * displayScale) / drawing.width
        let modulePixels: Int
        switch drawing.shape {
        case .linear: modulePixels = across
        case .matrix: modulePixels = min(across, Int(matrixMax * displayScale) / drawing.height)
        }
        return drawing.image(modulePixels: max(modulePixels, 1),
                             linearHeight: Int(linearHeight * displayScale))
    }
}

/// The line said wherever an Admission cannot be redrawn: which one, and what to do
/// instead. `symbology` is the fixtures' name for it.
func cannotShowLine(symbology: String, page: AdmissionPage? = nil) -> String {
    let which = page?.label.map { "Barcode \($0)" } ?? "This ticket's barcode"
    return "\(which) (\(symbology.uppercased())) can't be shown by the app. "
        + "Bring the original PDF to the door."
}

/// The ticket at the door (#441): every **Admission** in its own symbology, one at a
/// time, with "1 of 3" and a way to step between them when there are several (story 5;
/// `AdmissionPage` holds the rules). White is not a theme choice and does not follow the
/// Room's ground: a scanner reads contrast, and that ground is nearly black.
///
/// What is drawn is what `AdmissionVerdicts` says reads back as itself — the same check
/// the import ran, asked again here because nothing stored carries a verdict and an
/// Admission migrated from an old `ticketQr` was never checked at all. One that does not
/// read back keeps its page and says so, in the prompt's words: never a guess, and never
/// its payload drawn as some other symbology.
struct TicketAtTheDoor: View {
    let admissions: [StoredAdmission]

    private enum Shown: Equatable {
        case checking
        case drawn(AdmissionDrawing)
        case cannotShow
    }

    @State private var index = 0
    @State private var shown = Shown.checking
    @State private var width: CGFloat = 0

    private var page: AdmissionPage { AdmissionPage(index: index, count: admissions.count) }

    var body: some View {
        if admissions.isEmpty {
            EmptyView()
        } else {
            let page = page
            let admission = admissions[page.index]
            VStack(alignment: .leading, spacing: 6) {
                Group {
                    switch shown {
                    case .drawn(let drawing):
                        card(drawing, page: page)
                    case .cannotShow:
                        Text(cannotShowLine(symbology: admission.symbology, page: page))
                            .font(.system(size: 12))
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    case .checking:
                        Color.clear.frame(height: 1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(GeometryReader { proxy in
                    Color.clear.preference(key: DoorWidthKey.self, value: proxy.size.width)
                })
                if let label = page.label {
                    HStack(spacing: 18) {
                        Button("‹ previous") { index = page.previous().index }
                            .disabled(!page.hasPrevious)
                        Text(label).foregroundStyle(.primary)
                        Button("next ›") { index = page.next().index }
                            .disabled(!page.hasNext)
                    }
                    .font(.system(size: 13))
                }
            }
            .onPreferenceChange(DoorWidthKey.self) { width = $0 }
            .onChange(of: admissions) { _ in index = 0 }
            .task(id: admission) {
                shown = .checking
                guard let bytes = admission.payloadBytes else {
                    shown = .cannotShow
                    return
                }
                let ok = await AdmissionVerdicts.shared.redraws(symbology: admission.symbology, payload: bytes)
                shown = ok ? admissionDrawing(admission).map(Shown.drawn) ?? .cannotShow : .cannotShow
            }
        }
    }

    @ViewBuilder
    private func card(_ drawing: AdmissionDrawing, page: AdmissionPage) -> some View {
        // The card's padding, inside the width the Room gives it.
        if width > 24 {
            AdmissionBarcode(drawing: drawing, maxWidth: width - 24, matrixMax: 200,
                             linearHeight: 110,
                             label: "Your ticket's barcode"
                                 + (page.label.map { ", \($0)" } ?? "")
                                 + ". Hold it up to be scanned.")
                .padding(12)
                .background(Color.white)
                .clipShape(RoundedRectangle(cornerRadius: 8))
        }
    }
}

private struct DoorWidthKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

/// The confirm prompt's Admissions, as they will be presented at the door (#441, story
/// 7): each one the import's check read back, drawn small by the same `admissionDrawing`
/// the Room uses, and a plain line for each that did not (story 29).
struct ConfirmAdmissions: View {
    let admissions: [Admission]

    private struct Drawn: Identifiable {
        let id: Int
        let drawing: AdmissionDrawing
    }

    var body: some View {
        let drawn = admissions.indices.compactMap { i -> Drawn? in
            guard admissions[i].redrawable == true, let drawing = admissionDrawing(admissions[i]) else { return nil }
            return Drawn(id: i, drawing: drawing)
        }
        VStack(alignment: .leading, spacing: 8) {
            if !drawn.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(drawn) { item in
                            AdmissionBarcode(drawing: item.drawing, maxWidth: 220, matrixMax: 96,
                                             linearHeight: 48,
                                             label: "Barcode \(item.id + 1) of \(admissions.count), "
                                                 + "as it will be shown at the door")
                                .padding(6)
                                .background(Color.white)
                                .clipShape(RoundedRectangle(cornerRadius: 6))
                        }
                    }
                }
            }
            ForEach(admissions.indices.filter { i in !drawn.contains { $0.id == i } }, id: \.self) { i in
                Text(cannotShowLine(symbology: admissions[i].symbology,
                                    page: AdmissionPage(index: i, count: admissions.count)))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}
