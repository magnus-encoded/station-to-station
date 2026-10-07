package io.github.magnusencoded.stationtostation.features.tour

import io.github.magnusencoded.stationtostation.UiState
import io.github.magnusencoded.stationtostation.data.Festivals
import io.github.magnusencoded.stationtostation.data.StoredAttendance
import io.github.magnusencoded.stationtostation.data.StoredFestival
import io.github.magnusencoded.stationtostation.data.StoredMedia
import io.github.magnusencoded.stationtostation.data.StoredLog
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSet
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSets
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSong
import io.github.magnusencoded.stationtostation.features.FakeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ContextHintsTest {
    private val now = LocalDate.of(2026, 10, 7)
    private val lastYear = now.minusYears(1)

    private data class Case(
        val hint: ContextHint,
        val expected: Boolean,
        val seen: Set<String> = emptySet(),
        val running: Boolean = false,
    )

    private fun check(cases: List<Case>) {
        for (case in cases) assertEquals(
            case.toString(), case.expected, contextHintDue(case.hint, case.seen, case.running),
        )
    }

    @Test
    fun long_press_is_due_with_at_least_two_editable_rows() = check(listOf(
        Case(ContextHint.LongPress(0), false),
        Case(ContextHint.LongPress(1), false),
        Case(ContextHint.LongPress(2), true),
        Case(ContextHint.LongPress(5), true),
        Case(ContextHint.LongPress(2), false, seen = setOf("longPress")),
        Case(ContextHint.LongPress(2), false, running = true),
    ))

    @Test
    fun pull_down_is_one_hint_per_place() = check(HintPlace.entries.flatMap { place -> listOf(
        Case(ContextHint.PullDown(place), true),
        Case(ContextHint.PullDown(place), false, seen = setOf("pullDown.${place.key}")),
        Case(ContextHint.PullDown(place), false, running = true),
        Case(ContextHint.PullDown(place), true, seen = HintPlace.entries.filter { it != place }
            .map { "pullDown.${it.key}" }.toSet()),
    ) })

    @Test
    fun legend_tap_is_due_only_when_hiding_a_Line() = check(listOf(
        Case(ContextHint.LegendTap(true), true),
        Case(ContextHint.LegendTap(false), false),
        Case(ContextHint.LegendTap(true), false, seen = setOf("legendTap")),
        Case(ContextHint.LegendTap(true), false, running = true),
    ))

    @Test
    fun Flyover_is_due_only_for_an_older_night_with_songs_and_photos() = check(listOf(
        Case(ContextHint.Flyover(lastYear, now, 12, 3), true),
        Case(ContextHint.Flyover(now.minusDays(1), now, 1, 1), true),
        Case(ContextHint.Flyover(lastYear, now, 0, 3), false),
        Case(ContextHint.Flyover(lastYear, now, 12, 0), false),
        Case(ContextHint.Flyover(lastYear, now, 0, 0), false),
        Case(ContextHint.Flyover(now, now, 12, 3), false),
        Case(ContextHint.Flyover(now.plusDays(1), now, 12, 3), false),
        Case(ContextHint.Flyover(null, now, 12, 3), false),
        Case(ContextHint.Flyover(lastYear, now, 12, 3), false, seen = setOf("flyover")),
        Case(ContextHint.Flyover(lastYear, now, 12, 3), false, running = true),
    ))

    @Test
    fun Programme_is_due_with_at_least_one_Festival_on_the_timeline() = check(listOf(
        Case(ContextHint.Programme(0), false),
        Case(ContextHint.Programme(1), true),
        Case(ContextHint.Programme(3), true),
        Case(ContextHint.Programme(1), false, seen = setOf("programme")),
        Case(ContextHint.Programme(1), false, running = true),
    ))

    private class Store : TourStore {
        var saved: TourState? = null
        var saves = 0
        override suspend fun saveTour(state: TourState) { saved = state; saves++ }
        override suspend fun setOnboarded() {}
        override suspend fun purgeDemoWorld() {}
        override suspend fun markDemo(gigId: String) {}
    }

    private val store = Store()
    private val fake = FakeState()
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private fun hints(host: FakeState = fake) = TourHintEffects(host.state, host.update, store, scope)
    private fun tour() = TourController(fake.state, fake.update, store, { true }, scope)
    private fun allHints(): List<ContextHint> = listOf(
        ContextHint.LongPress(2), ContextHint.LegendTap(true),
        ContextHint.Flyover(lastYear, now, 12, 3), ContextHint.Programme(1),
    ) + HintPlace.entries.map { ContextHint.PullDown(it) }

    @Test
    fun each_hint_is_persisted_as_it_shows_and_stays_seen_after_relaunch() {
        val effects = hints()
        for (hint in allHints()) {
            effects.offer(hint)
            assertEquals(hint, fake.current.contextHint)
            assertEquals(fake.current.tour, store.saved)
            assertTrue(hint.key in store.saved!!.seenContextHints)
            effects.dismiss()
            val saves = store.saves
            effects.offer(hint)
            assertNull(fake.current.contextHint)
            val relaunched = FakeState(UiState(tour = store.saved!!))
            hints(relaunched).offer(hint)
            assertNull(relaunched.current.contextHint)
            assertEquals(saves, store.saves)
        }
        assertEquals(allHints().map { it.key }.toSet(), store.saved!!.seenContextHints)
    }

    @Test
    fun only_the_hint_that_shows_is_marked_seen() {
        val effects = hints()
        val room = ContextHint.PullDown(HintPlace.Room)
        val legend = ContextHint.LegendTap(true)
        effects.offer(room)
        effects.offer(legend)
        assertEquals(room, fake.current.contextHint)
        assertEquals(setOf(room.key), store.saved!!.seenContextHints)
        effects.dismiss()
        effects.offer(legend)
        assertEquals(legend, fake.current.contextHint)
        assertEquals(setOf(room.key, legend.key), store.saved!!.seenContextHints)
    }

    @Test
    fun no_hints_show_during_the_Tour_and_all_are_available_after_Skip() {
        val controller = tour()
        controller.launch()
        val before = store.saved
        val effects = hints()
        for (hint in allHints()) effects.offer(hint)
        assertNull(fake.current.contextHint)
        assertTrue(fake.current.tour.seenContextHints.isEmpty())
        assertEquals(before, store.saved)
        controller.dispatch(TourEvent.Skipped)
        assertNull(fake.current.tour.step)
        assertTrue(fake.current.tour.finished)
        for (hint in allHints()) {
            effects.offer(hint)
            assertEquals(hint, fake.current.contextHint)
            effects.dismiss()
        }
    }

    @Test
    fun replay_clears_a_visible_hint_and_preserves_its_seen_key() {
        fake.update { it.copy(tour = TourState(finished = true)) }
        hints().offer(ContextHint.LegendTap(true))
        tour().replay()
        assertTrue(fake.current.tour.running)
        assertNull(fake.current.contextHint)
        assertEquals(setOf("legendTap"), fake.current.tour.seenContextHints)
    }

    private val gig = FmSetlist(id = "gig", eventDate = "06-10-2026")
    private val photo = StoredMedia(id = "photo")
    private val video = StoredMedia(id = "video", kind = StoredMedia.Kind.VIDEO)
    private val note = StoredMedia(id = "note", kind = StoredMedia.Kind.NOTE)

    @Test
    fun the_Room_counts_only_my_movable_photos_and_videos_on_my_own_Gig() {
        data class RoomCase(val mine: Boolean, val light: Boolean, val media: List<StoredMedia>, val rows: Int)
        val cases = listOf(
            RoomCase(true, false, listOf(photo, video), 2),
            RoomCase(true, false, listOf(photo, note), 0),
            RoomCase(true, false, listOf(photo, video.copy(from = "contact")), 0),
            RoomCase(true, false, listOf(photo.copy(from = "contact"), video.copy(from = "contact")), 0),
            RoomCase(false, false, listOf(photo, video), 0),
            RoomCase(true, true, listOf(photo, video), 0),
        )
        for (case in cases) {
            fake.value = UiState(
                tour = TourState(seenContextHints = setOf("pullDown.room")), selectedSetlist = gig,
                setlists = if (case.mine) listOf(gig) else emptyList(), contactLight = case.light,
                mediaBySetlist = mapOf(gig.id to case.media),
            )
            hints().offerInRoom(now)
            assertEquals(case.toString(), if (case.rows >= 2) ContextHint.LongPress(case.rows) else null,
                fake.current.contextHint)
        }
    }

    @Test
    fun the_Room_offers_Flyover_for_an_old_setlist_with_visible_photos() {
        val withSongs = gig.copy(sets = FmSets(listOf(FmSet(song = listOf(FmSong(name = "Song"))))))
        for (media in listOf(emptyList(), listOf(note), listOf(video), listOf(photo))) {
            fake.value = UiState(
                selectedSetlist = withSongs, mediaBySetlist = mapOf(gig.id to media),
                tour = TourState(seenContextHints = setOf("pullDown.room", "longPress")),
            )
            hints().offerInRoom(now)
            assertEquals(media.toString(), if (media == listOf(photo)) ContextHint.Flyover(
                gig.localDate(), now, 1, 1,
            ) else null, fake.current.contextHint)
        }
        fake.update { it.copy(contextHint = null, contactLight = true,
            tour = TourState(seenContextHints = setOf("pullDown.room", "longPress")),
            mediaBySetlist = mapOf(gig.id to listOf(photo.copy(personal = true)))) }
        hints().offerInRoom(now)
        assertNull(fake.current.contextHint)
    }

    @Test
    fun a_planned_Gig_offers_lifting_only_after_checking_in_reveals_its_media() {
        fake.value = UiState(
            selectedSetlist = gig, plannedGigs = listOf(gig),
            attendanceByGig = mapOf(gig.id to StoredAttendance()),
            mediaBySetlist = mapOf(gig.id to listOf(photo, video)),
            tour = TourState(seenContextHints = setOf("pullDown.room")),
        )
        hints().offerInRoom(now)
        assertNull(fake.current.contextHint)
        fake.update { it.copy(attendanceByGig = mapOf(gig.id to StoredAttendance(
            provenance = StoredAttendance.Provenance.CHECKED_IN,
        ))) }
        hints().offerInRoom(now)
        assertEquals(ContextHint.LongPress(2), fake.current.contextHint)
    }

    @Test
    fun the_Room_offers_Flyover_for_logged_songs_but_not_empty_Gaps() {
        for (logged in listOf(emptyList(), listOf(""), listOf("Song"))) {
            fake.value = UiState(
                selectedSetlist = gig, logsByGig = mapOf(gig.id to StoredLog(songs = logged)),
                mediaBySetlist = mapOf(gig.id to listOf(photo)),
                tour = TourState(seenContextHints = setOf("pullDown.room", "longPress")),
            )
            hints().offerInRoom(now)
            assertEquals(logged.toString(), if (logged == listOf("Song")) ContextHint.Flyover(
                gig.localDate(), now, 1, 1,
            ) else null, fake.current.contextHint)
        }
    }

    @Test
    fun a_cached_Festival_is_not_enough_until_it_is_on_my_timeline() {
        val festival = StoredFestival(id = "festival")
        fake.value = UiState(festivals = Festivals(byId = mapOf(festival.id to festival),
            idByShow = mapOf(gig.id to festival.id)))
        hints().offerProgramme()
        assertNull(fake.current.contextHint)
        assertFalse("programme" in fake.current.tour.seenContextHints)
        fake.update { it.copy(plannedGigs = listOf(gig)) }
        hints().offerProgramme()
        assertEquals(ContextHint.Programme(1), fake.current.contextHint)
    }
}
