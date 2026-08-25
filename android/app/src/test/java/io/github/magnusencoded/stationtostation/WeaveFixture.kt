package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.Festivals
import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.data.StoredFestival
import io.github.magnusencoded.stationtostation.ui.WovenRow
import io.github.magnusencoded.stationtostation.ui.visibleLanes
import io.github.magnusencoded.stationtostation.ui.weaveTimelines
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Loads one case from `fixtures/weave/` — the cross-platform contract described in
 * that directory's README — as the rows it weaves to, plus its lane order.
 *
 * [WeaveFixturesTest] iterates every case and asserts the whole model; this is for
 * suites that want *one* named night to ask a narrower question of, which is what the
 * geometry assertions need.
 */
internal object WeaveFixture {

    @Serializable
    private data class Doc(
        val me: String = "",
        val friends: List<Friend> = emptyList(),
        val shows: Map<String, List<FmSetlist>> = emptyMap(),
        val festivals: Map<String, StoredFestival> = emptyMap(),
        val festivalIdByShow: Map<String, String> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Walk up from the module dir: the fixtures sit at the repo root, outside android/. */
    private fun dir(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "fixtures/weave") }
            .firstOrNull { it.isDirectory }
            ?: error("fixtures/weave not found above ${File("").absolutePath}")

    /**
     * [hide] is the **Timelines** filter (#266) applied where the app applies it: a
     * shorter friend list into the weave, and nothing else. What comes back is what a
     * timeline with those people tapped out of the legend actually holds.
     */
    fun load(case: String, hide: Set<String> = emptySet()): Pair<List<WovenRow>, List<Friend>> {
        val doc = json.decodeFromString<Doc>(File(dir(), "$case/timelines.json").readText())
        val friends = visibleLanes(doc.friends, hide)
        val rows = weaveTimelines(
            mine = doc.shows[doc.me].orEmpty(),
            festivals = Festivals(doc.festivals, doc.festivalIdByShow),
            friends = friends,
            theirs = doc.shows - doc.me,
        )
        return rows to friends
    }
}
