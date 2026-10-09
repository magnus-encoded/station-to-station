package io.github.magnusencoded.stationtostation.features.tour

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TourCharacterTest {
    private fun source(): String = File("../../fixtures/tour/character/character.json").readText()
    @Test fun sharedScreenCopyAndAcknowledgementCases() {
        val character = TourCharacter.decode(source())
        val cases = Json.parseToJsonElement(File("../../fixtures/tour/cases.json").readText())
            .jsonObject.getValue("copyCases").jsonArray
        for (item in cases) {
            val row = item.jsonObject
            val mark = TourStep.valueOf(row.getValue("step").jsonPrimitive.content).mark!!
            val screen = row.getValue("screen").jsonPrimitive.content
            val line = character.line(mark, "Paranoid Android", screen)
            assertEquals(row.toString(), row.getValue("android").jsonPrimitive.content, line.instruction)
            assertEquals(row["why"]?.jsonPrimitive?.contentOrNull, line.why)
            assertEquals(row.getValue("ack").jsonPrimitive.boolean, mark.canAcknowledge(screen))
        }
    }
    @Test fun actionCardsHaveNoAcknowledgement() {
        TourStep.entries.mapNotNull { it.mark }.forEach { mark ->
            assertEquals(mark == CoachMark.Line || mark == CoachMark.Gossip, mark.canAcknowledge("room"))
        }
    }
    @Test fun finalCharacterLoadsEveryCard() {
        val character = TourCharacter.decode(source())
        TourStep.entries.mapNotNull { it.mark }.forEach { assertTrue(character.line(it).instruction.isNotBlank()) }
    }
    @Test fun openerRendersAndAnEmptyPoolKeepsTheGenericInstruction() {
        val character = TourCharacter.decode(source())
        assertEquals("They’re playing “Paranoid Android” as the opener. Type it into the Log, then add it.",
            character.line(CoachMark.Log, "Paranoid Android", screen = "room").instruction)
        assertEquals("Type the first song they played into the Log, then add it.", character.line(CoachMark.Log, screen = "room").instruction)
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
