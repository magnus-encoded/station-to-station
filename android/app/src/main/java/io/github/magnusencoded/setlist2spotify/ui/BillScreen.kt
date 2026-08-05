package io.github.magnusencoded.setlist2spotify.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.magnusencoded.setlist2spotify.BuildConfig
import io.github.magnusencoded.setlist2spotify.data.StoredAct
import io.github.magnusencoded.setlist2spotify.data.StoredBill
import io.github.magnusencoded.setlist2spotify.data.StoredLog
import io.github.magnusencoded.setlist2spotify.data.parseFmDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// The palette, per file, as everywhere else on this timeline.
private val Raised = Color(0xFF17121F)
private val LineCol = Color(0xFF2E2740)
private val Ink = Color(0xFFEDE9F2)
private val Muted = Color(0xFF8B8299)
private val Faint = Color(0xFF5A5368)
private val Amber = Color(0xFFE7B24C)
private val Slate = Color(0xFF6D7E9B)
private val Danger = Color(0xFFE08A8A)
private val Serif = FontFamily.Serif

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
 */
@Composable
fun BillItem(
    bill: StoredBill,
    open: Boolean,
    fetching: Boolean,
    onToggle: () -> Unit,
    onPlayed: (Int) -> Unit,
    onUnmark: (Int) -> Unit,
    onOpenGig: (String) -> Unit,
    onSurprise: (String) -> Unit,
    onFetchCandidates: () -> Unit,
    onRemove: () -> Unit,
) {
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
                onPlayed = { onPlayed(i) },
                onUnmark = { onUnmark(i) },
                onOpenGig = onOpenGig,
            )
        }
        SurpriseField(onSurprise)
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
 * One **Act**. Undated, the whole row is the field gesture: one tap says it played
 * tonight and the act becomes a **Gig** I was at. Dated, it opens that **Gig**; a
 * long press is the way back out of a mistap.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActRow(
    act: StoredAct,
    onPlayed: () -> Unit,
    onUnmark: () -> Unit,
    onOpenGig: (String) -> Unit,
) {
    val name = act.name
    val gigId = act.gigId
    val seen = gigId != null
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ActRowHeight)
            .combinedClickable(
                onClick = { if (gigId != null) onOpenGig(gigId) else onPlayed() },
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
            Text(sub, fontSize = 11.sp, color = if (seen) Amber else Faint)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            if (seen) "open ›" else "played tonight",
            fontSize = 13.sp,
            fontWeight = if (seen) FontWeight.Normal else FontWeight.SemiBold,
            color = if (seen) Faint else Slate,
        )
    }
}

/** An act nobody announced. Typed once, dated on arrival — see `addSurpriseAct`. */
@Composable
private fun SurpriseField(onSurprise: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.padding(start = SpineWidth, end = 18.dp, top = 6.dp)) {
        StationField(
            value = text,
            onValueChange = { text = it },
            label = "someone nobody announced",
            imeDone = true,
        )
        if (text.isNotBlank()) {
            Text(
                "+ add \"${text.trim()}\", played tonight",
                color = Amber,
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable { onSurprise(text.trim()); text = "" }
                    .padding(vertical = 10.dp),
            )
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
            if (BuildConfig.DEBUG) {
                Spacer(Modifier.height(10.dp))
                // ponytail: a seed for the one festival this was built the night before.
                // Debug-only so it cannot reach a release, and delete it after Ringnes —
                // the app has no business knowing about a farm in Norway.
                Text(
                    "↺ fill in Ringnes 2026",
                    color = Slate,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickable {
                            name = RINGNES_NAME
                            city = RINGNES_CITY
                            from = RINGNES_FROM
                            to = RINGNES_TO
                            lineup = RINGNES_LINEUP
                        }
                        .padding(vertical = 8.dp),
                )
            }
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
 * The setlist of a night this app owns: tick off what the artist has been playing,
 * type in what isn't there.
 *
 * The pool is a *prompt*, never a claim — nothing enters the record until it is
 * tapped, so "I think they played X" never becomes "they played X" by inaction. An
 * artist with no pool is the ordinary case here, not a failure, so the typing path
 * is always present rather than a fallback.
 *
 * [chosen] is ordered and stays ordered: running order is the payload the setlist.fm
 * paste carries, and it is the only thing distinguishing a song played twice.
 */
@Composable
fun LogEditor(
    candidates: List<String>,
    /** Which artist [candidates] came from, named so a wrong match can be distrusted. */
    poolArtist: String,
    log: StoredLog,
    /** How many songs setlist.fm's own record holds, when there is one. */
    published: Int?,
    onChange: (List<String>) -> Unit,
    onClosed: (Boolean) -> Unit,
    /** A song the owner knows this band plays, used to find the right namesake. */
    onDisambiguate: (String) -> Unit = {},
    searching: Boolean = false,
) {
    var typed by remember { mutableStateOf("") }
    var knownSong by remember { mutableStateOf("") }
    val chosen = log.songs
    val remaining = candidates.filterNot { c -> chosen.any { it.equals(c, ignoreCase = true) } }
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text(
            if (chosen.isEmpty()) "What did they play?" else "Your log of this night",
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
        // The set as it stands, numbered, in the order it was tapped in — which is
        // the running order, which is the whole payload of the setlist.fm paste. A
        // song played twice appears twice, and only its position tells them apart.
        chosen.forEachIndexed { i, song ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = ActRowHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${i + 1}", color = Faint, fontSize = 12.sp, modifier = Modifier.width(24.dp))
                // A **Gap** is a song that was played and could not be named. It is in
                // the record on purpose: an acknowledged hole is a true fact, and the
                // same song silently absent is the record lying about what it knows.
                Text(
                    song.ifBlank { "— one I couldn't name —" },
                    color = if (song.isBlank()) Faint else Ink,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "×",
                    color = Faint,
                    fontSize = 20.sp,
                    modifier = Modifier
                        .clickable { onChange(chosen.filterIndexed { j, _ -> j != i }) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
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
                    .clickable { onChange(chosen + typed.trim()); typed = "" }
                    .padding(vertical = 10.dp),
            )
        }
        Text(
            "+ they played one I can't name",
            color = Slate,
            fontSize = 13.sp,
            modifier = Modifier
                .clickable { onChange(chosen + "") }
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
            // The way out of a wrong match, and it asks the one question a person in a
            // field can actually answer. A picker would offer five identical names.
            if (poolArtist.isNotBlank()) {
                StationField(
                    value = knownSong,
                    onValueChange = { knownSong = it },
                    label = "not them? name a song you know they play",
                    imeDone = true,
                )
                if (knownSong.isNotBlank()) {
                    Text(
                        if (searching) "looking for a band that plays it…"
                        else "→ find the right ${poolArtist.substringBefore(" (")}",
                        color = if (searching) Faint else Amber,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clickable(enabled = !searching) { onDisambiguate(knownSong.trim()) }
                            .padding(vertical = 10.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            remaining.forEach { song ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = ActRowHeight)
                        .clickable { onChange(chosen + song) },
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
            Modifier.fillMaxWidth().heightIn(min = ActRowHeight).clickable { onClosed(!log.closed) },
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

// The one lineup this was built the night before, so tomorrow costs no typing.
// Debug-only (see AddBillDialog). Delete after Ringnes 2026.
//
// The name is setlist.fm's short form, not the festival's own "Hilsen fra RINGNES
// 25 år": it is the string every minted Gig carries as its venue, and a node has
// no room for a full official title. Getting the long name onto setlist.fm is an
// upstream matter and not this record's problem.
private const val RINGNES_NAME = "Ringnes Festival 2026"
// The town, not the country — this is what venueMapsQuery hands the geocoder, so
// "open in maps" and the check-in proximity test both need a real place.
// Skotbuveien 118, Skotbu.
private const val RINGNES_CITY = "Skotbu"
// The nights with music. The event itself runs to the 9th, but the programme is
// "6, 7 & 8.august" and no Act plays the last day — a range is what an Act gets
// dated within, so it ends where the music does.
private const val RINGNES_FROM = "06-08-2026"
private const val RINGNES_TO = "08-08-2026"
private const val RINGNES_LINEUP = """Cowboyfrokost TRIO
Du&Du
Enok Monk
Martin Hagfors
Linge + Erga
Silent Majority
Sugarfoot DUO
Villskudd
?Eivind Staxrud
?Bent Sæther
?Truls Lorentzen"""
