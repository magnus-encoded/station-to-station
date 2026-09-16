package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.setlistfm.SHARED_QUOTA_MEMORY_MS
import io.github.magnusencoded.stationtostation.data.setlistfm.SetlistFmClient
import io.github.magnusencoded.stationtostation.data.setlistfm.SetlistFmKey
import io.github.magnusencoded.stationtostation.data.setlistfm.SetlistFmRateLimited
import io.github.magnusencoded.stationtostation.data.setlistfm.SetlistFmResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * What the client returns and throws, over a fake transport (#457).
 *
 * Nothing here knows how the retry loop is written — only how many requests reached
 * setlist.fm and what came back out of the client, which is what the rest of the app
 * and the user actually see. Invented data throughout; the repository is public.
 */
class SetlistFmClientTest {

    private val shared = SetlistFmKey("bundled-key", shared = true)
    private val own = SetlistFmKey("my-own-key", shared = false)

    private val oneShow = """{"total":1,"setlist":[{"id":"abc1234"}]}"""

    /** Answers the given statuses in order, and records every request it was given. */
    private class FakeTransport(private val statuses: List<Int>, private val body: String) {
        val keysSent = mutableListOf<String>()
        val requests: Int get() = keysSent.size

        suspend fun send(url: String, apiKey: String): SetlistFmResponse {
            keysSent += apiKey
            val status = statuses.getOrElse(requests - 1) { statuses.last() }
            return SetlistFmResponse(status, if (status in 200..299) body else "")
        }
    }

    /** A client with no clock and no sleeping, so the tests run at full speed. */
    private fun client(
        key: SetlistFmKey?,
        transport: FakeTransport,
        spentAt: Long? = null,
        now: Long = 1_000_000L,
        recorded: MutableList<Long> = mutableListOf(),
    ) = SetlistFmClient(
        keySource = { key },
        sharedQuotaSpentAt = { spentAt },
        recordSharedQuotaSpent = { recorded += it },
        now = { now },
        transport = transport::send,
        sleep = {},
    )

    @Test
    fun `a 200 is decoded, and costs one request`() = runBlocking {
        val transport = FakeTransport(listOf(200), oneShow)
        val resp = client(shared, transport).userAttended("someone")
        assertEquals(1, resp.total)
        assertEquals(1, transport.requests)
    }

    @Test
    fun `a burst is retried once and carries on`() = runBlocking {
        val transport = FakeTransport(listOf(429, 200), oneShow)
        val resp = client(shared, transport).userAttended("someone")
        assertEquals(1, resp.total)
        assertEquals(2, transport.requests)
    }

    @Test
    fun `two 429s on the shared key is the shared-key error, after two requests`() = runBlocking {
        val transport = FakeTransport(listOf(429, 429), oneShow)
        val recorded = mutableListOf<Long>()
        val e = failing { client(shared, transport, recorded = recorded, now = 555L).userAttended("someone") }
        assertTrue(e is SetlistFmRateLimited && e.sharedKey)
        assertEquals(2, transport.requests)
        assertEquals(listOf(555L), recorded)
    }

    @Test
    fun `two 429s on my own key is the own-key error, and records nothing`() = runBlocking {
        val transport = FakeTransport(listOf(429, 429), oneShow)
        val recorded = mutableListOf<Long>()
        val e = failing { client(own, transport, recorded = recorded).searchArtists("Whoever") }
        assertTrue(e is SetlistFmRateLimited && !e.sharedKey)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun `the shared key fails without a request while the quota is spent`() = runBlocking {
        val transport = FakeTransport(listOf(200), oneShow)
        val e = failing {
            client(shared, transport, spentAt = 1_000_000L, now = 1_000_001L).userAttended("someone")
        }
        assertTrue(e is SetlistFmRateLimited && e.sharedKey)
        assertEquals(0, transport.requests)
    }

    @Test
    fun `my own key is sent normally while the shared quota is spent`() = runBlocking {
        val transport = FakeTransport(listOf(200), oneShow)
        val resp = client(own, transport, spentAt = 1_000_000L, now = 1_000_001L).userAttended("me")
        assertEquals(1, resp.total)
        assertEquals(listOf("my-own-key"), transport.keysSent)
    }

    @Test
    fun `after the hour the shared key probes, and a 200 leaves the memory expired`() = runBlocking {
        val transport = FakeTransport(listOf(200), oneShow)
        val recorded = mutableListOf<Long>()
        val resp = client(
            shared,
            transport,
            spentAt = 1_000_000L,
            now = 1_000_000L + SHARED_QUOTA_MEMORY_MS,
            recorded = recorded,
        ).userAttended("someone")
        assertEquals(1, resp.total)
        assertEquals(1, transport.requests)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun `after the hour a refused probe records a new instant`() = runBlocking {
        val transport = FakeTransport(listOf(429, 429), oneShow)
        val recorded = mutableListOf<Long>()
        val later = 1_000_000L + SHARED_QUOTA_MEMORY_MS
        failing {
            client(shared, transport, spentAt = 1_000_000L, now = later, recorded = recorded)
                .userAttended("someone")
        }
        assertEquals(2, transport.requests)
        assertEquals(listOf(later), recorded)
    }

    @Test
    fun `5xx is still ridden out with backoff`() = runBlocking {
        val transport = FakeTransport(listOf(503, 503, 200), oneShow)
        val resp = client(shared, transport).userAttended("someone")
        assertEquals(1, resp.total)
        assertEquals(3, transport.requests)
    }

    @Test
    fun `5xx all the way is unavailable, not rate-limited`() = runBlocking {
        val transport = FakeTransport(listOf(503), oneShow)
        val e = failing { client(shared, transport).userAttended("someone") }
        assertTrue(e !is SetlistFmRateLimited)
        assertEquals(3, transport.requests)
    }

    @Test
    fun `403 still says the key was rejected`() = runBlocking {
        val transport = FakeTransport(listOf(403), oneShow)
        val e = failing { client(shared, transport).userAttended("someone") }
        assertTrue(e !is SetlistFmRateLimited)
        assertTrue(e!!.message!!.contains("403"))
        assertEquals(1, transport.requests)
    }

    /** An attended list with no shows in it is still an answer, not an error. */
    @Test
    fun `404 on an attended list is still empty, not missing`() = runBlocking {
        val transport = FakeTransport(listOf(404), oneShow)
        val resp = client(shared, transport).userAttended("brand-new")
        assertEquals(0, resp.total)
    }

    @Test
    fun `404 elsewhere is still not found`() = runBlocking {
        val transport = FakeTransport(listOf(404), oneShow)
        val e = failing { client(shared, transport).searchArtists("Nobody") }
        assertTrue(e!!.message!!.contains("404"))
    }

    @Test
    fun `no key at all still says to configure one`() = runBlocking {
        val transport = FakeTransport(listOf(200), oneShow)
        val e = failing { client(null, transport).userAttended("someone") }
        assertTrue(e !is SetlistFmRateLimited)
        assertTrue(e!!.message!!.contains("Settings"))
        assertEquals(0, transport.requests)
    }

    @Test
    fun `the messages say the right thing about whose quota it is`() {
        assertTrue(SetlistFmRateLimited(sharedKey = true).message!!.contains("your own"))
        assertTrue(SetlistFmRateLimited(sharedKey = false).message!!.contains("your API key"))
        // Never a time: setlist.fm does not define its day, so any hour named is a guess.
        assertNull(Regex("""\b\d{1,2}(:\d\d)?\s?(am|pm)\b""", RegexOption.IGNORE_CASE)
            .find(SetlistFmRateLimited(sharedKey = true).message!!))
    }

    private inline fun failing(body: () -> Unit): IOException? =
        try {
            body()
            null
        } catch (e: IOException) {
            e
        }
}
