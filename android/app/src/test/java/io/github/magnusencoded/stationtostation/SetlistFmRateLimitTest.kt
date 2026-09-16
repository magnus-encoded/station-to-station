package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.setlistfm.SHARED_QUOTA_MEMORY_MS
import io.github.magnusencoded.stationtostation.data.setlistfm.sharedQuotaSpent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether the shared setlist.fm quota still counts as spent
 * (#457). A pure function of a stored instant and now — no clock, no storage, and the
 * one thing both platforms have to agree on.
 */
class SetlistFmRateLimitTest {

    private val noon = 1_758_000_000_000L

    @Test
    fun `nothing recorded means the quota is not spent`() {
        assertFalse(sharedQuotaSpent(null, noon))
    }

    @Test
    fun `just recorded means spent`() {
        assertTrue(sharedQuotaSpent(noon, noon))
    }

    @Test
    fun `still spent a moment before the hour is up`() {
        assertTrue(sharedQuotaSpent(noon, noon + SHARED_QUOTA_MEMORY_MS - 1))
    }

    @Test
    fun `expired the moment the hour is up`() {
        assertFalse(sharedQuotaSpent(noon, noon + SHARED_QUOTA_MEMORY_MS))
    }

    @Test
    fun `expired long after`() {
        assertFalse(sharedQuotaSpent(noon, noon + 5 * SHARED_QUOTA_MEMORY_MS))
    }

    /**
     * A clock that moved back leaves a timestamp in the future. Believing it would shut
     * setlist.fm off for as long as the clock stays wrong; disbelieving it costs one
     * request.
     */
    @Test
    fun `a timestamp in the future is expired, not spent forever`() {
        assertFalse(sharedQuotaSpent(noon + SHARED_QUOTA_MEMORY_MS, noon))
        assertFalse(sharedQuotaSpent(noon + 400L * SHARED_QUOTA_MEMORY_MS, noon))
    }
}
