package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.TimelineStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * `fixtures/timeline/unreadable/` is shared with iOS's `TimelineQuarantineTests`, which
 * asserts the same four things of it (see `fixtures/timeline/README.md`).
 */
class TimelineQuarantineTest {

    private fun fixture(name: String): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "fixtures/timeline/unreadable/$name") }
            .firstOrNull { it.isFile }
            ?: error("fixtures/timeline/unreadable/$name not found above ${File("").absolutePath}")

    private fun quarantined(dir: File): List<File> =
        dir.listFiles().orEmpty().filter { it.name != "timelines.json" }

    private fun assertQuarantinesAndKeeps(name: String) = runBlocking {
        val original = fixture(name).readBytes()
        val dir = Files.createTempDirectory("quarantine").toFile()
        val file = File(dir, "timelines.json").apply { writeBytes(original) }
        val store = TimelineStore(file)

        val loaded = store.load()
        assertTrue(loaded.gigs.isEmpty())
        val aside = quarantined(dir).single()
        assertTrue(aside.name, Regex("""timelines\.corrupt-\d+\.json""").matches(aside.name))
        assertTrue(original.contentEquals(aside.readBytes()))

        store.save(attendedTotals = mapOf("dizzi90" to 3))
        assertEquals(listOf(aside), quarantined(dir))
        assertTrue(original.contentEquals(aside.readBytes()))
        val reloaded = store.load()
        assertEquals(mapOf("dizzi90" to 3), reloaded.attendedTotals)
        assertTrue(reloaded.gigs.isEmpty())
        check(dir.deleteRecursively())
    }

    @Test
    fun `a truncated timeline file is set aside untouched, not overwritten by the next save`() =
        assertQuarantinesAndKeeps("truncated.json")

    @Test
    fun `a timeline file that is not an object is set aside untouched, not overwritten by the next save`() =
        assertQuarantinesAndKeeps("not-an-object.json")

    @Test
    fun `a missing timeline file on first run sets nothing aside`() = runBlocking {
        val dir = Files.createTempDirectory("quarantine").toFile()
        val store = TimelineStore(File(dir, "timelines.json"))
        assertTrue(store.load().gigs.isEmpty())
        store.save(attendedTotals = mapOf("dizzi90" to 3))
        assertEquals(emptyList<File>(), quarantined(dir))
        check(dir.deleteRecursively())
    }
}
