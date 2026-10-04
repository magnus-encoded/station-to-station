package io.github.magnusencoded.stationtostation.features.gossip

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.magnusencoded.stationtostation.data.TimelineStore
import io.github.magnusencoded.stationtostation.data.gossip.GossipStore
import io.github.magnusencoded.stationtostation.features.StateFake
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class GossipControllerTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Rig(
        val fake: StateFake,
        val store: GossipStore,
        val controller: GossipController,
        val radioCalls: MutableList<Instant?>,
        val scope: CoroutineScope,
    )

    private fun rig(run: suspend (Rig) -> Unit) = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.IO + job)
        try {
            val store = GossipStore(PreferenceDataStoreFactory.create(scope = scope) {
                File(temporary.root, "gossip.preferences_pb")
            })
            val fake = StateFake()
            val radioCalls = mutableListOf<Instant?>()
            val controller = GossipController(
                state = fake.state, update = fake.update, gossip = store,
                timelines = TimelineStore(File(temporary.root, "timelines.json")),
                radio = { radioCalls += it }, scope = scope,
            )
            run(Rig(fake, store, controller, radioCalls, scope))
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test fun syncOnAnEmptyTimelineTellsTheRadioThereIsNoReasonToRun() = rig { r ->
        r.controller.sync()
        assertEquals(listOf<Instant?>(null), r.radioCalls)
        assertTrue(r.fake.current.gossipEligibleUntil.isEmpty())
        assertNull(r.fake.current.gossipActiveGig)
        assertTrue(r.fake.current.gossipStoppedGigs.isEmpty())
    }

    @Test fun standingAtAGigThatIsNotOnTheTimelineChangesNothing() = rig { r ->
        r.store.stopParticipation(1000)
        r.controller.selectGig("unknown")
        r.scope.coroutineContext[kotlinx.coroutines.Job]!!.children.forEach { it.join() }
        assertNull(r.store.selectedGigId())
        assertEquals(1000L, r.store.stoppedAt())
        assertTrue(r.radioCalls.isEmpty())
    }

    @Test fun gossipAboutAGigThatIsNotOnTheTimelineAuthorsNothing() = rig { r ->
        r.controller.gossipAbout("unknown")
        assertTrue(r.store.publicStates.first().facts.isEmpty())
    }
}
