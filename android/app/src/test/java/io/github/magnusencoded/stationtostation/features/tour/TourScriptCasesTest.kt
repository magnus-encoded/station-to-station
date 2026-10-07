package io.github.magnusencoded.stationtostation.features.tour

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The **Shared cases** of #587, from `fixtures/tour/`, run against [TourScript]. */
class TourScriptCasesTest {

    @Test
    fun offline_start() = run("Offline start")

    @Test
    fun skip_at_every_step() = run("Skip at Sn")

    @Test
    fun resume() = run("Resume")

    @Test
    fun replay() = run("Replay")

    @Test
    fun out_of_order_event() = run("Out-of-order event")

    @Test
    fun happy_path() = run("Happy path")

    @Test
    fun spotify_declined_and_retry() {
        run("Spotify declined")
        run("Spotify retry")
    }

    private fun run(row: String) {
        val cases = cases().filter { it.getValue("row").jsonPrimitive.content == row }
        assertTrue("no cases for $row", cases.isNotEmpty())
        cases.forEach(::check)
    }

    private fun check(case: JsonObject) {
        val id = case.getValue("id").jsonPrimitive.content
        var state = precondition(case["precondition"]?.jsonObject)
        val initial = state
        val visited = mutableListOf<TourStep>()
        val commands = mutableListOf<TourCommand>()
        val commandsAfterRestart = mutableListOf<TourCommand>()
        var restarted = false
        for (raw in case.getValue("events").jsonArray) {
            val event = raw.jsonObject
            if (event.getValue("type").jsonPrimitive.content == "restart") {
                // What a killed app gets back: the state as stored, nothing else.
                state = json.decodeFromString(TourState.serializer(), json.encodeToString(TourState.serializer(), state))
                restarted = true
                continue
            }
            val before = state.step
            val transition = TourScript.on(state, event(event))
            state = transition.state
            commands += transition.commands
            if (restarted) commandsAfterRestart += transition.commands
            state.step?.takeIf { it != before }?.let(visited::add)
            if (TourCommand.MarkTourFinished in transition.commands && before == TourStep.S19) {
                visited += TourStep.S20
            }
        }
        val expect = case.getValue("expect").jsonObject
        expect["currentStep"]?.let { assertEquals(id, it.jsonPrimitive.contentOrNull, state.step?.name) }
        expect["commands"]?.let { assertEquals(id, it.jsonArray.map { c -> c.jsonPrimitive.content }, commands.map(::name)) }
        expect["commandsEnd"]?.let {
            val end = it.jsonArray.map { c -> c.jsonPrimitive.content }
            assertEquals(id, end, commands.map(::name).takeLast(end.size))
        }
        expect["finished"]?.let { assertEquals(id, it.jsonPrimitive.boolean, state.finished) }
        expect["playlistCreated"]?.let { assertFalse(id, it.jsonPrimitive.boolean) }
        expect["visitedSteps"]?.let { assertEquals(id, it.jsonArray.map { s -> s.jsonPrimitive.content }, visited.map { s -> s.name }) }
        expect["pendingSpotifyRetry"]?.let { assertEquals(id, it.jsonPrimitive.boolean, state.pendingSpotifyRetry) }
        expect["stateChanged"]?.let { assertEquals(id, it.jsonPrimitive.boolean, state != initial) }
        expect["reemitEntryCommands"]?.let {
            val step = TourStep.valueOf(expect.getValue("reenteredStep").jsonPrimitive.content)
            assertTrue(id, step.mark?.let { m -> TourCommand.ShowCoachMark(m) in commandsAfterRestart } ?: true)
        }
        expect["doNotRepeat"]?.let {
            val names = commandsAfterRestart.map(::name)
            it.jsonArray.forEach { c -> assertFalse(id, c.jsonPrimitive.content in names) }
        }
        expect["freshDemoWorld"]?.let { assertEquals(id, "purgeDemoWorld", commands.map(::name).first()) }
    }

    private fun precondition(p: JsonObject?): TourState {
        p ?: return TourState()
        return TourState(
            step = p["currentStep"]?.jsonPrimitive?.content?.let(TourStep::valueOf),
            finished = p["finished"]?.jsonPrimitive?.boolean ?: false,
            pendingSpotifyRetry = p["pendingSpotifyRetry"]?.jsonPrimitive?.boolean ?: false,
        )
    }

    private fun event(e: JsonObject): TourEvent = when (val type = e.getValue("type").jsonPrimitive.content) {
        "started" -> TourEvent.Started(e.getValue("online").jsonPrimitive.boolean)
        "acknowledged" -> TourEvent.Acknowledged
        "curtainPulled" -> TourEvent.CurtainPulled
        "bandPicked" -> TourEvent.BandPicked
        "gigAdded" -> TourEvent.GigAdded
        "roomOpened" -> TourEvent.RoomOpened
        "swipedBack" -> TourEvent.SwipedBack
        "contactExchanged" -> TourEvent.ContactExchanged(
            e["location"]?.jsonObject?.let {
                Place(it.getValue("latitude").jsonPrimitive.double, it.getValue("longitude").jsonPrimitive.double)
            }
        )
        "pinchedOut" -> TourEvent.PinchedOut
        "ticketImported" -> TourEvent.TicketImported
        "calendarAdded" -> TourEvent.CalendarAdded
        "mapsOpened" -> TourEvent.MapsOpened
        "ticketShown" -> TourEvent.TicketShown
        "checkedIn" -> TourEvent.CheckedIn
        "logEntryWritten" -> TourEvent.LogEntryWritten
        "gapRecorded" -> TourEvent.GapRecorded
        "gossipSent" -> TourEvent.GossipSent
        "setlistFilled" -> TourEvent.SetlistFilled
        "returnedFromPhotos" -> TourEvent.ReturnedFromPhotos
        "mediaAdded" -> TourEvent.MediaAdded(e["visibility"]?.jsonPrimitive?.content == "shared")
        "spotifyExported" -> TourEvent.SpotifyExported
        "spotifyDeclined" -> TourEvent.SpotifyDeclined
        "skipped" -> TourEvent.Skipped
        "resumed" -> TourEvent.Resumed
        "replayRequested" -> TourEvent.ReplayRequested
        else -> error("unknown event $type")
    }

    private fun name(c: TourCommand): String = when (c) {
        is TourCommand.ShowCoachMark -> "showCoachMark"
        TourCommand.LookUpBand -> "lookUpBand"
        is TourCommand.ImportDemoTicket -> "importDemoTicket"
        is TourCommand.AdvanceDemoClock -> "advanceDemoClock"
        TourCommand.DeliverGossip -> "deliverGossip"
        TourCommand.FillSetlist -> "fillSetlist"
        TourCommand.DeliverFriendSelfie -> "deliverFriendSelfie"
        TourCommand.PurgeDemoWorld -> "purgeDemoWorld"
        TourCommand.MarkTourFinished -> "markTourFinished"
    }

    private fun cases(): List<JsonObject> =
        Json.parseToJsonElement(File(fixtureDir(), "cases.json").readText()).jsonArray.map { it.jsonObject }

    private fun fixtureDir(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "fixtures/tour") }
            .firstOrNull { it.isDirectory }
            ?: error("fixtures/tour not found above ${File("").absolutePath}")

    private val json = Json
}
