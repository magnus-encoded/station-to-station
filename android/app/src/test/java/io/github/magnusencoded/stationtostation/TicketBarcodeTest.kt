package io.github.magnusencoded.stationtostation

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.Result
import com.google.zxing.ResultPoint
import com.google.zxing.common.BitMatrix
import io.github.magnusencoded.stationtostation.data.PixelRect
import io.github.magnusencoded.stationtostation.data.StoredAdmission
import io.github.magnusencoded.stationtostation.data.TicketEvidence
import io.github.magnusencoded.stationtostation.data.admissionDrawing
import io.github.magnusencoded.stationtostation.data.admissionText
import io.github.magnusencoded.stationtostation.data.barcodeCrop
import io.github.magnusencoded.stationtostation.data.decodeTicketBarcode
import io.github.magnusencoded.stationtostation.data.decodeTicketBarcodes
import io.github.magnusencoded.stationtostation.data.distinctTicketBarcodes
import io.github.magnusencoded.stationtostation.data.parseTicketFields
import io.github.magnusencoded.stationtostation.data.ticketSymbology
import io.github.magnusencoded.stationtostation.data.toTicketBarcode
import io.github.magnusencoded.stationtostation.data.zxingFormatName
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The barcode half of a ticket import (TicketBarcode.kt), round-tripped through real
 * zxing on the JVM: encode, decode with the same hinted reader the extractor uses,
 * keep what [parseTicketFields] keeps, read it back as [admissionText] does, and
 * compare **text** — what a scanner at the door reads out. This is the assertion that
 * would have caught #441's `rawBytes` fault; the probe (2026-09-25) found it on device
 * instead.
 *
 * What it cannot say: that a venue's scanner accepts the redraw, or anything about a
 * rasterized PDF page. Those stay manual, on a device, with a real ticket. Every
 * payload here is synthetic.
 */
class TicketBarcodeTest {

    /** A matrix as the pixels `Bitmap.getPixels` would hand the extractor. */
    private fun pixels(matrix: BitMatrix): IntArray {
        val width = matrix.width
        return IntArray(width * matrix.height) { i ->
            if (matrix[i % width, i / width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }

    private fun decode(matrix: BitMatrix): Result? = decodeTicketBarcode(matrix.width, matrix.height, pixels(matrix))

    private fun decodeAll(matrix: BitMatrix): List<Result> =
        decodeTicketBarcodes(matrix.width, matrix.height, pixels(matrix))

    /** Small symbols placed on a big white page — a ticket PDF, not a camera frame. */
    private fun onAPage(
        vararg symbols: Pair<BitMatrix, Pair<Int, Int>>,
        pageWidth: Int = 1654,
        pageHeight: Int = 2339,
    ): BitMatrix {
        val page = BitMatrix(pageWidth, pageHeight)
        for ((symbol, at) in symbols) {
            val (left, top) = at
            for (y in 0 until symbol.height) {
                for (x in 0 until symbol.width) {
                    if (symbol[x, y]) page.set(left + x, top + y)
                }
            }
        }
        return page
    }

    private fun onAPage(symbol: BitMatrix): BitMatrix = onAPage(symbol to (1654 - symbol.width - 120 to 160))

    /**
     * What is stored for these results, as the extractor hands them over: the first QR
     * Admission [parseTicketFields] yields, through [StoredAdmission]'s base64 and back
     * — the bytes the Room redraws.
     */
    private fun stored(vararg results: Result): ByteArray? =
        parseTicketFields(TicketEvidence(readings = emptyList(), barcodes = results.map { it.toTicketBarcode() }))
            .admissions.firstOrNull { it.symbology == "qr" }
            ?.let(StoredAdmission::of)?.payloadBytes

    /** A QR as the Room draws it (AdmissionRedraw.kt), [modulePx] per module. */
    private fun qr(text: String, modulePx: Int = 6): BitMatrix =
        admissionDrawing("qr", text.toByteArray(Charsets.UTF_8))!!.scaled(modulePx)

    private fun roundTrip(payload: String): String? {
        val decoded = decode(onAPage(qr(payload)))
        assertNotNull("nothing decoded for $payload", decoded)
        val stored = stored(decoded!!)
        assertNotNull("nothing stored for $payload", stored)
        // What the day-of view redraws, decoded again: the text has to survive twice.
        val redrawn = decode(admissionDrawing("qr", stored!!)!!.scaled(8))
        return redrawn?.text
    }

    @Test
    fun aTicketQrsTextSurvivesDecodeStoreAndRedraw() {
        // Shapes seen on real tickets: a short code, a long digit string, a URL.
        for (payload in listOf("SYNTH46G7", "000000000000000000000001", "https://tickets.example/t?id=SYNTH46G7&n=1")) {
            assertEquals(payload, roundTrip(payload))
        }
    }

    @Test
    fun nonAsciiTextSurvivesTheRoundTripToo() {
        // zxing's writer defaults to ISO-8859-1 with no ECI, and its reader guessed
        // "ÆØÅ" back as Shift_JIS; admissionDrawing writes UTF-8 with an ECI instead.
        for (payload in listOf("Parkteatret Scene ÆØÅ", "Kjøpt – 🎫 1/2")) {
            assertEquals(payload, roundTrip(payload))
        }
    }

    @Test
    fun theStoredBytesAreTheDecodedTextAsUtf8NotZxingsRawBytes() {
        val decoded = decode(qr("SYNTH46G7"))!!

        val stored = stored(decoded)!!

        assertArrayEquals("SYNTH46G7".toByteArray(Charsets.UTF_8), stored)
        // rawBytes are the symbol's codewords (mode, length, padding) — the fault.
        assertEquals(false, decoded.rawBytes.contentEquals(stored))
    }

    @Test
    fun aCode128IsSeenAndKeptAsAnAdmissionInItsOwnSymbology() {
        // Eventim's real barcode shape: 24 digits, Code 128. Found with the same
        // hinted reader, and kept as what it is (#441) — never as a QR.
        val strip = MultiFormatWriter().encode("000000000000000000000001", BarcodeFormat.CODE_128, 600, 120)
        val decoded = decode(onAPage(strip))

        assertEquals(BarcodeFormat.CODE_128, decoded?.barcodeFormat)
        val parsed = parseTicketFields(TicketEvidence(emptyList(), listOf(decoded!!.toTicketBarcode())))
        val admission = parsed.admissions.single()
        assertEquals("code128", admission.symbology)
        assertEquals("000000000000000000000001", admission.payload.toString(Charsets.UTF_8))
        // What the confirm prompt and the Room say, as the prompt has since #534.
        assertEquals("CODE_128", zxingFormatName(admission.symbology))
    }

    @Test
    fun everyQrOnAPageIsFoundAndTheSinglePassHitStaysFirst() {
        // A Billettservice ticket prints three QRs; the single pass alone kept one.
        val page = onAPage(
            qr("SYNTHETIC-QR-1") to (120 to 160),
            qr("SYNTHETIC-QR-2") to (1200 to 1400),
        )

        val results = decodeAll(page)
        val single = decode(page)!!

        assertEquals(setOf("SYNTHETIC-QR-1", "SYNTHETIC-QR-2"), results.map { it.text }.toSet())
        assertEquals(single.text, results.first().text)
        assertEquals(setOf(BarcodeFormat.QR_CODE), results.map { it.barcodeFormat }.toSet())
    }

    @Test
    fun aPageWithNothingOnItHasNoBarcodes() {
        assertEquals(emptyList<Result>(), decodeAll(BitMatrix(400, 400)))
        assertNull(decode(BitMatrix(400, 400)))
    }

    @Test
    fun aBarcodeRepeatedOnEveryPageIsOneBarcode() {
        val qr = Result("SYNTHETIC-QR-1", null, null, BarcodeFormat.QR_CODE)
        val other = Result("SYNTHETIC-QR-2", null, null, BarcodeFormat.QR_CODE)
        // Same text, another symbology: a different barcode, kept.
        val strip = Result("SYNTHETIC-QR-1", null, null, BarcodeFormat.CODE_128)

        val kept = distinctTicketBarcodes(
            listOf(
                qr.toTicketBarcode(page = 0),
                qr.toTicketBarcode(page = 1),
                other.toTicketBarcode(page = 1),
                strip.toTicketBarcode(page = 2),
                qr.toTicketBarcode(page = 2),
            ),
        )

        assertEquals(listOf(0, 1, 2), kept.map { it.page })
        assertEquals(listOf("qr", "qr", "code128"), kept.map { it.symbology })
    }

    @Test
    fun symbologiesAreTheSharedFixturesNames() {
        // fixtures/ticket/README.md: the names both platforms write.
        val names = mapOf(
            BarcodeFormat.QR_CODE to "qr",
            BarcodeFormat.CODE_128 to "code128",
            BarcodeFormat.EAN_13 to "ean13",
            BarcodeFormat.EAN_8 to "ean8",
            BarcodeFormat.UPC_E to "upce",
            BarcodeFormat.AZTEC to "aztec",
            BarcodeFormat.PDF_417 to "pdf417",
            BarcodeFormat.DATA_MATRIX to "datamatrix",
        )
        for ((format, name) in names) {
            assertEquals(name, ticketSymbology(format))
            assertEquals(format.name, zxingFormatName(name))
        }
    }

    @Test
    fun theCropCarriesTheQuietZoneAndStaysInsideTheImage() {
        // A 21-module QR's finder centres sit 14 modules apart; 0.6 of that on every
        // side is past the symbol's corner and its four-module quiet zone.
        val centres = listOf(ResultPoint(100f, 200f), ResultPoint(150f, 200f), ResultPoint(100f, 250f))
        assertEquals(PixelRect(70, 170, 180, 280), barcodeCrop(centres, 1000, 1000))

        // At the page's edge the crop stops at the edge.
        assertEquals(PixelRect(0, 0, 40, 40), barcodeCrop(listOf(ResultPoint(5f, 5f), ResultPoint(30f, 30f)), 40, 40))

        assertNull(barcodeCrop(null, 1000, 1000))
        assertNull(barcodeCrop(emptyList(), 1000, 1000))
    }

    @Test
    fun aValueStoredBeforeThisFixThatIsNotUtf8DrawsNothingRatherThanCrashing() {
        // A pre-fix `rawBytes` value: QR codewords, which are rarely valid UTF-8.
        val oldRawBytes = byteArrayOf(0x40, 0x94.toByte(), 0xB3.toByte(), 0x85.toByte(), 0xFF.toByte(), 0x11)

        assertNull(admissionText(oldRawBytes))
        assertEquals("SYNTH46G7", admissionText("SYNTH46G7".toByteArray(Charsets.UTF_8)))
    }
}
