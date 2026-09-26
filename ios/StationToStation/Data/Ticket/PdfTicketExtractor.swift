import CoreGraphics
import Foundation

// The PDF extractor's shape, with no renderer in it (#526).
//
// This file is in the folder the Share Extension compiles, and it imports neither
// PDFKit nor Vision: the readers that need them live in the extension
// (`TicketShare/PdfReaders.swift`). What is left here is the part worth asserting —
// that every reader runs on every page and each leaves one reading behind — and it is
// asserted with fake pages and fake readers, since the real renderers cannot be run
// without a device.

/// One page of a PDF, as the readers see it.
///
/// **Rendered at most once.** An implementation draws the page on the first call to
/// `rendered()` and hands back the same pixels after that, so the OCR reader and the
/// barcode locator share one rasterization. Two would be twice the memory for the same
/// answer, inside a Share Extension that is killed rather than warned when it overruns.
protocol PdfPage: AnyObject {
    /// The page's own text layer, as the PDF carries it. Nil or blank for a scan.
    var textLayer: String? { get }
    func rendered() -> CGImage?
}

/// An opened PDF. Pages are asked for one at a time, so each page's pixels can be let
/// go before the next is drawn.
protocol PdfPages {
    var count: Int { get }
    func page(at index: Int) -> (any PdfPage)?
}

/// One way of reading a page's words.
protocol PdfTextReader {
    var origin: TicketReading.Origin { get }
    func lines(of page: any PdfPage) async -> [String]
}

/// Finds the barcode on a page. Always visual, whatever the text readers did.
protocol BarcodeLocator {
    func locate(on page: any PdfPage) async -> TicketBarcode?
}

/// A PDF's evidence: every reader over every page, and the barcode off the first page
/// that has one.
///
/// **Both readers always run, on every page.** Comparing what they found is
/// `parseTicketFields`'s job, not this one's, so nothing here stops early or picks a
/// winner. A reader that found nothing — the text layer of a scan — leaves no reading
/// behind, which is how the parser knows a scan from a PDF that agreed with itself.
struct PdfTicketExtractor: TicketExtractor {
    typealias Source = Data

    let open: (Data) -> (any PdfPages)?
    let readers: [any PdfTextReader]
    let barcode: any BarcodeLocator

    /// Pages past the third are not read. A ticket is one page; the rest of a PDF that
    /// has more is terms and conditions, and reading them is memory spent to make the
    /// parse *worse*. Android reads five, which is a plumbing difference and not a rule.
    var pageLimit = 3

    func extract(_ source: Data) async -> TicketEvidence {
        guard let pages = open(source) else { return TicketEvidence(readings: []) }
        var lines = Array(repeating: [String](), count: readers.count)
        var found: TicketBarcode?

        for index in 0..<min(pages.count, pageLimit) {
            // Held for this iteration only: the page's cached pixels go with it.
            guard let page = pages.page(at: index) else { continue }
            for (slot, reader) in readers.enumerated() {
                lines[slot] += await reader.lines(of: page).filter {
                    !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                }
            }
            if found == nil { found = await barcode.locate(on: page) }
        }

        let readings = zip(readers, lines)
            .filter { !$0.1.isEmpty }
            .map { TicketReading(origin: $0.0.origin, lines: $0.1) }
        // One barcode today: the locator stops at the first page with one. The evidence
        // is a list because a ticket can carry several (#441).
        return TicketEvidence(readings: readings, barcodes: found.map { [$0] } ?? [])
    }
}

/// The PDF's own text, which costs nothing to read next to OCR.
///
/// A content stream's order is whatever the generator wrote, not always down the page;
/// that is why the parser compares readings by their lines and never by position.
struct TextLayerReader: PdfTextReader {
    var origin: TicketReading.Origin { .textLayer }

    func lines(of page: any PdfPage) async -> [String] {
        (page.textLayer ?? "").components(separatedBy: .newlines)
    }
}

/// The pixel rectangle to cut a barcode out of its page with: the bounds a locator
/// found, grown on every side by `padding` of the longer edge and clamped to the image.
///
/// A symbol cropped to its own bounds is not readable: a QR needs four modules of blank
/// quiet zone around it, and a scanner at the door is given exactly the crop. A fifth of
/// the longer edge covers four modules of even the smallest QR (21 modules across).
///
/// `bounds` is top-left origin, in pixels — the locator converts its own coordinates.
func barcodeCrop(bounds: CGRect, imageWidth: Int, imageHeight: Int,
                 padding: CGFloat = 0.2) -> CGRect {
    let margin = max(bounds.width, bounds.height) * padding
    let image = CGRect(x: 0, y: 0, width: imageWidth, height: imageHeight)
    return bounds.insetBy(dx: -margin, dy: -margin).intersection(image).integral
}
