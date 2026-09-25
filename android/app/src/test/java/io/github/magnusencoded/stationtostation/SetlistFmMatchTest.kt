package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.data.setlistfm.FmArtist
import io.github.magnusencoded.stationtostation.data.setlistfm.MatchLevel
import io.github.magnusencoded.stationtostation.data.setlistfm.SetlistFmMatch
import io.github.magnusencoded.stationtostation.data.setlistfm.SetlistsResponse
import io.github.magnusencoded.stationtostation.data.setlistfm.TicketLookup
import io.github.magnusencoded.stationtostation.data.setlistfm.fieldLevels
import io.github.magnusencoded.stationtostation.data.setlistfm.foldName
import io.github.magnusencoded.stationtostation.data.setlistfm.matchSetlistFm
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shared cases in `fixtures/setlistfm-match/` (#531), the same files iOS's
 * `SetlistFmMatchTests` reads. A case is required, never skipped: an empty directory
 * fails, because a matcher that agrees with iOS on nothing agrees on nothing.
 */
class SetlistFmMatchTest {

    @Serializable
    private data class Case(
        val note: String,
        val ticket: TicketFields,
        val lineArtists: List<FmArtist>,
        val response: SetlistsResponse,
        val levels: Map<String, Levels>,
        val expected: Expected,
    )

    @Serializable
    private data class TicketFields(val artist: String, val venue: String?, val date: String) {
        fun lookup() = TicketLookup(artist = artist, venue = venue, date = date)
    }

    @Serializable
    private data class Levels(val artist: String, val date: String, val venue: String?)

    @Serializable
    private data class Expected(val outcome: String, val ids: List<String>)

    private val json = Json { ignoreUnknownKeys = true }

    private fun cases(): List<Pair<String, Case>> {
        val dir = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "fixtures/setlistfm-match") }.first { it.isDirectory }
        return dir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
            .map { it.nameWithoutExtension to json.decodeFromString<Case>(it.readText()) }
    }

    private fun MatchLevel?.wire(): String? = this?.name?.lowercase()

    @Test fun `every shared case gives its levels`() {
        val all = cases()
        assertTrue("no cases in fixtures/setlistfm-match", all.isNotEmpty())
        for ((name, case) in all) {
            assertEquals("$name: a level for every hit", case.response.setlist.map { it.id }.toSet(), case.levels.keys)
            for (hit in case.response.setlist) {
                val got = fieldLevels(case.ticket.lookup(), hit, case.lineArtists)
                val want = case.levels.getValue(hit.id)
                assertEquals("$name/${hit.id} artist", want.artist, got.artist.wire())
                assertEquals("$name/${hit.id} date", want.date, got.date.wire())
                assertEquals("$name/${hit.id} venue", want.venue, got.venue.wire())
            }
        }
    }

    @Test fun `every shared case gives its outcome`() {
        for ((name, case) in cases()) {
            val (outcome, ids) = when (val m = matchSetlistFm(case.ticket.lookup(), case.response.setlist, case.lineArtists)) {
                is SetlistFmMatch.Linked -> "linked" to listOf(m.candidate.setlist.id)
                is SetlistFmMatch.Ask -> "ask" to m.candidates.map { it.setlist.id }
                SetlistFmMatch.NoMatch -> "noMatch" to emptyList()
            }
            assertEquals("$name: ${case.note}", case.expected.outcome, outcome)
            assertEquals("$name: ${case.note}", case.expected.ids, ids)
        }
    }

    // --- The fold, which is iOS's `foldName` term for term ---

    @Test fun `the fold takes off case, accents and the Nordic letters NFD leaves alone`() {
        assertEquals("royksopp", foldName("RØYKSOPP"))
        assertEquals("royksopp", foldName("Röyksopp"))
        assertEquals("baerum", foldName("Bærum"))
        assertEquals("sigurros", foldName("Sigur Rós"))
        assertEquals("gunsnroses", foldName("Guns N' Roses"))
    }
}
