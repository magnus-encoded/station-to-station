package io.github.magnusencoded.stationtostation.features.tour

import io.github.magnusencoded.stationtostation.data.StoredLog
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.features.FakeState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TourLogEffectsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val fake = FakeState()
    private val gig = FmSetlist(id = "demo")

    @Test fun gapWaitsAndFillArrivesOneSongPerTick() = runBlocking {
        lateinit var effects: TourLogEffects
        val waits = mutableListOf<Pair<Long, List<String>>>()
        effects = TourLogEffects(File(temporary.root, "paced.json"), fake.update, pause = { duration ->
            waits += duration to effects.record(gig.id)!!.log.songs
        }) { listOf("Mine", "Second", "Third", "Fourth") }
        effects.write(gig.id, StoredLog().adding("Mine", 10).adding("", 20))
        assertTrue(effects.deliver(gig) { true })
        assertTrue(effects.fill(gig, 30) { true })
        assertEquals(listOf(
            2500L to listOf("Mine", ""),
            500L to listOf("Mine", "Second"),
            500L to listOf("Mine", "Second", "Third"),
        ), waits)
        assertEquals(listOf("Mine", "Second", "Third", "Fourth"), effects.record(gig.id)!!.log.songs)
    }

    @Test fun gapFillPreservesEntryIdentityAndRelaunchPurgeKeepsRealLog() = runBlocking {
        val file = File(temporary.root, "logs.json")
        val effects = TourLogEffects(file, fake.update, pause = {}) { listOf("Mine", "Second", "Third") }
        val original = StoredLog().adding("Mine", 10).adding("", 20)
        fake.update { it.copy(logsByGig = mapOf("real" to StoredLog(songs = listOf("Keep")))) }
        effects.write(gig.id, original)
        assertTrue(effects.deliver(gig) { true })
        val filled = effects.record(gig.id)!!.log
        assertEquals(listOf("Mine", "Second"), filled.songs)
        assertEquals(original.enteredAt, filled.enteredAt)
        assertEquals(original.lineNumbers, filled.lineNumbers)
        assertTrue(effects.deliver(gig) { true })
        assertTrue(effects.fill(gig, 30) { true })
        assertEquals(listOf("Mine", "Second", "Third"), effects.record(gig.id)!!.log.songs)
        val resumed = TourLogEffects(file, fake.update, pause = {}) { emptyList() }
        resumed.restore()
        assertEquals(effects.record(gig.id), resumed.record(gig.id))
        resumed.purge()
        assertNull(fake.current.logsByGig["demo"])
        assertEquals(listOf("Keep"), fake.current.logsByGig.getValue("real").songs)
        assertFalse(file.exists())
    }

    @Test fun pickedArtistSurvivesStoreCheckInAndSuppliesGossip() = runBlocking {
        val store = io.github.magnusencoded.stationtostation.data.TimelineStore(File(temporary.root, "timeline.json"))
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job() + kotlinx.coroutines.Dispatchers.Unconfined)
        val fm = io.github.magnusencoded.stationtostation.data.setlistfm.SetlistFmClient(keySource = { null })
        val planning = io.github.magnusencoded.stationtostation.features.planning.PlanningController(
            fake.state, fake.update, store, fm,
            io.github.magnusencoded.stationtostation.data.musicbrainz.MusicBrainzClient(), scope, { throw it },
            gigAdded = { id -> kotlinx.coroutines.runBlocking { store.markDemo(id) } })
        planning.pickArtist(io.github.magnusencoded.stationtostation.data.musicbrainz.MbArtist("Radiohead", "radiohead-id"))
        planning.addGig("Radiohead", "Venue", "01-01-2030")
        scope.coroutineContext[kotlinx.coroutines.Job]!!.children.toList().forEach { it.join() }
        val added = store.load().planned().single()
        assertEquals("radiohead-id", added.artist?.mbid)
        store.saveAttendance(added.id, io.github.magnusencoded.stationtostation.data.StoredAttendance(
            provenance = io.github.magnusencoded.stationtostation.data.StoredAttendance.Provenance.CHECKED_IN))
        val cache = store.load()
        val ids = cache.gigs.values.filter { it.demo }.map { it.setlistId ?: it.id }.toSet()
        val atS16 = cache.planned().single { it.id in ids }
        val effects = TourLogEffects(File(temporary.root, "logs.json"), fake.update, pause = {}) { gig ->
            if (gig.artist?.mbid == "radiohead-id") listOf("Paranoid Android", "Karma Police", "No Surprises") else emptyList()
        }
        assertEquals("Paranoid Android", effects.pool(atS16).first())
        effects.write(atS16.id, StoredLog().adding("Paranoid Android", 10).adding("", 20))
        assertTrue(effects.deliver(atS16) { true })
        assertEquals(listOf("Paranoid Android", "Karma Police"), effects.record(atS16.id)!!.log.songs)
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
    }

    @Test fun gossipDoesNotRepeatTheSuggestedOpenerWhenTheUserLogsAnotherSong() = runBlocking {
        val effects = TourLogEffects(File(temporary.root, "logs.json"), fake.update, pause = {}) { listOf("Opener", "Second", "Third") }
        effects.write(gig.id, StoredLog().adding("Other", 10).adding("", 20))
        assertTrue(effects.deliver(gig) { true })
        assertEquals(listOf("Other", "Second"), effects.record(gig.id)!!.log.songs)
    }

    @Test fun emptyPoolDoesNotReportAFilledSet() = runBlocking {
        val effects = TourLogEffects(File(temporary.root, "logs.json"), fake.update, pause = {}) { emptyList() }
        effects.write(gig.id, StoredLog().adding("Mine", 10))
        assertFalse(effects.fill(gig, 30) { true })
    }

    @Test fun fillNeverInventsSongsOrOverwritesTheUsersWords() {
        assertEquals(emptyList<String>(), tourSetlistFill(listOf("Mine"), emptyList()))
        assertEquals(listOf("Second"), tourSetlistFill(listOf("Mine"), listOf("mine", "Second", "Second", "")))
        val mine = (1..12).map { "My song $it" }
        assertEquals(emptyList<String>(), tourSetlistFill(mine, listOf("Another")))
    }

    @Test fun lateNetworkResultCannotRecreatePurgedDemo() = runBlocking {
        val file = File(temporary.root, "logs.json")
        lateinit var effects: TourLogEffects
        effects = TourLogEffects(file, fake.update, pause = {}) { effects.purge(); listOf("Second") }
        effects.write(gig.id, StoredLog().adding("", 20))
        assertFalse(effects.deliver(gig) { true })
        assertFalse(file.exists())
    }
}
