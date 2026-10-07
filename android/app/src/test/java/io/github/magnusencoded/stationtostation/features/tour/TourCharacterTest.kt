package io.github.magnusencoded.stationtostation.features.tour

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The shared character file has a line for every step, S1–S20, and its playlist text. */
class TourCharacterTest {

    private val character: TourCharacter = run {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "fixtures/tour/character/${TourCharacter.ASSET}") }
            .first { it.exists() }
        TourCharacter.parse(file.readText())
    }

    @Test
    fun every_step_has_a_line() {
        val missing = TourStep.entries.map { it.name }.filter { character.lines[it].isNullOrBlank() }
        assertTrue("missing lines: $missing", missing.isEmpty())
    }

    @Test
    fun name_assets_and_playlist_are_set() {
        assertTrue(character.name.isNotBlank())
        assertTrue(character.avatar.isNotBlank() && character.selfie.isNotBlank())
        assertTrue(character.playlist.title.isNotBlank() && character.playlist.description.isNotBlank())
    }
}
