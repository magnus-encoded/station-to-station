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

    @Test fun gapFillPreservesEntryIdentityAndRelaunchPurgeKeepsRealLog() = runBlocking {
        val file = File(temporary.root, "logs.json")
        val effects = TourLogEffects(file, fake.update) { listOf("Mine", "Second", "Third") }
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
        val resumed = TourLogEffects(file, fake.update) { emptyList() }
        resumed.restore()
        assertEquals(effects.record(gig.id), resumed.record(gig.id))
        resumed.purge()
        assertNull(fake.current.logsByGig["demo"])
        assertEquals(listOf("Keep"), fake.current.logsByGig.getValue("real").songs)
        assertFalse(file.exists())
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
        effects = TourLogEffects(file, fake.update) { effects.purge(); listOf("Second") }
        effects.write(gig.id, StoredLog().adding("", 20))
        assertFalse(effects.deliver(gig) { true })
        assertFalse(file.exists())
    }
}
