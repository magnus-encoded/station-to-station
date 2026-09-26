import Foundation
import PDFKit
import UIKit
import Vision

/// The impure half of ticket reading: PDFKit and Vision behind the seams
/// `PdfTicketExtractor` is written against.
///
/// Nothing here is unit-tested and that is the seam working as intended: what can be
/// asserted without a device is the extractor's shape (fake pages, fake readers) and
/// `parseTicketFields`, which is all this ever feeds.
extension PdfTicketExtractor {
    /// The extractor the Share Extension runs: the text layer and OCR on every page,
    /// and Vision for the barcode.
    static var onDevice: PdfTicketExtractor {
        PdfTicketExtractor(open: { PdfKitPages(data: $0) },
                           readers: [TextLayerReader(), OcrReader()],
                           barcode: VisionBarcodeLocator())
    }
}

/// A `PDFDocument`, one page at a time.
struct PdfKitPages: PdfPages {
    private let document: PDFDocument

    init?(data: Data) {
        guard let document = PDFDocument(data: data) else { return nil }
        self.document = document
    }

    var count: Int { document.pageCount }

    func page(at index: Int) -> (any PdfPage)? {
        document.page(at: index).map(PdfKitPage.init)
    }
}

/// One `PDFPage`, drawn on the first ask and kept for the rest of the page's readers.
final class PdfKitPage: PdfPage {
    /// The long edge the page is rasterized to. 72dpi PDF points scaled to roughly
    /// 200dpi is what Vision wants for small print, capped so a poster-sized page
    /// cannot blow the extension's budget on its own.
    static let maxEdge: CGFloat = 2000

    private let page: PDFPage
    private let render: RenderOnce

    init(_ page: PDFPage) {
        self.page = page
        render = RenderOnce { autoreleasepool { PdfKitPage.rasterize(page) } }
    }

    var textLayer: String? { page.string }

    func rendered() -> CGImage? { render() }

    private static func rasterize(_ page: PDFPage) -> CGImage? {
        let bounds = page.bounds(for: .mediaBox).size
        guard bounds.width > 0, bounds.height > 0 else { return nil }
        let longEdge = max(bounds.width, bounds.height)
        let scale = max(1, min(Self.maxEdge / longEdge, 3))
        let size = CGSize(width: bounds.width * scale, height: bounds.height * scale)
        return page.thumbnail(of: size, for: .mediaBox).cgImage
    }
}

/// Vision's text recognition over the page's one rasterization.
struct OcrReader: PdfTextReader {
    var origin: TicketReading.Origin { .ocr }

    func lines(of page: any PdfPage) async -> [String] {
        guard let image = page.rendered() else { return [] }
        return autoreleasepool {
            let request = VNRecognizeTextRequest()
            request.recognitionLevel = .accurate
            // Off deliberately: a ticket is mostly proper nouns and reference codes, and
            // language correction turns an unfamiliar band name into a familiar word.
            request.usesLanguageCorrection = false
            request.recognitionLanguages = ["en-US", "nb-NO"]
            try? VNImageRequestHandler(cgImage: image, options: [:]).perform([request])
            let observations = request.results ?? []
            // Reading order, which is the order the parser resolves ties in: down the
            // page, then across it. Vision's own order is by confidence.
            return observations
                .sorted {
                    $0.boundingBox.maxY != $1.boundingBox.maxY
                        ? $0.boundingBox.maxY > $1.boundingBox.maxY
                        : $0.boundingBox.minX < $1.boundingBox.minX
                }
                .compactMap { $0.topCandidates(1).first?.string }
        }
    }
}

/// Vision's barcode detection over the same pixels, cropped to each code it found.
struct VisionBarcodeLocator: BarcodeLocator {
    func locate(on page: any PdfPage) async -> [TicketBarcode] {
        guard let image = page.rendered() else { return [] }
        return autoreleasepool {
            let request = VNDetectBarcodesRequest()
            // Every format a real ticket has been seen to carry (#441, story 21), named
            // as the fixtures name them (`ticketVisionSymbologies`). No longer `.qr`
            // alone: the record is **Admissions** with their symbology, and an Eventim
            // Code 128 this could not see was a ticket this platform lost whole.
            // Intersected with what this request revision supports, because asking for
            // one it does not makes `perform` throw, and `try?` would turn that into
            // "no barcodes on any page".
            let supported = Set((try? request.supportedSymbologies()) ?? ticketVisionSymbologies)
            request.symbologies = ticketVisionSymbologies.filter(supported.contains)
            try? VNImageRequestHandler(cgImage: image, options: [:]).perform([request])
            // Every code on the page, not only the first: which of them count is the
            // parser's call (#441), and the extractor drops the repeats.
            return (request.results ?? []).map { found in
                TicketBarcode(image: crop(image, to: found.boundingBox) ?? Data(),
                              payload: payload(of: found),
                              symbology: ticketSymbology(found.symbology))
            }
        }
    }

    /// The decoded **text**, as UTF-8: what a scanner at the door reads back out, and
    /// the form Android stores (`toTicketBarcode`) and the fixtures carry. Only when there
    /// is no text (a binary payload), `payloadData` on iOS 17: whether that is the
    /// decoded bytes or the symbol's raw codewords is #441's open iOS question, and the
    /// app's check at import (`redrawsExactly`) reports such a payload as not redrawable
    /// rather than trusting it. Before the redraw (#441) this preferred `payloadData`.
    private func payload(of found: VNBarcodeObservation) -> Data? {
        if let text = found.payloadStringValue, !text.isEmpty {
            return Data(text.utf8)
        }
        if #available(iOS 17.0, *), let bytes = found.payloadData, !bytes.isEmpty {
            return bytes
        }
        return nil
    }

    /// PNG of the symbol and its quiet zone. Vision's box is normalized with its origin
    /// bottom-left; the crop is taken in pixels from the top-left.
    private func crop(_ image: CGImage, to box: CGRect) -> Data? {
        let width = image.width
        let height = image.height
        let pixels = VNImageRectForNormalizedRect(box, width, height)
        let flipped = CGRect(x: pixels.minX, y: CGFloat(height) - pixels.maxY,
                             width: pixels.width, height: pixels.height)
        let rect = barcodeCrop(bounds: flipped, imageWidth: width, imageHeight: height)
        guard !rect.isNull, !rect.isEmpty, let cut = image.cropping(to: rect) else { return nil }
        return UIImage(cgImage: cut).pngData()
    }
}
