package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.AttendedPage
import io.github.magnusencoded.stationtostation.data.Festivals
import io.github.magnusencoded.stationtostation.data.LoadedSpine
import io.github.magnusencoded.stationtostation.data.StoredFestival
import io.github.magnusencoded.stationtostation.data.TimelineLogic
import io.github.magnusencoded.stationtostation.data.TimelinePlumbing
import io.github.magnusencoded.stationtostation.data.setlistfm.FmArtist
import io.github.magnusencoded.stationtostation.data.setlistfm.ScrapedFestival
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmVenue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The logic layer above the plumbing (ADR-0001). Case for case with iOS's
 * `TimelineLogicTests`: these are the rules the two builds must agree about, so a
 * case added on one side belongs on the other.
 *
 * Every one of them is reachable only because the plumbing is handed in. Before
 * the split, the collaborators were constructed in place, so the rules that had
 * actually broken in the field — a reopened app showing venue names, an empty CI
 * screenshot, playlist naming drifting between the platforms — were exactly the
 * rules nothing could assert.
 */
class TimelineLogicTest {

    // --- The fake device ---
    //
    // The whole test double. If it ever stops being trivial to write, the plumbing
    // interface is wrong.

    private class FakePlumbing : TimelinePlumbing {
        var seeded: LoadedSpine? = null
        var stored: LoadedSpine? = null

        /**
         * What the scrape of a setlist page finds, by url. A url that isn't here is
         * a night at no festival — the common answer, and the one that must be
         * remembered rather than asked again.
         */
        var festivals: Map<String, ScrapedFestival> = emptyMap()

        /** Pages of an Attended list by username, in page order. */
        var pages: Map<String, List<AttendedPage>> = emptyMap()

        /**
         * What was asked of the device, in order. A call-order rule is exactly what
         * a pure function could not express, so it is asserted directly.
         */
        val calls = mutableListOf<String>()
        val savedFestivals = mutableMapOf<String, StoredFestival>()
        val savedMembership = mutableMapOf<String, String>()
        val savedAsked = mutableSetOf<String>()

        override suspend fun seededSpine(): LoadedSpine? {
            calls += "seededSpine"
            return seeded
        }

        override suspend fun storedSpine(me: String): LoadedSpine? {
            calls += "storedSpine"
            return stored
        }

        override suspend fun attendedPage(user: String, page: Int): AttendedPage {
            calls += "attendedPage($user, $page)"
            val all = pages[user].orEmpty()
            return all.getOrElse(page - 1) { AttendedPage(emptyList(), 0) }
        }

        override suspend fun festivalAt(setlistUrl: String): ScrapedFestival? {
            calls += "festivalAt($setlistUrl)"
            return festivals[setlistUrl]
        }

        override suspend fun saveFestivals(
            festivals: Map<String, StoredFestival>,
            idByShow: Map<String, String>,
            asked: Set<String>,
        ) {
            calls += "saveFestivals"
            savedFestivals += festivals
            savedMembership += idByShow
            savedAsked += asked
        }
    }

    private fun show(
        id: String,
        date: String = "25-06-2026",
        venue: String = "Rockefeller",
        artist: String = "The Warning",
    ) = FmSetlist(
        id = id,
        eventDate = date,
        artist = FmArtist(name = artist),
        venue = FmVenue(name = venue),
        url = "https://www.setlist.fm/setlist/$id.html",
    )

    /**
     * Two acts at one venue on one night: a **Section**, and the one shape the
     * resolver asks setlist.fm about. Two *nights* would be two **Nodes** and would
     * never be asked about at all (#166).
     */
    private fun oneEvening() = listOf(
        show("a", "25-06-2026", venue = "Ekebergsletta"),
        show("b", "25-06-2026", venue = "Ekebergsletta", artist = "Gojira"),
    )

    /** The identity that evening turns out to have, as the scrape hands it over. */
    private fun scraped() = ScrapedFestival(
        name = "Tons of Rock 2026",
        slug = "tons-of-rock-2026-6bd52ece",
        rangeFrom = "24-06-2026",
        rangeTo = "27-06-2026",
    )

    /**
     * The same identity, as everything downstream of the resolver holds it. The id is
     * the app's own and any stable string will do — that it is minted from the slug is
     * the store's business, not this layer's.
     */
    private fun identified(shows: List<FmSetlist>, asked: Set<String> = emptySet()) = Festivals(
        byId = mapOf("tor" to StoredFestival(id = "tor", name = "Tons of Rock 2026")),
        idByShow = shows.associate { it.id to "tor" },
        asked = asked,
    )

    // --- The playlist name ---
    //
    // The rule that shipped wrong output from correct sequencing, drifted between
    // the platforms and cost a commit to bring back in line. Asserted here and in
    // iOS's TimelineLogicTests with the same inputs and the same expectations.

    @Test
    fun `a lone show is named year artist venue`() {
        val gig = show("a", "25-06-2026", venue = "Rockefeller")
        assertEquals(
            "2026 – The Warning – Rockefeller",
            TimelineLogic.playlistName(gig, listOf(gig), Festivals()),
        )
    }

    @Test
    fun `a festival is named by its festival name with the year stripped`() {
        // The year already leads, so "Tons of Rock 2026" must not repeat it.
        val mine = oneEvening()
        assertEquals(
            "2026 – The Warning – Tons of Rock",
            TimelineLogic.playlistName(mine[0], mine, identified(mine)),
        )
    }

    @Test
    fun `an evening with no identity is named by its room`() {
        // Which is right *here* and nowhere else: a playlist title says where the
        // night was, and a room is a true answer to that. It is the **Node**'s label
        // that must never be a venue — see billedAs.
        val mine = oneEvening()
        assertEquals(
            "2026 – The Warning – Ekebergsletta",
            TimelineLogic.playlistName(mine[0], mine, Festivals()),
        )
    }

    @Test
    fun `a name with nothing known is just Setlist`() {
        val gig = FmSetlist(id = "a")
        assertEquals("Setlist", TimelineLogic.playlistName(gig, listOf(gig), Festivals()))
    }

    // --- The sequence ---
    //
    // Load, then ask about the evenings nothing has identified, then save the answers.
    // A call-order rule, and the reason this layer is allowed to call plumbing at all.

    @Test
    fun `an unidentified evening is asked about on load`() = runBlocking {
        val mine = oneEvening()
        val fake = FakePlumbing()
        fake.stored = LoadedSpine(me = "magnus", mine = mine)
        fake.festivals = mapOf(mine[0].url!! to scraped())

        val emitted = mutableListOf<LoadedSpine>()
        TimelineLogic(fake).loadSpine("magnus") { emitted += it }

        // Twice: the cached Spine has to be on screen before any network is, so the
        // identities cannot be awaited before the first hand-over.
        assertEquals(2, emitted.size)
        assertTrue(emitted[0].festivals.isEmpty())
        assertEquals("Tons of Rock 2026", emitted[1].festivals.of("a")?.name)
        // The range is the festival's own, not the nights I happened to attend.
        assertEquals("24-06-2026", emitted[1].festivals.of("a")?.rangeFrom)
        // Paid once: an identity costs a fetch, so it is saved the moment it lands.
        assertEquals(listOf("Tons of Rock 2026"), fake.savedFestivals.values.map { it.name })
    }

    @Test
    fun `an evening already identified is not asked about again`() = runBlocking {
        val mine = oneEvening()
        val fake = FakePlumbing()
        fake.stored = LoadedSpine(me = "magnus", mine = mine, festivals = identified(mine))

        val emitted = mutableListOf<LoadedSpine>()
        TimelineLogic(fake).loadSpine("magnus") { emitted += it }

        assertEquals(1, emitted.size)
        assertFalse(fake.calls.any { it.startsWith("festivalAt") })
    }

    @Test
    fun `a night at no festival is asked about once, ever`() = runBlocking {
        // The Sentrum Scene case: a headline show with support looks exactly like a
        // festival day in the data, so it has to be asked about — and "no festival"
        // is a real answer worth keeping. 44 nights on the line are shaped like that
        // one, and re-asking on every launch is 44 fetches for nothing.
        val mine = oneEvening()
        val fake = FakePlumbing()
        val logic = TimelineLogic(fake)

        val first = logic.resolveFestivals(mine, Festivals())
        assertTrue(first.isEmpty())
        // The evening, not the act: one page answers for the whole night.
        assertEquals(setOf("a", "b"), fake.savedAsked)

        fake.calls.clear()
        logic.resolveFestivals(mine, first)
        assertFalse(fake.calls.any { it.startsWith("festivalAt") })
    }

    @Test
    fun `two nights at one venue are never asked about at all`() = runBlocking {
        // They are two Nodes now. Nothing on the Line claims they are one thing, so
        // there is nothing to look up: the four-day window is gone from the seam and
        // from what it costs.
        val mine = listOf(
            show("a", "26-06-2026", venue = "Ekebergsletta"),
            show("b", "25-06-2026", venue = "Ekebergsletta"),
        )
        val fake = FakePlumbing()
        TimelineLogic(fake).resolveFestivals(mine, Festivals())
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `the source's own day grouping decides membership, not my attendance`() = runBlocking {
        // I went for one day; the festival did not become one day long, and the acts
        // I did not see still belong to it.
        val mine = oneEvening()
        val fake = FakePlumbing()
        fake.festivals = mapOf(
            mine[0].url!! to scraped().copy(
                dayMembership = mapOf("26-06-2026" to listOf("elsewhere")),
            ),
        )

        val found = TimelineLogic(fake).resolveFestivals(mine, Festivals())
        assertEquals("Tons of Rock 2026", found.of("elsewhere")?.name)
        assertEquals(found.of("a")?.id, found.of("elsewhere")?.id)
    }

    @Test
    fun `a seeded fixture is the spine and the store is never read`() = runBlocking {
        val fake = FakePlumbing()
        fake.seeded = LoadedSpine(me = "dizzi90", mine = listOf(show("fixture")))
        // In CI the stored cache is empty, which is how a screenshot came back
        // blank; here it holds something else entirely, so a read would show.
        fake.stored = LoadedSpine(me = "magnus", mine = listOf(show("cached")))

        val emitted = mutableListOf<LoadedSpine>()
        TimelineLogic(fake).loadSpine("magnus") { emitted += it }

        assertEquals(1, emitted.size)
        assertEquals(listOf("fixture"), emitted[0].mine.map { it.id })
        assertFalse("storedSpine" in fake.calls)
    }

    @Test
    fun `nothing stored yet leaves the screen alone`() = runBlocking {
        val emitted = mutableListOf<LoadedSpine>()
        TimelineLogic(FakePlumbing()).loadSpine("magnus") { emitted += it }
        assertTrue(emitted.isEmpty())
    }

    // --- Shared concerts ---

    @Test
    fun `shared concerts are the intersection of two attended lists`() = runBlocking {
        val fake = FakePlumbing()
        fake.pages = mapOf(
            "magnus" to listOf(AttendedPage(listOf(show("a"), show("b"), show("c")), 3)),
            "Ozzy" to listOf(AttendedPage(listOf(show("b"), show("c"), show("d")), 3)),
        )
        val shared = TimelineLogic(fake).sharedConcerts("magnus", "Ozzy")
        // Mine keeps its order, so the result is newest first like every list.
        assertEquals(listOf("b", "c"), shared.map { it.id })
    }

    @Test
    fun `attended paging stops at the named cap`() = runBlocking {
        val fake = FakePlumbing()
        // Ten pages available and a total nobody will reach: only the guard stops it.
        val deep = (1..10).map { page -> AttendedPage(listOf(show("p$page")), 999) }
        fake.pages = mapOf("magnus" to deep, "Ozzy" to deep)
        TimelineLogic(fake).sharedConcerts("magnus", "Ozzy")

        val asked = fake.calls.filter { it.startsWith("attendedPage(magnus") }
        assertEquals(TimelineLogic.ATTENDED_PAGE_CAP, asked.size)
    }

    @Test
    fun `attended paging stops early once the total is in hand`() = runBlocking {
        val fake = FakePlumbing()
        fake.pages = mapOf(
            "magnus" to listOf(AttendedPage(listOf(show("a")), 1), AttendedPage(listOf(show("never")), 1)),
            "Ozzy" to listOf(AttendedPage(listOf(show("a")), 1)),
        )
        val shared = TimelineLogic(fake).sharedConcerts("magnus", "Ozzy")
        assertEquals(listOf("a"), shared.map { it.id })
        assertEquals(1, fake.calls.count { it.startsWith("attendedPage(magnus") })
    }
}
