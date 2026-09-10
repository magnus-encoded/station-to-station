package io.github.magnusencoded.stationtostation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.magnusencoded.stationtostation.data.gossip.GossipStore
import kotlinx.coroutines.*
import java.io.File
import io.github.magnusencoded.stationtostation.data.exchange.verifyChallenge
import io.github.magnusencoded.stationtostation.data.gossip.GigIdentity
import io.github.magnusencoded.stationtostation.data.gossip.GossipEnvelope
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Exercises Android Keystore; JVM tests can only exercise software signing keys. */
@RunWith(AndroidJUnit4::class)
class GigIdentityDeviceTest {
    @Test fun gigKeyPersistsAndSignsVerifiableEnvelopes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "scope-test-${UUID.randomUUID()}.preferences_pb")
        val job = SupervisorJob()
        val data = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
        val bindings = GossipStore(data)
        val scopes = List(2) { bindings.authorScope("instrumented-gig-$it") }
        try {
            assertEquals(scopes[0], GossipStore(data).authorScope("instrumented-gig-0"))
            val workers = Executors.newFixedThreadPool(2)
            try {
                val keys = workers.invokeAll(List(2) { Callable { GigIdentity(scopes[0]).publicKey() } })
                    .map { it.get() }
                assertEquals(1, keys.toSet().size)
            } finally { workers.shutdownNow() }
            val first = GigIdentity(scopes[0])
            val author = first.publicKey()
            val reopened = GigIdentity(scopes[0])
            assertEquals(author, reopened.publicKey())
            assertNotEquals(author, GigIdentity(scopes[1]).publicKey())
            val challenge = "gig-identity-device-test".toByteArray()
            assertTrue(verifyChallenge(challenge, reopened.sign(challenge), author))
            val fact = GossipEnvelope(gigId = "device-test", scope = scopes[0], author = author,
                createdAt = 1000, expiresAt = 100000, kind = "log", line = 3, text = "Karma Police")
                .signed(reopened::sign)
            assertNotNull(fact)
            assertTrue(fact!!.valid())
            assertFalse(fact.copy(text = "changed").valid())
        } finally {
            // Only keys minted by this test; never remove the app's existing identities.
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            scopes.forEach { store.deleteEntry("gossip-gig-$it") }
            job.cancelAndJoin()
            file.delete()
        }
    }
}
