package io.github.magnusencoded.setlist2spotify

import io.github.magnusencoded.setlist2spotify.ble.NearbyNameLimitProbe
import io.github.magnusencoded.setlist2spotify.ble.ProbeCard
import io.github.magnusencoded.setlist2spotify.ble.SCAN_RESPONSE_NAME_BUDGET
import io.github.magnusencoded.setlist2spotify.ble.parseProbeCard
import io.github.magnusencoded.setlist2spotify.ble.sliceForOffset
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

    /**
     * #30: with the MTU bump skipped, the platform reads a card longer than one ATT
     * PDU as a rising-offset series of ATT_READ_BLOB requests (20 payload bytes per
     * read at the default 23-byte MTU) and reassembles them. This is the server side
     * of that — reject it and a truncated card would look exactly like a success,
     * which is the bug the old #18 probe had before it handled offset at all.
     */
    @Test fun aChunkedReadAtDefaultMtuStillReturnsTheWholeCard() {
        val payload = card.bytes()
        assertTrue("test card should need chunking to be a meaningful check", payload.size > 20)

        val chunkSize = 20 // MTU 23 minus the 3-byte ATT_OVERHEAD
        val reassembled = ByteArray(payload.size)
        var offset = 0
        var reads = 0
        while (offset < payload.size) {
            // sliceForOffset hands back everything from offset onward — the server's
            // job is only to answer at the right offset. The radio (simulated here)
            // is what actually caps one PDU to chunkSize bytes and drives the next
            // read at the next offset.
            val onWire = sliceForOffset(payload, offset).copyOfRange(0, chunkSize.coerceAtMost(payload.size - offset))
            onWire.copyInto(reassembled, offset)
            offset += onWire.size
            reads++
            assertTrue("should not spin forever", reads <= payload.size)
        }
        // One extra blob read past the end, as a real client issues once offset
        // lands exactly on the payload boundary — must come back empty, not throw.
        assertEquals(0, sliceForOffset(payload, payload.size).size)
        assertTrue("$reads reads should need more than one round trip at MTU 23", reads > 1)
        assertEquals(String(payload, Charsets.UTF_8), String(reassembled, Charsets.UTF_8))
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

    // --- #84: exact wire format pinned so the iOS port fails a test, not a demo, on divergence. ---

    @Test fun encodesFieldsInAFixedOrderWithPercentEscaping() {
        val full = ProbeCard(
            name = "Anna Ø",
            publicKey = "keyABC=",
            setlistfm = "annaofficial",
            spotifyId = "annaspotify",
        )
        assertEquals(
            "station-to-station://friend?name=Anna+%C3%98&k=keyABC%3D&u=annaofficial&sid=annaspotify",
            full.encode(),
        )
    }

    @Test fun encodeOmitsAbsentSetlistfmAndSpotifyId() {
        val minimal = ProbeCard(name = "Bob", publicKey = "key2")
        assertEquals("station-to-station://friend?name=Bob&k=key2", minimal.encode())
    }

    @Test fun parseFallsBackToUForNameWhenNameIsAbsent() {
        assertEquals(
            ProbeCard(name = "dizzi90", publicKey = "abc", setlistfm = "dizzi90"),
            parseProbeCard("station-to-station://friend?u=dizzi90&k=abc"),
        )
    }

    @Test fun parseIgnoresAnUnknownQueryParameter() {
        assertEquals(
            ProbeCard(name = "Bob", publicKey = "abc"),
            parseProbeCard("station-to-station://friend?name=Bob&k=abc&future=xyz"),
        )
    }

    @Test fun truncateToBytesKeepsANameThatFitsExactlyAtTheBudget() {
        val exact = "A".repeat(SCAN_RESPONSE_NAME_BUDGET)
        assertEquals(exact, truncateToBytes(exact))
    }

    @Test fun truncateToBytesDropsOneCharWhenOneByteOverBudget() {
        val oneOver = "A".repeat(SCAN_RESPONSE_NAME_BUDGET + 1)
        assertEquals("A".repeat(SCAN_RESPONSE_NAME_BUDGET), truncateToBytes(oneOver))
    }

    @Test fun truncateToBytesCutsAMultiByteNameOnACharBoundary() {
        // É is 2 UTF-8 bytes; 14 of them is 28 bytes, one over budget.
        val name = "É".repeat(14)
        val cut = truncateToBytes(name)
        assertEquals("É".repeat(13), cut)
        assertEquals(26, cut.toByteArray(Charsets.UTF_8).size)
    }

    @Test fun truncateToBytesNeverSplitsASurrogatePair() {
        // Each emoji is a surrogate pair and 4 UTF-8 bytes; 7 of them is 28 bytes,
        // one over budget. A blind byte-count cut lands mid-emoji, on the high
        // surrogate of the 7th one — the whole emoji must go, not half of it.
        val name = "😀".repeat(7)
        assertEquals("😀".repeat(6), truncateToBytes(name))
    }

    @Test fun sliceForOffsetAtZeroReturnsTheWholePayload() {
        val payload = card.bytes()
        assertEquals(
            String(payload, Charsets.UTF_8),
            String(sliceForOffset(payload, 0), Charsets.UTF_8),
        )
    }

    @Test fun sliceForOffsetInTheMiddleReturnsTheRemainder() {
        val payload = card.bytes()
        val mid = payload.size / 2
        assertEquals(
            String(payload.copyOfRange(mid, payload.size), Charsets.UTF_8),
            String(sliceForOffset(payload, mid), Charsets.UTF_8),
        )
    }

    @Test fun sliceForOffsetPastTheEndIsEmptyNotACrash() {
        val payload = card.bytes()
        assertEquals(0, sliceForOffset(payload, payload.size + 50).size)
    }
}
