package io.github.magnusencoded.stationtostation

import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Debug
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import io.github.magnusencoded.stationtostation.data.*
import io.github.magnusencoded.stationtostation.ui.qrBitmap
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Explicit opt-in, local-only measurement. Never logs ticket text or credentials.
 * adb shell am instrument -w -e class ...TicketEvidenceProbe -e ticketProbe true ...
 * Input/output: targetContext.getExternalFilesDir(null)/probe/{inputs,raw}.
 */
@RunWith(AndroidJUnit4::class)
class TicketEvidenceProbe {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val root get() = File(context.getExternalFilesDir(null), "probe")
    private val harder = mapOf(DecodeHintType.TRY_HARDER to true)
    private fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().apply {
        pairs.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) }
    }
    private fun arr(values: Iterable<*>) = JSONArray().apply { values.forEach { put(it) } }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun hash(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun bytes(bytes: ByteArray?): Any = bytes?.let {
        val utf8 = runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(it)) }.isSuccess
        obj("sha256" to hash(it), "length" to it.size, "isUtf8" to utf8,
            "charset" to if (utf8) "UTF-8-compatible; encoding not proven" else "unknown",
            "hex" to hex(it))
    } ?: JSONObject.NULL
    private fun bounds(r: Rect?) = r?.let { arr(listOf(it.left, it.top, it.right, it.bottom)) }
    private fun bounds(r: RectF) = arr(listOf(r.left, r.top, r.right, r.bottom))
    private fun binary(bitmap: Bitmap): BinaryBitmap {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))
    }
    private fun code(text: String, format: BarcodeFormat, charset: String? = null): Bitmap {
        val hints = mutableMapOf<EncodeHintType, Any>(EncodeHintType.MARGIN to 12)
        charset?.let { hints[EncodeHintType.CHARACTER_SET] = it }
        val matrix = MultiFormatWriter().encode(text, format, 800, if (format == BarcodeFormat.CODE_128) 240 else 800, hints)
        return Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
            val pixels = IntArray(matrix.width * matrix.height) { n ->
                if (matrix[n % matrix.width, n / matrix.width]) Color.BLACK else Color.WHITE
            }
            setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
        }
    }
    private fun segments(r: Result): List<ByteArray> =
        (r.resultMetadata?.get(ResultMetadataType.BYTE_SEGMENTS) as? Iterable<*>)?.mapNotNull { it as? ByteArray } ?: emptyList()
    private fun segmentPayload(r: Result): ByteArray? = segments(r).takeIf { it.isNotEmpty() }?.fold(byteArrayOf()) { a, b -> a + b }
    private fun roundTrip(original: Result, text: String, format: BarcodeFormat, charset: String?): JSONObject {
        val start = System.nanoTime()
        return try {
            val bmp = code(text, format, charset)
            val decoded = try { MultiFormatReader().decode(binary(bmp), harder) } finally { bmp.recycle() }
            obj("status" to "decoded", "format" to decoded.barcodeFormat.name,
                "textEqualsOriginal" to (decoded.text == original.text),
                "rawEqualsOriginal" to (decoded.rawBytes?.contentEquals(original.rawBytes ?: byteArrayOf())),
                "byteSegmentsEqualOriginal" to segmentPayload(original)?.let { segmentPayload(decoded)?.contentEquals(it) },
                "decodedText" to decoded.text, "decodedTextUtf8" to bytes(decoded.text.toByteArray(Charsets.UTF_8)),
                "decodedRaw" to bytes(decoded.rawBytes), "decodedByteSegments" to arr(segments(decoded).map { bytes(it) }))
        } catch (e: Exception) { obj("status" to "failed", "errorType" to e.javaClass.simpleName) }
            .put("ms", (System.nanoTime() - start) / 1e6)
    }
    private fun result(r: Result): JSONObject {
        val points = r.resultPoints?.filterNotNull().orEmpty()
        val raw = r.rawBytes
        return obj("format" to r.barcodeFormat.name, "text" to r.text,
            "textUtf8" to bytes(r.text.toByteArray(Charsets.UTF_8)), "rawBytes" to bytes(raw),
            "rawEqualsTextUtf8" to raw?.contentEquals(r.text.toByteArray(Charsets.UTF_8)),
            "byteSegments" to arr(segments(r).map { bytes(it) }),
            "symbologyIdentifier" to r.resultMetadata?.get(ResultMetadataType.SYMBOLOGY_IDENTIFIER),
            "points" to arr(points.map { arr(listOf(it.x, it.y)) }),
            "pointBounds" to if (points.isEmpty()) null else arr(listOf(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })),
            "roundTrips" to obj(
                "textDefault" to roundTrip(r, r.text, r.barcodeFormat, null),
                "textUtf8" to roundTrip(r, r.text, r.barcodeFormat, "UTF-8"),
                "rawLatin1" to raw?.let { roundTrip(r, String(it, Charsets.ISO_8859_1), r.barcodeFormat, "ISO-8859-1") },
                "byteSegmentsLatin1" to segmentPayload(r)?.let { roundTrip(r, String(it, Charsets.ISO_8859_1), r.barcodeFormat, "ISO-8859-1") },
                "mainRedraw" to roundTrip(r, String(raw ?: r.text.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1), BarcodeFormat.QR_CODE, null)))
    }
    private fun parsed(lines: List<String>, payload: ByteArray?, today: LocalDate): JSONObject {
        val start = System.nanoTime()
        val p = parseTicket(TicketExtract(payload, lines))
        val parseMs = (System.nanoTime() - start) / 1e6
        val routeStart = System.nanoTime()
        val route = routeTicket(p, emptyList(), today)
        return obj("artist" to p.artist, "venue" to p.venue, "date" to p.date,
            "isComplete" to p.isComplete, "route" to route.javaClass.simpleName,
            "parseMs" to parseMs, "routeMs" to (System.nanoTime() - routeStart) / 1e6)
    }
    /** Separate, cheap pass invokes main's actual 480 px renderer on captured results.
     * Select this method explicitly after capture; it does not launch UI or persist gigs.
     */
    @Test fun redrawStoredBytes() {
        if (InstrumentationRegistry.getArguments().getString("ticketProbe") != "true") return
        check(context.packageName == "io.github.magnusencoded.stationtostation.debug")
        File(root, "raw").listFiles()!!.filter { it.extension == "json" }.forEach { file ->
            val doc = JSONObject(file.readText())
            val checked = JSONObject()
            val pages = doc.getJSONArray("pages")
            for (p in 0 until pages.length()) {
                val renders = pages.getJSONObject(p).getJSONArray("renders")
                for (d in 0 until renders.length()) {
                    val runs = renders.getJSONObject(d).getJSONArray("barcodeRuns")
                    for (m in 0 until runs.length()) {
                        val results = runs.getJSONObject(m).getJSONArray("results")
                        for (n in 0 until results.length()) {
                            val r = results.getJSONObject(n)
                            val key = r.getString("format") + ":" + r.getJSONObject("textUtf8").getString("sha256")
                            if (checked.has(key)) continue
                            val payload = if (r.isNull("rawBytes")) r.getString("text").toByteArray(Charsets.UTF_8)
                                else r.getJSONObject("rawBytes").getString("hex").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                            val start = System.nanoTime()
                            val outcome = try {
                                val bitmap = qrBitmap(String(payload, Charsets.ISO_8859_1), 480)
                                val decoded = try { MultiFormatReader().decode(binary(bitmap), harder) } finally { bitmap.recycle() }
                                obj("status" to "decoded", "format" to decoded.barcodeFormat.name,
                                    "sameFormat" to (decoded.barcodeFormat.name == r.getString("format")),
                                    "sameText" to (decoded.text == r.getString("text")),
                                    "decodedTextUtf8" to bytes(decoded.text.toByteArray(Charsets.UTF_8)))
                            } catch (e: Exception) { obj("status" to "failed", "errorType" to e.javaClass.simpleName) }
                            checked.put(key, outcome.put("ms", (System.nanoTime() - start) / 1e6))
                        }
                    }
                }
            }
            doc.put("actualMainRedraw480", checked)
            file.writeText(doc.toString(2))
        }
    }
    @Test fun capture() {
        if (InstrumentationRegistry.getArguments().getString("ticketProbe") != "true") return
        check(context.packageName == "io.github.magnusencoded.stationtostation.debug")
        val input = File(root, "inputs").apply { mkdirs() }
        val output = File(root, "raw").apply { mkdirs() }
        check(input.isDirectory && output.isDirectory) { "Probe directories unavailable" }
        if (InstrumentationRegistry.getArguments().getString("prepareOnly") == "true") return
        generate(input)
        val today = LocalDate.now(ZoneId.of("Europe/Oslo"))
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            input.listFiles()!!.filter { it.extension.equals("pdf", true) }.sortedBy { it.name }.forEach { pdf ->
                val started = System.nanoTime()
                val pages = JSONArray()
                val textLines = mutableListOf<String>()
                val blocks = mutableListOf<String>()
                val ocrLines = mutableListOf<String>()
                var mainPayload: ByteArray? = null
                var anyPayload: ByteArray? = null
                var peakPssKb = 0
                val doc = obj("file" to pdf.name, "pdfSha256" to hash(pdf.readBytes()),
                    "model" to Build.MODEL, "sdk" to Build.VERSION.SDK_INT, "release" to Build.VERSION.RELEASE,
                    "baseCommit" to BuildConfig.GIT_SHA, "today" to today.toString(), "timezone" to "Europe/Oslo",
                    "pages" to pages)
                ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        doc.put("pageCount", renderer.pageCount)
                        for (pageIndex in 0 until renderer.pageCount) renderer.openPage(pageIndex).use { page ->
                            val pageJson = obj("page" to pageIndex + 1, "widthPt" to page.width, "heightPt" to page.height)
                            pages.put(pageJson)
                            val layerStart = System.nanoTime()
                            if (Build.VERSION.SDK_INT >= 35) {
                                try {
                                    val content = page.textContents
                                    pageJson.put("textLayer", arr(content.map { c ->
                                        textLines.addAll(c.text.split('\n'))
                                        obj("text" to c.text, "lines" to arr(c.text.split('\n')), "boundsPt" to arr(c.bounds.map { bounds(it) }))
                                    }))
                                } catch (e: Exception) { pageJson.put("textLayerError", e.javaClass.simpleName) }
                            } else pageJson.put("textLayer", "unavailable")
                            pageJson.put("textLayerMs", (System.nanoTime() - layerStart) / 1e6)
                            val renders = JSONArray()
                            pageJson.put("renders", renders)
                            for (dpi in listOf(200, 300)) {
                                val renderStart = System.nanoTime()
                                val bitmap = Bitmap.createBitmap((page.width * dpi / 72f).toInt(), (page.height * dpi / 72f).toInt(), Bitmap.Config.ARGB_8888)
                                bitmap.eraseColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val render = obj("dpi" to dpi, "widthPx" to bitmap.width, "heightPx" to bitmap.height,
                                    "bitmapBytes" to bitmap.allocationByteCount, "renderMs" to (System.nanoTime() - renderStart) / 1e6)
                                renders.put(render)
                                try {
                                    val runs = JSONArray()
                                    render.put("barcodeRuns", runs)
                                    for (mode in listOf("unhinted", "tryHarder", "multiple")) {
                                        val decodeStart = System.nanoTime()
                                        var error: String? = null
                                        val results = try {
                                            val b = binary(bitmap)
                                            when (mode) {
                                                "unhinted" -> listOf(MultiFormatReader().decode(b))
                                                "tryHarder" -> listOf(MultiFormatReader().decode(b, harder))
                                                else -> GenericMultipleBarcodeReader(MultiFormatReader()).decodeMultiple(b, harder).toList()
                                            }
                                        } catch (e: ReaderException) { error = e.javaClass.simpleName; emptyList() }
                                        val decodeMs = (System.nanoTime() - decodeStart) / 1e6
                                        if (dpi == 200 && mode == "unhinted" && pageIndex < 5 && mainPayload == null)
                                            mainPayload = results.firstOrNull()?.let { it.rawBytes ?: it.text.toByteArray(Charsets.UTF_8) }
                                        if (anyPayload == null) anyPayload = results.firstOrNull()?.let { it.rawBytes ?: it.text.toByteArray(Charsets.UTF_8) }
                                        runs.put(obj("mode" to mode, "decodeMs" to decodeMs, "errorType" to error, "results" to arr(results.map { result(it) })))
                                    }
                                    val ocrStart = System.nanoTime()
                                    try {
                                        val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 90, TimeUnit.SECONDS)
                                        render.put("ocrBlocks", arr(text.textBlocks.map { block ->
                                            if (dpi == 200) {
                                                if (pageIndex < 5) blocks.add(block.text)
                                                ocrLines.addAll(block.lines.map { it.text })
                                            }
                                            obj("text" to block.text, "boundsPx" to bounds(block.boundingBox),
                                                "lines" to arr(block.lines.map { obj("text" to it.text, "boundsPx" to bounds(it.boundingBox)) }))
                                        }))
                                    } catch (e: Exception) { render.put("ocrError", e.javaClass.simpleName) }
                                    render.put("ocrMs", (System.nanoTime() - ocrStart) / 1e6)
                                    val info = Debug.MemoryInfo()
                                    Debug.getMemoryInfo(info)
                                    peakPssKb = maxOf(peakPssKb, info.totalPss)
                                    render.put("samplePssKb", info.totalPss)
                                } finally { bitmap.recycle() }
                            }
                        }
                    }
                }
                doc.put("currentExtractionPayload", bytes(mainPayload))
                doc.put("parser", obj("pooledOcrBlocks" to parsed(blocks, mainPayload, today),
                    "textLayerLines" to parsed(textLines, mainPayload, today), "ocrLines" to parsed(ocrLines, mainPayload, today)))
                doc.put("parserWithAnyDecodedBarcode", obj("pooledOcrBlocks" to parsed(blocks, anyPayload, today),
                    "textLayerLines" to parsed(textLines, anyPayload, today), "ocrLines" to parsed(ocrLines, anyPayload, today)))
                doc.put("sampledPeakPssKb", peakPssKb).put("totalMs", (System.nanoTime() - started) / 1e6)
                File(output, pdf.nameWithoutExtension + ".json").writeText(doc.toString(2))
                // Progress identifies only the local, user-assigned alias, never PDF contents.
                InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("stream", "Captured ${pdf.name}\n") })
            }
        } finally { recognizer.close() }
    }

    private fun generate(input: File) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 20f }
        fun text(c: Canvas, s: String, y: Float, imageOnly: Boolean = false) {
            if (!imageOnly) c.drawText(s, 42f, y, paint) else {
                val b = Bitmap.createBitmap(1550, 90, Bitmap.Config.ARGB_8888)
                b.eraseColor(Color.WHITE)
                Canvas(b).drawText(s, 0f, 65f, Paint(paint).apply { textSize = 60f })
                c.drawBitmap(b, null, RectF(42f, y - 23f, 559f, y + 7f), null)
                b.recycle()
            }
        }
        fun barcode(c: Canvas, value: String, format: BarcodeFormat, rect: RectF, charset: String? = null) {
            val b = code(value, format, charset)
            c.drawBitmap(b, null, rect, Paint().apply { isFilterBitmap = false })
            b.recycle()
        }
        fun pdf(name: String, count: Int = 1, draw: (Canvas, Int) -> Unit) {
            val d = PdfDocument()
            try {
                repeat(count) { n ->
                    val p = d.startPage(PdfDocument.PageInfo.Builder(595, 842, n + 1).create())
                    p.canvas.drawColor(Color.WHITE)
                    draw(p.canvas, n)
                    d.finishPage(p)
                }
                File(input, "$name.pdf").outputStream().use { d.writeTo(it) }
            } finally { d.close() }
        }
        fun header(c: Canvas, date: String = "25-09-2026", artistImage: Boolean = false) {
            text(c, "Ocean Colour Scene", 90f, artistImage)
            text(c, date, 145f)
            text(c, "Auditorio Marina Norte, Valencia", 200f)
            val footer = Paint(paint).apply { textSize = 11f }
            c.drawText("Synthetic test ticket - not valid for admission", 42f, 755f, footer)
            c.drawText("Visor Fest / setlist.fm ID 1b498dc4", 42f, 779f, footer)
        }
        val box = RectF(170f, 310f, 425f, 565f)
        for (f in listOf(BarcodeFormat.QR_CODE, BarcodeFormat.CODE_128, BarcodeFormat.PDF_417, BarcodeFormat.AZTEC, BarcodeFormat.DATA_MATRIX)) {
            pdf("synthetic-${f.name.lowercase()}") { c, _ ->
                header(c)
                barcode(c, "SYNTHETIC-OCS-1b498dc4-${f.name}", f,
                    if (f == BarcodeFormat.CODE_128) RectF(42f, 330f, 553f, 460f) else box)
            }
        }
        pdf("synthetic-two-one-page") { c, _ ->
            header(c)
            barcode(c, "SYNTHETIC-A", BarcodeFormat.QR_CODE, RectF(50f, 310f, 250f, 510f))
            barcode(c, "SYNTHETIC-B", BarcodeFormat.QR_CODE, RectF(345f, 530f, 545f, 730f))
        }
        pdf("synthetic-two-pages", 2) { c, n -> header(c); barcode(c, "SYNTHETIC-PAGE-$n", BarcodeFormat.QR_CODE, box) }
        pdf("synthetic-repeat-three", 3) { c, _ -> header(c); barcode(c, "SYNTHETIC-REPEATED", BarcodeFormat.QR_CODE, box) }
        pdf("synthetic-artist-image") { c, _ -> header(c, artistImage = true); barcode(c, "SYNTHETIC-ARTIST-IMAGE", BarcodeFormat.QR_CODE, box) }
        pdf("synthetic-vendor-image") { c, _ ->
            header(c); text(c, "TICKETLINE", 42f, true); barcode(c, "SYNTHETIC-VENDOR-IMAGE", BarcodeFormat.QR_CODE, box)
        }
        for ((name, date) in listOf("norwegian" to "28. nov. 2026", "numeric" to "24-09-2026", "english" to "June 24, 2026")) {
            pdf("synthetic-date-$name") { c, _ -> header(c, date); barcode(c, "SYNTHETIC-DATE-$name", BarcodeFormat.QR_CODE, box) }
        }
        pdf("synthetic-small-corner") { c, _ ->
            header(c)
            val small = Paint(paint).apply { textSize = 11f }
            repeat(30) { c.drawText("Synthetic layout filler. Bring this example to a test, never to a venue.", 42f, 240f + it * 14f, small) }
            barcode(c, "SYNTHETIC-SMALL-CORNER", BarcodeFormat.QR_CODE, RectF(518f, 30f, 565f, 77f))
        }
        pdf("synthetic-binary-qr") { c, _ ->
            header(c)
            val payload = byteArrayOf(0, 0xff.toByte(), 0x80.toByte(), 0xc3.toByte(), 0x28, 0x41)
            barcode(c, String(payload, Charsets.ISO_8859_1), BarcodeFormat.QR_CODE, box, "ISO-8859-1")
        }
        // Identical pixels/content to the QR text-layer example, now no text objects.
        val source = File(input, "synthetic-qr_code.pdf")
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r -> r.openPage(0).use { p ->
                val bitmap = Bitmap.createBitmap(1785, 2526, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                p.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                pdf("synthetic-flattened") { c, _ -> c.drawBitmap(bitmap, null, RectF(0f, 0f, 595f, 842f), null) }
                bitmap.recycle()
            } }
        }
    }
}
