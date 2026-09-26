package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.TicketBarcode
import io.github.magnusencoded.stationtostation.data.TicketEvidence
import io.github.magnusencoded.stationtostation.data.TicketReading
import io.github.magnusencoded.stationtostation.data.TicketSupport
import io.github.magnusencoded.stationtostation.data.parseTicketFields
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The shared ticket corpus (`fixtures/ticket/`, #526): the same readings into
 * [parseTicketFields] on both platforms, and the same fields out, each with the same
 * support. `fixtures/ticket/README.md` is the schema and the rules; iOS's
 * `TicketFixtureTests` runs these files unchanged.
 *
 * Required, never skipped: a missing corpus would make the parity this exists for pass
 * by saying nothing. Every mismatch in every case is collected and reported together,
 * so one CI run shows the whole picture rather than the first failure.
 *
 * `unsupportedBarcodeFormat` (#534) is Android's own and outside the corpus, so it is
 * not asserted here (README, "What each platform's test does").
 */
class TicketFixturesTest {

    /** Walk up from the module dir: the fixtures sit at the repo root, outside android/. */
    private fun fixturesDir(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "fixtures/ticket") }
            .firstOrNull { it.isDirectory }
            ?: error("fixtures/ticket not found above ${File("").absolutePath}")

    private val origins = mapOf("textLayer" to TicketReading.Origin.TEXT_LAYER, "ocr" to TicketReading.Origin.OCR)
    private val supports = mapOf("both" to TicketSupport.BOTH, "textLayer" to TicketSupport.TEXT_LAYER, "ocr" to TicketSupport.OCR)
    private val fields = setOf("artist", "venue", "date", "skipsPrompt")

    @Test
    fun everyCaseReadsAsExpected() {
        val files = fixturesDir().listFiles().orEmpty()
            .filter { it.extension == "json" }
            .sortedBy { it.name }
        assertTrue("fixtures/ticket lost cases: ${files.size} < $FLOOR", files.size >= FLOOR)

        val failures = mutableListOf<String>()
        var ran = 0
        for (file in files) {
            failures += check(file.nameWithoutExtension, Json.parseToJsonElement(file.readText()).jsonObject)
            ran++
        }
        // Printed so the count shows in the CI log (testLogging in app/build.gradle.kts).
        println("TicketFixturesTest: ran $ran fixture cases")
        if (failures.isNotEmpty()) fail("${failures.size} mismatches in fixtures/ticket:\n" + failures.joinToString("\n"))
    }

    /** Every mismatch in one case, as lines for the report. Empty when the case reads as expected. */
    private fun check(name: String, case: JsonObject): List<String> {
        val failures = mutableListOf<String>()
        val known = case["knownFailure"]?.jsonObject.orEmpty()
        for (field in known.keys - fields) failures += "$name: knownFailure names no field $field"

        val evidence = TicketEvidence(
            readings = case.getValue("readings").jsonArray.map { reading ->
                val r = reading.jsonObject
                TicketReading(
                    origin = origins.getValue(r.getValue("origin").jsonPrimitive.content),
                    lines = r.getValue("lines").jsonArray.map { it.jsonPrimitive.content },
                )
            },
            barcodes = case.getValue("barcodes").jsonArray.map { barcode ->
                val b = barcode.jsonObject
                TicketBarcode(
                    image = ByteArray(0),
                    payload = b.getValue("payload").jsonPrimitive.content.toByteArray(Charsets.UTF_8),
                    symbology = b.getValue("symbology").jsonPrimitive.content,
                )
            },
        )
        val found = parseTicketFields(evidence)
        if (found.isEmpty) return failures + "$name: read nothing"

        val want = case.getValue("expected").jsonObject

        /**
         * A known failure is asserted strictly, like iOS's `XCTExpectFailure`: it has to
         * still be failing, and it fails the test once it reads right, so the flag gets
         * removed together with the fix.
         */
        fun assertField(field: String, mismatches: List<String>) {
            val reason = known[field]?.jsonPrimitive?.content
            when {
                reason == null -> failures += mismatches
                mismatches.isEmpty() -> failures += "$name: $field reads right now; remove its knownFailure ($reason)"
            }
        }

        for ((field, value, support) in listOf(
            Triple("artist", found.artist, found.artistSupport),
            Triple("venue", found.venue, found.venueSupport),
            Triple("date", found.date, found.dateSupport),
        )) {
            val expect = want.getValue(field)
            if (expect.isUnchecked()) continue
            val (wantValue, wantSupport) = when (expect) {
                is JsonNull -> null to null
                else -> expect.jsonObject.let {
                    it.getValue("value").jsonPrimitive.content to supports.getValue(it.getValue("support").jsonPrimitive.content)
                }
            }
            assertField(
                field,
                listOfNotNull(
                    "$name: $field is \"$value\", expected \"$wantValue\"".takeIf { value != wantValue },
                    "$name: $field support is $support, expected $wantSupport".takeIf { support != wantSupport },
                ),
            )
        }

        val wantBarcode = want.getValue("barcode").let { if (it is JsonNull) null else it.jsonPrimitive.content }
        val barcode = found.qrBytes?.toString(Charsets.UTF_8)
        if (barcode != wantBarcode) failures += "$name: barcode is \"$barcode\", expected \"$wantBarcode\""

        val skips = want.getValue("skipsPrompt")
        if (!skips.isUnchecked()) {
            val wantSkips = skips.jsonPrimitive.boolean
            assertField(
                "skipsPrompt",
                listOfNotNull(
                    "$name: skipsPrompt is ${found.canSkipPrompt}, expected $wantSkips"
                        .takeIf { found.canSkipPrompt != wantSkips },
                ),
            )
        }
        return failures
    }

    private fun JsonElement.isUnchecked() = this is JsonPrimitive && isString && content == "unchecked"

    private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())

    private companion object {
        /** README: fail below this, and raise it with the corpus. */
        const val FLOOR = 19
    }
}
