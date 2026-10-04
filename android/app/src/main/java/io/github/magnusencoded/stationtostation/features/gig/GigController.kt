package io.github.magnusencoded.stationtostation.features.gig

import io.github.magnusencoded.stationtostation.GigStanding
import io.github.magnusencoded.stationtostation.UiState
import io.github.magnusencoded.stationtostation.adopting
import io.github.magnusencoded.stationtostation.data.Band
import io.github.magnusencoded.stationtostation.data.DeviceLocation
import io.github.magnusencoded.stationtostation.data.StoredAttendance
import io.github.magnusencoded.stationtostation.data.StoredLog
import io.github.magnusencoded.stationtostation.data.StoredMedia
import io.github.magnusencoded.stationtostation.data.TimelineStore
import io.github.magnusencoded.stationtostation.data.gossip.GigIdentity
import io.github.magnusencoded.stationtostation.data.gossip.GossipEnvelope
import io.github.magnusencoded.stationtostation.data.gossip.GossipStore
import io.github.magnusencoded.stationtostation.data.gossip.gossipExpiry
import io.github.magnusencoded.stationtostation.data.gossip.gossipParticipationEnds
import io.github.magnusencoded.stationtostation.data.nameKey
import io.github.magnusencoded.stationtostation.data.photos.PhotoRepository
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.data.setlistfm.SetlistFmClient
import io.github.magnusencoded.stationtostation.data.setlistfm.parseSetlistId
import io.github.magnusencoded.stationtostation.gigStanding
import io.github.magnusencoded.stationtostation.sortedPlanned
import io.github.magnusencoded.stationtostation.ui.atVenue
import io.github.magnusencoded.stationtostation.ui.canCheckInManually
import io.github.magnusencoded.stationtostation.ui.checkInCandidate
import io.github.magnusencoded.stationtostation.ui.venueMapsQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * A **Gig**'s own record: its **Log**, notes, setlist.fm adoption, deletion and check-in.
 *
 * Check-in establishes the attendance fact here; distributing it is gossip's, reached
 * through [gossipAbout] and [syncGossip]. Media is not here: [setGigMedia] writes it.
 */
class GigController(
    private val state: () -> UiState,
    private val update: ((UiState) -> UiState) -> Unit,
    private val timelines: TimelineStore,
    private val photos: PhotoRepository,
    private val where: DeviceLocation,
    private val setlistFm: SetlistFmClient,
    private val gossip: GossipStore,
    private val scope: CoroutineScope,
    private val setGigMedia: (String, List<StoredMedia>) -> Unit,
    private val syncGossip: suspend () -> Unit,
    private val gossipAbout: suspend (String) -> Unit,
) {

    /**
     * The **Gig** already on my line for this night and this artist, if there is one.
     *
     * On the same terms [matchAct] uses in the other direction: the MusicBrainz id where
     * both sides have one, and [nameKey] otherwise, because the two sources spell the
     * same artist differently often enough that an exact compare mints duplicates.
     */
    fun onLine(night: LocalDate, artist: String, mbid: String = ""): FmSetlist? =
        (state().plannedGigs + state().setlists).firstOrNull { gig ->
            gig.localDate() == night &&
                (mbid.isNotBlank() && gig.artist?.mbid == mbid ||
                    nameKey(gig.artist?.name.orEmpty()) == nameKey(artist))
        }

    /**
     * Where the **Gig** is held. Read from the raw attended list under my own key, never from
     * [UiState.setlists]: that one also carries the nights I attended here, which is what
     * this has to tell apart.
     */
    fun standing(gigId: String): GigStanding = state().let { s ->
        gigStanding(
            gigId,
            s.plannedGigs.map { it.id },
            s.showsByFriend[s.mySetlistFmUser.trim()].orEmpty().map { it.id },
        )
    }

    /**
     * How many photographs a delete would destroy — the ones this app holds the
     * only copy of. Zero means every picture on the night also lives in the
     * gallery, so removing the night costs nothing that cannot be found again.
     *
     * The screen asks this to decide whether to stop and ask.
    fun photosLostByDeleting(gigId: String): Int =
        state().mediaBySetlist[gigId].orEmpty().count { photos.ownsBytes(it.ref) }

    /**
     * A night deleted from its own screen.
     *
     * Takes the media with it, because someone reading the night's own screen can see
     * what is on it. The screen is responsible for asking first when
     * [photosLostByDeleting] says bytes would go — a pointer into the gallery is not
     * worth a dialog, the only copy of a photograph is.
     *
     * Any **Gig** that is that this phone holds a record of can go, its setlist.fm id or not. One held only
     * by my setlist.fm attended list has no delete; see [gigMenu].
     */
    fun deleteGig(gigId: String) {
        val media = state().mediaBySetlist[gigId].orEmpty()
        val me = state().mySetlistFmUser.trim()
        update {
            it.copy(
                plannedGigs = it.plannedGigs.filterNot { g -> g.id == gigId },
                setlists = it.setlists.filterNot { g -> g.id == gigId },
                // The cached copy of my attended list goes too, or the night comes back as
                // soon as the Spine is read again. Setlist.fm itself is not touched.
                showsByFriend = it.showsByFriend + (me to it.showsByFriend[me].orEmpty().filterNot { g -> g.id == gigId }),
                attendanceByGig = it.attendanceByGig - gigId,
                logsByGig = it.logsByGig - gigId,
                mediaBySetlist = it.mediaBySetlist - gigId,
                playlistsBySetlist = it.playlistsBySetlist - gigId,
                calendarEventByGig = it.calendarEventByGig - gigId,
                selectedSetlist = null,
            )
        }
        scope.launch {
            if (timelines.deleteGig(gigId, withMedia = true, anyId = true, attendedLane = me)) {
                media.forEach { photos.deleteOwnedBytes(it.id, it.ref) }
            }
        }
    }

    fun logFor(gigId: String): StoredLog = state().logsByGig[gigId] ?: StoredLog()

    /**
     * Edits my **Log** of a night. Asserted, never derived: the candidate pool is a
     * prompt and only a tap is a claim, so a song I *think* they played never becomes
     * a song they played by inaction.
     *
     * Editing songs never touches [StoredLog.closed]. Adding a song days later is
     * ordinary — the **Log** is the app's own record and stays editable forever —
     * and saying "that was the whole set" is a separate, deliberate sentence.
     *
     * Four edits rather than one "here is the new list", because a **Log** now carries
     * a **Remembered Line** beside each entry (#126) and a whole-list replacement
     * cannot say whether the third entry was deleted or renamed. The intent is what
     * keeps the two lists parallel, and [StoredLog] is the one place that does it.
     */
    fun addToLog(gigId: String, song: String) = writeLog(gigId) { it.adding(song) }

    fun removeFromLog(gigId: String, index: Int) = writeLog(gigId) { it.removingAt(index) }

    /**
     * A title replaces what was written, and what was written is kept beneath it. The
     * candidate was ranked, never chosen: only this tap decides.
     */
    fun correctLogEntry(gigId: String, index: Int, title: String) =
        writeLog(gigId) { it.correctingAt(index, title) }

    /** The way back. A wrong correction must not be a one-way door. */
    fun restoreLogEntry(gigId: String, index: Int) = writeLog(gigId) { it.restoringAt(index) }

    /**
     * The only thing that may **Close** a **Log**, and it is a person saying so. Not
     * publishing, not a refetch, not a song count: setlist.fm has nowhere to keep
     * this bit, so it never leaves the device and nothing coming back can set it.
     */
    fun setLogClosed(gigId: String, closed: Boolean) = writeLog(gigId) { it.completing(closed) }

    fun writeLog(gigId: String, edit: (StoredLog) -> StoredLog) {
        val before = logFor(gigId)
        val updated = edit(before)
        update { it.copy(logsByGig = it.logsByGig + (gigId to updated)) }
        scope.launch {
            timelines.saveLog(gigId, updated)
            withContext(Dispatchers.IO) { publishLog(gigId, before, updated) }
            syncGossip()
        }
    }

    private suspend fun publishLog(gigId: String, before: StoredLog, after: StoredLog) {
        val now = System.currentTimeMillis()
        val cache = timelines.load()
        val local = cache.gigs[gigId] ?: cache.gigForSetlist(gigId) ?: return
        val date = runCatching { LocalDate.parse(local.date, java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy")) }.getOrNull() ?: return
        val expiry = gossipExpiry(date)
        val until = io.github.magnusencoded.stationtostation.data.gossip.gossipParticipationUntil(
            cache.attendance()[gigId]?.checkedInAt, after.closed, after.completedAt, expiry, gossip.stoppedAt())
        if (until == null || now >= until.toEpochMilli()) return
        val changes = io.github.magnusencoded.stationtostation.data.gossip.gossipLogChanges(before, after)
        if (changes.isEmpty()) return
        runCatching {
            val scope = gossip.authorScope(local.id)
            val identity = GigIdentity(scope)
            gossip.updatePublic(now) { state ->
                val author = identity.publicKey()
                val previous = state.facts.values.filter { it.author == author }
                val former = previous.flatMap { it.formerIds + it.gigId }.distinct().filter { it != gigId }
                // Monotone revision time makes rapid successive Done actions deterministic.
                val revision = maxOf(now, (previous.maxOfOrNull { it.createdAt } ?: 0) + 1)
                changes.forEach { (line, text) ->
                    GossipEnvelope(gigId = gigId, formerIds = former, scope = scope,
                        author = author, createdAt = revision, expiresAt = expiry.toEpochMilli(),
                        kind = "log", line = line, text = text, attribution = identity.attribution())
                        .signed(identity::sign)?.let { state.receive(it, "", now, local = true) }
                }
            }
        }
    }

    /**
     * Write, edit or clear my **Note** in one **Band** (#50).
     *
     * At most one of mine per band, so this is an upsert keyed by band rather than by
     * id: the write-line the finger landed on already said which one it means. Two
     * notes in a band would need arranging, arranging would need the handle, and the
     * thing being served is one opinion about one night.
     *
     * **Emptying it removes it.** A note with nothing in it is not something anyone
     * wrote, and leaving an empty record behind would make the shared band claim a
     * contributor who said nothing — which would turn a night green over blank text.
     */
    fun setGigNote(setlistId: String, band: Band, text: String) {
        val had = state().mediaBySetlist[setlistId].orEmpty()
        val personal = band == Band.VAULT
        val mine = had.firstOrNull {
            it.kind == StoredMedia.Kind.NOTE && it.from == null && it.personal == personal
        }
        val written = text.trim()
        setGigMedia(
            setlistId,
            when {
                mine != null && written.isEmpty() -> had.filterNot { it.id == mine.id }
                mine != null -> had.map { if (it.id == mine.id) it.copy(text = written) else it }
                written.isEmpty() -> had
                else -> had + StoredMedia(
                    id = java.util.UUID.randomUUID().toString(),
                    kind = StoredMedia.Kind.NOTE,
                    // When it was written. It is what sorts received notes, and a note
                    // has no camera to ask for anything better.
                    capturedAt = System.currentTimeMillis(),
                    personal = personal,
                    text = written,
                )
            },
        )
    }

    /**
     * Set or unset the **Verdict** on one of my **Notes**.
     *
     * Tapping the one already set passes null, because unset has to stay reachable —
     * it is a real state, and a night I have stopped having an opinion about must not
     * be stuck wearing the one I had.
     */
    fun setGigVerdict(setlistId: String, noteId: String, verdict: String?) {
        val had = state().mediaBySetlist[setlistId].orEmpty()
        // Mine only. A received note's verdict is its sender's statement and is not
        // mine to edit, the same way their photograph is not mine to reposition.
        if (had.none { it.id == noteId && it.from == null }) return
        setGigMedia(setlistId, had.map { if (it.id == noteId) it.copy(verdict = verdict) else it })
    }

    /**
     * The night is now on setlist.fm — someone typed it in, possibly not me. The
     * local **Gig** takes their id and stops being a stub, which is the whole payoff
     * #34 names: only then can it be a **Crossing**.
     *
     * A pasted link rather than a search by artist+date. #34 sketched the search, but
     * the moment this is used is the moment you are looking at the page you just
     * created, so its url is in your hand and matching heuristics are a way to be
     * wrong about which night you meant.
     */
    fun adoptSetlistLink(gigId: String, linkOrId: String) {
        val setlistId = parseSetlistId(linkOrId)
        if (setlistId == null) {
            update { it.copy(errorKind = null, error = "That doesn't look like a setlist.fm link.") }
            return
        }
        scope.launch {
            if (!adoptSetlist(gigId, setlistId, fresh = null, notice = true)) {
                update { it.copy(errorKind = null, error = "That night already has a setlist.fm id.") }
            }
        }
    }

    /**
     * Local **Gig** [gigId] takes setlist.fm's [setlistId]: [adoptSetlistLink]'s pasted
     * link, a search hit the person picked, or one the automatic checks were sure of
     * (#531). [fresh] is the record already in hand from a search, which saves asking
     * setlist.fm for it again; null fetches it. [notice] shows "Adopted". False where
     * the night already had an id, or is gone, and nothing was changed.
     */
    suspend fun adoptSetlist(gigId: String, setlistId: String, fresh: FmSetlist?, notice: Boolean): Boolean {
        val before = timelines.load()
        val original = before.gigs[gigId]
        val now = System.currentTimeMillis()
        val until = if (original?.setlistId == null)
            gossipParticipationEnds(timelines, gossip.stoppedAt())[gigId] ?: 0L else 0L
        if (!timelines.adoptSetlistId(gigId, setlistId)) return false
        // Before anything else can read the night under its new id: a Log edit landing
        // between the store's move and this one saved an empty Log over the real one.
        update { it.adopting(gigId, setlistId) }
        gossip.rememberAdoption(gigId, setlistId)
        if (now < until && original != null) {
            val scope = gossip.authorScope(original.id)
            val identity = GigIdentity(scope)
            val update = GossipEnvelope(
                gigId = setlistId, formerIds = listOf(gigId), scope = scope,
                author = identity.publicKey(), createdAt = now, expiresAt = until,
                kind = "update", attribution = identity.attribution(),
            ).signed(identity::sign)
            if (update != null) gossip.updatePublic(now) { it.receive(update, "", now, local = true) }
        }
        // A night adopted after participation ended sends nothing, but its old witnessed
        // claim still decorates the same local record under the newly displayed ID.
        if (notice) update { it.copy(notice = "Adopted — this night is on setlist.fm now.") }
        // The real record replaces the stub: it has the url, the songs whoever
        // typed them in logged, and an id friends' lines can meet at.
        val record = fresh?.takeIf { it.id == setlistId }
            ?: runCatching { setlistFm.setlist(setlistId) }.getOrNull()
        record?.let { real ->
            timelines.savePlanned(real)
            update {
                it.copy(
                    plannedGigs = sortedPlanned(it.plannedGigs.filterNot { g -> g.id == setlistId } + real),
                    selectedSetlist = if (it.selectedSetlist?.id == setlistId) real else it.selectedSetlist,
                )
            }
        }
        return true
    }

    /** Forgets a gig I'm not going to after all. */
    fun removePlannedGig(gigId: String) {
        update { it.copy(plannedGigs = it.plannedGigs.filterNot { g -> g.id == gigId }) }
        scope.launch { timelines.removePlanned(gigId) }
    }

    /**
     * True if any gig I know about could be checked into right now on the calendar
     * alone. Cheap and pure — it is what decides whether asking for the location
     * permission is warranted at all, so the prompt only ever appears on a night
     * there is actually something to check into.
     */
    fun checkInDue(now: LocalDateTime = LocalDateTime.now()): Boolean =
        state().plannedGigs.any { gig ->
            canCheckInManually(gig, now) && !isCheckedIn(gig.id)
        }

    fun hasLocationPermission(): Boolean = where.hasPermission()

    fun isCheckedIn(gigId: String): Boolean =
        state().attendanceByGig[gigId]?.provenance == StoredAttendance.Provenance.CHECKED_IN

    /**
     * One fix, once, when the timeline is opened: if it puts me at a gig I'm going
     * to tonight, offer to check in. Every failure along the way — permission
     * refused, no fix, no coordinates for the venue, too far away — is silently no
     * offer. Nothing here is retried, scheduled or run in the background.
     *
     * ponytail: linear over the planned gigs, geocoding only the one that passes
     * the city gate. You have a ticket for a handful of nights, not thousands.
     */
    fun offerCheckIn() {
        if (askedToCheckIn) return
        askedToCheckIn = true
        scope.launch {
            val now = LocalDateTime.now()
            val fix = where.currentFix() ?: return@launch
            val candidates = state().plannedGigs.filterNot { isCheckedIn(it.id) }
            val gig = checkInCandidate(candidates, now, fix) ?: return@launch
            val venue = venueCoords(gig) ?: return@launch
            if (!atVenue(fix, venue)) return@launch
            update { it.copy(checkInOffer = gig) }
        }
    }

    /** One-shot per launch: dismissing an offer must not make it reappear. */
    private var askedToCheckIn = false

    fun dismissCheckInOffer() = update { it.copy(checkInOffer = null) }

    /**
     * The venue's coordinates, geocoded once and kept on the attendance record —
     * the same cache #29 reserved the fields for. Null for a venue the geocoder
     * can't place, which costs this gig its prompt and nothing else.
     */
    private suspend fun venueCoords(gig: FmSetlist): Pair<Double, Double>? {
        state().attendanceByGig[gig.id]?.let { stored ->
            val lat = stored.venueLat
            val lon = stored.venueLon
            if (lat != null && lon != null) return lat to lon
        }
        val query = venueMapsQuery(gig.venue?.name, gig.venue?.city?.name) ?: return null
        val found = where.geocodeVenue(
            listOfNotNull(query, gig.venue?.city?.country?.name).joinToString(", "),
        ) ?: return null
        updateAttendance(gig.id) { it.copy(venueLat = found.first, venueLon = found.second) }
        return found
    }

    /**
     * I am here. Sets the provenance the whole issue exists for, with the moment it
     * happened — evidence of a different strength than setlist.fm's retroactive
     * flag, not a competing record. Not a gate on anything: the peer-attested badge
     * (#30) decorates this entry later, it doesn't replace it.
     */
    fun checkIn(gigId: String) {
        update { it.copy(checkInOffer = null) }
        val saved = updateAttendance(gigId) {
            it.copy(
                provenance = StoredAttendance.Provenance.CHECKED_IN,
                checkedInAt = System.currentTimeMillis(),
            )
        }
        scope.launch {
            saved.join()
            withContext(Dispatchers.IO) { runCatching { gossipAbout(gigId) } }
            syncGossip()
        }
    }

    /** Writes one gig's attendance to state and disk together, never one without the other. */
    private fun updateAttendance(gigId: String, edit: (StoredAttendance) -> StoredAttendance): Job {
        val updated = edit(state().attendanceByGig[gigId] ?: StoredAttendance())
        update { it.copy(attendanceByGig = it.attendanceByGig + (gigId to updated)) }
        return scope.launch { timelines.saveAttendance(gigId, updated) }
    }
}
