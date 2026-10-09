package io.github.magnusencoded.stationtostation.features.tour

import io.github.magnusencoded.stationtostation.TourFixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
        val cases = TourFixtures.load().filter { it.getValue("row").jsonPrimitive.content == row }
        assertTrue("no cases for $row", cases.isNotEmpty())
        cases.forEach(::check)
    }

    private fun check(case: JsonObject) {
        val id = case.getValue("id").jsonPrimitive.content
        val online = case["online"]?.jsonPrimitive?.boolean ?: true
        var state = initial(case["initial"]?.jsonObject)
        val visited = mutableListOf<String>()
        for ((i, raw) in case.getValue("checks").jsonArray.withIndex()) {
            val check = raw.jsonObject
            val type = check.getValue("event").jsonPrimitive.content
            val at = "$id #$i $type"
            val before = state
            val commands: List<TourCommand>
            if (type == "restart") {
                // What a killed app gets back: the state as stored, nothing else.
                state = Json.decodeFromString(TourState.serializer(), Json.encodeToString(TourState.serializer(), state))
                commands = emptyList()
            } else {
                val transition = TourScript.on(state, event(type, online))
                state = transition.state
                commands = transition.commands
            }
            state.step?.takeIf { it != before.step }?.let { visited += it.name }
            val expect = check.getValue("expect").jsonObject
            expect["step"]?.let { assertEquals(at, if (it is JsonNull) null else it.jsonPrimitive.content, state.step?.name) }
            expect["commands"]?.let { assertEquals(at, it.jsonArray.map { c -> c.jsonPrimitive.content }, commands.map(::notation)) }
            expect["finished"]?.let { assertEquals(at, it.jsonPrimitive.boolean, state.finished) }
            expect["pendingSpotifyRetry"]?.let { assertEquals(at, it.jsonPrimitive.boolean, state.pendingSpotifyRetry) }
            expect["unchanged"]?.let { if (it.jsonPrimitive.boolean) assertEquals(at, before, state) }
            expect["freshDemoWorld"]?.let { if (it.jsonPrimitive.boolean) assertNotEquals(at, before.demoWorld, state.demoWorld) }
        }
        val expected = case["expected"]?.jsonObject ?: return
        expected["visitedSteps"]?.let { assertEquals(id, it.jsonArray.map { s -> s.jsonPrimitive.content }, visited) }
        expected["finished"]?.let { assertEquals(id, it.jsonPrimitive.boolean, state.finished) }
    }

    private fun initial(p: JsonObject?): TourState {
        p ?: return TourState()
        return TourState(
            step = p["step"]?.jsonPrimitive?.content?.let(TourStep::valueOf),
            finished = p["finished"]?.jsonPrimitive?.boolean ?: false,
            pendingSpotifyRetry = p["pendingSpotifyRetry"]?.jsonPrimitive?.boolean ?: false,
            completedEffects = p["completedEffects"]?.jsonArray.orEmpty()
                .mapTo(mutableSetOf()) { OnceOnly.valueOf(it.jsonPrimitive.content.replaceFirstChar(Char::uppercase)) },
        )
    }

    private fun event(type: String, online: Boolean): TourEvent = when (type) {
        "started" -> TourEvent.Started(online)
        "acknowledged" -> TourEvent.Acknowledged
        "curtainPulled" -> TourEvent.CurtainPulled
        "bandPicked" -> TourEvent.BandPicked
        "gigAdded" -> TourEvent.GigAdded
        "roomOpened" -> TourEvent.RoomOpened
        "swipedBack" -> TourEvent.SwipedBack
        // The harness supplies the place (fixtures/tour/README.md).
        "contactExchanged" -> TourEvent.ContactExchanged(Place(59.9139, 10.7522))
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
        "setCompleted" -> TourEvent.SetCompleted
        "returnedFromPhotos" -> TourEvent.ReturnedFromPhotos
        "mediaAdded" -> TourEvent.MediaAdded(shared = true)
        "spotifyExported" -> TourEvent.SpotifyExported
        "spotifyDeclined" -> TourEvent.SpotifyDeclined
        "skipped" -> TourEvent.Skipped
        "resumed" -> TourEvent.Resumed
        "replayRequested" -> TourEvent.ReplayRequested
        else -> error("unknown event $type")
    }

    /** The epic's notation for a command, as the fixtures write it. */
    private fun notation(c: TourCommand): String = when (c) {
        is TourCommand.ShowCoachMark -> "showCoachMark(${c.mark.name.replaceFirstChar(Char::lowercase)})"
        TourCommand.LookUpBand -> "lookUpBand"
        is TourCommand.ImportDemoTicket -> "importDemoTicket(at: venue)"
        is TourCommand.AdvanceDemoClock -> "advanceDemoClock(${c.to.name.replaceFirstChar(Char::lowercase)})"
        TourCommand.DeliverGossip -> "deliverGossip(gapFill)"
        TourCommand.FillSetlist -> "fillSetlist"
        TourCommand.DeliverFriendSelfie -> "deliverFriendSelfie"
        TourCommand.PurgeDemoWorld -> "purgeDemoWorld"
        TourCommand.MarkTourFinished -> "markTourFinished"
    }
}
