package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.gossip.GossipEnvelope
import io.github.magnusencoded.stationtostation.data.gossip.weaveGossip
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GossipPlacementTest {
    private val cases: JsonArray = run {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "fixtures/gossip-placement/cases.json") }.first { it.isFile }
        Json.parseToJsonElement(file.readText()).jsonArray
    }

    private fun envelope(author: String, line: Int, text: String) = GossipEnvelope(
        gigId = "gig", scope = "scope", author = author, createdAt = 1000, expiresAt = 100000,
        kind = "log", line = line, text = text,
    )

    @Test fun everySharedCasePlacesReceivedLinesWhereTheCorpusSays() {
        assertTrue(cases.isNotEmpty())
        for (case in cases.map { it.jsonObject }) {
            val name = case["name"]!!.jsonPrimitive.content
            val mine = case["mine"]!!.jsonArray.map { if (it is JsonNull) null else it.jsonPrimitive.content }
            val facts = case["theirs"]!!.jsonObject.flatMap { (author, lines) ->
                lines.jsonObject.map { (line, text) -> envelope(author, line.toInt(), text.jsonPrimitive.content) }
            }
            val actual = weaveGossip(mine, facts.shuffled(java.util.Random(7)))
            val expected = case["expected"]!!.jsonArray.map { it.jsonObject }
            assertEquals(name, expected.map { it["base"].let { b -> if (b is JsonNull) null else b!!.jsonPrimitive.int } },
                actual.map { it.base })
            assertEquals(name, expected.map { row -> row["lines"]!!.jsonArray.map { it.jsonPrimitive.content }.sorted() },
                actual.map { row -> row.facts.map { "${it.author}:${it.line}" }.sorted() })
        }
    }
}
