package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.Festivals
import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.data.StoredFestival
import io.github.magnusencoded.stationtostation.data.TimelineCache
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.ui.Spine
import io.github.magnusencoded.stationtostation.ui.TimelineNode
import io.github.magnusencoded.stationtostation.ui.WovenRow
import io.github.magnusencoded.stationtostation.ui.hostLane
import io.github.magnusencoded.stationtostation.ui.weaveTimelines
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The fixtures in `fixtures/weave/` are the contract between the two platforms: the
 * same store document must produce the same spine in Kotlin and in Swift. They live
 * outside `android/` because neither platform owns them.
 *
 * Adding a case is adding a directory — this test iterates, it does not enumerate.
 */
class WeaveFixturesTest {

    /** A fixture's `timelines.json`: a [TimelineCache] plus who I am and the lane order. */
    @Serializable
    private data class Fixture(
        val me: String = "",
        /** Lane order, nearest the spine first — the device's friends list, reversed. */
        val friends: List<Friend> = emptyList(),
        val shows: Map<String, List<FmSetlist>> = emptyMap(),
        val festivals: Map<String, StoredFestival> = emptyMap(),
        val festivalIdByShow: Map<String, String> = emptyMap(),
    )

    @Serializable
    private data class Expected(val rows: List<Row> = emptyList())

    /**
     * One row of the woven spine, as data. Ownership is the vocabulary's, not a field
     * name's: **together** means a gig on both lists, so a festival that merely
     * **absorbs** a friend's cluster is `mine`.
     */
    @Serializable
    private data class Row(
        val key: String,
        val date: String? = null,
        val node: String,
        val title: String,
        val ownership: String,
        val with: List<String> = emptyList(),
        val together: Int = 0,
        val theirs: Int = 0,
        /** Which line each friend is drawn on here: `spine`, or `laneN` counting from 1. */
        val hosts: Map<String, String> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Walk up from the module dir: the fixtures sit at the repo root, outside android/. */
    private fun fixturesDir(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "fixtures/weave") }
            .firstOrNull { it.isDirectory }
            ?: error("fixtures/weave not found above ${File("").absolutePath}")

    private fun row(r: WovenRow, lanes: List<Friend>) = Row(
        key = r.key,
        date = r.date?.toString(),
        node = when (r.node) {
            is TimelineNode.Festival -> "festival"
            is TimelineNode.Section -> "section"
            is TimelineNode.Concert -> "gig"
        },
        title = when (val n = r.node) {
            is TimelineNode.Several -> n.label
            is TimelineNode.Concert -> n.setlist.artist?.name.orEmpty()
        },
        ownership = when {
            !r.mine -> "theirs"
            r.sharedCount > 0 -> "together"
            else -> "mine"
        },
        with = r.others.map { it.setlistfm },
        together = r.sharedCount,
        theirs = r.showsHereByFriends.size,
        hosts = lanes.associate { f ->
            val lane = hostLane(r, f, lanes)
            f.setlistfm to if (lane == Spine) "spine" else "lane${lane + 1}"
        },
    )

    @Test
    fun `every fixture weaves to its expected rows`() {
        val cases = fixturesDir().listFiles().orEmpty()
            .filter { File(it, "timelines.json").isFile }
            .sortedBy { it.name }
        // Iterating an empty directory would pass silently and prove nothing.
        assertTrue("no fixtures found in ${fixturesDir()}", cases.isNotEmpty())

        for (case in cases) {
            val text = File(case, "timelines.json").readText()
            // The fixture is a real store document: `me` and `friends` are extra keys
            // the store ignores, not a format of their own.
            json.decodeFromString<TimelineCache>(text)

            val fixture = json.decodeFromString<Fixture>(text)
            val expected = json.decodeFromString<Expected>(File(case, "expected.json").readText())
            val rows = weaveTimelines(
                mine = fixture.shows[fixture.me].orEmpty(),
                festivals = Festivals(fixture.festivals, fixture.festivalIdByShow),
                friends = fixture.friends,
                theirs = fixture.shows - fixture.me,
            )
            assertEquals(case.name, expected.rows, rows.map { row(it, fixture.friends) })
        }
    }
}
