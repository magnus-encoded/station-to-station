package io.github.magnusencoded.stationtostation.features.tickets

import android.app.Application
import android.net.Uri
import io.github.magnusencoded.stationtostation.*
import io.github.magnusencoded.stationtostation.data.*
import io.github.magnusencoded.stationtostation.data.setlistfm.*
import io.github.magnusencoded.stationtostation.features.planning.PlanningController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Shared **Tickets**: routed onto a night, or queued for a person to confirm. A **Ticket**
 * can be for a night already past, so it is not Planning's; minting a new **Gig** is, and
 * this calls [PlanningController] for it.
 */
class TicketsController(
    private val state: () -> UiState,
    private val update: ((UiState) -> UiState) -> Unit,
    private val timelines: TimelineStore,
    private val setlistFm: SetlistFmClient,
    private val ticketOriginals: TicketOriginals,
    private val application: Application,
    private val scope: CoroutineScope,
    private val planning: PlanningController,
    private val adoptSetlist: suspend (gigId: String, setlistId: String, fresh: FmSetlist?, notice: Boolean) -> Boolean,
    private val lookUpLocalGig: suspend (gigId: String, manual: Boolean) -> Boolean,
) {

    companion object {
        /** How long a shared ticket's import waits on setlist.fm before reading it as no match. */
        private const val TICKET_LOOKUP_TIMEOUT_MS = 5_000L
    }

    /**
     * A PDF shared into the app via the system share sheet — MainActivity's
     * `handleTicketIntent` is the sibling of `handleAuthIntent` that reaches this.
     *
     * `PdfTicketExtractor.onDevice` reads every page twice — its own text layer and
     * ML Kit's OCR of one rasterization, which zxing reads for barcodes too — and
     * `parseTicket` (through `parseTicketFields`) and `routeTicket` decide what that
     * adds up to. Only a complete,
     * unambiguous parse acts on its own — [TicketRouting.AlreadyKnown] merges into
     * the gig it matched, [TicketRouting.NewPlannedGig] takes the same
     * local-planned-gig path `addPlannedGigByHand` does. Anything else becomes
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
     * becomes the same Admission shape the PDF path stores: the text's UTF-8
     * bytes, symbology `qr` (the parameter's own name for it), page 0, uncorroborated —
     * a link has no printed text to check it against.
     *
     * A link is never a complete read: any page or app can open one, so even a
     * complete link only prefills the confirm prompt, Admission shown, and writes
     * nothing until the person saves it.
     */
    fun handleTicketLink(uri: Uri) = handleTicketLink(
        uri.getQueryParameter("artist"),
        uri.getQueryParameter("venue"),
        uri.getQueryParameter("date"),
        uri.getQueryParameter("qr"),
    )

    /** [handleTicketLink]'s query parameters, as the link carried them. */
    internal fun handleTicketLink(artist: String?, venue: String?, date: String?, qr: String?) {
        val admissions = listOfNotNull(
            qr?.trim()?.ifBlank { null }
                ?.let { Admission(payload = it.toByteArray(Charsets.UTF_8), symbology = QR_SYMBOLOGY) },
        )
        val parsed = ParsedTicket(
            admissions = admissions,
            artist = artist?.trim()?.ifBlank { null },
            venue = venue?.trim()?.ifBlank { null },
            date = date?.trim()?.ifBlank { null }?.let { findDate(it) },
        )
        scope.launch { routeParsedTicket(parsed, alwaysAsk = true) }
    }

    /**
     * [handleSharedTicketPdf] and [handleTicketLink]'s shared decision, once each has its own [ParsedTicket].
     *
     * Every Admission is redrawn in its own symbology and read back first,
     * whichever path it came in by: [routeTicket] adds nothing without asking whose
     * barcode the app could not show, and the prompt says which one. One zxing decode
     * each, off the main thread.
     *
     * Where one does not redraw, [keepOriginal] copies the shared file in and
     * every such Admission names it: the Room shows that file in its place, so the
     * ticket needs no prompt for it. Null for a path with no file (a link).
     *
     * [alwaysAsk] turns a complete read into the prompt it would otherwise skip,
     * the night it matched offered as the possible match.
     */
    private suspend fun routeParsedTicket(
        read: ParsedTicket,
        alwaysAsk: Boolean = false,
        keepOriginal: (() -> String?)? = null,
    ) {
        val parsed = withContext(Dispatchers.IO) {
            val checked = read.checkedForRedraw()
            if (keepOriginal != null && checked.needsOriginal) {
                checked.keepingOriginal(runCatching { keepOriginal() }.getOrNull())
            } else {
                checked
            }
        }
        val known = state().setlists + state().plannedGigs
        val routed = routeTicket(parsed, known)
        val routing = if (!alwaysAsk) routed else when (routed) {
            is TicketRouting.AlreadyKnown -> TicketRouting.NeedsConfirmation(parsed, possibleMatch = routed.gig)
            is TicketRouting.NewPlannedGig -> TicketRouting.NeedsConfirmation(parsed, possibleMatch = null)
            is TicketRouting.NeedsConfirmation -> routed
        }
        // setlist.fm is asked before anything is written or asked: by artist and
        // day, never venue. A night already known needs no search here; a local one is
        // looked up once its Admissions are on it.
        val artist = parsed.artist?.trim()?.ifEmpty { null }
        val date = parsed.date?.takeIf { parseFmDate(it) != null }
        val search = if (routed !is TicketRouting.AlreadyKnown && artist != null && date != null) {
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
                planning.planFmGig(landing.hit)
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
     * seconds and no more; a failure, a refusal or a timeout reads as no match.
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
        val match = hits?.let { matchSetlistFm(parsed.copy(artist = artist, date = date), it, planning.lineArtists()) }
        return TicketSearch(match, at)
    }

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
     * by hand.
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
        // What the setlist.fm list above "None of these" comes to. An edited
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
                        planning.planFmGig(hit)
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

    /** The confirm dialog's Discard — the guess is dropped, nothing is written, and no file is kept for it. */
    fun dismissPendingTicket(id: String) {
        state().pendingTickets.firstOrNull { it.id == id }?.parsed?.originals?.forEach(ticketOriginals::forget)
        update { it.answeringTicket(id) }
    }

    private suspend fun addParsedPlannedGig(
        artist: String,
        venue: String,
        night: java.time.LocalDate,
        admissions: List<Admission>,
    ): String {
        val gigId = planning.mintPlannedGig(artist, venue, night)
        attachAdmissions(gigId, admissions)
        return gigId
    }

    /**
     * Every Admission onto the night, appended. No-op when there is none to
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
}

/** [ticket] behind whatever is already waiting on the prompt, never in its place. */
fun UiState.queuingTicket(ticket: PendingTicket): UiState = copy(pendingTickets = pendingTickets + ticket)

/** The ticket [id] answered (saved or discarded) and off the queue; the next one shows. */
fun UiState.answeringTicket(id: String): UiState = copy(pendingTickets = pendingTickets.filterNot { it.id == id })
