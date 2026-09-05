import Foundation
import PDFKit
import UIKit
import Vision

/// The impure half of ticket reading: a PDF in, a QR payload and text blocks out.
///
/// **One rasterization feeds both extractions.** The page is drawn once and both
/// Vision requests run against that same image — two pipelines over one PDF would be
/// twice the memory for the same answer, and this runs inside a Share Extension,
/// which is given a fraction of an app's budget and is killed rather than warned when
/// it overruns.
///
/// Nothing here is unit-tested and that is the seam working as intended: what can be
/// asserted without a device is `parseTicket`, which this only ever feeds.
enum TicketExtractor {

    /// Pages past the third are not read. A ticket is one page; the rest of a PDF that
    /// has more is terms and conditions, and OCR-ing them is memory spent to make the
    /// parse *worse*.
    static let pageLimit = 3

    /// The long edge the page is rasterized to. 72dpi PDF points scaled to roughly
    /// 200dpi is what Vision wants for small print, capped so a poster-sized page
    /// cannot blow the extension's budget on its own.
    static let maxEdge: CGFloat = 2000

    static func extract(pdf data: Data) -> (qr: Data?, blocks: [String]) {
        guard let document = PDFDocument(data: data) else { return (nil, []) }
        var qr: Data?
        var blocks: [String] = []

        for index in 0..<min(document.pageCount, pageLimit) {
            autoreleasepool {
                guard let page = document.page(at: index),
                      let image = rasterize(page)
                else { return }
                if qr == nil { qr = readQr(image) }
                blocks.append(contentsOf: readText(image))
            }
            // A ticket that has already given up a QR and some text has given up
            // everything the parser can use; the remaining pages are cost only.
            if qr != nil, !blocks.isEmpty { break }
        }
        return (qr, blocks)
    }

    private static func rasterize(_ page: PDFPage) -> CGImage? {
        let bounds = page.bounds(for: .mediaBox).size
        guard bounds.width > 0, bounds.height > 0 else { return nil }
        let longEdge = max(bounds.width, bounds.height)
        let scale = max(1, min(maxEdge / longEdge, 3))
        let size = CGSize(width: bounds.width * scale, height: bounds.height * scale)
        return page.thumbnail(of: size, for: .mediaBox).cgImage
    }

    private static func readQr(_ image: CGImage) -> Data? {
        let request = VNDetectBarcodesRequest()
        // QR only. Aztec and PDF417 are common on tickets too, but the field this
        // fills is called a QR everywhere in this app and on the Android twin, and a
        // record whose name is a lie costs more than the extra symbology is worth.
        request.symbologies = [.qr]
        try? VNImageRequestHandler(cgImage: image, options: [:]).perform([request])
        guard let found = (request.results ?? []).first else { return nil }
        // `payloadData` is iOS 17. On 16 a binary payload only comes back as a lossy
        // string, which is stated on `Ticket.qr` rather than silently accepted here.
        if #available(iOS 17.0, *), let bytes = found.payloadData, !bytes.isEmpty {
            return bytes
        }
        return found.payloadStringValue?.data(using: .utf8)
    }

    private static func readText(_ image: CGImage) -> [String] {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        // Off deliberately: a ticket is mostly proper nouns and reference codes, and
        // language correction turns an unfamiliar band name into a familiar word.
        request.usesLanguageCorrection = false
        request.recognitionLanguages = ["en-US", "nb-NO"]
        try? VNImageRequestHandler(cgImage: image, options: [:]).perform([request])
        let observations = request.results ?? []
        // Reading order, which is the order `parseTicket` resolves ties in: down the
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
