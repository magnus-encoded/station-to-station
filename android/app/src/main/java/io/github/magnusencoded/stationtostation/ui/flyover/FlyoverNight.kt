package io.github.magnusencoded.stationtostation.ui.flyover

import io.github.magnusencoded.stationtostation.data.Festivals
import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.data.StoredAttendance
import io.github.magnusencoded.stationtostation.data.StoredLog
import io.github.magnusencoded.stationtostation.data.StoredMedia
import io.github.magnusencoded.stationtostation.data.WovenSong
import io.github.magnusencoded.stationtostation.data.scheduledStart
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.data.visibleToContacts
import io.github.magnusencoded.stationtostation.data.weaveSetlist
import io.github.magnusencoded.stationtostation.ui.EventRow
import io.github.magnusencoded.stationtostation.ui.TimelineNode
import io.github.magnusencoded.stationtostation.ui.eventRows
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The walk, as the **Flyover** needs it (#278, #313): who is on it, what stands along
 * the spine, and what is said at the end of it.
 *
 * Kept apart from the screen for the reason [FlyoverGeometry.kt] is: this is where the
 * decisions live — which flank a photograph takes, which colour a **Contact** keeps,
 * what the wall holds and in what order — and every one of them is assertable without
 * a device. The screen below it does nothing but draw the answer.
 *
 * **One Gig or five, it is the same call.** [flyoverNight] takes the run in running
 * order and a single night is the N=1 case of it, so the rules for a festival cannot
 * drift away from the rules for a Tuesday: there is only one implementation of both.
 */

/**
 * A **Contact** on the walk.
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
    /** The `from` key their media carries, and the key their **Lane** is filed under. */
    val key: String,
    val name: String,
    val colourIndex: Int,
    /**
     * The **Gigs** of the run their records are on, by id.
     *
     * **The weight of their floor line, never its existence** (#313). Presence on the
     * ground means *attended*; whether they also handed over photographs is a second
     * thing said about the same stretch of line, and a night somebody stood through
     * without lifting a camera is not a night they were absent from.
     */
    val gaveAt: Set<String> = emptySet(),
    /**
     * Where their floor line runs, in walk depth. One span per unbroken run of
     * **Gigs** they attended, so somebody who was at every night is one span and
     * somebody who skipped the Saturday is two — a line that leaves shot and comes
     * back, which is one person and reads as one person.
     */
    val spans: List<FlyoverSpan> = emptyList(),
)

/** One stretch of floor line: from [fromZ] to [toZ] along the walk. */
data class FlyoverSpan(val fromZ: Double, val toZ: Double)

/**
 * What a billboard says, before it has been given a depth.
 *
 * The words are the caller's: a **Gig** is billed by its artist and its room, a run by
 * whatever identity the **Collection** has (#166), and neither is the composer's to
 * invent.
 */
data class FlyoverBillboard(
    val title: String,
    val where: String = "",
    val chips: List<String> = emptyList(),
)

/**
 * A **Cover**, placed.
 *
 * Today's single night draws one at a fixed depth because there is only ever one.
 * A run has one per **Gig** where its stretch begins, plus its own billboard ahead of
 * the first, so the **Cover** stops being a property of the surface and becomes an
 * element of the walk like any other.
 */
data class FlyoverCover(
    /** The **Gig** it introduces. Null for the run's own billboard, which introduces
     *  all of them and carries the key to the ground. */
    val gigId: String?,
    val billboard: FlyoverBillboard,
    val z: Double,
)

/**
 * One **Gig** of the run, as the composer needs it.
 *
 * [media] is what the caller decided is visible — the **Room** narrows it under the
 * contact light, and the walk must not widen it again.
 */
internal data class FlyoverGig(
    val id: String,
    /** What its **Cover** says. */
    val billboard: FlyoverBillboard,
    val media: List<StoredMedia> = emptyList(),
    val rows: List<EventRow> = emptyList(),
    val woven: List<WovenSong> = emptyList(),
    val log: StoredLog = StoredLog(),
    /** The night it happened. The first rung of the running order. */
    val date: LocalDate? = null,
    /** The scheduled set time, where the record has one — the **Programme** knows them
     *  for a festival day and setlist.fm's API knows none at all. The second rung. */
    val startsAt: LocalDateTime? = null,
    /**
     * The **Contacts** whose timeline holds this **Gig**, by `setlistfm` key: who was
     * here, whatever they gave. The woven timelines already know this — see
     * [io.github.magnusencoded.stationtostation.ui.weaveTimelines] — and it is the
     * evidence a floor line now stands on.
     */
    val attended: Set<String> = emptySet(),
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
    /**
     * The billboards, nearest the start first: the run's own if it has one, then one
     * per **Gig** where its stretch begins.
     */
    val covers: List<FlyoverCover> = emptyList(),
    /** **One Wall, at the very end.** You stop once, not once per night. */
    val wallZ: Double,
    /** How long the walk is, for [travelGain] — the whole run of it. */
    val contentLength: Double,
)

/**
 * Which **Contacts** have a floor line, and in which order.
 *
 * **The people on the walk are the people who were there** (#313). Presence used to
 * mean *this person's records are on this night*, which made a floor line ambiguous the
 * moment a walk held more than one **Gig**: a line leaving shot could not be told apart
 * from somebody who simply took no photographs. So presence is attendance now — their
 * timeline holds the **Gig** — and what they contributed is [FlyoverPerson.gaveAt], a
 * weight on the line rather than its existence.
 *
 * **Giving media is itself evidence of having been there.** A photograph from that
 * night is a person saying they attended it, so a sender counts as attending whether or
 * not the timeline we hold for them reaches back that far. That keeps the cast a
 * superset of what it used to be — nobody loses a line — and it is the one direction
 * the inference runs in: attendance never invents media.
 *
 * Somebody not on the friends list keeps a colour anyway, after everyone who is, so
 * that two strangers are still two colours. Their name degrades to nothing rather than
 * being invented — the room already answers "someone else" the same way.
 */
internal fun flyoverPeople(gigs: List<FlyoverGig>, friends: List<Friend>): List<FlyoverPerson> {
    val gaveAt = mutableMapOf<String, MutableSet<String>>()
    val cast = LinkedHashSet<String>()
    for (gig in gigs) {
        cast += gig.attended
        for (item in gig.media) {
            val from = item.from ?: continue
            cast += from
            gaveAt.getOrPut(from) { mutableSetOf() } += gig.id
        }
    }
    var unknown = 0
    return cast.map { key ->
        val gave = gaveAt[key].orEmpty().toSet()
        val at = friends.indexOfFirst { it.setlistfm == key }
        if (at >= 0) FlyoverPerson(key, friends[at].name, at, gave)
        else FlyoverPerson(key, "", friends.size + unknown++, gave)
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
 * **The running order, and what it falls back to when the record is thin.**
 *
 * By date, then by scheduled set time where the record has one, then by the order the
 * source returned — the same degradation ladder `billedAs` climbs down for the
 * headliner (#166), for the same reason: each rung is a weaker answer to the same
 * question, and the order is *stated* rather than guessed silently. The last rung is
 * the sort being stable, which is the honest answer to "we do not know" — it is the
 * order the **Collection** already lists, and nothing about it is invented.
 *
 * A **Gig** the record gives no date for sorts last rather than first: an undated night
 * is not evidence of an early one.
 */
internal fun runningOrder(gigs: List<FlyoverGig>): List<FlyoverGig> =
    gigs.sortedWith(
        compareBy<FlyoverGig, LocalDate?>(nullsLast<LocalDate>()) { it.date }
            .thenBy(nullsLast<LocalDateTime>()) { it.startsAt },
    )

/** One **Gig**'s stretch, laid out at its own depths and told where it starts. */
private class Stretch(
    val gig: FlyoverGig,
    val markers: List<FlyoverMarker>,
    val photos: List<FlyoverPhoto>,
    /** Where the stretch begins, along the whole walk. */
    val offset: Double,
    /** How long it is, measured the way a single night is. */
    val length: Double,
)

/** Whether this **Contact** was at [gig] — see [flyoverPeople] for why giving counts. */
private fun FlyoverPerson.wasAt(gig: FlyoverGig): Boolean =
    key in gig.attended || gig.id in gaveAt

/**
 * Where one **Contact**'s floor line runs.
 *
 * Consecutive attended **Gigs** are one span and not two, so a line under a run of
 * nights is unbroken across the dark between them — the gap belongs to the walk, not to
 * an absence. What they gave on each of those nights is [FlyoverPerson.gaveAt] and does
 * not break the line: a person present throughout is one span whether or not every
 * night of it holds a photograph of theirs.
 */
private fun FlyoverPerson.floorSpans(stretches: List<Stretch>): List<FlyoverSpan> {
    val spans = mutableListOf<FlyoverSpan>()
    var from: Double? = null
    var to = 0.0
    for (stretch in stretches) {
        if (wasAt(stretch.gig)) {
            if (from == null) from = stretch.offset
            to = stretch.offset + stretch.length
        } else {
            from?.let { spans.add(FlyoverSpan(it, to)) }
            from = null
        }
    }
    from?.let { spans.add(FlyoverSpan(it, to)) }
    return spans
}

/**
 * Assemble the whole walk, one **Gig** or five.
 *
 * **Depth is allocated per Gig and then concatenated.** Each stretch is laid out
 * exactly as a single night is — spine markers evenly spaced, media placed against them
 * — and the stretches are offset one after another in running order, [StretchGap]
 * apart. A one-**Gig** run therefore comes back at exactly the depths it always did,
 * because the first offset is zero and there is nothing to concatenate.
 *
 * [runBillboard] is the billboard for the run as a whole, and null for a walk that is only one
 * night: a single **Gig** is not a run of anything, and two billboards in front of one
 * night would be the feature changing the thing it was built not to change.
 */
internal fun flyoverNight(
    gigs: List<FlyoverGig>,
    friends: List<Friend>,
    runBillboard: FlyoverBillboard? = null,
): FlyoverNight {
    val ordered = runningOrder(gigs)
    // The cast is a fact about who was there and carries no depths, so it is settled
    // before anything is placed — which is what lets a photograph be given its owner's
    // colour in the same pass that measures the stretch it stands in.
    val cast = flyoverPeople(ordered, friends)

    var offset = 0.0
    val stretches = ordered.map { gig ->
        val markers = flyoverMarkers(gig.rows, gig.woven, gig.log)
        val photos = flyoverPhotos(gig.media, cast, markers.size)
        val length = contentEnd(photos.map { PlacedItem(it.id, it.mine, it.z) }, markers.size)
        val at = offset
        offset = at + length + StretchGap
        Stretch(gig, markers, photos, at, length)
    }

    // Now the stretches are measured, the cast can be told where each of them stood.
    // One value per person from here on, so a photograph's owner and the same person on
    // the ground are never two slightly different values of the same **Contact**.
    val people = cast.map { it.copy(spans = it.floorSpans(stretches)) }
    val byKey = people.associateBy { it.key }

    val last = stretches.lastOrNull()
    val contentLength = if (last == null) 0.0 else last.offset + last.length
    return FlyoverNight(
        photos = stretches.flatMap { s ->
            s.photos.map {
                it.copy(z = it.z + s.offset, person = it.person?.let { p -> byKey[p.key] })
            }
        },
        markers = stretches.flatMap { s -> s.markers.map { it.copy(z = it.z + s.offset) } },
        // The **Wall** holds the whole run's notes, mine first, in the order the ground
        // reads in — the same rule one night has, given one more night to apply to.
        notes = flyoverNotes(ordered.flatMap { it.media }, people),
        people = people,
        covers = buildList {
            runBillboard?.let {
                add(FlyoverCover(gigId = null, billboard = it, z = CoverZ - StretchGap))
            }
            stretches.forEach {
                add(FlyoverCover(it.gig.id, it.gig.billboard, it.offset + CoverZ))
            }
        },
        // One **Wall**, clear of the *run* by the same dark that used to clear one
        // night: [wallZ] said `contentEnd + WallGap` and this says the same of the whole
        // walk.
        wallZ = contentLength + WallGap,
        contentLength = contentLength,
    )
}

/**
 * One **Gig**'s stored parts, turned into what [flyoverNight] needs.
 *
 * **The one place a night is assembled.** Extracted from the screen that used to build
 * it inline for the N=1 case only (#313, PR #316) — a **Collection**'s walk needs the
 * same assembly done once per **Gig** in the run, and a second, slightly different copy
 * of it is exactly the drift the composer seam exists to prevent.
 */
internal fun buildFlyoverGig(
    setlist: FmSetlist,
    /** What the caller decided is visible — narrowed by the contact light where it
     *  applies. The walk must not widen it again. */
    media: List<StoredMedia>,
    log: StoredLog,
    festivals: Festivals,
    /** The **Contacts** whose timeline holds this **Gig**. */
    attended: Set<String>,
    checkedIn: Boolean,
): FlyoverGig {
    val rows = setlist.eventRows()
    return FlyoverGig(
        id = setlist.id,
        billboard = FlyoverBillboard(
            title = setlist.artist?.name ?: "Unknown artist",
            where = listOfNotNull(setlist.venueLine(), setlist.readableDate()).joinToString(" · "),
            chips = buildList {
                val performed = setlist.performed().size
                if (performed > 0) add("$performed songs")
                setlist.tour?.name?.let { add(it) }
                if (checkedIn) add("checked in")
            },
        ),
        media = media,
        rows = rows,
        woven = weaveSetlist(rows.map { (it as? EventRow.SongItem)?.song?.name }, log.songs),
        log = log,
        date = setlist.localDate(),
        startsAt = scheduledStart(setlist, festivals),
        attended = attended,
    )
}

/** `d MMM yyyy`, fixed to English so a run's date range does not depend on the phone's
 *  locale — the same reason [FmSetlist.readableDate] is fixed. */
private val RunDateFmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/**
 * What the run's own billboard says, for a **Collection resolution** (#313).
 *
 * **A Section and a Festival get the same resolution, so the same rule for both:** the
 * title is whatever the node is already labelled — #166 decides that, never this — and
 * the date range is the record's own range (story 29), not an invented span. A single
 * date reads as one line, same as a **Gig**'s own **Cover**; two or more reads as a
 * range, earliest to latest, regardless of running order.
 */
internal fun collectionBillboard(node: TimelineNode.Several): FlyoverBillboard {
    val dates = node.shows.mapNotNull { it.localDate() }.sorted()
    val where = when {
        dates.isEmpty() -> ""
        dates.first() == dates.last() -> dates.first().format(RunDateFmt)
        else -> "${dates.first().format(RunDateFmt)} – ${dates.last().format(RunDateFmt)}"
    }
    return FlyoverBillboard(title = node.label, where = where)
}

/**
 * A **Collection**'s whole run, as [flyoverNight] needs it — one [FlyoverGig] per
 * **Gig** the node holds, in no particular order here: [flyoverNight] applies the
 * running order itself, from each **Gig**'s own [FlyoverGig.date] and [FlyoverGig.startsAt].
 *
 * Media, the log and attendance are read from the same maps the single-**Gig** walk
 * reads from — [buildFlyoverGig] is the one seam that turns them into a [FlyoverGig],
 * whether it is called once or once per night of a festival.
 */
internal fun collectionFlyoverGigs(
    node: TimelineNode.Several,
    mediaBySetlist: Map<String, List<StoredMedia>>,
    logsByGig: Map<String, StoredLog>,
    festivals: Festivals,
    showsByFriend: Map<String, List<FmSetlist>>,
    attendanceByGig: Map<String, StoredAttendance>,
    contactLight: Boolean,
): List<FlyoverGig> = node.shows.map { setlist ->
    val held = mediaBySetlist[setlist.id].orEmpty()
    val media = if (contactLight) visibleToContacts(held) else held
    val attended = showsByFriend.filterValues { shows -> shows.any { it.id == setlist.id } }.keys
    val checkedIn = attendanceByGig[setlist.id]?.provenance == StoredAttendance.Provenance.CHECKED_IN
    buildFlyoverGig(setlist, media, logsByGig[setlist.id] ?: StoredLog(), festivals, attended, checkedIn)
}

/**
 * A **Collection**'s whole run, reduced to its media — the portrait face's whole
 * content (#313 story 5). Combined, and in running order, earliest **Gig** first: a
 * three-day festival reads as one weekend instead of needing a per-night visit to see
 * everything it holds.
 *
 * The same [runningOrder] the walk lays its depths against, so the portrait face and
 * the landscape one can never disagree about which **Gig** comes first.
 */
internal fun collectionMedia(gigs: List<FlyoverGig>): List<StoredMedia> =
    runningOrder(gigs).flatMap { it.media }
