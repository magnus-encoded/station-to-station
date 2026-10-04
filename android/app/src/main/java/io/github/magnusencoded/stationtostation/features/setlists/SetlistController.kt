package io.github.magnusencoded.stationtostation.features.setlists

import io.github.magnusencoded.stationtostation.SetlistSource
import io.github.magnusencoded.stationtostation.UiState
import io.github.magnusencoded.stationtostation.data.ParsedTicket
import io.github.magnusencoded.stationtostation.data.StoredSetlistFmHit
import io.github.magnusencoded.stationtostation.data.TimelineStore
import io.github.magnusencoded.stationtostation.data.gossip.gossipParticipationEnds
import io.github.magnusencoded.stationtostation.data.isLocal
import io.github.magnusencoded.stationtostation.data.parseFmDate
import io.github.magnusencoded.stationtostation.data.setlistfm.FmArtist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.data.setlistfm.LOOKUP_FRICTION_MESSAGE
import io.github.magnusencoded.stationtostation.data.setlistfm.LookupGig
import io.github.magnusencoded.stationtostation.data.setlistfm.LookupOutcome
import io.github.magnusencoded.stationtostation.data.setlistfm.LookupPlan
import io.github.magnusencoded.stationtostation.data.setlistfm.ManualLookup
import io.github.magnusencoded.stationtostation.data.setlistfm.SetlistFmClient
import io.github.magnusencoded.stationtostation.data.setlistfm.SetlistFmKey
import io.github.magnusencoded.stationtostation.data.setlistfm.SetlistFmRateLimited
import io.github.magnusencoded.stationtostation.data.setlistfm.asStoredHit
import io.github.magnusencoded.stationtostation.data.setlistfm.chipHits
import io.github.magnusencoded.stationtostation.data.setlistfm.manualSetlistFmLookup
import io.github.magnusencoded.stationtostation.data.setlistfm.setlistFmLookupOutcome
import io.github.magnusencoded.stationtostation.data.setlistfm.setlistFmLookupPlan
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * setlist.fm search, the attended import, the open show's refresh and the automatic
 * lookups for local **Gigs**. Pieces other features own arrive as functions.
 */
class SetlistController(
    private val state: () -> UiState,
    private val update: ((UiState) -> UiState) -> Unit,
    private val setlistFm: SetlistFmClient,
    private val timelines: TimelineStore,
    private val setlistFmKey: suspend () -> SetlistFmKey?,
    private val sharedQuotaSpentAtValue: suspend () -> Long?,
    private val gossipStoppedAt: suspend () -> Long,
    private val scope: CoroutineScope,
    private val fail: (Exception) -> Unit,
    private val consumeError: () -> Unit,
    private val saveSettingsNow: suspend (apiKey: String, clientId: String) -> Unit,
    private val saveMySetlistFmUser: (String) -> Unit,
    private val adoptSetlist: suspend (gigId: String, setlistId: String, fresh: FmSetlist?, notice: Boolean) -> Boolean,
    private val lineArtists: () -> List<FmArtist>,
) {

    private companion object {
        /** The automatic checks' longest sleep: a night coming due is noticed within this. */
        const val LOOKUP_CHECK_CAP_MS = 5 * 60_000L
    }

    fun setArtistQuery(q: String) = update { it.copy(artistQuery = q) }
    fun setUserQuery(q: String) = update { it.copy(userQuery = q) }

    fun searchArtists() {
        val query = state().artistQuery.trim()
        if (query.isEmpty()) return
        scope.launch {
            update { it.copy(searchLoading = true) }
            try {
                val result = setlistFm.searchArtists(query)
                update { it.copy(artistResults = result.artist, searchLoading = false) }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /** Loads setlists for an artist. Returns immediately; UI navigates and observes state. */
    fun openArtist(artist: FmArtist) {
        update {
            it.copy(
                source = SetlistSource.ARTIST,
                setlistsTitle = artist.name,
                setlists = emptyList(),
                setlistsPage = 1,
                setlistsTotal = 0,
                setlistsLoading = true,
            )
        }
        scope.launch {
            try {
                val result = setlistFm.artistSetlists(artist.mbid)
                update {
                    it.copy(setlists = result.setlist, setlistsTotal = result.total, setlistsLoading = false)
                }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /**
     * Timeline import: persists a just-entered API key first (so the fetch sees
     * it — saveSettings alone is fire-and-forget and would race), then loads the
     * user's attended concerts. [apiKey] is null when a key is already available.
     */
    fun importAttended(username: String, apiKey: String?) {
        scope.launch {
            consumeError()
            if (!apiKey.isNullOrBlank()) saveSettingsNow(apiKey.trim(), state().spotifyClientId)
            setUserQuery(username)
            openUserAttended()
        }
    }

    fun openUserAttended() {
        val userId = state().userQuery.trim()
        if (userId.isEmpty()) return
        // "My concerts" is your own username; adopt it as the identity used to stamp
        // playlists and find shared concerts — but never clobber an explicit choice.
        if (state().mySetlistFmUser.isBlank()) saveMySetlistFmUser(userId)
        update {
            it.copy(
                source = SetlistSource.USER,
                setlistsTitle = "Attended by $userId",
                setlists = emptyList(),
                setlistsPage = 1,
                setlistsTotal = 0,
                setlistsLoading = true,
            )
        }
        scope.launch {
            try {
                val result = setlistFm.userAttended(userId)
                update {
                    it.copy(setlists = result.setlist, setlistsTotal = result.total, setlistsLoading = false)
                }
                timelines.save(
                    shows = mapOf(userId to result.setlist),
                    attendedTotals = mapOf(userId to result.total),
                )
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /**
     * Re-fetches the open show from setlist.fm. The one thing that changes under
     * you here is the setlist itself — you log a night, go and type the songs in
     * on the site, come back. Refreshes in place: the cached spine keeps its
     * order and every other night untouched.
     */
    fun refreshSelectedSetlist() {
        val open = state().selectedSetlist ?: return
        // A local Gig's id is this app's, not setlist.fm's — asking them for it is a
        // guaranteed 404. A pull on one asks setlist.fm whether the night is there yet
        // instead (#531), by artist and day, the way the automatic checks do.
        if (open.isLocal()) {
            refreshLocalGig(open.id)
            return
        }
        if (state().setlistsLoading) return
        update { it.copy(setlistsLoading = true) }
        scope.launch {
            try {
                val fresh = setlistFm.setlist(open.id)
                val setlists = state().setlists.map { if (it.id == fresh.id) fresh else it }
                // A gig I'm going to lives in its own list, so refreshing one has to
                // write back there — otherwise the night's setlist appears on screen
                // and is gone again on the next launch. Provenance is untouched:
                // songs landing is setlist.fm filling a record in, not evidence I went.
                val wasPlanned = state().plannedGigs.any { it.id == fresh.id }
                update {
                    it.copy(
                        setlists = setlists,
                        plannedGigs = if (wasPlanned) {
                            it.plannedGigs.map { g -> if (g.id == fresh.id) fresh else g }
                        } else {
                            it.plannedGigs
                        },
                        selectedSetlist = fresh,
                        setlistsLoading = false,
                    )
                }
                if (wasPlanned) timelines.savePlanned(fresh)
                val user = state().userQuery.trim()
                if (user.isNotEmpty()) timelines.save(shows = mapOf(user to setlists))
            } catch (e: Exception) {
                // A refresh is optional freshness, never a fatal operation: the night
                // is already on screen from cache, with its artist, venue and date.
                // `fail` sets the global error, and doing that here tore the screen up
                // mid-gesture — the pull's own fling was still running, which is how a
                // 404 on a 1985 setlist came back as "measure is called on a
                // deactivated node". A notice says what happened and changes nothing.
                //
                // The id and code are logged because this only ever fails in the field,
                // on someone else's phone, where there is no other way to find out
                // which night and which status it was.
                android.util.Log.w("StationToStation", "refresh failed for setlist ${open.id}: ${e.message}")
                update {
                    it.copy(
                        setlistsLoading = false,
                        notice = "setlist.fm didn't have that one just now — showing what's saved.",
                    )
                }
            }
        }
    }

    /**
     * A pull on local Gig [gigId] (#531): one lookup now, whatever the schedule says,
     * unless the last one went out under a minute ago — then nothing is sent or
     * stamped, and the notice says the checks carry on.
     */
    private fun refreshLocalGig(gigId: String) {
        if (state().setlistsLoading) return
        val last = state().attendanceByGig[gigId]?.setlistFmLookup?.lastLookupAt
        val now = Instant.now()
        if (manualSetlistFmLookup(last?.let(Instant::ofEpochMilli), now) == ManualLookup.FRICTION) {
            update { it.copy(notice = LOOKUP_FRICTION_MESSAGE) }
            return
        }
        update { it.copy(setlistsLoading = true) }
        scope.launch {
            try {
                lookUpLocalGig(gigId, manual = true)
            } finally {
                update { it.copy(setlistsLoading = false) }
            }
        }
    }

    /**
     * One setlist.fm lookup for local Gig [gigId] (#531): `search/setlists` by its artist
     * and day, no venue, held to the night by [setlistFmLookupOutcome]. A sure hit is
     * adopted with the "Adopted" notice; a doubtful one becomes the "Possible match"
     * chip; nothing only stamps. [manual] is a pull, which says so when setlist.fm
     * refuses or fails; the automatic checks say nothing.
     *
     * False when setlist.fm refused for its quota, which stops a batch of checks.
     */
    suspend fun lookUpLocalGig(gigId: String, manual: Boolean): Boolean {
        val gig = timelines.load().gigs[gigId] ?: return true
        if (gig.setlistId != null || gig.artist.isBlank() || parseFmDate(gig.date) == null) return true
        val at = System.currentTimeMillis()
        val hits = try {
            setlistFm.searchSetlists(gig.artist, gig.date).setlist
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The request went out (or was refused on the quota): stamped either way,
            // so the schedule does not ask again at once.
            withContext(NonCancellable) { timelines.editSetlistFmLookup(gigId) { it.lookedUp(at) } }
                ?.let { settled -> update { it.copy(attendanceByGig = it.attendanceByGig + (gigId to settled)) } }
            android.util.Log.w("StationToStation", "setlist.fm lookup failed for gig $gigId: ${e.message}")
            if (e is SetlistFmRateLimited) {
                if (manual) fail(e)
                return false
            }
            if (manual) {
                update { it.copy(notice = "setlist.fm didn't have that one just now — showing what's saved.") }
            }
            return true
        }
        // From here the store is written and the night may move to its new id: all of
        // it, or none of it, whatever stops the checks meanwhile.
        withContext<Unit>(NonCancellable) {
            val ticket = ParsedTicket(artist = gig.artist, venue = gig.venue, date = gig.date)
            var outcome: LookupOutcome? = null
            val settled = timelines.editSetlistFmLookup(gigId) { had ->
                val next = setlistFmLookupOutcome(ticket, hits, lineArtists(), had, at)
                outcome = next
                // A sure hit settles any question a chip was still asking.
                if (next is LookupOutcome.Adopt) next.next.asking(emptyList()) else next.next
            } ?: return@withContext
            update { it.copy(attendanceByGig = it.attendanceByGig + (gigId to settled)) }
            val adopt = outcome as? LookupOutcome.Adopt ?: return@withContext
            adoptSetlist(gigId, adopt.hit.id, fresh = adopt.hit, notice = true)
        }
        return true
    }

    private var lookupChecks: Job? = null

    /**
     * The automatic setlist.fm checks (#531), while the app is in the foreground: at
     * launch, on coming back, and on a timer. Each pass plans every local Gig with
     * [setlistFmLookupPlan], looks up the ones due one at a time, and sleeps until the
     * next is due, five minutes at most. Nothing at all without a setlist.fm key.
     * Called from the root composable's start; [stopLookupChecks] on its stop.
     */
    fun startLookupChecks() {
        if (lookupChecks?.isActive == true) return
        lookupChecks = scope.launch {
            while (true) {
                val due = lookupPlan()
                for (gigId in due?.dueNow.orEmpty()) {
                    if (!lookUpLocalGig(gigId, manual = false)) break
                }
                val next = lookupPlan()
                val now = System.currentTimeMillis()
                val sleep = when {
                    next == null -> LOOKUP_CHECK_CAP_MS
                    next.dueNow.isNotEmpty() -> LOOKUP_CHECK_CAP_MS
                    else -> next.nextWakeAt?.toEpochMilli()?.minus(now)
                        ?.coerceIn(1_000L, LOOKUP_CHECK_CAP_MS) ?: LOOKUP_CHECK_CAP_MS
                }
                delay(sleep)
            }
        }
    }

    /** The app left the foreground: no lookups until [startLookupChecks] again. */
    fun stopLookupChecks() {
        lookupChecks?.cancel()
        lookupChecks = null
    }

    /**
     * What the automatic checks do next, from the store: every local Gig with a claim on
     * it (planned or attended) and an artist to search by. Null without a setlist.fm key.
     */
    private suspend fun lookupPlan(): LookupPlan? {
        val key = setlistFmKey() ?: return null
        val cache = timelines.load()
        val ends = gossipParticipationEnds(timelines, gossipStoppedAt())
        val gigs = cache.gigs.values
            .filter { it.setlistId == null && it.artist.isNotBlank() && parseFmDate(it.date) != null }
            .mapNotNull { gig ->
                val attendance = cache.gigAttendance[gig.id] ?: return@mapNotNull null
                LookupGig(
                    id = gig.id,
                    date = gig.date,
                    local = true,
                    lookup = attendance.setlistFmLookup,
                    participationUntil = ends[gig.id]?.takeIf { it > 0L }?.let(Instant::ofEpochMilli),
                )
            }
        return setlistFmLookupPlan(
            gigs = gigs,
            now = Instant.now(),
            zone = ZoneId.systemDefault(),
            sharedKey = key.shared,
            sharedQuotaSpentAt = sharedQuotaSpentAtValue(),
        )
    }

    /**
     * The hits local Gig [gigId]'s "Possible match on setlist.fm" chip asks about, best
     * first: the stored snapshot, or — where that was lost but ids are still pending —
     * each fetched from setlist.fm afresh. Empty when nothing is pending.
     */
    suspend fun setlistFmChipHits(gigId: String): List<StoredSetlistFmHit> {
        val lookup = state().attendanceByGig[gigId]?.setlistFmLookup ?: return emptyList()
        if (!lookup.possibleMatchPending) return emptyList()
        return lookup.chipHits().ifEmpty {
            lookup.pendingHitIds.mapNotNull { id -> runCatching { setlistFm.setlist(id) }.getOrNull()?.asStoredHit() }
        }
    }

    /** The chip's "Yes": the question is settled and local Gig [gigId] takes hit [setlistId]. */
    fun acceptSetlistFmMatch(gigId: String, setlistId: String) {
        scope.launch {
            val settled = timelines.editSetlistFmLookup(gigId) { it.asking(emptyList()) }
            if (settled != null) update { it.copy(attendanceByGig = it.attendanceByGig + (gigId to settled)) }
            if (!adoptSetlist(gigId, setlistId, fresh = null, notice = true)) {
                update { it.copy(errorKind = null, error = "That night already has a setlist.fm id.") }
            }
        }
    }

    /** The chip's "None of these": every hit it asked about is not this night, and the checks resume. */
    fun rejectSetlistFmMatches(gigId: String) {
        scope.launch {
            val settled = timelines.editSetlistFmLookup(gigId) { it.rejectingPending() } ?: return@launch
            update { it.copy(attendanceByGig = it.attendanceByGig + (gigId to settled)) }
        }
    }

    fun loadMoreSetlists() {
        val s = state()
        if (s.setlistsLoading || s.setlists.size >= s.setlistsTotal) return
        val nextPage = s.setlistsPage + 1
        update { it.copy(setlistsLoading = true) }
        scope.launch {
            try {
                val result = when (s.source) {
                    SetlistSource.USER -> setlistFm.userAttended(s.userQuery.trim(), nextPage)
                    SetlistSource.ARTIST -> {
                        val mbid = s.setlists.firstOrNull()?.artist?.mbid
                            ?: throw IllegalStateException("No artist context")
                        setlistFm.artistSetlists(mbid, nextPage)
                    }
                }
                update {
                    it.copy(
                        // By id: resuming a cached spine refetches its last, part-full
                        // page, and a duplicate row would collide on the LazyColumn key.
                        setlists = (it.setlists + result.setlist).distinctBy { s -> s.id },
                        setlistsPage = nextPage,
                        setlistsTotal = result.total,
                        setlistsLoading = false,
                    )
                }
                // Store the accumulated spine, or scrolling back through history
                // pays for those pages again on the next launch.
                if (s.source == SetlistSource.USER) {
                    val user = s.userQuery.trim()
                    timelines.save(
                        shows = mapOf(user to state().setlists),
                        attendedTotals = mapOf(user to result.total),
                    )
                }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }
}
