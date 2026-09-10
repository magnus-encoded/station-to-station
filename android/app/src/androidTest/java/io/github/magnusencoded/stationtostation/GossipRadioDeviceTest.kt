package io.github.magnusencoded.stationtostation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.magnusencoded.stationtostation.ble.GossipPeripheral
import io.github.magnusencoded.stationtostation.data.gossip.GigIdentity
import io.github.magnusencoded.stationtostation.data.gossip.PublicGossipState
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in real-radio test. Run the Pi's gossip_v2_peer.py with six trials alongside it. */
@RunWith(AndroidJUnit4::class)
class GossipRadioDeviceTest {
    /** Run gossip_v2_peer.py --controls: indirect/direct request, indirect/direct receipt. */
    @Test fun indirectControlsAreRejectedWithoutPoisoningDirectDelivery() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("manual_ble_controls") == "true")
        val scope = "radio-test-${UUID.randomUUID()}"
        val identity = GigIdentity(scope)
        val state = PublicGossipState()
        val admitted = mutableListOf<Boolean>()
        val received = CountDownLatch(4)
        val radio = GossipPeripheral(InstrumentationRegistry.getInstrumentation().targetContext,
            myKey = identity::publicKey, sign = identity::sign)
        radio.onPublicDelivery = { delivery ->
            synchronized(state) {
                delivery.pass.batch.forEach {
                    admitted.add(state.receive(it, delivery.from, System.currentTimeMillis()))
                }
            }
            received.countDown()
        }
        try {
            radio.start()
            assertTrue("Pi did not deliver four verified control Passes", received.await(90, TimeUnit.SECONDS))
            synchronized(state) {
                assertEquals(listOf(false, true, false, true), admitted)
                assertEquals(2, state.seen.size)
                assertTrue(state.facts.isEmpty())
                assertTrue(state.held.isEmpty())
                assertEquals(setOf("useful-peer"), state.useful.keys)
            }
            Thread.sleep(1000)
        } finally {
            radio.stop()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("gossip-gig-$scope")
        }
    }

    @Test fun publicPassesCrossTheRealGattLinkAndCloseTheStormGate() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("manual_ble_peer") == "true")
        val scope = "radio-test-${UUID.randomUUID()}"
        val identity = GigIdentity(scope)
        val state = PublicGossipState()
        val received = CountDownLatch(6)
        val radio = GossipPeripheral(InstrumentationRegistry.getInstrumentation().targetContext,
            myKey = identity::publicKey, sign = identity::sign)
        radio.onPublicDelivery = { delivery ->
            synchronized(state) {
                delivery.pass.batch.forEach { state.receive(it, delivery.from, System.currentTimeMillis()) }
            }
            received.countDown()
        }
        try {
            radio.start()
            assertTrue("Pi did not deliver six verified Passes", received.await(90, TimeUnit.SECONDS))
            synchronized(state) {
                assertEquals(1, state.facts.size)
                assertTrue("second copy must close the storm gate", state.held.isEmpty())
            }
            // Let the final ATT write response leave before closing the server.
            Thread.sleep(1000)
        } finally {
            radio.stop()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("gossip-gig-$scope")
        }
    }
}
