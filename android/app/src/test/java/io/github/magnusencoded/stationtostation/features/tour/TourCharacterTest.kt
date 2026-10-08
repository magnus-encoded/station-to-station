package io.github.magnusencoded.stationtostation.features.tour

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TourCharacterTest {
    private fun source(): String = File("../../fixtures/tour/character/character.json").readText()
    @Test fun finalCharacterLoadsEveryCard() {
        val character = TourCharacter.decode(source())
        TourStep.entries.mapNotNull { it.mark }.forEach { assertTrue(character.line(it).instruction.isNotBlank()) }
    }
    @Test fun anOverrideChangesOnlyTheInstruction() {
        val line = TourCharacter.Line("Base", "Reason", ios = "Apple", android = "Android")
        assertEquals("Android", line.onAndroid().instruction)
        assertEquals("Reason", line.onAndroid().why)
    }
    @Test fun swappedCharacterKeepsNoOriginalIdentityOrNotes() {
        val changed = Json.parseToJsonElement(source()).jsonObject.toMutableMap()
        changed["name"] = JsonPrimitive("Ada")
        changed["username"] = JsonPrimitive("ada")
        changed["avatar"] = JsonPrimitive("ada_avatar")
        val character = TourCharacter.decode(JsonObject(changed).toString())
        assertEquals("Ada", character.name)
        assertEquals("ada_avatar", character.avatar)
    }
    @Test fun missingStepAndOverlongInstructionFail() {
        val source = Json.parseToJsonElement(source()).jsonObject
        val lines = source.getValue("lines").jsonObject.toMutableMap()
        lines.remove("S16")
        assertThrows(IllegalArgumentException::class.java) { TourCharacter.decode(JsonObject(source + ("lines" to JsonObject(lines))).toString()) }
        val character = TourCharacter.decode(source.toString())
        assertThrows(IllegalArgumentException::class.java) {
            character.copy(lines = character.lines + ("S1" to TourCharacter.Line("word ".repeat(21)))).validated()
        }
    }
}
