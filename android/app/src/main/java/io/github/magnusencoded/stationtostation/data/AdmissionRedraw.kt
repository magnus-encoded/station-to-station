package io.github.magnusencoded.stationtostation.data

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.ReaderException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.datamatrix.encoder.SymbolShapeHint
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.concurrent.ConcurrentHashMap

/*
 * An Admission redrawn in its own symbology (#441, stories 2, 3, 9, 29), and checked.
 *
 * One drawing serves three readers: the check at import, the confirm prompt and the
 * Room. The check decodes exactly the modules the Room shows (quiet zone, correction
 * level, charset and all), so "it read back" is a statement about the code at the door
 * and not about a cousin of it. Pure zxing core on the JVM, so every symbology's round
 * trip is asserted in AdmissionRedrawTest rather than assumed. The Swift twin is
 * `AdmissionRedraw.swift`, over CoreImage and Vision: the two agree on shapes, quiet
 * zones and the QR's correction level, and differ in which symbologies they can draw.
 */

/**
 * How a symbology is laid out. A matrix code keeps its own aspect (square for QR, Aztec
 * and Data Matrix; wide for PDF417); a linear one is a strip, as wide as the screen
 * allows, whose height carries no information.
 */
enum class AdmissionShape { MATRIX, LINEAR }

/**
 * An Admission as it will be drawn: [modules] has one cell per module (the narrowest bar,
 * for a linear code, whose one row is its bars), black on white, with the quiet zone
 * already in it. A code bled to its own edge is one many scanners will not see at all,
 * so the margin is part of the symbol rather than of whatever view holds it.
 */
class AdmissionDrawing(val shape: AdmissionShape, val modules: BitMatrix) {
    val width: Int get() = modules.width
    val height: Int get() = modules.height

    /**
     * Pixels: [modulePx] per module — a whole number, so every bar and cell is the same
     * width on screen (story 9) — and [linearHeightPx] tall for a linear code. [borderPx]
     * is extra white on every side: the decoder's margin at import, never a substitute
     * for the quiet zone already drawn in.
     */
    fun scaled(modulePx: Int, linearHeightPx: Int = 0, borderPx: Int = 0): BitMatrix {
        require(modulePx > 0) { "modulePx must be positive" }
        val codeHeight = if (shape == AdmissionShape.LINEAR) linearHeightPx.coerceAtLeast(1) else height * modulePx
        val out = BitMatrix(width * modulePx + 2 * borderPx, codeHeight + 2 * borderPx)
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!modules[x, y]) continue
                val top = if (shape == AdmissionShape.LINEAR) 0 else y * modulePx
                val tall = if (shape == AdmissionShape.LINEAR) codeHeight else modulePx
                out.setRegion(borderPx + x * modulePx, borderPx + top, modulePx, tall)
            }
        }
        return out
    }
}

/** Modules of quiet zone around a matrix code: the QR's own four, used for all of them. */
private const val MATRIX_QUIET_ZONE = 4

/**
 * Modules either side of a linear code: Code 128's ten, and room for EAN-13's eleven on
 * the left. zxing's own writers leave Aztec and Data Matrix bare and give Code 128 ten,
 * so every drawing is trimmed to its ink and given these instead — one rule, the same
 * numbers `AdmissionRedraw.swift` uses.
 */
private const val LINEAR_QUIET_ZONE = 11

private val LINEAR_FORMATS = setOf(
    BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.CODE_93, BarcodeFormat.ITF,
    BarcodeFormat.CODABAR, BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E,
)

/** The formats whose zxing writers take [EncodeHintType.CHARACTER_SET] and mark it with an ECI. */
private val CHARSET_FORMATS = setOf(
    BarcodeFormat.QR_CODE, BarcodeFormat.AZTEC, BarcodeFormat.PDF_417, BarcodeFormat.DATA_MATRIX,
)

/** The zxing format a fixtures symbology name redraws as, or null for a name zxing has no format for. */
fun admissionFormat(symbology: String): BarcodeFormat? =
    BarcodeFormat.values().firstOrNull { ticketSymbology(it) == symbology }

/**
 * The Admission's own symbology, redrawn from its payload's text (#441). Null when the
 * payload is not UTF-8 text, zxing has no writer for the symbology, or the writer
 * refuses the payload (an EAN with a bad check digit, a Code 128 of text it cannot
 * carry) — never a blank box, and never the payload in some other symbology.
 *
 * zxing writes QR, Aztec, PDF417, Data Matrix, Code 128/39/93, ITF, Codabar and
 * EAN/UPC. CoreImage, the iOS twin's generator, writes only the first three and Code
 * 128: the rest are drawn here and "can't be shown" there (a parity gap in capability,
 * not in the rule; the PR records it).
 *
 * - **The QR is drawn at correction level M on both twins.** Android drew at zxing's
 *   default L and iOS at H. H buys resistance to damage a phone screen does not suffer
 *   and pays in density, and at phone size a denser code is harder for a handheld
 *   scanner to lock onto. M is the usual middle, and the same payload now draws the
 *   same symbol on both.
 * - **Charset as #534 set it for the QR, now for every format that can say it:** pure
 *   ASCII is written exactly as before with no ECI (every real ticket payload seen so
 *   far, and the form any scanner reads), and anything else as UTF-8 with an ECI. Linear
 *   formats carry no ECI; the round trip decides whether they carried the text at all.
 */
fun admissionDrawing(symbology: String, payload: ByteArray): AdmissionDrawing? {
    val format = admissionFormat(symbology) ?: return null
    val text = admissionText(payload)?.takeIf { it.isNotEmpty() } ?: return null
    val hints = buildMap<EncodeHintType, Any> {
        if (format == BarcodeFormat.QR_CODE) put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
        // Square, as every matrix code here is drawn: zxing would otherwise pick a
        // rectangular Data Matrix for a short payload, which fewer readers accept.
        if (format == BarcodeFormat.DATA_MATRIX) put(EncodeHintType.DATA_MATRIX_SHAPE, SymbolShapeHint.FORCE_SQUARE)
        if (format in CHARSET_FORMATS && text.any { it.code >= 0x80 }) {
            put(EncodeHintType.CHARACTER_SET, Charsets.UTF_8.name())
        }
    }
    // 0 × 0 asks each writer for its smallest rendering: one pixel per module.
    val raw = try {
        MultiFormatWriter().encode(text, format, 0, 0, hints)
    } catch (e: WriterException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: UnsupportedOperationException) {
        null
    } ?: return null
    val ink = raw.enclosingRectangle ?: return null
    val (left, top, inkWidth, inkHeight) = ink
    return if (format in LINEAR_FORMATS) {
        // Every row of a linear code is the same row.
        val row = top + inkHeight / 2
        val bars = BitMatrix(inkWidth + 2 * LINEAR_QUIET_ZONE, 1)
        for (x in 0 until inkWidth) if (raw[left + x, row]) bars.set(LINEAR_QUIET_ZONE + x, 0)
        AdmissionDrawing(AdmissionShape.LINEAR, bars)
    } else {
        val q = MATRIX_QUIET_ZONE
        val cells = BitMatrix(inkWidth + 2 * q, inkHeight + 2 * q)
        for (y in 0 until inkHeight) {
            for (x in 0 until inkWidth) if (raw[left + x, top + y]) cells.set(q + x, q + y)
        }
        AdmissionDrawing(AdmissionShape.MATRIX, cells)
    }
}

/**
 * Whether the Admission's redraw reads back as the same payload in the same symbology
 * (story 29): drawn exactly as the Room will draw it, then decoded by the same zxing
 * reader that read the ticket in the first place. The payload is compared as bytes
 * against the decoded text's UTF-8, the form every input path stores.
 *
 * A binary payload (zxing's BYTE_SEGMENTS, not text) does not survive that and is
 * reported as not redrawable — the honest answer until byte segments are carried.
 */
fun redrawsExactly(symbology: String, payload: ByteArray): Boolean {
    val format = admissionFormat(symbology) ?: return false
    val drawing = admissionDrawing(symbology, payload) ?: return false
    val bits = drawing.scaled(modulePx = 4, linearHeightPx = 160, borderPx = 32)
    val pixels = IntArray(bits.width * bits.height) { i ->
        if (bits[i % bits.width, i / bits.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(format))
    val found = try {
        MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bits.width, bits.height, pixels))), hints)
    } catch (e: ReaderException) {
        return false
    }
    return found.barcodeFormat == format && found.text?.toByteArray(Charsets.UTF_8)?.contentEquals(payload) == true
}

/** This Admission with [Admission.redrawable] answered by [check] — [redrawsExactly] everywhere but a test. */
fun Admission.checkedForRedraw(check: (String, ByteArray) -> Boolean = ::redrawsExactly): Admission =
    withRedrawable(check(symbology, payload))

/**
 * Every Admission checked (story 29): what [routeTicket] needs before it may act on
 * this ticket without asking, and what the prompt shows when it may not.
 */
fun ParsedTicket.checkedForRedraw(check: (String, ByteArray) -> Boolean = ::redrawsExactly): ParsedTicket =
    copy(admissions = admissions.map { it.checkedForRedraw(check) })

/**
 * The Room's verdicts, one per symbology and payload, so a Room that recomposes does not
 * decode again an Admission it has already read back. A stored Admission carries no
 * verdict: it can always be recomputed, and one written by an older build (a migrated
 * `ticketQr`) was never checked at all, so the Room asks here.
 */
private val doorVerdicts = ConcurrentHashMap<Pair<String, String>, Boolean>()

/**
 * What the Room draws for a stored Admission: its drawing when it reads back as itself,
 * and null when it does not — which the Room says plainly rather than draw.
 * Not cheap the first time (one zxing decode): call it off the main thread.
 */
fun doorDrawing(admission: StoredAdmission): AdmissionDrawing? {
    val bytes = admission.payloadBytes ?: return null
    val ok = doorVerdicts.getOrPut(admission.symbology to admission.payload) {
        redrawsExactly(admission.symbology, bytes)
    }
    return if (ok) admissionDrawing(admission.symbology, bytes) else null
}
