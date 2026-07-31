package io.github.magnusencoded.setlist2spotify

import io.github.magnusencoded.setlist2spotify.data.StoredPlaylist
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
        store.save(shows = mapOf("Egil" to listOf(show("b"))))
        val shows = store.load().shows
        assertEquals(listOf("a"), shows["magnus"]?.map { it.id })
        assertEquals(listOf("b"), shows["Egil"]?.map { it.id })
    }

    @Test
    fun `a failed fetch does not wipe the last good lane`() = runBlocking {
        val store = store()
        store.save(shows = mapOf("Egil" to listOf(show("a"))))
        // loadFriendTimelines() puts an empty list in the map when a fetch throws.
        store.save(shows = mapOf("Egil" to emptyList()))
        assertEquals(listOf("a"), store.load().shows["Egil"]?.map { it.id })
    }

    @Test
    fun `festival names accumulate across saves`() = runBlocking {
        val store = store()
        store.save(festivalNames = mapOf("a" to "Tons of Rock"))
        store.save(shows = mapOf("magnus" to listOf(show("a"))))
        assertEquals(mapOf("a" to "Tons of Rock"), store.load().festivalNames)
    }

    private fun playlist(id: String) =
        StoredPlaylist("https://open.spotify.com/playlist/$id", "2026 – The Warning", 19)

    @Test
    fun `a night remembers the playlist it became`() = runBlocking {
        val store = store()
        store.save(shows = mapOf("magnus" to listOf(show("a"))))
        store.save(playlists = mapOf("a" to playlist("p1")))
        // A later save of the shows must not drop it: the two write independently.
        store.save(shows = mapOf("magnus" to listOf(show("a"), show("b"))))
        val cached = store.load()
        assertEquals(19, cached.playlistsMade["a"]?.single()?.trackCount)
        assertEquals(listOf("a", "b"), cached.shows["magnus"]?.map { it.id })
    }

    @Test
    fun `converting a night again keeps the link already sent to someone`() = runBlocking {
        val store = store()
        store.save(playlists = mapOf("a" to playlist("p1")))
        store.save(playlists = mapOf("a" to playlist("p2")))
        assertEquals(
            listOf("https://open.spotify.com/playlist/p1", "https://open.spotify.com/playlist/p2"),
            store.load().playlistsMade["a"]?.map { it.url },
        )
    }

    @Test
    fun `recording the same playlist twice does not duplicate it`() = runBlocking {
        val store = store()
        store.save(playlists = mapOf("a" to playlist("p1")))
        store.save(playlists = mapOf("a" to playlist("p1")))
        assertEquals(1, store.load().playlistsMade["a"]?.size)
    }

    @Test
    fun `a cache written before playlists were a list still loads its timelines`() = runBlocking {
        val file = File.createTempFile("timelines", ".json")
        // The shape the previous build wrote: playlists as one entry per night.
        file.writeText(
            """{"shows":{"magnus":[{"id":"a","eventDate":"25-06-2026"}]},""" +
                """"festivalNames":{},"playlists":{"a":{"url":"u","name":"n","trackCount":3}}}"""
        )
        val cached = TimelineStore(file).load()
        assertEquals(listOf("a"), cached.shows["magnus"]?.map { it.id })
        assertTrue(cached.playlistsMade.isEmpty())
    }

    @Test
    fun `an unreadable cache degrades to empty instead of crashing the launch`() = runBlocking {
        val file = File.createTempFile("timelines", ".json")
        file.writeText("{ not json")
        assertTrue(TimelineStore(file).load().shows.isEmpty())
    }

    @Test
    fun `song offsets survive a save of the shows around them`() = runBlocking {
        val store = store()
        store.saveSongOffsets("a", listOf(0L, 214_000L, -1L))
        store.save(shows = mapOf("magnus" to listOf(show("a"))))
        assertEquals(listOf(0L, 214_000L, -1L), store.load().songOffsetsBySetlist["a"])
    }

    @Test
    fun `restamping a night replaces its offsets rather than appending`() = runBlocking {
        val store = store()
        store.saveSongOffsets("a", listOf(0L, 100L))
        store.saveSongOffsets("a", listOf(0L, 250L))
        assertEquals(listOf(0L, 250L), store.load().songOffsetsBySetlist["a"])
    }

    @Test
    fun `the reported total survives a reload, so paging can resume`() = runBlocking {
        val store = store()
        store.save(shows = mapOf("dizzi90" to listOf(show("a"))), attendedTotals = mapOf("dizzi90" to 169))
        // A later save of more shows must not drop the total already learned.
        store.save(shows = mapOf("dizzi90" to listOf(show("a"), show("b"))))
        assertEquals(169, store.load().attendedTotals["dizzi90"])
    }
}
