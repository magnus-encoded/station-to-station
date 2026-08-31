package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.ProgrammeAct
import io.github.magnusencoded.stationtostation.data.StoredProgramme
import io.github.magnusencoded.stationtostation.data.actsOn
import io.github.magnusencoded.stationtostation.data.clashesWith
import io.github.magnusencoded.stationtostation.data.encodeProgramme
import io.github.magnusencoded.stationtostation.data.endTimes
import io.github.magnusencoded.stationtostation.data.parseProgramme
import io.github.magnusencoded.stationtostation.data.playingAt
import io.github.magnusencoded.stationtostation.data.programmeDays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
