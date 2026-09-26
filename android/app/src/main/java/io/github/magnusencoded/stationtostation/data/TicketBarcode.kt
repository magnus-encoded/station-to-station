package io.github.magnusencoded.stationtostation.data

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.ResultPoint
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/*
 * The exact half of reading a Ticket, as far as it goes before #441: the barcodes
 * decoded off a page, each kept as the text a scanner at the door would read back out,
 * and a QR redrawn from that text. Pure zxing core on plain pixel arrays — no Bitmap,
 * no PdfRenderer — so the round trip is asserted on the JVM (TicketBarcodeTest) rather
 * than assumed. `ZxingBarcodeLocator` (TicketExtraction.kt) is the only caller that
 * feeds it real pages.
 *
 * This is an interim slice of #441, Android only. The stored shape is unchanged:
 * `StoredAttendance.ticketQr` stays one base64 value with no symbology beside it,
 * because renaming or reshaping it is a lockstep change on both twins (ADR-0020's
 * closing note; a key only one twin knows is lost when the other writes). The
 * evidence carries every barcode (#526); `parseTicketFields` keeps the first QR.
 */

// zxing's unhinted single pass is tuned for a camera frame filled by a barcode. A
// ticket PDF is the opposite — a small, crisp symbol in one corner of a page of text —
// and on device (probe-report.md, 2026-09-25) the unhinted pass found none of the
// three QRs on a real Billettservice ticket or the two Code 128s on each Eventim one;
// with TRY_HARDER it found all of them at 200dpi. POSSIBLE_FORMATS is left
// unrestricted on purpose for the first pass: a non-QR has to be *seen* to be
// reported honestly (see `ParsedTicket.unsupportedBarcodeFormat`), not silently
// missed. With TRY_HARDER and no format list, MultiFormatReader tries the matrix
// readers (QR first) before the linear ones, so a page carrying a QR returns the QR.
private val DECODE_HINTS: Map<DecodeHintType, Any> = mapOf(DecodeHintType.TRY_HARDER to true)

/**
 * One page's pixels ([width] × [height], ARGB rows as `Bitmap.getPixels` hands them
 * back) through the hinted reader. Null when nothing on the page decodes. The first
 * barcode zxing finds, and only that — see [decodeTicketBarcodes] for the page's all.
 */
fun decodeTicketBarcode(width: Int, height: Int, pixels: IntArray): Result? =
    decodeTicketBarcode(binary(width, height, pixels))

private fun decodeTicketBarcode(bitmap: BinaryBitmap): Result? =
    try {
        MultiFormatReader().decode(bitmap, DECODE_HINTS)
    } catch (e: NotFoundException) {
        null
    }

private fun binary(width: Int, height: Int, pixels: IntArray) =
    BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels)))

/**
 * Every barcode on one page, in the order found: the single hinted pass first, then —
 * only on a page where that found something — zxing's multi-barcode reader, looking for
 * more **of the same symbology**.
 *
 * Every barcode, within a budget. The probe (#441) found that every-format multi
 * decoding costs 27–49 s a real PDF and turns up EAN-13, EAN-8 and UPC-E "candidates"
 * on tickets whose Admission is a QR — unverified, and probably the ticket's other
 * print. The single pass already says which kind of code the page is built around
 * (matrix readers first, so a QR page says QR); asking the multi reader for more of
 * that kind finds a Billettservice ticket's three QRs and an Eventim ticket's two
 * Code 128s without paying for, or reporting, the rest. A page with nothing on it
 * costs what it did before #526.
 *
 * Duplicates within the page are left in; [distinctTicketBarcodes] takes them out
 * across the whole ticket.
 */
fun decodeTicketBarcodes(width: Int, height: Int, pixels: IntArray): List<Result> {
    val bitmap = binary(width, height, pixels)
    val first = decodeTicketBarcode(bitmap) ?: return emptyList()
    val more = try {
        GenericMultipleBarcodeReader(MultiFormatReader())
            .decodeMultiple(bitmap, DECODE_HINTS + (DecodeHintType.POSSIBLE_FORMATS to listOf(first.barcodeFormat)))
            .toList()
    } catch (e: NotFoundException) {
        emptyList()
    }
    return listOf(first) + more
}

/**
 * `fixtures/ticket/README.md`'s name for a zxing format: `QR_CODE` is `qr`, and every
 * other is its zxing name lowercased without underscores (`CODE_128` is `code128`,
 * `EAN_13` is `ean13`, `DATA_MATRIX` is `datamatrix`) — the same names iOS writes.
 */
fun ticketSymbology(format: BarcodeFormat): String =
    if (format == BarcodeFormat.QR_CODE) "qr" else format.name.lowercase(Locale.ROOT).replace("_", "")

/**
 * The zxing name for a symbology, as the confirm prompt has said it since #534
 * (`CODE_128`). The symbology itself when no zxing format has that name.
 */
fun zxingFormatName(symbology: String): String =
    BarcodeFormat.values().firstOrNull { ticketSymbology(it) == symbology }?.name ?: symbology

/**
 * One decoded barcode as evidence, [image] its crop.
 *
 * The payload is the decoded **text**, as UTF-8 — the same bytes `handleTicketLink`'s
 * `qr=` already stores, so both input paths agree. Never zxing's `rawBytes`: those are
 * the symbol's own codewords (mode and length headers, padding), not the payload, and
 * on device every real QR redrawn from them decoded to different text.
 *
 * Known gap: a QR whose payload is binary rather than text (zxing's BYTE_SEGMENTS) is
 * not preserved by storing decoded text. The probe's one binary example lost its bytes
 * this way. No real ticket seen so far carries one; #441 stores byte segments.
 */
fun Result.toTicketBarcode(image: ByteArray = ByteArray(0), page: Int? = null): TicketBarcode =
    TicketBarcode(
        image = image,
        payload = text?.toByteArray(Charsets.UTF_8),
        symbology = ticketSymbology(barcodeFormat),
        page = page,
    )

/**
 * The ticket's barcodes with each (symbology, payload) kept once, first sighting wins —
 * a ticket that prints its one QR on every page is one barcode, not three (#441's
 * probe: removing duplicates by payload works).
 */
fun distinctTicketBarcodes(barcodes: List<TicketBarcode>): List<TicketBarcode> =
    barcodes.distinctBy { it.symbology to it.payload?.toList() }

/** A pixel rectangle, right and bottom exclusive. */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * The pixel rectangle to cut a barcode out of its page with: the box around the points
 * zxing reported, grown on every side by [padding] of its longer edge and clamped to
 * the image. Null when there are no points, or nothing is left inside the image.
 *
 * A symbol cropped to its own bounds is not readable: a QR needs four modules of blank
 * quiet zone around it, and a scanner at the door is given exactly the crop. iOS grows
 * Vision's bounds by a fifth (`barcodeCrop` in PdfTicketExtractor.swift); zxing does not
 * report bounds but the centres of a QR's finder patterns, 3.5 modules in from each
 * corner, and a linear code's two ends on one scan row. So the default here is larger:
 * on the smallest QR (21 modules, centres 14 apart) 0.6 reaches 8.4 modules out, past
 * the corner and a four-module quiet zone, and on a linear code it gives the strip a
 * height of 1.2 of its width.
 */
fun barcodeCrop(
    points: List<ResultPoint?>?,
    imageWidth: Int,
    imageHeight: Int,
    padding: Float = 0.6f,
): PixelRect? {
    val found = points?.filterNotNull().orEmpty()
    if (found.isEmpty()) return null
    val minX = found.minOf { it.x }
    val maxX = found.maxOf { it.x }
    val minY = found.minOf { it.y }
    val maxY = found.maxOf { it.y }
    val margin = maxOf(maxX - minX, maxY - minY) * padding
    val rect = PixelRect(
        left = floor(minX - margin).toInt().coerceAtLeast(0),
        top = floor(minY - margin).toInt().coerceAtLeast(0),
        right = ceil(maxX + margin).toInt().coerceAtMost(imageWidth),
        bottom = ceil(maxY + margin).toInt().coerceAtMost(imageHeight),
    )
    return rect.takeIf { it.width > 0 && it.height > 0 }
}

/**
 * A stored `ticketQr`'s bytes back to the text to redraw — strictly UTF-8, the one
 * charset everything since this change writes. Null when the bytes are not valid UTF-8.
 *
 * That null is how values written before this change behave: the PDF path used to
 * store zxing's `rawBytes`, which were never the payload and never redrew correctly
 * (the old ISO-8859-1 read only hid that). Codewords are rarely valid UTF-8, so most
 * such values now draw no barcode at all rather than a wrong one. One that happens to
 * be valid UTF-8 still draws wrong; it cannot be told apart from a real payload, and
 * re-sharing the ticket PDF replaces it.
 */
fun ticketQrText(bytes: ByteArray): String? =
    try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

/**
 * The QR matrix the day-of view draws for a ticket's stored text, [sizePx] square.
 *
 * zxing's writer defaults to ISO-8859-1 with no ECI, and its own reader then *guesses*
 * the charset back: checked against zxing 3.5.4, "ÆØÅ" came back as Shift_JIS
 * half-width katakana and "–" as "?". So anything outside ASCII is written as UTF-8
 * with an ECI marker saying so; pure ASCII — every real ticket payload seen so far —
 * is written exactly as it was before, with no ECI, since that is the form most
 * likely to be read by whatever scanner a venue owns.
 */
fun ticketQrMatrix(text: String, sizePx: Int): BitMatrix {
    val hints = if (text.all { it.code < 0x80 }) {
        emptyMap()
    } else {
        mapOf<EncodeHintType, Any>(EncodeHintType.CHARACTER_SET to Charsets.UTF_8.name())
    }
    return MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
}
