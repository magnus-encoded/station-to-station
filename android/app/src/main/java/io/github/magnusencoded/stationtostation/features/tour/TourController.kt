package io.github.magnusencoded.stationtostation.features.tour

import io.github.magnusencoded.stationtostation.UiState
import io.github.magnusencoded.stationtostation.data.TimelineCache
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.features.gig.AttachClaim
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId

/** What the Tour saves and deletes. */
interface TourStore {
    suspend fun saveTour(state: TourState)
    suspend fun setOnboarded()
    suspend fun purgeDemoWorld()
    suspend fun markDemo(gigId: String)
}

/**
 * Runs [TourScript] against the app: feeds it events, keeps its state in [UiState] and in
 * storage, and carries out the commands this platform has an engine for so far.
 */
class TourController(
    private val state: () -> UiState,
    private val update: ((UiState) -> UiState) -> Unit,
    private val store: TourStore,
    private val isOnline: () -> Boolean,
    private val scope: CoroutineScope,
    private val meetFriend: TourMeetFriendEffects? = null,
    private val askLocation: suspend () -> Boolean = { false },
    private val night: TourNightArrivesEffects? = null,
    private val selfie: TourSelfieEffects? = null,
    private val log: TourLogEffects? = null,
    private val demoGig: suspend () -> FmSetlist? = { null },
    private val readCharacter: () -> TourCharacter = { error("Tour character not supplied") },
) {
    val character: TourCharacter by lazy(readCharacter)

    fun acknowledgeCard() {
        when (state().tour.step) {
            TourStep.S1 -> dispatch(TourEvent.Acknowledged)
            TourStep.S16 -> dispatch(TourEvent.GossipSent)
            else -> update { it.copy(coachMark = null) }
        }
    }

    fun logWritten(id: String, before: io.github.magnusencoded.stationtostation.data.StoredLog,
                   after: io.github.magnusencoded.stationtostation.data.StoredLog): Boolean {
        if (!isDemoGig(id) || log == null) return false
        log.write(id, after)
        when {
            state().tour.step == TourStep.S14 && after.songs.count { it.isNotBlank() } > before.songs.count { it.isNotBlank() } -> dispatch(TourEvent.LogEntryWritten)
            state().tour.step == TourStep.S15 && after.songs.count { it.isBlank() } > before.songs.count { it.isBlank() } -> dispatch(TourEvent.GapRecorded)
        }
        return true
    }

    fun demoLogRecord(id: String): TourLogEffects.Record? = if (isDemoGig(id)) log?.record(id) else null

    val hints = TourHintEffects(state, update, store, scope)
    val demoClock: StateFlow<LocalDateTime?> = night?.demoNow ?: MutableStateFlow(null)

    fun isDemoGig(gigId: String): Boolean = state().tour.running && night?.isDemoGig(gigId) == true

    fun now(gigId: String, realNow: LocalDateTime = LocalDateTime.now()): LocalDateTime =
        if (isDemoGig(gigId)) demoClock.value ?: realNow else realNow

    fun mapsUri(gig: FmSetlist): String? =
        venuePoint(gig)?.let { TourNightArrivesEffects.mapsUri(it, gig.venue?.name) }

    fun venuePoint(gig: FmSetlist): Pair<Double, Double>? =
        state().tour.venue?.takeIf { isDemoGig(gig.id) }?.let { it.latitude to it.longitude }

    fun calendarWorld(gigId: String): Int? = state().tour.demoWorld.takeIf { isDemoGig(gigId) }

    suspend fun calendarAdded(gigId: String, eventUri: String, world: Int? = calendarWorld(gigId)): Boolean {
        if (world == null) return true
        val kept = night?.recordCalendarEvent(eventUri) {
            isDemoGig(gigId) && state().tour.demoWorld == world
        } == true
        if (kept) sendForDemoGig(gigId, TourEvent.CalendarAdded)
        return kept
    }

    fun mapsOpened(gigId: String) = sendForDemoGig(gigId, TourEvent.MapsOpened)
    fun ticketShown(gigId: String) = sendForDemoGig(gigId, TourEvent.TicketShown)
    fun checkedIn(gigId: String) = sendForDemoGig(gigId, TourEvent.CheckedIn)

    fun returnedFromPhotos(gigId: String) = sendForDemoGig(gigId, TourEvent.ReturnedFromPhotos)

    /** Only for an attach on the demo **Gig** while the Tour runs; any other attach stays the person's own. */
    fun mediaClaim(gigId: String): AttachClaim? {
        val effects = selfie?.takeIf { isDemoGig(gigId) } ?: return null
        val world = state().tour.demoWorld
        return effects.claim(
            active = { state().tour.running && state().tour.demoWorld == world },
            added = { shared -> dispatch(TourEvent.MediaAdded(shared)) },
        )
    }

    /** What this phone may offer a real **Contact**: the Demo world's media left out. */
    fun offerable(cache: TimelineCache): TimelineCache = TourSelfieEffects.offerable(cache)

    private fun sendForDemoGig(gigId: String, event: TourEvent) {
        if (isDemoGig(gigId)) dispatch(event)
    }

    /** At launch, once the saved Tour is in state: resume an unfinished one, or offer it. */
    fun launch() {
        val current = state()
        when {
            current.tour.running -> resume()
            !current.onboarded -> dispatch(TourEvent.Started(isOnline()))
            // Only an install onboarded before the Tour existed has `onboarded` and no Tour state.
            current.tour == TourState() -> update { it.copy(tourUpgradePrompt = true) }
        }
    }

    /** Either answer closes the upgrade prompt for good. */
    fun acceptUpgradePrompt() {
        closeUpgradePrompt()
        replay()
    }

    fun dismissUpgradePrompt() = closeUpgradePrompt()

    private fun closeUpgradePrompt() {
        val after = state().tour.copy(upgradePromptDismissed = true)
        update { it.copy(tour = after, tourUpgradePrompt = false) }
        scope.launch { store.saveTour(after) }
    }

    /** Settings' "Resume tour", there only while a Tour is unfinished. */
    fun resume() {
        log?.restore()
        val tour = state().tour
        if (tour.running && tour.step == TourStep.S9 && OnceOnly.ImportDemoTicket in tour.completedEffects) {
            dispatch(TourEvent.TicketImported)
        } else {
            dispatch(TourEvent.Resumed)
        }
    }

    /** Settings' "Replay tour": from S1 with a fresh **Demo world**, finished or not. */
    fun replay() = dispatch(TourEvent.ReplayRequested)

    /** For the engines of S3 and S9: their effect is done and isn't asked for again. */
    fun completed(effect: OnceOnly) {
        val after = TourScript.completed(state().tour, effect)
        update { it.copy(tour = after) }
        scope.launch { store.saveTour(after) }
    }

    /** A **Gig** added while S4 waits for one is the **Demo world**'s; any other is the person's own. */
    fun gigAdded(gigId: String) {
        val tour = state().tour
        if (!tour.running || tour.step != TourStep.S4) return
        scope.launch { store.markDemo(gigId) }
        dispatch(TourEvent.GigAdded)
    }

    val exchangeFriendName: String?
        get() = meetFriend?.name?.takeIf { state().tour.running && state().tour.step == TourStep.S7 }

    suspend fun askLocationOnce(): Boolean = askLocation()

    suspend fun exchangeWithFriend() {
        if (exchangeFriendName == null) return
        val world = state().tour.demoWorld
        meetFriend?.exchange(
            active = { state().tour.running && state().tour.step == TourStep.S7 && state().tour.demoWorld == world },
            exchanged = { dispatch(TourEvent.ContactExchanged(it)) },
        )
    }

    fun dispatch(event: TourEvent) {
        val before = state().tour
        val (after, commands) = TourScript.on(before, event)
        if (after == before && commands.isEmpty()) return
        // Offered means started: an offline first launch leaves it to be offered again.
        val offered = !before.running && after.running
        val mark = commands.filterIsInstance<TourCommand.ShowCoachMark>().lastOrNull()?.mark
        update {
            it.copy(
                tour = after,
                contextHint = it.contextHint.takeUnless { after.running },
                coachMark = mark ?: if (after.step == before.step) it.coachMark else null,
                onboarded = it.onboarded || offered,
            )
        }
        scope.launch {
            store.saveTour(after)
            if (offered) store.setOnboarded()
            // A new Demo world starts clean, whatever a killed earlier Tour left behind.
            if (after.demoWorld != before.demoWorld) store.purgeDemoWorld()
            for (command in commands) {
                when (command) {
                    TourCommand.PurgeDemoWorld -> store.purgeDemoWorld()
                    // The band is looked up by the add dialog's own MusicBrainz completion
                    // as the person types; the Tour asks for no second lookup.
                    TourCommand.LookUpBand -> completed(OnceOnly.LookUpBand)
                    is TourCommand.ImportDemoTicket -> {
                        val imported = meetFriend?.importDemoTicket {
                            state().tour.running && state().tour.step == TourStep.S9 && state().tour.demoWorld == after.demoWorld
                        } == true
                        if (imported) {
                            completed(OnceOnly.ImportDemoTicket)
                            dispatch(TourEvent.TicketImported)
                        }
                    }
                    TourCommand.DeliverGossip, TourCommand.FillSetlist -> {
                        val gig = demoGig()
                        if (gig != null && log != null) {
                            val active = { state().tour.running && state().tour.demoWorld == after.demoWorld }
                            if (command == TourCommand.DeliverGossip) log.deliver(gig, active)
                            else if (log.fill(gig, now(gig.id).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), active)) dispatch(TourEvent.SetlistFilled)
                        }
                    }
                    TourCommand.DeliverFriendSelfie -> {
                        val gigId = night?.gigId
                        if (selfie != null && gigId != null) {
                            val at = now(gigId).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                            scope.launch {
                                selfie.deliverFriendSelfie(gigId, at) {
                                    state().tour.running && state().tour.demoWorld == after.demoWorld
                                }
                            }
                        }
                    }
                    is TourCommand.AdvanceDemoClock -> night?.advance(command.to) {
                        state().tour.running && state().tour.step == after.step && state().tour.demoWorld == after.demoWorld
                    }
                    else -> Unit
                }
            }
        }
    }
}
