package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.data.StoredLog
import io.github.magnusencoded.stationtostation.data.StoredMedia
import io.github.magnusencoded.stationtostation.data.WovenSong
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSong
import io.github.magnusencoded.stationtostation.ui.EventRow
import io.github.magnusencoded.stationtostation.ui.flyover.flyoverMarkers
import io.github.magnusencoded.stationtostation.ui.flyover.flyoverNotes
import io.github.magnusencoded.stationtostation.ui.flyover.flyoverPeople
import io.github.magnusencoded.stationtostation.ui.flyover.flyoverPhotos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the **Flyover** makes of a night (#278): who gets a floor line and in which
 * colour, which flank a photograph takes, what reaches the **Wall**, and what the spine
 * says.
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
        val people = flyoverPeople(listOf(photo("p", from = "Lemmy")), friends)
        assertEquals(1, people.size)
        assertEquals("Lemmy", people[0].name)
        assertEquals(friends.indexOf(lemmy), people[0].colourIndex)
    }

    /** Floor lines read left to right in lane order, and so does the cover's key. */
    @Test
    fun `people come back in lane order`() {
        val people = flyoverPeople(
            listOf(photo("a", from = "Dio"), photo("b", from = "Ozzy"), photo("c", from = "Lemmy")),
            friends,
        )
        assertEquals(listOf("Ozzy", "Lemmy", "Dio"), people.map { it.name })
    }

    /**
     * The people on the night are the people whose records it holds. Somebody who was
     * there and gave nothing has no floor line — a lane under an empty stretch of night
     * says nothing that the walk doesn't already say.
     */
    @Test
    fun `somebody who gave nothing gets no floor line`() {
        assertTrue(flyoverPeople(listOf(photo("mine")), friends).isEmpty())
    }

    /** Two strangers are still two colours, and neither gets a name invented for them. */
    @Test
    fun `a sender nobody knows still gets their own colour`() {
        val people = flyoverPeople(
            listOf(photo("a", from = "stranger"), photo("b", from = "other")),
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
        val people = flyoverPeople(media, friends)
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
        val notes = flyoverNotes(media, flyoverPeople(media, friends))
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
        val notes = flyoverNotes(media, flyoverPeople(media, friends))
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
}
