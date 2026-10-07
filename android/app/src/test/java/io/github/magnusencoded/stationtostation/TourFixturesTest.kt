package io.github.magnusencoded.stationtostation

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TourFixturesTest {
    @Test
    fun sharedCasesLoadWithEverySpecRowAndEverySkipStep() {
        val cases = TourFixtures.load()
        val ids = cases.map { it.getValue("id").jsonPrimitive.content }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(setOf("Happy path", "Offline start", "Skip at Sn", "Resume", "Replay",
            "Spotify declined", "Spotify retry", "Fill: setlist.fm hit", "Fill: fallback",
            "Out-of-order event"), cases.map { it.getValue("row").jsonPrimitive.content }.toSet())
        for (n in 1..20) assertTrue("skip-s$n", "skip-s$n" in ids)
        for (n in 1..19) {
            assertTrue("resume-s$n", "resume-s$n" in ids)
            assertTrue("out-of-order-s$n", "out-of-order-s$n" in ids)
        }
        for (case in cases) {
            val checks = case.getValue("checks").jsonArray
            assertTrue(checks.isNotEmpty())
            for (check in checks) {
                val expect = check.jsonObject.getValue("expect").jsonObject
                assertTrue(expect.containsKey("step"))
                expect.getValue("commands").jsonArray
            }
        }
    }
}
