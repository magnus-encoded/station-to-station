package io.github.magnusencoded.setlist2spotify

import io.github.magnusencoded.setlist2spotify.data.setlistfm.parseFestivalName
import io.github.magnusencoded.setlist2spotify.data.setlistfm.parseSetlistId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A gig you're going to arrives as a pasted link, because setlist.fm's search index
 * stops about a day out and the id in that url is the only way to ask for the gig.
 */
class SetlistIdParseTest {

    @Test
    fun `reads the id out of a setlist page url`() {
        assertEquals(
            "53414fd1",
            parseSetlistId("https://www.setlist.fm/setlist/nick-cave-and-the-bad-seeds/2026/toyenparken-oslo-norway-53414fd1.html"),
        )
    }

    @Test
    fun `reads the id out of an upcoming page url — where a future gig actually lives`() {
        assertEquals(
            "53414fd1",
            parseSetlistId("https://www.setlist.fm/upcoming/nick-cave-and-the-bad-seeds/2026/toyenparken-oslo-norway-53414fd1.html"),
        )
    }

    @Test
    fun `takes a bare id, and trims what the clipboard added`() {
        assertEquals("53414fd1", parseSetlistId("  53414fd1\n"))
    }

    @Test
    fun `an artist page is not a gig, even though its url ends the same way`() {
        // Taking this id would fetch some unrelated setlist and call it the show
        // the user was looking at — worse than refusing the link.
        assertNull(parseSetlistId("https://www.setlist.fm/setlists/nick-cave-and-the-bad-seeds-23d6a877.html"))
    }

    @Test
    fun `a venue page is not a gig either`() {
        assertNull(parseSetlistId("https://www.setlist.fm/venue/toyenparken-oslo-norway-63d41af7.html"))
    }

    @Test
    fun `nonsense is nothing`() {
        assertNull(parseSetlistId("Øyafestivalen"))
        assertNull(parseSetlistId(""))
    }
}

/** The festival name is scraped from the setlist page, so pin the shape we rely on. */
class FestivalNameParseTest {

    @Test
    fun `reads the festival name from the played-at link`() {
        val html = """
            <div class="festivalBg"><h2 class="festivalHeadline">Hey, this setlist was played at a festival:</h2>
            <a class="nested" href="../../../festival/2025/oyafestivalen-2025-73d58625.html"
               title="View Øyafestivalen 2025 details">Øyafestivalen 2025</a></div>
        """.trimIndent()
        assertEquals("Øyafestivalen 2025", parseFestivalName(html))
    }

    @Test
    fun `a plain club show has no festival link`() {
        assertNull(parseFestivalName("<html><body>Blå, Oslo, Norway</body></html>"))
    }
}
