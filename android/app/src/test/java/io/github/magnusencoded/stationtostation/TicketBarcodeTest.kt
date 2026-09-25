package io.github.magnusencoded.stationtostation

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.Result
import com.google.zxing.common.BitMatrix
import io.github.magnusencoded.stationtostation.data.chooseTicketBarcode
import io.github.magnusencoded.stationtostation.data.decodeTicketBarcode
import io.github.magnusencoded.stationtostation.data.ticketQrMatrix
import io.github.magnusencoded.stationtostation.data.ticketQrText
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The barcode half of a ticket import (TicketBarcode.kt), round-tripped through real
 * zxing on the JVM: encode, decode with the same hinted reader `extractTicket` uses,
 * keep what [chooseTicketBarcode] keeps, read it back as [ticketQrText] does, and
 * compare **text** — what a scanner at the door reads out. This is the assertion that
 * would have caught #441's `rawBytes` fault; the probe (2026-09-25) found it on device
 * instead.
 *
 * What it cannot say: that a venue's scanner accepts the redraw, or anything about a
 * rasterized PDF page. Those stay manual, on a device, with a real ticket.
 */
class TicketBarcodeTest {

    /** A matrix as the pixels `Bitmap.getPixels` would hand the extractor. */
    private fun decode(matrix: BitMatrix): Result? {
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height) { i ->
            if (matrix[i % width, i / width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        return decodeTicketBarcode(width, height, pixels)
    }

    /** A small symbol in the corner of a big white page — a ticket PDF, not a camera frame. */
    private fun onAPage(symbol: BitMatrix, pageWidth: Int = 1654, pageHeight: Int = 2339): BitMatrix {
        val page = BitMatrix(pageWidth, pageHeight)
        val left = pageWidth - symbol.width - 120
        val top = 160
        for (y in 0 until symbol.height) {
            for (x in 0 until symbol.width) {
                if (symbol[x, y]) page.set(left + x, top + y)
            }
        }
        return page
    }

    private fun roundTrip(payload: String): String? {
        val decoded = decode(onAPage(ticketQrMatrix(payload, 240)))
        assertNotNull("nothing decoded for $payload", decoded)
        val stored = chooseTicketBarcode(listOf(decoded!!)).qrBytes
        assertNotNull("nothing stored for $payload", stored)
        // What the day-of view redraws, decoded again: the text has to survive twice.
        val redrawn = decode(ticketQrMatrix(ticketQrText(stored!!)!!, 480))
        return redrawn?.text
    }

    @Test
    fun aTicketQrsTextSurvivesDecodeStoreAndRedraw() {
        // Shapes seen on real tickets: a short code, a long digit string, a URL.
        for (payload in listOf("TESTQRAA1", "123456789012345678901234", "https://tickets.example/t?id=TESTQRAA1&n=1")) {
            assertEquals(payload, roundTrip(payload))
        }
    }

    @Test
    fun nonAsciiTextSurvivesTheRoundTripToo() {
        // zxing's writer defaults to ISO-8859-1 with no ECI, and its reader guessed
        // "ÆØÅ" back as Shift_JIS; ticketQrMatrix writes UTF-8 with an ECI instead.
        for (payload in listOf("Parkteatret Scene ÆØÅ", "Kjøpt – 🎫 1/2")) {
            assertEquals(payload, roundTrip(payload))
        }
    }

    @Test
    fun theStoredBytesAreTheDecodedTextAsUtf8NotZxingsRawBytes() {
        val decoded = decode(ticketQrMatrix("TESTQRAA1", 240))!!

        val stored = chooseTicketBarcode(listOf(decoded)).qrBytes!!

        assertArrayEquals("TESTQRAA1".toByteArray(Charsets.UTF_8), stored)
        // rawBytes are the symbol's codewords (mode, length, padding) — the fault.
        assertEquals(false, decoded.rawBytes.contentEquals(stored))
    }

    @Test
    fun aCode128IsSeenButFlaggedUnsupportedAndNotStored() {
        // Eventim's real barcode shape: 24 digits, Code 128. Found with the same
        // hinted reader, then refused a place in a field that can only redraw a QR.
        val strip = MultiFormatWriter().encode("123456789012345678901234", BarcodeFormat.CODE_128, 600, 120)
        val decoded = decode(onAPage(strip))

        assertEquals(BarcodeFormat.CODE_128, decoded?.barcodeFormat)
        val choice = chooseTicketBarcode(listOf(decoded!!))
        assertNull(choice.qrBytes)
        assertEquals("CODE_128", choice.unsupportedFormat)
    }

    @Test
    fun theFirstQrIsKeptAndAnEarlierNonQrIsStillReported() {
        val code128 = Result("123456789012345678901234", null, null, BarcodeFormat.CODE_128)
        val first = Result("TESTQRAA1", null, null, BarcodeFormat.QR_CODE)
        val second = Result("TESTQRBB2", null, null, BarcodeFormat.QR_CODE)

        val choice = chooseTicketBarcode(listOf(code128, first, second))

        assertArrayEquals("TESTQRAA1".toByteArray(Charsets.UTF_8), choice.qrBytes)
        assertEquals("CODE_128", choice.unsupportedFormat)
    }

    @Test
    fun noBarcodeAtAllIsNeitherStoredNorFlagged() {
        val choice = chooseTicketBarcode(emptyList())

        assertNull(choice.qrBytes)
        assertNull(choice.unsupportedFormat)
        assertNull(decode(BitMatrix(400, 400)))
    }

    @Test
    fun aValueStoredBeforeThisFixThatIsNotUtf8DrawsNothingRatherThanCrashing() {
        // A pre-fix `rawBytes` value: QR codewords, which are rarely valid UTF-8.
        val oldRawBytes = byteArrayOf(0x40, 0x94.toByte(), 0xB3.toByte(), 0x85.toByte(), 0xFF.toByte(), 0x11)

        assertNull(ticketQrText(oldRawBytes))
        assertEquals("TESTQRAA1", ticketQrText("TESTQRAA1".toByteArray(Charsets.UTF_8)))
    }
}
