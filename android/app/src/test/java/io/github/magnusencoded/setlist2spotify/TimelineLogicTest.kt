package io.github.magnusencoded.setlist2spotify

import io.github.magnusencoded.setlist2spotify.data.AttendedPage
import io.github.magnusencoded.setlist2spotify.data.LoadedSpine
import io.github.magnusencoded.setlist2spotify.data.TimelineLogic
import io.github.magnusencoded.setlist2spotify.data.TimelinePlumbing
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmArtist
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmSetlist
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmVenue
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
         * Festival name by setlist page url; a url that isn't here fails the
         * scrape, which is the silent case.
         */
        var festivalNames: Map<String, String> = emptyMap()

        /** Pages of an Attended list by username, in page order. */
        var pages: Map<String, List<AttendedPage>> = emptyMap()

        /**
         * What was asked of the device, in order. A call-order rule is exactly what
         * a pure function could not express, so it is asserted directly.
         */
        val calls = mutableListOf<String>()
        val savedFestivalNames = mutableMapOf<String, String>()

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

        override suspend fun festivalName(setlistUrl: String): String? {
            calls += "festivalName($setlistUrl)"
            return festivalNames[setlistUrl]
        }

        override suspend fun saveFestivalNames(names: Map<String, String>) {
            calls += "saveFestivalNames"
            savedFestivalNames += names
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

    /** Two nights at one venue: a **Festival**, as groupIntoFestivals sees it. */
    private fun festivalShows() = listOf(
        show("a", "26-06-2026", venue = "Ekebergsletta"),
        show("b", "25-06-2026", venue = "Ekebergsletta"),
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
            TimelineLogic.playlistName(gig, listOf(gig), emptyMap()),
        )
    }

    @Test
    fun `a festival is named by its festival name with the year stripped`() {
        // The year already leads, so "Tons of Rock 2026" must not repeat it.
        val mine = festivalShows()
        assertEquals(
            "2026 – The Warning – Tons of Rock",
            TimelineLogic.playlistName(mine[1], mine, mapOf(mine[0].id to "Tons of Rock 2026")),
        )
    }

    @Test
    fun `a festival with no resolved name falls back to its venue`() {
        val mine = festivalShows()
        assertEquals(
            "2026 – The Warning – Ekebergsletta",
            TimelineLogic.playlistName(mine[0], mine, emptyMap()),
        )
    }

    @Test
    fun `a name with nothing known is just Setlist`() {
        val gig = FmSetlist(id = "a")
        assertEquals("Setlist", TimelineLogic.playlistName(gig, listOf(gig), emptyMap()))
    }

    // --- The sequence ---
    //
    // Load, then retry the Festival names, then save them. A call-order rule, and
    // the reason this layer is allowed to call plumbing at all.

    @Test
    fun `unresolved festival names are retried on load`() = runBlocking {
        val mine = festivalShows()
        val fake = FakePlumbing()
        fake.stored = LoadedSpine(me = "magnus", mine = mine)
        fake.festivalNames = mapOf(mine[0].url!! to "Tons of Rock 2026")

        val emitted = mutableListOf<LoadedSpine>()
        TimelineLogic(fake).loadSpine("magnus") { emitted += it }

        // Twice: the cached Spine has to be on screen before any network is, so the
        // names cannot be awaited before the first hand-over.
        assertEquals(2, emitted.size)
        assertTrue(emitted[0].festivalNames.isEmpty())
        assertEquals("Tons of Rock 2026", emitted[1].festivalNames[mine[0].id])
        // Paid once: a Festival name costs a fetch each.
        assertEquals(listOf("Tons of Rock 2026"), fake.savedFestivalNames.values.toList())
    }

    @Test
    fun `a festival name already known is not fetched again`() = runBlocking {
        val mine = festivalShows()
        val fake = FakePlumbing()
        fake.stored = LoadedSpine(
            me = "magnus",
            mine = mine,
            festivalNames = mapOf(mine[0].id to "Tons of Rock 2026"),
        )

        val emitted = mutableListOf<LoadedSpine>()
        TimelineLogic(fake).loadSpine("magnus") { emitted += it }

        assertEquals(1, emitted.size)
        assertFalse(fake.calls.any { it.startsWith("festivalName") })
    }

    @Test
    fun `a failed scrape leaves the venue standing and saves nothing`() = runBlocking {
        val fake = FakePlumbing()
        fake.stored = LoadedSpine(me = "magnus", mine = festivalShows())
        // No entry in festivalNames: the scrape came back with nothing.

        val emitted = mutableListOf<LoadedSpine>()
        TimelineLogic(fake).loadSpine("magnus") { emitted += it }

        assertEquals(1, emitted.size)
        assertTrue(fake.savedFestivalNames.isEmpty())
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
