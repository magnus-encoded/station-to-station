package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.ProgrammeAct
import io.github.magnusencoded.stationtostation.data.actsOn
import io.github.magnusencoded.stationtostation.data.clashesWith
import io.github.magnusencoded.stationtostation.data.encodeProgramme
import io.github.magnusencoded.stationtostation.data.endTimes
import io.github.magnusencoded.stationtostation.data.oyaProgramme
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

    private fun act(artist: String, start: String, stage: String, date: String = "2026-08-13") =
        ProgrammeAct(artist = artist, date = date, start = start, stage = stage)

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

    /**
     * The markup this mimics, written out rather than captured: a saved copy of the
     * real page would put a festival's programme in the repo, which is the thing this
     * app deliberately does not do. What is being tested is the *shape* — nested spans
     * inside the h3, Next.js comment nodes mid-text, "kl." before the time, a Norwegian
     * date with no year — and that is reproducible without anyone's data.
     */
    private val pageShape = """
        <div><h3 class="x">Band One<!-- --> <span class="y">(<!-- -->UK<!-- -->)</span></h3>
        <ul class="flex"><li><span><span class="inline-block first-letter:uppercase">torsdag 13. august</span></span></li>
        <li><span><span class="hidden md:inline-block">kl.</span> <!-- -->15:45</span></li>
        <li><span class="h-8">Main Stage</span></li></ul></div>
        <div><h3 class="x">Band &amp; Band&#x27;s Friend&#160;Two &#x1F3B8;</h3>
        <ul class="flex"><li><span><span class="inline-block first-letter:uppercase">fredag 14. august</span></span></li>
        <li><span><span class="hidden md:inline-block">kl.</span> <!-- -->22:00</span></li>
        <li><span class="h-8">Tent</span></li></ul></div>
    """.trimIndent()

    @Test
    fun `the page parses into acts, comments and nested spans and all`() {
        val acts = oyaProgramme(pageShape, year = 2026)
        assertEquals(2, acts.size)
        assertEquals("Band One (UK)", acts[0].artist)
        assertEquals("2026-08-13", acts[0].date)
        assertEquals("15:45", acts[0].start)
        assertEquals("Main Stage", acts[0].stage)
        assertEquals(listOf(LocalDate.parse("2026-08-13"), LocalDate.parse("2026-08-14")), programmeDays(acts))
    }

    @Test
    fun `an ampersand in a band name arrives as an ampersand`() {
        // The live page writes "Nick Cave &amp; The Bad Seeds". Stored raw, that name
        // matches nothing a person or setlist.fm would ever write. The guitar is the
        // code point above U+FFFF: decoded one UTF-16 unit at a time it comes out
        // as garbage, and a band name is exactly where such a character turns up.
        val acts = oyaProgramme(pageShape, year = 2026)
        assertEquals("Band & Band's Friend Two 🎸", acts[1].artist)
    }

    @Test
    fun `a block missing any field is dropped, never half-built`() {
        // The failure that would matter is a partial act quietly joining the list —
        // an act with no stage clashes with nothing and is invisible on the timetable.
        val broken = """
            <div><h3>No time</h3><ul><li><span>torsdag 13. august</span></li>
            <li><span>Main Stage</span></li></ul></div>
        """.trimIndent()
        assertTrue(oyaProgramme(broken, year = 2026).isEmpty())
    }

    @Test
    fun `markup that has moved on parses to nothing, not to nonsense`() {
        assertTrue(oyaProgramme("<html><body><p>We redesigned the site</p></body></html>", 2026).isEmpty())
    }

    @Test
    fun `a cached programme round-trips`() {
        val acts = oyaProgramme(pageShape, year = 2026)
        assertEquals(acts, parseProgramme(encodeProgramme(acts)))
    }
}
