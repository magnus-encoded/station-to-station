package io.github.magnusencoded.setlist2spotify

import io.github.magnusencoded.setlist2spotify.data.StoredLog
import io.github.magnusencoded.setlist2spotify.data.billNight
import io.github.magnusencoded.setlist2spotify.data.candidateSongs
import io.github.magnusencoded.setlist2spotify.data.fmDate
import io.github.magnusencoded.setlist2spotify.data.isLocal
import io.github.magnusencoded.setlist2spotify.data.localGigSetlist
import io.github.magnusencoded.setlist2spotify.data.parseFmDate
import io.github.magnusencoded.setlist2spotify.data.parseLineup
import io.github.magnusencoded.setlist2spotify.data.setlistEditEntry
import io.github.magnusencoded.setlist2spotify.data.setlistPaste
import io.github.magnusencoded.setlist2spotify.data.SETLISTFM_ADD_URL
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmSet
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmSetlist
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmSets
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmSong
import io.github.magnusencoded.setlist2spotify.ui.GigLeaf
import io.github.magnusencoded.setlist2spotify.ui.gigLeaf
import io.github.magnusencoded.setlist2spotify.ui.nightWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The real thing this was built for, so the fixtures are its actual shape. */
private const val RINGNES = """Cowboyfrokost TRIO
Du&Du
Silent Majority
?Bent Sæther"""

class BillTest {

    // --- The lineup, as it is actually pasted in --------------------------------

    @Test
    fun `a pasted lineup becomes acts in poster order`() {
        val acts = parseLineup(RINGNES)
        assertEquals(listOf("Cowboyfrokost TRIO", "Du&Du", "Silent Majority", "Bent Sæther"), acts.map { it.name })
    }

    @Test
    fun `a leading question mark is the poster's own hedge, and only that act's`() {
        val acts = parseLineup(RINGNES)
        assertEquals(listOf(false, false, false, true), acts.map { it.maybe })
    }

    @Test
    fun `no act arrives with a night — that is the whole point`() {
        assertTrue(parseLineup(RINGNES).all { it.gigId == null })
    }

    @Test
    fun `blank lines, bullets and repeats are dropped`() {
        val acts = parseLineup("- Villskudd\n\n  \n• Villskudd\n* Enok Monk\n")
        assertEquals(listOf("Villskudd", "Enok Monk"), acts.map { it.name })
    }

    // --- The night an act tapped in the field belongs to -------------------------

    @Test
    fun `an act tapped during the evening is tonight`() {
        assertEquals(
            LocalDate.of(2026, 8, 6),
            billNight(LocalDate.of(2026, 8, 6).atTime(22, 30)),
        )
    }

    @Test
    fun `an act tapped walking out at half one is still last night`() {
        assertEquals(
            LocalDate.of(2026, 8, 6),
            billNight(LocalDate.of(2026, 8, 7).atTime(1, 30)),
        )
    }

    @Test
    fun `six in the morning is a new day, the same edge check-in draws`() {
        assertEquals(
            LocalDate.of(2026, 8, 7),
            billNight(LocalDate.of(2026, 8, 7).atTime(6, 0)),
        )
    }

    // --- The Gig an Act becomes -------------------------------------------------

    @Test
    fun `a local gig carries its own id and no setlist-fm page`() {
        val gig = localGigSetlist("local-1", "Silent Majority", LocalDate.of(2026, 8, 6), "Ringnes Festival 2026", "Norway")
        assertEquals("local-1", gig.id)
        assertEquals("06-08-2026", gig.eventDate)
        assertEquals("Silent Majority", gig.artist?.name)
        assertEquals("Ringnes Festival 2026", gig.venue?.name)
        assertNull(gig.url)
        assertTrue(gig.isLocal())
    }

    @Test
    fun `a local gig never carries songs — those are the Log's, not a setlist's`() {
        val gig = localGigSetlist("local-1", "Villskudd", LocalDate.of(2026, 8, 7), "Ringnes", "")
        assertTrue(gig.performed().isEmpty())
        assertNull(gig.sets)
    }

    @Test
    fun `two acts on the same night at one venue are a festival's worth of gigs`() {
        // The payoff of dating acts rather than inventing dates: once they are real
        // nights they cluster on venue and date exactly like any other run of shows.
        val a = localGigSetlist("a", "Du&Du", LocalDate.of(2026, 8, 6), "Ringnes Festival 2026", "")
        val b = localGigSetlist("b", "Villskudd", LocalDate.of(2026, 8, 8), "Ringnes Festival 2026", "")
        val nodes = io.github.magnusencoded.setlist2spotify.ui.groupIntoFestivals(listOf(b, a))
        assertEquals(1, nodes.size)
        assertTrue(nodes.first() is io.github.magnusencoded.setlist2spotify.ui.TimelineNode.Festival)
    }

    // --- The Log: what I saw, and what it admits about itself --------------------

    @Test
    fun `a log starts Open — a capture built from prompts is never complete by default`() {
        assertFalse(StoredLog().closed)
    }

    @Test
    fun `a gap is in the record but is not a title`() {
        val log = StoredLog(songs = listOf("Ei vise", "", "Siste dans"))
        assertEquals(listOf("Ei vise", "Siste dans"), log.named())
        assertEquals(1, log.gaps)
    }

    @Test
    fun `the paste is bare titles, one per line, in the order they were played`() {
        val log = StoredLog(songs = listOf("Second", "First", "Second"))
        assertEquals("Second\nFirst\nSecond", setlistPaste(log))
    }

    @Test
    fun `a gap pastes as setlist-fm's own unknown marker, never as nothing`() {
        // Dropping it would publish a set silently claiming that song was not played.
        assertEquals("A\n@Unknown[]\nB", setlistPaste(StoredLog(songs = listOf("A", "  ", "B"))))
    }

    @Test
    fun `an empty log pastes to nothing rather than to a fabricated set`() {
        assertEquals("", setlistPaste(StoredLog()))
    }

    // --- Where the Historian is sent -------------------------------------------

    @Test
    fun `a night setlist-fm already has goes to its own page, never a built edit url`() {
        val known = FmSetlist(id = "63a80d2f", url = "https://www.setlist.fm/setlist/x-63a80d2f.html")
        assertEquals("https://www.setlist.fm/setlist/x-63a80d2f.html", setlistEditEntry(known))
    }

    @Test
    fun `a night setlist-fm has never heard of goes to the add flow`() {
        val stub = localGigSetlist("local-1", "Enok Monk", LocalDate.of(2026, 8, 7), "Ringnes", "")
        assertEquals(SETLISTFM_ADD_URL, setlistEditEntry(stub))
    }

    // --- Candidate songs: a prompt, never a claim -------------------------------

    @Test
    fun `candidates rank by how often the artist has been playing them`() {
        fun set(vararg songs: String) =
            FmSetlist(sets = FmSets(listOf(FmSet(song = songs.map { FmSong(name = it) }))))
        val songs = candidateSongs(listOf(set("Core", "Rare"), set("Core", "Other"), set("Core")))
        assertEquals("Core", songs.first())
        assertEquals(setOf("Core", "Rare", "Other"), songs.toSet())
    }

    @Test
    fun `an artist setlist-fm has never heard of yields an empty pool, not an error`() {
        // Half the Ringnes lineup. The typing path is not a fallback for this — it is
        // the ordinary case, which is why it is always on screen.
        assertEquals(emptyList<String>(), candidateSongs(emptyList()))
    }

    // --- Which action leads, on the clock ---------------------------------------

    private val night = nightWindow(LocalDate.of(2026, 8, 6))

    @Test
    fun `before the night, the leaf is still planning`() {
        assertEquals(GigLeaf.PLAN, gigLeaf(LocalDate.of(2026, 8, 5).atTime(20, 0), night))
    }

    @Test
    fun `during the night the leaf is capture, and stays capture past midnight`() {
        assertEquals(GigLeaf.CAPTURE, gigLeaf(LocalDate.of(2026, 8, 6).atTime(21, 0), night))
        assertEquals(GigLeaf.CAPTURE, gigLeaf(LocalDate.of(2026, 8, 7).atTime(2, 0), night))
    }

    @Test
    fun `a check-in opens capture even before the window says the night has started`() {
        val early = LocalDate.of(2026, 8, 5).atTime(23, 0)
        assertEquals(GigLeaf.PLAN, gigLeaf(early, night))
        assertEquals(GigLeaf.CAPTURE, gigLeaf(early, night, checkedIn = true))
    }

    @Test
    fun `after the window closes the leaf becomes publish — but only the leaf`() {
        assertEquals(GigLeaf.PUBLISH, gigLeaf(LocalDate.of(2026, 8, 7).atTime(11, 0), night))
        // And a check-in cannot drag it back: being there yesterday does not reopen
        // the night. The Log itself stays editable regardless — that is the screen's
        // rule, not this function's, precisely because it is not conditional.
        assertEquals(
            GigLeaf.PUBLISH,
            gigLeaf(LocalDate.of(2026, 8, 7).atTime(11, 0), night, checkedIn = true),
        )
    }

    @Test
    fun `an undated gig has no clock to follow and keeps the plan-ahead leaf`() {
        assertEquals(GigLeaf.PLAN, gigLeaf(LocalDate.of(2026, 8, 6).atTime(21, 0), null))
    }

    // --- Dates ------------------------------------------------------------------

    @Test
    fun `dates round-trip in the shape setlist-fm sends`() {
        assertEquals(LocalDate.of(2026, 8, 6), parseFmDate(fmDate(LocalDate.of(2026, 8, 6))))
        assertNull(parseFmDate("6 August"))
    }
}
