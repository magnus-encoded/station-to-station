import Vision
import XCTest
@testable import StationToStation

/// An **Admission** redrawn in its own symbology and read back (#441, stories 2, 3, 9,
/// 29): CoreImage draws exactly what the Room will show, Vision decodes it, and the
/// payload must come back byte for byte. Android's `AdmissionRedrawTest` asserts the
/// same payloads through zxing.
///
/// What it cannot say: that a venue's scanner accepts the redraw. Every payload here is
/// synthetic.
final class AdmissionRedrawTests: XCTestCase {

    /// A 24-digit number in the shape of an Eventim ticket's Code 128, made up.
    private let eventimShaped = "123456789012345678901234"

    private func roundTrips(_ symbology: String, _ text: String,
                            file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertNotNil(admissionDrawing(symbology: symbology, payload: Data(text.utf8)),
                        "\(symbology) draws", file: file, line: line)
        XCTAssertTrue(redrawsExactly(symbology: symbology, payload: Data(text.utf8)),
                      "\(symbology) reads back as \(text)", file: file, line: line)
    }

    // MARK: - The round trip, per symbology this platform draws

    func testAnAsciiQrReadsBack() {
        roundTrips("qr", "SYNTHETIC-QR-TESTQRAA1")
    }

    /// Non-ASCII text in a QR: the case #534 found zxing mangling without an ECI.
    func testANonAsciiQrReadsBack() {
        roundTrips("qr", "Dumdumboys – ÆØÅ")
    }

    func testAnEventimShapedCode128ReadsBack() {
        roundTrips("code128", eventimShaped)
    }

    func testAnAlphanumericCode128ReadsBack() {
        roundTrips("code128", "SYNTH-128-ABC")
    }

    func testAnAztecReadsBack() {
        roundTrips("aztec", "SYNTHETIC-AZTEC-0042")
    }

    func testAPdf417ReadsBack() {
        roundTrips("pdf417", "SYNTHETIC-PDF417-0042")
    }

    // MARK: - What cannot be drawn says so

    /// CoreImage has no Data Matrix generator. Android draws one; here it is "can't be
    /// shown", never a QR standing in for it. The parity gap, pinned.
    func testDataMatrixCannotBeDrawnHere() {
        XCTAssertNil(admissionDrawing(symbology: "datamatrix", payload: Data("SYNTH-DM".utf8)))
        XCTAssertFalse(redrawsExactly(symbology: "datamatrix", payload: Data("SYNTH-DM".utf8)))
    }

    func testRetailFormatsCannotBeDrawnHere() {
        for symbology in ["ean13", "ean8", "upce", "upca"] {
            XCTAssertFalse(redrawsExactly(symbology: symbology, payload: Data("5901234123457".utf8)),
                           symbology)
        }
    }

    func testAnEmptyPayloadDrawsNothing() {
        XCTAssertNil(admissionDrawing(symbology: "qr", payload: Data()))
        XCTAssertFalse(redrawsExactly(symbology: "qr", payload: Data()))
    }

    /// Code 128 carries ASCII; text it cannot carry is a failure at import, not a
    /// wrong barcode.
    func testACode128OfTextItCannotCarryIsNotRedrawable() {
        XCTAssertFalse(redrawsExactly(symbology: "code128", payload: Data("Æ–Ø".utf8)))
    }

    /// A binary payload (not UTF-8) has no text to compare, and byte segments are not
    /// carried yet (#441): reported, not guessed.
    func testABinaryPayloadIsNotRedrawable() {
        XCTAssertFalse(redrawsExactly(symbology: "qr", payload: Data([0x00, 0xFF, 0xFE])))
    }

    // MARK: - Shape (story 9)

    func testAMatrixCodeIsSquareWithItsQuietZone() throws {
        let qr = try XCTUnwrap(admissionDrawing(symbology: "qr", payload: Data("SYNTH".utf8)))
        XCTAssertEqual(.matrix, qr.shape)
        XCTAssertEqual(qr.width, qr.height)
        // The four outermost rings are quiet.
        for i in 0..<qr.width {
            for ring in 0..<4 {
                XCTAssertFalse(qr.isDark(i, ring))
                XCTAssertFalse(qr.isDark(ring, i))
                XCTAssertFalse(qr.isDark(i, qr.height - 1 - ring))
                XCTAssertFalse(qr.isDark(qr.width - 1 - ring, i))
            }
        }
    }

    func testALinearCodeIsAStripWithItsQuietZone() throws {
        let strip = try XCTUnwrap(admissionDrawing(symbology: "code128", payload: Data(eventimShaped.utf8)))
        XCTAssertEqual(.linear, strip.shape)
        XCTAssertEqual(1, strip.height)
        XCTAssertTrue((0..<10).allSatisfy { !strip.isDark($0, 0) && !strip.isDark(strip.width - 1 - $0, 0) })
        XCTAssertTrue(strip.isDark(11, 0), "the start pattern begins at the quiet zone's edge")
        // Story 9's module width is set by how many modules the strip needs. Printed so
        // CI shows it beside Android's figure for the same payload (AdmissionRedrawTest).
        print("AdmissionRedraw: code128 \(eventimShaped.count)-digit strip is \(strip.width) modules")
    }

    /// Every module the same whole number of pixels: no uneven bars from scaling.
    func testTheImageIsAWholeNumberOfPixelsPerModule() throws {
        let strip = try XCTUnwrap(admissionDrawing(symbology: "code128", payload: Data(eventimShaped.utf8)))
        let image = try XCTUnwrap(strip.image(modulePixels: 3, linearHeight: 90))
        XCTAssertEqual(strip.width * 3, image.width)
        XCTAssertEqual(90, image.height)
        let qr = try XCTUnwrap(admissionDrawing(symbology: "qr", payload: Data("SYNTH".utf8)))
        let square = try XCTUnwrap(qr.image(modulePixels: 5))
        XCTAssertEqual(qr.width * 5, square.width)
        XCTAssertEqual(qr.height * 5, square.height)
    }

    // MARK: - Checking a Ticket

    func testCheckingATicketAnswersEveryAdmission() {
        let ticket = Ticket(admissions: [
            Admission(payload: Data("SYNTHETIC-QR-1".utf8), symbology: "qr"),
            Admission(payload: Data(eventimShaped.utf8), symbology: "code128"),
            Admission(payload: Data("SYNTH-DM".utf8), symbology: "datamatrix"),
        ])
        let checked = ticket.checkedForRedraw()
        XCTAssertEqual([true, true, false], checked.admissions.map(\.redrawable))
        XCTAssertFalse(checked.redrawsEveryAdmission)
    }

    // MARK: - Vision's names (story 21)

    func testVisionNamesMapToTheFixturesNames() {
        XCTAssertEqual("qr", ticketSymbology(.qr))
        XCTAssertEqual("code128", ticketSymbology(.code128))
        XCTAssertEqual("aztec", ticketSymbology(.aztec))
        XCTAssertEqual("pdf417", ticketSymbology(.pdf417))
        XCTAssertEqual("datamatrix", ticketSymbology(.dataMatrix))
        XCTAssertEqual("ean13", ticketSymbology(.ean13))
        XCTAssertEqual("ean8", ticketSymbology(.ean8))
        XCTAssertEqual("upce", ticketSymbology(.upce))
        XCTAssertNil(ticketSymbology(.code39))
        for symbology in ticketVisionSymbologies {
            XCTAssertEqual(symbology, ticketSymbology(symbology).flatMap(visionSymbology))
        }
    }
}
