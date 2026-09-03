package io.github.magnusencoded.stationtostation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.magnusencoded.stationtostation.data.StoredLog

// The palette, per file, as everywhere else on this timeline.
private val Raised = Color(0xFF17121F)
private val Ink    = Color(0xFFEDE9F2)
private val Muted  = Color(0xFF8B8299)
private val Faint  = Color(0xFF5A5368)
private val Amber  = Color(0xFFE7B24C)
private val Slate  = Color(0xFF6D7E9B)
private val Serif  = androidx.compose.ui.text.font.FontFamily.Serif

/** Big targets: one hand, sunlight, standing up. Everything here is thumb-sized. */
private val ActRowHeight = 56.dp

/**
 * The way into the setlist of a night this app owns: tick off what the artist has
 * been playing, type in what isn't there, and say whether that was all of it.
 *
 * The pool is a *prompt*, never a claim — nothing enters the record until it is
 * tapped, so "I think they played X" never becomes "they played X" by inaction. An
 * artist with no pool is the ordinary case here, not a failure, so the typing path
 * is always present rather than a fallback.
 *
 * **The entries are not here.** They are drawn on the night's own spine, woven
 * against setlist.fm's record so a song both hold is one line rather than two
 * (#268) — which is also where they are removed and corrected, because an entry
 * should be edited where it is read. What is left here is everything that is about
 * the log rather than in it.
 */
@Composable
fun LogEditor(
    candidates: List<String>,
    /** Which artist [candidates] came from, named so a wrong match can be distrusted. */
    poolArtist: String,
    log: StoredLog,
    /** How many songs setlist.fm's own record holds, when there is one. */
    published: Int?,
    onAdd: (String) -> Unit,
    onClosed: (Boolean) -> Unit,
    /** The typed song, used to find the right namesake instead of being logged. */
    onDisambiguate: (String) -> Unit = {},
    searching: Boolean = false,
) {
    var typed by remember { mutableStateOf("") }
    val chosen = log.songs
    val remaining = candidates.filterNot { c -> chosen.any { it.equals(c, ignoreCase = true) } }
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text(
            // The heading names what this surface is *for*, and it stopped being the
            // log itself when the entries moved onto the spine above it (#268).
            if (chosen.isEmpty()) "What did they play?" else "Anything else they played?",
            fontFamily = Serif,
            fontSize = 16.sp,
            color = Ink,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Yours, on this phone. Only what you tap is recorded — nothing here is " +
                "guessed on your behalf.",
            color = Faint,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(10.dp))
        StationField(typed, { typed = it }, "a song they played", imeDone = true)
        // The escape hatch, always present and never a fallback. A pool built from what
        // an artist has played before cannot contain a new song, a cover, a guest spot
        // or anything at all by an artist setlist.fm has never heard of — so a capture
        // that could only say yes or no would be incomplete by construction.
        if (typed.isNotBlank()) {
            Text(
                "+ add \"${typed.trim()}\"",
                color = Amber,
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable { onAdd(typed.trim()); typed = "" }
                    .padding(vertical = 10.dp),
            )
            // The way out of a wrong match, on the song already typed above. It used to
            // have a field of its own asking "name a song you know they play" — which is
            // the same song: one you just heard them play is one you know they play, so
            // the screen asked twice for a title you had already given it. Two fields
            // wanting the same thing is a question about which, and there is no answer.
            //
            // A picker would be worse still: it offers five identical names, and the
            // names being identical is the entire problem. A title is answerable.
            //
            // Only the tap decides where it goes. "+ add" puts it in the **Log**;
            // this looks the band up and writes nothing — naming a song to identify a
            // band is not a claim they played it tonight, and that rule survives the
            // fields merging because the actions stayed separate.
            if (poolArtist.isNotBlank()) {
                Text(
                    if (searching) "looking for a band that plays it…"
                    else "→ not them? find who plays \"${typed.trim()}\"",
                    color = if (searching) Faint else Slate,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clickable(enabled = !searching) { onDisambiguate(typed.trim()) }
                        .padding(vertical = 10.dp),
                )
            }
        }
        Text(
            "+ they played one I can't name",
            color = Slate,
            fontSize = 13.sp,
            modifier = Modifier
                .clickable { onAdd("") }
                .padding(vertical = 12.dp),
        )
        if (remaining.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            // Named, not implied. The pool comes from whichever artist a name search
            // landed on, and names are not unique — this line is what turns a wrong
            // match from an invisible corruption into an obvious one.
            Text(
                if (poolArtist.isBlank()) "They have been playing these — tap the ones you heard"
                else "$poolArtist has been playing these — tap the ones you heard",
                color = Slate,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            remaining.forEach { song ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = ActRowHeight)
                        .clickable { onAdd(song) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("+", color = Slate, fontSize = 18.sp)
                    Spacer(Modifier.width(12.dp))
                    Text(song, color = Muted, fontSize = 15.sp)
                }
            }
        }
        // Whether this log claims to be the whole set. **Open** is the default and the
        // honest one; only a person may **Close** it, and publishing never does — a
        // set that went out to setlist.fm and came back has no completeness field to
        // come back in, so the bit is only safe if it never leaves.
        //
        // The label does NOT swap with the state, which is what made this read
        // backwards: an unticked box beside the words "there may be more" answers
        // *no* to the sentence next to it, so the default asserted completeness —
        // the exact opposite of what a Log is. One fixed sentence, and the box is
        // the claim: unticked means nobody has claimed it, which is the truth on a
        // capture built from prompts and is why it stays unticked by default.
        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth().heightIn(min = ActRowHeight).toggleable(
                value = log.closed,
                role = Role.Checkbox,
                onValueChange = { onClosed(it) },
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(16.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (log.closed) Amber else Color.Transparent)
                    .border(1.5.dp, if (log.closed) Amber else Faint, RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "That was the whole set",
                    color = if (log.closed) Ink else Muted,
                    fontSize = 14.sp,
                )
                Text(
                    if (log.closed) "tap if you remember more" else "there may be more until you tick this",
                    color = Faint,
                    fontSize = 11.sp,
                )
            }
        }
        if (log.gaps > 0) {
            Text(
                "${log.gaps} you couldn't name — still true, still in the record",
                color = Faint,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        // Divergence, shown and never merged. setlist.fm's list is the shared record;
        // this one is mine. Someone else filling in what I missed is the good case
        // (#34), and quietly overwriting either side with the other would lose a fact.
        if (published != null && published != chosen.size) {
            Spacer(Modifier.height(10.dp))
            Text(
                "setlist.fm has $published songs for this night; your log has ${chosen.size}. " +
                    "Neither is changed by the other.",
                color = Slate,
                fontSize = 11.sp,
            )
        }
    }
}

/**
 * Correcting one **Log** entry, in place, under the row it belongs to (#126).
 *
 * Sometimes what was typed in the dark is not the title but the only words that could
 * be caught. **Nothing is rewritten without a tap**: [candidates] are ranked against
 * what was written, never chosen, which is the same human-in-the-loop rule already
 * settled for correcting an **Act**'s name and for the same reason — "this string is
 * not a known song" cannot tell a title we don't know from a lyric whose title differs.
 *
 * The whole pool is offered rather than close matches only, because a remembered line
 * sharing no words with any title still has to be correctable. Where there is no pool
 * the panel says so and typing still works: an artist with nothing known is the
 * ordinary case here, not a failure.
 */
@Composable
internal fun CorrectEntry(
    written: String,
    candidates: List<String>,
    canRestore: Boolean,
    loading: Boolean,
    onPick: (String) -> Unit,
    onRestore: () -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    Column(Modifier.padding(start = 24.dp, bottom = 10.dp)) {
        Text(
            "What was this really called? \"$written\" is kept either way.",
            color = Slate,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(8.dp))
        StationField(typed, { typed = it }, "the title", imeDone = true)
        if (typed.isNotBlank()) {
            Text(
                "→ call it \"${typed.trim()}\"",
                color = Amber,
                fontSize = 13.sp,
                modifier = Modifier.clickable { onPick(typed.trim()) }.padding(vertical = 10.dp),
            )
        }
        if (candidates.isEmpty()) {
            Text(
                if (loading) "Looking up their songs…"
                else "Nothing known for this artist — type the title above.",
                color = Faint,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            Spacer(Modifier.height(6.dp))
            candidates.forEach { title ->
                Text(
                    title,
                    color = Muted,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = ActRowHeight)
                        .clickable { onPick(title) }
                        .padding(vertical = 10.dp),
                )
            }
        }
        // A wrong correction is never a one-way door.
        if (canRestore) {
            Text(
                "↩ put \"$written\" back as the entry",
                color = Slate,
                fontSize = 12.sp,
                modifier = Modifier.clickable(onClick = onRestore).padding(vertical = 10.dp),
            )
        }
    }
}

/** Pasting the link to the record the Historian just created. See `adoptSetlistLink`. */
@Composable
fun AdoptSetlistDialog(onAdopt: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(16.dp)).background(Raised).padding(20.dp),
        ) {
            Text("It's on setlist.fm now", fontFamily = Serif, fontSize = 19.sp, color = Ink)
            Spacer(Modifier.height(6.dp))
            Text(
                "Paste the link. This night takes their id and stops being a stub — " +
                    "which is what lets a friend who was there meet you on it.",
                color = Muted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(14.dp))
            StationField(text, { text = it }, "setlist.fm link", imeDone = true)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = Faint) }
                TextButton(onClick = { onAdopt(text) }, enabled = text.isNotBlank()) {
                    Text("Adopt", color = if (text.isBlank()) Faint else Amber)
                }
            }
        }
    }
}
