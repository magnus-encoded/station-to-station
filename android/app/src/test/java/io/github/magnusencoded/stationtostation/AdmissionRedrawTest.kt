package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.Admission
import io.github.magnusencoded.stationtostation.data.AdmissionShape
import io.github.magnusencoded.stationtostation.data.ParsedTicket
import io.github.magnusencoded.stationtostation.data.StoredAdmission
import io.github.magnusencoded.stationtostation.data.admissionDrawing
import io.github.magnusencoded.stationtostation.data.admissionFormat
import io.github.magnusencoded.stationtostation.data.checkedForRedraw
import io.github.magnusencoded.stationtostation.data.doorDrawing
import io.github.magnusencoded.stationtostation.data.redrawsExactly
import io.github.magnusencoded.stationtostation.data.ticketSymbology
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An Admission redrawn in its own symbology and read back (#441, stories 2, 3, 9, 29):
 * zxing draws exactly what the Room will show, zxing decodes it, and the payload must
 * come back byte for byte. iOS's `AdmissionRedrawTests` asserts the same payloads
 * through CoreImage and Vision.
 *
 * What it cannot say: that a venue's scanner accepts the redraw. Every payload here is
 * synthetic.
 */
class AdmissionRedrawTest {

    /** A 24-digit number in the shape of an Eventim ticket's Code 128, made up. */
    private val eventimShaped = "000310038500100020019999"

    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)

    private fun assertRoundTrips(symbology: String, text: String) {
        assertNotNull("$symbology draws", admissionDrawing(symbology, bytes(text)))
        assertTrue("$symbology reads back as $text", redrawsExactly(symbology, bytes(text)))
    }

    // --- The round trip, per symbology ---

    @Test
    fun anAsciiQrReadsBack() = assertRoundTrips("qr", "SYNTHETIC-QR-K8TZC46G7")

    @Test
    fun aNonAsciiQrReadsBack() {
        // #534's case: without the UTF-8 ECI, zxing turned "–" into "?" and read "ÆØÅ" back as Shift_JIS.
        assertRoundTrips("qr", "Dumdumboys – ÆØÅ")
        assertRoundTrips("qr", "Kjøpt – 🎫 1/2")
    }

    @Test
    fun anEventimShapedCode128ReadsBack() = assertRoundTrips("code128", eventimShaped)

    @Test
    fun anAlphanumericCode128ReadsBack() = assertRoundTrips("code128", "SYNTH-128-ABC")

    @Test
    fun anAztecReadsBack() {
        assertRoundTrips("aztec", "SYNTHETIC-AZTEC-0042")
        assertRoundTrips("aztec", "Parkteatret Scene ÆØÅ")
    }

    @Test
    fun aPdf417ReadsBack() {
        assertRoundTrips("pdf417", "SYNTHETIC-PDF417-0042")
        assertRoundTrips("pdf417", "Parkteatret Scene ÆØÅ")
    }

    @Test
    fun aDataMatrixReadsBack() {
        // Drawn here; CoreImage has no Data Matrix generator, so on iOS this is
        // "can't be shown" — the parity gap, pinned on both sides.
        assertRoundTrips("datamatrix", "SYNTHETIC-DM-0042")
    }

    @Test
    fun retailFormatsReadBackWhenTheirDigitsAreValid() {
        assertRoundTrips("ean13", "5901234123457")
        assertRoundTrips("ean8", "96385074")
    }

    // --- What cannot be drawn says so ---

    @Test
    fun anEanWithABadCheckDigitIsNotRedrawable() {
        assertFalse(redrawsExactly("ean13", bytes("5901234123458")))
    }

    @Test
    fun aCode128OfTextItCannotCarryIsNotRedrawable() {
        assertFalse(redrawsExactly("code128", bytes("Æ–Ø")))
    }

    @Test
    fun anEmptyOrBinaryPayloadDrawsNothing() {
        assertNull(admissionDrawing("qr", ByteArray(0)))
        assertFalse(redrawsExactly("qr", ByteArray(0)))
        // Not UTF-8: no text to redraw, and byte segments are not carried yet (#441).
        assertNull(admissionDrawing("qr", byteArrayOf(0x00, 0xFF.toByte(), 0xFE.toByte())))
        assertFalse(redrawsExactly("qr", byteArrayOf(0x00, 0xFF.toByte(), 0xFE.toByte())))
    }

    @Test
    fun aSymbologyWithNoWriterIsNotRedrawable() {
        assertFalse(redrawsExactly("maxicode", bytes("SYNTH")))
        assertFalse(redrawsExactly("nonsense", bytes("SYNTH")))
    }

    @Test
    fun fixturesNamesMapToZxingFormatsAndBack() {
        for (name in listOf("qr", "code128", "aztec", "pdf417", "datamatrix", "ean13", "ean8", "upca", "upce")) {
            val format = admissionFormat(name)
            assertNotNull(name, format)
            assertEquals(name, ticketSymbology(format!!))
        }
    }

    // --- Shape (story 9) ---

    @Test
    fun aMatrixCodeIsSquareWithItsQuietZone() {
        for (symbology in listOf("qr", "aztec", "datamatrix")) {
            val drawing = admissionDrawing(symbology, bytes("SYNTH-0042"))!!
            assertEquals(AdmissionShape.MATRIX, drawing.shape)
            assertEquals(symbology, drawing.width, drawing.height)
            for (i in 0 until drawing.width) {
                for (ring in 0 until 4) {
                    assertFalse(drawing.modules[i, ring])
                    assertFalse(drawing.modules[ring, i])
                    assertFalse(drawing.modules[i, drawing.height - 1 - ring])
                    assertFalse(drawing.modules[drawing.width - 1 - ring, i])
                }
            }
        }
    }

    @Test
    fun aPdf417KeepsItsWideShape() {
        val drawing = admissionDrawing("pdf417", bytes("SYNTHETIC-PDF417-0042"))!!
        assertEquals(AdmissionShape.MATRIX, drawing.shape)
        assertTrue(drawing.width > drawing.height)
    }

    @Test
    fun aLinearCodeIsAStripWithItsQuietZone() {
        val strip = admissionDrawing("code128", bytes(eventimShaped))!!
        assertEquals(AdmissionShape.LINEAR, strip.shape)
        assertEquals(1, strip.height)
        for (x in 0 until 11) {
            assertFalse(strip.modules[x, 0])
            assertFalse(strip.modules[strip.width - 1 - x, 0])
        }
        assertTrue("the start pattern begins at the quiet zone's edge", strip.modules[11, 0])
        // Story 9's module width is set by how many modules the strip needs: zxing packs
        // digits two to a symbol (Code 128 set C), so 24 digits are 12 symbols, plus
        // start, check and stop, plus 11 quiet modules each side.
        assertEquals(11 + (12 + 2) * 11 + 13 + 11, strip.width)
    }

    @Test
    fun scalingIsAWholeNumberOfPixelsPerModule() {
        val strip = admissionDrawing("code128", bytes(eventimShaped))!!
        val wide = strip.scaled(modulePx = 3, linearHeightPx = 90)
        assertEquals(strip.width * 3, wide.width)
        assertEquals(90, wide.height)
        // Every module's pixels agree with it, on every row.
        for (x in 0 until wide.width) {
            for (y in 0 until wide.height) assertEquals(strip.modules[x / 3, 0], wide[x, y])
        }
        val qr = admissionDrawing("qr", bytes("SYNTH"))!!
        val square = qr.scaled(modulePx = 5)
        assertEquals(qr.width * 5, square.width)
        assertEquals(qr.height * 5, square.height)
    }

    // --- Checking a ticket, and the Room's answer ---

    @Test
    fun checkingATicketAnswersEveryAdmission() {
        val ticket = ParsedTicket(
            admissions = listOf(
                Admission(bytes("SYNTHETIC-QR-1"), "qr"),
                Admission(bytes(eventimShaped), "code128"),
                Admission(bytes("SYNTH"), "maxicode"),
            ),
        )

        val checked = ticket.checkedForRedraw()

        assertEquals(listOf(true, true, false), checked.admissions.map { it.redrawable })
        assertFalse(checked.redrawsEveryAdmission)
        assertTrue(checked.copy(admissions = checked.admissions.take(2)).redrawsEveryAdmission)
    }

    @Test
    fun theRoomDrawsWhatReadsBackAndNothingElse() {
        val drawn = doorDrawing(StoredAdmission.of(Admission(bytes(eventimShaped), "code128")))
        assertNotNull(drawn)
        assertEquals(AdmissionShape.LINEAR, drawn!!.shape)
        assertNull(doorDrawing(StoredAdmission.of(Admission(bytes("5901234123458"), "ean13"))))
        assertNull(doorDrawing(StoredAdmission(payload = "not base64 !", symbology = "qr")))
    }
}
