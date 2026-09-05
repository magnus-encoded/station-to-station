package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.StoredLog
import io.github.magnusencoded.stationtostation.data.setlistPaste
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Log: what I saw, and what it admits about itself — and the Remembered Line (#126). */
class LogTest {

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

    // --- The Remembered Line -----------------------------------------------------

    /**
     * The whole point: correcting the record must not destroy the words that are the
     * memory. "All held together by toothpicks and gum" is what was caught in the dark;
     * "Toothpicks and Gum" is what it is called.
     */
    @Test
    fun `a title replaces the words and the words are kept beneath it`() {
        val log = StoredLog(songs = listOf("Hollowmoor", "All held together by toothpicks and gum"))
            .correctingAt(1, "Toothpicks and Gum")

        assertEquals(listOf("Hollowmoor", "Toothpicks and Gum"), log.songs)
        assertEquals("All held together by toothpicks and gum", log.rememberedAt(1))
        assertNull(log.rememberedAt(0))
    }

    /** The first words are the memory; a title I already chose is not. */
    @Test
    fun `a second correction keeps the words originally written`() {
        val log = StoredLog(songs = listOf("All held together by toothpicks and gum"))
            .correctingAt(0, "Toothpick and Gum")
            .correctingAt(0, "Toothpicks and Gum")

        assertEquals(listOf("Toothpicks and Gum"), log.songs)
        assertEquals("All held together by toothpicks and gum", log.rememberedAt(0))
    }

    /** A wrong correction is never a one-way door. */
    @Test
    fun `restoring puts the remembered line back as the entry`() {
        val log = StoredLog(songs = listOf("Hollowmoor", "a line I misheard"))
            .correctingAt(1, "Vardhavn")
            .restoringAt(1)

        assertEquals(listOf("Hollowmoor", "a line I misheard"), log.songs)
        assertNull(log.rememberedAt(1))
    }

    /** "One I couldn't name" is an acknowledged fact, not an invitation to guess. */
    @Test
    fun `a Gap is not corrected`() {
        val log = StoredLog(songs = listOf("Hollowmoor", "")).correctingAt(1, "Vardhavn")
        assertEquals(listOf("Hollowmoor", ""), log.songs)
        assertEquals(1, log.gaps)
    }

    /**
     * The parallel list is only parallel if every edit keeps it so — which is why the
     * editor gained intent-carrying callbacks rather than "here is the new list".
     */
    @Test
    fun `adding and removing keep the words with the entry they belong to`() {
        val log = StoredLog()
            .adding("Hollowmoor")
            .adding("a line I misheard")
            .adding("Vardhavn")
            .correctingAt(1, "Paper Cranes")
            .removingAt(0)

        assertEquals(listOf("Paper Cranes", "Vardhavn"), log.songs)
        assertEquals("a line I misheard", log.rememberedAt(0))
        assertNull(log.rememberedAt(1))
    }

    /** Nothing on an existing phone is lost or reinterpreted. */
    @Test
    fun `a Log written before this feature reads as nothing ever replaced`() {
        val old = StoredLog(songs = listOf("Hollowmoor", "Vardhavn"), closed = true)
        assertNull(old.rememberedAt(0))
        assertNull(old.rememberedAt(1))
        // And it still edits correctly with no remembered list to align against.
        val corrected = old.correctingAt(0, "Paper Cranes")
        assertEquals(listOf("Paper Cranes", "Vardhavn"), corrected.songs)
        assertEquals("Hollowmoor", corrected.rememberedAt(0))
        assertNull(corrected.rememberedAt(1))
        assertTrue(corrected.closed)
    }

    // --- Entry timestamps (#409) ---------------------------------------------------

    /** [adding] stamps the new entry with the moment it was typed, and nothing else. */
    @Test
    fun `adding stamps the new entry with the given time`() {
        val log = StoredLog().adding("Hollowmoor", now = 1_000L)
        assertEquals(1_000L, log.enteredAtOrNull(0))
    }

    /** Correcting a title does not move when the memory was written. */
    @Test
    fun `correcting leaves an entry's timestamp untouched`() {
        val log = StoredLog(songs = listOf("a line I misheard"), enteredAt = listOf(1_000L))
            .correctingAt(0, "Vardhavn")

        assertEquals(1_000L, log.enteredAtOrNull(0))
    }

    /** Restoring the remembered line is not a new entry either. */
    @Test
    fun `restoring leaves an entry's timestamp untouched`() {
        val log = StoredLog(songs = listOf("a line I misheard"), enteredAt = listOf(1_000L))
            .correctingAt(0, "Vardhavn")
            .restoringAt(0)

        assertEquals(1_000L, log.enteredAtOrNull(0))
    }

    /** Nothing on an existing phone is lost or reinterpreted — and never guessed. */
    @Test
    fun `a Log written before this feature reads as unknown, never fabricated`() {
        val old = StoredLog(songs = listOf("Hollowmoor", "Vardhavn"), closed = true)
        assertNull(old.enteredAtOrNull(0))
        assertNull(old.enteredAtOrNull(1))
        // And it still edits correctly with no enteredAt list to align against.
        val added = old.adding("Paper Cranes", now = 2_000L)
        assertNull(added.enteredAtOrNull(0))
        assertNull(added.enteredAtOrNull(1))
        assertEquals(2_000L, added.enteredAtOrNull(2))
    }
}
