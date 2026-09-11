package io.github.magnusencoded.stationtostation

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.magnusencoded.stationtostation.data.gossip.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PublicGossipStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun authorScopeSurvivesConcurrentCallersExpiryAndRestart() = runBlocking {
        val file = File(temporary.root, "scopes.preferences_pb")
        val job = SupervisorJob()
        val data = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
        val first = GossipStore(data)
        val second = GossipStore(data)
        val scope: String
        try {
            val scopes = coroutineScope {
                listOf(async { first.authorScope("local-gig") },
                    async { second.authorScope("local-gig") }).awaitAll()
            }
            scope = scopes.first()
            assertEquals(scope, scopes.last())
            assertNotEquals("local-gig", scope)
            assertNotEquals(scope, second.authorScope("another-local-gig"))
            first.updatePublic(Long.MAX_VALUE) { }
            assertEquals(scope, first.authorScope("local-gig"))
        } finally {
            job.cancelAndJoin()
        }
        val reopenedJob = SupervisorJob()
        try {
            val reopened = GossipStore(PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + reopenedJob)) { file })
            assertEquals(scope, reopened.authorScope("local-gig"))
        } finally {
            reopenedJob.cancelAndJoin()
        }
    }

    @Test fun authorAndRadioUpdatesShareOneTransactionStreamAndSurviveRestart() = runBlocking {
        val file = File(temporary.root, "gossip.preferences_pb")
        val job = SupervisorJob()
        val data = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
        val author = GossipStore(data)
        val radio = GossipStore(data)
        val fixture = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "fixtures/gossip/signed-pass/pass.txt") }.first { it.isFile }
        val envelopes = requireNotNull(decodePublicGossipPass(fixture.readBytes())).batch
        val now = envelopes.maxOf { it.createdAt }
        try {
            coroutineScope {
                launch { author.updatePublic(now) { it.receive(envelopes[0], "", now, local = true) } }
                launch { radio.updatePublic(now) { it.receive(envelopes[1], "supplier", now) } }
            }
            assertEquals(2, author.publicStates.first().facts.size)
            radio.updatePublic(now) { it.delivered("recipient", listOf(envelopes[0].id)) }
            radio.updatePublic(now) { it.receive(envelopes[1], "duplicate", now) }
            author.publicStates.first().facts.clear()
            assertEquals(2, radio.publicStates.first().facts.size)
        } finally {
            job.cancelAndJoin()
        }
        val reopenedJob = SupervisorJob()
        val reopened = GossipStore(PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + reopenedJob)) { file })
        try {
            val restored = reopened.publicStates.first()
            assertEquals(2, restored.facts.size)
            assertEquals(setOf(envelopes[0].author), restored.localAuthors)
            assertTrue(restored.offer("recipient", now).isEmpty())
            assertFalse(restored.receive(envelopes[1], "late-duplicate", now))
            assertTrue(restored.held.containsKey(envelopes[0].id))
            reopened.updatePublic(envelopes.maxOf { it.expiresAt } + 1) { }
            val expired = reopened.publicStates.first()
            assertTrue(expired.held.isEmpty())
            assertTrue(expired.seen.isEmpty())
            assertEquals(2, expired.facts.size)
        } finally {
            reopenedJob.cancelAndJoin()
        }
    }
}
