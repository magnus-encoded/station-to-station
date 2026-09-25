package io.github.magnusencoded.stationtostation.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.zxing.BarcodeFormat
import com.google.zxing.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * The one rasterization pipeline #411/#408 call for: turn a shared PDF's pages into
 * bitmaps, then read the same bitmap two ways — zxing for a barcode, ML Kit for whatever
 * text is on the page. Neither reader knows about the other; this function is only
 * the plumbing that feeds them both the same pixels.
 *
 * `PdfRenderer` is a platform type with no fake to hand a test, so nothing here is
 * unit-tested — see [parseTicket] for the seam that is, and [decodeTicketBarcode] /
 * [chooseTicketBarcode] for the barcode decode this only feeds pixels to. Capped at
 * the first five pages: a ticket is one or two pages, and an unrelated PDF shared by
 * mistake should not spend minutes rasterizing a program listing before failing to
 * find a QR.
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
            val barcodes = mutableListOf<Result>()
            val textBlocks = mutableListOf<String>()
            val pageCount = minOf(r.pageCount, MAX_PAGES)
            for (index in 0 until pageCount) {
                val bitmap = renderPage(r, index)
                // Keep decoding until a QR turns up: a non-QR on page 1 does not stop
                // a QR on page 2 from being found. TRY_HARDER is the slow part, so
                // pages after the first QR are not scanned for barcodes at all.
                if (barcodes.none { it.barcodeFormat == BarcodeFormat.QR_CODE }) {
                    decodeBarcode(bitmap)?.let { barcodes += it }
                }
                textBlocks += recognizeText(bitmap)
                bitmap.recycle()
            }
            val barcode = chooseTicketBarcode(barcodes)
            TicketExtract(
                qrBytes = barcode.qrBytes,
                unsupportedBarcodeFormat = barcode.unsupportedFormat,
                textBlocks = textBlocks,
            )
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

/** The page's pixels through [decodeTicketBarcode] (TicketBarcode.kt), where the hints and their reasons live. */
private fun decodeBarcode(bitmap: Bitmap): Result? {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    return decodeTicketBarcode(width, height, pixels)
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
