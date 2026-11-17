package io.github.magnusencoded.stationtostation.ui

import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.magnusencoded.stationtostation.NOT_STAMPED
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSong
import io.github.magnusencoded.stationtostation.data.setlistfm.line

private val Amber = Color(0xFFE7B24C)
private val Raised = Color(0xFF17121F)
private val Ink = Color(0xFFEDE9F2)
private val Muted = Color(0xFF8B8299)
private val Faint = Color(0xFF5A5368)
private val Ground = Color(0xFF0E0B14)
private val LineCol = Color(0xFF2E2740)
private val Raised2 = Color(0xFF1D1728)
private val LineLit = Color(0xFF4A3F63)

/** mm:ss, or h:mm:ss once a recording runs past the hour — a full gig usually does. */
internal fun formatOffset(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    return if (h > 0) "%d:%02d:%02d".format(h, (total % 3600) / 60, total % 60)
    else "%d:%02d".format(total / 60, total % 60)
}

/** One song inside the recording viewer: tap to stamp or to jump, long-press to clear. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun StampRow(
    number: Int,
    song: FmSong,
    offsetMs: Long,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val stamped = offsetMs > NOT_STAMPED
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClickLabel = if (stamped) "Jump to it" else "Stamp it here",
                onClick = onTap,
                onLongClickLabel = if (stamped) "Clear the stamp" else null,
                onLongClick = { if (stamped) onLongPress() },
            )
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$number", color = Faint, fontSize = 11.sp, modifier = Modifier.width(24.dp))
        Text(
            song.name,
            color = if (stamped) Ink else Muted,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (stamped) formatOffset(offsetMs) else "–",
            color = if (stamped) Amber else Faint,
            fontSize = 13.sp,
            // An en dash is read out as "en dash"; say what it means (#164).
            modifier = if (stamped) Modifier else Modifier.spokenAs("not stamped"),
        )
    }
}

/**
 * One song on the night's spine.
 *
 * [mine] is the overlap: this song is in setlist.fm's record *and* in my **Log**, and
 * the two records agreeing is the strongest thing a line here can say. It is drawn as
 * the ring going **Amber** — mine, the same as everywhere else — rather than as a
 * second copy of the song further down the screen (#268).
 */
@Composable
internal fun SongRow(
    number: Int?,
    song: FmSong,
    offsetMs: Long = NOT_STAMPED,
    mine: Boolean = false,
    /** The words I wrote before a title replaced them, where there were any. */
    remembered: String? = null,
    /** Drops my **Log** entry, leaving setlist.fm's row where it was. */
    onRemoveLog: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val cover = song.cover?.name
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            // One stop per song, and the amber ring said in words: it is the only thing
            // that tells a song my Log also holds from one it does not (#164).
            .semantics(mergeDescendants = true) { if (mine) stateDescription = "in your log" }
            .padding(end = 20.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(50.dp).fillMaxHeight()) {
            Box(Modifier.align(Alignment.TopCenter).width(2.dp).fillMaxHeight().background(LineCol))
            // A tape track sits on the line as a bare dot: it happened, it isn't
            // numbered, and it doesn't pretend to be part of the set.
            val size = if (number == null) 8.dp else 18.dp
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = if (number == null) 7.dp else 2.dp)
                    .size(size)
                    .clip(CircleShape)
                    // The page's own colour, not [Raised]: the line has to pass
                    // *underneath* the number, and a lighter disc reads as the line
                    // showing through it (#268).
                    .background(Ground)
                    .border(1.5.dp, if (mine) Amber else LineLit, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (number != null) Text(
                    number.toString(),
                    color = if (mine) Amber else Faint,
                    fontSize = 10.sp,
                    // Default font padding pads above the ascent, so a centred digit
                    // sits high in a circle this small. Dropping it is not enough on
                    // its own — the line box still carries the font's leading, and
                    // pinning lineHeight to the glyph size only moved the baseline.
                    // Trim both ends and centre what is left, which is the one
                    // arrangement where Center means the digit's centre (#268).
                    lineHeight = 10.sp,
                    textAlign = TextAlign.Center,
                    style = LocalTextStyle.current.copy(
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle = LineHeightStyle(
                            alignment = LineHeightStyle.Alignment.Center,
                            trim = LineHeightStyle.Trim.Both,
                        ),
                    ),
                )
            }
        }
        Column(Modifier.weight(1f).padding(top = 1.dp, bottom = 15.dp)) {
            Text(song.name, color = if (number == null) Muted else Ink, fontSize = 15.sp)
            val note = cover?.let { "$it cover" } ?: "tape".takeIf { song.tape }
            if (note != null) Text(note, color = Faint, fontSize = 11.sp)
            // What I wrote in the dark, under the title the record settled on. Kept
            // for the reason it is always kept: it is often *the* memory (#126).
            if (remembered != null) Text("\"$remembered\"", color = Faint, fontSize = 12.sp)
        }
        // Where this song sits in the night's recording, once someone has marked it.
        if (offsetMs > NOT_STAMPED) {
            Text(
                formatOffset(offsetMs),
                color = Amber,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (onRemoveLog != null) RemoveLogEntry(onRemoveLog)
    }
}

/**
 * What a screen reader says for a hint whose printed words are for a thumb (#164).
 *
 * The "‹ swipe to …" crumbs name the gesture and are tappable too; TalkBack takes the
 * swipe for itself, so it hears the action and not the gesture — and not the ‹, either.
 */
internal fun Modifier.spokenAs(label: String): Modifier = semantics { contentDescription = label }

/**
 * The × that takes one entry out of my **Log**.
 *
 * It never touches setlist.fm's row — on a line both records hold, removing mine
 * leaves the published song exactly where it was and only puts the ring out.
 */
@Composable
internal fun RemoveLogEntry(onRemove: () -> Unit) {
    Text(
        "×",
        color = Faint,
        fontSize = 20.sp,
        modifier = Modifier
            .clickable(onClick = onRemove)
            .semantics { contentDescription = "Remove from your log" }
            .padding(horizontal = 10.dp),
    )
}

/**
 * A song only my **Log** has: I wrote it down and setlist.fm's record does not hold it
 * — either because nobody has published it or because nobody else caught it (#268).
 *
 * **A number is a position in a record.** Where setlist.fm has a set, the numbers are
 * its numbers and mine gets a bare dot instead — the same mark a tape track gets, and
 * for the same reason: it happened, it is on the line, and it is not one of the
 * numbered songs. Where there is no published set my **Log** *is* the record of the
 * night, so [number] is its own position and the running order reads back.
 */
@Composable
internal fun LoggedRow(
    title: String,
    number: Int?,
    remembered: String?,
    onCorrect: (() -> Unit)?,
    onRemove: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .then(if (onCorrect != null) Modifier.clickable(onClickLabel = "Correct the title", onClick = onCorrect) else Modifier)
            .semantics(mergeDescendants = true) { stateDescription = "in your log" }
            .padding(end = 20.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(50.dp).fillMaxHeight()) {
            Box(Modifier.align(Alignment.TopCenter).width(2.dp).fillMaxHeight().background(LineCol))
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = if (number == null) 7.dp else 2.dp)
                    .size(if (number == null) 8.dp else 18.dp)
                    .clip(CircleShape)
                    .background(if (number == null && title.isNotBlank()) Amber else Ground)
                    .border(1.5.dp, Amber, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (number != null) Text(
                    number.toString(),
                    color = Amber,
                    fontSize = 10.sp,
                    lineHeight = 10.sp,
                    textAlign = TextAlign.Center,
                    // The same trimming SongRow needs, and for the same reason (#268).
                    style = LocalTextStyle.current.copy(
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle = LineHeightStyle(
                            alignment = LineHeightStyle.Alignment.Center,
                            trim = LineHeightStyle.Trim.Both,
                        ),
                    ),
                )
            }
        }
        Column(Modifier.weight(1f).padding(top = 1.dp, bottom = 15.dp)) {
            // A **Gap** is a song that was played and could not be named. It is in the
            // record on purpose: an acknowledged hole is a true fact, and the same
            // song silently absent is the record lying about what it knows.
            Text(
                title.ifBlank { "— one I couldn't name —" },
                color = if (title.isBlank()) Faint else Ink,
                fontSize = 15.sp,
            )
            if (remembered != null) Text("\"$remembered\"", color = Faint, fontSize = 12.sp)
        }
        if (onRemove != null) RemoveLogEntry(onRemove)
    }
}

@Composable
internal fun EncoreLabel() {
    Text(
        "— ENCORE —",
        color = Amber,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 2.sp,
        modifier = Modifier
            .padding(start = 50.dp, top = 4.dp, bottom = 14.dp)
            .semantics { contentDescription = "Encore"; heading() },
    )
}

@Composable
internal fun EventTag(
    text: String,
    color: Color = Muted,
    onClick: (() -> Unit)? = null,
    // What a reader should hear when the visible text is a bare identifier or a
    // glyph. Sighted readers get the id because the id is the record; TalkBack
    // reading it out loud says nothing about what a tap will do.
    label: String? = null,
) {
    Text(
        text,
        color = color,
        fontSize = 11.sp,
        modifier = Modifier
            .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
            .clip(RoundedCornerShape(20.dp))
            .background(Raised2)
            .border(1.dp, Color(0xFF2A2338), RoundedCornerShape(20.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}
