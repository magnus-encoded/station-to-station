package io.github.magnusencoded.stationtostation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.magnusencoded.stationtostation.AddGigLink
import io.github.magnusencoded.stationtostation.NightKind
import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.data.FriendArrival
import io.github.magnusencoded.stationtostation.data.MediaOffer
import io.github.magnusencoded.stationtostation.data.StoredMedia
import io.github.magnusencoded.stationtostation.data.atUser
import io.github.magnusencoded.stationtostation.data.handle
import io.github.magnusencoded.stationtostation.data.musicbrainz.MbArtist
import io.github.magnusencoded.stationtostation.data.nameOf
import io.github.magnusencoded.stationtostation.data.parseFmDate
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.nightKind
import java.time.LocalDate

private val Amber = Color(0xFFE7B24C)
private val Raised = Color(0xFF17121F)
private val Ink = Color(0xFFEDE9F2)
private val Muted = Color(0xFF8B8299)
private val Faint = Color(0xFF5A5368)
private val Slate = Color(0xFF6F809D) // the future / a connected-source, a cooler light
private val Serif = FontFamily.Serif
private val Danger = Color(0xFFE08A8A)

/**
 * Add a **Gig**: who played or is playing, where, and when. One form for both, because
 * the input is the same and both put a **Gig** on my **Line**; the date decides the rule
 * underneath (see [nightKind]).
 *
 * **A night I was at** has no upstream record to collide with, so it is minted locally
 * and claimed attended. **A night I am going to** is minted locally too and claims
 * nothing, because setlist.fm's search index stops about a day out and a show weeks away
 * cannot be *found* by artist, venue or date; it moves onto the vendor id when setlist.fm
 * catches up.
 *
 * **The link path stays, demoted.** A setlist.fm link is strictly better when you have
 * it: it brings the real id, venue and date, and needs no adoption later.
 *
 * **The artist completes; the venue does not.** MusicBrainz has a `place` entity and
 * its coverage of small rooms is thin, so a completion box that fails most of the time
 * would teach people to ignore the one above it. A plain field that never guesses is
 * the honest version of a venue.
 */
@Composable
internal fun AddGigDialog(
    initial: AddGigLink?,
    suggestions: List<MbArtist>,
    onArtistTyped: (String) -> Unit,
    onArtistPicked: (MbArtist) -> Unit,
    onAdd: (artist: String, venue: String, date: String) -> Unit,
    onAddByLink: (String) -> Unit,
    onDismiss: () -> Unit,
    /** The **Tour**'s coach mark, which the dialog would otherwise cover. */
    tour: @Composable () -> Unit = {},
    tourPlanning: Boolean = false,
) {
    var artist by remember { mutableStateOf(initial?.artist.orEmpty()) }
    var pickedArtist by remember { mutableStateOf<MbArtist?>(null) }
    var venue by remember { mutableStateOf(initial?.venue.orEmpty()) }
    var date by remember { mutableStateOf(initial?.date.orEmpty()) }
    var link by remember { mutableStateOf("") }
    var pasting by remember { mutableStateOf(false) }
    val kind = nightKind(parseFmDate(date), LocalDate.now())

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Raised)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            tour()
            Text("Add a gig", fontFamily = Serif, fontSize = 19.sp, color = Ink, modifier = Modifier.asHeading())
            Spacer(Modifier.height(6.dp))
            if (pasting) {
                Text(
                    "Paste the setlist.fm link for the show. It brings the real venue " +
                        "and date with it.",
                    color = Muted,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(14.dp))
                StationField(link, { link = it }, "setlist.fm link", imeDone = true)
            } else {
                Text(
                    when (kind) {
                        NightKind.GOING_TO ->
                            "A night ahead can't be searched for, so it lives on this phone " +
                                "until setlist.fm catches up with it."
                        NightKind.WAS_AT ->
                            "No account needed. This night lives on this phone, and what was " +
                                "played goes in its log afterwards."
                    },
                    color = Muted,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(14.dp))
                StationField(artist, { artist = it; pickedArtist = null; onArtistTyped(it) }, "who's playing")
                ArtistSuggestions(suggestions) { artist = it.name; pickedArtist = it; onArtistPicked(it) }
                Spacer(Modifier.height(8.dp))
                StationField(venue, { venue = it }, if (tourPlanning) "venue" else "venue (optional)")
                Spacer(Modifier.height(8.dp))
                StationField(date, { date = it }, "date (dd-MM-yyyy)", imeDone = true)
            }

            Spacer(Modifier.height(4.dp))
            if (!tourPlanning) TextButton(onClick = { pasting = !pasting }) {
                Text(
                    if (pasting) "or type it in" else "or paste a setlist.fm link",
                    color = Faint,
                    fontSize = 12.sp,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = Faint) }
                val ready =
                    if (pasting) link.isNotBlank() else artist.isNotBlank() && date.isNotBlank() &&
                        (!tourPlanning || (pickedArtist != null && venue.isNotBlank() && parseFmDate(date)?.isAfter(LocalDate.now()) == true))
                TextButton(
                    onClick = {
                        if (pasting) onAddByLink(link) else onAdd(artist, venue, date)
                    },
                    enabled = ready,
                ) { Text("Add", color = if (ready) Amber else Faint) }
            }
        }
    }
}

/** Whose offer this is: the Contact's name, or "A Contact" when their key is not on my list. */
internal fun offerSender(offer: MediaOffer, friends: List<Friend>): String =
    offer.media.firstNotNullOfOrNull { it.from }?.let { friends.nameOf(it) } ?: "A Contact"

/** "Mia offered 3 photos", counted the way a person would say it. */
internal fun offerLine(offer: MediaOffer, sender: String): String {
    val n = offer.media.size
    val kinds = offer.media.map { it.kind }.toSet()
    val what = when (kinds.singleOrNull()) {
        StoredMedia.Kind.PHOTO -> if (n == 1) "a photo" else "$n photos"
        StoredMedia.Kind.VIDEO -> if (n == 1) "a video" else "$n videos"
        StoredMedia.Kind.NOTE -> if (n == 1) "a note" else "$n notes"
        else -> "$n things"
    }
    return "$sender offered $what"
}

/**
 * A **Contact**'s offer, answered (#405). Their media is for a Night of theirs on this
 * date; saying yes files it here and joins the two, saying no leaves this Night exactly as
 * it was. "Not now" leaves the question waiting.
 */
@Composable
internal fun MediaOfferDialog(
    offer: MediaOffer,
    sender: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Raised)
                .padding(20.dp),
        ) {
            Text(offerLine(offer, sender), fontFamily = Serif, fontSize = 19.sp, color = Ink, modifier = Modifier.asHeading())
            Spacer(Modifier.height(6.dp))
            val theirs = listOf(offer.artist, offer.venue).filter { it.isNotBlank() }.joinToString(" at ")
            Text(
                (if (theirs.isNotBlank()) "From their night: $theirs. " else "") +
                    "Accept puts them on this night, as the same night. Decline leaves it as it is.",
                color = Muted,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Not now", color = Faint) }
                TextButton(
                    onClick = onDecline,
                    modifier = Modifier.semantics { contentDescription = "Decline $sender's offer" },
                ) { Text("Decline", color = Slate) }
                TextButton(
                    onClick = onAccept,
                    modifier = Modifier.semantics { contentDescription = "Accept $sender's offer onto this night" },
                ) { Text("Accept", color = Amber) }
            }
        }
    }
}

/**
 * "Are you here?" — the one thing a check-in asks. Shown only when a fix already
 * put the phone at the venue on the night, so it states what it thinks and offers
 * the two honest answers.
 */
@Composable
internal fun CheckInDialog(gig: FmSetlist, onCheckIn: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Raised)
                .padding(20.dp),
        ) {
            Text("Are you here?", fontFamily = Serif, fontSize = 19.sp, color = Ink, modifier = Modifier.asHeading())
            Spacer(Modifier.height(6.dp))
            Text(
                "${gig.artist?.name ?: "This show"} at ${gig.venue?.name ?: "the venue"}, tonight.",
                color = Muted,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Checking in records that you were at it — on this phone, nowhere else.",
                color = Faint,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Not now", color = Faint) }
                TextButton(onClick = onCheckIn) { Text("Check in", color = Amber) }
            }
        }
    }
}

/**
 * The one question a handed-over card has to ask: it names someone I already hold, and
 * says something different about them (#188).
 *
 * Shown only for a change. A card for a stranger is written without asking, and the
 * same card twice asks nothing — a prompt that routinely means nothing is a prompt
 * nobody reads, and this one has to be read.
 *
 * It names **both** values rather than only the new one, because the question is not
 * "is this name plausible" but "did the person in front of you mean to change what you
 * already had". A card can be handed over by a radio nobody tapped.
 */
@Composable
internal fun FriendOverwriteDialog(
    conflict: FriendArrival.Conflict,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Raised)
                .padding(20.dp),
        ) {
            Text("Change this contact?", fontFamily = Serif, fontSize = 19.sp, color = Ink, modifier = Modifier.asHeading())
            Spacer(Modifier.height(6.dp))
            // A changed key is a changed phone, and that is how the question is asked:
            // someone who bought a handset recognises it immediately, and someone who did
            // not has just been shown an attack. Cryptography is not a thing to ask a
            // person about. A first key never lands here — that is a promotion (#188).
            val keyChanged = conflict.existing.publicKey != null &&
                conflict.incoming.publicKey != null &&
                conflict.existing.publicKey != conflict.incoming.publicKey
            Text(
                if (keyChanged) {
                    "${conflict.existing.name}${conflict.existing.atUser} seems to " +
                        "be on a different phone than last time you saw them. Confirm you " +
                        "still want to share."
                } else {
                    "A card for ${conflict.existing.handle} says something different " +
                        "from what you have."
                },
                color = Muted,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text("Now: ${conflict.existing.name}${conflict.existing.atUser}", color = Ink, fontSize = 13.sp)
            Text("Card: ${conflict.incoming.name}${conflict.incoming.atUser}", color = Amber, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            // A card with a different setlist.fm username — or a first one — changes where
            // their Line is read from (#405), so the reassurance only holds when it does not.
            val sameUser = conflict.incoming.setlistfm.isBlank() ||
                conflict.incoming.setlistfm.equals(conflict.existing.setlistfm, ignoreCase = true)
            if (sameUser) {
                Text(
                    "Their timeline does not change either way — only the name you see " +
                        "against it.",
                    color = Faint,
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Keep mine", color = Faint) }
                TextButton(onClick = onConfirm) { Text("Use the card", color = Amber) }
            }
        }
    }
}

/**
 * The one question a delete has to ask: this night holds the only copy of
 * [photos] photographs, and they go with it.
 *
 * Shown only when that count is above zero. A picture that also lives in the
 * gallery is a pointer, and stopping someone to confirm a pointer teaches them
 * to tap through the dialog that mattered.
 */
@Composable
internal fun DeleteNightDialog(photos: Int, onDelete: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Raised)
                .padding(20.dp),
        ) {
            Text("Delete this night?", fontFamily = Serif, fontSize = 19.sp, color = Ink, modifier = Modifier.asHeading())
            Spacer(Modifier.height(6.dp))
            Text(
                if (photos == 1) "Its photograph is only stored here. Deleting the night deletes it."
                else "Its $photos photographs are only stored here. Deleting the night deletes them.",
                color = Muted,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text("There is no undo.", color = Faint, fontSize = 11.sp)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Keep it", color = Faint) }
                TextButton(onClick = onDelete) { Text("Delete", color = Danger) }
            }
        }
    }
}
