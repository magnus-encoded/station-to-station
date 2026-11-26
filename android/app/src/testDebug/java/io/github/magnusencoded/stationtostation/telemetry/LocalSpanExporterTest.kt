package io.github.magnusencoded.stationtostation.telemetry

import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LocalSpanExporterTest {
    @Test fun `OTLP export preserves timing and status while dropping payload attributes`() {
        val directory = Files.createTempDirectory("telemetry-test").toFile()
        try {
            val provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(LocalSpanExporter(directory))).build()
            val span = provider.get("test").spanBuilder("feature.error").startSpan()
            span.setAttribute("screen.name", "search")
            span.setAttribute("artist.query", "private artist query")
            span.setStatus(StatusCode.ERROR, "private error message")
            span.recordException(IllegalArgumentException("private exception"))
            span.end()
            provider.close()
            val text = directory.resolve("spans.jsonl").readText()
            assertFalse(text.contains("private"))
            val data = JSONObject(text.trim()).getJSONArray("resourceSpans").getJSONObject(0)
                .getJSONArray("scopeSpans").getJSONObject(0).getJSONArray("spans").getJSONObject(0)
            assertEquals("feature.error", data.getString("name"))
            assertEquals(32, data.getString("traceId").length)
            assertEquals(16, data.getString("spanId").length)
            assertTrue(data.getString("endTimeUnixNano").toLong() >= data.getString("startTimeUnixNano").toLong())
            assertEquals(2, data.getJSONObject("status").getInt("code"))
            assertEquals(1, data.getJSONArray("attributes").length())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `export rotates a full file and removes expired traces`() {
        val directory = Files.createTempDirectory("telemetry-test").toFile()
        try {
            directory.resolve("spans.jsonl").writeText("x".repeat(2 * 1024 * 1024 + 1))
            val expired = directory.resolve("spans.2.jsonl")
            expired.writeText("old")
            expired.setLastModified(System.currentTimeMillis() - 8 * 86_400_000L)
            val exporter = LocalSpanExporter(directory)
            assertTrue(exporter.export(emptyList()).isSuccess)
            assertTrue(directory.resolve("spans.1.jsonl").exists())
            assertFalse(expired.exists())
            assertTrue(directory.resolve("spans.jsonl").length() < 100)
        } finally { directory.deleteRecursively() }
    }
}
