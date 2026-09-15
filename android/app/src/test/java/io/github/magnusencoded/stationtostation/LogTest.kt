package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.StoredLog
import io.github.magnusencoded.stationtostation.data.setlistPaste
import io.github.magnusencoded.stationtostation.data.unionLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Log: what I saw, and what it admits about itself — and the Remembered Line (#126). */
class LogTest {
    @Test fun `deleting a line never renumbers another observation or reuses its identity`() {
        val original = StoredLog(songs = listOf("A", "B", "A"), enteredAt = listOf(10, 20, 30))
        val removed = original.removingAt(1)
        val restored = kotlinx.serialization.json.Json.decodeFromString<StoredLog>(
            kotlinx.serialization.json.Json.encodeToString(StoredLog.serializer(), removed))
        val encore = restored.adding("B", 40).correctingAt(1, "A reprise")
        assertEquals(listOf(0, 2, 3), encore.songs.indices.map(encore::lineNumberAt))
        assertEquals(listOf(10L, 30L, 40L), encore.enteredAt)
        assertEquals(mapOf(2 to "A reprise", 3 to "B"),
            io.github.magnusencoded.stationtostation.data.gossip.gossipLogChanges(removed, encore))
    }

    @Test fun `ordinary edits preserve completion across restart`() {
        val closed = StoredLog(songs = listOf("A"), closed = true, completedAt = 1000)
        val edited = closed.adding("B", 1200).correctingAt(0, "C").removingAt(1)
        val restored = kotlinx.serialization.json.Json.decodeFromString<StoredLog>(
            kotlinx.serialization.json.Json.encodeToString(StoredLog.serializer(), edited))
        assertEquals(1000L, restored.completedAt)
        assertTrue(restored.closed)
    }


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

    @Test fun `merging two Logs keeps every handwritten entry, interleaved by when it was typed`() {
        val survivor = StoredLog(songs = listOf("Title", "B"), remembered = listOf("chorus words", ""),
            enteredAt = listOf(10, 30), lineNumbers = listOf(0, 4), nextLineNumber = 5)
        val dropped = StoredLog(songs = listOf("X", "", "Y", "B"), remembered = listOf("", "", "hummed bit", ""),
            enteredAt = listOf(5, 20, 40, 30), lineNumbers = listOf(0, 1, 2, 3), nextLineNumber = 4)
        val merged = unionLog(survivor, dropped)
        assertEquals(listOf("X", "Title", "", "B", "Y"), merged.songs)
        assertEquals(listOf("", "chorus words", "", "", "hummed bit"), merged.remembered)
        assertEquals(listOf(5L, 10L, 20L, 30L, 40L), merged.enteredAt)
        assertEquals(listOf(5, 0, 6, 4, 7), merged.songs.indices.map(merged::lineNumberAt))
        assertEquals(8, merged.nextLineNumber)
        assertEquals(merged.songs.size, merged.lineNumbers.size)
    }

    @Test fun `merging without timestamps appends the dropped Log and never dedupes unknown times`() {
        val survivor = StoredLog(songs = listOf("A", "B"))
        val dropped = StoredLog(songs = listOf("A", "C", "D"), remembered = listOf("", "la la", ""))
        val merged = unionLog(survivor, dropped)
        assertEquals(listOf("A", "B", "A", "C", "D"), merged.songs)
        assertEquals(listOf("", "", "", "la la", ""), merged.remembered)
        assertEquals(listOf(0L, 0L, 0L, 0L, 0L), merged.enteredAt)
        assertEquals(listOf(0, 1, 2, 3, 4), merged.songs.indices.map(merged::lineNumberAt))
    }

    @Test fun `merging a Log with itself changes nothing, and a shorter survivor still survives`() {
        val log = StoredLog(songs = listOf("A"), enteredAt = listOf(10), lineNumbers = listOf(3), nextLineNumber = 4)
        assertEquals(log, unionLog(log, log))
        val longer = log.adding("B", 20).adding("C", 30)
        val merged = unionLog(log, longer.removingAt(0))
        assertEquals(listOf("A", "B", "C"), merged.songs)
        assertEquals(3, merged.lineNumberAt(0))
    }

    @Test fun `merging keeps completion only where both were closed, earliest completion`() {
        val a = StoredLog(songs = listOf("A"), closed = true, completedAt = 200, enteredAt = listOf(1))
        val b = StoredLog(songs = listOf("B"), closed = true, completedAt = 100, enteredAt = listOf(2))
        assertEquals(100L, unionLog(a, b).completedAt)
        assertTrue(unionLog(a, b).closed)
        assertFalse(unionLog(a, b.completing(false)).closed)
    }
}
