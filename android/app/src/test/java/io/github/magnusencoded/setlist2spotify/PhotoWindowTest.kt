package io.github.magnusencoded.setlist2spotify

import io.github.magnusencoded.setlist2spotify.data.photos.capturedAtMs
import io.github.magnusencoded.setlist2spotify.data.photos.isInPhotoWindow
import io.github.magnusencoded.setlist2spotify.data.photos.photoWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * #99's portable half: which photos belong to a night, and what a keepsake's
 * capture time is when the file carries none.
 *
 * Every number here is also asserted in the iOS `PhotoWindowTests`, against the
 * same fixed values rather than against this file — so neither platform can drift
 * by agreeing with itself. The picking, the permission prompt and the grid are not
 * asserted: they need a device.
 *
 * UTC throughout, because the assertions are epoch millis and a runner's zone is
 * not a fact about the domain.
 */
class PhotoWindowTest {

    private val utc = ZoneId.of("UTC")
    private val gigDate = LocalDate.of(2026, 8, 4)

    /** 2026-08-04 00:00:00 UTC — the gig's date, as setlist.fm dates it. */
    private val dayStart = 1_785_801_600_000L

    /** 2026-08-05 06:00:00 UTC — the small hours after it. */
    private val windowEnd = 1_785_909_600_000L

    private val window = photoWindow(gigDate, utc)

    // --- The window ---

    @Test
    fun `the window is the gig's day plus the small hours after it`() {
        assertEquals(dayStart, window.first)
        assertEquals(windowEnd, window.last)
    }

    @Test
    fun `a show at eleven at night is photographed on the following calendar day`() {
        // The whole reason the window is not "that calendar day": setlist.fm dates
        // a show by when it started, and a 23:00 set is shot after midnight.
        val halfPastMidnight = dayStart + 86_400_000L + 1_800_000L
        assertTrue(isInPhotoWindow(window, taken = halfPastMidnight, added = null))
    }

    @Test
    fun `a photo just outside the window is excluded`() {
        assertFalse(isInPhotoWindow(window, taken = dayStart - 1, added = null))
        assertFalse(isInPhotoWindow(window, taken = windowEnd + 1, added = null))
        // Both ends are inclusive.
        assertTrue(isInPhotoWindow(window, taken = dayStart, added = null))
        assertTrue(isInPhotoWindow(window, taken = windowEnd, added = null))
    }

    // --- The absent-timestamp fallback ---

    @Test
    fun `a photo carrying no timestamp falls back to when the library saw it`() {
        val duringTheNight = dayStart + 86_400_000L + 1_800_000L
        assertEquals(duringTheNight, capturedAtMs(taken = null, added = duringTheNight)!!)
        assertTrue(isInPhotoWindow(window, taken = null, added = duringTheNight))
    }

    @Test
    fun `the camera's own stamp wins when it has one`() {
        assertEquals(1000L, capturedAtMs(taken = 1000, added = 2000)!!)
    }

    @Test
    fun `a zero stamp counts as absent rather than as nineteen seventy`() {
        assertEquals(2000L, capturedAtMs(taken = 0, added = 2000)!!)
        assertNull(capturedAtMs(taken = 0, added = 0))
    }

    @Test
    fun `a photo that answers neither is not from that night`() {
        // A wrong timestamp on a keepsake is worse than an honest gap.
        assertNull(capturedAtMs(taken = null, added = null))
        assertFalse(isInPhotoWindow(window, taken = null, added = null))
    }
}
