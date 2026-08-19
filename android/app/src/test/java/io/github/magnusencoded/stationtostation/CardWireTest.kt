package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.ble.CARD_WRITE_CHARACTERISTIC_UUID
import io.github.magnusencoded.stationtostation.ble.NearbyNameLimitProbe
import io.github.magnusencoded.stationtostation.ble.ProbeCard
import io.github.magnusencoded.stationtostation.ble.SCAN_RESPONSE_NAME_BUDGET
import io.github.magnusencoded.stationtostation.ble.fitsAnEndpointName
import io.github.magnusencoded.stationtostation.ble.parseProbeCard
import io.github.magnusencoded.stationtostation.ble.sliceForOffset
import io.github.magnusencoded.stationtostation.ble.truncateToBytes
import io.github.magnusencoded.stationtostation.ble.writeAtOffset
import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.data.exchange.friendFromCard
import io.github.magnusencoded.stationtostation.data.isPlausibleSetlistFmUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

class CardWireTest {

    /**
     * A **real** key, not a stub. This fixture used to hold 32 bytes (44 base64 chars) and
     * every size assertion below ran against it — which is how
     * [aCardWithARealKeyCannotRideANearbyEndpointName] passed while asserting the opposite
     * of the truth, and how #272 shipped. An ECDSA P-256 SubjectPublicKeyInfo is 91 bytes:
     * 124 base64 characters, 132 once URL-encoded, which is over Nearby's whole budget on
     * its own.
     */
    private val card = ProbeCard(
        name = "Magnus Vikan",
        publicKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAESJ5HBSXgpBvTHldadDFDHID2DHFp5nzo" +
            "W/bIS4g6jqE9CexG0gBprY6tuJMyl4+vpW0LWI4J4QmJybaY2nkiUg==",
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

    /**
     * #30's premise, corrected against a real key: the card cannot ride an advertisement,
     * and it does not fit one ATT read either — so the blob path below is not an edge case,
     * it is how every card is read.
     */
    @Test fun cardIsTooBigForAnAdvertAndTooBigForOneMtuRead() {
        val size = card.bytes().size
        assertTrue("$size bytes should not fit a 31-byte advert", size > 31)
        assertTrue(
            "$size bytes fits one 185-byte read — check the fixture is still a real key",
            size > 185,
        )
    }

    /**
     * **Why the key rides a connection and not an advertisement (#272).**
     *
     * Nearby's endpoint name tops out at 131 bytes and overflow is silent — no error, just
     * a truncated name over Bluetooth Classic or a dropped advertisement over BLE. A card
     * carrying a real P-256 key is nowhere near fitting: the URL-encoded key alone is over
     * the whole budget.
     *
     * This assertion used to say the opposite, and passed, because the fixture key was a
     * 32-byte stub. That is exactly how #272 shipped — an Android↔Android **Exchange** that
     * completed with no key and made nobody a **Contact**.
     */
    @Test fun aCardWithARealKeyCannotRideANearbyEndpointName() {
        val size = card.bytes().size
        assertTrue(
            "$size bytes now fits Nearby's ${NearbyNameLimitProbe.NEARBY_ENDPOINT_NAME_LIMIT}-byte " +
                "endpoint name; if that is real, the fixture key is not",
            size > NearbyNameLimitProbe.NEARBY_ENDPOINT_NAME_LIMIT,
        )
        val urlEncodedKey = URLEncoder.encode(card.publicKey, "UTF-8").length
        assertTrue(
            "the key alone is $urlEncodedKey bytes, which should already blow the budget",
            urlEncodedKey > NearbyNameLimitProbe.NEARBY_ENDPOINT_NAME_LIMIT,
        )
    }

    /**
     * What Nearby *does* advertise: the keyless share URI, which is only a claim that a card
     * exists here. An ordinary card fits with room to spare.
     */
    @Test fun theKeylessAdvertisedCardFitsWithHeadroom() {
        val advertised = Friend(setlistfm = "dizzi90", name = "Magnus Vikan", spotifyId = "dizziness")
        val size = shareUriLength(advertised)
        assertTrue(
            "$size bytes leaves no room for a longer display name",
            size <= NearbyNameLimitProbe.NEARBY_ENDPOINT_NAME_LIMIT - 40,
        )
    }

    /**
     * …but "an ordinary card fits" is not the same claim as "our cards fit", and the earlier
     * version of this suite only made the first one.
     *
     * `AppViewModel.myCard()` advertises `Friend(setlistfm = it, name = it)`, so the username
     * is carried twice and the URI costs `36 + 2N` bytes for an N-character ASCII handle.
     * [isPlausibleSetlistFmUser] admits 64 characters of any script, so a username nobody
     * would call invalid overflows — and an overflow is silent, which is #272 again. Hence
     * [fitsAnEndpointName], which refuses instead of vanishing.
     */
    @Test fun aLongButValidUsernameIsRefusedRatherThanSilentlyDropped() {
        val longest = "a".repeat(64)
        assertTrue("64 characters is a username we accept", isPlausibleSetlistFmUser(longest))
        assertFalse(
            "a 64-character handle should not fit an endpoint name",
            fitsAnEndpointName(shareUri(Friend(setlistfm = longest, name = longest))),
        )
        // The boundary: two bytes per extra character, so 48 is where it goes over.
        assertTrue(fitsAnEndpointName(shareUri(Friend(setlistfm = "a".repeat(47), name = "a".repeat(47)))))
        assertFalse(fitsAnEndpointName(shareUri(Friend(setlistfm = "a".repeat(48), name = "a".repeat(48)))))
    }

    /**
     * Non-Latin usernames are deliberately admitted — the allow-list excludes URL syntax,
     * not scripts — and they cost far more than a byte each: a Cyrillic character is two
     * UTF-8 bytes, which is six characters once percent-encoded. A rule that counted
     * characters rather than bytes would miss this entirely.
     */
    @Test fun aNonLatinUsernameOverflowsSoonerBecauseTheBudgetIsBytes() {
        val cyrillic = "магнус".repeat(3) // 18 characters, well inside the 64-char rule
        assertTrue(isPlausibleSetlistFmUser(cyrillic))
        assertFalse(
            "18 non-Latin characters encode past the byte budget",
            fitsAnEndpointName(shareUri(Friend(setlistfm = cyrillic, name = cyrillic))),
        )
    }

    /**
     * The string `toShareUri` builds, without android.net.Uri (not available in a JVM test).
     * A close approximation rather than the exact bytes: `Uri.Builder` percent-encodes a
     * space where `URLEncoder` writes `+`, so the real name is a little longer. That is part
     * of why the ordinary-card assertion demands headroom rather than a bare fit.
     */
    private fun shareUri(f: Friend): String = buildString {
        append("station-to-station://friend?u=").append(URLEncoder.encode(f.setlistfm, "UTF-8"))
        append("&name=").append(URLEncoder.encode(f.name, "UTF-8"))
        f.spotifyId?.let { append("&sid=").append(URLEncoder.encode(it, "UTF-8")) }
    }

    private fun shareUriLength(f: Friend): Int = shareUri(f).toByteArray(Charsets.UTF_8).size

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

    /**
     * #87: the card written back is the same card, through the same parser. A typo in
     * the UUID is a silent no-discovery that costs a device session to find, so it is
     * pinned here rather than in a room with two phones.
     */
    @Test fun theWriteCharacteristicUuidIsFixedOnBothPlatforms() {
        assertEquals(
            "7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7713",
            CARD_WRITE_CHARACTERISTIC_UUID.toString(),
        )
    }

    @Test fun aWrittenCardBecomesTheSameFriendAsAReadOne() {
        val written = String(card.bytes(), Charsets.UTF_8)
        assertEquals(friendFromCard(card), friendFromCard(parseProbeCard(written)!!))
        assertEquals("dizzi90", friendFromCard(card)?.setlistfm)
    }

    @Test fun anUnparseableWrittenPayloadYieldsNothing() {
        assertNull(parseProbeCard("half a card"))
        assertNull(parseProbeCard(""))
        // …and a card with no username is not a blank friend, it is no friend.
        assertNull(friendFromCard(ProbeCard(name = "Magnus", publicKey = "AAAA")))
    }

    /**
     * A card longer than one ATT PDU arrives as a rising-offset series of write
     * requests. The server must place each chunk at its offset; assuming one write
     * carried the payload is the read path's truncation bug, in reverse.
     */
    @Test fun aChunkedWriteAtDefaultMtuStillReassemblesTheWholeCard() {
        val payload = card.bytes()
        val chunkSize = 20 // MTU 23 minus the 3-byte ATT_OVERHEAD
        var accumulated = ByteArray(0)
        var offset = 0
        while (offset < payload.size) {
            val onWire = payload.copyOfRange(offset, (offset + chunkSize).coerceAtMost(payload.size))
            accumulated = writeAtOffset(accumulated, offset, onWire)
            offset += onWire.size
        }
        assertTrue("test card should need chunking to be meaningful", payload.size > chunkSize)
        assertEquals(card, parseProbeCard(String(accumulated, Charsets.UTF_8)))
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
