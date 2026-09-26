package io.github.magnusencoded.stationtostation.data

import android.graphics.Bitmap

// The PDF extractor's shape, with no renderer in it (#526) — the twin of
// `PdfTicketExtractor.swift`.
//
// Bitmap appears here only as a type. The readers that call PdfRenderer, ML Kit and
// zxing live in TicketExtraction.kt; what is left here is the part worth asserting —
// that every reader runs on every page and each leaves one reading behind — and it is
// asserted on the JVM with fake pages and fake readers (PdfTicketExtractorTest), since
// the real renderers cannot be run without a device.

/**
 * One page of a PDF, as the readers see it. Open only for the one iteration of the
 * extractor's page loop that asked for it: PdfRenderer allows a single open page.
 *
 * **Rendered at most once.** An implementation draws the page on the first call to
 * [rendered] and hands back the same pixels after that, so the OCR reader and the
 * barcode locator share one rasterization; [close] lets them go.
 */
interface PdfPage : AutoCloseable {
    /** The page's own text layer, as the PDF carries it. Null or blank for a scan, and below API 35. */
    val textLayer: String?
    fun rendered(): Bitmap?
    override fun close() {}
}

/** An opened PDF. Pages are asked for one at a time, so each page's pixels can be let go before the next is drawn. */
interface PdfPages : AutoCloseable {
    val count: Int
    fun page(index: Int): PdfPage?
    override fun close() {}
}

/** One way of reading a page's words. */
interface PdfTextReader {
    val origin: TicketReading.Origin
    suspend fun lines(page: PdfPage): List<String>
}

/** Finds the barcodes on a page — every one it can, in the order found. Always visual, whatever the text readers did. */
interface BarcodeLocator {
    suspend fun locate(page: PdfPage, index: Int): List<TicketBarcode>
}

/**
 * A PDF's evidence: every reader over every page, and every barcode on every page, up
 * to [pageLimit].
 *
 * **Both readers always run, on every page.** Comparing what they found is
 * [parseTicketFields]'s job, not this one's, so nothing here stops early or picks a
 * winner. A reader that found nothing — the text layer of a scan, or of any PDF below
 * API 35 — leaves no reading behind, which is how the parser knows a scan from a PDF
 * that agreed with itself.
 *
 * Barcodes are kept from every page, each (symbology, payload) once
 * ([distinctTicketBarcodes]); which one a ticket carries is the parser's call.
 *
 * [open] returns null for anything that is not a readable PDF — a file that is gone, a
 * permission that was revoked, a password — which is no evidence at all: the honest
 * "couldn't read this" (#408, story 9), never a crash.
 */
class PdfTicketExtractor<in S>(
    private val open: (S) -> PdfPages?,
    private val readers: List<PdfTextReader>,
    private val barcodes: BarcodeLocator,
    /**
     * Pages past the fifth are not read: a ticket is one or two pages, and an unrelated
     * PDF shared by mistake should not spend minutes rasterizing a program listing
     * before failing to find a QR. iOS reads three, which is a plumbing difference and
     * not a rule.
     */
    private val pageLimit: Int = MAX_PAGES,
) : TicketExtractor<S> {

    override suspend fun extract(source: S): TicketEvidence {
        val pages = open(source) ?: return TicketEvidence(readings = emptyList())
        return pages.use { pdf ->
            val lines = List(readers.size) { mutableListOf<String>() }
            val found = mutableListOf<TicketBarcode>()
            for (index in 0 until minOf(pdf.count, pageLimit)) {
                // Held for this iteration only: the page's cached pixels go with it.
                val page = pdf.page(index) ?: continue
                page.use {
                    readers.forEachIndexed { slot, reader ->
                        lines[slot] += reader.lines(page).filter { line -> line.isNotBlank() }
                    }
                    found += barcodes.locate(page, index)
                }
            }
            TicketEvidence(
                readings = readers.zip(lines)
                    .filter { (_, read) -> read.isNotEmpty() }
                    .map { (reader, read) -> TicketReading(reader.origin, read) },
                barcodes = distinctTicketBarcodes(found),
            )
        }
    }

    companion object {
        const val MAX_PAGES = 5
    }
}

/**
 * The PDF's own text, which costs nothing to read next to OCR (under 5 ms a PDF on the
 * probe's Pixel, against 0.3–5 s of OCR).
 *
 * A text object can hold several lines, so it is split on `\n`. Nothing else is done to
 * it: a trailing `\r` is left for [parseTicketFields]'s tidying, which is where
 * `fixtures/ticket/` expects it. A content stream's order is whatever the generator
 * wrote, not always down the page; that is why the parser compares readings by their
 * lines and never by position.
 */
object TextLayerReader : PdfTextReader {
    override val origin = TicketReading.Origin.TEXT_LAYER

    override suspend fun lines(page: PdfPage): List<String> = page.textLayer.orEmpty().split('\n')
}
