package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.ProgrammeAct
import io.github.magnusencoded.stationtostation.data.StoredProgramme
import io.github.magnusencoded.stationtostation.data.actKey
import io.github.magnusencoded.stationtostation.data.actsOn
import io.github.magnusencoded.stationtostation.data.clashesWith
import io.github.magnusencoded.stationtostation.data.commitLabel
import io.github.magnusencoded.stationtostation.data.departuresOf
import io.github.magnusencoded.stationtostation.data.encodeProgramme
import io.github.magnusencoded.stationtostation.data.endTimes
import io.github.magnusencoded.stationtostation.data.matchAct
import io.github.magnusencoded.stationtostation.data.nameKey
import io.github.magnusencoded.stationtostation.data.parseProgramme
import io.github.magnusencoded.stationtostation.data.playedActs
import io.github.magnusencoded.stationtostation.data.playingAt
import io.github.magnusencoded.stationtostation.data.programmeDays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ProgrammeTest {

    private fun act(
        artist: String,
        start: String,
        stage: String,
        date: String = "2026-08-13",
        end: String = "",
    ) = ProgrammeAct(artist = artist, date = date, start = start, stage = stage, end = end)

    @Test
    fun `an act ends when the next one on its stage starts`() {
        val a = act("First", "18:00", "Amfiet")
        val b = act("Second", "18:40", "Amfiet")
        val ends = endTimes(listOf(a, b))
        assertEquals(LocalDateTime.parse("2026-08-13T18:40"), ends[a])
    }

    @Test
    fun `a long gap to the next act does not stretch the set`() {
        // The stage went quiet for three hours. Without the cap this act would be
        // "playing" all evening and clash with everything.
        val a = act("Afternoon", "16:00", "Klubben")
        val b = act("Evening", "21:00", "Klubben")
        val ends = endTimes(listOf(a, b))
        assertEquals(LocalDateTime.parse("2026-08-13T17:00"), ends[a])
    }

    @Test
    fun `the last act of the night falls back to the default length`() {
        val a = act("Headliner", "22:30", "Sirkus")
        assertEquals(LocalDateTime.parse("2026-08-13T23:30"), endTimes(listOf(a))[a])
    }

    @Test
    fun `a published end is preferred over anything inferred`() {
        val a = act("First", "18:00", "Amfiet", end = "18:20")
        val b = act("Second", "18:40", "Amfiet")
        assertEquals(LocalDateTime.parse("2026-08-13T18:20"), endTimes(listOf(a, b))[a])
    }

    /**
     * The truncation bug, asserted. A 105-minute headline set clipped to the default
     * hour makes a real conflict disappear — the clash function reports free time
     * exactly where the choice is, which is the one thing this feature exists to
     * prevent.
     */
    @Test
    fun `a published end longer than the default hour is honoured, and the clash is seen`() {
        val headline = act("Headline", "22:00", "Amfiet", end = "23:45")
        val other = act("Also want", "23:15", "Sirkus")

        assertEquals(LocalDateTime.parse("2026-08-13T23:45"), endTimes(listOf(headline, other))[headline])
        assertEquals(listOf(other), clashesWith(headline, listOf(headline, other)))
    }

    @Test
    fun `an end that runs past midnight closes on the right night`() {
        val late = act("Closer", "23:30", "Klubben", end = "01:00")
        assertEquals(LocalDateTime.parse("2026-08-14T01:00"), endTimes(listOf(late))[late])
    }

    /**
     * The 02:00–06:00 stage that runs until it is light. The start is pushed past the
     * night boundary and the end sits on the far side of it, so read on its own the end
     * lands a day early — and a four-hour set silently became a guessed hour, with three
     * of its four hours reported as free time.
     */
    @Test
    fun `a set that starts after midnight and ends at dawn keeps its real length`() {
        val allNighter = act("Sunrise", "02:00", "Klubben", end = "06:00")

        assertEquals(LocalDateTime.parse("2026-08-14T02:00"), allNighter.startsAt())
        assertEquals(LocalDateTime.parse("2026-08-14T06:00"), endTimes(listOf(allNighter))[allNighter])
    }

    @Test
    fun `an end that cannot be after its start is ignored, and the inference stands`() {
        // A malformed act degrades to a guessed hour rather than dropping out of clash
        // detection entirely.
        val a = act("Malformed", "20:00", "Amfiet", end = "19:00")
        assertEquals(LocalDateTime.parse("2026-08-13T21:00"), endTimes(listOf(a))[a])
    }

    @Test
    fun `overlapping acts on different stages clash`() {
        val mine = act("Want", "20:00", "Amfiet")
        val other = act("Also want", "20:30", "Sirkus")
        assertEquals(listOf(other), clashesWith(mine, listOf(mine, other)))
    }

    @Test
    fun `acts on the same stage never clash`() {
        // Consecutive sets on one stage are a running order, not a choice.
        val a = act("First", "20:00", "Amfiet")
        val b = act("Second", "20:30", "Amfiet")
        assertTrue(clashesWith(a, listOf(a, b)).isEmpty())
    }

    @Test
    fun `back to back across stages is a dash, not a clash`() {
        val a = act("Ends at 21", "20:00", "Amfiet")
        val filler = act("Next on Amfiet", "21:00", "Amfiet")
        val b = act("Starts at 21", "21:00", "Sirkus")
        assertFalse(b in clashesWith(a, listOf(a, filler, b)))
    }

    @Test
    fun `an after-midnight act belongs to the night, not the next afternoon`() {
        val late = act("Late", "01:00", "Klubben", date = "2026-08-13")
        assertEquals(LocalDateTime.parse("2026-08-14T01:00"), late.startsAt())
        // And so it cannot clash with something playing that same afternoon.
        val afternoon = act("Afternoon", "16:00", "Sirkus", date = "2026-08-13")
        assertTrue(clashesWith(late, listOf(late, afternoon)).isEmpty())
    }

    @Test
    fun `playingAt finds what is on and excludes what has ended`() {
        val on = act("On now", "20:00", "Amfiet")
        val over = act("Finished", "18:00", "Sirkus")
        val soon = act("Later", "22:00", "Klubben")
        val at = LocalDateTime.parse("2026-08-13T20:30")
        assertEquals(listOf(on), playingAt(at, listOf(on, over, soon)))
    }

    @Test
    fun `the days and the running order of a timetable`() {
        val thursday = act("First", "15:45", "Amfiet")
        val friday = act("Second", "22:00", "Tent", date = "2026-08-14")
        val acts = listOf(friday, thursday)

        assertEquals(
            listOf(LocalDate.parse("2026-08-13"), LocalDate.parse("2026-08-14")),
            programmeDays(acts).sorted(),
        )
        assertEquals(listOf(thursday), actsOn(LocalDate.parse("2026-08-13"), acts))
    }

    @Test
    fun `a cached programme round-trips, attribution and all`() {
        val programme = StoredProgramme(
            id = "oyafestivalen2026",
            name = "Øyafestivalen 2026",
            copyright = "Clashfinder data CC BY-NC 3.0",
            lastEdit = "2026-07-11 13:44:46",
            acts = listOf(act("Headline", "21:30", "Amfiet", end = "23:15")),
        )
        assertEquals(programme, parseProgramme(encodeProgramme(programme)))
    }

    @Test
    fun `an unreadable cache is no programme, not a crash`() {
        assertEquals(StoredProgramme(), parseProgramme("not json at all"))
    }

    // --- Departures: the board, and the change committing it would make ---

    private fun programme(vararg acts: ProgrammeAct) =
        StoredProgramme(id = "tor2027", name = "Tons of Rock 2027", acts = acts.toList())

    /** Who is on each position, so a test says what a person would see. */
    private fun rungsOn(board: io.github.magnusencoded.stationtostation.data.Departures, date: String) =
        board.positions[LocalDate.parse(date)].orEmpty().map { p -> p.options.map { it.artist } }

    @Test
    fun `a day with no overlaps is a run of single acts, and no rungs`() {
        val board = departuresOf(
            programme(act("First", "18:00", "Amfiet"), act("Second", "20:00", "Amfiet")),
            picked = emptySet(),
            applied = emptySet(),
        )
        assertEquals(listOf(listOf("First"), listOf("Second")), rungsOn(board, "2026-08-13"))
    }

    @Test
    fun `two acts overlapping across stages are one rung of two`() {
        val board = departuresOf(
            programme(act("Want", "20:00", "Amfiet"), act("Also want", "20:30", "Sirkus")),
            emptySet(), emptySet(),
        )
        assertEquals(listOf(listOf("Want", "Also want")), rungsOn(board, "2026-08-13"))
    }

    @Test
    fun `a chain of three is one decision, even where the ends do not touch`() {
        // A overlaps B and B overlaps C, but A is over before C starts. Taking A and C
        // is still one walk, so it is one rung.
        val board = departuresOf(
            programme(
                act("A", "20:00", "Amfiet", end = "20:45"),
                act("B", "20:30", "Sirkus", end = "21:15"),
                act("C", "21:00", "Klubben", end = "21:45"),
            ),
            emptySet(), emptySet(),
        )
        assertEquals(listOf(listOf("A", "B", "C")), rungsOn(board, "2026-08-13"))
    }

    @Test
    fun `two acts on one stage are a running order, never a rung`() {
        val board = departuresOf(
            programme(act("First", "20:00", "Amfiet"), act("Second", "20:30", "Amfiet")),
            emptySet(), emptySet(),
        )
        assertEquals(listOf(listOf("First"), listOf("Second")), rungsOn(board, "2026-08-13"))
    }

    @Test
    fun `an after-midnight act closes its own night rather than opening the next`() {
        val board = departuresOf(
            programme(
                act("Afternoon", "16:00", "Amfiet", date = "2026-08-13"),
                act("Late", "01:00", "Klubben", date = "2026-08-13"),
            ),
            emptySet(), emptySet(),
        )
        assertEquals(listOf(listOf("Afternoon"), listOf("Late")), rungsOn(board, "2026-08-13"))
    }

    @Test
    fun `turning off an act that is on the line is a removal`() {
        val want = act("Want", "20:00", "Amfiet")
        val diff = departuresOf(programme(want), picked = emptySet(), applied = setOf(actKey(want))).diff
        assertTrue(diff.add.isEmpty())
        assertEquals(listOf(actKey(want)), diff.remove)
    }

    @Test
    fun `a selection that matches the line changes nothing, so there is no button`() {
        val want = act("Want", "20:00", "Amfiet")
        val diff = departuresOf(programme(want), setOf(actKey(want)), setOf(actKey(want))).diff
        assertTrue(diff.isEmpty)
        assertEquals(null, commitLabel(diff, "Tons of Rock 2027", firstCommit = false))
    }

    @Test
    fun `a selection spanning two days names both, whichever tab is in view`() {
        // The tab is a view, not a scope: nothing in the diff knows one exists.
        val thu = act("Thursday act", "20:00", "Amfiet", date = "2026-08-13")
        val fri = act("Friday act", "20:00", "Amfiet", date = "2026-08-14")
        val diff = departuresOf(programme(thu, fri), setOf(actKey(thu), actKey(fri)), emptySet()).diff
        assertEquals(
            listOf(LocalDate.parse("2026-08-13"), LocalDate.parse("2026-08-14")),
            diff.days,
        )
    }

    @Test
    fun `a first commit on one day is named in the language of the festival`() {
        val thu = act("Thursday act", "20:00", "Amfiet", date = "2026-08-13")
        val diff = departuresOf(programme(thu), setOf(actKey(thu)), emptySet()).diff
        assertEquals(
            "Add Thursday at Tons of Rock 2027",
            commitLabel(diff, "Tons of Rock 2027", firstCommit = true),
        )
    }

    @Test
    fun `adding to a programme already committed says how much it adds`() {
        val a = act("A", "20:00", "Amfiet")
        val b = act("B", "20:00", "Sirkus")
        val on = act("Already on", "16:00", "Amfiet")
        val diff = departuresOf(
            programme(a, b, on),
            picked = setOf(actKey(a), actKey(b), actKey(on)),
            applied = setOf(actKey(on)),
        ).diff
        assertEquals("Add 2 more gigs", commitLabel(diff, "Tons of Rock 2027", firstCommit = false))
    }

    @Test
    fun `a commit that only takes things away announces itself`() {
        val on = act("Already on", "16:00", "Amfiet")
        val diff = departuresOf(programme(on), emptySet(), setOf(actKey(on))).diff
        assertEquals("Remove 1 gig", commitLabel(diff, "Tons of Rock 2027", firstCommit = false))
    }

    @Test
    fun `a change of mind that both adds and removes is one update`() {
        val thu = act("Thursday act", "20:00", "Amfiet", date = "2026-08-13")
        val fri = act("Friday act", "20:00", "Amfiet", date = "2026-08-14")
        val diff = departuresOf(programme(thu, fri), setOf(actKey(fri)), setOf(actKey(thu))).diff
        assertEquals(
            "Update Thursday and Friday",
            commitLabel(diff, "Tons of Rock 2027", firstCommit = false),
        )
    }

    @Test
    fun `a country tag on one source is the same artist as no tag on the other`() {
        assertEquals(nameKey("Wilco"), nameKey("Wilco (US)"))
    }

    @Test
    fun `an ampersand and the word and are the same artist`() {
        assertEquals(
            nameKey("Nick Cave & the Bad Seeds"),
            nameKey("Nick Cave and the Bad Seeds (AU)"),
        )
    }

    @Test
    fun `a typographic apostrophe is the same artist as a plain one`() {
        assertEquals(nameKey("Melody's Echo Chamber"), nameKey("Melody’s Echo Chamber"))
    }

    @Test
    fun `two different artists do not fold into one`() {
        assertNotEquals(nameKey("Wilco"), nameKey("Wilko Johnson"))
    }

    @Test
    fun `a MusicBrainz id decides it before the name is even read`() {
        val id = "b7ffd2af-418f-4be2-bdd1-22f8b48613da"
        val listed = act("Nick Cave and the Bad Seeds (AU)", "20:00", "Amfiet").copy(mbid = id)
        assertEquals(listed, matchAct(listOf(listed), name = "something else entirely", mbid = id))
    }

    @Test
    fun `a set that has finished is something that happened, not something planned`() {
        val over = act("Played already", "13:00", "Amfiet", date = "2026-08-15")
        val played = playedActs(listOf(over), LocalDateTime.parse("2026-08-15T18:00"))
        assertTrue(actKey(over) in played)
    }

    @Test
    fun `a set still to come is not something that happened`() {
        val later = act("On tonight", "22:00", "Amfiet", date = "2026-08-15")
        val played = playedActs(listOf(later), LocalDateTime.parse("2026-08-15T18:00"))
        assertFalse(actKey(later) in played)
    }
}
