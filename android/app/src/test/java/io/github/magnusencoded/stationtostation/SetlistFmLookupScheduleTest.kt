package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.StoredAttendance
import io.github.magnusencoded.stationtostation.data.StoredSetlistFmLookup
import io.github.magnusencoded.stationtostation.data.TimelineStore
import io.github.magnusencoded.stationtostation.data.parseFmDate
import io.github.magnusencoded.stationtostation.data.setlistfm.LOOKUP_FRICTION_MESSAGE
import io.github.magnusencoded.stationtostation.data.setlistfm.ManualLookup
import io.github.magnusencoded.stationtostation.data.setlistfm.manualSetlistFmLookup
import io.github.magnusencoded.stationtostation.data.setlistfm.setlistFmLookupDue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

/**
 * When a local **Gig** is next looked up on setlist.fm (#531), asserted case for case
 * against `fixtures/setlistfm-lookup/`, which `SetlistFmLookupScheduleTests.swift` reads
 * too. UTC throughout, as the fixtures' README says.
 */
class SetlistFmLookupScheduleTest {

    @Serializable
    private data class DueCase(
        val name: String,
        val night: String,
        val now: String,
        val lastLookupAt: String? = null,
        val participationUntil: String? = null,
        val chipPending: Boolean = false,
        val local: Boolean = true,
        val sharedKey: Boolean = true,
        val sharedQuotaSpentAt: String? = null,
        val expectDue: String?,
    )

    @Serializable
    private data class ManualCase(val name: String, val now: String, val lastLookupAt: String?, val expect: String)

    @Serializable
    private data class Cases<T>(val cases: List<T>)

    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): String {
        val dir = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "fixtures/setlistfm-lookup") }.first { it.isDirectory }
        return File(dir, name).readText()
    }

    private fun instant(text: String?) = text?.let(Instant::parse)

    @Test
    fun `every shared due case`() {
        val cases = json.decodeFromString<Cases<DueCase>>(fixture("due.json")).cases
        assertTrue("the fixture must not be empty", cases.size >= 20)
        cases.forEach { case ->
            val due = setlistFmLookupDue(
                night = parseFmDate(case.night),
                now = Instant.parse(case.now),
                zone = ZoneOffset.UTC,
                local = case.local,
                lastLookupAt = instant(case.lastLookupAt),
                participationUntil = instant(case.participationUntil),
                possibleMatchPending = case.chipPending,
                sharedKey = case.sharedKey,
                sharedQuotaSpentAt = instant(case.sharedQuotaSpentAt)?.toEpochMilli(),
            )
            assertEquals(case.name, instant(case.expectDue), due)
        }
    }

    @Test
    fun `every shared manual case`() {
        val cases = json.decodeFromString<Cases<ManualCase>>(fixture("manual.json")).cases
        assertTrue("the fixture must not be empty", cases.isNotEmpty())
        cases.forEach { case ->
            val answer = manualSetlistFmLookup(instant(case.lastLookupAt), Instant.parse(case.now))
            val expected = when (case.expect) {
                "lookUpNow" -> ManualLookup.LOOK_UP_NOW
                "friction" -> ManualLookup.FRICTION
                else -> error("unknown expectation ${case.expect} in ${case.name}")
            }
            assertEquals(case.name, expected, answer)
        }
    }

    /** The one Gig both halves are asked about: its automatic checks are over, a pull is not. */
    @Test
    fun `a Gig whose automatic checks have stopped can still be pulled`() {
        val last = Instant.parse("2026-10-09T08:00:00Z")
        val now = Instant.parse("2026-11-01T12:00:00Z")
        assertNull(
            setlistFmLookupDue(
                parseFmDate("25-09-2026"), now, ZoneOffset.UTC, local = true, lastLookupAt = last,
                participationUntil = null, possibleMatchPending = false, sharedKey = true, sharedQuotaSpentAt = null,
            ),
        )
        assertEquals(ManualLookup.LOOK_UP_NOW, manualSetlistFmLookup(last, now))
    }

    @Test
    fun `the friction words are the spec's`() {
        assertEquals("We'll keep checking for you", LOOKUP_FRICTION_MESSAGE)
    }

    @Test
    fun `a pending chip is the stored pending hits`() {
        assertEquals(false, StoredSetlistFmLookup().possibleMatchPending)
        assertEquals(true, StoredSetlistFmLookup(pendingHitIds = listOf("63de6d5b")).possibleMatchPending)
    }

    @Test
    fun `a lookup is stamped and keeps what was rejected`() {
        val before = StoredSetlistFmLookup(lastLookupAt = 1L, rejectedIds = listOf("r1"))
        assertEquals(StoredSetlistFmLookup(lastLookupAt = 42L, rejectedIds = listOf("r1")), before.lookedUp(42L))
    }

    @Test
    fun `none of these rejects every pending hit, and they are never offered again`() {
        val asked = StoredSetlistFmLookup(rejectedIds = listOf("r1"), pendingHitIds = listOf("h1", "h2"))
        val answered = asked.rejectingPending()
        assertEquals(listOf("r1", "h1", "h2"), answered.rejectedIds)
        assertEquals(emptyList<String>(), answered.pendingHitIds)
        assertEquals(false, answered.possibleMatchPending)
        assertEquals(listOf("h3"), answered.unrejected(listOf("h1", "h3", "r1", "h2")))
    }

    @Test
    fun `rejecting twice remembers a hit once`() {
        val asked = StoredSetlistFmLookup(rejectedIds = listOf("h1"), pendingHitIds = listOf("h1"))
        assertEquals(listOf("h1"), asked.rejectingPending().rejectedIds)
    }

    /** Written before #531: no `setlistFmLookup` key at all. It must read as never looked up. */
    @Test
    fun `an attendance record written before the lookup state decodes with none`() {
        val old = """{"provenance":"checked_in","checkedInAt":42}"""
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<StoredAttendance>(old)
        assertEquals(StoredAttendance(provenance = "checked_in", checkedInAt = 42), decoded)
        assertNull(decoded.setlistFmLookup)
    }

    @Test
    fun `the lookup state round-trips inside the attendance record`() {
        val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val attendance = StoredAttendance(
            provenance = StoredAttendance.Provenance.PLANNED,
            setlistFmLookup = StoredSetlistFmLookup(
                lastLookupAt = 1_790_000_000_000L,
                rejectedIds = listOf("63de6d5b"),
                pendingHitIds = listOf("4bd6af3a", "13d6a5b9"),
            ),
        )
        assertEquals(attendance, codec.decodeFromString<StoredAttendance>(codec.encodeToString(StoredAttendance.serializer(), attendance)))
    }

    /** Or "once a day" becomes "once a launch". */
    @Test
    fun `the last lookup survives a restart`() = runBlocking {
        val file = File.createTempFile("timelines", ".json").also { it.delete() }
        val lookup = StoredSetlistFmLookup(lastLookupAt = 42L, rejectedIds = listOf("r1"), pendingHitIds = listOf("h1"))
        TimelineStore(file).saveAttendance("a", StoredAttendance(setlistFmLookup = lookup))
        assertEquals(lookup, TimelineStore(file).load().attendance()["a"]?.setlistFmLookup)
    }

    /** iOS omits an absent field rather than writing its default, and Android must read that too. */
    @Test
    fun `a lookup state with fields missing decodes to their defaults`() {
        val partial = """{"provenance":"planned","setlistFmLookup":{"lastLookupAt":7}}"""
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<StoredAttendance>(partial)
        assertEquals(StoredSetlistFmLookup(lastLookupAt = 7), decoded.setlistFmLookup)
    }
}
