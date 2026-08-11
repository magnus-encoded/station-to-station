package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.ui.WovenRow
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
        val festivalNames: Map<String, String> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Walk up from the module dir: the fixtures sit at the repo root, outside android/. */
    private fun dir(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "fixtures/weave") }
            .firstOrNull { it.isDirectory }
            ?: error("fixtures/weave not found above ${File("").absolutePath}")

    fun load(case: String): Pair<List<WovenRow>, List<Friend>> {
        val doc = json.decodeFromString<Doc>(File(dir(), "$case/timelines.json").readText())
        val rows = weaveTimelines(
            mine = doc.shows[doc.me].orEmpty(),
            festivalNames = doc.festivalNames,
            friends = doc.friends,
            theirs = doc.shows - doc.me,
        )
        return rows to doc.friends
    }
}
