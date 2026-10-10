package io.github.magnusencoded.stationtostation.features.tour

import io.github.magnusencoded.stationtostation.features.planning.*
import io.github.magnusencoded.stationtostation.ui.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

internal fun tourFixtureFile(name: String): File = generateSequence(File("").absoluteFile) { it.parentFile }
    .map { File(it, "fixtures/tour/$name") }.first { it.isFile }
internal fun handoffCases() = Json.parseToJsonElement(tourFixtureFile("cases.json").readText()).jsonObject
class HandoffDTest {
    private val character = TourCharacter.decode(tourFixtureFile("character/character.json").readText())
    @Test fun unknownBandAndRetry() {
        val line = character.lines.getValue("S3")
        assertEquals(line.nohit, tourBandReason(false, "nohit", line.nohit!!, line.failed!!))
        assertFalse(tourPlanReady(false, "Venue", true))
        assertEquals(line.failed, tourBandReason(false, "failed", line.nohit, line.failed))
        assertNull(tourBandReason(true, "hits", line.nohit, line.failed))
        assertTrue(tourPlanReady(true, "Venue", true))
        assertFalse(tourPlanReady(true, "", true))
        assertFalse(tourPlanReady(true, "Venue", false))
    }
    @Test fun sharedGoals() {
        for (raw in handoffCases().getValue("goalCases").jsonArray) {
            val row = raw.jsonObject
            val state = TourState(step = row["initial"]?.jsonPrimitive?.content?.let(TourStep::valueOf))
            val event = when(row.getValue("event").jsonPrimitive.content) {
                "started" -> TourEvent.Started(true)
                "resumed" -> TourEvent.Resumed
                "contactExchanged" -> TourEvent.ContactExchanged(Place(0.0, 0.0))
                else -> TourEvent.Acknowledged
            }
            val goals = row.getValue("goals").jsonArray.map { TourStep.valueOf(it.jsonPrimitive.content) }.toSet()
            val next = TourScript.on(state, event, goals).state
            assertEquals(row.getValue("step").jsonPrimitive.content, next.step!!.name)
            assertEquals(row.getValue("satisfied").jsonPrimitive.boolean, next.goalSatisfied)
            val line = character.line(next.step.mark!!, screen = "timelines", satisfied = next.goalSatisfied)
            if (next.goalSatisfied) {
                assertEquals("Tap OK.", line.instruction)
                assertEquals(character.lines.getValue(next.step.name).already, line.why)
                assertEquals(TourStep.S9, TourScript.on(next, TourEvent.Acknowledged, goals).state.step)
            } else assertTrue(line.instruction.contains(if(next.step == TourStep.S1) "OK" else "Spread two fingers"))
        }
        for (step in listOf(TourStep.S5,TourStep.S6,TourStep.S8,TourStep.S10,TourStep.S13)) {
            val next = TourScript.on(TourState(step=step), TourEvent.Resumed, setOf(step)).state
            assertTrue(next.goalSatisfied)
            assertEquals("Tap OK.", character.line(step.mark!!, satisfied=true).instruction)
            assertNotNull(character.lines.getValue(step.name).already)
        }
    }
    @Test fun arrivalPhasesAndOrder() {
        assertEquals(listOf("3","4","5","6"), logArrivalOrder(listOf("1","2"), (1..6).map(Int::toString)))
        assertTrue(logArrivalOrder(listOf("1","2"), listOf("1","2")).isEmpty())
        assertEquals(listOf("published:2"), logArrivalOrder(listOf("log:1"), listOf("published:1", "published:2"), listOf("First"), listOf("First", "Second")))
        val row = LogArrival(0, 4, 0)
        assertEquals(LogArrivalFrame(0f,0f,false), row.frame(0))
        assertTrue(row.frame(45).space > 0f)
        assertEquals(0f,row.frame(45).text)
        assertEquals(1f,row.frame(180).space)
        assertTrue(row.frame(180).text > 0f)
        assertFalse(row.frame(180).node)
        assertTrue(row.frame(360).node)
        assertEquals(LogArrivalFrame(0f,0f,false),LogArrival(1,4,0).frame(360))
        assertTrue(LogArrival(24,25,0).frame(3000).node)
        assertEquals(LogArrivalFrame(),row.frame(0,true))
    }
}
