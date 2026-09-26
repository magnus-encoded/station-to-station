import CoreGraphics
import Foundation
import XCTest
@testable import StationToStation

/// `PdfTicketExtractor`'s shape (#526), with fake pages and fake readers. The real
/// PDFKit and Vision readers stay untested, as they always have: they need a device.
final class PdfTicketExtractorTests: XCTestCase {

    private final class FakePage: PdfPage {
        let textLayer: String?
        init(_ text: String?) { textLayer = text }
        func rendered() -> CGImage? { nil }
    }

    private struct FakePages: PdfPages {
        let pages: [FakePage]
        var count: Int { pages.count }
        func page(at index: Int) -> (any PdfPage)? { pages[index] }
    }

    /// Reads whatever the page's text layer says, prefixed, and remembers every page it
    /// was handed — so a test can tell which reader ran on which page.
    private final class RecordingReader: PdfTextReader {
        let origin: TicketReading.Origin
        let answer: (FakePage) -> [String]
        private(set) var seen: [ObjectIdentifier] = []

        init(_ origin: TicketReading.Origin, answer: @escaping (FakePage) -> [String]) {
            self.origin = origin
            self.answer = answer
        }

        func lines(of page: any PdfPage) async -> [String] {
            seen.append(ObjectIdentifier(page))
            return answer(page as! FakePage)
        }
    }

    private final class RecordingLocator: BarcodeLocator {
        let onPage: Int?
        private(set) var asked = 0
        init(foundOnPage: Int?) { onPage = foundOnPage }

        func locate(on page: any PdfPage) async -> TicketBarcode? {
            defer { asked += 1 }
            guard asked == onPage else { return nil }
            return TicketBarcode(image: Data([0x89]), payload: Data("code".utf8), symbology: "qr")
        }
    }

    private func extractor(_ pages: [FakePage], readers: [any PdfTextReader],
                           locator: any BarcodeLocator = RecordingLocator(foundOnPage: nil))
    -> PdfTicketExtractor {
        PdfTicketExtractor(open: { _ in FakePages(pages: pages) }, readers: readers, barcode: locator)
    }

    /// Every reader on every page, and one reading per reader, in page order.
    func testBothReadersRunOnEveryPage() async {
        let pages = [FakePage("Static Halo"), FakePage("Rockefeller")]
        let text = RecordingReader(.textLayer) { [$0.textLayer ?? ""] }
        let ocr = RecordingReader(.ocr) { ["ocr " + ($0.textLayer ?? "")] }

        let evidence = await extractor(pages, readers: [text, ocr]).extract(Data())

        XCTAssertEqual(pages.map { ObjectIdentifier($0) }, text.seen)
        XCTAssertEqual(pages.map { ObjectIdentifier($0) }, ocr.seen)
        XCTAssertEqual([
            TicketReading(origin: .textLayer, lines: ["Static Halo", "Rockefeller"]),
            TicketReading(origin: .ocr, lines: ["ocr Static Halo", "ocr Rockefeller"]),
        ], evidence.readings)
    }

    /// A scan's text layer is empty. It leaves no reading behind, rather than an empty
    /// one the parser would have to know to ignore.
    func testAReaderThatFoundNothingLeavesNoReading() async {
        let text = RecordingReader(.textLayer) { _ in ["", "  "] }
        let ocr = RecordingReader(.ocr) { _ in ["Static Halo"] }

        let evidence = await extractor([FakePage(nil)], readers: [text, ocr]).extract(Data())

        XCTAssertEqual([TicketReading(origin: .ocr, lines: ["Static Halo"])], evidence.readings)
    }

    /// Finding the barcode on page one does not stop the text readers from reading page
    /// two — comparing is the parser's job, and it needs everything.
    func testAFoundBarcodeDoesNotStopTheReaders() async {
        let pages = [FakePage("a"), FakePage("b")]
        let ocr = RecordingReader(.ocr) { [$0.textLayer ?? ""] }
        let locator = RecordingLocator(foundOnPage: 0)

        let evidence = await extractor(pages, readers: [ocr], locator: locator).extract(Data())

        XCTAssertEqual(2, ocr.seen.count)
        XCTAssertEqual(1, locator.asked, "the first barcode found is the one kept")
        XCTAssertEqual([Data("code".utf8)], evidence.barcodes.map(\.payload))
    }

    func testPagesPastTheLimitAreNotRead() async {
        let pages = (0..<5).map { FakePage("page \($0)") }
        let ocr = RecordingReader(.ocr) { [$0.textLayer ?? ""] }

        _ = await extractor(pages, readers: [ocr]).extract(Data())

        XCTAssertEqual(3, ocr.seen.count)
    }

    func testDataThatIsNotAPdfIsNoEvidence() async {
        let ocr = RecordingReader(.ocr) { _ in ["x"] }
        let extractor = PdfTicketExtractor(open: { _ in nil }, readers: [ocr],
                                           barcode: RecordingLocator(foundOnPage: nil))

        let evidence = await extractor.extract(Data())

        XCTAssertEqual(TicketEvidence(readings: []), evidence)
        XCTAssertTrue(ocr.seen.isEmpty)
    }

    /// The text layer arrives as one string; the reader splits it into lines.
    func testTheTextLayerReaderSplitsLines() async {
        let lines = await TextLayerReader().lines(of: FakePage("Your ticket\nStatic Halo\n24-09-2026"))
        XCTAssertEqual(["Your ticket", "Static Halo", "24-09-2026"], lines)
    }

    // MARK: - The crop

    /// Grown by a fifth of the longer edge on every side, for the quiet zone.
    func testTheCropCarriesTheQuietZone() {
        let crop = barcodeCrop(bounds: CGRect(x: 100, y: 200, width: 50, height: 40),
                               imageWidth: 1000, imageHeight: 1000)
        XCTAssertEqual(CGRect(x: 90, y: 190, width: 70, height: 60), crop)
    }

    /// A barcode at the page's edge is cropped at the edge, never past it.
    func testTheCropStaysInsideTheImage() {
        let crop = barcodeCrop(bounds: CGRect(x: 0, y: 5, width: 100, height: 100),
                               imageWidth: 110, imageHeight: 200)
        XCTAssertEqual(CGRect(x: 0, y: 0, width: 110, height: 125), crop)
    }
}
