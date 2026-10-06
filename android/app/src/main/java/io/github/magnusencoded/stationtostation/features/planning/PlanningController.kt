package io.github.magnusencoded.stationtostation.features.planning

import io.github.magnusencoded.stationtostation.*
import io.github.magnusencoded.stationtostation.data.*
import io.github.magnusencoded.stationtostation.data.musicbrainz.MusicBrainzClient
import io.github.magnusencoded.stationtostation.data.setlistfm.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

/** Planned **Gigs** and **Departures**' commit: what puts a night on the **Line** ahead of, or without, setlist.fm. */
class PlanningController(
    private val state: () -> UiState,
    private val update: ((UiState) -> UiState) -> Unit,
    private val timelines: TimelineStore,
    private val setlistFm: SetlistFmClient,
    private val musicBrainz: MusicBrainzClient,
    private val scope: CoroutineScope,
    private val fail: (Exception) -> Unit,
) {

    /** The in-flight artist lookup, so a new keystroke cancels the last one. */
    private var artistSearch: Job? = null

    /**
     * Furthest-future first, which is the same order the attended rows below already
     * use: up is always later, and a planned gig is not an exception to that.
     */
    internal fun sortedPlanned(gigs: List<FmSetlist>): List<FmSetlist> =
        gigs.sortedByDescending { it.localDate() }

    /**
     * Adds a gig I'm going to, from whatever was pasted off setlist.fm — the page
     * url or the bare id.
     *
     * Fetched by id, never searched. setlist.fm's search index stops about a day
     * out, so a show weeks away cannot be found by artist, venue or date;
     * it can only be asked for by the id sitting in the url of the page the user
     * was on when they pressed "I'll be there".
     */
    fun addPlannedGig(linkOrId: String) {
        val id = parseSetlistId(linkOrId)
        if (id == null) {
            update { it.copy(errorKind = null, error = "That doesn't look like a setlist.fm gig link.") }
            return
        }
        if (state().plannedGigs.any { it.id == id }) return
        update { it.copy(planningLoading = true) }
        scope.launch {
            try {
                planFmGig(setlistFm.setlist(id))
                update { it.copy(planningLoading = false) }
            } catch (e: Exception) {
                update { it.copy(planningLoading = false) }
                fail(e)
            }
        }
    }

    /**
     * A setlist.fm night onto the plan, as setlist.fm has it: [addPlannedGig]'s write,
     * shared with a ticket whose lookup found its night.
     */
    internal suspend fun planFmGig(hit: FmSetlist) {
        // Saved before the state update: the claim the lane filters on comes back from the save.
        val attendance = timelines.savePlanned(hit)
        update {
            it.copy(
                plannedGigs = sortedPlanned(it.plannedGigs.filterNot { g -> g.id == hit.id } + hit),
                attendanceByGig = it.attendanceByGig + (hit.id to attendance),
            )
        }
    }

    /**
     * The one add form's write. The date decides the rule underneath: a night before
     * today is one I was at ([addLocalGig]), any other is one I am going to
     * ([addPlannedGigByHand]).
     */
    fun addGig(artist: String, venue: String, date: String) {
        val night = parseFmDate(date)
        if (artist.isBlank() || night == null) {
            update { it.copy(errorKind = null, error = "A night needs who played and a date as dd-MM-yyyy.") }
            return
        }
        when (nightKind(night, LocalDate.now())) {
            NightKind.GOING_TO -> addPlannedGigByHand(artist, venue, date)
            NightKind.WAS_AT -> addLocalGig(artist, venue, date)
        }
    }

    /**
     * Joins a **Contact**'s **Gig**: it goes onto my **Line** under the same id, so
     * holding it on both **Lines** makes the **Crossing** and nothing else has to be
     * said. The date decides the claim, as it does for the add form: a night before
     * today is one I was there, attended; any other is one I am going to, planned and
     * claiming nothing.
     *
     * Joining answers no **Maybe**. A **Maybe** is joined only by my "same night", so a
     * hand-logged night of mine on this date stays a question, asked against
     * a night I hold.
     */
    fun joinGig(gig: FmSetlist) {
        val kind = nightKind(gig.localDate(), LocalDate.now())
        scope.launch {
            var attendance = timelines.savePlanned(gig)
            if (kind == NightKind.WAS_AT) {
                attendance = StoredAttendance(provenance = StoredAttendance.Provenance.ATTENDED)
                timelines.saveAttendance(gig.id, attendance)
            }
            update {
                it.copy(
                    plannedGigs = sortedPlanned(it.plannedGigs.filterNot { g -> g.id == gig.id } + gig),
                    attendanceByGig = it.attendanceByGig + (gig.id to attendance),
                )
            }
        }
    }

    /**
     * A gig I'm going to, typed in: who is playing, where, and when.
     *
     * A typed-in night is no duplicate of setlist.fm's: `adoptSetlistId` moves a local
     * **Gig** onto the vendor id when setlist.fm catches up, keeping its photos, offsets,
     * calendar link and playlist. setlist.fm's search index stops about a day out, so a
     * future gig cannot be found, only typed.
     *
     * **No attendance is written**, which is the whole difference from [addLocalGig].
     * `savePlanned` records `PLANNED` for a gig with no claim on it, and a night I have
     * not been to yet has no claim to make. Writing `ATTENDED` here would be the app
     * asserting I was somewhere I have not been.
     */
    fun addPlannedGigByHand(artist: String, venue: String, date: String) {
        val night = parseFmDate(date)
        if (artist.isBlank() || night == null) {
            update { it.copy(errorKind = null, error = "A night needs who is playing and a date as dd-MM-yyyy.") }
            return
        }
        scope.launch {
            val gigId = timelines.createLocalGig(fmDate(night), artist.trim(), venue.trim())
            val gig = localGigSetlist(gigId, artist.trim(), night, venue.trim(), city = "")
            // The claim goes into state as well as onto disk. `plannedLane` filters on
            // it, so a gig added without it was written correctly and then drawn by
            // nothing — the night appeared only after a restart, which reads as Add
            // having done nothing at all.
            val attendance = timelines.savePlanned(gig)
            update {
                it.copy(
                    plannedGigs = sortedPlanned(it.plannedGigs + gig),
                    attendanceByGig = it.attendanceByGig + (gig.id to attendance),
                    artistSuggestions = emptyList(),
                )
            }
        }
    }

    /** The artists already on my **Line**, for the matcher's artist check: one per MusicBrainz id. */
    internal fun lineArtists(): List<FmArtist> =
        (state().setlists + state().plannedGigs)
            .mapNotNull { it.artist }
            .filter { it.mbid.isNotBlank() }
            .distinctBy { it.mbid }

    /** A typed-in planned **Gig** minted onto the plan, claiming nothing, for a **Ticket**: its id. */
    internal suspend fun mintPlannedGig(artist: String, venue: String, night: LocalDate): String {
        val gigId = timelines.createLocalGig(fmDate(night), artist, venue)
        val gig = localGigSetlist(gigId, artist, night, venue, city = "")
        val attendance = timelines.savePlanned(gig)
        update {
            it.copy(
                plannedGigs = sortedPlanned(it.plannedGigs + gig),
                attendanceByGig = it.attendanceByGig + (gig.id to attendance),
            )
        }
        return gigId
    }

    /**
     * Spellings for the artist name being typed, from MusicBrainz.
     *
     * Debounced rather than rate-limited: MusicBrainz asks for no more than a request a
     * second, and a search-as-you-type field would otherwise send one per keystroke.
     * Cancelling the previous job is also what keeps the answers in order — without it a
     * slow reply for "ka" can land after a fast one for "kaizers" and replace it.
     *
     * Failures are swallowed to an empty list on purpose. This is a prompt; a person who
     * is offline can still type the name, and an error snackbar for a suggestion that
     * did not arrive would be nagging about a service they did not ask to use.
     */
    fun suggestArtists(query: String) {
        artistSearch?.cancel()
        if (query.isBlank()) {
            update { it.copy(artistSuggestions = emptyList()) }
            return
        }
        artistSearch = scope.launch {
            delay(350)
            val hits = runCatching { musicBrainz.searchArtists(query) }.getOrDefault(emptyList())
            update { it.copy(artistSuggestions = hits) }
        }
    }

    /** The typed name was replaced by a picked one, so the list has done its job. */
    fun clearArtistSuggestions() {
        artistSearch?.cancel()
        update { it.copy(artistSuggestions = emptyList()) }
    }

    /**
     * A night entered by hand — the zero-account floor's one door.
     *
     * **Attendance is ATTENDED, never CHECKED_IN.** Typing a night in is a claim
     * about the past made now; a check-in is a claim the phone corroborated at the
     * venue on the night. Recording the two as the same thing would make the
     * provenance the Room shows a lie, which is the one thing this path must not do.
     *
     * A blank venue stays blank rather than becoming "": an unknown room is not a
     * place two gigs have in common, and `localGigSetlist` is careful about that.
     */
    fun addLocalGig(artist: String, venue: String, date: String) {
        val night = parseFmDate(date)
        if (artist.isBlank() || night == null) {
            update { it.copy(errorKind = null, error = "A night needs who played and a date as dd-MM-yyyy.") }
            return
        }
        scope.launch {
            val gigId = timelines.createLocalGig(fmDate(night), artist.trim(), venue.trim())
            val gig = localGigSetlist(gigId, artist.trim(), night, venue.trim(), city = "")
            val attendance = StoredAttendance(provenance = StoredAttendance.Provenance.ATTENDED)
            timelines.savePlanned(gig)
            timelines.saveAttendance(gigId, attendance)
            update {
                it.copy(
                    plannedGigs = sortedPlanned(it.plannedGigs + gig),
                    attendanceByGig = it.attendanceByGig + (gigId to attendance),
                )
            }
        }
    }

    /**
     * A calendar event was just created for a gig; remember its URI. Presence of the
     * URI is what flips the swipe from "add to calendar" to "invite a friend" and
     * makes the tappable link appear, so this is what graduates the leaf. Persisted
     * so both survive a cold start.
     */
    fun markCalendarAdded(gigId: String, eventUri: String) {
        update { it.copy(calendarEventByGig = it.calendarEventByGig + (gigId to eventUri)) }
        scope.launch { timelines.markCalendarAdded(gigId, eventUri) }
    }

    /**
     * **Departures committed: a diff applied to the Line, not an import.**
     *
     * Adds mint a **Gig** claimed `planned` — a programme is a plan, and it must never
     * be counted as a show I have seen. Each carries the act's stage as its room and the
     * **Festival**'s id, so `groupIntoFestivals` groups it by declared identity and never
     * has to infer a festival from nights and venues: the **Festival** exists because
     * somebody picked it.
     *
     * **An act already on the Line is adopted, never duplicated.** That is the bug that
     * started this work — two stores with different date formats that did not check each
     * other — and it is fixed here by matching on the night and the artist across the
     * whole line before minting anything.
     *
     * Removes delete only a **Gig** this app minted. A night setlist.fm knows about is
     * evidence of something that happened; deselecting a plan must not be able to erase
     * it, and `deleteGig` refuses one carrying media in any case.
     */
    fun commitProgramme(
        programme: StoredProgramme,
        diff: ProgrammeDiff,
        picked: Set<String>,
        now: LocalDateTime = LocalDateTime.now(),
    ) {
        if (diff.isEmpty) return
        scope.launch {
            val played = playedActs(programme.acts, now)
            val festivalId = programmeFestivalId(programme)
            val days = programmeDays(programme.acts)
            val festival = StoredFestival(
                id = festivalId,
                name = programme.name.trim().ifBlank { programme.id },
                rangeFrom = days.firstOrNull()?.let { fmDate(it) },
                rangeTo = days.lastOrNull()?.let { fmDate(it) },
                // Authored: I picked this festival. An upstream scrape must not overwrite
                // a name I chose off its own programme — see `mergedWith`.
                source = StoredFestival.FestivalSource.AUTHORED,
            )

            val minted = mutableListOf<FmSetlist>()
            val attendances = mutableMapOf<String, StoredAttendance>()
            val membership = mutableMapOf<String, String>()
            // Everything picked, not only what is new: an act already on the line from
            // setlist.fm is exactly the one that has to be *gathered* into the festival
            // rather than minted a second time, and it never appears in the diff because
            // it was on the line before the programme was opened.
            val taking = programme.acts.filter { actKey(it) in picked }.distinctBy { actKey(it) }
            for (act in taking) {
                val night = runCatching { LocalDate.parse(act.date) }.getOrNull() ?: continue
                val artist = act.artist.trim()
                if (artist.isBlank()) continue
                val existing = onLine(night, artist, act.mbid)
                val gigId = existing?.id
                    ?: timelines.createLocalGig(fmDate(night), artist, act.stage)
                if (existing == null) {
                    val gig = localGigSetlist(gigId, artist, night, venue = act.stage, city = "")
                    val claim = timelines.savePlanned(gig)
                    // A set that has already finished is a night I was at, not a night I
                    // am going to — see `playedActs`. Only ever upgrades: `savePlanned`
                    // hands back a claim that already exists, and a check-in outranks
                    // this one.
                    attendances[gigId] =
                        if (actKey(act) in played &&
                            claim.provenance == StoredAttendance.Provenance.PLANNED
                        ) {
                            claim.withProvenance(StoredAttendance.Provenance.ATTENDED)
                                .also { timelines.saveAttendance(gigId, it) }
                        } else {
                            claim
                        }
                    minted += gig
                }
                membership[gigId] = festivalId
            }

            val dropped = mutableSetOf<String>()
            for (key in diff.remove) {
                val night = runCatching { LocalDate.parse(key.substringBefore('|')) }.getOrNull() ?: continue
                val gig = onLine(night, key.substringAfter('|'))?.takeIf { it.isLocal() } ?: continue
                if (timelines.deleteGig(gig.id)) dropped += gig.id
            }

            timelines.save(festivals = mapOf(festivalId to festival), festivalIdByShow = membership)
            update {
                it.copy(
                    plannedGigs = sortedPlanned(it.plannedGigs.filterNot { g -> g.id in dropped } + minted),
                    setlists = it.setlists.filterNot { g -> g.id in dropped },
                    attendanceByGig = (it.attendanceByGig + attendances) - dropped,
                    festivals = it.festivals + Festivals(
                        byId = mapOf(festivalId to festival),
                        idByShow = membership,
                    ),
                )
            }
        }
    }

    /**
     * The **Gig** already on my line for this night and this artist, if there is one.
     *
     * On the same terms [matchAct] uses in the other direction: the MusicBrainz id where
     * both sides have one, and [nameKey] otherwise, because the two sources spell the
     * same artist differently often enough that an exact compare mints duplicates.
     */
    private fun onLine(night: LocalDate, artist: String, mbid: String = ""): FmSetlist? =
        (state().plannedGigs + state().setlists).firstOrNull { gig ->
            gig.localDate() == night &&
                (mbid.isNotBlank() && gig.artist?.mbid == mbid ||
                    nameKey(gig.artist?.name.orEmpty()) == nameKey(artist))
        }
}
