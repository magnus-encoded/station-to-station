package io.github.magnusencoded.stationtostation.features.navigation

import io.github.magnusencoded.stationtostation.AddGigLink
import io.github.magnusencoded.stationtostation.GigLink
import io.github.magnusencoded.stationtostation.LinkIntent
import io.github.magnusencoded.stationtostation.LinkScreen
import io.github.magnusencoded.stationtostation.OpenGigPlan
import io.github.magnusencoded.stationtostation.UiState
import io.github.magnusencoded.stationtostation.data.Festivals
import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.data.StoredLog
import io.github.magnusencoded.stationtostation.data.fmDate
import io.github.magnusencoded.stationtostation.data.laneKey
import io.github.magnusencoded.stationtostation.data.plannedLane
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.planOpenGig
import io.github.magnusencoded.stationtostation.writing
import io.github.magnusencoded.stationtostation.ui.TimelineNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Where the app is looking: the weave, linked screens and **Gigs**, open **Festivals**,
 * hidden **Lines**, the **Collection** walk. Features it reaches into arrive as functions.
 */
class NavigationController(
    private val state: () -> UiState,
    private val update: ((UiState) -> UiState) -> Unit,
    private val scope: CoroutineScope,
    private val fetchSetlist: suspend (String) -> FmSetlist,
    private val saveHiddenLines: suspend (Map<String, Long>) -> Unit,
    private val resolveFestivalsFor: suspend (List<FmSetlist>, Festivals) -> Festivals,
    private val refreshLine: (Friend) -> Unit,
    private val writeLog: (String, (StoredLog) -> StoredLog) -> Unit,
    private val fail: (Exception) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * Open or close the woven view. The one place that decides it, so a pinch, a card
     * swap and a key press cannot disagree about when there is anything to open onto.
     */
    fun setZoomedOut(on: Boolean) = update {
        if (on && it.friends.isEmpty()) it else it.copy(zoomedOut = on)
    }

    /**
     * What a parsed `station-to-station://` link asks for. Only records the intent:
     * [UiState.linkedGig], [UiState.linkedDate], [UiState.addGigLink] and
     * [UiState.linkScreen] are acted on by the timeline and the navigation, which are
     * the only places that know where a row or a screen ended up. Pass-through links
     * are the caller's; they never arrive here.
     */
    fun handleLink(intent: LinkIntent) {
        when (intent) {
            is LinkIntent.Open -> openScreen(intent.screen, intent.date)
            is LinkIntent.OpenGig -> openGig(intent.id)
            is LinkIntent.AddGig -> openAddGig(intent.artist, intent.venue, intent.date)
            is LinkIntent.WriteToLog -> openGig(intent.gigId) {
                writeLog(intent.gigId) { it.writing(intent.appends, intent.replacements) }
            }
            is LinkIntent.LegacyPlace -> {
                if (intent.at != GigLink.SETLIST) setZoomedOut(intent.at == GigLink.WOVEN)
                update { it.copy(linkedGig = intent.gigId, linkedGigAs = intent.at) }
            }
            LinkIntent.LegacyMe -> openScreen(LinkScreen.TIMELINE, null)
            is LinkIntent.LegacyFixture, is LinkIntent.PassThrough -> Unit
        }
    }

    private fun openScreen(screen: LinkScreen, date: String?) = update {
        val zoomedOut = when (screen) {
            LinkScreen.TIMELINE -> false
            LinkScreen.TIMELINES -> it.friends.isNotEmpty()
            else -> it.zoomedOut
        }
        it.copy(linkScreen = screen, zoomedOut = zoomedOut, linkedDate = date?.let(LocalDate::parse))
    }

    /**
     * A **Gig** on my **Line** opens as it is. An unknown setlist.fm id is fetched and
     * opened without being kept: joining it is a question the **Room** asks, and an
     * invite never answers it. [then] runs once it is open. An id with nothing to fetch
     * says so and goes nowhere.
     */
    private fun openGig(id: String, then: () -> Unit = {}) {
        val land = {
            update { it.copy(linkScreen = LinkScreen.TIMELINE, linkedGig = id, linkedGigAs = GigLink.SETLIST) }
            then()
        }
        val mine = state().let { s -> s.setlists.any { it.id == id } || s.plannedGigs.any { it.id == id } }
        when (planOpenGig(id, mine)) {
            OpenGigPlan.OPEN -> land()
            OpenGigPlan.FETCH_THEN_OPEN -> scope.launch {
                try {
                    openShow(fetchSetlist(id))
                    land()
                } catch (e: Exception) {
                    fail(e)
                }
            }
            OpenGigPlan.REFUSE -> update {
                it.copy(errorKind = null, error = "That doesn't look like a setlist.fm gig link.")
            }
        }
    }

    private fun openAddGig(artist: String?, venue: String?, date: String?) {
        val day = date?.let(LocalDate::parse)
        val link = AddGigLink(
            artist = artist.orEmpty(),
            venue = venue.orEmpty(),
            date = day?.let(::fmDate).orEmpty(),
        )
        update { it.copy(linkScreen = LinkScreen.TIMELINE, addGigLink = link) }
    }

    /** The **Gig** nearest a linked date, scrolled to by the timeline like any linked **Gig**. */
    fun linkGig(id: String, at: GigLink) = update { it.copy(linkedGig = id, linkedGigAs = at) }

    fun consumeLinkScreen() = update { it.copy(linkScreen = null) }

    fun consumeLinkedDate() = update { it.copy(linkedDate = null) }

    fun consumeAddGigLink() = update { it.copy(addGigLink = null) }

    fun consumeGigLink() = update { it.copy(linkedGig = null, linkedGigAs = null) }

    /** Open or close a festival in place. A new set each time, so remember() sees it. */
    fun toggleFestival(key: String) = update {
        it.copy(
            openFestivals = if (key in it.openFestivals) it.openFestivals - key
            else it.openFestivals + key,
        )
    }

    /**
     * Hide or show one **Line** in the weave. The gesture is its own undo, so there is
     * one entry point and no separate restore. A new map each time, so remember() sees
     * it. Persisted with the toggle-off moment, which the legend's recency
     * order sorts by. Showing a **Line** also asks setlist.fm for its latest.
     */
    fun toggleLineHidden(lane: String) {
        val hiddenAt = if (lane in state().hiddenAt) {
            state().hiddenAt - lane
        } else {
            state().hiddenAt + (lane to now())
        }
        update { it.copy(hiddenAt = hiddenAt) }
        scope.launch { saveHiddenLines(hiddenAt) }
        // Switching a **Line** on is a reason to look: it may have been off for a while.
        if (lane !in hiddenAt) {
            state().friends.firstOrNull { it.laneKey == lane }?.let(refreshLine)
        }
    }

    fun openFestival(key: String) = update {
        it.copy(openFestivals = it.openFestivals + key)
    }

    /** The gig behind a link, wherever it is already loaded — mine or any lane's. */
    fun knownGig(id: String): FmSetlist? =
        state().setlists.firstOrNull { it.id == id }
            ?: state().plannedGigs.firstOrNull { it.id == id }
            ?: state().showsByFriend.values.firstNotNullOfOrNull { shows ->
                shows.firstOrNull { it.id == id }
            }
            ?: state().selectedSetlist?.takeIf { it.id == id }

    /**
     * Asks setlist.fm whether the unidentified evenings on the timeline belong to a
     * **Festival**. The rule itself — which evenings, what counts as already asked, and
     * that the answers are stored — lives in the logic layer; this is the screen's
     * caller of it.
     */
    fun resolveFestivals() {
        val s = state()
        scope.launch {
            // Two passes rather than one concatenated list, so a night ahead and a
            // night behind can never be read as one evening. The future lane grows its
            // own Sections and they want identities too.
            val found = resolveFestivalsFor(s.setlists, s.festivals)
            val alsoAhead = resolveFestivalsFor(
                plannedLane(s.plannedGigs, s.attendanceByGig),
                found,
            )
            update { it.copy(festivals = it.festivals + alsoAhead) }
        }
    }

    /** Opens a show for viewing (its real setlist) without the Spotify match/cover
     *  machinery — that only starts when the user converts it to a playlist. */
    fun openShow(setlist: FmSetlist) = update { it.copy(selectedSetlist = setlist) }

    /**
     * Enters the **Collection resolution** on this run of **Gigs**. A state at
     * the Line, not a route — there is nothing to pop, only a value to set back to
     * null, which is what [closeCollectionWalk] and the reverse gesture both do.
     */
    fun openCollectionWalk(node: TimelineNode.Several) =
        update { it.copy(selectedCollection = node) }

    /** Leaves the **Collection resolution**, landing back on the Line exactly where it
     *  was left — nothing moved, so there is nowhere else it could land. */
    fun closeCollectionWalk() = update { it.copy(selectedCollection = null) }
}
