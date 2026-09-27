import PDFKit
import SwiftUI
import UIKit

// The kept original at the door (#568): the ticket file itself, shown in place of a
// redraw the app could not make. The venue issued that page, so it scans as issued.
// The Kotlin twin is `TicketOriginal.kt`, over PdfRenderer.

/// `page` of the file at `url`, drawn white-backed `width` points across at the
/// screen's scale, or nil where it cannot be (a file gone, or not a PDF or image). A
/// page past the end is the last page.
func renderOriginal(_ url: URL, page: Int, width: CGFloat = 800) -> UIImage? {
    guard url.pathExtension.lowercased() == "pdf" else { return UIImage(contentsOfFile: url.path) }
    guard let document = PDFDocument(url: url), document.pageCount > 0,
          let pdfPage = document.page(at: min(max(page, 0), document.pageCount - 1))
    else { return nil }
    let bounds = pdfPage.bounds(for: .mediaBox)
    guard bounds.width > 0 else { return nil }
    let size = CGSize(width: width, height: width * bounds.height / bounds.width)
    return pdfPage.thumbnail(of: size, for: .mediaBox)
}

/// The **Room**'s card for an **Admission** shown from its original: the page, small,
/// and a tap away from full screen at full brightness. `label` is the "2 of 3" the
/// Room already says, or nil for one.
struct OriginalAtTheDoor: View {
    let url: URL
    let page: Int
    let label: String?
    /// What is said where the page cannot be drawn: a damaged, cut-off or locked PDF.
    let unrendered: String

    @State private var image: UIImage?
    @State private var failed = false
    @State private var full = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let image {
                Button { full = true } label: {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                        .frame(maxHeight: 260)
                        .padding(6)
                        .background(Color.white)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Your original ticket" + (label.map { ", \($0)" } ?? "")
                                    + ". Tap to show it full screen.")
                Text("Your original ticket. Tap to show it full screen.")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
            } else if failed {
                Text(unrendered)
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .task(id: url) {
            let url = url, page = page
            image = await Task.detached(priority: .userInitiated) { renderOriginal(url, page: page, width: 1200) }.value
            failed = image == nil
        }
        .fullScreenCover(isPresented: $full) {
            if let image { OriginalFullScreen(image: image, label: label) }
        }
    }
}

/// The page on white, filling the screen, at full brightness until it is closed.
private struct OriginalFullScreen: View {
    let image: UIImage
    let label: String?

    @Environment(\.dismiss) private var dismiss
    @State private var brightness: CGFloat?

    var body: some View {
        ZStack {
            Color.white.ignoresSafeArea()
            Image(uiImage: image)
                .resizable()
                .scaledToFit()
                .padding(8)
                .accessibilityLabel("Your original ticket" + (label.map { ", \($0)" } ?? "")
                                    + ". Hold it up to be scanned; tap to close.")
        }
        .contentShape(Rectangle())
        .onTapGesture { dismiss() }
        .onAppear {
            brightness = UIScreen.main.brightness
            UIScreen.main.brightness = 1
        }
        .onDisappear {
            if let brightness { UIScreen.main.brightness = brightness }
        }
    }
}
