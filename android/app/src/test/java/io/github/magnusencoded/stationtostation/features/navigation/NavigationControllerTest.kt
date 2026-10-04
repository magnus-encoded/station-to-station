package io.github.magnusencoded.stationtostation.features.navigation

import io.github.magnusencoded.stationtostation.GigLink
import io.github.magnusencoded.stationtostation.LinkIntent
import io.github.magnusencoded.stationtostation.LinkScreen
import io.github.magnusencoded.stationtostation.UiState
import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.features.FakeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NavigationControllerTest {

    private val fake = FakeState()
    private val fetched = mutableListOf<String>()
    private val saved = mutableListOf<Map<String, Long>>()
    private val refreshed = mutableListOf<Friend>()
    private val logWrites = mutableListOf<String>()
    private val failures = mutableListOf<Exception>()
    private var fetchFails: Exception? = null

    private val nav = NavigationController(
        state = fake.state,
        update = fake.update,
        scope = CoroutineScope(Dispatchers.Unconfined),
        fetchSetlist = { id ->
            fetchFails?.let { throw it }
            fetched += id
            FmSetlist(id = id)
        },
        saveHiddenLines = { saved += it },
        resolveFestivalsFor = { _, known -> known },
        refreshLine = { refreshed += it },
        writeLog = { id, _ -> logWrites += id },
        fail = { failures += it },
        now = { 42L },
    )

    private val friend = Friend(setlistfm = "ann")

    @Test
    fun `the weave does not open when there is no Line to weave`() {
        nav.setZoomedOut(true)
        assertFalse(fake.value.zoomedOut)
    }

    @Test
    fun `the weave opens and closes once there is a followed Line`() {
        fake.value = UiState(friends = listOf(friend))
        nav.setZoomedOut(true)
        assertTrue(fake.value.zoomedOut)
        nav.setZoomedOut(false)
        assertFalse(fake.value.zoomedOut)
    }

    @Test
    fun `timeline link shows my single Line`() {
        fake.value = UiState(friends = listOf(friend), zoomedOut = true)
        nav.handleLink(LinkIntent.Open(LinkScreen.TIMELINE))
        assertEquals(LinkScreen.TIMELINE, fake.value.linkScreen)
        assertFalse(fake.value.zoomedOut)
    }

    @Test
    fun `timelines link zooms out only with someone to weave`() {
        nav.handleLink(LinkIntent.Open(LinkScreen.TIMELINES))
        assertFalse(fake.value.zoomedOut)
        fake.value = UiState(friends = listOf(friend))
        nav.handleLink(LinkIntent.Open(LinkScreen.TIMELINES, "2026-09-29"))
        assertTrue(fake.value.zoomedOut)
        assertEquals(LocalDate.of(2026, 9, 29), fake.value.linkedDate)
    }

    @Test
    fun `settings link leaves the zoom as it is`() {
        fake.value = UiState(friends = listOf(friend), zoomedOut = true)
        nav.handleLink(LinkIntent.Open(LinkScreen.SETTINGS))
        assertEquals(LinkScreen.SETTINGS, fake.value.linkScreen)
        assertTrue(fake.value.zoomedOut)
    }

    @Test
    fun `add-gig link pre-fills the form and goes to the timeline`() {
        nav.handleLink(LinkIntent.AddGig("Kvelertak", null, null))
        val link = fake.value.addGigLink!!
        assertEquals("Kvelertak", link.artist)
        assertEquals("", link.venue)
        assertEquals("", link.date)
        assertEquals(LinkScreen.TIMELINE, fake.value.linkScreen)
    }

    @Test
    fun `a Gig on my Line opens without fetching`() {
        fake.value = UiState(setlists = listOf(FmSetlist(id = "334c742d")))
        nav.handleLink(LinkIntent.OpenGig("334c742d"))
        assertEquals("334c742d", fake.value.linkedGig)
        assertEquals(GigLink.SETLIST, fake.value.linkedGigAs)
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun `an unknown setlist id is fetched and shown without being kept`() {
        nav.handleLink(LinkIntent.OpenGig("334c742d"))
        assertEquals(listOf("334c742d"), fetched)
        assertEquals("334c742d", fake.value.selectedSetlist?.id)
        assertEquals("334c742d", fake.value.linkedGig)
        assertTrue(fake.value.setlists.isEmpty())
    }

    @Test
    fun `a failed fetch reports and opens nothing`() {
        fetchFails = RuntimeException("offline")
        nav.handleLink(LinkIntent.OpenGig("334c742d"))
        assertEquals(1, failures.size)
        assertNull(fake.value.linkedGig)
    }

    @Test
    fun `an id that is not a setlist id is refused`() {
        nav.handleLink(LinkIntent.OpenGig("not a gig"))
        assertEquals("That doesn't look like a setlist.fm gig link.", fake.value.error)
        assertNull(fake.value.linkedGig)
    }

    @Test
    fun `write-to-log writes after the Gig is open`() {
        fake.value = UiState(setlists = listOf(FmSetlist(id = "334c742d")))
        nav.handleLink(LinkIntent.WriteToLog("334c742d", listOf("Song"), emptyMap()))
        assertEquals(listOf("334c742d"), logWrites)
        assertEquals("334c742d", fake.value.linkedGig)
    }

    @Test
    fun `a legacy woven place link zooms out and links the Gig`() {
        fake.value = UiState(friends = listOf(friend))
        nav.handleLink(LinkIntent.LegacyPlace("334c742d", GigLink.WOVEN))
        assertTrue(fake.value.zoomedOut)
        assertEquals(GigLink.WOVEN, fake.value.linkedGigAs)
    }

    @Test
    fun `a legacy me link is the timeline link`() {
        nav.handleLink(LinkIntent.LegacyMe)
        assertEquals(LinkScreen.TIMELINE, fake.value.linkScreen)
    }

    @Test
    fun `a fixture link changes nothing`() {
        nav.handleLink(LinkIntent.LegacyFixture("x", true))
        assertEquals(UiState(), fake.value)
    }

    @Test
    fun `each consume clears only its own link`() {
        fake.value = UiState(
            linkScreen = LinkScreen.TIMELINE,
            linkedGig = "a",
            linkedGigAs = GigLink.SETLIST,
            linkedDate = LocalDate.of(2026, 1, 1),
        )
        nav.consumeLinkScreen()
        assertNull(fake.value.linkScreen)
        assertEquals("a", fake.value.linkedGig)
        nav.consumeLinkedDate()
        assertNull(fake.value.linkedDate)
        nav.consumeGigLink()
        assertNull(fake.value.linkedGig)
        assertNull(fake.value.linkedGigAs)
    }

    @Test
    fun `festivals toggle and open in place`() {
        nav.toggleFestival("f")
        assertEquals(setOf("f"), fake.value.openFestivals)
        nav.toggleFestival("f")
        assertTrue(fake.value.openFestivals.isEmpty())
        nav.openFestival("f")
        nav.openFestival("f")
        assertEquals(setOf("f"), fake.value.openFestivals)
    }

    @Test
    fun `hiding a Line records the moment and saves it`() {
        fake.value = UiState(friends = listOf(friend))
        nav.toggleLineHidden("ann")
        assertEquals(mapOf("ann" to 42L), fake.value.hiddenAt)
        assertEquals(listOf(mapOf("ann" to 42L)), saved)
        assertTrue(refreshed.isEmpty())
    }

    @Test
    fun `showing a hidden Line refreshes it`() {
        fake.value = UiState(friends = listOf(friend), hiddenAt = mapOf("ann" to 1L))
        nav.toggleLineHidden("ann")
        assertTrue(fake.value.hiddenAt.isEmpty())
        assertEquals(listOf(friend), refreshed)
    }

    @Test
    fun `a known Gig is found on my Line, a plan, a lane or the open show`() {
        fake.value = UiState(
            setlists = listOf(FmSetlist(id = "a")),
            plannedGigs = listOf(FmSetlist(id = "b")),
            showsByFriend = mapOf("ann" to listOf(FmSetlist(id = "c"))),
            selectedSetlist = FmSetlist(id = "d"),
        )
        listOf("a", "b", "c", "d").forEach { assertEquals(it, nav.knownGig(it)?.id) }
        assertNull(nav.knownGig("e"))
    }

    @Test
    fun `opening a show selects it`() {
        nav.openShow(FmSetlist(id = "a"))
        assertEquals("a", fake.value.selectedSetlist?.id)
    }
}
