package io.github.magnusencoded.stationtostation.telemetry

import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Bounded OTLP JSON on this phone; no backup, payload attributes or network exporter. */
internal class LocalSpanExporter(private val directory: File) : SpanExporter {
    @Synchronized
    override fun export(spans: Collection<SpanData>): CompletableResultCode = try {
        directory.mkdirs()
        val current = directory.resolve("spans.jsonl")
        if (current.length() > 2 * 1024 * 1024) {
            directory.resolve("spans.2.jsonl").delete()
            directory.resolve("spans.1.jsonl").renameTo(directory.resolve("spans.2.jsonl"))
            current.renameTo(directory.resolve("spans.1.jsonl"))
        }
        directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 7 * 86_400_000L }
            ?.forEach { it.delete() }
        val resources = JSONArray()
        for (span in spans) {
            val attrs = JSONArray()
            span.attributes.forEach { key, value ->
                if (key.key in allowed) attrs.put(attribute(key.key, value))
            }
            val data = JSONObject().put("traceId", span.traceId).put("spanId", span.spanId)
                .put("name", span.name).put("kind", 1)
                .put("startTimeUnixNano", span.startEpochNanos.toString())
                .put("endTimeUnixNano", span.endEpochNanos.toString())
                .put("attributes", attrs)
                .put("status", JSONObject().put("code", when (span.status.statusCode.name) {
                    "ERROR" -> 2; "OK" -> 1; else -> 0
                }))
            val resource = JSONArray()
            span.resource.attributes.forEach { key, value -> resource.put(attribute(key.key, value)) }
            resources.put(JSONObject().put("resource", JSONObject().put("attributes", resource))
                .put("scopeSpans", JSONArray().put(JSONObject()
                    .put("scope", JSONObject().put("name", "station-to-station.debug.quality"))
                    .put("spans", JSONArray().put(data)))))
        }
        current.appendText(JSONObject().put("resourceSpans", resources).toString() + "\n")
        CompletableResultCode.ofSuccess()
    } catch (_: Exception) {
        CompletableResultCode.ofFailure()
    }

    override fun flush() = CompletableResultCode.ofSuccess()
    override fun shutdown() = CompletableResultCode.ofSuccess()

    private fun attribute(key: String, value: Any) = JSONObject().put("key", key).put("value",
        JSONObject().put(if (value is Long) "intValue" else "stringValue", value.toString()))

    private val allowed = setOf("screen.name", "feature.name", "outcome", "stall.ms", "error.source")
}
