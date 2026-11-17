package io.github.magnusencoded.stationtostation.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.magnusencoded.stationtostation.caption
import io.github.magnusencoded.stationtostation.data.Band
import io.github.magnusencoded.stationtostation.data.StoredMedia
import io.github.magnusencoded.stationtostation.data.bandsOf
import io.github.magnusencoded.stationtostation.data.hintForMoving
import io.github.magnusencoded.stationtostation.data.preamble
import io.github.magnusencoded.stationtostation.data.setlistfm.line

private val Amber = Color(0xFFE7B24C)
private val Ink = Color(0xFFEDE9F2)
private val Faint = Color(0xFF5A5368)
private val Slate = Color(0xFF6F809D) // the future / a connected-source, a cooler light

/**
 * The night's prose, both bands of it, drawn below the setlist (#50).
 *
 * **Below the set, not beside the photographs.** Analysis happens after the show —
 * the Journalist writes when the lights are up and the Reliver reads after they have
 * been through the night again — so the write-line sits at the end of the room rather
 * than in the middle of it, where it would interrupt the scroll through the material
 * with a demand for a sentence.
 *
 * **Shared above vault, always**, which is the same order the **Bands** are drawn in
 * and the same claim: up is what my **Audience** reads, down is what reaches nobody.
 * The prose leaves the band frames but not the model — a long-press still lifts a note
 * between them, and [MediaBands] still never learns that any of this is text.
 */
@Composable
internal fun GigNotes(
    media: List<StoredMedia>,
    /** The night's own facts, already composed. Empty when the record knows nothing. */
    preamble: String,
    senderName: (String) -> String?,
    /**
     * Still needed on its own: the vault row is *absent* under the light rather than
     * merely read-only, because a **Contact** cannot see the vault and an empty row
     * drawn there would claim nothing is held back over a vault that holds something.
     */
    contactLight: Boolean,
    /** Whether this night is mine to write on, and not under the light (#327). */
    editable: Boolean,
    onWrite: (Band, String) -> Unit,
    onVerdict: (String, String?) -> Unit,
    onMove: (String, Band, Int) -> Unit,
) {
    val noteBands = bandsOf(media.filter { it.kind == StoredMedia.Kind.NOTE })
    Column {
        BandNotes(
            band = Band.SHARED,
            mine = noteBands.shared.firstOrNull(),
            received = noteBands.received,
            // The prose's own crossing, not the night's: this outline is a statement
            // about what is written here (#268).
            crossed = noteBands.crossed,
            // Once per night, over whichever note is uppermost. The same sentence
            // twice is noise, and it is a fact about the night rather than about
            // either band.
            preamble = if (noteBands.shared.isNotEmpty()) preamble else "",
            senderName = senderName,
            editable = editable,
            onWrite = { onWrite(Band.SHARED, it) },
            onVerdict = { v -> noteBands.shared.firstOrNull()?.let { onVerdict(it.id, v) } },
            onLift = { id -> onMove(id, Band.VAULT, 0) },
        )
        // Absent under the contact light for the reason the vault strip is: a Contact
        // cannot see the vault, and an empty row drawn there would claim nothing is
        // held back over a vault that holds something.
        if (!contactLight) {
            BandNotes(
                band = Band.VAULT,
                mine = noteBands.vault.firstOrNull(),
                // Nothing arrives here. A **Contact**'s note is something they put in
                // the commons; there is no path by which one lands in my vault.
                received = emptyList(),
                crossed = false,
                preamble = if (noteBands.shared.isEmpty()) preamble else "",
                senderName = senderName,
                // Was `true`: the vault is only ever mine, which is true of the *band*
                // and says nothing about whose *night* this is (#327).
                editable = editable,
                onWrite = { onWrite(Band.VAULT, it) },
                onVerdict = { v -> noteBands.vault.firstOrNull()?.let { onVerdict(it.id, v) } },
                // Publishing a draft. The upward move earns the green promise for
                // free, because [hintForMoving] never asked what kind of item it was
                // holding.
                onLift = { id -> onMove(id, Band.SHARED, 0) },
            )
        }
    }
}

/**
 * One band's prose: my **Note**, then any **Received** ones (#50).
 *
 * **Position is the bit here too.** There is no switch and no badge — a note in the
 * lower band reaches nobody, a note in the upper one reaches my **Audience**, and
 * moving it is the act that changes its mind. Which write-line you tapped is which
 * question you answered, so nothing has to ask a second time.
 *
 * The empty line renders on a night nothing was written about, for the same reason
 * the empty vault strip does: a surface nobody can see is a surface nobody finds.
 *
 * **The write-line stays on top, and everything written sits under it** — mine, then
 * anyone else's. It reads backwards for a second and then stops: you come here to
 * write, and reading what a **Contact** said *after* saying your own piece is the
 * order that keeps the sentence yours (#268).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BandNotes(
    band: Band,
    mine: StoredMedia?,
    received: List<StoredMedia>,
    /** More than one of us in this band's prose — see [bandAccent]. */
    crossed: Boolean,
    /** The night's own facts. Rendered, never stored — see [preamble]. */
    preamble: String,
    senderName: (String) -> String?,
    editable: Boolean,
    onWrite: (String) -> Unit,
    onVerdict: (String?) -> Unit,
    onLift: (String) -> Unit,
) {
    var editing by remember(mine?.id, band) { mutableStateOf(false) }
    var draft by remember(mine?.id, band) { mutableStateOf(mine?.text.orEmpty()) }
    var expanded by remember(mine?.id) { mutableStateOf(false) }
    // The tap that opens the field is the tap that means "I am writing now" — asking
    // for a second one to raise the keyboard is the whole cost of capture doubled, on
    // the surface ADR-0012 says has to be one-handed and cheap.
    val focus = remember { FocusRequester() }
    LaunchedEffect(editing) { if (editing) focus.requestFocus() }

    // A **Contact** looking at a night nobody wrote about gets no frame around the
    // nothing. The write-line is what the empty frame is *for*, and there isn't one.
    if (!editable && mine == null && received.isEmpty()) return

    val accent = bandAccent(band, crossed)
    // One frame for the whole band, thickening while it is being written in — the
    // same language the strips use, and for the same reason: a second outline around
    // the field inside this one is two boundaries drawn for one boundary. It was
    // Amber besides, which on a shared note is the colour of the other answer (#268).
    Column(
        Modifier
            .padding(start = 20.dp, end = 20.dp, top = 6.dp)
            .border(if (editing) 2.dp else 1.dp, accent, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        // Always first, whether it opens the field or reopens it over what is
        // already there. Everything written lands underneath.
        if (editable && !editing) {
            Text(
                when {
                    mine != null -> "Edit"
                    band == Band.SHARED -> "Write something to share"
                    else -> "Write something just for you"
                },
                color = if (mine != null) accent else Faint,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { draft = mine?.text.orEmpty(); editing = true }
                    .padding(vertical = 6.dp),
            )
        }
        when {
            editing -> {
                // One field, no toolbar. The phone is the wrong surface for long form
                // (ADR-0012) and the answer is to keep the room visible around it,
                // not to grow an editor.
                //
                // Tall enough to invite several sentences, though: a one-line box asks
                // for a caption, and the thing being asked for is what the night was
                // like. The floor is the invitation; the field grows past it as it
                // fills, and nothing truncates what gets typed.
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    textStyle = LocalTextStyle.current.copy(color = Ink, fontSize = 13.sp),
                    cursorBrush = SolidColor(Amber),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 108.dp)
                        .clip(RoundedCornerShape(6.dp))
                        // No border of its own: the band's frame is the boundary, and
                        // the darker ground is enough to say "type here".
                        .background(UnlitField)
                        .padding(10.dp)
                        .focusRequester(focus),
                )
                Row(Modifier.padding(top = 6.dp)) {
                    Text(
                        "done",
                        color = Amber,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clickable { onWrite(draft); editing = false }
                            .padding(vertical = 4.dp, horizontal = 2.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        "discard",
                        color = Faint,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clickable { draft = mine?.text.orEmpty(); editing = false }
                            .padding(vertical = 4.dp, horizontal = 2.dp),
                    )
                }
            }

            mine != null -> {
                if (preamble.isNotEmpty()) {
                    // Not editable, and drawn apart from the typed text: nothing
                    // generated may be mistaken for something I said.
                    Text(preamble, color = Faint, fontSize = 11.sp)
                    Spacer(Modifier.height(3.dp))
                }
                Text(
                    mine.text,
                    color = Ink,
                    fontSize = 13.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        // A long-press lifts the note into the other band. The same
                        // act as dragging a photograph across, minus the index —
                        // one note per band means there is no position to choose.
                        .combinedClickable(
                            onClickLabel = if (expanded) "Show less" else "Show all",
                            onClick = { expanded = !expanded },
                            // Named for the reader's actions menu, where a bare "long
                            // press" says nothing about which way the note goes (#164).
                            onLongClickLabel = if (!editable) null
                            else if (band == Band.SHARED) "Move to the vault" else "Share it",
                            onLongClick = { if (editable) onLift(mine.id) },
                        ),
                )
                // Editing is the line above now, so this row is the verdict alone.
                if (editable) {
                    Box(Modifier.padding(top = 5.dp)) {
                        VerdictThumbs(mine.verdict, onVerdict)
                    }
                }
            }
        }

        received.forEach { note ->
            Spacer(Modifier.height(8.dp))
            Text(
                // A name where the key resolves to one, and never an invented name:
                // the same degradation the green promise makes.
                senderName(note.from.orEmpty()) ?: "Someone else",
                color = Slate,
                fontSize = 11.sp,
            )
            Text(note.text, color = Slate, fontSize = 13.sp)
            if (note.verdict != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    verdictGlyph(note.verdict),
                    color = Slate,
                    fontSize = 13.sp,
                    // Not "thumbs up sign" (#164).
                    modifier = Modifier.spokenAs(verdictWords(note.verdict)),
                )
            }
        }
    }
}

/**
 * Down, up, up twice — and unset, which is reachable by tapping the one that is set.
 *
 * Choosing one takes the others away: the row is a question while it is open and an
 * answer once it is closed, and three glyphs left standing beside the chosen one read
 * as three unmade choices (#268). Tapping what is left reopens the question.
 */
@Composable
internal fun VerdictThumbs(current: String?, onVerdict: (String?) -> Unit) {
    Row {
        listOf(
            StoredMedia.Verdict.DOWN,
            StoredMedia.Verdict.UP,
            StoredMedia.Verdict.DOUBLE_UP,
        ).filter { current == null || current == it }.forEach { v ->
            val selected = current == v
            Text(
                verdictGlyph(v),
                color = if (selected) Amber else Faint,
                fontSize = 15.sp,
                modifier = Modifier
                    .clickable { onVerdict(if (selected) null else v) }
                    .semantics {
                        contentDescription = verdictLabel(v)
                        this.selected = selected
                        role = Role.Button
                    }
                    .padding(end = 10.dp, top = 2.dp, bottom = 2.dp),
            )
        }
    }
}

internal fun verdictGlyph(verdict: String?): String = when (verdict) {
    StoredMedia.Verdict.DOWN -> "👎"
    StoredMedia.Verdict.UP -> "👍"
    StoredMedia.Verdict.DOUBLE_UP -> "👍👍"
    else -> ""
}

/** A verdict someone else gave, in words — the glyph means nothing read aloud. */
internal fun verdictWords(verdict: String?): String = when (verdict) {
    StoredMedia.Verdict.DOWN -> "rated down"
    StoredMedia.Verdict.UP -> "rated up"
    StoredMedia.Verdict.DOUBLE_UP -> "rated up twice"
    else -> ""
}

internal fun verdictLabel(verdict: String?): String = when (verdict) {
    StoredMedia.Verdict.DOWN -> "Rate down"
    StoredMedia.Verdict.UP -> "Rate up"
    StoredMedia.Verdict.DOUBLE_UP -> "Rate up twice"
    else -> "Rate"
}
