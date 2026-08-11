package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.musicbrainz.dedupe
import io.github.magnusencoded.stationtostation.data.musicbrainz.parseRecordings
import io.github.magnusencoded.stationtostation.data.rankTitles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalogue an artist's own songs come from (#126). Parsing is kept apart from
 * fetching, so the shape of a reply is asserted without a socket.
 *
 * The titles here are the real ones for the artist that motivated this, because the
 * ranking has to be checked against the actual case. No personal data is involved: a
 * band's song titles are a published catalogue.
 */
class MusicBrainzTest {

    private val page = """
        {
          "recording-count": 3,
          "recording-offset": 0,
          "recordings": [
            { "id": "1", "title": "Toothpicks and Gum", "length": 214000 },
            { "id": "2", "title": "High and Apple Sweet" },
            { "id": "3", "title": "Between Stations" }
          ]
        }
    """.trimIndent()

    @Test
    fun `a page of recordings reads as titles and a total`() {
        val parsed = parseRecordings(page)
        assertEquals(3, parsed.count)
        assertEquals(
            listOf("Toothpicks and Gum", "High and Apple Sweet", "Between Stations"),
            parsed.recordings.map { it.title },
        )
    }

    /** A reply we cannot read is an empty catalogue, never an exception on a gig screen. */
    @Test
    fun `an unreadable reply is an empty page`() {
        assertEquals(0, parseRecordings("<html>rate limited</html>").count)
        assertTrue(parseRecordings("").recordings.isEmpty())
        assertTrue(parseRecordings("{}").recordings.isEmpty())
    }

    /**
     * MusicBrainz lists every *recording*: a studio take, a live take and a remaster are
     * three rows and one song. Raw, the panel would offer the same title three times and
     * bury the rest.
     */
    @Test
    fun `one entry per song, not per recording`() {
        val titles = listOf(
            "Toothpicks and Gum",
            "Toothpicks and Gum",
            "toothpicks and gum",
            "Between Stations",
        )
        assertEquals(listOf("Toothpicks and Gum", "Between Stations"), dedupe(titles))
    }

    /** Same normalisation as recognition everywhere else, and the first spelling wins. */
    @Test
    fun `punctuation does not make a second entry`() {
        assertEquals(listOf("Don't Look Back"), dedupe(listOf("Don't Look Back", "Dont Look Back")))
        assertTrue(dedupe(listOf("", "  ")).isEmpty())
    }

    /**
     * The whole point of the second source, asserted end to end from the parse: the pool
     * setlist.fm could offer for this artist is empty, and the catalogue puts the right
     * answer first.
     */
    @Test
    fun `the catalogue answers the case the setlist pool could not`() {
        val fromSetlistFm = emptyList<String>()
        val catalogue = dedupe(parseRecordings(page).recordings.map { it.title })

        val ranked = rankTitles(
            "All held together by toothpicks and gum",
            (fromSetlistFm + catalogue).distinctBy { it.lowercase() },
        )

        assertEquals("Toothpicks and Gum", ranked.first())
        assertEquals(3, ranked.size)
    }
}
