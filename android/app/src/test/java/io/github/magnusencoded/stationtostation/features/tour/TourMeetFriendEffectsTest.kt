package io.github.magnusencoded.stationtostation.features.tour

import android.app.Application
import io.github.magnusencoded.stationtostation.UiState
import io.github.magnusencoded.stationtostation.data.*
import io.github.magnusencoded.stationtostation.data.musicbrainz.MusicBrainzClient
import io.github.magnusencoded.stationtostation.data.setlistfm.*
import io.github.magnusencoded.stationtostation.features.FakeState
import io.github.magnusencoded.stationtostation.features.planning.PlanningController
import io.github.magnusencoded.stationtostation.features.tickets.TicketsController
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.time.LocalDate
import java.util.Base64

class TourMeetFriendEffectsTest {
    @Test fun `demo ticket redraw does not depend on the gig UUID`() = runBlocking {
        seed()
        val ticket = TourMeetFriendEffects.ticket(gig.copy(id = "0ea054ab-ce3e-4f10-8ce7-78e3040dc36f"))!!
        assertTrue(ticket.checkedForRedraw().showsEveryAdmission)
    }

    private val directory = Files.createTempDirectory("tour-meet-friend").toFile()
    private val timelineFile = File(directory, "timelines.json")
    private val friendsFile = File(directory, "friends.json")
    private var timelines = TimelineStore(timelineFile)
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val here = Place(59.91, 10.75)
    private lateinit var gig: FmSetlist
    private lateinit var real: FmSetlist
    private val realFriend = Friend("account", "Real", publicKey = "real-key")
    private val fake = FakeState()
    private var fix: Place? = here
    private var lookupCalls = 0

    // These dependencies are never reached by an already-known ticket import.
    private inline fun <reified T> unbuilt(): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").also { it.isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, T::class.java) as T
    }

    @After
    fun cleanup() {
        scope.cancel()
        directory.deleteRecursively()
    }

    private fun contacts(): List<Friend> = if (friendsFile.exists()) decodeFriends(friendsFile.readText()) else listOf(realFriend)

    private fun saveContacts(friends: List<Friend>) {
        friendsFile.writeText(encodeFriends(friends))
        fake.current = fake.current.copy(friends = friends)
    }

    private fun tickets(): TicketsController {
        val setlistFm = SetlistFmClient(
            keySource = { SetlistFmKey("key", shared = false) },
            transport = { _, _ -> error("a demo ticket needs no catalogue search") },
            sleep = {},
        )
        val planning = PlanningController(
            fake.state, fake.update, timelines, setlistFm, unbuilt<MusicBrainzClient>(), scope, { throw it },
        )
        return TicketsController(
            fake.state, fake.update, timelines, setlistFm, TicketOriginals(File(directory, "originals")),
            unbuilt<Application>(), scope, planning,
            adoptSetlist = { _, _, _, _ -> error("a demo night cannot adopt a real night") },
            lookUpLocalGig = { _, _ -> lookupCalls++; true },
        )
    }

    private fun effects(key: (() -> String)? = null): TourMeetFriendEffects {
        return TourMeetFriendEffects(
            friendName = { "Friend from data" },
            contacts = { contacts() },
            demoGig = {
                val cache = timelines.load()
                cache.planned().firstOrNull { cache.gigs[it.id]?.demo == true }
            },
            locate = { fix },
            placeVenue = { placed ->
                timelines.savePlanned(placed)
                fake.current = fake.current.copy(plannedGigs = fake.current.plannedGigs.map { if (it.id == placed.id) placed else it })
            },
            addContact = { friend ->
                if (contacts().any { it.publicKey == friend.publicKey }) false
                else { saveContacts(withFriend(contacts(), friend)); true }
            },
            landNights = { key, nights ->
                val lane = contacts().first { it.publicKey == key }.laneKey
                val (held, _) = timelines.mergeContactNights(lane, nights)
                fake.current = fake.current.copy(showsByFriend = fake.current.showsByFriend + (lane to held.orEmpty()))
            },
            importTicket = { ticket, id -> tickets().importTicket(ticket, id) },
            purgeGigs = { timelines.purgeDemoWorld(it) },
            purgeContacts = { contacts().filterNot { it.demo }.also(::saveContacts) },
            update = fake.update,
            mintKey = key,
        )
    }

    private fun tour(effects: TourMeetFriendEffects = effects()): TourController {
        val store = object : TourStore {
            override suspend fun saveTour(state: TourState) {}
            override suspend fun setOnboarded() {}
            override suspend fun purgeDemoWorld() = effects.purge()
            override suspend fun markDemo(gigId: String) = timelines.markDemo(gigId)
        }
        return TourController(fake.state, fake.update, store, { true }, scope, effects)
    }

    private suspend fun seed() {
        val date = LocalDate.of(2030, 1, 1)
        real = localGigSetlist(timelines.createLocalGig(fmDate(date), "Band", "Room"), "Band", date, "Room", "")
        gig = localGigSetlist(timelines.createLocalGig(fmDate(date), "Band", "Room"), "Band", date, "Room", "")
        fake.current = UiState(plannedGigs = listOf(real, gig))
        saveContacts(listOf(realFriend))
        timelines.savePlanned(real)
        timelines.savePlanned(gig)
        timelines.markDemo(gig.id)
        timelines.mergeContactNights(realFriend.laneKey, listOf(real))
        val attendance = timelines.attachAdmissions(real.id, listOf(StoredAdmission.of(Admission("real-ticket".toByteArray(), QR_SYMBOLOGY))))
        fake.current = fake.current.copy(
            attendanceByGig = mapOf(real.id to attendance),
            showsByFriend = mapOf(realFriend.laneKey to listOf(real)),
        )
    }

    @Test
    fun `exchange lands a Contact and their Line at the fix and imports their ticket onto the demo Gig`() = runBlocking {
        seed()
        val effects = effects()
        var location: Place? = null
        effects.exchange({ true }) { location = it }
        val friend = contacts().single { it.demo }
        assertEquals("Friend from data", friend.name)
        assertEquals("EC", KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(friend.publicKey))).algorithm)
        assertEquals(here, location)
        val placed = timelines.load().planned().single { it.id == gig.id }
        assertEquals(here.latitude, placed.venue?.city?.coords?.lat)
        assertEquals(here.longitude, placed.venue?.city?.coords?.long)
        assertEquals("Room", placed.venue?.name)
        assertTrue(timelines.load().shows[friend.laneKey].orEmpty().none { it.id == gig.id })
        assertTrue(effects.importDemoTicket { true })
        assertEquals(1, timelines.load().attendance()[gig.id]?.admissions?.size)
        assertEquals("tour-demo-ticket", timelines.load().attendance()[gig.id]?.admissions?.single()?.payloadBytes?.toString(Charsets.UTF_8))
        assertEquals("real-ticket", timelines.load().attendance()[real.id]?.admissions?.single()?.payloadBytes?.toString(Charsets.UTF_8))
        assertTrue(fake.current.pendingTickets.isEmpty())
        assertEquals(0, lookupCalls)
    }

    @Test
    fun `no location fix clears coordinates and still makes a Contact`() = runBlocking {
        seed()
        timelines.savePlanned(TourMeetFriendEffects.placed(gig, here))
        fix = null
        effects().exchange({ true }) { assertNull(it) }
        assertNull(timelines.load().planned().single { it.id == gig.id }.venue?.city?.coords)
        assertEquals(1, contacts().count { it.demo })
    }

    @Test
    fun `purge after relaunch removes the Contact their Line and ticket and preserves real records`() = runBlocking {
        seed()
        val before = timelines.load()
        effects().exchange({ true }) {}
        effects().importDemoTicket { true }
        val lane = contacts().single { it.demo }.laneKey
        timelines = TimelineStore(timelineFile)
        effects().purge()
        val after = timelines.load()
        assertEquals(listOf(realFriend), contacts())
        assertFalse(after.shows.containsKey(lane))
        assertFalse(after.gigs.containsKey(gig.id))
        assertFalse(after.attendance().containsKey(gig.id))
        assertEquals(before.gigs[real.id], after.gigs[real.id])
        assertEquals(before.attendance()[real.id], after.attendance()[real.id])
        assertEquals(before.shows[realFriend.laneKey], after.shows[realFriend.laneKey])
        assertEquals(listOf(real), fake.current.plannedGigs)
        assertEquals(setOf(real.id), fake.current.attendanceByGig.keys)
        effects().purge()
        assertEquals(after, timelines.load())
    }

    @Test
    fun `a real Contact with the minted key is never tagged or removed`() = runBlocking {
        seed()
        effects { realFriend.publicKey!! }.exchange({ true }) {}
        assertEquals(listOf(realFriend), contacts())
        effects().purge()
        assertEquals(listOf(realFriend), contacts())
        assertEquals(listOf(real), timelines.load().shows[realFriend.laneKey])
    }

    @Test
    fun `exchange pinch and ticket walk S7 to S10 and skip purges the world`() = runBlocking {
        seed()
        fake.current = fake.current.copy(tour = TourState(step = TourStep.S7, demoWorld = 1))
        val effects = effects()
        val tour = tour(effects)
        assertEquals("Friend from data", tour.exchangeFriendName)
        tour.dispatch(TourEvent.PinchedOut)
        assertEquals(TourStep.S7, fake.current.tour.step)
        tour.exchangeWithFriend()
        assertEquals(TourStep.S8, fake.current.tour.step)
        assertEquals(here, fake.current.tour.venue)
        assertNull(tour.exchangeFriendName)
        tour.dispatch(TourEvent.PinchedOut)
        scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
        assertEquals(TourStep.S10, fake.current.tour.step)
        assertTrue(OnceOnly.ImportDemoTicket in fake.current.tour.completedEffects)
        assertEquals(1, timelines.load().attendance()[gig.id]?.admissions?.size)
        tour.dispatch(TourEvent.Skipped)
        scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
        assertEquals(listOf(realFriend), contacts())
        assertEquals(setOf(real.id), timelines.load().gigs.keys)
    }

    @Test
    fun `leaving S7 while a location fix is pending cannot create a demo Contact`() = runBlocking {
        seed()
        effects().exchange({ false }) { fail("the exchange was abandoned") }
        assertEquals(listOf(realFriend), contacts())
        assertEquals(gig, timelines.load().planned().single { it.id == gig.id })
    }
    @Test
    fun `a repeated exchange after relaunch reuses the Contact key and Line`() = runBlocking {
        seed()
        effects().exchange({ true }) {}
        val friend = contacts().single { it.demo }
        timelines = TimelineStore(timelineFile)
        effects().exchange({ true }) {}
        assertEquals(friend, contacts().single { it.demo })
        assertTrue(timelines.load().shows[friend.laneKey].orEmpty().none { it.id == gig.id })
        assertTrue(effects().importDemoTicket { true })
        assertTrue(effects().importDemoTicket { true })
        assertEquals(1, timelines.load().attendance()[gig.id]?.admissions?.size)
    }

    @Test
    fun `without a demo Gig the exchange and ticket leave real data untouched`() = runBlocking {
        seed()
        effects().purge()
        val before = timelines.load()
        effects().exchange({ true }) { fail("no demo Gig was available") }
        assertFalse(effects().importDemoTicket { true })
        assertEquals(listOf(realFriend), contacts())
        assertEquals(before, timelines.load())
    }

    @Test
    fun `the friend name is empty until the character definition supplies a string`() {
        assertEquals("", friendName { throw java.io.FileNotFoundException() })
        assertEquals("", friendName { "{}" })
        assertEquals("", friendName { "invalid" })
        assertEquals("", friendName { "{\"name\":null}" })
        assertEquals("", friendName { "{\"name\":123}" })
        assertEquals("Friend from data", friendName { "{\"name\":\"Friend from data\"}" })
    }

    @Test
    fun `relaunch at S9 imports the ticket on the restored demo Gig and reaches S10`() = runBlocking {
        seed()
        effects().exchange({ true }) {}
        timelines = TimelineStore(timelineFile)
        fake.current = fake.current.copy(onboarded = true, tour = TourState(step = TourStep.S9, demoWorld = 1))
        tour().launch()
        scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
        assertEquals(TourStep.S10, fake.current.tour.step)
        assertTrue(OnceOnly.ImportDemoTicket in fake.current.tour.completedEffects)
        assertEquals(1, timelines.load().attendance()[gig.id]?.admissions?.size)
    }

}
