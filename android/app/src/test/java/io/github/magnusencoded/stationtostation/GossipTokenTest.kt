package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.gossip.GOSSIP_ADVERTISE_SLOT
import io.github.magnusencoded.stationtostation.data.gossip.GOSSIP_TOKEN_BUCKET
import io.github.magnusencoded.stationtostation.data.gossip.GOSSIP_TOKEN_BYTES
import io.github.magnusencoded.stationtostation.data.gossip.gossipAdvertisedToken
import io.github.magnusencoded.stationtostation.data.gossip.gossipToken
import io.github.magnusencoded.stationtostation.data.gossip.gossipTokenBucket
import io.github.magnusencoded.stationtostation.data.gossip.gossipTokenHex
import io.github.magnusencoded.stationtostation.data.gossip.gossipTokenOwner
import io.github.magnusencoded.stationtostation.data.gossip.gossipTokenTable
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rotating **Token** (#416).
 *
 * The properties here are the ones a radio cannot tell you about: that both ends of a pair
 * compute the same bytes, that a stranger's advertisement resolves to nobody, and that a
 * clock a few minutes out still recognises a **Contact**. On a phone all three fail as
 * "it didn't find them", which is indistinguishable from bad reception.
 *
 * Keys here are stand-in strings, not real SPKI: the derivation treats them as opaque bytes,
 * and a fixture that has to generate keypairs to test a string sort is a fixture that hides
 * what it is asserting.
 */
class GossipTokenTest {

    private val mine = "AAAAkey-mine"
    private val theirs = "ZZZZkey-theirs"
    private val stranger = "MMMMkey-stranger"
    private val now: Instant = Instant.parse("2026-09-04T21:07:30Z")

    @Test
    fun `both ends of a pair derive the same token`() {
        val bucket = gossipTokenBucket(now)

        assertArrayEquals(
            gossipToken(mine, theirs, bucket),
            gossipToken(theirs, mine, bucket),
        )
    }

    @Test
    fun `a token is eight bytes`() {
        assertEquals(GOSSIP_TOKEN_BYTES, gossipToken(mine, theirs, 0)!!.size)
    }

    @Test
    fun `a different pair, a different token`() {
        val bucket = gossipTokenBucket(now)

        assertNotEquals(
            gossipTokenHex(gossipToken(mine, theirs, bucket)!!),
            gossipTokenHex(gossipToken(mine, stranger, bucket)!!),
        )
    }

    @Test
    fun `the token rotates with the bucket`() {
        assertNotEquals(
            gossipTokenHex(gossipToken(mine, theirs, 100)!!),
            gossipTokenHex(gossipToken(mine, theirs, 101)!!),
        )
    }

    @Test
    fun `the bucket is a quarter of an hour`() {
        val bucket = gossipTokenBucket(now)

        assertEquals(bucket, gossipTokenBucket(now.plus(Duration.ofMinutes(7))))
        assertEquals(bucket + 1, gossipTokenBucket(now.plus(GOSSIP_TOKEN_BUCKET)))
    }

    /** `floorDiv`, not `/` — see [gossipTokenBucket]. A clock that has not been set reads 1970. */
    @Test
    fun `buckets do not fold back across the epoch`() {
        assertEquals(-1L, gossipTokenBucket(Instant.ofEpochSecond(-1)))
        assertEquals(0L, gossipTokenBucket(Instant.ofEpochSecond(0)))
    }

    @Test
    fun `a key carrying the joining newline is refused rather than escaped`() {
        assertNull(gossipToken("mine\nsplit", theirs, 0))
        assertNull(gossipToken(mine, "theirs\nsplit", 0))
        assertNull(gossipToken("", theirs, 0))
    }

    @Test
    fun `a Contact's advertisement resolves to them and a stranger's to nobody`() {
        val table = gossipTokenTable(mine, listOf(theirs, stranger), now)
        val advertised = gossipToken(theirs, mine, gossipTokenBucket(now))

        assertEquals(theirs, gossipTokenOwner(table, advertised))
        assertNull(gossipTokenOwner(table, gossipToken("someone-else", mine, gossipTokenBucket(now))))
    }

    /** The neighbours are in the table so a clock a minute out is still recognised. */
    @Test
    fun `the table covers the bucket either side`() {
        val table = gossipTokenTable(mine, listOf(theirs), now)
        val bucket = gossipTokenBucket(now)

        assertEquals(theirs, gossipTokenOwner(table, gossipToken(theirs, mine, bucket - 1)))
        assertEquals(theirs, gossipTokenOwner(table, gossipToken(theirs, mine, bucket + 1)))
        assertNull(gossipTokenOwner(table, gossipToken(theirs, mine, bucket + 2)))
    }

    /** A short value that happens to be a prefix must not match — the advert is anyone's to write. */
    @Test
    fun `a token of the wrong length is not looked up`() {
        val table = gossipTokenTable(mine, listOf(theirs), now)
        val real = gossipToken(theirs, mine, gossipTokenBucket(now))!!

        assertNull(gossipTokenOwner(table, real.copyOf(GOSSIP_TOKEN_BYTES - 1)))
        assertNull(gossipTokenOwner(table, real + byteArrayOf(0)))
        assertNull(gossipTokenOwner(table, null))
    }

    @Test
    fun `a Followed line contributes nothing to the table`() {
        assertTrue(gossipTokenTable(mine, emptyList(), now).isEmpty())
    }

    @Test
    fun `the advertisement cycles through the Contacts one slot at a time`() {
        val contacts = listOf(theirs, stranger)
        val ordered = contacts.sorted()
        val first = gossipAdvertisedToken(mine, contacts, now)
        val next = gossipAdvertisedToken(mine, contacts, now.plus(GOSSIP_ADVERTISE_SLOT))

        assertNotEquals(gossipTokenHex(first!!), gossipTokenHex(next!!))
        val table = gossipTokenTable(mine, contacts, now)
        assertEquals(
            ordered.toSet(),
            setOf(gossipTokenOwner(table, first), gossipTokenOwner(table, next)),
        )
    }

    @Test
    fun `nobody to advertise to is nothing to advertise`() {
        assertNull(gossipAdvertisedToken(mine, emptyList(), now))
        assertNull(gossipAdvertisedToken(mine, listOf("", "  "), now))
    }
}
