package io.github.magnusencoded.setlist2spotify

import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmSet
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmSets
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmSetlist
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmSong
import io.github.magnusencoded.setlist2spotify.ui.EventRow
import io.github.magnusencoded.setlist2spotify.ui.eventRows
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The song numbers a user reads. A tape track or a nameless placeholder taking a
 * number is what put every song after it one out of step with setlist.fm.
 */
class SetlistNumberingTest {

    private fun show(vararg sets: FmSet) = FmSetlist(id = "x", sets = FmSets(sets.toList()))

    private fun set(vararg songs: FmSong, encore: Int? = null) =
        FmSet(encore = encore, song = songs.toList())

    private fun song(name: String) = FmSong(name = name)
    private fun tape(name: String) = FmSong(name = name, tape = true)

    private fun List<EventRow>.numbers() =
        filterIsInstance<EventRow.SongItem>().map { it.number }

    @Test
    fun `a tape track sits on the line but takes no number`() {
        val rows = show(set(tape("Intro"), song("Choke"), song("Money"))).eventRows()
        assertEquals(listOf(null, 1, 2), rows.numbers())
    }

    @Test
    fun `a nameless placeholder is dropped entirely`() {
        val rows = show(set(song("Choke"), song(""), song("Money"))).eventRows()
        assertEquals(listOf("Choke", "Money"), rows.filterIsInstance<EventRow.SongItem>().map { it.song.name })
        assertEquals(listOf(1, 2), rows.numbers())
    }

    @Test
    fun `numbering runs on across the encore break`() {
        val rows = show(
            set(song("Choke"), song("Money")),
            set(song("Evolve"), encore = 1),
        ).eventRows()
        assertEquals(listOf(1, 2, 3), rows.numbers())
        assertEquals(1, rows.count { it is EventRow.Encore })
    }

    /**
     * The confirm screen numbers the playlist being built, not the setlist. Same
     * rule as [io.github.magnusencoded.setlist2spotify.ui.ConfirmScreen]'s `numbers`.
     */
    private fun playlistNumbers(vararg going: Boolean): List<Int?> {
        var n = 0
        return going.map { if (it) ++n else null }
    }

    @Test
    fun `including a tape track gives it a place and pushes the rest down`() {
        // Choke, Money, then a tape nobody wanted: 1, 2, unnumbered.
        assertEquals(listOf(1, 2, null), playlistNumbers(true, true, false))
        // Tap the tape in and it takes position 3.
        assertEquals(listOf(1, 2, 3), playlistNumbers(true, true, true))
    }

    @Test
    fun `dropping a song closes the numbering up behind it`() {
        assertEquals(listOf(1, null, 2), playlistNumbers(true, false, true))
    }

    @Test
    fun `the count shown is what the band played, not what was logged`() {
        val setlist = show(set(tape("Walk-on"), song("Choke"), song(""), song("Money")))
        assertEquals(4, setlist.songs().size)
        assertEquals(2, setlist.performed().size)
    }
}
