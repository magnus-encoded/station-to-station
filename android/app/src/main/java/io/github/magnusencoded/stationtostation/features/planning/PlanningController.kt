package io.github.magnusencoded.stationtostation.features.planning

import android.app.Application
import android.net.Uri
import io.github.magnusencoded.stationtostation.*
import io.github.magnusencoded.stationtostation.data.*
import io.github.magnusencoded.stationtostation.data.musicbrainz.MusicBrainzClient
import io.github.magnusencoded.stationtostation.data.setlistfm.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Planned **Gigs**, shared tickets and **Departures**' commit: everything that puts a night
 * onto the **Line** ahead of, or without, setlist.fm.
 *
 * What is not extracted yet arrives as a function: [fail], [adoptSetlist], [lookUpLocalGig].
 */
class PlanningController(
    private val state: () -> UiState,
    private val update: ((UiState) -> UiState) -> Unit,
    private val timelines: TimelineStore,
    private val setlistFm: SetlistFmClient,
    private val musicBrainz: MusicBrainzClient,
    private val ticketOriginals: TicketOriginals,
    private val application: Application,
    private val scope: CoroutineScope,
    private val fail: (Exception) -> Unit,
    private val adoptSetlist: suspend (gigId: String, setlistId: String, fresh: FmSetlist?, notice: Boolean) -> Boolean,
    private val lookUpLocalGig: suspend (gigId: String, manual: Boolean) -> Boolean,
) {

    companion object {
        /** How long a shared ticket's import waits on setlist.fm before reading it as no match (#531). */
        private const val TICKET_LOOKUP_TIMEOUT_MS = 5_000L
    }

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
     * out (see #29), so a show weeks away cannot be found by artist, venue or date;
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
     * shared with a ticket whose lookup found its night (#531).
     */
    private suspend fun planFmGig(hit: FmSetlist) {
        // Saved before the state update, not after, because the claim the lane
        // filters on comes back from the save. The old order left this path
        // with the same hole as the typed-in one.
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
     * hand-logged night of mine on this date stays a question, and is now asked against
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
     * **The objection that kept this a paste box is obsolete.** `AddPlannedGigDialog`
     * defended taking only a setlist.fm link on two grounds. The first still holds —
     * setlist.fm's search index stops about a day out (#29), so a future gig cannot be
     * *found*. The second, that typing the details in "would invent a second record for
     * a gig setlist.fm already has", is no longer true: `createLocalGig` mints local
     * **Gig**s for nights setlist.fm has never heard of, and `adoptSetlistId` moves one
     * onto the vendor id when setlist.fm catches up, with every photo, offset, calendar
     * link and playlist intact.
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

    /**
     * A PDF shared into the app via the system share sheet (#411) — MainActivity's
     * `handleTicketIntent` is the sibling of `handleAuthIntent` that reaches this.
     *
     * `PdfTicketExtractor.onDevice` reads every page twice — its own text layer and
     * ML Kit's OCR of one rasterization, which zxing reads for barcodes too — and
     * `parseTicket` (through `parseTicketFields`) and `routeTicket` decide what that
     * adds up to (#526). Per #411's clarified spec, only a complete,
     * unambiguous parse acts on its own — [TicketRouting.AlreadyKnown] merges into
     * the gig it matched, [TicketRouting.NewPlannedGig] takes the same
     * local-planned-gig path [addPlannedGigByHand] does. Anything else becomes
     * [PendingTicket] for a person to confirm; see [confirmPendingTicket].
     */
    fun handleSharedTicketPdf(uri: Uri) {
        scope.launch {
            val parsed = parseTicket(uri, PdfTicketExtractor.onDevice(application))
            routeParsedTicket(parsed) {
                application.contentResolver.openInputStream(uri)
                    ?.use { ticketOriginals.keep(it, "pdf") }
            }
        }
    }

    /**
     * A ticketing provider's own confirmation page, linked straight into the app
     * rather than shared as a PDF for this app to OCR — `station-to-station://ticket
     * ?artist=…&venue=…&date=…&qr=…`, meant to be embeddable in a page the provider
     * already controls the same way any other "open in app" link is. `artist`,
     * `venue` and `date` are the plain fields; `date` is read through the same
     * [findDate] every PDF ticket's text is, so a provider can send whatever
     * reasonably-dated shape they already format dates in rather than being made to
     * learn this app's own dd-MM-yyyy. `qr` is the barcode's own decoded payload
     * (plain text, not base64) — optional, since a page may not have it at hand. It
     * becomes the same Admission shape the PDF path stores (#441): the text's UTF-8
     * bytes, symbology `qr` (the parameter's own name for it), page 0, uncorroborated —
     * a link has no printed text to check it against.
     *
     * Reuses [routeTicket] exactly as the PDF path does: a complete, unambiguous
     * parse acts on its own, anything less is shown to the person to confirm. A link
     * a provider gets wrong (a typo'd date, a missing artist) fails exactly the same
     * safe way an unreadable PDF does — never a silent add.
     */
    fun handleTicketLink(uri: Uri) {
        val artist = uri.getQueryParameter("artist")?.trim()?.ifBlank { null }
        val venue = uri.getQueryParameter("venue")?.trim()?.ifBlank { null }
        val date = uri.getQueryParameter("date")?.trim()?.ifBlank { null }?.let { findDate(it) }
        val admissions = listOfNotNull(
            uri.getQueryParameter("qr")?.trim()?.ifBlank { null }
                ?.let { Admission(payload = it.toByteArray(Charsets.UTF_8), symbology = QR_SYMBOLOGY) },
        )
        val parsed = ParsedTicket(admissions = admissions, artist = artist, venue = venue, date = date)
        scope.launch { routeParsedTicket(parsed) }
    }

    /**
     * [handleSharedTicketPdf] and [handleTicketLink]'s shared decision, once each has its own [ParsedTicket].
     *
     * Every Admission is redrawn in its own symbology and read back first (#441, story
     * 29), whichever path it came in by: [routeTicket] adds nothing without asking whose
     * barcode the app could not show, and the prompt says which one. One zxing decode
     * each, off the main thread.
     *
     * Where one does not redraw, [keepOriginal] copies the shared file in (#568) and
     * every such Admission names it: the Room shows that file in its place, so the
     * ticket needs no prompt for it. Null for a path with no file (a link).
     */
    private suspend fun routeParsedTicket(read: ParsedTicket, keepOriginal: (() -> String?)? = null) {
        val parsed = withContext(Dispatchers.IO) {
            val checked = read.checkedForRedraw()
            if (keepOriginal != null && checked.needsOriginal) {
                checked.keepingOriginal(runCatching { keepOriginal() }.getOrNull())
            } else {
                checked
            }
        }
        val known = state().setlists + state().plannedGigs
        val routing = routeTicket(parsed, known)
        // setlist.fm is asked before anything is written or asked (#531): by artist and
        // day, never venue. A night already known needs no search here; a local one is
        // looked up once its Admissions are on it.
        val artist = parsed.artist?.trim()?.ifEmpty { null }
        val date = parsed.date?.takeIf { parseFmDate(it) != null }
        val search = if (routing !is TicketRouting.AlreadyKnown && artist != null && date != null) {
            ticketSearch(parsed, artist, date)
        } else {
            null
        }
        val knownIds = known.filterNot { it.isLocal() }.map { it.id }.toSet()
        when (val landing = ticketImport(routing, search?.match, knownIds)) {
            is TicketImport.Attach -> attachAdmissions(landing.gigId, parsed.admissions)
            is TicketImport.AttachThenLookUp -> {
                attachAdmissions(landing.gigId, parsed.admissions)
                lookUpLocalGig(landing.gigId, false)
            }
            is TicketImport.MintFromSetlistFm -> {
                planFmGig(landing.hit)
                attachAdmissions(landing.hit.id, parsed.admissions)
            }
            TicketImport.MintLocal -> {
                val new = routing as? TicketRouting.NewPlannedGig ?: return
                val night = parseFmDate(new.date) ?: return
                val gigId = addParsedPlannedGig(new.artist, new.venue, night, new.admissions)
                if (search != null) stampLookup(gigId, TicketSetlistFmAnswer(null, emptyList(), search.at))
            }
            is TicketImport.Prompt -> {
                val (ticket, possibleMatch) = when (routing) {
                    is TicketRouting.NeedsConfirmation -> routing.parsed to routing.possibleMatch
                    else -> parsed to null
                }
                val offered = search?.let {
                    TicketSetlistFm(landing.candidates, landing.preselectedId, artist.orEmpty(), date.orEmpty(), it.at)
                }
                update { it.queuingTicket(PendingTicket(ticket, possibleMatch, setlistFm = offered)) }
            }
        }
    }

    /** One import lookup: what it came to (null where it failed) and when it went out. */
    private class TicketSearch(val match: SetlistFmMatch?, val at: Long)

    /**
     * setlist.fm's `search/setlists` for a shared ticket's [artist] and [date], held to
     * [parsed] by the matcher. The person is waiting on the import, so it gets a few
     * seconds and no more; a failure, a refusal or a timeout reads as no match (#531).
     */
    private suspend fun ticketSearch(parsed: ParsedTicket, artist: String, date: String): TicketSearch {
        val at = System.currentTimeMillis()
        // Raced rather than wrapped: the client's blocking call does not hear a
        // timeout, so the import stops waiting on it instead.
        val request = scope.async { setlistFm.searchSetlists(artist, date).setlist }
        val hits = try {
            withTimeoutOrNull(TICKET_LOOKUP_TIMEOUT_MS) { request.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("StationToStation", "ticket lookup failed: ${e.message}")
            null
        } finally {
            if (!request.isCompleted) request.cancel()
        }
        val match = hits?.let { matchSetlistFm(parsed.copy(artist = artist, date = date), it, lineArtists()) }
        return TicketSearch(match, at)
    }

    /** The artists already on my **Line**, for the matcher's artist check: one per MusicBrainz id. */
    internal fun lineArtists(): List<FmArtist> =
        (state().setlists + state().plannedGigs)
            .mapNotNull { it.artist }
            .filter { it.mbid.isNotBlank() }
            .distinctBy { it.mbid }

    /** [answer] onto local Gig [gigId]'s stored lookup; nothing for a setlist.fm night or an empty answer. */
    private suspend fun stampLookup(gigId: String, answer: TicketSetlistFmAnswer) {
        if (!answer.recordsAnything) return
        val gig = timelines.load().gigs[gigId] ?: return
        if (gig.setlistId != null) return
        val settled = timelines.editSetlistFmLookup(gigId) { answer.applyTo(it) } ?: return
        update { it.copy(attendanceByGig = it.attendanceByGig + (gigId to settled)) }
    }

    /**
     * The confirm dialog's Save — [handleSharedTicketPdf]'s pending guess, corrected
     * or filled in by hand, then routed the same way a complete auto-parse would be:
     * matched if it turns out to be a night already known, otherwise a new planned
     * gig. The Admissions travel from the original parse regardless of what the person
     * edited — they are preserved even when the text half of the ticket needed fixing
     * by hand (#441, story 16).
     *
     * The match is checked *again* on the confirmed values rather than trusted from
     * routing, as iOS's `confirmTicket` does. [PendingTicket.possibleMatch] was found
     * for what the parse read; a person who corrected the artist or the date has said
     * it is some other night, and a partial parse that matched nothing may, once
     * filled in, name a night that was already there.
     */
    fun confirmPendingTicket(
        id: String,
        artist: String,
        venue: String,
        date: String,
        chosenSetlistId: String? = null,
    ) {
        val pending = state().pendingTickets.firstOrNull { it.id == id } ?: return
        val night = parseFmDate(date)
        if (artist.isBlank() || night == null) {
            update { it.copy(errorKind = null, error = "A night needs who is playing and a date as dd-MM-yyyy.") }
            return
        }
        // What the setlist.fm list above "None of these" comes to (#531). An edited
        // artist or date hid the list, so it neither chooses nor rejects anything.
        val answer = pending.setlistFm?.answer(artist, date, chosenSetlistId) ?: TicketSetlistFmAnswer.UNASKED
        scope.launch {
            val known = state().setlists + state().plannedGigs
            val confirmed = pending.confirmedAs(artist, venue, night, known)
            val hit = answer.chosen
            if (hit != null) {
                val onLine = known.firstOrNull { it.id == hit.id }
                val localNight = (confirmed as? ConfirmedTicket.Attach)?.gigId
                    ?.let { gigId -> known.firstOrNull { it.id == gigId && it.isLocal() } }
                when {
                    onLine != null -> attachAdmissions(hit.id, pending.parsed.admissions)
                    // The night is already here as a local Gig: it takes the hit's id.
                    localNight != null -> {
                        attachAdmissions(localNight.id, pending.parsed.admissions)
                        adoptSetlist(localNight.id, hit.id, hit, true)
                    }
                    else -> {
                        planFmGig(hit)
                        attachAdmissions(hit.id, pending.parsed.admissions)
                    }
                }
            } else {
                val gigId = when (confirmed) {
                    is ConfirmedTicket.Attach -> confirmed.gigId.also { attachAdmissions(it, confirmed.admissions) }
                    is ConfirmedTicket.Mint ->
                        addParsedPlannedGig(confirmed.artist, confirmed.venue, confirmed.night, confirmed.admissions)
                }
                // "None of these": every hit offered is not this night, and the lookup counts.
                stampLookup(gigId, answer)
            }
            update { it.answeringTicket(id) }
        }
    }

    /** The confirm dialog's Discard — the guess is dropped, nothing is written, and no file is kept for it (#568). */
    fun dismissPendingTicket(id: String) {
        state().pendingTickets.firstOrNull { it.id == id }?.parsed?.originals?.forEach(ticketOriginals::forget)
        update { it.answeringTicket(id) }
    }

    /** [addPlannedGigByHand]'s write, shared by both ticket paths above. */
    private suspend fun addParsedPlannedGig(
        artist: String,
        venue: String,
        night: LocalDate,
        admissions: List<Admission>,
    ): String {
        val gigId = timelines.createLocalGig(fmDate(night), artist, venue)
        val gig = localGigSetlist(gigId, artist, night, venue, city = "")
        val attendance = timelines.savePlanned(gig)
        update {
            it.copy(
                plannedGigs = sortedPlanned(it.plannedGigs + gig),
                attendanceByGig = it.attendanceByGig + (gig.id to attendance),
            )
        }
        attachAdmissions(gig.id, admissions)
        return gigId
    }

    /**
     * Every Admission onto the night, appended (#441). No-op when there is none to
     * keep — most confirmations and most matches.
     */
    private suspend fun attachAdmissions(gigId: String, admissions: List<Admission>) {
        if (admissions.isEmpty()) return
        val attendance = timelines.attachAdmissions(gigId, admissions.map(StoredAdmission::of))
        // The same ticket shared twice keeps its first file; the second copy is named by nothing.
        val named = attendance.admissions.mapNotNull { it.original }.toSet()
        admissions.mapNotNull { it.original }.filterNot { it in named }.toSet().forEach(ticketOriginals::forget)
        update { it.copy(attendanceByGig = it.attendanceByGig + (gigId to attendance)) }
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
     * A night entered by hand — the zero-account floor's one door (#225).
     *
     * Nothing here is new machinery. `createLocalGig` has minted **Gig**s with no
     * setlist.fm id since the **Bill** shipped, and `localGigSetlist` has been
     * dressing them as an `FmSetlist` for every screen to draw. The floor was
     * always real; what was missing was a way onto it, because both affordances on
     * the empty spine led to setlist.fm.
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
