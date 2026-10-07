package io.github.magnusencoded.stationtostation.features.tour

import io.github.magnusencoded.stationtostation.UiState
import io.github.magnusencoded.stationtostation.data.DeviceLocation
import io.github.magnusencoded.stationtostation.data.TimelineStore
import io.github.magnusencoded.stationtostation.data.StoredAttendance
import io.github.magnusencoded.stationtostation.data.gossip.GossipStore
import io.github.magnusencoded.stationtostation.data.localGigSetlist
import io.github.magnusencoded.stationtostation.data.photos.PhotoRepository
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.data.setlistfm.SetlistFmClient
import io.github.magnusencoded.stationtostation.features.FakeState
import io.github.magnusencoded.stationtostation.features.gig.GigController
import io.github.magnusencoded.stationtostation.ui.Alcove
import io.github.magnusencoded.stationtostation.ui.GigAsKnown
import io.github.magnusencoded.stationtostation.ui.GigTimeState
import io.github.magnusencoded.stationtostation.ui.atVenue
import io.github.magnusencoded.stationtostation.ui.canCheckInManually
import io.github.magnusencoded.stationtostation.ui.checkInCandidate
import io.github.magnusencoded.stationtostation.ui.cityCoords
import io.github.magnusencoded.stationtostation.ui.gigOffers
import io.github.magnusencoded.stationtostation.ui.gigTimeState
import io.github.magnusencoded.stationtostation.ui.nightWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.URLDecoder
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class TourNightArrivesEffectsTest {
    private val directory = Files.createTempDirectory("tour-night").toFile()
    private val nightFile = File(directory, "night.json")
    private val tourFile = File(directory, "tour.json")
    private val timelines = TimelineStore(File(directory, "timelines.json"))
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val fake = FakeState()
    private val deleted = mutableListOf<String>()
    private val date = LocalDate.of(2030, 1, 1)
    private val realNow = LocalDateTime.of(2026, 10, 7, 12, 0)
    private val here = Place(59.91, 10.75)
    private val gig = TourMeetFriendEffects.placed(localGigSetlist("demo", "Band", date, "Room & Hall", ""), here)
    private val real = gig.copy(id = "real")

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

    private fun night() = TourNightArrivesEffects(
        file = nightFile,
        demoGig = {
            val cache = timelines.load()
            val ids = cache.gigs.values.filter { it.demo }.map { it.setlistId ?: it.id }.toSet()
            cache.planned().firstOrNull { it.id in ids }
        },
        deleteCalendarEvent = { deleted += it },
    )

    private fun tour(night: TourNightArrivesEffects = night()): TourController {
        val world = DemoWorldRegistry(listOf(night, DemoWorld { timelines.purgeDemoWorld(); Unit }))
        val store = object : TourStore {
            override suspend fun saveTour(state: TourState) { tourFile.writeText(Json.encodeToString(state)) }
            override suspend fun setOnboarded() {}
            override suspend fun purgeDemoWorld() = world.purge()
            override suspend fun markDemo(gigId: String) = timelines.markDemo(gigId)
        }
        return TourController(fake.state, fake.update, store, { true }, scope, night = night)
    }

    private suspend fun atS10(): TourController {
        timelines.savePlanned(real)
        timelines.savePlanned(gig)
        timelines.markDemo(gig.id)
        fake.current = UiState(
            plannedGigs = listOf(real, gig),
            tour = TourState(step = TourStep.S9, demoWorld = 1, venue = here),
        )
        return tour().also {
            it.dispatch(TourEvent.TicketImported)
            drain()
        }
    }

    // Check-in never reaches these platform and catalogue dependencies.
    private inline fun <reified T> unbuilt(): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").also { it.isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, T::class.java) as T
    }

    private fun checkIn(tour: TourController) = GigController(
        state = fake.state,
        update = fake.update,
        timelines = timelines,
        photos = unbuilt<PhotoRepository>(),
        where = unbuilt<DeviceLocation>(),
        setlistFm = unbuilt<SetlistFmClient>(),
        gossip = unbuilt<GossipStore>(),
        scope = scope,
        setGigMedia = { _, _ -> },
        syncGossip = {},
        gossipAbout = {},
        gigNow = { id, now -> tour.now(id, now) },
        venuePoint = { tour.venuePoint(it) },
        onCheckedIn = { tour.checkedIn(it) },
        locate = { here.latitude to here.longitude },
    )

    @Test
    fun `the demo Gig moves from approaching through doors and check-in to the show while real Gigs keep their clock`() = runBlocking {
        val tour = atS10()
        assertEquals(TourStep.S10, fake.current.tour.step)
        assertEquals(CoachMark.Calendar, fake.current.coachMark)
        val approaching = date.minusDays(1).atTime(12, 0)
        assertEquals(approaching, tour.now(gig.id, realNow))
        assertEquals(GigTimeState.APPROACHING, gigTimeState(tour.now(gig.id), date))
        assertEquals(Alcove.ADD_TO_CALENDAR, gigOffers(GigAsKnown(window = nightWindow(date)), tour.now(gig.id)).alcove)
        assertFalse(canCheckInManually(gig, tour.now(gig.id)))
        assertFalse(checkIn(tour).checkInDue(realNow))
        assertEquals(realNow, tour.now(real.id, realNow))
        assertEquals(GigTimeState.FUTURE, gigTimeState(tour.now(real.id, realNow), date))
        assertTrue(tour.calendarAdded(real.id, "real-event"))
        assertEquals(TourStep.S10, fake.current.tour.step)
        assertTrue(tour.calendarAdded(gig.id, "demo-event"))
        drain()
        assertEquals(TourStep.S11, fake.current.tour.step)
        assertEquals(CoachMark.Maps, fake.current.coachMark)
        tour.mapsOpened(real.id)
        assertEquals(TourStep.S11, fake.current.tour.step)
        tour.mapsOpened(gig.id)
        drain()
        assertEquals(TourStep.S12, fake.current.tour.step)
        assertEquals(date.atTime(19, 0), tour.now(gig.id))
        assertEquals(GigTimeState.DAY_OF, gigTimeState(tour.now(gig.id), date))
        assertTrue(canCheckInManually(gig, tour.now(gig.id)))
        assertFalse(canCheckInManually(real, tour.now(real.id, realNow)))
        assertEquals(gig, checkInCandidate(listOf(gig), tour.now(gig.id), here.latitude to here.longitude))
        assertTrue(atVenue(here.latitude to here.longitude, gig.cityCoords()!!))
        tour.ticketShown(real.id)
        assertEquals(TourStep.S12, fake.current.tour.step)
        tour.ticketShown(gig.id)
        assertEquals(TourStep.S13, fake.current.tour.step)
        assertEquals(CoachMark.CheckIn, fake.current.coachMark)
        val checkIn = checkIn(tour)
        assertTrue(checkIn.checkInDue(realNow))
        checkIn.offerCheckIn(realNow)
        drain()
        assertEquals(gig, fake.current.checkInOffer)
        checkIn.checkIn(real.id, realNow)
        drain()
        assertEquals(TourStep.S13, fake.current.tour.step)
        assertEquals(realNow.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), timelines.load().attendance()[real.id]?.checkedInAt)
        checkIn.checkIn(gig.id, realNow)
        drain()
        assertEquals(StoredAttendance.Provenance.CHECKED_IN, timelines.load().attendance()[gig.id]?.provenance)
        assertEquals(date.atTime(19, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), timelines.load().attendance()[gig.id]?.checkedInAt)
        assertEquals(TourStep.S14, fake.current.tour.step)
        assertEquals(date.atTime(21, 0), tour.now(gig.id))
    }

    @Test
    fun `Maps opens at the S7 fix with the venue name and real Gigs use their plain query`() = runBlocking {
        val tour = atS10()
        val staleCity = TourMeetFriendEffects.placed(gig, Place(40.71, -74.0))
        val uri = tour.mapsUri(staleCity)!!
        assertTrue(uri.startsWith("geo:59.91,10.75?"))
        assertEquals("59.91,10.75(Room & Hall)", URLDecoder.decode(uri.substringAfter("q="), "UTF-8"))
        assertNull(tour.mapsUri(real))
        assertNotNull(tour.mapsUri(TourMeetFriendEffects.placed(gig, null)))
        fake.current = fake.current.copy(tour = fake.current.tour.copy(venue = null))
        assertNull(tour.mapsUri(gig))
        tour.dispatch(TourEvent.Skipped)
        assertNull(tour.mapsUri(gig))
        assertNull(tour.venuePoint(gig))
        drain()
    }

    @Test
    fun `each Demo clock moment lands on the Gigs own date`() {
        assertEquals(date.minusDays(1).atTime(12, 0), TourNightArrivesEffects.instant(DemoMoment.Approaching, gig.eventDate))
        assertEquals(date.atTime(19, 0), TourNightArrivesEffects.instant(DemoMoment.Doors, gig.eventDate))
        assertEquals(date.atTime(21, 0), TourNightArrivesEffects.instant(DemoMoment.ShowStarted, gig.eventDate))
        val after = TourNightArrivesEffects.instant(DemoMoment.After, gig.eventDate)!!
        assertEquals(date.plusDays(1).atTime(12, 0), after)
        assertEquals(GigTimeState.PAST, gigTimeState(after, date))
        assertNull(TourNightArrivesEffects.instant(DemoMoment.Doors, "not a date"))
    }

    @Test
    fun `relaunch at S11 keeps the clock and calendar event until Skip purges only the demo world`() = runBlocking {
        val first = atS10()
        timelines.markCalendarAdded(real.id, "real-event")
        timelines.markCalendarAdded(gig.id, "demo-event")
        first.calendarAdded(gig.id, "demo-event")
        drain()
        val saved = Json.decodeFromString<TourState>(tourFile.readText())
        fake.current = UiState(plannedGigs = timelines.load().planned(), tour = saved, onboarded = true)
        val night = night()
        val relaunched = tour(night)
        relaunched.launch()
        drain()
        assertEquals(TourStep.S11, fake.current.tour.step)
        assertEquals(CoachMark.Maps, fake.current.coachMark)
        assertEquals(date.minusDays(1).atTime(12, 0), relaunched.now(gig.id))
        assertTrue(deleted.isEmpty())
        relaunched.dispatch(TourEvent.Skipped)
        assertEquals(realNow, relaunched.now(gig.id, realNow))
        drain()
        assertEquals(listOf("demo-event"), deleted)
        assertNull(night.demoNow.value)
        assertEquals("real-event", timelines.load().calendarEvents()[real.id])
        assertFalse(timelines.load().calendarEvents().containsKey(gig.id))
        assertEquals(listOf(real), timelines.load().planned())
        night.purge()
        assertEquals(listOf("demo-event"), deleted)
        assertNull(night().demoNow.value)
    }

    @Test
    fun `relaunch at the door keeps 19 oclock and a direct purge restores the real clock`() = runBlocking {
        val first = atS10()
        first.calendarAdded(gig.id, "demo-event")
        first.mapsOpened(gig.id)
        drain()
        first.ticketShown(gig.id)
        drain()
        fake.current = fake.current.copy(tour = Json.decodeFromString(tourFile.readText()))
        val night = night()
        val relaunched = tour(night)
        relaunched.launch()
        drain()
        assertEquals(TourStep.S13, fake.current.tour.step)
        assertEquals(date.atTime(19, 0), relaunched.now(gig.id))
        night.purge()
        assertEquals(realNow, relaunched.now(gig.id, realNow))
        assertEquals(listOf("demo-event"), deleted)
    }

    @Test
    fun `a calendar insert finishing after Skip is deleted and cannot recreate demo state`() = runBlocking {
        val tour = atS10()
        val world = tour.calendarWorld(gig.id)
        tour.dispatch(TourEvent.Skipped)
        drain()
        assertFalse(tour.calendarAdded(gig.id, "late-demo-event", world))
        assertEquals(listOf("late-demo-event"), deleted)
        assertNull(night().demoNow.value)
        assertNull(fake.current.tour.step)
        assertFalse(timelines.load().gigs.containsKey(gig.id))
    }

    @Test
    fun `real calendar events outside a Tour are never tracked for purge`() = runBlocking {
        val tour = atS10()
        tour.dispatch(TourEvent.Skipped)
        drain()
        assertTrue(tour.calendarAdded(real.id, "real-event"))
        night().purge()
        assertTrue(deleted.isEmpty())
        assertEquals(realNow, tour.now(real.id, realNow))
    }

    @Test
    fun `the after moment makes the demo Gig past and finishing restores the real clock`() = runBlocking {
        val tour = atS10()
        tour.calendarAdded(gig.id, "demo-event")
        drain()
        fake.current = fake.current.copy(tour = fake.current.tour.copy(step = TourStep.S19))
        tour.resume()
        drain()
        assertEquals(date.plusDays(1).atTime(12, 0), tour.now(gig.id))
        assertEquals(GigTimeState.PAST, gigTimeState(tour.now(gig.id), date))
        assertFalse(canCheckInManually(gig, tour.now(gig.id)))
        assertEquals(realNow, tour.now(real.id, realNow))
        tour.dispatch(TourEvent.SpotifyDeclined)
        assertEquals(realNow, tour.now(gig.id, realNow))
        drain()
        assertEquals(listOf("demo-event"), deleted)
        assertNull(night().demoNow.value)
        assertEquals(listOf(real), timelines.load().planned())
        tour.mapsOpened(gig.id)
        tour.ticketShown(gig.id)
        tour.checkedIn(gig.id)
        assertEquals(TourStep.S20, fake.current.tour.step)
    }

    @Test
    fun `without a location fix the demo Gig can still be checked into by hand at doors`() = runBlocking {
        val tour = atS10()
        val withoutFix = TourMeetFriendEffects.placed(gig, null)
        timelines.savePlanned(withoutFix)
        fake.current = fake.current.copy(
            plannedGigs = listOf(real, withoutFix),
            tour = fake.current.tour.copy(venue = null),
        )
        tour.calendarAdded(gig.id, "demo-event")
        tour.mapsOpened(gig.id)
        drain()
        assertNull(tour.mapsUri(withoutFix))
        assertNull(tour.venuePoint(withoutFix))
        assertTrue(canCheckInManually(withoutFix, tour.now(gig.id)))
        tour.ticketShown(gig.id)
        checkIn(tour).checkIn(gig.id, realNow)
        drain()
        assertEquals(TourStep.S14, fake.current.tour.step)
        assertEquals(StoredAttendance.Provenance.CHECKED_IN, timelines.load().attendance()[gig.id]?.provenance)
    }


    @Test
    fun `a real Gig uses the real clock throughout a running Tour`() = runBlocking {
        val tour = atS10()
        assertTrue(fake.current.tour.running)
        assertEquals(date.minusDays(1).atTime(12, 0), tour.now(gig.id, realNow))
        assertEquals(realNow, tour.now(real.id, realNow))
        tour.calendarAdded(gig.id, "demo-event")
        tour.mapsOpened(gig.id)
        drain()
        assertTrue(fake.current.tour.running)
        assertEquals(date.atTime(19, 0), tour.now(gig.id, realNow))
        assertEquals(realNow, tour.now(real.id, realNow))
        assertEquals(GigTimeState.FUTURE, gigTimeState(tour.now(real.id, realNow), date))
        assertFalse(canCheckInManually(real, tour.now(real.id, realNow)))
    }

}
