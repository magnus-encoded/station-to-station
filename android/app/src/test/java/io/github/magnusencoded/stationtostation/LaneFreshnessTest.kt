package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.data.holdLanes
import io.github.magnusencoded.stationtostation.data.laneNeedsFetch
import io.github.magnusencoded.stationtostation.data.parseFmDate
import io.github.magnusencoded.stationtostation.data.setlistfm.FmArtist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Whether a **Contact**'s **Lane** needs a setlist.fm fetch (#405).
 *
 * `fixtures/lane-freshness/cases.json` is shared with iOS's `LaneFreshnessTests`, which
 * asserts it case for case; so are the two-pass cases below, which no single call to
 * [laneNeedsFetch] can reach. Everything here is synthetic.
 */
class LaneFreshnessTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** Walk up from the module dir: the fixtures sit at the repo root, outside android/. */
    private fun fixture(path: String): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "fixtures/$path") }
            .firstOrNull { it.exists() }
            ?: error("fixtures/$path not found above ${File("").absolutePath}")

    @Serializable
    private data class Case(
        val name: String,
        val username: String,
        val held: List<String>? = null,
        val myOldest: String? = null,
        val fetch: Boolean,
    )

    @Serializable
    private data class Cases(val cases: List<Case>)

    private fun night(date: String, id: String = "n-$date") =
        FmSetlist(id = id, eventDate = date, artist = FmArtist(name = "The Warning"))

    @Test
    fun `every case says what the fixture says`() {
        val cases = json.decodeFromString<Cases>(fixture("lane-freshness/cases.json").readText()).cases
        assertTrue("fixtures/lane-freshness/cases.json is empty", cases.isNotEmpty())
        cases.forEach { case ->
            val got = laneNeedsFetch(
                Friend(setlistfm = case.username),
                case.held?.map { night(it) },
                case.myOldest?.let { parseFmDate(it) ?: error("${case.name}: bad myOldest") },
            )
            assertEquals(case.name, case.fetch, got)
        }
        println("LaneFreshnessTest: ${cases.size} fixture cases")
    }

    private val ozzy = Friend(setlistfm = "ozzy")
    private val myOldest = parseFmDate("25-06-2019")

    @Test
    fun `a contact with no nights is not fetched on a second pass`() {
        var held = emptyMap<String, List<FmSetlist>>()
        assertTrue("first pass", laneNeedsFetch(ozzy, held[ozzy.setlistfm], myOldest))
        // setlist.fm answers: a real user with no attended shows.
        held = holdLanes(held, mapOf(ozzy.setlistfm to emptyList()))
        assertFalse("second pass", laneNeedsFetch(ozzy, held[ozzy.setlistfm], myOldest))
    }

    @Test
    fun `a failed fetch is asked again on the next pass`() {
        // A failure is left out of what landed, so nothing is held for them yet.
        val held = holdLanes(emptyMap(), emptyMap())
        assertTrue(laneNeedsFetch(ozzy, held[ozzy.setlistfm], myOldest))
    }

    @Test
    fun `an empty answer keeps a lane that had nights`() {
        val had = mapOf(ozzy.setlistfm to listOf(night("20-06-2019")))
        val held = holdLanes(had, mapOf(ozzy.setlistfm to emptyList()))
        assertEquals(listOf("n-20-06-2019"), held[ozzy.setlistfm]?.map { it.id })
    }

    @Test
    fun `a fetched lane replaces the one held and leaves the others alone`() {
        val had = mapOf(
            ozzy.setlistfm to listOf(night("10-01-2020")),
            "magnus" to listOf(night("01-01-2026")),
        )
        val held = holdLanes(had, mapOf(ozzy.setlistfm to listOf(night("20-06-2019"))))
        assertEquals(listOf("n-20-06-2019"), held[ozzy.setlistfm]?.map { it.id })
        assertEquals(listOf("n-01-01-2026"), held["magnus"]?.map { it.id })
        assertFalse(laneNeedsFetch(ozzy, held[ozzy.setlistfm], myOldest))
    }
}
