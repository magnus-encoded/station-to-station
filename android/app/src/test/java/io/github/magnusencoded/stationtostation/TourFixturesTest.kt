package io.github.magnusencoded.stationtostation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TourFixturesTest {
    private fun cases() = Json.parseToJsonElement(
        File(fixtureDir(), "cases.json").readText()
    ).jsonArray

    private fun fixtureDir(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "fixtures/tour") }
            .firstOrNull { it.isDirectory }
            ?: error("fixtures/tour not found above ${File("").absolutePath}")

    @Test
    fun sharedTourCasesLoad() {
        val cases = cases()
        val rows = cases.map { it.jsonObject.getValue("row").jsonPrimitive.content }.toSet()
        assertEquals(EXPECTED_ROWS, rows)
        assertEquals(19, cases.count { it.jsonObject.getValue("row").jsonPrimitive.content == "Skip at Sn" })
        assertTrue(cases.all { it.jsonObject.getValue("events").jsonArray.isNotEmpty() })
    }

    private companion object {
        val EXPECTED_ROWS = setOf(
            "Happy path", "Offline start", "Skip at Sn", "Resume", "Replay",
            "Spotify declined", "Spotify retry", "Fill: setlist.fm hit",
            "Fill: fallback", "Out-of-order event"
        )
    }
}
