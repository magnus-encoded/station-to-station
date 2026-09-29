package io.github.magnusencoded.stationtostation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Loading contract for the platform-neutral Tour script cases in #587. */
class TourScriptFixturesTest {

    @Serializable
    private data class Fixture(
        val version: Int,
        val interactiveSteps: List<String>,
        val cases: List<Case>,
    )

    @Serializable
    private data class Case(
        val name: String,
        val kind: String,
        val events: List<Event> = emptyList(),
        val event: Event? = null,
        val atSteps: List<String> = emptyList(),
        val expect: Map<String, JsonElement>,
    )

    @Serializable
    private data class Event(val type: String, val value: String? = null)

    private fun fixture(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "fixtures/tour-script/cases.json") }
            .firstOrNull { it.isFile }
            ?: error("fixtures/tour-script/cases.json not found above ${File("").absolutePath}")

    @Test
    fun `both platforms receive every shared Tour case`() {
        val document = Json { ignoreUnknownKeys = true }
            .decodeFromString<Fixture>(fixture().readText())

        assertEquals(1, document.version)
        assertEquals((1..19).map { "S$it" }, document.interactiveSteps)
        assertEquals(
            setOf(
                "happy path",
                "offline start",
                "skip at every step",
                "resume at every step",
                "replay",
                "spotify declined",
                "spotify retry",
                "fill from setlist.fm",
                "fill from MusicBrainz fallback",
                "out-of-order event",
            ),
            document.cases.map { it.name }.toSet(),
        )
        assertEquals(document.cases.size, document.cases.map { it.name }.toSet().size)

        val skip = document.cases.single { it.name == "skip at every step" }
        val resume = document.cases.single { it.name == "resume at every step" }
        assertEquals(document.interactiveSteps, skip.atSteps)
        assertEquals(document.interactiveSteps, resume.atSteps)
        assertEquals("skipped", skip.event?.type)
        assertEquals("resumed", resume.event?.type)
        assertTrue(document.cases.all { it.expect.isNotEmpty() })
    }
}
