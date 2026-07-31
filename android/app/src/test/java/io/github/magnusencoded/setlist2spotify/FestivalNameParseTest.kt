package io.github.magnusencoded.setlist2spotify

import io.github.magnusencoded.setlist2spotify.data.setlistfm.parseFestivalName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
