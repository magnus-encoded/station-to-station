package io.github.magnusencoded.stationtostation.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * The one rasterization pipeline #411/#408 call for: turn a shared PDF's pages into
 * bitmaps, then read the same bitmap two ways — zxing for a QR, ML Kit for whatever
 * text is on the page. Neither reader knows about the other; this function is only
 * the plumbing that feeds them both the same pixels.
 *
 * `PdfRenderer` is a platform type with no fake to hand a test, so nothing here is
 * unit-tested — see [parseTicket] for the seam that is. Capped at the first five
 * pages: a ticket is one or two pages, and an unrelated PDF shared by mistake should
 * not spend minutes rasterizing a program listing before failing to find a QR.
 */
suspend fun extractTicket(context: Context, uri: Uri): TicketExtract = withContext(Dispatchers.IO) {
    val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext TicketExtract()
    pfd.use { descriptor ->
        val renderer = try {
            PdfRenderer(descriptor)
        } catch (e: Exception) {
            return@withContext TicketExtract()
        }
        renderer.use { r ->
            var barcode: DecodedBarcode? = null
            val textBlocks = mutableListOf<String>()
            val pageCount = minOf(r.pageCount, MAX_PAGES)
            for (index in 0 until pageCount) {
                val bitmap = renderPage(r, index)
                if (barcode == null) barcode = decodeBarcode(bitmap)
                textBlocks += recognizeText(bitmap)
                bitmap.recycle()
            }
            TicketExtract(qrBytes = barcode?.bytes, qrFormat = barcode?.format, textBlocks = textBlocks)
        }
    }
}

private const val MAX_PAGES = 5

// A ticket's QR and any small print need real resolution to read; PdfRenderer's
// default render is one point per pixel, which is too coarse for either. Points are
// 1/72"; this targets roughly 200dpi.
private const val RENDER_SCALE = 200f / 72f

private fun renderPage(renderer: PdfRenderer, index: Int): Bitmap {
    val page = renderer.openPage(index)
    val bitmap = Bitmap.createBitmap(
        (page.width * RENDER_SCALE).toInt().coerceAtLeast(1),
        (page.height * RENDER_SCALE).toInt().coerceAtLeast(1),
        Bitmap.Config.ARGB_8888,
    )
    // White background: a PDF page has no fill of its own, and PdfRenderer leaves
    // unpainted pixels transparent — which both readers would otherwise see as black.
    bitmap.eraseColor(android.graphics.Color.WHITE)
    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
    page.close()
    return bitmap
}

/** One page's barcode: the payload to re-show, and the symbology to re-show it in. */
private class DecodedBarcode(val bytes: ByteArray, val format: String)

// zxing's unhinted single pass is tuned for a camera frame — a barcode filling much
// of a roughly-focused image. A ticket PDF is the opposite: a small, pixel-perfect
// symbol in one corner of a page of text, and the plain scan misses it. Measured
// against both real tickets (#411): unhinted, zxing found nothing on any of the four
// pages; with TRY_HARDER it read all four. The cost is a slower scan of a page that
// has no barcode at all, which the MAX_PAGES cap already bounds.
private val DECODE_HINTS = mapOf<DecodeHintType, Any>(DecodeHintType.TRY_HARDER to true)

private fun decodeBarcode(bitmap: Bitmap): DecodedBarcode? {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    val source = RGBLuminanceSource(width, height, pixels)
    val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
    return try {
        val result = MultiFormatReader().decode(binaryBitmap, DECODE_HINTS)
        // The decoded *payload*, not `rawBytes`. rawBytes is the symbol's own
        // codewords — error correction, mode and length headers and all — which is
        // not what a scanner at the door reads back out, so re-encoding it would
        // produce a barcode carrying something no ticketing system has ever heard
        // of. A barcode is stored here to be re-shown, so the text form is the
        // thing to keep; ISO-8859-1 because that is the charset it round-trips
        // through on the way back out (see `barcodeBitmap`).
        val text = result.text ?: return null
        DecodedBarcode(text.toByteArray(Charsets.ISO_8859_1), result.barcodeFormat.name)
    } catch (e: NotFoundException) {
        null
    }
}

private suspend fun recognizeText(bitmap: Bitmap): List<String> {
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    val image = InputImage.fromBitmap(bitmap, 0)
    val text = suspendCancellableCoroutine { continuation ->
        recognizer.process(image)
            .addOnSuccessListener { continuation.resume(it) }
            .addOnFailureListener { continuation.resume(null) }
    }
    return text?.textBlocks?.map { it.text } ?: emptyList()
}
