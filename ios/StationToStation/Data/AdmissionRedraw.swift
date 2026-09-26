import CoreGraphics
import CoreImage
import CoreImage.CIFilterBuiltins
import Foundation
import Vision

// An **Admission** redrawn in its own symbology (#441, stories 2, 3, 9, 29), and checked.
//
// One drawing serves three readers: the check at import, the confirm prompt and the
// Room. The check decodes exactly the pixels the Room shows (quiet zone, correction
// level and all), so "it read back" is a statement about the code at the door and not
// about a cousin of it. Android's twin is `AdmissionRedraw.kt`, over zxing; the two
// agree on shapes, quiet zones and the QR's correction level, and differ in which
// symbologies they can draw at all (see `admissionDrawing`).
//
// App-only, not in `Data/Ticket/`: the Share Extension deposits what it read and never
// draws or checks anything (ADR-0020). The app checks once, when it routes the deposit.

/// How a symbology is laid out. A matrix code keeps its own aspect (square for QR, Aztec
/// and Data Matrix; wide for PDF417); a linear one is a strip, as wide as the screen
/// allows, whose height carries no information.
enum AdmissionShape: Equatable, Sendable {
    case matrix, linear
}

/// An Admission as it will be drawn: one cell per module (the narrowest bar, for a linear
/// code), black on white, with the quiet zone already in it — a code bled to its own
/// edge is one many scanners will not see at all, so the margin is part of the symbol
/// rather than of whatever view happens to hold it.
struct AdmissionDrawing: Equatable, Sendable {
    let shape: AdmissionShape
    /// Modules across, quiet zone included.
    let width: Int
    /// Modules down; 1 for a linear code, whose bars are one row stretched.
    let height: Int
    /// Row-major, top row first. True is ink.
    let dark: [Bool]

    func isDark(_ x: Int, _ y: Int) -> Bool { dark[y * width + x] }

    /// Pixels: `modulePixels` per module, a whole number so every bar and cell is the
    /// same width on screen (story 9), and `linearHeight` tall for a linear code.
    /// `border` is extra white on every side — the decoder's margin at import, never a
    /// substitute for the quiet zone already drawn in.
    func image(modulePixels: Int, linearHeight: Int = 0, border: Int = 0) -> CGImage? {
        guard modulePixels > 0 else { return nil }
        let codeHeight = shape == .linear ? max(linearHeight, 1) : height * modulePixels
        let pixelsWide = width * modulePixels + 2 * border
        let pixelsHigh = codeHeight + 2 * border
        guard let context = CGContext(data: nil, width: pixelsWide, height: pixelsHigh,
                                      bitsPerComponent: 8, bytesPerRow: 0,
                                      space: CGColorSpaceCreateDeviceGray(),
                                      bitmapInfo: CGImageAlphaInfo.none.rawValue)
        else { return nil }
        context.interpolationQuality = .none
        context.setShouldAntialias(false)
        context.setFillColor(gray: 1, alpha: 1)
        context.fill(CGRect(x: 0, y: 0, width: pixelsWide, height: pixelsHigh))
        context.setFillColor(gray: 0, alpha: 1)
        for y in 0..<height {
            for x in 0..<width where isDark(x, y) {
                // CoreGraphics counts rows from the bottom; `dark` from the top.
                let top = shape == .linear ? 0 : y * modulePixels
                let tall = shape == .linear ? codeHeight : modulePixels
                context.fill(CGRect(x: border + x * modulePixels,
                                    y: pixelsHigh - border - top - tall,
                                    width: modulePixels, height: tall))
            }
        }
        return context.makeImage()
    }
}

/// Modules of quiet zone around a matrix code: the QR's own four, used for all of them.
/// Aztec needs none by its spec and Data Matrix one; four costs nothing in a square.
private let matrixQuietZone = 4
/// Modules of quiet zone either side of a linear code: Code 128's ten, and room for
/// EAN-13's eleven on the left (Android draws those; CoreImage cannot).
private let linearQuietZone = 11

/// One context for every redraw: it owns GPU resources, and the Room redraws often.
private let redrawContext = CIContext()

/// The Admission's own symbology, redrawn from its payload (#441). Nil when this
/// platform has no generator for the symbology or the generator refuses the payload —
/// never a blank box, and never the payload in some other symbology.
///
/// CoreImage is this platform's generator, as zxing is Android's, so neither side takes
/// a dependency. It draws QR, Aztec, PDF417 and Code 128. It has **no Data Matrix, EAN,
/// UPC, Code 39, Code 93, ITF or Codabar generator**, so an Admission in one of those
/// is "can't be shown" here even though Android draws it: a parity gap in capability,
/// not in the rule, and the prompt says so at import rather than the door at the night.
///
/// The QR is drawn at correction level M on both twins (#441). H was chosen here once
/// for a scratched phone in the dark, but a scratch is not what a phone screen suffers:
/// the cost is density, and at phone size a denser code is harder for a handheld
/// scanner to lock onto. Aztec at 33% and PDF417 at level 2 are zxing's defaults,
/// asked for here so the same payload draws the same size on both.
func admissionDrawing(symbology: String, payload: Data) -> AdmissionDrawing? {
    guard !payload.isEmpty else { return nil }
    let shape: AdmissionShape
    let output: CIImage?
    switch symbology {
    case "qr":
        let filter = CIFilter.qrCodeGenerator()
        filter.message = payload
        filter.correctionLevel = "M"
        shape = .matrix
        output = filter.outputImage
    case "aztec":
        let filter = CIFilter.aztecCodeGenerator()
        filter.message = payload
        filter.correctionLevel = 33
        shape = .matrix
        output = filter.outputImage
    case "pdf417":
        let filter = CIFilter.pdf417BarcodeGenerator()
        filter.message = payload
        filter.correctionLevel = 2
        shape = .matrix
        output = filter.outputImage
    case "code128":
        let filter = CIFilter.code128BarcodeGenerator()
        filter.message = payload
        filter.quietSpace = 0
        shape = .linear
        output = filter.outputImage
    default:
        return nil
    }
    guard let output, !output.extent.isInfinite, !output.extent.isEmpty,
          let image = redrawContext.createCGImage(output, from: output.extent)
    else { return nil }
    return modules(of: image, shape: shape)
}

func admissionDrawing(_ admission: Admission) -> AdmissionDrawing? {
    admissionDrawing(symbology: admission.symbology, payload: admission.payload)
}

func admissionDrawing(_ admission: StoredAdmission) -> AdmissionDrawing? {
    admission.payloadBytes.flatMap { admissionDrawing(symbology: admission.symbology, payload: $0) }
}

/// A generator's output, one pixel per module, as cells: trimmed to its ink and given
/// this file's quiet zone, whatever margin the generator put on it (CoreImage's QR
/// carries one of its own; its Aztec and Code 128 do not, with `quietSpace` at 0).
private func modules(of image: CGImage, shape: AdmissionShape) -> AdmissionDrawing? {
    let w = image.width, h = image.height
    guard w > 0, h > 0,
          let context = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8,
                                  bytesPerRow: w, space: CGColorSpaceCreateDeviceGray(),
                                  bitmapInfo: CGImageAlphaInfo.none.rawValue)
    else { return nil }
    context.interpolationQuality = .none
    context.setFillColor(gray: 1, alpha: 1)
    context.fill(CGRect(x: 0, y: 0, width: w, height: h))
    context.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
    guard let data = context.data else { return nil }
    // A bitmap context's memory starts at the image's top row.
    let bytes = data.bindMemory(to: UInt8.self, capacity: w * h)
    func ink(_ x: Int, _ y: Int) -> Bool { bytes[y * w + x] < 128 }

    var minX = w, maxX = -1, minY = h, maxY = -1
    for y in 0..<h {
        for x in 0..<w where ink(x, y) {
            minX = min(minX, x); maxX = max(maxX, x)
            minY = min(minY, y); maxY = max(maxY, y)
        }
    }
    guard maxX >= minX, maxY >= minY else { return nil }

    switch shape {
    case .linear:
        // Every row of a linear code is the same row; the middle one is the least
        // likely to be caught by anything a generator draws at the ends.
        let row = (minY + maxY) / 2
        let bars = (minX...maxX).map { ink($0, row) }
        let quiet = [Bool](repeating: false, count: linearQuietZone)
        let cells = quiet + bars + quiet
        return AdmissionDrawing(shape: .linear, width: cells.count, height: 1, dark: cells)
    case .matrix:
        let q = matrixQuietZone
        let width = maxX - minX + 1 + 2 * q
        let height = maxY - minY + 1 + 2 * q
        var cells = [Bool](repeating: false, count: width * height)
        for y in minY...maxY {
            for x in minX...maxX where ink(x, y) {
                cells[(y - minY + q) * width + (x - minX + q)] = true
            }
        }
        return AdmissionDrawing(shape: .matrix, width: width, height: height, dark: cells)
    }
}

// MARK: - The check at import (story 29)

/// Whether the Admission's redraw reads back as the same payload in the same symbology:
/// drawn exactly as the Room will draw it, then decoded by Vision — the reader this
/// platform already trusts to read the ticket in the first place.
///
/// The payload is compared as bytes against the decoded **text**'s UTF-8, the form the
/// Share Extension stores (`VisionBarcodeLocator`) and Android stores too. A payload that
/// is not text — a binary QR — does not survive that and is reported as not redrawable,
/// which is the honest answer until byte segments are carried (#441, out of this slice).
///
/// Synchronous and not cheap (one Vision pass): call it off the main actor.
func redrawsExactly(symbology: String, payload: Data) -> Bool {
    redrawCheck(symbology: symbology, payload: payload) == .readsBack
}

/// What one draw-and-decode found. Only the first two are answers: `unanswered` is
/// Vision failing to say (it threw, or read nothing at all off a drawing it was given),
/// which may not be true the next time it is asked, so `AdmissionVerdicts` never keeps
/// it (the #441 review). The import's check still counts it as not redrawable.
enum RedrawCheck: Equatable, Sendable {
    /// Read back as the same payload in the same symbology.
    case readsBack
    /// No: nothing this platform can draw it with, or Vision read something else.
    case readsDifferently
    /// Vision threw, or found no barcode at all.
    case unanswered
}

/// `redrawsExactly`, with Vision's failures told apart from its answers.
func redrawCheck(symbology: String, payload: Data) -> RedrawCheck {
    // Nothing to draw it with, or no Vision symbology to read it as: the same answer
    // every time.
    guard let drawing = admissionDrawing(symbology: symbology, payload: payload),
          let image = drawing.image(modulePixels: 4, linearHeight: 160, border: 32),
          let vision = visionSymbology(symbology)
    else { return .readsDifferently }
    return autoreleasepool {
        let request = VNDetectBarcodesRequest()
        #if targetEnvironment(simulator)
        // The simulator cannot compile the model the current revisions run ("e5rt …
        // OPERATION ERROR" on CI, and no results), so there — and only there, which is
        // where the tests run — the pre-model detector reads the redraw back. A phone
        // uses the revision it reads tickets with.
        request.revision = VNDetectBarcodesRequestRevision1
        #endif
        request.symbologies = [vision]
        do {
            try VNImageRequestHandler(cgImage: image, options: [:]).perform([request])
        } catch {
            return .unanswered
        }
        let results = request.results ?? []
        guard !results.isEmpty else { return .unanswered }
        let same = results.contains { found in
            ticketSymbology(found.symbology) == symbology
                && found.payloadStringValue.map { Data($0.utf8) } == payload
        }
        return same ? .readsBack : .readsDifferently
    }
}

extension Admission {
    /// This Admission with `redrawable` answered by `redrawsExactly`.
    func checkedForRedraw(_ check: (String, Data) -> Bool = redrawsExactly) -> Admission {
        var checked = self
        checked.redrawable = check(symbology, payload)
        return checked
    }
}

extension Ticket {
    /// Every Admission checked (story 29): what `routeTicket` needs before it may add
    /// this **Ticket** without asking, and what the prompt shows when it may not.
    /// `check` is Vision's `redrawsExactly` everywhere but in a test.
    func checkedForRedraw(_ check: (String, Data) -> Bool = redrawsExactly) -> Ticket {
        var checked = self
        checked.admissions = admissions.map { $0.checkedForRedraw(check) }
        return checked
    }
}

/// The Room's verdicts, one per payload and symbology, so a **Room** that re-renders
/// does not run Vision again for an Admission it has already read back. Stored
/// Admissions carry no verdict (it can always be recomputed, and one written by an older
/// build — a migrated `ticketQr` — was never checked at all), so the Room asks here.
///
/// Only answers are kept (`RedrawCheck`): a Vision failure is not a verdict, and caching
/// one would say "can't be shown" for as long as the app runs about a code that might
/// read back the next time (the #441 review). Such a code is checked again on every
/// Room render until Vision answers, which costs time, not correctness.
actor AdmissionVerdicts {
    static let shared = AdmissionVerdicts()
    private var known: [String: Bool] = [:]
    private let check: @Sendable (String, Data) -> RedrawCheck

    init(check: @escaping @Sendable (String, Data) -> RedrawCheck = { redrawCheck(symbology: $0, payload: $1) }) {
        self.check = check
    }

    func redraws(symbology: String, payload: Data) async -> Bool {
        let key = symbology + ":" + payload.base64EncodedString()
        if let verdict = known[key] { return verdict }
        let check = self.check
        let found = await Task.detached(priority: .userInitiated) { check(symbology, payload) }.value
        switch found {
        case .readsBack: known[key] = true
        case .readsDifferently: known[key] = false
        case .unanswered: break
        }
        return found == .readsBack
    }
}
