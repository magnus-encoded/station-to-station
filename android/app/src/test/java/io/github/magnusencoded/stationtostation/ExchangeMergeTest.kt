package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.ble.PeerHit
import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.data.exchange.mergePeers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the dedup decision documented in [mergePeers]: a brief duplicate, never a vanish. */
class ExchangeMergeTest {

    private fun hit(address: String, name: String?) = PeerHit(address, name, rssi = -50, discoveryMs = 100)

    @Test fun nearbyPeerCarriesItsUsername() {
        val merged = mergePeers(listOf(Friend(setlistfm = "dizzi90", name = "Magnus")), emptyList())
        assertEquals(1, merged.size)
        assertEquals("Magnus", merged[0].name)
        assertEquals("dizzi90", merged[0].setlistfm)
    }

    @Test fun blePeerIsNameOnlyUntilTheCardArrives() {
        val merged = mergePeers(emptyList(), listOf(hit("AA:BB", "Ozzy")))
        assertEquals(1, merged.size)
        assertEquals("Ozzy", merged[0].name)
        assertNull("no username until we've read the card", merged[0].setlistfm)
    }

    @Test fun bleHitsWithoutANameAreDropped() {
        assertTrue(mergePeers(emptyList(), listOf(hit("AA:BB", null), hit("CC:DD", " "))).isEmpty())
    }

    @Test fun sameNameOnBothRadiosShowsTwiceRatherThanVanishing() {
        // The accepted failure mode: a person seen by both radios is a brief duplicate,
        // never collapsed on a guessed identity (which could swallow a real second person).
        val merged = mergePeers(
            listOf(Friend(setlistfm = "ozzy", name = "Ozzy")),
            listOf(hit("AA:BB", "Ozzy")),
        )
        assertEquals(2, merged.size)
        assertEquals(2, merged.map { it.id }.toSet().size) // distinct rows, distinct keys
    }

    @Test fun repeatedScanResultsForOneDeviceAreOneRow() {
        val merged = mergePeers(emptyList(), listOf(hit("AA:BB", "Ozzy"), hit("AA:BB", "Ozzy")))
        assertEquals(1, merged.size)
    }
}
