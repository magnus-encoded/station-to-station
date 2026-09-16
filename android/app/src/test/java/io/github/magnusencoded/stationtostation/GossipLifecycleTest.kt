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
import io.github.magnusencoded.stationtostation.data.gossip.gossipRelayShouldRun
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.LocalDate
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import io.github.magnusencoded.stationtostation.data.gossip.gossipParticipationEnds

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

}
