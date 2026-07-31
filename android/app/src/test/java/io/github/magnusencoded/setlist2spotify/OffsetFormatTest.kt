package io.github.magnusencoded.setlist2spotify

import io.github.magnusencoded.setlist2spotify.ui.formatOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class OffsetFormatTest {

    @Test
    fun `under an hour reads as minutes and seconds`() {
        assertEquals("0:00", formatOffset(0L))
        assertEquals("3:34", formatOffset(214_000L))
        assertEquals("59:59", formatOffset(3_599_000L))
    }

    @Test
    fun `a full gig past the hour grows an hours field`() {
        assertEquals("1:00:00", formatOffset(3_600_000L))
        assertEquals("1:12:05", formatOffset(4_325_000L))
    }

    @Test
    fun `sub-second remainders round down rather than tipping the minute`() {
        assertEquals("0:59", formatOffset(59_999L))
    }
}
