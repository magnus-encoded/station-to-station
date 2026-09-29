package io.github.magnusencoded.stationtostation.data

import java.time.LocalDateTime

data class TourSetlistFill(val titles: List<String>, val usedSetlistFm: Boolean)

/** Chooses one real source, removes the user's entries and recording-title duplicates. */
fun tourSetlistFill(
    setlistFm: List<String>,
    musicBrainz: List<String>,
    entered: List<String>,
    target: Int = 10,
): TourSetlistFill {
    val fromSetlistFm = setlistFm.any { it.isNotBlank() }
    val source = if (fromSetlistFm) setlistFm else musicBrainz
    val room = (target - entered.count { it.isNotBlank() }).coerceAtLeast(0)
    val titles = source.filter { it.isNotBlank() }
        .fold(mutableListOf<String>()) { kept, title ->
            if (entered.none { sameSong(it, title) } && kept.none { sameSong(it, title) }) kept += title
            kept
        }.take(room)
    return TourSetlistFill(titles, fromSetlistFm)
}

/** The saved position of the first-run Tour. Pure data; the UI only renders commands. */
data class TourState(
    val step: TourStep? = null,
    val finished: Boolean = false,
    val pendingSpotifyRetry: Boolean = false,
    val upgradePromptDismissed: Boolean = false,
    val seenContextHints: Set<String> = emptySet(),
    val deliveredOnce: Set<TourCommand.Once> = emptySet(),
    val demoWorld: Int = 0,
    val returnedFromPhotos: Boolean = false,
    val demoVenueLat: Double? = null,
    val demoVenueLon: Double? = null,
    val demoNow: String? = null,
)

/** Tour time is an input to the ordinary gig rules, never a branch inside them. */
fun TourState.now(realNow: LocalDateTime): LocalDateTime =
    if (step != null && !finished) demoNow?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: realNow
    else realNow

/** The exchange fix stands in for GPS only while the Tour is active. */
fun TourState.location(realLocation: Pair<Double, Double>?): Pair<Double, Double>? =
    if (step != null && !finished && demoVenueLat != null && demoVenueLon != null) demoVenueLat to demoVenueLon
    else realLocation

enum class TourStep { S1, S2, S3, S4, S5, S6, S7, S8, S9, S10, S11, S12, S13, S14, S15, S16, S17, S18, S19, S20 }

sealed interface TourEvent {
    data class Started(val online: Boolean) : TourEvent
    data object Acknowledged : TourEvent
    data object CurtainPulled : TourEvent
    data object BandPicked : TourEvent
    data object GigAdded : TourEvent
    data object RoomOpened : TourEvent
    data object SwipedBack : TourEvent
    data class ContactExchanged(val latitude: Double, val longitude: Double) : TourEvent
    data object PinchedOut : TourEvent
    data object TicketImported : TourEvent
    data object CalendarAdded : TourEvent
    data object MapsOpened : TourEvent
    data object TicketShown : TourEvent
    data object CheckedIn : TourEvent
    data object LogEntryWritten : TourEvent
    data object GapRecorded : TourEvent
    data object GossipSent : TourEvent
    data object SetlistFilled : TourEvent
    data object ReturnedFromPhotos : TourEvent
    data object MediaAdded : TourEvent
    data object SpotifyExported : TourEvent
    data object SpotifyDeclined : TourEvent
    data object Skipped : TourEvent
    data object Resumed : TourEvent
    data object ReplayRequested : TourEvent
    data object ConnectivityLost : TourEvent
}

sealed interface TourCommand {
    data class ShowCoachMark(val step: TourStep) : TourCommand
    sealed interface Once : TourCommand
    data object LookUpBand : Once
    data object ImportDemoTicket : Once
    data class AdvanceDemoClock(val moment: String) : TourCommand
    data object DeliverGossip : TourCommand
    data object FillSetlist : TourCommand
    data object DeliverFriendSelfie : TourCommand
    data object PurgeDemoWorld : TourCommand
    data object MarkTourFinished : TourCommand
}

data class TourTransition(val state: TourState, val commands: List<TourCommand> = emptyList())

/** `(TourState, TourEvent) -> (TourState, [TourCommand])` from #587. */
fun runTour(state: TourState, event: TourEvent): TourTransition {
    if (event == TourEvent.SpotifyExported && state.pendingSpotifyRetry && state.step == null) {
        return TourTransition(state.copy(pendingSpotifyRetry = false))
    }
    if (event == TourEvent.ReplayRequested && state.finished) {
        return enter(state.copy(finished = false, pendingSpotifyRetry = false, deliveredOnce = emptySet(), demoWorld = state.demoWorld + 1), TourStep.S1)
    }
    if (event == TourEvent.Resumed && state.step != null && !state.finished) return enter(state, state.step)
    if (event == TourEvent.Skipped && state.step != null && !state.finished) return finish(state)
    if (event is TourEvent.Started) {
        if (!event.online || state.step != null || state.finished) return TourTransition(state)
        return enter(state.copy(demoWorld = state.demoWorld + 1), TourStep.S1)
    }
    val next = if (state.step == TourStep.S7 && event is TourEvent.ContactExchanged) {
        TourStep.S8
    } else when (state.step to event) {
        TourStep.S1 to TourEvent.Acknowledged -> TourStep.S2
        TourStep.S2 to TourEvent.CurtainPulled -> TourStep.S3
        TourStep.S3 to TourEvent.BandPicked -> TourStep.S4
        TourStep.S4 to TourEvent.GigAdded -> TourStep.S5
        TourStep.S5 to TourEvent.RoomOpened -> TourStep.S6
        TourStep.S6 to TourEvent.SwipedBack -> TourStep.S7
        TourStep.S8 to TourEvent.PinchedOut -> TourStep.S9
        TourStep.S9 to TourEvent.TicketImported -> TourStep.S10
        TourStep.S10 to TourEvent.CalendarAdded -> TourStep.S11
        TourStep.S11 to TourEvent.MapsOpened -> TourStep.S12
        TourStep.S12 to TourEvent.TicketShown -> TourStep.S13
        TourStep.S13 to TourEvent.CheckedIn -> TourStep.S14
        TourStep.S14 to TourEvent.LogEntryWritten -> TourStep.S15
        TourStep.S15 to TourEvent.GapRecorded -> TourStep.S16
        TourStep.S16 to TourEvent.GossipSent -> TourStep.S17
        TourStep.S17 to TourEvent.SetlistFilled -> TourStep.S18
        TourStep.S18 to TourEvent.ReturnedFromPhotos -> null // waits for mediaAdded at S18
        TourStep.S18 to TourEvent.MediaAdded -> if (state.returnedFromPhotos) TourStep.S19 else return TourTransition(state)
        TourStep.S19 to TourEvent.SpotifyExported -> TourStep.S20
        TourStep.S19 to TourEvent.SpotifyDeclined -> TourStep.S20
        else -> return TourTransition(state)
    }
    if (state.step == TourStep.S18 && event == TourEvent.ReturnedFromPhotos) {
        return TourTransition(state.copy(returnedFromPhotos = true))
    }
    val exchanged = event as? TourEvent.ContactExchanged
    val moved = state.copy(
        pendingSpotifyRetry = event == TourEvent.SpotifyDeclined,
        demoVenueLat = exchanged?.latitude ?: state.demoVenueLat,
        demoVenueLon = exchanged?.longitude ?: state.demoVenueLon,
    )
    return if (next == TourStep.S20) finish(moved.copy(step = TourStep.S20)) else enter(moved, next!!)
}

private fun enter(state: TourState, step: TourStep): TourTransition {
    val commands = entryCommands(step).filterNot { it is TourCommand.Once && it in state.deliveredOnce }
    val delivered = state.deliveredOnce + commands.filterIsInstance<TourCommand.Once>()
    return TourTransition(state.copy(step = step, deliveredOnce = delivered), commands)
}

private fun finish(state: TourState) = TourTransition(
    state.copy(step = null, finished = true),
    listOf(TourCommand.PurgeDemoWorld, TourCommand.MarkTourFinished),
)

private fun entryCommands(step: TourStep): List<TourCommand> = when (step) {
    TourStep.S1 -> listOf(TourCommand.ShowCoachMark(step))
    TourStep.S2 -> listOf(TourCommand.ShowCoachMark(step))
    TourStep.S3 -> listOf(TourCommand.ShowCoachMark(step), TourCommand.LookUpBand)
    TourStep.S4, TourStep.S5, TourStep.S6, TourStep.S7, TourStep.S8 -> listOf(TourCommand.ShowCoachMark(step))
    TourStep.S9 -> listOf(TourCommand.ImportDemoTicket)
    TourStep.S10 -> listOf(TourCommand.AdvanceDemoClock("approaching"), TourCommand.ShowCoachMark(step))
    TourStep.S11 -> listOf(TourCommand.ShowCoachMark(step))
    TourStep.S12 -> listOf(TourCommand.AdvanceDemoClock("doors"), TourCommand.ShowCoachMark(step))
    TourStep.S13 -> listOf(TourCommand.ShowCoachMark(step))
    TourStep.S14 -> listOf(TourCommand.AdvanceDemoClock("showStarted"), TourCommand.ShowCoachMark(step))
    TourStep.S15 -> listOf(TourCommand.ShowCoachMark(step))
    TourStep.S16 -> listOf(TourCommand.DeliverGossip, TourCommand.ShowCoachMark(step))
    TourStep.S17 -> listOf(TourCommand.FillSetlist)
    TourStep.S18 -> listOf(TourCommand.ShowCoachMark(step))
    TourStep.S19 -> listOf(TourCommand.AdvanceDemoClock("after"), TourCommand.ShowCoachMark(step))
    TourStep.S20 -> emptyList()
}
