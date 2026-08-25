package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.setlistfm.parseFestivalLink
import io.github.magnusencoded.stationtostation.data.setlistfm.parseFestivalName
import io.github.magnusencoded.stationtostation.data.setlistfm.parseFestivalPage
import io.github.magnusencoded.stationtostation.data.setlistfm.parseSetlistId
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

    /**
     * The identity, not just the label (#166). The slug in the href is setlist.fm's own
     * key for the festival — the same one across every act and every year's edition —
     * so it, and never the name, is what a stored identity is derived from.
     */
    @Test
    fun `reads the identity out of the href, not just the label`() {
        val festival = parseFestivalLink(
            """<a class="nested" href="../../../festival/2025/oyafestivalen-2025-73d58625.html"
               title="View Øyafestivalen 2025 details">Øyafestivalen 2025</a>""",
        )
        assertEquals("oyafestivalen-2025-73d58625", festival?.slug)
        assertEquals("../../../festival/2025/oyafestivalen-2025-73d58625.html", festival?.href)
    }

    @Test
    fun `a plain club show has no festival link`() {
        assertNull(parseFestivalName("<html><body>Blå, Oslo, Norway</body></html>"))
        assertNull(parseFestivalLink("<html><body>Blå, Oslo, Norway</body></html>"))
    }
}

/**
 * The festival page — the only place the range, the day grouping and the set times
 * live. Three facts, read one at a time: a redesign upstream must cost the field it
 * touched and never the night (ADR-0004).
 *
 * Trimmed from the real page, 25 August 2026.
 */
class FestivalPageParseTest {

    private val page = """
        <div class="condensed dateBlock dtstart">
          <span class="value-title" title="2026-06-24"></span>
          <span class="month">Jun</span><span class="day">24</span>
        </div>
        <span>Wed June 24, 2026 - Sat June 27, 2026</span>
        <p class="FestivalSetlistsGroupedVenueDayBySubVenue-eventDate x1">Wednesday, June 24, 2026</p>
        <div class="FestivalSetlistListItem-root">
          <div class="FestivalSetlistListItem-scheduledStart"><p>2:00 pm</p></div>
          <a href="/setlist/gojira/2026/ekebergsletta-oslo-norway-1ba2c3d4.html">Gojira</a>
        </div>
        <div class="FestivalSetlistListItem-root">
          <div class="FestivalSetlistListItem-scheduledStart"><p>10:30 pm</p></div>
          <a href="/setlist/ghost/2026/ekebergsletta-oslo-norway-2ba2c3d4.html">Ghost</a>
        </div>
        <p class="FestivalSetlistsGroupedVenueDayBySubVenue-eventDate x1">Thursday, June 25, 2026</p>
        <div class="FestivalSetlistListItem-root">
          <a href="/setlist/turnstile/2026/ekebergsletta-oslo-norway-3ba2c3d4.html">Turnstile</a>
        </div>
    """.trimIndent()

    @Test
    fun `reads the range the festival ran over`() {
        val f = parseFestivalPage(page)
        assertEquals("24-06-2026", f.rangeFrom)
        assertEquals("27-06-2026", f.rangeTo)
    }

    /**
     * Membership is the source's own day grouping, not my attendance and not a window
     * drawn over dates — which is the whole of #166 in one field.
     */
    @Test
    fun `reads the source's own day grouping`() {
        assertEquals(
            mapOf(
                "24-06-2026" to listOf("1ba2c3d4", "2ba2c3d4"),
                "25-06-2026" to listOf("3ba2c3d4"),
            ),
            parseFestivalPage(page).dayMembership,
        )
    }

    /** Twelve-hour on the page, twenty-four here, so the latest set also sorts last. */
    @Test
    fun `reads the published start times, for the acts that have one`() {
        val times = parseFestivalPage(page).setTimes
        assertEquals("14:00", times?.get("1ba2c3d4"))
        assertEquals("22:30", times?.get("2ba2c3d4"))
        assertNull(times?.get("3ba2c3d4")) // no scheduled start published
    }

    /** One field going missing must not take the others with it. */
    @Test
    fun `a page with no times still yields its days`() {
        val f = parseFestivalPage(page.replace("FestivalSetlistListItem-scheduledStart", "somethingElse"))
        assertNull(f.setTimes)
        assertEquals(2, f.dayMembership?.size)
    }

    @Test
    fun `a page shaped nothing like a festival yields nothing rather than nonsense`() {
        val f = parseFestivalPage("<html><body>Not a festival page at all.</body></html>")
        assertNull(f.rangeFrom)
        assertNull(f.rangeTo)
        assertNull(f.dayMembership)
        assertNull(f.setTimes)
    }
}
