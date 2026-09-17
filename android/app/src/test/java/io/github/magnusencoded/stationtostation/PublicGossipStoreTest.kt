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
            first.stopParticipation(1234)
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
            assertEquals(1234L, reopened.stoppedAt())
        } finally {
            reopenedJob.cancelAndJoin()
        }
    }

    @Test fun lateAdoptionAliasSurvivesRestartWithoutPublishingAnUpdate() = runBlocking {
        val file = File(temporary.root, "late-adoption.preferences_pb")
        withStore(file) { store ->
            store.stopParticipation(2000)
            store.rememberAdoption("local-gig", "fm-gig")
            store.rememberAdoption("local-gig", "fm-gig")
            assertEquals(mapOf("local-gig" to "fm-gig"), store.adoptedIds())
            assertTrue(store.publicStates.first().facts.isEmpty())
        }
        withStore(file) { store ->
            assertEquals(mapOf("local-gig" to "fm-gig"), store.adoptedIds())
            assertTrue(store.publicStates.first().facts.isEmpty())
        }
    }

    private val key = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

    private fun logFact(scope: String, gigId: String, line: Int, text: String, at: Long,
                        formerIds: List<String> = emptyList(), signingKey: java.security.KeyPair = key): GossipEnvelope =
        GossipEnvelope(gigId = gigId, formerIds = formerIds, scope = scope, author = java.util.Base64.getEncoder().encodeToString(signingKey.public.encoded),
            createdAt = at, expiresAt = at + 3_600_000, kind = "log", line = line, text = text)
            .signed { bytes -> java.security.Signature.getInstance("SHA256withECDSA").run {
                initSign(signingKey.private); update(bytes); sign() } }!!

    private suspend fun <T> withStore(file: File, body: suspend (GossipStore) -> T): T {
        val job = SupervisorJob()
        try {
            return body(GossipStore(PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + job)) { file }))
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test fun publicationUnderAScopeFollowsSetlistAdoptionAcrossRestart() = runBlocking {
        val file = File(temporary.root, "publish.preferences_pb")
        val now = 10_000L
        val timeline = io.github.magnusencoded.stationtostation.data.TimelineStore(File(temporary.root, "timeline.json"))
        val localId = timeline.createLocalGig("04-09-2026", "Band", "Room")
        val scope = withStore(file) { store ->
            val scope = store.authorScope(localId)
            store.updatePublic(now) { it.receive(logFact(scope, localId, 0, "Opener", now), "", now, local = true) }
            // Adoption gives the night a setlist.fm id; the scope is bound to the local Gig, so it does not rotate.
            assertTrue(timeline.adoptSetlistId(localId, "fm-1"))
            assertEquals(localId, timeline.load().gigForSetlist("fm-1")?.id)
            assertEquals(scope, store.authorScope(localId))
            store.updatePublic(now + 1) {
                it.receive(logFact(scope, "fm-1", 0, "Opener (corrected)", now + 1, listOf(localId)), "", now + 1, local = true)
            }
            scope
        }
        withStore(file) { store ->
            val state = store.publicStates.first()
            assertEquals(1, state.localAuthors.size)
            assertEquals(scope, store.authorScope(localId))
            for (ids in listOf(setOf(localId), setOf("fm-1"))) {
                assertEquals(listOf("Opener (corrected)"), state.project(ids).map { it.text })
            }
        }
    }

    @Test fun explicitMergeDoesNotRebindOrDeleteEitherAuthorScope() = runBlocking {
        val file = File(temporary.root, "merge.preferences_pb")
        val timeline = io.github.magnusencoded.stationtostation.data.TimelineStore(File(temporary.root, "merge-timeline.json"))
        val first = timeline.createLocalGig("04-09-2026", "Band", "Room")
        val second = timeline.createLocalGig("04-09-2026", "Band", "Room")
        val secondKey = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val now = 10_000L
        val (a, b) = withStore(file) { store ->
            val a = store.authorScope(first)
            val b = store.authorScope(second)
            store.updatePublic(now) { state ->
                state.receive(logFact(a, first, 0, "Song A", now), "", now, local = true)
                state.receive(logFact(b, second, 0, "Song B", now, signingKey = secondKey), "", now, local = true)
            }
            a to b
        }
        assertNotEquals(a, b)
        assertNotNull(timeline.mergeGigs(first, second))
        assertEquals(1, timeline.load().gigs.size)
        withStore(file) { store ->
            assertEquals(a, store.authorScope(first))
            assertEquals(b, store.authorScope(second))
            val state = store.publicStates.first()
            assertEquals(2, state.localAuthors.size)
            assertEquals(setOf("Song A", "Song B"), state.facts.values.map { it.text }.toSet())
        }
        // Projection onto the surviving night is a separate requirement; this proves
        // only that merging the timeline does not silently collapse signing identities.
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
