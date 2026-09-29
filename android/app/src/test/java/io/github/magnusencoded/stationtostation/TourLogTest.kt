package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.StoredLog
import io.github.magnusencoded.stationtostation.data.tourSetlistFill
import io.github.magnusencoded.stationtostation.data.gossip.GossipEnvelope
import io.github.magnusencoded.stationtostation.data.gossip.PublicGossipState
import io.github.magnusencoded.stationtostation.data.gossip.withoutGigs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TourLogTest {
    @Test fun `Demo purge removes its local gossip and keeps real gossip`() {
        fun fact(id: String, gig: String) = GossipEnvelope(
            id = id, gigId = gig, scope = "scope", author = "$id-author",
            createdAt = 1, expiresAt = 2, kind = "log", line = 0, text = "song",
        )
        val state = PublicGossipState(
            facts = mutableMapOf("demo-fact" to fact("demo-fact", "demo"), "real-fact" to fact("real-fact", "real")),
            localAuthors = mutableSetOf("demo-fact-author", "real-fact-author"),
        )

        val purged = state.withoutGigs(setOf("demo"))

        assertEquals(setOf("real-fact"), purged.facts.keys)
        assertEquals(setOf("real-fact-author"), purged.localAuthors)
    }

    @Test fun `friend gossip fills only the recorded Gap`() {
        val log = StoredLog(songs = listOf("First song", "", "Reply"))

        val filled = log.fillingGapAt(1, "The song the friend knew")

        assertEquals(listOf("First song", "The song the friend knew", "Reply"), filled.songs)
        assertEquals(log, log.fillingGapAt(0, "Must not replace handwriting"))
    }

    @Test fun `setlist fm wins and entered songs and duplicates are removed`() {
        val result = tourSetlistFill(
            setlistFm = listOf("First song", "New One", "new-one", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine"),
            musicBrainz = listOf("Fallback must not appear"),
            entered = listOf("First Song", "Gap filled", "Reply"),
        )

        assertTrue(result.usedSetlistFm)
        assertEquals(listOf("New One", "Two", "Three", "Four", "Five", "Six", "Seven"), result.titles)
        assertFalse("Fallback must not appear" in result.titles)
        assertEquals(10, result.titles.size + 3)
    }

    @Test fun `MusicBrainz fills when setlist fm has nothing`() {
        val result = tourSetlistFill(
            setlistFm = emptyList(),
            musicBrainz = listOf("Entered", "A", "B", "C", "D", "E", "F", "G", "H", "I"),
            entered = listOf("entered", "Known gap"),
        )

        assertFalse(result.usedSetlistFm)
        assertEquals(listOf("A", "B", "C", "D", "E", "F", "G", "H"), result.titles)
        assertEquals(10, result.titles.size + 2)
    }
}
