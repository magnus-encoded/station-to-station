package io.github.magnusencoded.setlist2spotify

import io.github.magnusencoded.setlist2spotify.data.BillWhen
import io.github.magnusencoded.setlist2spotify.data.StoredBill
import io.github.magnusencoded.setlist2spotify.data.StoredLog
import io.github.magnusencoded.setlist2spotify.data.artistLabel
import io.github.magnusencoded.setlist2spotify.data.billNight
import io.github.magnusencoded.setlist2spotify.data.billWhen
import io.github.magnusencoded.setlist2spotify.data.gigNight
import io.github.magnusencoded.setlist2spotify.data.nights
import io.github.magnusencoded.setlist2spotify.data.candidateSongs
import io.github.magnusencoded.setlist2spotify.data.filingFields
import io.github.magnusencoded.setlist2spotify.data.FutureRow
import io.github.magnusencoded.setlist2spotify.data.fmDate
import io.github.magnusencoded.setlist2spotify.data.futureRows
import io.github.magnusencoded.setlist2spotify.data.isLocal
import io.github.magnusencoded.setlist2spotify.data.localGigSetlist
import io.github.magnusencoded.setlist2spotify.data.parseFmDate
import io.github.magnusencoded.setlist2spotify.data.parseLineup
import io.github.magnusencoded.setlist2spotify.data.playsSong
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

    // --- A Bill knows when it is (#135) -----------------------------------------

    // A festival shaped like the one this was built for — three nights, invented
    // names — because the case that broke was a Bill read on the day after it ended.
    private val threeNights = StoredBill(
        id = "nordlys",
        name = "Nordlys Fields 2026",
        city = "Kalmarhavn",
        from = "06-08-2026",
        to = "09-08-2026",
        acts = parseLineup("Paper Cranes\nVelvet Ditch"),
    )

    @Test
    fun `a bill's nights are every day of its range, ends included`() {
        assertEquals(
            listOf(6, 7, 8, 9).map { LocalDate.of(2026, 8, it) },
            threeNights.nights(),
        )
    }

    @Test
    fun `a bill with no dates typed in has no nights, and no range to argue with`() {
        assertEquals(emptyList<LocalDate>(), StoredBill(name = "Nordlys Fields").nights())
    }

    @Test
    fun `where the clock stands relative to the range is the whole question`() {
        assertEquals(BillWhen.BEFORE, billWhen(threeNights, LocalDate.of(2026, 8, 5).atTime(20, 0)))
        assertEquals(BillWhen.DURING, billWhen(threeNights, LocalDate.of(2026, 8, 7).atTime(22, 30)))
        assertEquals(BillWhen.AFTER, billWhen(threeNights, LocalDate.of(2026, 8, 10).atTime(12, 0)))
    }

    @Test
    fun `walking out of the last night at half one is still during the festival`() {
        // NIGHT_ENDS decides which night it is, and this reads the same boundary: the
        // festival is not over while its last night is still going on.
        assertEquals(BillWhen.DURING, billWhen(threeNights, LocalDate.of(2026, 8, 10).atTime(1, 30)))
    }

    @Test
    fun `inside the range the clock still dates the gig, exactly as it always did`() {
        val now = LocalDate.of(2026, 8, 7).atTime(22, 30)
        assertEquals(billNight(now), gigNight(threeNights, chosen = null, now = now))
        assertEquals(LocalDate.of(2026, 8, 7), gigNight(threeNights, null, now))
    }

    @Test
    fun `after the festival the clock cannot date anything — it has to be asked`() {
        // The field report: Nordlys Fields ran 6–9 August, the phone says the 10th, and
        // tapping an act that never got a night minted a Gig dated 10 August — a night
        // the festival did not have.
        val theDayAfter = LocalDate.of(2026, 8, 10).atTime(12, 0)
        assertNull(gigNight(threeNights, chosen = null, now = theDayAfter))
        assertEquals(
            LocalDate.of(2026, 8, 8),
            gigNight(threeNights, chosen = LocalDate.of(2026, 8, 8), now = theDayAfter),
        )
    }

    @Test
    fun `before the festival opens nothing has played, so there is no night to give`() {
        assertNull(gigNight(threeNights, null, LocalDate.of(2026, 8, 5).atTime(23, 0)))
    }

    @Test
    fun `a night the festival did not have is refused however it was arrived at`() {
        // The invariant, stated as the one thing that cannot happen: no clock and no
        // choice can date a Gig outside the Bill it was minted from.
        val clocks = listOf(4, 5, 6, 7, 8, 9, 10, 11).map { LocalDate.of(2026, 8, it).atTime(1, 30) } +
            listOf(4, 5, 6, 7, 8, 9, 10, 11).map { LocalDate.of(2026, 8, it).atTime(21, 0) }
        val choices: List<LocalDate?> =
            listOf(null) + (1..14).map { LocalDate.of(2026, 8, it) } + LocalDate.of(2025, 8, 7)
        for (now in clocks) {
            for (chosen in choices) {
                val night = gigNight(threeNights, chosen, now) ?: continue
                assertTrue(
                    "minted $night outside ${threeNights.from}..${threeNights.to}",
                    night in threeNights.nights(),
                )
            }
        }
    }

    @Test
    fun `the minted Gig itself carries a date inside the range`() {
        // The invariant where it actually lands: on the record, in setlist.fm's own
        // date shape, which is what artist + venue + day has to match on later.
        val night = gigNight(threeNights, LocalDate.of(2026, 8, 8), LocalDate.of(2026, 8, 10).atTime(12, 0))!!
        val gig = localGigSetlist("local-1", "Velvet Ditch", night, threeNights.name, threeNights.city)
        assertEquals("08-08-2026", gig.eventDate)
        assertTrue(threeNights.nights().any { fmDate(it) == gig.eventDate })
    }

    @Test
    fun `a bill with no range left is the clock's, as before — nothing to disagree with`() {
        // An undated Bill is a real thing to be holding, and it keeps the old
        // behaviour rather than becoming un-markable.
        val undated = StoredBill(id = "u", name = "Nordlys Fields")
        val now = LocalDate.of(2026, 8, 10).atTime(23, 0)
        assertEquals(LocalDate.of(2026, 8, 10), gigNight(undated, null, now))
        assertEquals(BillWhen.DURING, billWhen(undated, now))
    }

    @Test
    fun `a one-day bill has one night, and it is the only answer`() {
        val oneDay = StoredBill(id = "d", name = "Harbour Sessions", from = "06-08-2026")
        assertEquals(listOf(LocalDate.of(2026, 8, 6)), oneDay.nights())
        assertNull(gigNight(oneDay, null, LocalDate.of(2026, 8, 7).atTime(20, 0)))
        assertEquals(
            LocalDate.of(2026, 8, 6),
            gigNight(oneDay, LocalDate.of(2026, 8, 6), LocalDate.of(2026, 8, 7).atTime(20, 0)),
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

    // --- What crosses the app switch into setlist.fm's form ---------------------

    @Test
    fun `the filing carries every field the form asks for, in the form's own order`() {
        // Order read off setlist.fm's add form on the Pixel 2026-08-06. Date before
        // Venue is load-bearing: the venue field is disabled until a date is set.
        val gig = localGigSetlist("local-1", "Villskudd", LocalDate.of(2026, 8, 7), "Ringnes Festival 2026", "Gjerdrum")
        val fields = filingFields(gig, StoredLog(songs = listOf("A", "B")))
        assertEquals(listOf("Artist", "Date", "Venue", "City", "Songs"), fields.map { it.label })
        assertEquals("Villskudd", fields[0].value)
        assertEquals("07-08-2026", fields[1].value)
        assertEquals("Ringnes Festival 2026", fields[2].value)
        assertEquals("Gjerdrum", fields[3].value)
        // The songs field hands over the paste, not the summary line beside it.
        assertEquals("A\nB", fields[4].value)
        assertEquals("2 songs, in order", fields[4].shown)
    }

    @Test
    fun `the date reads the way a calendar does, because it is picked and not pasted`() {
        // setlist.fm opens a month grid for this one, so no string can land in it. The
        // value shown is the one you go looking for in that grid.
        val gig = localGigSetlist("local-1", "Villskudd", LocalDate.of(2026, 8, 7), "Ringnes", "")
        val date = filingFields(gig, StoredLog()).first { it.label == "Date" }
        assertEquals("7 August 2026", date.shown)
        assertEquals("07-08-2026", date.value)
    }

    @Test
    fun `a field nobody typed in is left out rather than offered blank`() {
        // A Bill with no town gets four values. A fifth, empty, would be a value to
        // paste that says nothing — worse than the absence it is hiding.
        val gig = localGigSetlist("local-1", "Enok Monk", LocalDate.of(2026, 8, 7), "Ringnes", "")
        val fields = filingFields(gig, StoredLog(songs = listOf("A")))
        assertEquals(listOf("Artist", "Date", "Venue", "Songs"), fields.map { it.label })
    }

    @Test
    fun `nothing logged still files the night itself, minus the songs`() {
        val gig = localGigSetlist("local-1", "Enok Monk", LocalDate.of(2026, 8, 7), "Ringnes", "Gjerdrum")
        val fields = filingFields(gig, StoredLog())
        assertEquals(listOf("Artist", "Date", "Venue", "City"), fields.map { it.label })
    }

    @Test
    fun `the songs line counts gaps in, because the paste does`() {
        // "1 song" beside a two-line paste is the small disagreement that makes
        // someone distrust the whole handoff.
        val gig = localGigSetlist("local-1", "Villskudd", LocalDate.of(2026, 8, 7), "Ringnes", "")
        val songs = filingFields(gig, StoredLog(songs = listOf("A", ""))).first { it.label == "Songs" }
        assertEquals("2 songs, in order · 1 unnamed", songs.shown)
        assertEquals("A\n@Unknown[]", songs.value)
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

    @Test
    fun `an artist label carries whatever tells it from its namesakes`() {
        // Five bands are called Silent Majority. The pool has to say which one.
        assertEquals("Silent Majority (US hardcore)", artistLabel("Silent Majority", "US hardcore"))
        assertEquals("Villskudd", artistLabel("Villskudd", null))
        assertEquals("Villskudd", artistLabel("Villskudd", "  "))
    }

    @Test
    fun `an act starts un-answered, which is not the same as having no history`() {
        // "No pool because they have no setlist.fm history" is final; "no pool
        // because the radio couldn't reach anyone" is a question still open, and
        // only the second one should ever be asked again.
        val act = io.github.magnusencoded.setlist2spotify.data.StoredAct(name = "Enok Monk")
        assertFalse(act.tried)
        assertTrue(act.candidates.isEmpty())
        assertEquals("", act.matchedArtist)
    }

    // --- Finding the right band among its namesakes ------------------------------

    private fun withSongs(vararg songs: String) =
        FmSetlist(sets = FmSets(listOf(FmSet(song = songs.map { FmSong(name = it) }))))

    @Test
    fun `a named song picks the band that actually plays it`() {
        val wrongSilentMajority = listOf(withSongs("Suburbia", "Polar Bear Club"))
        val rightSilentMajority = listOf(withSongs("Ei natt til", "Rundt neste sving"))
        assertFalse(playsSong(wrongSilentMajority, "Ei natt til"))
        assertTrue(playsSong(rightSilentMajority, "Ei natt til"))
    }

    @Test
    fun `recognising a title forgives case, punctuation and spacing`() {
        // Typed one-handed in a field. "PIMP" has to find "P.I.M.P.".
        val fifty = listOf(withSongs("P.I.M.P.", "Candy Shop"))
        assertTrue(playsSong(fifty, "pimp"))
        assertTrue(playsSong(fifty, "  Candy   shop "))
        assertFalse(playsSong(fifty, "Magic Stick"))
    }

    @Test
    fun `an artist with no setlists cannot be picked by a song, and does not throw`() {
        assertFalse(playsSong(emptyList(), "anything"))
    }

    // --- Up is always later, Bills included -------------------------------------

    private fun bill(from: String) =
        io.github.magnusencoded.setlist2spotify.data.StoredBill(id = from, name = "F", from = from)

    @Test
    fun `a festival starting tonight sits below a gig next week`() {
        // The field report: Ringnes (today) rendered above a Nick Cave gig seven days
        // out, because Bills were a block pinned above the tickets.
        val ringnes = bill("06-08-2026")
        val nickCave = localGigSetlist("nc", "Nick Cave", LocalDate.of(2026, 8, 13), "Oslo", "")
        val rows = futureRows(listOf(ringnes), listOf(nickCave))
        assertEquals(
            listOf<Any>(FutureRow.Ticket(nickCave), FutureRow.OnBill(ringnes)),
            rows,
        )
    }

    @Test
    fun `a bill sorts by when it starts, not when it ends`() {
        val threeDays = io.github.magnusencoded.setlist2spotify.data.StoredBill(
            id = "b", name = "F", from = "06-08-2026", to = "09-08-2026",
        )
        val between = localGigSetlist("g", "X", LocalDate.of(2026, 8, 8), "Oslo", "")
        assertEquals(
            listOf<Any>(FutureRow.Ticket(between), FutureRow.OnBill(threeDays)),
            futureRows(listOf(threeDays), listOf(between)),
        )
    }

    @Test
    fun `a bill with no dates typed in still renders, at the bottom of the future`() {
        // Unknown is not "the furthest away", and it must not vanish either.
        val undated = bill("")
        val soon = localGigSetlist("g", "X", LocalDate.of(2026, 8, 7), "Oslo", "")
        assertEquals(
            listOf<Any>(FutureRow.Ticket(soon), FutureRow.OnBill(undated)),
            futureRows(listOf(undated), listOf(soon)),
        )
    }

    // --- Dates ------------------------------------------------------------------

    @Test
    fun `dates round-trip in the shape setlist-fm sends`() {
        assertEquals(LocalDate.of(2026, 8, 6), parseFmDate(fmDate(LocalDate.of(2026, 8, 6))))
        assertNull(parseFmDate("6 August"))
    }
}
