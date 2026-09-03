package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.musicbrainz.dedupe
import io.github.magnusencoded.stationtostation.data.musicbrainz.parseArtists
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

    // --- Ranking candidates against what was written, in isolation ---------------

    /** The case that motivated this, with the real numbers behind it. */
    @Test
    fun `the contained title ranks first by a wide margin`() {
        val catalogue = listOf("High and Apple Sweet", "Vardhavn", "Toothpicks and Gum", "Paper Cranes")
        val ranked = rankTitles("All held together by toothpicks and gum", catalogue)

        assertEquals("Toothpicks and Gum", ranked.first())
        assertEquals("High and Apple Sweet", ranked[1]) // one word shared, and only one
        assertEquals(catalogue.size, ranked.size) // the whole pool stays reachable
    }

    /** Punctuation is thrown away here exactly as it is everywhere recognition happens. */
    @Test
    fun `ranking ignores punctuation and case`() {
        assertEquals(
            "Don't Look Back",
            rankTitles("i think it was dont look back", listOf("Vardhavn", "Don't Look Back")).first(),
        )
    }

    /**
     * Degrades to "nothing confident" rather than promoting a bad match: with no words
     * in common the pool comes back in the order it came in.
     */
    @Test
    fun `a line sharing no words with any title leaves the order alone`() {
        val catalogue = listOf("Vardhavn", "Paper Cranes", "Hollowmoor")
        assertEquals(catalogue, rankTitles("something else entirely", catalogue))
        assertEquals(catalogue, rankTitles("", catalogue))
    }

    /**
     * A title is not "contained" across a word boundary.
     *
     * `songKey` throws spacing away, so on its terms "Sand" sits inside
     * "toothpick*s and* gum" — and containment is worth a whole point, so a
     * two-word coincidence outranked the title the line actually names.
     */
    @Test
    fun `a title spanning two words is not a contained match`() {
        val ranked = rankTitles("All held together by toothpicks and gum", listOf("Sand", "Toothpicks and Gum"))

        assertEquals("Toothpicks and Gum", ranked.first())
    }

    /** The same, with nothing to outrank it: a coincidence must not lead on its own. */
    @Test
    fun `a word-boundary coincidence does not beat a real word match`() {
        val ranked = rankTitles("All held together by toothpicks and gum", listOf("Sand", "Gum"))

        assertEquals("Gum", ranked.first())
    }

    /**
     * Artist completion for a planned gig (#228). The four artists called Nirvana are the
     * reason the disambiguation comes back with the name.
     */
    private val artistPage = """
        {
          "count": 4,
          "artists": [
            { "id": "a", "name": "Nirvana", "score": 74, "disambiguation": "UK band" },
            { "id": "b", "name": "Nirvana", "score": 100, "disambiguation": "US grunge band" },
            { "id": "c", "name": "", "score": 90 },
            { "id": "", "name": "Nirvana 2002", "score": 88 }
          ]
        }
    """.trimIndent()

    @Test
    fun `artists come back best first, with what tells them apart`() {
        val hits = parseArtists(artistPage)

        // The nameless hit and the idless one are both dropped: a row nobody can read is
        // not a suggestion, and one with no id cannot be followed up.
        assertEquals(2, hits.size)
        assertEquals("US grunge band", hits.first().disambiguation)
        assertEquals("b", hits.first().mbid)
    }

    @Test
    fun `a reply that is not what we expected is an empty list, not a crash`() {
        assertTrue(parseArtists("<html>rate limited</html>").isEmpty())
        assertTrue(parseArtists("").isEmpty())
    }
}
