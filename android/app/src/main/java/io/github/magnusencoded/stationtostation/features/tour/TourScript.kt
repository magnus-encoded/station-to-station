package io.github.magnusencoded.stationtostation.features.tour

import kotlinx.serialization.Serializable

/**
 * The **Tour** as one pure function, `(TourState, TourEvent) -> (TourState, [TourCommand])`,
 * to the step table in #587. It owns the order and the jumps of the **Demo clock**; the
 * platform only turns commands into coach marks and side effects.
 */
enum class TourStep(val mark: CoachMark?) {
    S1(CoachMark.Line), S2(CoachMark.Curtain), S3(CoachMark.Band), S4(CoachMark.AddGig),
    S5(CoachMark.OpenRoom), S6(CoachMark.SwipeBack), S7(CoachMark.Exchange), S8(CoachMark.PinchOut),
    S9(null), S10(CoachMark.Calendar), S11(CoachMark.Maps), S12(CoachMark.Ticket),
    S13(CoachMark.CheckIn), S14(CoachMark.Log), S15(CoachMark.Gap), S16(CoachMark.Gossip),
    S17(null), S18(CoachMark.Selfie), S19(CoachMark.Spotify), S20(null),
}

enum class CoachMark {
    Line, Curtain, Band, AddGig, OpenRoom, SwipeBack, Exchange, PinchOut, Calendar, Maps,
    Ticket, CheckIn, Log, Gap, Gossip, Selfie, Spotify,
}

/** The narrative moments the **Demo clock** jumps to. */
enum class DemoMoment { Approaching, Doors, ShowStarted, After }

@Serializable
data class Place(val latitude: Double, val longitude: Double)

/** Everything a restart has to get back; saved beside `onboarded`. */
@Serializable
data class TourState(
    /** Null when no Tour has started, or after one was skipped. S20 is the end. */
    val step: TourStep? = null,
    val finished: Boolean = false,
    val upgradePromptDismissed: Boolean = false,
    val pendingSpotifyRetry: Boolean = false,
    val seenContextHints: Set<String> = emptySet(),
    /** Side effects that finished; a resumed step doesn't ask for them again. */
    val completedEffects: Set<OnceOnly> = emptySet(),
    /** Bumped for each new **Demo world**, so a replay is known to need a fresh one. */
    val demoWorld: Int = 0,
    /** Where the exchange happened, which becomes the demo venue. */
    val venue: Place? = null,
    /** S18 waits for two events in turn; this is the first having arrived. */
    val returnedFromPhotos: Boolean = false,
)

/** A Tour is under way: started, and neither skipped nor at S20. */
val TourState.running: Boolean get() = step != null && !finished

enum class OnceOnly { LookUpBand, ImportDemoTicket }

sealed interface TourEvent {
    data class Started(val online: Boolean) : TourEvent
    data object Acknowledged : TourEvent
    data object CurtainPulled : TourEvent
    data object BandPicked : TourEvent
    data object GigAdded : TourEvent
    data object RoomOpened : TourEvent
    data object SwipedBack : TourEvent
    data class ContactExchanged(val location: Place?) : TourEvent
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
    data class MediaAdded(val shared: Boolean) : TourEvent
    data object SpotifyExported : TourEvent
    data object SpotifyDeclined : TourEvent
    data object Skipped : TourEvent
    data object Resumed : TourEvent
    data object ReplayRequested : TourEvent
}

sealed interface TourCommand {
    data class ShowCoachMark(val mark: CoachMark) : TourCommand
    data object LookUpBand : TourCommand
    data class ImportDemoTicket(val at: Place?) : TourCommand
    data class AdvanceDemoClock(val to: DemoMoment) : TourCommand
    data object DeliverGossip : TourCommand
    data object FillSetlist : TourCommand
    data object DeliverFriendSelfie : TourCommand
    data object PurgeDemoWorld : TourCommand
    data object MarkTourFinished : TourCommand
}

data class TourTransition(val state: TourState, val commands: List<TourCommand> = emptyList())

object TourScript {

    fun on(state: TourState, event: TourEvent): TourTransition {
        val step = state.step
        return when (event) {
            is TourEvent.Started ->
                if (event.online && step == null && !state.finished) enter(state.copy(demoWorld = state.demoWorld + 1), TourStep.S1)
                else TourTransition(state)
            // From Settings, at any time: a finished Tour doesn't block it.
            TourEvent.ReplayRequested -> enter(
                state.copy(
                    finished = false, completedEffects = emptySet(), venue = null,
                    returnedFromPhotos = false, demoWorld = state.demoWorld + 1,
                ),
                TourStep.S1,
            )
            TourEvent.Resumed ->
                if (step != null && !state.finished) enter(state, step) else TourTransition(state)
            // Skip at S20 still clears up; the Tour had already finished itself.
            TourEvent.Skipped -> if (step != null) TourTransition(
                state.copy(step = null, finished = true, completedEffects = emptySet(), venue = null, returnedFromPhotos = false),
                listOf(TourCommand.PurgeDemoWorld),
            ) else TourTransition(state)
            else -> when {
                // The retry is offered after the Tour, outside any step.
                !state.running && event == TourEvent.SpotifyExported && state.pendingSpotifyRetry ->
                    TourTransition(state.copy(pendingSpotifyRetry = false))
                step != null -> advance(state, step, event)
                else -> TourTransition(state)
            }
        }
    }

    private fun advance(state: TourState, step: TourStep, event: TourEvent): TourTransition = when {
        step == TourStep.S7 && event is TourEvent.ContactExchanged ->
            enter(state.copy(venue = event.location), TourStep.S8)
        step == TourStep.S15 && event == TourEvent.GapRecorded ->
            enter(state, TourStep.S16).let { it.copy(commands = listOf(TourCommand.DeliverGossip) + it.commands) }
        step == TourStep.S18 && event == TourEvent.ReturnedFromPhotos ->
            TourTransition(state.copy(returnedFromPhotos = true))
        step == TourStep.S18 && event is TourEvent.MediaAdded && state.returnedFromPhotos ->
            enter(state.copy(returnedFromPhotos = false), TourStep.S19)
                .let { it.copy(commands = listOf(TourCommand.DeliverFriendSelfie) + it.commands) }
        step == TourStep.S19 && event == TourEvent.SpotifyExported -> finish(state)
        step == TourStep.S19 && event == TourEvent.SpotifyDeclined -> finish(state.copy(pendingSpotifyRetry = true))
        AWAITS[step] == event -> enter(state, TourStep.entries[step.ordinal + 1])
        else -> TourTransition(state)
    }

    /** The steps whose one awaited event carries no payload. */
    private val AWAITS: Map<TourStep, TourEvent> = mapOf(
        TourStep.S1 to TourEvent.Acknowledged,
        TourStep.S2 to TourEvent.CurtainPulled,
        TourStep.S3 to TourEvent.BandPicked,
        TourStep.S4 to TourEvent.GigAdded,
        TourStep.S5 to TourEvent.RoomOpened,
        TourStep.S6 to TourEvent.SwipedBack,
        TourStep.S8 to TourEvent.PinchedOut,
        TourStep.S9 to TourEvent.TicketImported,
        TourStep.S10 to TourEvent.CalendarAdded,
        TourStep.S11 to TourEvent.MapsOpened,
        TourStep.S12 to TourEvent.TicketShown,
        TourStep.S13 to TourEvent.CheckedIn,
        TourStep.S14 to TourEvent.LogEntryWritten,
        TourStep.S16 to TourEvent.GossipSent,
        TourStep.S17 to TourEvent.SetlistFilled,
    )

    private fun enter(state: TourState, step: TourStep): TourTransition {
        val commands = mutableListOf<TourCommand>()
        when (step) {
            TourStep.S10 -> commands += TourCommand.AdvanceDemoClock(DemoMoment.Approaching)
            TourStep.S12 -> commands += TourCommand.AdvanceDemoClock(DemoMoment.Doors)
            TourStep.S14 -> commands += TourCommand.AdvanceDemoClock(DemoMoment.ShowStarted)
            TourStep.S19 -> commands += TourCommand.AdvanceDemoClock(DemoMoment.After)
            else -> Unit
        }
        step.mark?.let { commands += TourCommand.ShowCoachMark(it) }
        val done = state.completedEffects
        if (step == TourStep.S3 && OnceOnly.LookUpBand !in done) commands += TourCommand.LookUpBand
        if (step == TourStep.S9 && OnceOnly.ImportDemoTicket !in done) {
            commands += TourCommand.ImportDemoTicket(state.venue)
        }
        if (step == TourStep.S17) commands += TourCommand.FillSetlist
        return TourTransition(state.copy(step = step), commands)
    }

    /** S20: the playlist is kept, everything else the Tour made goes. */
    private fun finish(state: TourState) = TourTransition(
        state.copy(step = TourStep.S20, finished = true, completedEffects = emptySet(), venue = null, returnedFromPhotos = false),
        listOf(TourCommand.PurgeDemoWorld, TourCommand.MarkTourFinished),
    )

    /** The platform reports a once-only effect done, so a resumed step won't repeat it. */
    fun completed(state: TourState, effect: OnceOnly): TourState =
        state.copy(completedEffects = state.completedEffects + effect)
}
