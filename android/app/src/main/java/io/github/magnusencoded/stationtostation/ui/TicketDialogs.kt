package io.github.magnusencoded.stationtostation.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.magnusencoded.stationtostation.PendingTicket
import io.github.magnusencoded.stationtostation.caption
import io.github.magnusencoded.stationtostation.data.AdmissionDrawing
import io.github.magnusencoded.stationtostation.data.AdmissionShape
import io.github.magnusencoded.stationtostation.data.ParsedTicket
import io.github.magnusencoded.stationtostation.data.StoredAdmission
import io.github.magnusencoded.stationtostation.data.StoredSetlistFmHit
import io.github.magnusencoded.stationtostation.data.TicketOriginals
import io.github.magnusencoded.stationtostation.data.admissionDrawing
import io.github.magnusencoded.stationtostation.data.doorDrawing
import io.github.magnusencoded.stationtostation.data.musicbrainz.MbArtist
import io.github.magnusencoded.stationtostation.data.setlistfm.line
import io.github.magnusencoded.stationtostation.data.setlistfm.setlistFmQuestion
import io.github.magnusencoded.stationtostation.data.zxingFormatName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Amber = Color(0xFFE7B24C)
private val Raised = Color(0xFF17121F)
private val Ink = Color(0xFFEDE9F2)
private val Muted = Color(0xFF8B8299)
private val Faint = Color(0xFF5A5368)
private val Slate = Color(0xFF6F809D) // the future / a connected-source, a cooler light
private val Serif = FontFamily.Serif
private val LineLit = Color(0xFF4A3F63)

/**
 * Top of the timeline — the future. Only ever a notice now: the ways in are the doors
 * inside the curtain, and printing them here too made the pull decorative.
 */
@Composable
internal fun FuturePrompt(loading: Boolean) {
    if (!loading) return
    Text(
        "Looking it up on setlist.fm…",
        color = Faint,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 14.dp).spokenOnChange(),
    )
}

/**
 * One Admission drawn for a scanner (#441, story 9): [drawing] at a whole number of
 * pixels per module, as many as fit — [maxWidth] across, and for a matrix code
 * [matrixMax] down — so every bar and cell is the same width on screen. A linear code
 * fills the width it is given, [linearHeight] tall. Nearest-neighbour: the bitmap is
 * shown at its own pixel size, never scaled by the Image.
 */
@Composable
internal fun AdmissionBarcode(
    drawing: AdmissionDrawing,
    maxWidth: Dp,
    matrixMax: Dp,
    linearHeight: Dp,
    description: String,
) {
    val density = LocalDensity.current
    val bitmap = remember(drawing, maxWidth, matrixMax, linearHeight, density) {
        with(density) {
            val across = maxWidth.roundToPx() / drawing.width
            val modulePx = when (drawing.shape) {
                AdmissionShape.LINEAR -> across
                AdmissionShape.MATRIX -> minOf(across, matrixMax.roundToPx() / drawing.height)
            }.coerceAtLeast(1)
            matrixBitmap(drawing.scaled(modulePx, linearHeight.roundToPx()))
        }
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = description,
        filterQuality = FilterQuality.None,
        modifier = with(density) { Modifier.size(bitmap.width.toDp(), bitmap.height.toDp()) },
    )
}

/** What the Room has for the Admission on show: still reading it back, its drawing, or nothing it can show. */
internal sealed interface AtTheDoor {
    data object Checking : AtTheDoor
    class Shown(val drawing: AdmissionDrawing) : AtTheDoor
    /** No redraw, but the ticket file was kept for it (#568): that page is shown instead. */
    class Original(val file: java.io.File) : AtTheDoor
    data object CannotShow : AtTheDoor
}

/** The line said wherever an Admission cannot be redrawn: which one, and what to do instead. */
internal fun cannotShowLine(symbology: String, page: AdmissionPage? = null): String =
    "${page?.label?.let { "Barcode $it" } ?: "This ticket's barcode"} (${zxingFormatName(symbology)}) " +
        "can't be shown by the app. Bring the original PDF to the door."

/** The prompt's line for an Admission that will be shown from its kept original (#568). */
internal fun keptOriginalLine(symbology: String, page: AdmissionPage? = null): String =
    "${page?.label?.let { "Barcode $it" } ?: "This ticket's barcode"} (${zxingFormatName(symbology)}) " +
        "can't be redrawn, so the app keeps this ticket and shows it at the door as it was sent."

/**
 * The ticket at the door (#441): every **Admission** in its own symbology, one at a
 * time, with "1 of 3" and a way to step between them when there are several (story 5;
 * [AdmissionPage] holds the rules). Black on white, whatever the Room's ground: a
 * scanner reads contrast.
 *
 * What is drawn is [doorDrawing]'s: the Admission redrawn and read back as itself, the
 * same check the import ran, asked again here because nothing stored carries a verdict
 * and an Admission migrated from an old `ticketQr` was never checked at all. One that
 * does not read back is shown from the ticket file kept for it at import (#568,
 * [OriginalAtTheDoor]); with no file (a ticket imported before #568, a link, a
 * handover) it keeps its page and says so, in the prompt's words — never a guess, and
 * never its payload drawn as some other symbology.
 *
 * The card's look is unchanged from the QR it replaces; its redesign, brightness and a
 * full-screen view are #525's.
 */
@Composable
internal fun TicketAtTheDoor(admissions: List<StoredAdmission>, onShown: () -> Unit = {}) {
    if (admissions.isEmpty()) return
    var index by remember(admissions) { mutableStateOf(0) }
    val page = AdmissionPage.of(index, admissions.size)
    val admission = admissions[page.index]
    // Tagged with the Admission it is about: produceState keeps its last value when the
    // key changes, so an untagged one would put the previous page's drawing under the
    // new "2 of 3" until the next check ends.
    val originals = TicketOriginals.of(LocalContext.current)
    val verdict by produceState<DoorVerdict<StoredAdmission, AtTheDoor>?>(null, admission) {
        val door = withContext(Dispatchers.Default) {
            doorDrawing(admission)?.let { AtTheDoor.Shown(it) }
                ?: originals.file(admission.original)?.let { AtTheDoor.Original(it) }
                ?: AtTheDoor.CannotShow
        }
        value = DoorVerdict(admission, door)
    }
    val shown = verdict.forAdmission(admission) ?: AtTheDoor.Checking
    LaunchedEffect(admission, shown, onShown) {
        if (shown is AtTheDoor.Shown || shown is AtTheDoor.Original) onShown()
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        // The card's own padding and border, inside the width the Room gives it.
        val inner = maxWidth - 30.dp
        when (val door = shown) {
            is AtTheDoor.Shown -> Box(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White)
                    .border(1.dp, LineLit, RoundedCornerShape(14.dp))
                    .padding(14.dp),
            ) {
                AdmissionBarcode(
                    door.drawing,
                    maxWidth = inner,
                    matrixMax = 200.dp,
                    linearHeight = 110.dp,
                    description = "Your ticket's barcode${page.label?.let { ", $it" }.orEmpty()}. Hold it up to be scanned.",
                )
            }
            is AtTheDoor.Original -> OriginalAtTheDoor(door.file, admission.page, page.label, border = LineLit, caption = Muted) {
                Text(
                    cannotShowLine(admission.symbology, page),
                    color = Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            AtTheDoor.CannotShow -> Text(
                cannotShowLine(admission.symbology, page),
                color = Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            AtTheDoor.Checking -> Unit
        }
    }
    page.label?.let { label ->
        Row(
            Modifier.padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                "‹ previous",
                color = if (page.hasPrevious) Amber else Faint,
                fontSize = 13.sp,
                modifier = Modifier.spokenAs("Previous")
                    .clickable(enabled = page.hasPrevious) { index = page.previous().index }
                    .padding(vertical = 6.dp),
            )
            Text(label, color = Ink, fontSize = 13.sp)
            Text(
                "next ›",
                color = if (page.hasNext) Amber else Faint,
                fontSize = 13.sp,
                modifier = Modifier.spokenAs("Next")
                    .clickable(enabled = page.hasNext) { index = page.next().index }
                    .padding(vertical = 6.dp),
            )
        }
    }
    Spacer(Modifier.height(10.dp))
}

/**
 * The confirm prompt's Admissions, as they will be presented at the door (#441, story
 * 7): each one [Admission.redrawable] said reads back, drawn small by the same
 * [admissionDrawing] the Room uses; a plain line for each that did not (story 29); and,
 * for a ticket that read but carried no barcode at all, a line saying so (story 8).
 */
@Composable
internal fun ConfirmAdmissions(parsed: ParsedTicket) {
    val admissions = parsed.admissions
    if (admissions.isEmpty()) {
        if (!parsed.isEmpty) {
            Spacer(Modifier.height(8.dp))
            Text(
                "No barcode could be read off this ticket, so the app has nothing to show " +
                    "at the door. Bring the original PDF.",
                color = Muted,
                fontSize = 11.sp,
            )
        }
        return
    }
    Spacer(Modifier.height(8.dp))
    Text(
        if (admissions.size == 1) {
            "The ticket's barcode, as it will be shown at the door. It's kept whatever you put above."
        } else {
            "The ticket's ${admissions.size} barcodes, as they will be shown at the door. They're kept whatever you put above."
        },
        color = Faint,
        fontSize = 11.sp,
    )
    val drawn = remember(admissions) {
        admissions.mapIndexedNotNull { i, a ->
            if (a.redrawable == true) admissionDrawing(a.symbology, a.payload)?.let { i to it } else null
        }
    }
    if (drawn.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for ((i, drawing) in drawn) {
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White).padding(6.dp)) {
                    AdmissionBarcode(
                        drawing,
                        maxWidth = 220.dp,
                        matrixMax = 96.dp,
                        linearHeight = 48.dp,
                        description = "Barcode ${i + 1} of ${admissions.size}, as it will be shown at the door",
                    )
                }
            }
        }
    }
    // Not Faint: these are the lines in the dialog that change what to bring.
    admissions.forEachIndexed { i, a ->
        if (a.redrawable == true && drawn.any { it.first == i }) return@forEachIndexed
        Spacer(Modifier.height(6.dp))
        Text(
            if (a.original != null) {
                keptOriginalLine(a.symbology, AdmissionPage(i, admissions.size))
            } else {
                cannotShowLine(a.symbology, AdmissionPage(i, admissions.size))
            },
            color = Muted,
            fontSize = 11.sp,
        )
    }
}

/**
 * What a shared PDF ticket turned into, put in front of a person before anything is
 * written (#411, clarified after #408 shipped). `routeTicket` (TicketParsing.kt)
 * only ever reaches here for a parse that is missing something or whose match is
 * uncertain — a complete, unambiguous parse skips this dialog entirely.
 *
 * Every field starts pre-filled with whatever the parse found and stays editable —
 * the same fields [AddGigDialog] uses, wearing a guess
 * instead of a blank. A [PendingTicket.parsed] that found nothing at all still opens
 * this dialog with three empty fields, which is what makes "couldn't read this
 * ticket" an honest state rather than a silent failure.
 */
@Composable
internal fun TicketConfirmDialog(
    pending: PendingTicket,
    suggestions: List<MbArtist>,
    onArtistTyped: (String) -> Unit,
    onArtistPicked: () -> Unit,
    onConfirm: (artist: String, venue: String, date: String, chosenSetlistId: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var artist by remember { mutableStateOf(pending.parsed.artist.orEmpty()) }
    var venue by remember { mutableStateOf(pending.parsed.venue.orEmpty()) }
    var date by remember { mutableStateOf(pending.parsed.date.orEmpty()) }
    // The setlist.fm hit ticked, null for "None of these" (#531). Hidden, and so
    // answering nothing, once the artist or the date is edited away from the lookup.
    var chosen by remember { mutableStateOf(pending.setlistFm?.preselectedId) }
    val offered = pending.setlistFm?.takeIf { it.offeredFor(artist, date) }

    // Only Discard drops the ticket. A stray tap outside or a back press would throw
    // away a parsed ticket and its barcodes, which is worse than a prompt that stays.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Raised)
                // Up to three candidates and "None of these" under the fields can
                // outgrow a small screen with the keyboard up.
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Text("From the shared ticket", fontFamily = Serif, fontSize = 19.sp, color = Ink, modifier = Modifier.asHeading())
            Spacer(Modifier.height(6.dp))
            Text(
                if (pending.parsed.isEmpty) {
                    "Couldn't read anything off that PDF. Fill it in by hand, or discard it."
                } else if (pending.possibleMatch != null) {
                    "This looks like a night already on your line — check it before saving: " +
                        "it is added to that night only if who's playing and the date match it."
                } else {
                    "Here's what the ticket seemed to say. Check it before it's added."
                },
                color = Muted,
                fontSize = 12.sp,
            )
            pending.possibleMatch?.let { match ->
                Spacer(Modifier.height(6.dp))
                Text(
                    "Possible match: ${match.artist?.name.orEmpty()} — ${match.venueLine()} — ${match.eventDate.orEmpty()}",
                    color = Slate,
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(14.dp))
            StationField(artist, { artist = it; onArtistTyped(it) }, "who's playing")
            // Suggestions matter more here than anywhere else: the name in this field
            // came off an OCR pass, so a near miss is the expected case, not a typo.
            ArtistSuggestions(suggestions) { artist = it.name; onArtistPicked() }
            Spacer(Modifier.height(8.dp))
            StationField(venue, { venue = it }, "venue (optional)")
            Spacer(Modifier.height(8.dp))
            StationField(date, { date = it }, "date (dd-MM-yyyy)", imeDone = true)
            offered?.let { fm ->
                Spacer(Modifier.height(12.dp))
                Text(POSSIBLE_MATCH_TITLE, color = Slate, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                SetlistFmChoices(
                    rows = fm.candidates.map { c ->
                        SetlistFmChoice(
                            c.setlist.id,
                            StoredSetlistFmHit.of(c).line(),
                            setlistFmQuestion(pending.parsed.venue, fromTicket = true, candidate = c),
                        )
                    },
                    selected = chosen,
                    onSelect = { chosen = it },
                )
            }
            ConfirmAdmissions(pending.parsed)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Discard", color = Faint) }
                val ready = artist.isNotBlank() && date.isNotBlank()
                TextButton(
                    onClick = { onConfirm(artist, venue, date, if (offered != null) chosen else null) },
                    enabled = ready,
                ) { Text("Save", color = if (ready) Amber else Faint) }
            }
        }
    }
}

/**
 * MusicBrainz's artists for what is in a "who's playing" field, directly under it and
 * nowhere else. Capped at four rows: this is a prompt above a keyboard, and a list that
 * scrolls is a search result page pretending to be a hint.
 */
@Composable
internal fun ArtistSuggestions(suggestions: List<MbArtist>, onPick: (MbArtist) -> Unit) {
    suggestions.take(4).forEach { hit ->
        Text(
            buildString {
                append(hit.name)
                if (hit.disambiguation.isNotBlank()) append("  · ${hit.disambiguation}")
            },
            color = Slate,
            fontSize = 12.sp,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPick(hit) }
                .padding(vertical = 6.dp),
        )
    }
}

/** The heading of every setlist.fm "is it this one?" list, and the Gig screen's chip (#531). */
internal const val POSSIBLE_MATCH_TITLE = "Possible match on setlist.fm"

/**
 * One row of a setlist.fm list: the hit's [id] (null for "None of these"), its [line]
 * (who, where, when — always shown, so rows never read alike), and the
 * [setlistFmQuestion] under it where the room is in doubt.
 */
internal class SetlistFmChoice(val id: String?, val line: String, val question: String? = null)

/**
 * setlist.fm hits as a single choice, with "None of these" always last (#531).
 * [selected] null is "None of these"; [picked] false ticks nothing yet.
 */
@Composable
internal fun SetlistFmChoices(
    rows: List<SetlistFmChoice>,
    selected: String?,
    onSelect: (String?) -> Unit,
    picked: Boolean = true,
) {
    // A real radio group to TalkBack (#164): the RadioButton below takes no click of its
    // own, so without `selectable` on the row nothing said which one was chosen.
    Column(Modifier.selectableGroup()) {
        (rows + SetlistFmChoice(null, "None of these")).forEach { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = picked && selected == row.id, role = Role.RadioButton) {
                        onSelect(row.id)
                    }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = picked && selected == row.id,
                    onClick = null,
                    colors = RadioButtonDefaults.colors(selectedColor = Amber, unselectedColor = Faint),
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(row.line, color = Ink, fontSize = 13.sp)
                    row.question?.let { Text(it, color = Muted, fontSize = 11.sp) }
                }
            }
        }
    }
}

/**
 * The Gig screen's "Possible match on setlist.fm" chip, opened (#531): each hit a lookup
 * was not sure of, as a single choice, with [setlistFmQuestion] under a hit where the
 * room is in doubt. Confirm on a hit is "yes, this one"; on "None of these" it rejects
 * them all; "Not now" leaves the question waiting. [hits] is the stored snapshot,
 * fetched afresh where that was lost.
 */
@Composable
internal fun PossibleMatchDialog(
    gigId: String,
    yourVenue: String?,
    fromTicket: Boolean,
    pendingIds: List<String>,
    hits: suspend () -> List<StoredSetlistFmHit>,
    onPick: (String) -> Unit,
    onNone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val loaded by produceState<List<StoredSetlistFmHit>?>(null, gigId, pendingIds) { value = hits() }
    // Nothing ticked until the person picks: Confirm stays off, so no tap adopts by accident.
    var chosen by remember(gigId) { mutableStateOf<String?>(null) }
    var picked by remember(gigId) { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Raised)
                .padding(20.dp),
        ) {
            Text(POSSIBLE_MATCH_TITLE, fontFamily = Serif, fontSize = 19.sp, color = Ink, modifier = Modifier.asHeading())
            Spacer(Modifier.height(12.dp))
            val shown = loaded
            if (shown == null) {
                CircularProgressIndicator(color = Amber, modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                SetlistFmChoices(
                    rows = shown.map { hit ->
                        SetlistFmChoice(hit.id, hit.line(), setlistFmQuestion(yourVenue, fromTicket, hit))
                    },
                    selected = chosen,
                    onSelect = { chosen = it; picked = true },
                    picked = picked,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Not now", color = Faint) }
                TextButton(
                    onClick = { chosen?.let(onPick) ?: onNone() },
                    enabled = picked,
                ) { Text("Confirm", color = if (picked) Amber else Faint) }
            }
        }
    }
}
