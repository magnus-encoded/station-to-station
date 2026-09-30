package io.github.magnusencoded.stationtostation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The shared link corpus (`fixtures/deeplinks/`): the same link into [parseDeepLink] on
 * both platforms, the same intent out. `fixtures/deeplinks/README.md` is the schema and
 * the rules; iOS's `DeepLinkFixtureTests` runs `cases.json` unchanged.
 *
 * Required, never skipped, and every mismatch is reported together.
 */
class DeepLinkFixturesTest {

    private fun cases(): JsonArray {
        val dir = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "fixtures/deeplinks") }
            .firstOrNull { it.isDirectory }
            ?: error("fixtures/deeplinks not found above ${File("").absolutePath}")
        return Json.parseToJsonElement(File(dir, "cases.json").readText()).jsonArray
    }

    @Test
    fun everyLinkMeansWhatTheCorpusSays() {
        val cases = cases()
        assertTrue("fixtures/deeplinks lost cases: ${cases.size} < $FLOOR", cases.size >= FLOOR)
        val failures = cases.mapNotNull { case ->
            val link = case.jsonObject.getValue("link").jsonPrimitive.content
            val expected = case.jsonObject["intent"] ?: JsonNull
            val actual = parseDeepLink(link)?.let(::render) ?: JsonNull
            if (expected == actual) null else "$link\n    expected $expected\n    actual   $actual"
        }
        println("DeepLinkFixturesTest: ran ${cases.size} fixture cases")
        if (failures.isNotEmpty()) fail("${failures.size} mismatches in fixtures/deeplinks:\n" + failures.joinToString("\n"))
    }

    private fun render(intent: LinkIntent): JsonElement = when (intent) {
        is LinkIntent.Open -> buildJsonObject {
            put("type", JsonPrimitive("open"))
            put("screen", JsonPrimitive(intent.screen.name.lowercase()))
            intent.date?.let { put("date", JsonPrimitive(it)) }
        }
        is LinkIntent.OpenGig -> buildJsonObject {
            put("type", JsonPrimitive("openGig"))
            put("id", JsonPrimitive(intent.id))
        }
        is LinkIntent.AddGig -> buildJsonObject {
            put("type", JsonPrimitive("addGig"))
            intent.artist?.let { put("artist", JsonPrimitive(it)) }
            intent.venue?.let { put("venue", JsonPrimitive(it)) }
            intent.date?.let { put("date", JsonPrimitive(it)) }
        }
        is LinkIntent.WriteToLog -> buildJsonObject {
            put("type", JsonPrimitive("writeToLog"))
            put("gigId", JsonPrimitive(intent.gigId))
            put("appends", JsonArray(intent.appends.map(::JsonPrimitive)))
            put("replacements", JsonObject(intent.replacements.entries.associate { (n, t) -> n.toString() to JsonPrimitive(t) }))
        }
        is LinkIntent.LegacyPlace -> buildJsonObject {
            put("type", JsonPrimitive("legacyPlace"))
            put("gigId", JsonPrimitive(intent.gigId))
            put("as", JsonPrimitive(when (intent.at) {
                GigLink.SETLIST -> "setlist"
                GigLink.SINGLE_LINE -> "singleLine"
                GigLink.WOVEN -> "woven"
            }))
        }
        LinkIntent.LegacyMe -> buildJsonObject { put("type", JsonPrimitive("me")) }
        is LinkIntent.LegacyFixture -> buildJsonObject {
            put("type", JsonPrimitive("fixture"))
            put("name", JsonPrimitive(intent.name))
            put("open", JsonPrimitive(intent.open))
        }
        is LinkIntent.PassThrough -> buildJsonObject {
            put("type", JsonPrimitive("passThrough"))
            put("kind", JsonPrimitive(intent.kind.name.lowercase()))
        }
    }

    private companion object {
        const val FLOOR = 70
    }
}
