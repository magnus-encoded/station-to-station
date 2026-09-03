package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.Festivals
import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.data.StoredAttendance
import io.github.magnusencoded.stationtostation.data.StoredLog
import io.github.magnusencoded.stationtostation.data.StoredMedia
import io.github.magnusencoded.stationtostation.data.WovenSong
import io.github.magnusencoded.stationtostation.data.setlistfm.FmArtist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSong
import io.github.magnusencoded.stationtostation.ui.EventRow
import io.github.magnusencoded.stationtostation.ui.TimelineNode
import io.github.magnusencoded.stationtostation.ui.flyover.CoverZ
import io.github.magnusencoded.stationtostation.ui.flyover.FlyoverBillboard
import io.github.magnusencoded.stationtostation.ui.flyover.FlyoverGig
import io.github.magnusencoded.stationtostation.ui.flyover.SongGap
import io.github.magnusencoded.stationtostation.ui.flyover.StretchGap
import io.github.magnusencoded.stationtostation.ui.flyover.WallGap
import io.github.magnusencoded.stationtostation.ui.flyover.buildFlyoverGig
import io.github.magnusencoded.stationtostation.ui.flyover.collectionBillboard
import io.github.magnusencoded.stationtostation.ui.flyover.collectionFlyoverGigs
import io.github.magnusencoded.stationtostation.ui.flyover.flyoverMarkers
import io.github.magnusencoded.stationtostation.ui.flyover.flyoverNight
import io.github.magnusencoded.stationtostation.ui.flyover.flyoverNotes
import io.github.magnusencoded.stationtostation.ui.flyover.flyoverPeople
import io.github.magnusencoded.stationtostation.ui.flyover.flyoverPhotos
import io.github.magnusencoded.stationtostation.ui.flyover.runningOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * What the **Flyover** makes of a walk (#278, #313): who gets a floor line and in which
 * colour, which flank a photograph takes, what reaches the **Wall**, what the spine
 * says — and, when the walk is a run of **Gigs**, where one night stops and the next
 * one starts.
 *
 * Every claim here is about the returned content model and none of them is about the
 * drawing, which is the whole reason the seam is where it is.
 */
class FlyoverNightTest {

    private val ozzy = Friend(setlistfm = "Ozzy", name = "Ozzy")
    private val lemmy = Friend(setlistfm = "Lemmy", name = "Lemmy")
    private val dio = Friend(setlistfm = "Dio", name = "Dio")
    private val friends = listOf(ozzy, lemmy, dio)

    private fun photo(id: String, from: String? = null, at: Long? = 0L, personal: Boolean = false) =
        StoredMedia(
            id = id,
            kind = StoredMedia.Kind.PHOTO,
            ref = "content://x/$id",
            capturedAt = at,
            from = from,
            personal = personal,
        )

    /**
     * One **Gig** of a run. [songs] gives it a spine of that many published songs, which
     * is what its stretch is measured against.
     */
    private fun gig(
        id: String = "gig",
        media: List<StoredMedia> = emptyList(),
        songs: Int = 0,
        date: LocalDate? = null,
        startsAt: LocalDateTime? = null,
        attended: Set<String> = emptySet(),
    ): FlyoverGig {
        val rows = (1..songs).map { song("$id-$it", it) }
        return FlyoverGig(
            id = id,
            billboard = FlyoverBillboard(title = id),
            media = media,
            rows = rows,
            woven = rows.indices.map { WovenSong(published = it, logged = null) },
            date = date,
            startsAt = startsAt,
            attended = attended,
        )
    }

    private fun note(id: String, text: String, from: String? = null, personal: Boolean = false, verdict: String? = null) =
        StoredMedia(
            id = id,
            kind = StoredMedia.Kind.NOTE,
            ref = "",
            from = from,
            personal = personal,
            text = text,
            verdict = verdict,
        )

    // --- Who is on the night ----------------------------------------------

    /**
     * A **Contact** keeps the colour their **Lane** has on the woven timeline, so one
     * person is one colour everywhere — which is the whole claim behind "follow one
     * person through the night".
     */
    @Test
    fun `a contact keeps their lane colour`() {
        val people = flyoverPeople(listOf(gig(media = listOf(photo("p", from = "Lemmy")))), friends)
        assertEquals(1, people.size)
        assertEquals("Lemmy", people[0].name)
        assertEquals(friends.indexOf(lemmy), people[0].colourIndex)
    }

    /** Floor lines read left to right in lane order, and so does the cover's key. */
    @Test
    fun `people come back in lane order`() {
        val people = flyoverPeople(
            listOf(
                gig(
                    media = listOf(
                        photo("a", from = "Dio"),
                        photo("b", from = "Ozzy"),
                        photo("c", from = "Lemmy"),
                    ),
                ),
            ),
            friends,
        )
        assertEquals(listOf("Ozzy", "Lemmy", "Dio"), people.map { it.name })
    }

    /**
     * **Presence is attendance now** (#313), and this is the shipped single-night
     * behaviour it changes. A **Contact** who stood beside you all evening and lifted no
     * camera used to have no floor line at all, which told you they were somewhere else.
     * They have one — and what they gave is the line's weight rather than its existence,
     * so they are still not mistaken for somebody whose photographs are on the night.
     */
    @Test
    fun `somebody who was there and gave nothing still has a line`() {
        val people = flyoverPeople(
            listOf(gig(media = listOf(photo("mine")), attended = setOf("Lemmy"))),
            friends,
        )
        assertEquals(listOf("Lemmy"), people.map { it.name })
        assertTrue("no records of theirs on the night", people[0].gaveAt.isEmpty())
        assertEquals(friends.indexOf(lemmy), people[0].colourIndex)
    }

    /** Somebody who was at neither is on the ground nowhere: absence still reads. */
    @Test
    fun `somebody who was not there has no line`() {
        val people = flyoverPeople(listOf(gig(media = listOf(photo("mine")))), friends)
        assertTrue(people.isEmpty())
    }

    /**
     * A photograph from that night is somebody saying they were at it, so a sender is on
     * the ground whether or not the timeline we hold for them reaches back that far.
     */
    @Test
    fun `giving media is evidence enough of having been there`() {
        val people = flyoverPeople(listOf(gig(media = listOf(photo("p", from = "Ozzy")))), friends)
        assertEquals(listOf("Ozzy"), people.map { it.name })
        assertEquals(setOf("gig"), people[0].gaveAt)
    }

    /** Two strangers are still two colours, and neither gets a name invented for them. */
    @Test
    fun `a sender nobody knows still gets their own colour`() {
        val people = flyoverPeople(
            listOf(
                gig(media = listOf(photo("a", from = "stranger"), photo("b", from = "other"))),
            ),
            friends,
        )
        assertEquals(2, people.size)
        assertEquals(2, people.map { it.colourIndex }.distinct().size)
        assertTrue(people.all { it.name.isEmpty() })
        assertTrue("strangers sort after everyone known", people.all { it.colourIndex >= friends.size })
    }

    // --- The walk ----------------------------------------------------------

    /** Mine on the left, theirs on the right. Side is whose camera. */
    @Test
    fun `the flank is whose camera it came from`() {
        val media = listOf(photo("mine", at = 0L), photo("theirs", from = "Ozzy", at = 10L))
        val people = flyoverPeople(listOf(gig(media = media)), friends)
        val photos = flyoverPhotos(media, people, songCount = 12)
        assertTrue(photos.first { it.id == "mine" }.mine)
        assertTrue(!photos.first { it.id == "theirs" }.mine)
        assertEquals("Ozzy", photos.first { it.id == "theirs" }.person?.name)
        assertNull(photos.first { it.id == "mine" }.person)
    }

    /**
     * A **Note** is media and everything said about media applies to it — but it has no
     * bytes and nothing to look at in passing, so its place is the wall.
     */
    @Test
    fun `notes are not on the spine`() {
        val media = listOf(photo("p"), note("n", "something"))
        val photos = flyoverPhotos(media, emptyList(), songCount = 12)
        assertEquals(listOf("p"), photos.map { it.id })
    }

    /** The vault is on the walk: sorting it to the back would put photographs where
     *  they were not taken. Its disposition is carried elsewhere. */
    @Test
    fun `a held-back photograph still stands where it was taken`() {
        val media = listOf(
            photo("shared", at = 0L),
            photo("vault", at = 5L, personal = true),
            photo("later", at = 10L),
        )
        val photos = flyoverPhotos(media, emptyList(), songCount = 12).sortedBy { it.z }
        assertEquals(listOf("shared", "vault", "later"), photos.map { it.id })
        assertTrue(photos.first { it.id == "vault" }.personal)
    }

    // --- The wall ----------------------------------------------------------

    /** Mine first, and the one that reaches anybody leads. Then everyone else's, in
     *  the same order the ground reads in. */
    @Test
    fun `the wall reads mine first and then theirs in floor order`() {
        val media = listOf(
            note("theirs-dio", "d", from = "Dio"),
            note("mine-vault", "v", personal = true),
            note("theirs-ozzy", "o", from = "Ozzy"),
            note("mine-shared", "s"),
        )
        val notes = flyoverNotes(media, flyoverPeople(listOf(gig(media = media)), friends))
        assertEquals(
            listOf("mine-shared", "mine-vault", "theirs-ozzy", "theirs-dio"),
            notes.map { it.id },
        )
    }

    /** The verdict rides the note it was written on, never the night. */
    @Test
    fun `a verdict stays on its own note`() {
        val media = listOf(
            note("a", "up", verdict = StoredMedia.Verdict.DOUBLE_UP),
            note("b", "down", from = "Ozzy", verdict = StoredMedia.Verdict.DOWN),
        )
        val notes = flyoverNotes(media, flyoverPeople(listOf(gig(media = media)), friends))
        assertEquals(StoredMedia.Verdict.DOUBLE_UP, notes.first { it.id == "a" }.verdict)
        assertEquals(StoredMedia.Verdict.DOWN, notes.first { it.id == "b" }.verdict)
    }

    /** An empty note is not something that was said. */
    @Test
    fun `a note nobody wrote in is not on the wall`() {
        assertTrue(flyoverNotes(listOf(note("blank", "   ")), emptyList()).isEmpty())
    }

    // --- The spine ---------------------------------------------------------

    private fun song(name: String, number: Int?) = EventRow.SongItem(number, FmSong(name = name))

    /** The night is one list: the same weave the room reads down, stood on end. */
    @Test
    fun `a song both records hold is one marker that says so`() {
        val rows = listOf(song("Tupelo", 1), song("Joy", 2))
        val markers = flyoverMarkers(
            rows,
            listOf(WovenSong(published = 0, logged = 0), WovenSong(published = 1, logged = null)),
            StoredLog(songs = listOf("Tupelo")),
        )
        assertEquals(listOf("Tupelo", "Joy"), markers.map { it.label })
        assertTrue(markers[0].agreed)
        assertTrue(!markers[1].agreed)
        assertEquals(listOf(1, 2), markers.map { it.number })
    }

    /**
     * A song only my **Log** caught takes no number: numbering it would push every
     * published song after it out of step with setlist.fm.
     */
    @Test
    fun `a song only my log holds takes no number`() {
        val rows = listOf(song("Tupelo", 1))
        val markers = flyoverMarkers(
            rows,
            listOf(WovenSong(published = 0, logged = null), WovenSong(published = null, logged = 0)),
            StoredLog(songs = listOf("Something unpublished")),
        )
        assertEquals(2, markers.size)
        assertNull(markers[1].number)
        assertTrue(markers[1].loggedOnly)
        assertEquals("Something unpublished", markers[1].label)
    }

    /** A **Gap** is a true fact about the night and keeps its place on the spine. */
    @Test
    fun `a gap is drawn as a gap rather than dropped`() {
        val markers = flyoverMarkers(
            emptyList(),
            listOf(WovenSong(published = null, logged = 0)),
            StoredLog(songs = listOf("")),
        )
        assertEquals(1, markers.size)
        assertEquals("—", markers[0].label)
    }

    @Test
    fun `an encore is a marker without a number`() {
        val rows = listOf(EventRow.Encore, song("The Mercy Seat", 1))
        val markers = flyoverMarkers(
            rows,
            listOf(WovenSong(published = 0, logged = null), WovenSong(published = 1, logged = null)),
            StoredLog(),
        )
        assertTrue(markers[0].encore)
        assertNull(markers[0].number)
        assertEquals("encore", markers[0].label)
    }

    /** Markers are evenly spaced, and nothing about a photograph may move one. */
    @Test
    fun `markers are evenly spaced`() {
        val rows = (1..4).map { song("s$it", it) }
        val markers = flyoverMarkers(
            rows,
            rows.indices.map { WovenSong(published = it, logged = null) },
            StoredLog(),
        )
        val gaps = markers.map { it.z }.zipWithNext { a, b -> b - a }
        assertTrue("every gap is the same", gaps.distinct().size == 1)
    }

    // --- A run of Gigs (#313) ----------------------------------------------

    private val friday = LocalDate.of(2025, 8, 8)
    private val saturday = LocalDate.of(2025, 8, 9)
    private val sunday = LocalDate.of(2025, 8, 10)

    /** Two songs each, so every stretch is [SongGap] long and the arithmetic is
     *  readable: markers at 0 and 260, content ending at 260. */
    private fun weekend(
        friAttended: Set<String> = emptySet(),
        satAttended: Set<String> = emptySet(),
        sunAttended: Set<String> = emptySet(),
        friMedia: List<StoredMedia> = emptyList(),
        satMedia: List<StoredMedia> = emptyList(),
        sunMedia: List<StoredMedia> = emptyList(),
    ) = listOf(
        gig("fri", songs = 2, date = friday, attended = friAttended, media = friMedia),
        gig("sat", songs = 2, date = saturday, attended = satAttended, media = satMedia),
        gig("sun", songs = 2, date = sunday, attended = sunAttended, media = sunMedia),
    )

    /** Where each stretch of [weekend] begins. */
    private val friAt = 0.0
    private val satAt = SongGap + StretchGap
    private val sunAt = satAt + SongGap + StretchGap

    /**
     * **A one-Gig run is the night it always was.** The feature is built for festivals
     * and is not allowed to move a single Tuesday by one unit.
     */
    @Test
    fun `a one-gig run is exactly the single night it always was`() {
        val only = gig(
            songs = 4,
            media = listOf(photo("mine", at = 0L), photo("theirs", from = "Ozzy", at = 90L)),
        )
        val night = flyoverNight(listOf(only), friends)

        assertEquals(listOf(0.0, 260.0, 520.0, 780.0), night.markers.map { it.z })
        assertEquals(
            flyoverPhotos(only.media, night.people, 4).map { it.id to it.z },
            night.photos.map { it.id to it.z },
        )
        assertEquals(1, night.covers.size)
        assertEquals("gig", night.covers[0].gigId)
        assertEquals(CoverZ, night.covers[0].z, 0.0)
        assertEquals(night.contentLength + WallGap, night.wallZ, 0.0)
    }

    /**
     * Earliest first, and each stretch laid out as its own night before being offset —
     * so the spine inside a stretch is that **Gig**'s songs and nothing else's.
     */
    @Test
    fun `the stretches stand one after another in running order`() {
        val night = flyoverNight(weekend().reversed(), friends)

        assertEquals(listOf("fri", "sat", "sun"), night.covers.mapNotNull { it.gigId })
        assertEquals(
            listOf(
                friAt, friAt + SongGap,
                satAt, satAt + SongGap,
                sunAt, sunAt + SongGap,
            ),
            night.markers.map { it.z },
        )
    }

    /**
     * **The gap is fixed and belongs to the Cover that introduces the next stretch.**
     * Three days apart or three hours, it is the same corridor: the walk is a
     * concatenation and the model makes no claim about how long the real gap was.
     */
    @Test
    fun `the gap between two stretches says nothing about the time between them`() {
        val close = flyoverNight(
            listOf(
                gig("a", songs = 2, date = friday),
                gig("b", songs = 2, date = friday.plusDays(1)),
            ),
            friends,
        )
        val distant = flyoverNight(
            listOf(
                gig("a", songs = 2, date = friday),
                gig("b", songs = 2, date = friday.plusYears(3)),
            ),
            friends,
        )
        assertEquals(close.contentLength, distant.contentLength, 0.0)
        assertEquals(close.covers.map { it.z }, distant.covers.map { it.z })
    }

    /** One **Cover** per **Gig**, at the depth its stretch begins. */
    @Test
    fun `each gig has exactly one cover where its own stretch starts`() {
        val night = flyoverNight(weekend(), friends)

        assertEquals(listOf("fri", "sat", "sun"), night.covers.map { it.gigId })
        assertEquals(
            listOf(friAt + CoverZ, satAt + CoverZ, sunAt + CoverZ),
            night.covers.map { it.z },
        )
    }

    /** Two billboards at the start: what you are walking, then where it begins. */
    @Test
    fun `the run's own billboard stands ahead of the first gig's cover`() {
        val night = flyoverNight(
            weekend(),
            friends,
            runBillboard = FlyoverBillboard("Øyafestivalen 2025"),
        )

        assertNull("the run's billboard belongs to no one Gig", night.covers[0].gigId)
        assertEquals("Øyafestivalen 2025", night.covers[0].billboard.title)
        assertEquals(CoverZ - StretchGap, night.covers[0].z, 0.0)
        assertTrue("and it is met first", night.covers[0].z < night.covers[1].z)
        assertEquals("fri", night.covers[1].gigId)
    }

    /** **You stop once, at the end** — not once per night. */
    @Test
    fun `there is one wall, past the whole run`() {
        val night = flyoverNight(weekend(), friends)

        assertEquals(sunAt + SongGap, night.contentLength, 0.0)
        assertEquals(night.contentLength + WallGap, night.wallZ, 0.0)
        assertTrue(night.markers.all { it.z < night.wallZ })
        assertTrue(night.covers.all { it.z < night.wallZ })
    }

    /** A floor line runs under the nights they were at, and leaves shot across the
     *  ones they were not. */
    @Test
    fun `a floor line covers the gigs they attended and no others`() {
        val night = flyoverNight(
            weekend(
                friAttended = setOf("Ozzy", "Lemmy"),
                satAttended = setOf("Ozzy"),
                sunAttended = setOf("Ozzy", "Lemmy"),
            ),
            friends,
        )
        val lemmyLine = night.people.first { it.key == "Lemmy" }
        assertEquals(
            listOf(friAt to friAt + SongGap, sunAt to sunAt + SongGap),
            lemmyLine.spans.map { it.fromZ to it.toZ },
        )
    }

    /** One person, whatever the ground does in between: a line that leaves and returns
     *  is two spans of the same **Contact**, never two contacts. */
    @Test
    fun `a line that leaves and returns is still one person`() {
        val night = flyoverNight(
            weekend(friAttended = setOf("Lemmy"), sunAttended = setOf("Lemmy")),
            friends,
        )
        assertEquals(1, night.people.count { it.key == "Lemmy" })
        assertEquals(2, night.people.first { it.key == "Lemmy" }.spans.size)
    }

    /**
     * Present throughout is one unbroken span — including across the dark between two
     * stretches, which belongs to the walk and is not an absence.
     */
    @Test
    fun `a contact present on every night has one unbroken span`() {
        val night = flyoverNight(
            weekend(
                friAttended = setOf("Ozzy"),
                satAttended = setOf("Ozzy"),
                sunAttended = setOf("Ozzy"),
            ),
            friends,
        )
        val line = night.people.first { it.key == "Ozzy" }
        assertEquals(1, line.spans.size)
        assertEquals(friAt, line.spans[0].fromZ, 0.0)
        assertEquals(sunAt + SongGap, line.spans[0].toZ, 0.0)
    }

    /**
     * And a night they gave nothing on does not break it (story 17): what they handed
     * over is the line's weight, so the ground never says somebody was absent when they
     * were standing beside you.
     */
    @Test
    fun `a night they gave nothing on does not break their line`() {
        val night = flyoverNight(
            weekend(
                friAttended = setOf("Dio"),
                satAttended = setOf("Dio"),
                sunAttended = setOf("Dio"),
                satMedia = listOf(photo("theirs", from = "Dio")),
            ),
            friends,
        )
        val line = night.people.first { it.key == "Dio" }
        assertEquals(1, line.spans.size)
        assertEquals(setOf("sat"), line.gaveAt)
    }

    // --- The running order, and what it degrades to ------------------------

    /** The first rung: the date. */
    @Test
    fun `the running order is the date`() {
        val order = runningOrder(weekend().reversed())
        assertEquals(listOf("fri", "sat", "sun"), order.map { it.id })
    }

    /** The second: a scheduled set time, where the record has one. */
    @Test
    fun `set times decide the order inside one date`() {
        val late = gig("headliner", date = saturday, startsAt = saturday.atTime(22, 0))
        val early = gig("support", date = saturday, startsAt = saturday.atTime(19, 30))
        assertEquals(listOf("support", "headliner"), runningOrder(listOf(late, early)).map { it.id })
    }

    /**
     * The third, and the weakest: the order the source returned. An evening whose
     * **Gigs** have no known running order is still walkable, in an order that is
     * stated rather than guessed.
     */
    @Test
    fun `with no set times the source's own order stands`() {
        val a = gig("first", date = saturday)
        val b = gig("second", date = saturday)
        assertEquals(listOf("first", "second"), runningOrder(listOf(a, b)).map { it.id })
        assertEquals(listOf("second", "first"), runningOrder(listOf(b, a)).map { it.id })
    }

    /** An undated night is not evidence of an early one. */
    @Test
    fun `a gig the record gives no date for sorts last`() {
        val dated = gig("dated", date = saturday)
        val undated = gig("undated")
        assertEquals(listOf("dated", "undated"), runningOrder(listOf(undated, dated)).map { it.id })
    }

    // --- What widening the view may not widen ------------------------------

    /** **Personal** is held back on a run exactly as it is on a night. */
    @Test
    fun `a held-back photograph is held back the same at N equals one and above`() {
        val vault = photo("vault", personal = true)
        val one = flyoverNight(listOf(gig("a", songs = 2, media = listOf(vault))), friends)
        val many = flyoverNight(
            listOf(gig("a", songs = 2, media = listOf(vault)), gig("b", songs = 2)),
            friends,
        )
        assertTrue(one.photos.single().personal)
        assertTrue(many.photos.single { it.id == "vault" }.personal)
    }

    /**
     * The contact light narrows in the **Room** and the walk must not widen it again.
     * The composer is handed what the caller decided is visible and has no second
     * source to read from, at one **Gig** or five.
     */
    @Test
    fun `the run holds only the media it was given`() {
        val night = flyoverNight(
            listOf(
                gig("a", songs = 2, media = listOf(photo("shown"))),
                gig("b", songs = 2, media = emptyList()),
            ),
            friends,
        )
        assertEquals(listOf("shown"), night.photos.map { it.id })
    }

    /** The **Wall** holds the whole run's notes, mine first, in floor-line order. */
    @Test
    fun `the run's notes all reach the one wall`() {
        val night = flyoverNight(
            listOf(
                gig("fri", songs = 2, date = friday, media = listOf(note("n1", "friday"))),
                gig(
                    "sat",
                    songs = 2,
                    date = saturday,
                    media = listOf(note("n2", "saturday", from = "Ozzy")),
                ),
            ),
            friends,
        )
        assertEquals(listOf("n1", "n2"), night.notes.map { it.id })
    }

    // --- Assembling a Collection's run (#313 slice 2: the run billboard) ---

    private fun setlist(id: String, artist: String, date: LocalDate) = FmSetlist(
        id = id,
        artist = FmArtist(name = artist),
        eventDate = date.format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy")),
    )

    /**
     * **A Section and a Festival are billed by the same rule.** #166 decides what the
     * node is called — `billedAs`'s headliner-then-supports for a **Section**, the
     * identity's own name for a **Festival** — and this only ever reads `label`, so the
     * two get the same billboard by construction rather than by two call sites agreeing.
     */
    @Test
    fun `the run's billboard is titled from whatever the node is already labelled`() {
        val section = TimelineNode.Section(
            listOf(setlist("h", "Devin Townsend", friday), setlist("s", "Haken", friday)),
        )
        assertEquals(section.label, collectionBillboard(section).title)
    }

    /** One date reads as one line, the same as a single Gig's own Cover. */
    @Test
    fun `a run on one date has a single-date billboard`() {
        val section = TimelineNode.Section(
            listOf(setlist("h", "Devin Townsend", friday), setlist("s", "Haken", friday)),
        )
        assertEquals("8 Aug 2025", collectionBillboard(section).where)
    }

    /** **The run's date range is the record's own range** (story 29) — earliest to
     *  latest, whatever order the shows arrived in. */
    @Test
    fun `a run across dates has a range, earliest to latest`() {
        val section = TimelineNode.Section(
            listOf(setlist("sun", "Headliner", sunday), setlist("fri", "Support", friday)),
        )
        assertEquals("8 Aug 2025 – 10 Aug 2025", collectionBillboard(section).where)
    }

    /**
     * **The one seam a night is assembled through.** [buildFlyoverGig] is what the
     * single-**Gig** walk now calls too (PR #316 left this owed) — asserting it here is
     * what keeps the Collection path and the single-night path from drifting apart.
     */
    @Test
    fun `buildFlyoverGig carries the gig's own date, media and attendance through`() {
        val gig = buildFlyoverGig(
            setlist = setlist("fri", "Devin Townsend", friday),
            media = listOf(photo("p")),
            log = StoredLog(),
            festivals = Festivals(),
            attended = setOf("Lemmy"),
            checkedIn = false,
        )
        assertEquals("fri", gig.id)
        assertEquals("Devin Townsend", gig.billboard.title)
        assertEquals(friday, gig.date)
        assertEquals(setOf("Lemmy"), gig.attended)
        assertEquals(listOf("p"), gig.media.map { it.id })
    }

    /**
     * **The Collection assembly is buildFlyoverGig, called once per Gig in the run.**
     * Handing its output straight to [flyoverNight] is what the walk's entry point
     * still owes (#313) — this is the part of it that is pure and assertable today.
     */
    @Test
    fun `a Collection's gigs are built from the same maps the single night reads`() {
        val section = TimelineNode.Section(
            listOf(setlist("fri", "Support", friday), setlist("sat", "Headliner", saturday)),
        )
        val gigs = collectionFlyoverGigs(
            node = section,
            mediaBySetlist = mapOf("fri" to listOf(photo("only-friday"))),
            logsByGig = emptyMap(),
            festivals = Festivals(),
            showsByFriend = mapOf("Ozzy" to listOf(setlist("sat", "Headliner", saturday))),
            attendanceByGig = emptyMap(),
            contactLight = false,
        )
        assertEquals(listOf("fri", "sat"), gigs.map { it.id })
        assertEquals(listOf("only-friday"), gigs.first { it.id == "fri" }.media.map { it.id })
        assertTrue(gigs.first { it.id == "sat" }.media.isEmpty())
        assertEquals(setOf("Ozzy"), gigs.first { it.id == "sat" }.attended)
        assertTrue(gigs.first { it.id == "fri" }.attended.isEmpty())

        // The composer takes it exactly as it takes the single-night case: no second
        // implementation of the run's rules.
        val night = flyoverNight(gigs, friends, runBillboard = collectionBillboard(section))
        assertEquals(listOf("fri", "sat"), night.covers.mapNotNull { it.gigId })
        assertNull("the run's own billboard belongs to no one Gig", night.covers[0].gigId)
    }

    /** The contact light narrows a Collection's media exactly as it narrows one night's
     *  — the walk must not widen what the light held back. */
    @Test
    fun `contact light narrowing applies per gig in a Collection the same as at N equals one`() {
        val section = TimelineNode.Section(listOf(setlist("fri", "Support", friday)))
        val personalMedia = listOf(photo("shared"), photo("vault", personal = true))
        val lit = collectionFlyoverGigs(
            node = section,
            mediaBySetlist = mapOf("fri" to personalMedia),
            logsByGig = emptyMap(),
            festivals = Festivals(),
            showsByFriend = emptyMap(),
            attendanceByGig = emptyMap(),
            contactLight = true,
        )
        assertEquals(listOf("shared"), lit.first().media.map { it.id })
    }
}
