package io.github.magnusencoded.stationtostation

import android.graphics.Bitmap
import io.github.magnusencoded.stationtostation.data.BarcodeLocator
import io.github.magnusencoded.stationtostation.data.PdfPage
import io.github.magnusencoded.stationtostation.data.PdfPages
import io.github.magnusencoded.stationtostation.data.PdfTextReader
import io.github.magnusencoded.stationtostation.data.PdfTicketExtractor
import io.github.magnusencoded.stationtostation.data.TextLayerReader
import io.github.magnusencoded.stationtostation.data.TicketBarcode
import io.github.magnusencoded.stationtostation.data.TicketEvidence
import io.github.magnusencoded.stationtostation.data.TicketReading
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PdfTicketExtractor]'s shape (#526), with fake pages and fake readers — the twin of
 * iOS's `PdfTicketExtractorTests`. The real PdfRenderer, ML Kit and zxing readers stay
 * untested, as they always have: they need a device.
 */
class PdfTicketExtractorTest {

    private class FakePage(override val textLayer: String?) : PdfPage {
        var closed = false
        override fun rendered(): Bitmap? = null
        override fun close() {
            closed = true
        }
    }

    private class FakePages(val pages: List<FakePage>) : PdfPages {
        var closed = false
        override val count: Int get() = pages.size
        override fun page(index: Int): PdfPage = pages[index]
        override fun close() {
            closed = true
        }
    }

    /** Answers from the page, and remembers every page it was handed — so a test can tell which reader ran on which page. */
    private class RecordingReader(
        override val origin: TicketReading.Origin,
        val answer: (FakePage) -> List<String>,
    ) : PdfTextReader {
        val seen = mutableListOf<PdfPage>()
        override suspend fun lines(page: PdfPage): List<String> {
            seen += page
            return answer(page as FakePage)
        }
    }

    /** Finds [found] on the pages it names, and counts the pages it was asked about. */
    private class RecordingLocator(val found: Map<Int, List<TicketBarcode>> = emptyMap()) : BarcodeLocator {
        val asked = mutableListOf<Int>()
        override suspend fun locate(page: PdfPage, index: Int): List<TicketBarcode> {
            asked += index
            return found[index].orEmpty()
        }
    }

    private fun qr(payload: String, page: Int) =
        TicketBarcode(image = byteArrayOf(0x89.toByte()), payload = payload.toByteArray(), symbology = "qr", page = page)

    private fun extractor(
        pages: FakePages,
        readers: List<PdfTextReader>,
        locator: BarcodeLocator = RecordingLocator(),
    ) = PdfTicketExtractor<Unit>(open = { pages }, readers = readers, barcodes = locator)

    @Test
    fun bothReadersRunOnEveryPage() = runBlocking {
        // Every reader on every page, and one reading per reader, in page order.
        val pages = FakePages(listOf(FakePage("Static Halo"), FakePage("Rockefeller")))
        val text = RecordingReader(TicketReading.Origin.TEXT_LAYER) { listOf(it.textLayer.orEmpty()) }
        val ocr = RecordingReader(TicketReading.Origin.OCR) { listOf("ocr " + it.textLayer.orEmpty()) }

        val evidence = extractor(pages, listOf(text, ocr)).extract(Unit)

        assertEquals(pages.pages, text.seen)
        assertEquals(pages.pages, ocr.seen)
        assertEquals(
            listOf(
                TicketReading(TicketReading.Origin.TEXT_LAYER, listOf("Static Halo", "Rockefeller")),
                TicketReading(TicketReading.Origin.OCR, listOf("ocr Static Halo", "ocr Rockefeller")),
            ),
            evidence.readings,
        )
    }

    @Test
    fun aReaderThatFoundNothingLeavesNoReading() = runBlocking {
        // A scan's text layer is empty, as is every text layer below API 35. It leaves
        // no reading behind, rather than an empty one the parser would have to ignore.
        val text = RecordingReader(TicketReading.Origin.TEXT_LAYER) { listOf("", "  ") }
        val ocr = RecordingReader(TicketReading.Origin.OCR) { listOf("Static Halo") }

        val evidence = extractor(FakePages(listOf(FakePage(null))), listOf(text, ocr)).extract(Unit)

        assertEquals(listOf(TicketReading(TicketReading.Origin.OCR, listOf("Static Halo"))), evidence.readings)
    }

    @Test
    fun everyPagesBarcodesAreKeptOnceEach() = runBlocking {
        // A barcode on page one does not stop the readers, or the locator, on page two;
        // the same QR printed on both pages is one barcode.
        val pages = FakePages(listOf(FakePage("a"), FakePage("b")))
        val ocr = RecordingReader(TicketReading.Origin.OCR) { listOf(it.textLayer.orEmpty()) }
        val locator = RecordingLocator(
            mapOf(
                0 to listOf(qr("SYNTHETIC-QR-1", 0)),
                1 to listOf(qr("SYNTHETIC-QR-1", 1), qr("SYNTHETIC-QR-2", 1)),
            ),
        )

        val evidence = extractor(pages, listOf(ocr), locator).extract(Unit)

        assertEquals(2, ocr.seen.size)
        assertEquals(listOf(0, 1), locator.asked)
        assertEquals(listOf(qr("SYNTHETIC-QR-1", 0), qr("SYNTHETIC-QR-2", 1)), evidence.barcodes)
    }

    @Test
    fun pagesPastTheLimitAreNotRead() = runBlocking {
        val pages = FakePages((0 until 7).map { FakePage("page $it") })
        val ocr = RecordingReader(TicketReading.Origin.OCR) { listOf(it.textLayer.orEmpty()) }
        val locator = RecordingLocator()

        extractor(pages, listOf(ocr), locator).extract(Unit)

        assertEquals(PdfTicketExtractor.MAX_PAGES, ocr.seen.size)
        assertEquals(PdfTicketExtractor.MAX_PAGES, locator.asked.size)
    }

    @Test
    fun everyPageAndTheDocumentAreClosed() = runBlocking {
        // PdfRenderer allows one open page at a time, and a page holds its pixels.
        val pages = FakePages(listOf(FakePage("a"), FakePage("b")))
        val ocr = RecordingReader(TicketReading.Origin.OCR) { listOf(it.textLayer.orEmpty()) }

        extractor(pages, listOf(ocr)).extract(Unit)

        assertTrue(pages.pages.all { it.closed })
        assertTrue(pages.closed)
    }

    @Test
    fun aSourceThatWillNotOpenIsNoEvidence() = runBlocking {
        // A deleted file, a revoked grant, a password: the "couldn't read this" prompt.
        val ocr = RecordingReader(TicketReading.Origin.OCR) { listOf("x") }
        val extractor = PdfTicketExtractor<Unit>(open = { null }, readers = listOf(ocr), barcodes = RecordingLocator())

        val evidence = extractor.extract(Unit)

        assertEquals(TicketEvidence(readings = emptyList()), evidence)
        assertTrue(ocr.seen.isEmpty())
    }

    @Test
    fun theTextLayerReaderSplitsLinesAndLeavesTheRestToTheParser() = runBlocking {
        // One text object can hold several lines; a trailing \r is the parser's to tidy.
        val lines = TextLayerReader.lines(FakePage("Your ticket\r\nStatic Halo\n24-09-2026"))

        assertEquals(listOf("Your ticket\r", "Static Halo", "24-09-2026"), lines)
        assertEquals(emptyList<String>(), TextLayerReader.lines(FakePage(null)).filter { it.isNotBlank() })
    }
}
