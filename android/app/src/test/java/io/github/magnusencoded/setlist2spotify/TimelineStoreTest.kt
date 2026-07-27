package io.github.magnusencoded.setlist2spotify

import io.github.magnusencoded.setlist2spotify.data.TimelineStore
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmArtist
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmSetlist
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TimelineStoreTest {

    private fun store() = TimelineStore(File.createTempFile("timelines", ".json").also { it.delete() })

    private fun show(id: String) = FmSetlist(id = id, eventDate = "25-06-2026", artist = FmArtist(name = "The Warning"))

    @Test
    fun `missing file loads empty rather than throwing`() = runBlocking {
        val cache = store().load()
        assertTrue(cache.shows.isEmpty())
        assertTrue(cache.festivalNames.isEmpty())
    }

    @Test
    fun `a saved timeline round-trips`() = runBlocking {
        val store = store()
        store.save(shows = mapOf("magnus" to listOf(show("a"), show("b"))))
        assertEquals(listOf("a", "b"), store.load().shows["magnus"]?.map { it.id })
    }

    @Test
    fun `saving one lane leaves the others alone`() = runBlocking {
        val store = store()
        store.save(shows = mapOf("magnus" to listOf(show("a"))))
        store.save(shows = mapOf("Carlitos2" to listOf(show("b"))))
        val shows = store.load().shows
        assertEquals(listOf("a"), shows["magnus"]?.map { it.id })
        assertEquals(listOf("b"), shows["Carlitos2"]?.map { it.id })
    }

    @Test
    fun `a failed fetch does not wipe the last good lane`() = runBlocking {
        val store = store()
        store.save(shows = mapOf("Carlitos2" to listOf(show("a"))))
        // loadFriendTimelines() puts an empty list in the map when a fetch throws.
        store.save(shows = mapOf("Carlitos2" to emptyList()))
        assertEquals(listOf("a"), store.load().shows["Carlitos2"]?.map { it.id })
    }

    @Test
    fun `festival names accumulate across saves`() = runBlocking {
        val store = store()
        store.save(festivalNames = mapOf("a" to "Tons of Rock"))
        store.save(shows = mapOf("magnus" to listOf(show("a"))))
        assertEquals(mapOf("a" to "Tons of Rock"), store.load().festivalNames)
    }

    @Test
    fun `an unreadable cache degrades to empty instead of crashing the launch`() = runBlocking {
        val file = File.createTempFile("timelines", ".json")
        file.writeText("{ not json")
        assertTrue(TimelineStore(file).load().shows.isEmpty())
    }
}
