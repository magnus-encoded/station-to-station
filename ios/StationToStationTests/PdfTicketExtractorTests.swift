import CoreGraphics
import Foundation
import XCTest
@testable import StationToStation

/// `PdfTicketExtractor`'s shape (#526), with fake pages and fake readers. The real
/// PDFKit and Vision readers stay untested, as they always have: they need a device.
final class PdfTicketExtractorTests: XCTestCase {

    /// A page with a text layer and the barcodes a locator would see on it.
    private final class FakePage: PdfPage {
        let textLayer: String?
        let codes: [TicketBarcode]
        init(_ text: String?, codes: [TicketBarcode] = []) {
            textLayer = text
            self.codes = codes
        }
        func rendered() -> CGImage? { nil }
    }

    /// Counts how often each page was asked for, so a test can tell a page that was
    /// opened once and shared from one opened per reader.
    private final class FakePages: PdfPages {
        let pages: [FakePage]
        private(set) var opened: [Int] = []
        init(pages: [FakePage]) { self.pages = pages }
        var count: Int { pages.count }
        func page(at index: Int) -> (any PdfPage)? {
            opened.append(index)
            return pages[index]
        }
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

    /// Hands back the page's own codes, and remembers every page it was handed.
    private final class RecordingLocator: BarcodeLocator {
        private(set) var seen: [ObjectIdentifier] = []

        func locate(on page: any PdfPage) async -> [TicketBarcode] {
            seen.append(ObjectIdentifier(page))
            return (page as! FakePage).codes
        }
    }

    private func code(_ payload: String?, _ symbology: String = "qr") -> TicketBarcode {
        TicketBarcode(image: Data([0x89]), payload: payload.map { Data($0.utf8) },
                      symbology: symbology)
    }

    private func extractor(_ pages: [FakePage], readers: [any PdfTextReader],
                           locator: any BarcodeLocator = RecordingLocator())
    -> PdfTicketExtractor {
        extractor(FakePages(pages: pages), readers: readers, locator: locator)
    }

    private func extractor(_ pages: FakePages, readers: [any PdfTextReader],
                           locator: any BarcodeLocator = RecordingLocator())
    -> PdfTicketExtractor {
        PdfTicketExtractor(open: { _ in pages }, readers: readers, barcode: locator)
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

    /// Each page is opened once, and that one page is what every reader and the locator
    /// are handed — which is what lets OCR and the barcode share one render.
    func testEachPageIsOpenedOnceAndShared() async {
        let pages = FakePages(pages: [FakePage("a"), FakePage("b")])
        let text = RecordingReader(.textLayer) { [$0.textLayer ?? ""] }
        let ocr = RecordingReader(.ocr) { [$0.textLayer ?? ""] }
        let locator = RecordingLocator()

        _ = await extractor(pages, readers: [text, ocr], locator: locator).extract(Data())

        XCTAssertEqual([0, 1], pages.opened)
        let each = pages.pages.map { ObjectIdentifier($0) }
        XCTAssertEqual(each, text.seen)
        XCTAssertEqual(each, ocr.seen)
        XCTAssertEqual(each, locator.seen)
    }

    /// The render a page hands out is drawn on the first ask and never again, even when
    /// drawing failed.
    func testAPageIsRenderedOnce() {
        var draws = 0
        let render = RenderOnce {
            draws += 1
            return nil
        }

        _ = render()
        _ = render()
        _ = render()

        XCTAssertEqual(1, draws)
    }

    // MARK: - Barcodes

    /// A barcode on page one does not stop the text readers or the locator from reading
    /// page two — comparing is the parser's job, and it needs everything.
    func testAFoundBarcodeDoesNotStopTheReading() async {
        let pages = [FakePage("a", codes: [code("one")]), FakePage("b")]
        let ocr = RecordingReader(.ocr) { [$0.textLayer ?? ""] }
        let locator = RecordingLocator()

        let evidence = await extractor(pages, readers: [ocr], locator: locator).extract(Data())

        XCTAssertEqual(2, ocr.seen.count)
        XCTAssertEqual(2, locator.seen.count)
        XCTAssertEqual([Data("one".utf8)], evidence.barcodes.map(\.payload))
    }

    /// Every barcode on every page, in the order found, each stamped with its page.
    func testEveryBarcodeOnEveryPageIsKept() async {
        let pages = [
            FakePage("a", codes: [code("A1"), code("0001", "code128")]),
            FakePage("b"),
            FakePage("c", codes: [code("A2")]),
        ]

        let evidence = await extractor(pages, readers: []).extract(Data())

        XCTAssertEqual(["A1", "0001", "A2"],
                       evidence.barcodes.map { String(decoding: $0.payload!, as: UTF8.self) })
        XCTAssertEqual(["qr", "code128", "qr"], evidence.barcodes.map(\.symbology))
        XCTAssertEqual([0, 0, 2], evidence.barcodes.map(\.page))
        XCTAssertTrue(evidence.barcodes.allSatisfy { $0.image == Data([0x89]) }, "the crop is carried")
    }

    /// A code repeated on every page, or seen twice on one, is one code: kept at its
    /// first sighting.
    func testARepeatedBarcodeIsKeptOnce() async {
        let pages = [
            FakePage("a", codes: [code("same"), code("same")]),
            FakePage("b", codes: [code("same"), code("other")]),
        ]

        let evidence = await extractor(pages, readers: []).extract(Data())

        XCTAssertEqual([Data("same".utf8), Data("other".utf8)], evidence.barcodes.map(\.payload))
        XCTAssertEqual([0, 1], evidence.barcodes.map(\.page))
    }

    /// The same payload under two symbologies is two codes: the door scans a symbol,
    /// not a string.
    func testTheSamePayloadInTwoSymbologiesIsTwoBarcodes() async {
        let pages = [FakePage("a", codes: [code("1234"), code("1234", "code128")])]

        let evidence = await extractor(pages, readers: []).extract(Data())

        XCTAssertEqual(["qr", "code128"], evidence.barcodes.map(\.symbology))
    }

    /// A code with no payload cannot be told to be the same as another, so none is
    /// dropped as a repeat.
    func testBarcodesWithNoPayloadAreAllKept() async {
        let pages = [FakePage("a", codes: [code(nil)]), FakePage("b", codes: [code(nil)])]

        let evidence = await extractor(pages, readers: []).extract(Data())

        XCTAssertEqual(2, evidence.barcodes.count)
        XCTAssertEqual([0, 1], evidence.barcodes.map(\.page))
    }

    func testPagesPastTheLimitAreNotRead() async {
        let pages = (0..<5).map { FakePage("page \($0)", codes: [code("code \($0)")]) }
        let ocr = RecordingReader(.ocr) { [$0.textLayer ?? ""] }
        let locator = RecordingLocator()

        let evidence = await extractor(pages, readers: [ocr], locator: locator).extract(Data())

        XCTAssertEqual(3, ocr.seen.count)
        XCTAssertEqual(3, locator.seen.count)
        XCTAssertEqual([0, 1, 2], evidence.barcodes.map(\.page))
    }

    func testDataThatIsNotAPdfIsNoEvidence() async {
        let ocr = RecordingReader(.ocr) { _ in ["x"] }
        let extractor = PdfTicketExtractor(open: { _ in nil }, readers: [ocr],
                                           barcode: RecordingLocator())

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
