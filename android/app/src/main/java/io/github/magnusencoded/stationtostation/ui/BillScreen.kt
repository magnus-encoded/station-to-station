package io.github.magnusencoded.stationtostation.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.magnusencoded.stationtostation.data.BillWhen
import io.github.magnusencoded.stationtostation.data.StoredAct
import io.github.magnusencoded.stationtostation.data.StoredBill
import io.github.magnusencoded.stationtostation.data.StoredLog
import io.github.magnusencoded.stationtostation.data.billWhen
import io.github.magnusencoded.stationtostation.data.nights
import io.github.magnusencoded.stationtostation.data.parseFmDate
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// The palette, per file, as everywhere else on this timeline.
private val Raised  = Color(0xFF17121F)
private val LineCol = Color(0xFF2E2740)
private val Ink     = Color(0xFFEDE9F2)
private val Muted   = Color(0xFF8B8299)
private val Faint   = Color(0xFF5A5368)
private val Amber   = Color(0xFFE7B24C)
private val Slate   = Color(0xFF6D7E9B)
private val Danger  = Color(0xFFE08A8A)
private val Serif   = FontFamily.Serif

/** Big targets: one hand, sunlight, standing up. Everything here is thumb-sized. */
private val ActRowHeight = 56.dp

/**
 * A **Bill** on the timeline: one **Node**, above today, holding a lineup whose nights
 * nobody knows yet.
 *
 * Its ring is **Slate** until an **Act** has actually been seen and **Amber** after —
 * amber means mine *and it happened*, and a poster on the wall has not happened. The
 * number inside is what has been seen, not what was announced: a **Bill** that
 * promises eleven acts and delivered three is telling the truth about three.
 *
 * It opens in place, like a **Festival**, because that is what it is.
 *
 * What an undated **Act** offers depends on where the clock stands relative to the
 * **Bill**'s own range ([billWhen]) — "played tonight" only while there is a tonight
 * to mean. The night a tap produces is the second argument of [onPlayed]: null is
 * tonight, a date is a night picked off the range.
 */
@Composable
fun BillItem(
    bill: StoredBill,
    open: Boolean,
    fetching: Boolean,
    onToggle: () -> Unit,
    onPlayed: (Int, LocalDate?) -> Unit,
    onUnmark: (Int) -> Unit,
    onOpenGig: (String) -> Unit,
    onSurprise: (String, LocalDate?) -> Unit,
    onFetchCandidates: () -> Unit,
    onRemove: () -> Unit,
    onRename: (Int, String) -> Unit,
    now: LocalDateTime = LocalDateTime.now(),
) {
    val phase = billWhen(bill, now)
    val nights = bill.nights()
    val seen = bill.acts.count { it.gigId != null }
    val accent = if (seen > 0) Amber else Slate
    // Opportunistic, and this is the whole scheduler: opening a Bill that still has
    // unanswered acts is the reason to think a lookup might work. Not a timer, not a
    // background job, no retry loop — in the enclosure it fails once and stops, and
    // re-opening the Bill is the retry, made by someone who has a reason to try.
    LaunchedEffect(bill.id, open) { if (open) onFetchCandidates() }
    Column {
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(onClick = onToggle),
        ) {
            Box(Modifier.width(SpineWidth).fillMaxHeight()) {
                Box(
                    Modifier.padding(start = SpineX).width(2.dp).fillMaxHeight()
                        .background(LineCol),
                )
                Box(
                    Modifier
                        .padding(start = SpineX - 10.dp, top = 4.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color.Transparent)
                        .border(2.dp, accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "$seen",
                        color = accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Column(Modifier.padding(end = 18.dp, bottom = 18.dp)) {
                Text("BILL", color = Slate, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(3.dp))
                Text(bill.name, fontFamily = Serif, fontSize = 17.sp, color = Ink)
                Spacer(Modifier.height(2.dp))
                Text(billDates(bill), color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(7.dp))
                Text(
                    buildString {
                        // What is known and what is not, said in that order and never
                        // conflated: eleven names is not eleven nights.
                        append("${bill.acts.size} acts")
                        val undated = bill.acts.size - seen
                        if (seen > 0) append(" · $seen seen")
                        if (undated > 0) append(" · $undated with no night yet")
                        append(if (open) " · tap to close" else " · tap to open")
                    },
                    color = Faint,
                    fontSize = 12.sp,
                )
            }
        }
        if (!open) return
        // The acts, in poster order. Never re-sorted: order is the only thing a
        // lineup reliably carries, and a seen act sliding to the top would lose it.
        bill.acts.forEachIndexed { i, act ->
            ActRow(
                act = act,
                phase = phase,
                nights = nights,
                onPlayed = { night -> onPlayed(i, night) },
                onUnmark = { onUnmark(i) },
                onOpenGig = onOpenGig,
                onRename = { corrected -> onRename(i, corrected) },
            )
        }
        // Nothing has played yet, so there is nobody to have been surprised by. The
        // field would only offer a way to date an act before its festival opened.
        if (phase != BillWhen.BEFORE) SurpriseField(phase, nights, onSurprise)
        Row(
            Modifier.fillMaxWidth().padding(start = SpineWidth, end = 18.dp, top = 4.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Only while something is actually in flight, and it clears either way.
            // There is no manual "fetch suggestions" row any more: opening the Bill
            // does it, and re-opening is the retry — a chore the owner had to
            // remember before losing signal was the wrong shape for this.
            Text(
                if (fetching) "looking up song suggestions…" else "",
                color = Faint,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            Text(
                "take this bill down",
                color = Danger,
                fontSize = 12.sp,
                modifier = Modifier.clickable(onClick = onRemove).padding(vertical = 6.dp),
            )
        }
    }
}

/**
 * One **Act**. Undated *during* the festival, the whole row is the field gesture: one
 * tap says it played tonight and the act becomes a **Gig** I was at. Dated, it opens
 * that **Gig**; a long press is the way back out of a mistap.
 *
 * Once the festival is over the same tap asks instead of claims — the clock cannot
 * name the night any more, and the **Bill**'s own nights are the answers. Before it
 * starts there is nothing to offer at all.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActRow(
    act: StoredAct,
    phase: BillWhen,
    nights: List<LocalDate>,
    onPlayed: (LocalDate?) -> Unit,
    onUnmark: () -> Unit,
    onOpenGig: (String) -> Unit,
    onRename: (String) -> Unit,
) {
    val name = act.name
    val gigId = act.gigId
    val seen = gigId != null
    // Undated, at a festival that has ended: the one case that has to be asked about.
    val ask = !seen && phase == BillWhen.AFTER
    var asking by remember(act.name) { mutableStateOf(false) }
    var editing by remember(act.name) { mutableStateOf(false) }
    if (editing) {
        // The whole row becomes the correction, because the row's own gesture is
        // "played tonight" and a field sharing it would fire that on every tap.
        var text by remember { mutableStateOf(act.name) }
        Column(Modifier.padding(start = SpineWidth, end = 18.dp, top = 6.dp, bottom = 6.dp)) {
            StationField(
                value = text,
                onValueChange = { text = it },
                label = "who is actually on the poster",
                imeDone = true,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "cancel",
                    color = Faint,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { editing = false }.padding(vertical = 10.dp),
                )
                Text(
                    "rename and look up again",
                    color = Amber,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clickable { onRename(text); editing = false }
                        .padding(vertical = 10.dp),
                )
            }
        }
        return
    }
    // Emitted as siblings into the Bill's own Column, so the night choices can open
    // underneath this row without wrapping every act in a layout of its own.
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ActRowHeight)
            .combinedClickable(
                onClick = {
                    when {
                        gigId != null -> onOpenGig(gigId)
                        // Nothing has played. The row is a name on a poster and the
                        // tap has no honest meaning yet, so it does nothing.
                        phase == BillWhen.BEFORE -> Unit
                        ask -> asking = !asking
                        else -> onPlayed(null)
                    }
                },
                // A Surprise can always be taken back off, dated or not: it was typed
                // by hand and a typo has nothing to return to. An act off the Bill only
                // has something to undo once it has been given a night.
                onLongClick = { if (seen || act.surprise) onUnmark() },
            )
            .padding(start = SpineWidth, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(9.dp).clip(CircleShape)
                .background(if (seen) Amber else Color.Transparent)
                .border(1.5.dp, if (seen) Amber else Faint, CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 16.sp, color = if (seen) Ink else Muted)
            // The way out has to be visible, or it may as well not exist — a mistyped
            // surprise with no stated escape is just wrong data you have to live with.
            val sub = if (seen) {
                if (act.surprise) "you were there · hold to remove" else "you were there · hold to undo"
            } else listOfNotNull(
                // A maybe stays a maybe until it plays. The poster hedged; so does this.
                "maybe".takeIf { act.maybe },
                when {
                    // Which artist the pool came from, wherever the pool is mentioned.
                    // Five bands are called Silent Majority and four of their setlists
                    // are no use here — naming the source is what lets a wrong match be
                    // spotted in the second it appears.
                    act.candidates.isNotEmpty() ->
                        "${act.candidates.size} songs from ${act.matchedArtist.ifBlank { act.name }}"
                    // Answered, and the answer was nothing. Correct and final — not
                    // pending, and not a spinner waiting to become something.
                    act.tried -> "no setlist.fm history"
                    else -> "no night yet"
                },
            ).joinToString(" · ")
            // The sub-line is where a wrong name shows itself ("no setlist.fm
            // history" is usually a spelling, not an absence), so it is also the way
            // to fix it. Only before the act has a night: afterwards the row is a
            // record of a night, not a line on a poster.
            Text(
                if (seen) sub else "$sub · fix the name",
                fontSize = 11.sp,
                color = if (seen) Amber else Faint,
                modifier = if (seen) Modifier else Modifier.clickable { editing = true },
            )
        }
        Spacer(Modifier.width(10.dp))
        // The affordance is only ever what the clock can honestly support. After the
        // festival "played tonight" is a claim about a night that does not exist, so
        // it becomes a question; before it, there is nothing to say at all.
        Text(
            when {
                seen -> "open ›"
                phase == BillWhen.BEFORE -> ""
                ask -> if (asking) "which night?" else "played · which night?"
                else -> "played tonight"
            },
            fontSize = 13.sp,
            fontWeight = if (seen) FontWeight.Normal else FontWeight.SemiBold,
            color = if (seen) Faint else Slate,
        )
    }
    if (ask && asking) {
        NightChoices(nights, Modifier.padding(start = SpineWidth, end = 18.dp)) { night ->
            asking = false
            onPlayed(night)
        }
    }
}

/**
 * The nights this **Bill** actually had, as a list to tap.
 *
 * Not a date picker: a festival is three or four days, and a picker would offer every
 * day there has ever been — including the ones this festival did not have, which is
 * the fabrication being closed off. The choices *are* the range.
 */
@Composable
private fun NightChoices(
    nights: List<LocalDate>,
    modifier: Modifier = Modifier,
    onPick: (LocalDate) -> Unit,
) {
    Column(modifier.padding(bottom = 10.dp)) {
        Text(
            "Which night did they play?",
            color = Slate,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        nights.forEach { night ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = ActRowHeight)
                    .clickable { onPick(night) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("·", color = Faint, fontSize = 16.sp)
                Spacer(Modifier.width(12.dp))
                Text(night.format(NightLabel), color = Amber, fontSize = 15.sp)
            }
        }
    }
}

/** "Thu 6 Aug" — the day of the week is what someone remembers a festival night by. */
private val NightLabel: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

/**
 * An act nobody announced. Typed once, dated on arrival — see `addSurpriseAct`.
 *
 * Hand-typed is the easiest place of all to invent a date, so it is asked the same
 * question as the poster's own acts once the festival has ended.
 */
@Composable
private fun SurpriseField(
    phase: BillWhen,
    nights: List<LocalDate>,
    onSurprise: (String, LocalDate?) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf(false) }
    val ask = phase == BillWhen.AFTER
    Column(Modifier.padding(start = SpineWidth, end = 18.dp, top = 6.dp)) {
        StationField(
            value = text,
            onValueChange = { text = it; asking = false },
            label = "someone nobody announced",
            imeDone = true,
        )
        if (text.isNotBlank()) {
            Text(
                if (ask) "+ add \"${text.trim()}\" — which night?"
                else "+ add \"${text.trim()}\", played tonight",
                color = Amber,
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable {
                        if (ask) asking = !asking else { onSurprise(text.trim(), null); text = "" }
                    }
                    .padding(vertical = 10.dp),
            )
            if (ask && asking) {
                NightChoices(nights) { night ->
                    onSurprise(text.trim(), night)
                    text = ""
                    asking = false
                }
            }
        }
    }
}

/**
 * Putting a **Bill** on the wall. Five fields and a paste box, because the fastest
 * route in for eleven names is eleven names at once.
 *
 * No per-act date field, and there will not be one: the day each act plays is the
 * fact this whole design exists because nobody has.
 */
@Composable
fun AddBillDialog(
    onAdd: (name: String, city: String, from: String, to: String, lineup: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    var lineup by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Raised)
                .padding(20.dp),
        ) {
            Text("A festival, before it happens", fontFamily = Serif, fontSize = 19.sp, color = Ink)
            Spacer(Modifier.height(6.dp))
            Text(
                "The lineup is known; which night each act plays is not. Paste the " +
                    "names, one per line. Start a line with ? for an act that might " +
                    "not turn up.",
                color = Muted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(12.dp))
            StationField(name, { name = it }, "festival")
            Spacer(Modifier.height(8.dp))
            StationField(city, { city = it }, "town")
            Spacer(Modifier.height(8.dp))
            Row {
                Box(Modifier.weight(1f)) { StationField(from, { from = it }, "from (dd-MM-yyyy)") }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) { StationField(to, { to = it }, "to (dd-MM-yyyy)") }
            }
            Spacer(Modifier.height(8.dp))
            StationField(lineup, { lineup = it }, "the lineup, one act per line", singleLine = false)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = Faint) }
                TextButton(
                    onClick = { onAdd(name, city, from, to, lineup) },
                    enabled = name.isNotBlank() && lineup.isNotBlank(),
                ) {
                    Text(
                        "Put it up",
                        color = if (name.isBlank() || lineup.isBlank()) Faint else Amber,
                    )
                }
            }
        }
    }
}

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

/** "6 – 9 Aug 2026", or whatever of the range was actually given. */
private fun billDates(bill: StoredBill): String {
    val a = parseFmDate(bill.from)
    val b = parseFmDate(bill.to)
    val full = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    val short = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    return when {
        a != null && b != null && a != b -> "${a.format(short)} – ${b.format(full)}"
        a != null -> a.format(full)
        b != null -> b.format(full)
        else -> "dates not given"
    }
}

// ---- Previews -------------------------------------------------------------
//
// **The wrapper is the fixture.** [BillItem] takes eleven parameters, so it cannot
// be previewed directly — a preview has to be callable with no arguments. Supplying
// them here is not boilerplate around the real thing, it is the only place a
// **Bill** in a *chosen* state can be looked at without getting the app into that
// state on a device first.
//
// [now] is pinned rather than [LocalDateTime.now]: [billWhen] reads the clock, so a
// live one makes these three renders drift through the phases over a week and a
// preview that changes on its own is not a reference.
//
// Names invented, as in the test fixtures — this repository is public.

private val previewBill = StoredBill(
    id = "preview",
    name = "Verandaen Festival 2026",
    city = "Skotbu",
    from = "12-06-2026",
    to = "14-06-2026",
    acts = listOf(
        StoredAct(name = "Paper Cranes"),
        StoredAct(name = "Tøyen Tapes", maybe = true),
        StoredAct(name = "Halden Drift"),
    ),
)

/** Mid-festival, collapsed: the node as it sits in a list. */
@Preview(name = "Bill — collapsed", showBackground = true, backgroundColor = 0xFF101014)
@Composable
internal fun BillItemCollapsedPreview() {
    BillItem(
        bill = previewBill,
        open = false,
        fetching = false,
        onToggle = {}, onPlayed = { _, _ -> }, onUnmark = {}, onOpenGig = {},
        onSurprise = { _, _ -> }, onFetchCandidates = {}, onRemove = {}, onRename = { _, _ -> },
        now = LocalDateTime.of(2026, 6, 13, 21, 0),
    )
}

/** Open, with one **Act** already marked as played — the amber case. */
@Preview(name = "Bill — open, one seen", showBackground = true, backgroundColor = 0xFF101014)
@Composable
internal fun BillItemOpenPreview() {
    BillItem(
        bill = previewBill.copy(
            acts = previewBill.acts.mapIndexed { i, act ->
                if (i == 0) act.copy(gigId = "g-preview", matchedArtist = "Paper Cranes") else act
            },
        ),
        open = true,
        fetching = false,
        onToggle = {}, onPlayed = { _, _ -> }, onUnmark = {}, onOpenGig = {},
        onSurprise = { _, _ -> }, onFetchCandidates = {}, onRemove = {}, onRename = { _, _ -> },
        now = LocalDateTime.of(2026, 6, 13, 21, 0),
    )
}

/** Open the night before, mid-lookup: the state the opportunistic fetch produces. */
@Preview(name = "Bill — fetching", showBackground = true, backgroundColor = 0xFF101014)
@Composable
internal fun BillItemFetchingPreview() {
    BillItem(
        bill = previewBill,
        open = true,
        fetching = true,
        onToggle = {}, onPlayed = { _, _ -> }, onUnmark = {}, onOpenGig = {},
        onSurprise = { _, _ -> }, onFetchCandidates = {}, onRemove = {}, onRename = { _, _ -> },
        now = LocalDateTime.of(2026, 6, 11, 20, 0),
    )
}
