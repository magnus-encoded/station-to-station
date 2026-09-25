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
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/*
 * The exact half of reading a Ticket, as far as it goes before #441: one barcode
 * decoded off a page, kept as the text a scanner at the door would read back out,
 * and redrawn from that text. Pure zxing core on plain pixel arrays — no Bitmap, no
 * PdfRenderer — so the round trip is asserted on the JVM (TicketParsingTest) rather
 * than assumed. `extractTicket` is the only caller that feeds it real pages.
 *
 * This is an interim slice of #441, Android only. The stored shape is unchanged:
 * `StoredAttendance.ticketQr` stays one base64 value with no symbology beside it,
 * because renaming or reshaping it is a lockstep change on both twins (ADR-0020's
 * closing note; a key only one twin knows is lost when the other writes).
 */

// zxing's unhinted single pass is tuned for a camera frame filled by a barcode. A
// ticket PDF is the opposite — a small, crisp symbol in one corner of a page of text —
// and on device (probe-report.md, 2026-09-25) the unhinted pass found none of the
// three QRs on a real Billettservice ticket or the two Code 128s on each Eventim one;
// with TRY_HARDER it found all of them at 200dpi. POSSIBLE_FORMATS is left
// unrestricted on purpose: a non-QR has to be *seen* to be reported honestly (see
// [chooseTicketBarcode]), not silently missed. With TRY_HARDER and no format list,
// MultiFormatReader tries the matrix readers (QR first) before the linear ones, so a
// page carrying a QR returns the QR.
private val DECODE_HINTS: Map<DecodeHintType, Any> = mapOf(DecodeHintType.TRY_HARDER to true)

/**
 * One page's pixels ([width] × [height], ARGB rows as `Bitmap.getPixels` hands them
 * back) through the hinted reader. Null when nothing on the page decodes. The first
 * barcode zxing finds is the only one returned — keeping every barcode on a page is
 * #441's Admissions list, not this.
 */
fun decodeTicketBarcode(width: Int, height: Int, pixels: IntArray): Result? {
    val source = RGBLuminanceSource(width, height, pixels)
    return try {
        MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), DECODE_HINTS)
    } catch (e: NotFoundException) {
        null
    }
}

/**
 * What the page loop settles on, from every page's result in page order.
 *
 * [qrBytes] is the first QR's decoded **text**, as UTF-8 — the same bytes
 * `handleTicketLink`'s `qr=` already stores, so both input paths now agree. Never
 * zxing's `rawBytes`: those are the symbol's own codewords (mode and length headers,
 * padding), not the payload, and on device every real QR redrawn from them decoded to
 * different text.
 *
 * [unsupportedFormat] is the zxing name of the first barcode that was found but is not
 * a QR (e.g. `CODE_128`). Nothing stores which symbology a payload came in yet, and
 * the day-of view can only draw a QR, so storing a Code 128's text would put a QR in
 * front of a door that expects a Code 128 — worse than nothing, because it looks like
 * a ticket. It is reported instead, for the confirm prompt to say so.
 */
class TicketBarcodeChoice(val qrBytes: ByteArray?, val unsupportedFormat: String?)

/**
 * Picks what [TicketBarcodeChoice] describes. The first QR wins, as it did before
 * (first-barcode behaviour is unchanged; several Admissions is #441). A non-QR seen on
 * an earlier page is still reported alongside a QR found later, because that ticket
 * may well be the Code 128 with an unrelated QR printed after it — the prompt saying
 * "bring the PDF" costs nothing there.
 *
 * Known gap: a QR whose payload is binary rather than text (zxing's BYTE_SEGMENTS) is
 * not preserved by storing decoded text. The probe's one binary example lost its bytes
 * this way. No real ticket seen so far carries one; #441 stores byte segments.
 */
fun chooseTicketBarcode(pageResults: List<Result>): TicketBarcodeChoice {
    val qr = pageResults.firstOrNull { it.barcodeFormat == BarcodeFormat.QR_CODE && it.text != null }
    val other = pageResults.firstOrNull { it.barcodeFormat != BarcodeFormat.QR_CODE }
    return TicketBarcodeChoice(
        qrBytes = qr?.text?.toByteArray(Charsets.UTF_8),
        unsupportedFormat = other?.barcodeFormat?.name,
    )
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
