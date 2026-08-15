package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.billedAs
import io.github.magnusencoded.stationtostation.data.setlistfm.FmArtist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSet
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSets
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSong
import io.github.magnusencoded.stationtostation.data.setlistfm.FmVenue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The label half of #166: what an evening of several acts is called when nothing knows
 * it was a festival. The case that motivated it is real — 24 November 2019, Devin
 * Townsend at Sentrum Scene with Haken supporting, which the **Line** rendered as a
 * **Festival** called "Sentrum Scene".
 */
class BilledAsTest {

    private fun show(id: String, artist: String, songs: Int, venue: String = "Sentrum Scene") =
        FmSetlist(
            id = id,
            eventDate = "24-11-2019",
            artist = FmArtist(name = artist),
            venue = FmVenue(name = venue),
            sets = FmSets(listOf(FmSet(song = List(songs) { FmSong(name = "song $it") }))),
        )

    @Test
    fun `the headliner is named first and the support in parentheses`() {
        // Source order deliberately puts the support first: the label must come from
        // the evening, not from however setlist.fm happened to return it.
        val evening = listOf(show("2", "Haken", songs = 7), show("1", "Devin Townsend", songs = 18))

        assertEquals("Devin Townsend (Haken)", billedAs(evening))
    }

    @Test
    fun `a room never becomes the name of an event`() {
        val evening = listOf(show("1", "Devin Townsend", songs = 18), show("2", "Haken", songs = 7))

        assertEquals(false, "Sentrum Scene" in billedAs(evening))
    }

    /**
     * Song count is a weaker answer to "who played last", not a different question —
     * with nothing to separate them it must not shuffle the evening around. The first
     * the source gave wins, so the label is stable between two renders.
     */
    @Test
    fun `a tie keeps the order the source gave`() {
        val evening = listOf(show("1", "First", songs = 10), show("2", "Second", songs = 10))

        assertEquals("First (Second)", billedAs(evening))
    }

    @Test
    fun `tape tracks do not decide the headliner`() {
        // performed() already excludes walk-on tape; a support with a long interval
        // recording must not outrank the band everyone came for.
        val support = FmSetlist(
            id = "2",
            artist = FmArtist(name = "Haken"),
            sets = FmSets(listOf(FmSet(song = List(30) { FmSong(name = "tape $it", tape = true) }))),
        )

        assertEquals("Devin Townsend (Haken)", billedAs(listOf(support, show("1", "Devin Townsend", songs = 18))))
    }

    /** A Node is not a list. One Resolution in is where the whole lineup lives. */
    @Test
    fun `a festival day names two supports and counts the rest`() {
        val day = listOf(show("1", "QOTSA", songs = 20)) +
            (1..8).map { show("s$it", "Act $it", songs = 5) }

        assertEquals("QOTSA (Act 1, Act 2 +6)", billedAs(day))
    }

    @Test
    fun `one named act is just that act`() {
        assertEquals("Solo", billedAs(listOf(show("1", "Solo", songs = 12))))
    }

    /**
     * The venue comes back only here, where there is nothing else to say — and it is
     * the last resort rather than the default it used to be.
     */
    @Test
    fun `an evening with no named acts falls back to what little is known`() {
        val nameless = FmSetlist(id = "1", venue = FmVenue(name = "Sentrum Scene"))

        assertEquals("Sentrum Scene", billedAs(listOf(nameless)))
        assertEquals("Several acts", billedAs(listOf(FmSetlist(id = "1"))))
    }
}
