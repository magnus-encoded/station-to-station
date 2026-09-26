import Vision

// Vision's names for barcode formats, and `fixtures/ticket/README.md`'s (#441).
//
// In the shared folder because both halves need it: the Share Extension names what it
// found on a page, and the app names what it reads back off its own redraw when it
// checks one at import. One mapping, so the two cannot disagree about what a Code 128 is.

/// The formats `VisionBarcodeLocator` asks for (#441, story 21): every one a real
/// ticket has been seen to carry, and the retail ones beside them.
///
/// - `qr`, `aztec`, `pdf417` and `dataMatrix` are the matrix formats; `code128` is the
///   Eventim ticket's.
/// - `ean13`, `ean8` and `upce` are asked for only so the parser can see them and apply
///   its retail rule: dropped beside anything else, kept only when they are all there is.
///   Android's reader sees them unasked; not asking here would be a parity gap in the
///   *evidence*, below the one rule both twins share.
/// - Not asked for: Code 39, Code 93, ITF and Codabar. No ticket seen so far carries
///   one, the Room could not redraw them on this platform, and every extra format is
///   another chance for small print to read as a false barcode.
let ticketVisionSymbologies: [VNBarcodeSymbology] = [
    .qr, .aztec, .pdf417, .dataMatrix, .code128, .ean13, .ean8, .upce,
]

/// The fixtures' name for a Vision symbology, the same one Android's `ticketSymbology`
/// derives from zxing's (`QR_CODE` is `qr`, `DATA_MATRIX` is `datamatrix`). Nil for a
/// format outside `ticketVisionSymbologies`: an unnamed barcode is not an **Admission**.
func ticketSymbology(_ symbology: VNBarcodeSymbology) -> String? {
    switch symbology {
    case .qr: return "qr"
    case .aztec: return "aztec"
    case .pdf417: return "pdf417"
    case .dataMatrix: return "datamatrix"
    case .code128: return "code128"
    case .ean13: return "ean13"
    case .ean8: return "ean8"
    case .upce: return "upce"
    default: return nil
    }
}

/// The other way: the Vision symbology a fixtures name is read back as. Nil for a name
/// Vision is not asked about here.
func visionSymbology(_ symbology: String) -> VNBarcodeSymbology? {
    ticketVisionSymbologies.first { ticketSymbology($0) == symbology }
}
