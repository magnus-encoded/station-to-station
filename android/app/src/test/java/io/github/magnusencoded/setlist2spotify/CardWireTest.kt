package io.github.magnusencoded.setlist2spotify

import io.github.magnusencoded.setlist2spotify.ble.NearbyNameLimitProbe
import io.github.magnusencoded.setlist2spotify.ble.ProbeCard
import io.github.magnusencoded.setlist2spotify.ble.SCAN_RESPONSE_NAME_BUDGET
import io.github.magnusencoded.setlist2spotify.ble.parseProbeCard
import io.github.magnusencoded.setlist2spotify.ble.truncateToBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardWireTest {

    private val card = ProbeCard(
        name = "Magnus Vikan",
        publicKey = "8J+YgPCfmIDwn5iA8J+YgPCfmIDwn5iA8J+YgPCfmIA=", // 32 bytes, base64
        setlistfm = "dizzi90",
        spotifyId = "dizziness",
    )

    @Test fun roundTrips() {
        assertEquals(card, parseProbeCard(card.encode()))
    }

    @Test fun survivesReservedCharactersInTheName() {
        val awkward = ProbeCard(name = "Ærlig & co =?#", publicKey = "AAAA", setlistfm = "x y")
        assertEquals(awkward, parseProbeCard(awkward.encode()))
    }

    @Test fun rejectsAnythingWithoutAKey() {
        assertNull(parseProbeCard("setlist2spotify://friend?u=dizzi90&name=Magnus"))
        assertNull(parseProbeCard("setlist2spotify://callback?code=abc"))
        assertNull(parseProbeCard("nonsense"))
    }

    /** #30's premise: the card cannot ride an advertisement, but must fit a sane MTU. */
    @Test fun cardIsTooBigForAnAdvertAndSmallEnoughForOneMtuRead() {
        val size = card.bytes().size
        assertTrue("$size bytes should not fit a 31-byte advert", size > 31)
        assertTrue("$size bytes should fit one 185-byte ATT read", size <= 185)
    }

    /**
     * The finding #30 scope item 4 asks for, pinned so a later field cannot quietly
     * push the card past it: Nearby's endpoint name tops out at 131 bytes and
     * overflow is silent — no error, just a truncated name at the other end.
     */
    @Test fun cardWithAKeyStillFitsANearbyEndpointName() {
        val size = card.bytes().size
        assertTrue(
            "$size bytes exceeds Nearby's $NearbyNameLimitProbe.NEARBY_ENDPOINT_NAME_LIMIT-byte endpoint name; " +
                "the Android fast path would need connect-and-read too",
            size <= NearbyNameLimitProbe.NEARBY_ENDPOINT_NAME_LIMIT,
        )
        // …but only just. A long display name is enough to blow it.
        val chatty = card.copy(name = "Magnus Vikan (Station to Station)")
        assertTrue(chatty.bytes().size > NearbyNameLimitProbe.NEARBY_ENDPOINT_NAME_LIMIT)
    }

    @Test fun nameTruncationStaysInsideTheScanResponse() {
        val long = "Bjørn-Kristian Æøåstad-Hemmelighetsfull"
        val cut = truncateToBytes(long)
        assertTrue(cut.toByteArray(Charsets.UTF_8).size <= SCAN_RESPONSE_NAME_BUDGET)
        assertTrue(long.startsWith(cut))
        assertEquals("dizzi90", truncateToBytes("dizzi90"))
        // A name that is all emoji must not be cut mid-surrogate-pair.
        val emoji = truncateToBytes("😀".repeat(10))
        assertEquals(emoji, String(emoji.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }
}
