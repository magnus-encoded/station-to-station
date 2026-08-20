package io.github.magnusencoded.stationtostation.ui.flyover

import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.data.StoredLog
import io.github.magnusencoded.stationtostation.data.StoredMedia
import io.github.magnusencoded.stationtostation.data.WovenSong
import io.github.magnusencoded.stationtostation.ui.EventRow

/**
 * The night, as the **Flyover** needs it (#278): who is on it, what stands along the
 * spine, and what is said at the end of it.
 *
 * Kept apart from the screen for the reason [FlyoverGeometry.kt] is: this is where the
 * decisions live — which flank a photograph takes, which colour a **Contact** keeps,
 * what the wall holds and in what order — and every one of them is assertable without
 * a device. The screen below it does nothing but draw the answer.
 */

/**
 * A **Contact** whose records this night holds.
 *
 * **A colour per contact for this gig**: floor line, photo outline, note border, name
 * on the cover — one colour, so a person can be followed through the whole night. Never
 * **Amber**, which means mine.
 *
 * [colourIndex] is their position in the friends list, which is the same index the
 * **Timelines** resolution paints their **Lane** with. A person is therefore the same
 * colour on the woven timeline and on their floor line here, and hiding somebody
 * elsewhere cannot repaint them — the index is not a position in *this* list.
 */
data class FlyoverPerson(
    /** The `from` key their media carries. */
    val key: String,
    val name: String,
    val colourIndex: Int,
)

/** One photograph or video on the walk, placed. */
data class FlyoverPhoto(
    val id: String,
    val ref: String,
    val isVideo: Boolean,
    /** Left flank, and outlined **Amber**. */
    val mine: Boolean,
    /** Whose camera, when it wasn't mine. Null for mine, and for a stranger's. */
    val person: FlyoverPerson?,
    /** Held back from everyone. Mine only — a received item's disposition isn't mine. */
    val personal: Boolean,
    val z: Double,
)

/** One **Note** on the **Wall**. */
data class FlyoverNote(
    val id: String,
    val text: String,
    /** The **Verdict** the note carries, as its glyph. Empty for unset, which is real. */
    val verdict: String?,
    val mine: Boolean,
    val personal: Boolean,
    val person: FlyoverPerson?,
)

/** One marker on the spine. Evenly spaced — see [songZ]. */
data class FlyoverMarker(
    /** setlist.fm's own numbering. Null for a tape track, an encore rule, or a song
     *  only my **Log** holds — none of which take a number. */
    val number: Int?,
    val label: String,
    val encore: Boolean,
    /** Both records hold it: the strongest thing a row can say. */
    val agreed: Boolean,
    /** Only my **Log** holds it. */
    val loggedOnly: Boolean,
    val z: Double,
)

/** Everything the walk draws, in one value. */
data class FlyoverNight(
    val photos: List<FlyoverPhoto>,
    val markers: List<FlyoverMarker>,
    val notes: List<FlyoverNote>,
    /** In floor-line order, left to right — the same order the cover's key reads in. */
    val people: List<FlyoverPerson>,
    val wallZ: Double,
    /** How long the walk is, for [travelGain]. */
    val contentLength: Double,
)

/**
 * Which **Contacts** have a floor line, and in which order.
 *
 * **The people on the night are the people whose records it holds** — every distinct
 * sender across its media, notes included. Not everyone who was there: a floor line
 * runs under the flank their media sits on, and drawing one for somebody who gave
 * nothing is a lane under an empty stretch of night. The cover's key says the same
 * thing, in the same order, which is what makes it a key to the ground.
 *
 * Somebody not on the friends list keeps a colour anyway, after everyone who is, so
 * that two strangers are still two colours. Their name degrades to nothing rather than
 * being invented — the room already answers "someone else" the same way.
 */
fun flyoverPeople(media: List<StoredMedia>, friends: List<Friend>): List<FlyoverPerson> {
    val senders = media.mapNotNull { it.from }.distinct()
    var unknown = 0
    return senders.map { key ->
        val at = friends.indexOfFirst { it.setlistfm == key }
        if (at >= 0) FlyoverPerson(key, friends[at].name, at)
        else FlyoverPerson(key, "", friends.size + unknown++)
    }.sortedBy { it.colourIndex }
}

/**
 * The night's photographs and videos, placed along the spine.
 *
 * **Notes are not here.** A **Note** is **Media** and everything said about media
 * applies to it, but it carries no bytes and there is nothing to look at while walking
 * past it — its place is the **Wall**, which is what the end of the night is for.
 *
 * A reference that died is dropped rather than drawn as a hole: [StoredMedia.Kind]
 * records what an item *was*, and an item that was already unreadable when we looked
 * has no picture to put at that moment of the night.
 */
fun flyoverPhotos(
    media: List<StoredMedia>,
    people: List<FlyoverPerson>,
    songCount: Int,
): List<FlyoverPhoto> {
    val visual = media.filter {
        it.kind == StoredMedia.Kind.PHOTO || it.kind == StoredMedia.Kind.VIDEO
    }
    val byKey = people.associateBy { it.key }
    val placed = placeMedia(
        visual.map { FlyoverItem(id = it.id, mine = it.from == null, capturedAt = it.capturedAt) },
        songCount,
    ).associateBy { it.id }
    return visual.mapNotNull { item ->
        val at = placed[item.id] ?: return@mapNotNull null
        FlyoverPhoto(
            id = item.id,
            ref = item.ref,
            isVideo = item.kind == StoredMedia.Kind.VIDEO,
            mine = item.from == null,
            person = item.from?.let { byKey[it] },
            personal = item.personal,
            z = at.z,
        )
    }.sortedBy { it.z }
}

/**
 * What was said about the night, in reading order.
 *
 * Mine first and then everyone else's — the order the **Room** settled on for the same
 * reason (#268): what you wrote is yours to see first, and reading a **Contact**'s
 * account before your own quietly makes yours a reply to it. Theirs follow in floor-line
 * order, so the wall reads left-to-right the same way the ground did.
 */
fun flyoverNotes(media: List<StoredMedia>, people: List<FlyoverPerson>): List<FlyoverNote> {
    val byKey = people.associateBy { it.key }
    val notes = media.filter { it.kind == StoredMedia.Kind.NOTE && it.text.isNotBlank() }
    val mine = notes.filter { it.from == null }
        // Shared before vault: the one that reaches anybody leads.
        .sortedBy { it.personal }
    val theirs = notes.filter { it.from != null }
        .sortedBy { byKey[it.from]?.colourIndex ?: Int.MAX_VALUE }
    return (mine + theirs).map { note ->
        FlyoverNote(
            id = note.id,
            text = note.text,
            verdict = note.verdict,
            mine = note.from == null,
            personal = note.personal,
            person = note.from?.let { byKey[it] },
        )
    }
}

/**
 * The spine's markers: **the night is one list** (#268).
 *
 * The same weave the **Room** reads down, stood on end. A song both records hold is one
 * marker saying so; a song only my **Log** caught is a marker with no number, because
 * numbering it would push every published song after it out of step with setlist.fm.
 *
 * **Evenly spaced, and photographs sit deliberately off their gridlines.** There is no
 * photograph-to-song mapping available anywhere — setlist.fm has no per-song wall clock
 * and the only offsets that exist are the manual stamps inside a video — so anything
 * that aligned the two would be inventing a claim about the night.
 */
internal fun flyoverMarkers(rows: List<EventRow>, woven: List<WovenSong>, log: StoredLog): List<FlyoverMarker> =
    woven.mapIndexedNotNull { index, song ->
        val published = song.published?.let { rows.getOrNull(it) }
        when {
            published is EventRow.Encore -> FlyoverMarker(
                number = null,
                label = "encore",
                encore = true,
                agreed = false,
                loggedOnly = false,
                z = songZ(index),
            )
            published is EventRow.SongItem -> FlyoverMarker(
                number = published.number,
                label = published.song.name,
                encore = false,
                agreed = song.both,
                loggedOnly = false,
                z = songZ(index),
            )
            song.logged != null -> {
                val title = log.songs.getOrNull(song.logged).orEmpty()
                FlyoverMarker(
                    number = null,
                    // A **Gap** is a true fact about the night: they played something
                    // and it could not be named. It keeps its place on the spine and
                    // says so, rather than being dropped into a silence that would
                    // read as nothing having happened.
                    label = title.ifBlank { "—" },
                    encore = false,
                    agreed = false,
                    loggedOnly = true,
                    z = songZ(index),
                )
            }
            else -> null
        }
    }

/**
 * Assemble the whole night.
 *
 * [media] is what the caller decided is visible — the **Room** narrows it under the
 * contact light, and the walk must not widen it again.
 */
internal fun flyoverNight(
    media: List<StoredMedia>,
    friends: List<Friend>,
    rows: List<EventRow>,
    woven: List<WovenSong>,
    log: StoredLog,
): FlyoverNight {
    val markers = flyoverMarkers(rows, woven, log)
    val people = flyoverPeople(media, friends)
    val photos = flyoverPhotos(media, people, markers.size)
    val placed = photos.map { PlacedItem(it.id, it.mine, it.z) }
    return FlyoverNight(
        photos = photos,
        markers = markers,
        notes = flyoverNotes(media, people),
        people = people,
        wallZ = wallZ(placed, markers.size),
        contentLength = contentEnd(placed, markers.size),
    )
}
