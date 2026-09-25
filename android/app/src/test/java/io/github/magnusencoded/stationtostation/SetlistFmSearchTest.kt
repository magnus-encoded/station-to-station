package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.setlistfm.SetlistFmClient
import io.github.magnusencoded.stationtostation.data.setlistfm.SetlistFmKey
import io.github.magnusencoded.stationtostation.data.setlistfm.SetlistFmResponse
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `search/setlists`, the lookup a **Ticket** makes (#531), over a fake transport: what
 * reaches setlist.fm and what comes back. Invented data; the mirror of iOS's
 * `SetlistFmSearchTests`.
 */
class SetlistFmSearchTest {

    private class Recording(private val status: Int, private val body: String) {
        val urls = mutableListOf<String>()
        suspend fun send(url: String, apiKey: String): SetlistFmResponse {
            urls += url
            return SetlistFmResponse(status, body)
        }
    }

    private fun client(transport: Recording) = SetlistFmClient(
        keySource = { SetlistFmKey("my-own-key", shared = false) },
        transport = transport::send,
        sleep = {},
    )

    private val twoHits =
        """{"total":2,"setlist":[{"id":"1a2b3c01","eventDate":"12-10-2024"},{"id":"1a2b3c02"}]}"""

    @Test fun `artist and date are sent as setlist fm names them, with no venue unless asked`() = runBlocking {
        val transport = Recording(200, twoHits)
        val resp = client(transport).searchSetlists("Guns N' Roses", "07-07-2023")
        assertEquals(listOf("1a2b3c01", "1a2b3c02"), resp.setlist.map { it.id })
        val url = transport.urls.single().toHttpUrl()
        assertEquals("/rest/1.0/search/setlists", url.encodedPath)
        assertEquals("Guns N' Roses", url.queryParameter("artistName"))
        assertEquals("07-07-2023", url.queryParameter("date"))
        assertEquals("1", url.queryParameter("p"))
        assertNull(url.queryParameter("venueName"))
    }

    @Test fun `a venue narrows the search when given`() = runBlocking {
        val transport = Recording(200, twoHits)
        client(transport).searchSetlists("Motorpsycho", "12-10-2024", venueName = "Rockefeller Music Hall")
        assertEquals("Rockefeller Music Hall", transport.urls.single().toHttpUrl().queryParameter("venueName"))
    }

    @Test fun `a search that finds nothing is no hits, not an error`() = runBlocking {
        val resp = client(Recording(404, "")).searchSetlists("Motorpsycho", "12-10-2030")
        assertEquals(0, resp.total)
        assertEquals(emptyList<String>(), resp.setlist.map { it.id })
    }
}
