package io.github.magnusencoded.stationtostation.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.zxing.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/*
 * The impure half of ticket reading: PdfRenderer, ML Kit and zxing behind the seams
 * `PdfTicketExtractor` (PdfTicketExtractor.kt) is written against — the twin of iOS's
 * `TicketShare/PdfReaders.swift`.
 *
 * Nothing here is unit-tested and that is the seam working as intended: what can be
 * asserted without a device is the extractor's shape (fake pages, fake readers),
 * `parseTicketFields`, which is all this ever feeds, and the zxing decode in
 * TicketBarcode.kt, which runs on plain pixel arrays.
 */

/**
 * The extractor a shared PDF runs through: the text layer and OCR on every page, and
 * zxing for the barcodes, all off the main thread.
 */
fun PdfTicketExtractor.Companion.onDevice(context: Context): TicketExtractor<Uri> {
    val pdf = PdfTicketExtractor<Uri>(
        open = { uri -> RendererPages.open(context, uri) },
        readers = listOf(TextLayerReader, OcrReader),
        barcodes = ZxingBarcodeLocator,
    )
    return TicketExtractor { uri -> withContext(Dispatchers.IO) { pdf.extract(uri) } }
}

/** A PdfRenderer over a shared document, one page at a time. Closing it closes the descriptor too. */
private class RendererPages(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : PdfPages {
    override val count: Int get() = renderer.pageCount

    override fun page(index: Int): PdfPage? =
        try {
            RendererPage(renderer.openPage(index))
        } catch (e: Exception) {
            null
        }

    override fun close() {
        renderer.close()
        descriptor.close()
    }

    companion object {
        /**
         * Null for anything that cannot be opened as a PDF. `openFileDescriptor` throws
         * rather than returning null for most of what goes wrong with a shared Uri — a
         * `SecurityException` when the sender's grant is gone, a
         * `FileNotFoundException` when the file was deleted before this ran — and
         * PdfRenderer throws for a file that is not a PDF or is password protected.
         * Every one of those is the honest "couldn't read this" prompt, not a crash.
         */
        fun open(context: Context, uri: Uri): RendererPages? {
            val descriptor = try {
                context.contentResolver.openFileDescriptor(uri, "r")
            } catch (e: Exception) {
                null
            } ?: return null
            val renderer = try {
                PdfRenderer(descriptor)
            } catch (e: Exception) {
                descriptor.close()
                return null
            }
            return RendererPages(descriptor, renderer)
        }
    }
}

/** One open PdfRenderer page, drawn on the first ask and kept for the rest of the page's readers. */
private class RendererPage(private val page: PdfRenderer.Page) : PdfPage {
    private var bitmap: Bitmap? = null
    private var drawn = false

    override val textLayer: String? by lazy { readTextLayer(page) }

    override fun rendered(): Bitmap? {
        if (!drawn) {
            drawn = true
            bitmap = render(page)
        }
        return bitmap
    }

    override fun close() {
        bitmap?.recycle()
        bitmap = null
        page.close()
    }
}

/**
 * `getTextContents()`, which only exists from API 35 (minSdk is 26). Below that there
 * is no text layer and so no text-layer reading: only OCR's, as before #526. No PDF
 * library is added to fill the gap. One text object can hold several lines; they are
 * joined here and split again by [TextLayerReader], so every object starts a line.
 */
private fun readTextLayer(page: PdfRenderer.Page): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null
    return try {
        page.textContents.joinToString("\n") { it.text }
    } catch (e: Exception) {
        null
    }
}

// A ticket's QR and any small print need real resolution to read; PdfRenderer's
// default render is one point per pixel, which is too coarse for either. Points are
// 1/72"; this targets roughly 200dpi.
private const val RENDER_SCALE = 200f / 72f

private fun render(page: PdfRenderer.Page): Bitmap {
    val bitmap = Bitmap.createBitmap(
        (page.width * RENDER_SCALE).toInt().coerceAtLeast(1),
        (page.height * RENDER_SCALE).toInt().coerceAtLeast(1),
        Bitmap.Config.ARGB_8888,
    )
    // White background: a PDF page has no fill of its own, and PdfRenderer leaves
    // unpainted pixels transparent — which both readers would otherwise see as black.
    bitmap.eraseColor(android.graphics.Color.WHITE)
    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
    return bitmap
}

/**
 * ML Kit's text recognition over the page's one rasterization, as **lines**, in the
 * order ML Kit gives them. Lines rather than the pooled blocks this read before #526:
 * on the probe (2026-09-25) lines were the one input that got a real Billettservice
 * ticket's artist and venue both right, where its blocks put the small print first.
 */
private object OcrReader : PdfTextReader {
    override val origin = TicketReading.Origin.OCR

    override suspend fun lines(page: PdfPage): List<String> {
        val bitmap = page.rendered() ?: return emptyList()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromBitmap(bitmap, 0)
        val text = suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener { continuation.resume(null) }
        }
        return text?.textBlocks?.flatMap { block -> block.lines.map { it.text } }.orEmpty()
    }
}

/**
 * zxing over the same pixels, through [decodeTicketBarcodes] (TicketBarcode.kt), where
 * the hints and their reasons live. Each barcode comes with its crop.
 */
private object ZxingBarcodeLocator : BarcodeLocator {
    override suspend fun locate(page: PdfPage, index: Int): List<TicketBarcode> {
        val bitmap = page.rendered() ?: return emptyList()
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return decodeTicketBarcodes(width, height, pixels)
            // The multi pass finds the single pass's barcode again; crop each once.
            .distinctBy { it.barcodeFormat to it.text }
            .map { it.toTicketBarcode(image = crop(bitmap, it), page = index) }
    }

    /** PNG of the symbol and its quiet zone; empty when zxing gave no points to crop to. */
    private fun crop(bitmap: Bitmap, result: Result): ByteArray {
        val rect = barcodeCrop(result.resultPoints?.toList(), bitmap.width, bitmap.height)
            ?: return ByteArray(0)
        val cut = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width, rect.height)
        return ByteArrayOutputStream().use { out ->
            cut.compress(Bitmap.CompressFormat.PNG, 100, out)
            if (cut !== bitmap) cut.recycle()
            out.toByteArray()
        }
    }
}
