package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.StoredAttendance
import io.github.magnusencoded.stationtostation.data.StoredLog
import io.github.magnusencoded.stationtostation.data.TimelineStore
import io.github.magnusencoded.stationtostation.data.gossip.GossipEnvelope
import io.github.magnusencoded.stationtostation.data.gossip.PublicGossipState
import io.github.magnusencoded.stationtostation.data.gossip.gossipActiveUntil
import io.github.magnusencoded.stationtostation.data.gossip.gossipExpiry
import io.github.magnusencoded.stationtostation.data.gossip.gossipLogChanges
import io.github.magnusencoded.stationtostation.data.gossip.gossipParticipationUntil
import io.github.magnusencoded.stationtostation.data.gossip.GossipService
import io.github.magnusencoded.stationtostation.data.gossip.GossipStore
import io.github.magnusencoded.stationtostation.data.gossip.gossipRelayShouldRun
import io.github.magnusencoded.stationtostation.data.gossip.gossipStop
import io.github.magnusencoded.stationtostation.data.gossip.weaveGossip
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.LocalDate
import java.util.Base64
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import io.github.magnusencoded.stationtostation.data.gossip.gossipParticipationEnds
import io.github.magnusencoded.stationtostation.data.gossip.gossipActiveGigId
import io.github.magnusencoded.stationtostation.data.gossip.gossipGigAliases

/** #448 participation lifecycle, at the store and model seams that need no device. */
class GossipLifecycleTest {

    private val tonight: LocalDate = LocalDate.of(2026, 9, 4)
    private val end = gossipExpiry(tonight)
    private val key = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

    @get:Rule val temporary = TemporaryFolder()
    private fun store() = TimelineStore(File(temporary.newFolder(), "timeline.json"))

    private suspend fun TimelineStore.gig(date: LocalDate, checkedInAt: Long?, log: StoredLog? = null): String {
        val id = createLocalGig(date.format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy")), "The Warning", "Rockefeller")
        saveAttendance(id, StoredAttendance(provenance = StoredAttendance.Provenance.CHECKED_IN, checkedInAt = checkedInAt))
        if (log != null) saveLog(id, log)
        return id
    }

    private fun envelope(expiresAt: Long, at: Long = 1000): GossipEnvelope =
        GossipEnvelope(gigId = "gig", scope = "scope", author = Base64.getEncoder().encodeToString(key.public.encoded),
            createdAt = at, expiresAt = expiresAt, kind = "log", line = 0, text = "Qué Más Quieres").signed { bytes ->
            Signature.getInstance("SHA256withECDSA").run { initSign(key.private); update(bytes); sign() }
        }!!

    @Test
    fun `held envelopes without an active Gig do not keep the radio on`() = runBlocking {
        val done = end.minusSeconds(4 * 3600).toEpochMilli()
        val store = store()
        store.gig(tonight, done - 3600_000, StoredLog().completing(true, done))
        val now = end.minusSeconds(3600)
        val state = PublicGossipState()
        assertTrue(state.receive(envelope(end.toEpochMilli(), now.toEpochMilli()), "", now.toEpochMilli(), local = true))
        assertTrue(state.held.isNotEmpty())
        assertFalse(gossipRelayShouldRun(gossipActiveUntil(store), now))
    }

    @Test
    fun `no attendance at all means no participation even with a gig on the timeline`() = runBlocking {
        val store = store()
        store.gig(tonight, checkedInAt = null)
        assertNull(gossipActiveUntil(store))
    }

    @Test
    fun `a second Gig still active keeps the radio on after the first one's grace ends`() = runBlocking {
        val store = store()
        val done = end.minusSeconds(3 * 3600).toEpochMilli()
        store.gig(tonight, done - 3600_000, StoredLog().completing(true, done))
        store.gig(tonight, done + 600_000)
        val now = end.minusSeconds(3600)
        assertEquals(end, gossipActiveUntil(store))
        assertTrue(gossipRelayShouldRun(gossipActiveUntil(store), now))
    }

    @Test
    fun `ended Gig stops transmitting at its deadline while another keeps radio on`() = runBlocking {
        val store = store()
        val done = end.minusSeconds(3 * 3600).toEpochMilli()
        val cutoff = done + 1_800_000
        val ended = store.gig(tonight, done - 3600_000, StoredLog().completing(true, done))
        val active = store.gig(tonight, done + 600_000)
        val state = PublicGossipState()
        // Arrive just before grace ends: carry and envelope expiry are both still live.
        fun fact(gigId: String) = envelope(end.toEpochMilli(), cutoff - 1000).copy(gigId = gigId, signature = "").signed { bytes ->
            Signature.getInstance("SHA256withECDSA").run { initSign(key.private); update(bytes); sign() }
        }!!
        val endedFact = fact(ended)
        val activeFact = fact(active)
        assertTrue(state.receive(endedFact, "supplier", cutoff - 1000))
        assertTrue(state.receive(activeFact, "supplier", cutoff - 1000))
        val deadlines = gossipParticipationEnds(store)
        assertEquals(2, state.offer("peer", cutoff - 1, deadlines).size)
        assertEquals(listOf(activeFact.id), state.offer("peer", cutoff, deadlines).map { it.id })
        assertTrue(gossipRelayShouldRun(gossipActiveUntil(store), java.time.Instant.ofEpochMilli(cutoff)))
        assertEquals(2, state.held.size)
        assertEquals(2, state.facts.size)
        // Reopening restores participation without rewriting or reauthoring either fact.
        store.saveLog(ended, StoredLog().completing(false))
        assertEquals(2, state.offer("peer", cutoff, gossipParticipationEnds(store)).size)
    }

    @Test
    fun `manual stop ends participation read from the store`() = runBlocking {
        val store = store()
        val checked = end.minusSeconds(5 * 3600).toEpochMilli()
        store.gig(tonight, checked)
        assertEquals(end, gossipActiveUntil(store))
        assertNull(gossipActiveUntil(store, stoppedAt = checked + 1))
    }

    @Test
    fun `participation holds at 05 59 59 and ends at the night boundary`() {
        val until = gossipParticipationUntil(end.minusSeconds(3600).toEpochMilli(), false, null, end)
        assertTrue(gossipRelayShouldRun(until, end.minusSeconds(1)))
        assertFalse(gossipRelayShouldRun(until, end))
    }

    @Test
    fun `an encore added during grace changes neither completion nor the timer`() {
        val done = end.minusSeconds(3 * 3600).toEpochMilli()
        val completed = StoredLog().adding("Choke", done - 60_000).completing(true, done)
        val encore = completed.adding("Evolve", done + 600_000)
        assertTrue(encore.closed)
        assertEquals(done, encore.completedAt)
        assertEquals(
            gossipParticipationUntil(done - 3600_000, completed.closed, completed.completedAt, end),
            gossipParticipationUntil(done - 3600_000, encore.closed, encore.completedAt, end),
        )
        assertEquals(mapOf(1 to "Evolve"), gossipLogChanges(completed, encore))
    }

    @Test
    fun `Done inside participation is eligible to publish, an edit after the cutoff is not`() {
        val done = end.minusSeconds(3 * 3600).toEpochMilli()
        val open = StoredLog().adding("Choke", done - 60_000)
        val closed = open.completing(true, done)
        val until = gossipParticipationUntil(done - 3600_000, closed.closed, closed.completedAt, end)
        assertTrue(gossipRelayShouldRun(until, java.time.Instant.ofEpochMilli(done)))
        assertFalse(gossipRelayShouldRun(until, java.time.Instant.ofEpochMilli(done + 1_800_000)))
        assertTrue(gossipLogChanges(open, closed).isEmpty())
    }

    /**
     * Story 26 and story 14, which the local-encore case above cannot reach: the encore that
     * matters is somebody *else's*, arriving off the radio while this phone is in grace. It
     * must show, and it must not buy the sender another thirty minutes of this phone's battery.
     */
    @Test
    fun `an encore Fact received during grace shows inline and does not extend the deadline`() = runBlocking {
        val timeline = store()
        val checked = end.minusSeconds(4 * 3600).toEpochMilli()
        val done = end.minusSeconds(3 * 3600).toEpochMilli()
        val mine = StoredLog().adding("Choke", done - 60_000).completing(true, done)
        val gigId = timeline.gig(tonight, checked, mine)
        val deadline = gossipActiveUntil(timeline)
        assertEquals(java.time.Instant.ofEpochMilli(done + 1_800_000), deadline)

        // Through the same store the radio writes to, because that is the only path by which
        // a received envelope could reach this device's own attendance or Log at all.
        val duringGrace = done + 600_000
        val gossip = gossipStore("encore")
        val theirs = foreignFact(gigId, line = 1, text = "Evolve", at = duringGrace)
        gossip.updatePublic(duringGrace) { assertTrue(it.receive(theirs, "supplier", duringGrace)) }

        // Nothing about this device's own night moved: same Log, same completion, same deadline.
        assertEquals(mine, timeline.load().logs()[gigId])
        assertEquals(deadline, gossipActiveUntil(timeline, gossip.stoppedAt()))
        assertEquals(deadline, java.time.Instant.ofEpochMilli(gossipParticipationEnds(timeline).getValue(gigId)))
        assertTrue(gossipRelayShouldRun(gossipActiveUntil(timeline), java.time.Instant.ofEpochMilli(duringGrace)))
        assertFalse(gossipRelayShouldRun(gossipActiveUntil(timeline), java.time.Instant.ofEpochMilli(done + 1_800_000)))

        // And it is visible where the set is read, beside the line this phone wrote itself.
        val rows = weaveGossip(mine.songs, gossip.publicStates.first().project(setOf(gigId)))
        assertEquals(listOf("Choke", "Evolve"), rows.map { it.text })
    }

    /**
     * Story 30 and story 32. The notification's action is a `PendingIntent` carrying
     * [GossipService.ACTION_STOP]; what it *does* is [gossipStop], which is the seam here.
     */
    @Test
    fun `the notification stop action ends participation and keeps the Facts`() = runBlocking {
        val timeline = store()
        val checked = end.minusSeconds(5 * 3600).toEpochMilli()
        val gigId = timeline.gig(tonight, checked)
        val gossip = gossipStore("stop")
        assertEquals(end, gossipActiveUntil(timeline, gossip.stoppedAt()))

        val fact = foreignFact(gigId, line = 0, text = "Qué Más Quieres", at = checked + 60_000)
        gossip.updatePublic(checked + 60_000) { it.receive(fact, "supplier", checked + 60_000) }

        val stoppedAt = checked + 120_000
        assertNull(gossipStop(timeline, gossip, stoppedAt))
        assertFalse(gossipRelayShouldRun(gossipActiveUntil(timeline, gossip.stoppedAt()),
            java.time.Instant.ofEpochMilli(stoppedAt + 1000)))
        assertEquals(listOf("Qué Más Quieres"), gossip.publicStates.first().project(setOf(gigId)).map { it.text })

        // A stop the next Log edit undid would not be an off switch. Reopening does not resume.
        timeline.saveLog(gigId, StoredLog().completing(false))
        assertNull(gossipActiveUntil(timeline, gossip.stoppedAt()))
    }

    /**
     * Story 33, and the reason it holds: the grace deadline is never stored. It is derived
     * on every read from the check-in, the **Log**'s completion and the night's end, all of
     * which the timeline already keeps — so a restart recomputes the same instant rather than
     * restoring it, and a crash cannot lose a timer that does not exist.
     */
    @Test
    fun `the grace deadline and the held Facts survive a restart`() = runBlocking {
        val folder = temporary.newFolder()
        val file = File(folder, "timeline.json")
        val prefs = File(folder, "gossip.preferences_pb")
        val done = end.minusSeconds(3 * 3600).toEpochMilli()
        val expected = java.time.Instant.ofEpochMilli(done + 1_800_000)

        val gigId = TimelineStore(file).let { timeline ->
            val id = timeline.gig(tonight, done - 3600_000, StoredLog().adding("Choke", done - 60_000).completing(true, done))
            withGossipStore(prefs) { gossip ->
                val at = done + 60_000
                gossip.updatePublic(at) { it.receive(foreignFact(id, 1, "Evolve", at), "supplier", at) }
                assertEquals(expected, gossipActiveUntil(timeline, gossip.stoppedAt()))
            }
            id
        }

        // Nothing of the first run is alive: both stores are reopened off disk.
        val timeline = TimelineStore(file)
        withGossipStore(prefs) { gossip ->
            assertEquals(expected, gossipActiveUntil(timeline, gossip.stoppedAt()))
            assertEquals(expected.toEpochMilli(), gossipParticipationEnds(timeline).getValue(gigId))
            assertTrue(gossipRelayShouldRun(gossipActiveUntil(timeline, gossip.stoppedAt()), expected.minusSeconds(1)))
            assertFalse(gossipRelayShouldRun(gossipActiveUntil(timeline, gossip.stoppedAt()), expected))
            val state = gossip.publicStates.first()
            assertEquals(listOf("Evolve"), state.project(setOf(gigId)).map { it.text })
            assertEquals(1, state.held.size)
        }
    }

    private val theirKey = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

    /** A **Fact** somebody else signed, about a **Gig** on this timeline. */
    private fun foreignFact(gigId: String, line: Int, text: String, at: Long): GossipEnvelope =
        GossipEnvelope(gigId = gigId, scope = "theirs", author = Base64.getEncoder().encodeToString(theirKey.public.encoded),
            createdAt = at, expiresAt = end.toEpochMilli(), kind = "log", line = line, text = text).signed { bytes ->
            Signature.getInstance("SHA256withECDSA").run { initSign(theirKey.private); update(bytes); sign() }
        }!!

    private fun gossipStore(name: String): GossipStore = GossipStore(
        androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()),
        ) { File(temporary.newFolder(), "$name.preferences_pb") },
    )

    /** One process's worth of DataStore: the scope dies with the block, as a restart does. */
    private suspend fun withGossipStore(file: File, body: suspend (GossipStore) -> Unit) {
        val job = kotlinx.coroutines.SupervisorJob()
        try {
            body(GossipStore(androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
                scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + job)) { file }))
        } finally {
            job.cancelAndJoin()
        }
    }

    /**
     * Where a **Pass** that names no night lands (#498): the latest **Check-in** still running.
     *
     * "Currently active" and "the initial active **Gig** is the latest **Check-in**" are one
     * rule, which is why there is no stateful *active gig* to disagree with the deadlines. A
     * night whose participation has ended stops attracting **Passes**, and a night nobody
     * checked into never attracted any.
     */
    @Test
    fun `the active Gig is the latest still-running Check-in`() = runBlocking {
        val store = store()
        val early = store.gig(tonight, end.minusSeconds(5 * 3600).toEpochMilli())
        val later = store.gig(tonight, end.minusSeconds(2 * 3600).toEpochMilli())
        store.gig(tonight, null)
        val during = end.minusSeconds(3600).toEpochMilli()
        assertEquals(later, gossipActiveGigId(store, now = during))
        assertNull(gossipActiveGigId(store, now = end.plusSeconds(3600).toEpochMilli()))
        // Stopping ends tonight, so there is nowhere for an unattached Pass to land.
        assertNull(gossipActiveGigId(store, stoppedAt = during - 1, now = during))
        assertTrue(early != later)
    }

    /**
     * Adoption (#496) renames the night; the **Seen with** record has to be readable under both
     * ids and must not become two records. The union is over device identity, so the id set
     * being wider than one is safe.
     */
    @Test
    fun `a night's aliases index the adopted id and the local one at the same record`() = runBlocking {
        val store = store()
        val local = store.gig(tonight, end.minusSeconds(3600).toEpochMilli())
        val aliases = gossipGigAliases(store, mapOf(local to "setlist-id"))
        assertEquals(setOf(local, "setlist-id"), aliases[local])
        assertEquals(aliases[local], aliases["setlist-id"])
        val state = PublicGossipState()
        state.rememberPass("their-relay", emptyList(), local, 1000)
        state.rememberPass("their-relay", emptyList(), "setlist-id", 2000)
        assertEquals(1, state.seenWith(aliases.getValue(local)).others)
    }

}
