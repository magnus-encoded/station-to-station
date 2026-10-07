package io.github.magnusencoded.stationtostation.features.tour

import io.github.magnusencoded.stationtostation.UiState
import io.github.magnusencoded.stationtostation.data.Band
import io.github.magnusencoded.stationtostation.data.StoredMedia
import io.github.magnusencoded.stationtostation.data.TimelineStore
import io.github.magnusencoded.stationtostation.data.localGigSetlist
import io.github.magnusencoded.stationtostation.features.FakeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

class TourSelfieEffectsTest {
    private val directory = Files.createTempDirectory("tour-selfie").toFile()
    private val timelines = TimelineStore(File(directory, "timelines.json"))
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val fake = FakeState()
    private val discarded = mutableListOf<String>()
    private val stored = mutableListOf<String>()
    private var bytes: ByteArray? = byteArrayOf(1, 2, 3)
    private val date = LocalDate.of(2030, 1, 1)
    private val gig = localGigSetlist("demo", "Band", date, "Hall", "")
    private val real = gig.copy(id = "real")
    private val friendKey = "friend-key"
    private val mine = StoredMedia(id = "mine", ref = "content://media/external/1", personal = true)
    private val keepsake = StoredMedia(id = "keepsake", ref = "content://media/external/2")

    @After
    fun cleanup() {
        scope.cancel()
        directory.deleteRecursively()
    }

    private suspend fun drain() {
        while (scope.coroutineContext[Job]!!.children.any()) {
            scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
        }
    }

    private fun selfie() = TourSelfieEffects(
        timelines = timelines,
        friendKey = { friendKey },
        selfieBytes = { bytes },
        storeSelfie = { id, _ -> "content://app.fileprovider/gig_photos/$id.jpg".also { stored += id } },
        discard = { discarded += it.id },
        update = fake.update,
    )

    private fun night(): TourNightArrivesEffects {
        File(directory, "night.json").writeText("""{"gigId":"demo"}""")
        return TourNightArrivesEffects(File(directory, "night.json"), demoGig = { gig }, deleteCalendarEvent = {})
    }

    private suspend fun atS18(effects: TourSelfieEffects = selfie()): TourController {
        timelines.savePlanned(gig)
        timelines.savePlanned(real)
        timelines.markDemo(gig.id)
        fake.current = UiState(
            plannedGigs = listOf(real, gig),
            tour = TourState(step = TourStep.S18, demoWorld = 1),
        )
        val night = night()
        val world = DemoWorldRegistry(listOf(effects, night, DemoWorld { timelines.purgeDemoWorld(); Unit }))
        val store = object : TourStore {
            override suspend fun saveTour(state: TourState) {}
            override suspend fun setOnboarded() {}
            override suspend fun purgeDemoWorld() = world.purge()
            override suspend fun markDemo(gigId: String) = timelines.markDemo(gigId)
        }
        return TourController(fake.state, fake.update, store, { true }, scope, night = night, selfie = effects)
    }

    private suspend fun attach(tour: TourController, gigId: String, item: StoredMedia, band: Band): Boolean {
        val claim = tour.mediaClaim(gigId) ?: return false
        return claim.keep(listOf(item), band) { timelines.saveMedia(gigId, listOf(item)) }
    }

    private fun friendSelfies() = fake.current.mediaBySetlist[gig.id].orEmpty().filter { it.from == friendKey }

    @Test
    fun `a private selfie after returning from Photos moves to the Spotify step and the friend sends theirs`() = runBlocking {
        val tour = atS18()
        tour.returnedFromPhotos(gig.id)
        assertTrue(attach(tour, gig.id, mine, Band.VAULT))
        drain()
        assertEquals(TourStep.S19, fake.current.tour.step)
        assertEquals(1, friendSelfies().size)
        assertFalse(friendSelfies().single().personal)
        assertEquals(1, stored.size)
    }

    @Test
    fun `a shared selfie moves to the Spotify step too`() = runBlocking {
        val tour = atS18()
        tour.returnedFromPhotos(gig.id)
        assertTrue(attach(tour, gig.id, mine, Band.SHARED))
        drain()
        assertEquals(TourStep.S19, fake.current.tour.step)
    }

    @Test
    fun `a selfie before returning from Photos does not move the step`() = runBlocking {
        val tour = atS18()
        assertTrue(attach(tour, gig.id, mine, Band.VAULT))
        drain()
        assertEquals(TourStep.S18, fake.current.tour.step)
        assertTrue(friendSelfies().isEmpty())
    }

    @Test
    fun `an attach on a real Gig is not claimed`() = runBlocking {
        val tour = atS18()
        assertNull(tour.mediaClaim(real.id))
    }

    @Test
    fun `the friend's selfie is delivered once and not at all without the character's selfie`() = runBlocking {
        val effects = selfie()
        atS18(effects)
        effects.deliverFriendSelfie(gig.id, 5L) { true }
        effects.deliverFriendSelfie(gig.id, 5L) { true }
        assertEquals(1, friendSelfies().size)
        assertEquals(5L, friendSelfies().single().capturedAt)
        assertEquals(1, timelines.load().media()[gig.id].orEmpty().size)

        stored.clear()
        timelines.saveMedia(gig.id, emptyList())
        fake.current = fake.current.copy(mediaBySetlist = emptyMap())
        bytes = null
        effects.deliverFriendSelfie(gig.id, 5L) { true }
        assertTrue(stored.isEmpty())
        assertTrue(friendSelfies().isEmpty())
    }

    @Test
    fun `skip removes the demo Gig's media records and copies and leaves a real Gig's media`() = runBlocking {
        val tour = atS18()
        timelines.saveMedia(gig.id, listOf(mine, keepsake))
        timelines.saveMedia(real.id, listOf(StoredMedia(id = "real-photo", ref = "content://media/external/9")))
        fake.current = fake.current.copy(
            mediaBySetlist = mapOf(
                gig.id to listOf(mine, keepsake),
                real.id to listOf(StoredMedia(id = "real-photo", ref = "content://media/external/9")),
            ),
        )
        tour.dispatch(TourEvent.Skipped)
        drain()
        assertEquals(setOf("mine", "keepsake"), discarded.toSet())
        assertNull(fake.current.mediaBySetlist[gig.id])
        assertEquals(listOf("real-photo"), fake.current.mediaBySetlist[real.id].orEmpty().map { it.id })
        assertEquals(listOf("real-photo"), timelines.load().media()[real.id].orEmpty().map { it.id })
        assertTrue(timelines.load().media()[gig.id].orEmpty().isEmpty())
    }

    @Test
    fun `an attach that lands after skip is refused and saves nothing`() = runBlocking {
        val tour = atS18()
        val claim = tour.mediaClaim(gig.id)!!
        tour.dispatch(TourEvent.Skipped)
        drain()
        var saved = false
        assertFalse(claim.keep(listOf(mine), Band.VAULT) { saved = true })
        assertFalse(saved)
        assertTrue(timelines.load().media()[gig.id].orEmpty().isEmpty())
    }

    @Test
    fun `the demo Gig's media is never offered to a Contact and a real Gig's is`() = runBlocking {
        atS18()
        timelines.saveMedia(gig.id, listOf(mine))
        timelines.saveMedia(real.id, listOf(keepsake))
        val offered = TourSelfieEffects.offerable(timelines.load())
        assertTrue(offered.media()[gig.id].orEmpty().isEmpty())
        assertEquals(listOf("keepsake"), offered.media()[real.id].orEmpty().map { it.id })
    }

    @Test
    fun `the selfie asset is read by key from the character definition`() {
        assertEquals("pic", characterString({ """{"selfie":"pic"}""" }, "selfie"))
        assertNull(characterString({ """{"name":"x"}""" }, "selfie"))
        assertNull(characterString({ """{"selfie":""}""" }, "selfie"))
        assertNull(characterString({ throw java.io.FileNotFoundException() }, "selfie"))
    }
}
