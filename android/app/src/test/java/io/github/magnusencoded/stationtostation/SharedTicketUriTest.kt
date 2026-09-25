package io.github.magnusencoded.stationtostation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which uri a shared or opened ticket pdf is read from (#411, #514). Strings stand in
 * for `android.net.Uri`, and the actions are spelled out rather than read from
 * `Intent`, so this runs under plain JUnit.
 */
class SharedTicketUriTest {

    private val send = "android.intent.action.SEND"
    private val view = "android.intent.action.VIEW"
    private val pdf = "application/pdf"

    @Test
    fun `a share with EXTRA_STREAM uses the stream first`() {
        assertEquals("stream", sharedTicketUri(send, pdf, stream = "stream", data = "data", clip = "clip"))
    }

    @Test
    fun `a share with only data uses the data`() {
        assertEquals("data", sharedTicketUri<String>(send, pdf, stream = null, data = "data", clip = null))
    }

    @Test
    fun `a share with only clipData falls back to the first clip item`() {
        assertEquals("clip", sharedTicketUri<String>(send, pdf, stream = null, data = null, clip = "clip"))
    }

    @Test
    fun `a share carrying nothing yields nothing`() {
        assertNull(sharedTicketUri<String>(send, pdf, stream = null, data = null, clip = null))
    }

    @Test
    fun `anything but a pdf is ignored`() {
        assertNull(sharedTicketUri(send, "image/png", stream = "stream", data = "data", clip = "clip"))
        assertNull(sharedTicketUri<String>(view, null, stream = null, data = "data", clip = null))
    }

    @Test
    fun `open with reads only the data`() {
        assertEquals("data", sharedTicketUri<String>(view, pdf, stream = null, data = "data", clip = "clip"))
        assertNull(sharedTicketUri<String>(view, pdf, stream = null, data = null, clip = "clip"))
    }

    @Test
    fun `any other action is ignored`() {
        assertNull(sharedTicketUri(null, pdf, stream = "stream", data = "data", clip = "clip"))
    }
}
