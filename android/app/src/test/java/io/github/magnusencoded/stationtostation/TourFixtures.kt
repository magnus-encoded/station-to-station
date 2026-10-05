package io.github.magnusencoded.stationtostation

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

internal object TourFixtures {
    fun load(): List<JsonObject> {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "fixtures/tour/cases.json") }
            .firstOrNull { it.isFile }
            ?: error("fixtures/tour/cases.json not found")
        val document = Json.parseToJsonElement(file.readText()).jsonObject
        check(document["schemaVersion"].toString() == "1")
        return document.getValue("cases").jsonArray.map { it.jsonObject }
    }
}
